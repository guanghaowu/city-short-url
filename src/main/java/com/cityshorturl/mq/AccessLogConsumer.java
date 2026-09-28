package com.cityshorturl.mq;

import com.cityshorturl.service.AccessLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 访问日志消费者：把 MQ 里的消息落库。
 *
 * <p>本类刻意做得极薄 —— 真正的 INSERT 收敛在
 * {@link AccessLogService#persist(AccessLogMessage)}，
 * 这样「同步模式」和「异步模式」走的是<b>同一份写库逻辑</b>，
 * 不会出现「异步落库的字段和同步落库的字段不一致」这种对照实验的经典翻车。
 *
 * <h3>并发消费（{@code consumeMode} 默认 CONCURRENTLY）</h3>
 * 同一队列的消息会被线程池并发消费，日志之间没有顺序依赖，正合适。
 * （顺序消息只在你需要「同一订单的多个事件严格有序」时才需要，
 * 代价是消费端串行、吞吐大幅下降。）
 *
 * <h3>为什么 {@code maxReconsumeTimes = 2}</h3>
 * 默认 16 次、且重试间隔递增，一条写不进去的坏消息会拖几十分钟。
 * 访问日志是可丢弃数据，重试 2 次还不行就让它进死信队列，
 * <b>不要为了几条日志让消费位点卡住</b>（位点卡住会影响后面所有消息）。
 *
 * <h3>⚠ 至少一次语义 ⇒ 可能有重复</h3>
 * RocketMQ 保证 at-least-once：消费成功但 ack 丢失时消息会重投，
 * 于是 access_log 里可能出现两条一模一样的记录，PV 略微偏大。
 * 本项目<b>刻意不做去重</b>：为日志引入唯一键 + 去重查询，
 * 写放大和复杂度都不划算。这是一处明确的取舍，面试要能说出来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = AccessLogProducer.TOPIC,
        consumerGroup = "short-url-access-log-consumer-group",
        consumeThreadMax = 20,
        maxReconsumeTimes = 2
)
public class AccessLogConsumer implements RocketMQListener<AccessLogMessage> {

    private final AccessLogService accessLogService;

    @Override
    public void onMessage(AccessLogMessage msg) {
        // 不 try-catch：落库失败就让异常抛出去，交给 RocketMQ 重试。
        // 在这里吞掉异常等于「假装消费成功」，消息会直接丢，且没有任何痕迹。
        accessLogService.persist(msg);
        log.debug("访问日志落库: shortCode={}, ip={}", msg.getShortCode(), msg.getIp());
    }
}
