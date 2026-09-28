package com.cityshorturl.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * 访问日志生产者。
 *
 * <h3>🔴 为什么用 {@code asyncSend} 而不是项目 A 的 {@code syncSend}</h3>
 * 两个项目的取舍不同，因为<b>消息的价值不同</b>：
 * <table border="1">
 *   <tr><th></th><th>项目 A（AI 出题任务）</th><th>项目 B（访问日志）</th></tr>
 *   <tr><td>丢了会怎样</td><td>用户永远等不到题目，是<b>功能事故</b></td>
 *       <td>少一条统计记录，用户<b>完全无感</b></td></tr>
 *   <tr><td>发送方式</td><td>{@code syncSend}：必须拿到 broker 的 ack 才继续</td>
 *       <td>{@code asyncSend}：交给客户端内部线程发送，回调只用来记日志</td></tr>
 * </table>
 * <p>代价是 asyncSend 拿不到「发送成功」的同步保证；对日志来说这个代价可以接受。
 * <b>判据：消息丢了是「功能事故」还是「统计误差」。</b>
 *
 * <h3>失败策略：fail-open</h3>
 * 投递失败<b>绝不能让跳转失败</b>。所以这里把异常全部吞掉，只打 warn 日志。
 * 注意 asyncSend 有两种失败形态，两条路都得兜住：
 * <ol>
 *   <li><b>同步抛异常</b>：topic 路由信息都查不到（broker 刚挂、namesrv 也连不上）时，
 *       {@code asyncSend} 会在<b>调用线程里</b>直接抛 {@code MQClientException}。</li>
 *   <li><b>异步回调失败</b>：路由信息还在缓存里，消息发出去了但 broker 没接住，
 *       这时是 {@link SendCallback#onException} 被回调。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessLogProducer {

    /** 访问日志 topic。命名带业务前缀，方便在 broker 上一眼看出归属 */
    public static final String TOPIC = "short-url-access-log-topic";

    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 异步投递一条访问日志。<b>本方法不抛异常</b>，调用方（跳转主链路）无需 try-catch。
     */
    public void sendAsync(AccessLogMessage msg) {
        try {
            rocketMQTemplate.asyncSend(TOPIC, msg, new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    log.debug("访问日志已投递: shortCode={}, msgId={}",
                            msg.getShortCode(), sendResult.getMsgId());
                }

                @Override
                public void onException(Throwable e) {
                    // 形态 2：消息进了客户端内部队列但没送到 broker。
                    // 只记日志 —— 丢一条访问日志远好过让用户看到跳转失败。
                    log.warn("访问日志投递失败（已降级丢弃，不影响跳转）: shortCode={}, err={}",
                            msg.getShortCode(), e.getMessage());
                }
            });
        } catch (Exception e) {
            // 形态 1：同步抛异常（无路由 / 客户端未启动）
            log.warn("访问日志投递失败（已降级丢弃，不影响跳转）: shortCode={}, err={}",
                    msg.getShortCode(), e.getMessage());
        }
    }
}
