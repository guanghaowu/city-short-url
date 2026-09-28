package com.cityshorturl.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 滑动窗口限流。打在 Controller 方法上，由 {@code RateLimitAspect} 织入。
 *
 * <h3>用法</h3>
 * <pre>{@code
 * @RateLimit(key = "short:create", dimension = Dimension.IP, window = 60, limit = 60)
 * }</pre>
 *
 * <h3>为什么用注解 + AOP 而不是在 Controller 里手写</h3>
 * 手写会让「限流」和「业务」耦合在同一个方法里：
 * 换个接口要复制一遍、改阈值要动业务代码、想临时关掉要改代码重新发版。
 * 注解把限流变成<b>声明式</b>的：阈值写在方法签名旁边，一眼可见；
 * 全局开关由配置控制，不碰业务代码。
 *
 * <h3>为什么 {@code key} 是必填的</h3>
 * 强制每个接口想清楚自己的配额归属。若用默认值，
 * 很容易出现「创建短链」和「查询列表」共用一个 key 互相挤占配额的隐形 bug。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /** 限流维度：按 IP 还是按用户 */
    Dimension dimension() default Dimension.IP;

    /** 时间窗口，单位秒 */
    long window() default 60;

    /** 窗口内允许通过的最大次数 */
    long limit() default 60;

    /** Redis key 前缀，形如 {@code "short:create"}。不同接口必须不同，否则互相挤占配额 */
    String key();

    /**
     * 限流维度。
     * <ul>
     *   <li><b>IP</b>：防爬虫/防滥用。缺点是同一个出口 NAT（公司、校园网）后面
     *       的所有人共享配额，容易误伤。</li>
     *   <li><b>USER</b>：防用户侧刷接口。前提是接口已登录（UserContext 有值），
     *       取不到 userId 时降级为 IP。</li>
     * </ul>
     */
    enum Dimension {
        IP, USER
    }
}
