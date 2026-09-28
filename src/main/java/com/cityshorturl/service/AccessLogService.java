package com.cityshorturl.service;

import com.cityshorturl.entity.AccessLog;
import com.cityshorturl.mapper.AccessLogMapper;
import com.cityshorturl.mq.AccessLogMessage;
import com.cityshorturl.mq.AccessLogProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 访问日志服务：<b>分发写入模式</b> + <b>唯一的落库实现</b>。
 *
 * <h3>三种模式（Step 7 的对照实验开关）</h3>
 * <table border="1">
 *   <tr><th>mode</th><th>行为</th><th>用途</th></tr>
 *   <tr><td>{@code MQ}</td><td>asyncSend 到 RocketMQ，消费者异步落库</td>
 *       <td>默认。请求线程不碰 DB</td></tr>
 *   <tr><td>{@code SYNC}</td><td>请求线程直接 INSERT</td>
 *       <td>Step 7 之前的基线；压测对照用</td></tr>
 *   <tr><td>{@code OFF}</td><td>什么都不做</td>
 *       <td>测「记日志本身」的净开销下界</td></tr>
 * </table>
 *
 * <p>为什么三种模式共用同一个 {@link #persist(AccessLogMessage)}：
 * 如果 SYNC 和 MQ 各写一份 INSERT，两边字段映射稍有出入，
 * 对照出来的数字就不是「同步 vs 异步」的差异，而是「两份代码」的差异 ——
 * <b>对照实验要求唯一变量</b>。
 *
 * <h3>为什么这里的 insert 不加 {@code @Transactional}</h3>
 * 单条 INSERT 本身就是原子的，套事务只会多一次 BEGIN/COMMIT 往返
 * （对高写入量的日志表是纯损耗）。而且消费者里没有别的写操作需要和它同事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessLogService {

    /** 写入模式。取不到或写错时回落到 MQ（安全默认值） */
    public enum Mode { MQ, SYNC, OFF }

    /** user_agent / referer 列宽 255，超长必须截断，否则 MySQL 严格模式直接报错 */
    private static final int UA_MAX = 255;
    private static final int REFERER_MAX = 255;

    private final AccessLogProducer accessLogProducer;
    private final AccessLogMapper accessLogMapper;

    @Value("${app.access-log.mode:MQ}")
    private String mode;

    /** 当前生效模式。健康检查接口读它做「开关自证」 */
    public Mode currentMode() {
        try {
            return Mode.valueOf(mode.trim().toUpperCase());
        } catch (Exception e) {
            log.warn("app.access-log.mode 取值非法（{}），回落为 MQ", mode);
            return Mode.MQ;
        }
    }

    /**
     * 记录一次<b>成功</b>跳转。由请求线程调用。
     *
     * <p><b>本方法不抛异常</b>：记日志失败绝不能变成用户跳转失败。
     * 404 的短码不该走到这里 —— PV 统计的是有效跳转。
     */
    public void record(AccessLogMessage msg) {
        switch (currentMode()) {
            case OFF -> log.debug("访问日志已关闭，跳过: {}", msg.getShortCode());
            case SYNC -> {
                try {
                    persist(msg);
                } catch (Exception e) {
                    // 与 MQ 模式行为对齐：失败只记日志（fail-open）
                    log.warn("访问日志同步落库失败（不影响跳转）: shortCode={}, err={}",
                            msg.getShortCode(), e.getMessage());
                }
            }
            case MQ -> accessLogProducer.sendAsync(msg);
        }
    }

    /**
     * 真正落库。消费者（异步）与 {@link #record} 的 SYNC 分支（同步）共用。
     *
     * <p>本方法<b>会抛异常</b>：消费者需要靠异常触发 MQ 重试，
     * 所以不能在这里吞掉。SYNC 分支在调用处自己兜。
     */
    public void persist(AccessLogMessage msg) {
        AccessLog entity = new AccessLog();
        entity.setShortCode(msg.getShortCode());
        entity.setUserId(msg.getUserId());
        entity.setIp(msg.getIp());
        entity.setUserAgent(truncate(msg.getUserAgent(), UA_MAX));
        entity.setReferer(truncate(msg.getReferer(), REFERER_MAX));
        // 用消息里带的访问时刻，而不是 now()：消费者可能落后生产者若干秒，
        // 用 now() 会让统计曲线整体右移，还会把跨天的访问记到错误的那一天
        entity.setAccessTime(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(msg.getAccessTimeMillis()), ZoneId.systemDefault()));
        accessLogMapper.insert(entity);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
