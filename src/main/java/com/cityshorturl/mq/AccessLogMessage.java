package com.cityshorturl.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 访问日志消息体。跳转成功后由请求线程投递，消费者异步落库。
 *
 * <h3>为什么时间字段用 {@code long} 而不是 {@code LocalDateTime}</h3>
 * rocketmq-spring 默认的 {@code JacksonMessageConverter} 内部用的是<b>裸 ObjectMapper</b>，
 * <b>没有注册 JavaTimeModule</b>。传 {@code LocalDateTime} 会在序列化时直接抛
 * {@code InvalidDefinitionException: Java 8 date/time type not supported by default}。
 * 传 epoch 毫秒既没有这个问题，也顺带避开了时区歧义
 * （生产者和消费者不在同一台机器时，字符串时间会歧义，时间戳不会）。
 *
 * <h3>为什么消息体这么「胖」</h3>
 * 把 ip / userAgent / referer 都带上，而不是只传 shortCode 让消费者自己去查 ——
 * 因为<b>这些信息只在请求线程里存在</b>（HttpServletRequest 出了请求就没了）。
 * 消费者拿不到请求上下文，所以必须由生产者把上下文快照下来。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccessLogMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String shortCode;

    /** 访问者 ID。短链跳转是公开接口，未登录时为 null（列可空） */
    private Long userId;

    private String ip;

    private String userAgent;

    private String referer;

    /** 访问时刻（epoch 毫秒） */
    private long accessTimeMillis;
}
