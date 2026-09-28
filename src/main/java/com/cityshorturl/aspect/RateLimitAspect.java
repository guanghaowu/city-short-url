package com.cityshorturl.aspect;

import com.cityshorturl.annotation.RateLimit;
import com.cityshorturl.common.RateLimitException;
import com.cityshorturl.common.RequestUtils;
import com.cityshorturl.common.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;

/**
 * 滑动窗口限流切面。
 *
 * <h3>为什么用 ZSet 而不是「固定窗口计数器」</h3>
 * 固定窗口（{@code INCR key:分钟数}）有个经典缺陷：<b>临界点流量翻倍</b>。
 * 假设限 60 次/分钟，请求在 00:59 打满 60 次、01:00 又打满 60 次，
 * 那么在 1 秒内实际通过了 120 次 —— 限额形同虚设。
 *
 * <p>滑动窗口用 ZSet 存<b>每一次请求的毫秒时间戳</b>，
 * 每次请求前先 {@code ZREMRANGEBYSCORE} 清掉滑出窗口的旧记录，再 {@code ZCARD} 数剩余条数。
 * 这样「最近 60 秒」是真正滚动的，不存在临界点。
 *
 * <h3>为什么必须用 Lua</h3>
 * 上面「清理 + 计数 + 写入」三步必须<b>原子</b>执行。
 * 拆成三条独立 Redis 命令的话，并发下两个请求可能同时读到 count=59 然后都放行，
 * 实际放进去 61 条。Lua 在 Redis 里是单线程执行的，天然原子。
 *
 * <h3>🔴 PRD 骨架里的一处 bug（已修）</h3>
 * PRD 原稿写的是 {@code redis.call('ZADD', key, now, now)} —— 把时间戳<b>当作 member</b>。
 * ZADD 在 member 已存在时是「<b>更新 score</b>」而不是「新增成员」，
 * 所以同一毫秒内的并发请求会互相覆盖，计数少算，限流形同虚设。
 * 这里的 member 用 {@code 时间戳 + UUID} 保证唯一。
 *
 * <h3>失败策略：fail-open</h3>
 * Redis 挂了是「放行」还是「全拒」？本项目选<b>放行</b>。
 * 限流是保护手段而非业务前提，因保护组件故障导致全部请求失败是更大的事故。
 * （对比：鉴权必须 fail-close，宁可拒也不能放。）
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private static final String KEY_PREFIX = "rate:";

    /**
     * 滑动窗口 Lua 脚本。
     *
     * <p>KEYS[1] = 限流 key；ARGV = {当前毫秒, 窗口毫秒, 阈值, 唯一 member}
     *
     * <p>返回 1 = 放行，0 = 拦截。
     */
    private static final RedisScript<Long> SLIDING_WINDOW = new DefaultRedisScript<>("""
            local key    = KEYS[1]
            local now    = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit  = tonumber(ARGV[3])
            local member = ARGV[4]

            -- 1. 清掉滑出窗口的旧记录 —— 这一步让窗口真正「滑动」
            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)

            -- 2. 数窗口内还剩几条
            local count = redis.call('ZCARD', key)
            if count >= limit then
                -- 被拒也要续 TTL：否则这个 key 会在窗口结束时过期，
                -- 攻击者只要熬过这段时间就能再来一轮
                redis.call('PEXPIRE', key, window)
                return 0
            end

            -- 3. 放行并记账
            redis.call('ZADD', key, now, member)
            redis.call('PEXPIRE', key, window)
            return 1
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    /** Spring 注入的是请求级代理，单例 Bean 里也能安全取到当前请求 */
    private final HttpServletRequest request;

    /** 全局开关。压测播种 2000 条短链时要临时关掉，否则会撞上限流 */
    @Value("${app.rate-limit.enabled:true}")
    private boolean enabled;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        if (!enabled) {
            return pjp.proceed();
        }

        String identity = resolveIdentity(rateLimit.dimension());
        String key = KEY_PREFIX + rateLimit.key() + ":" + identity;
        long windowMs = rateLimit.window() * 1000L;

        // ⚠ member 必须唯一：同毫秒的并发请求若共用 member，ZADD 会更新 score
        // 而不是新增成员，计数少算 → 限流失效
        String member = System.currentTimeMillis() + "-" + UUID.randomUUID();

        Long allowed;
        try {
            allowed = stringRedisTemplate.execute(
                    SLIDING_WINDOW,
                    Collections.singletonList(key),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(windowMs),
                    String.valueOf(rateLimit.limit()),
                    member);
        } catch (Exception e) {
            // fail-open：限流组件故障不应导致业务全挂
            log.error("限流查询 Redis 失败，降级放行（fail-open）: key={}", key, e);
            return pjp.proceed();
        }

        if (allowed == null || allowed != 1L) {
            log.warn("限流拦截: key={}, 维度={}, 窗口={}s, 阈值={}",
                    key, rateLimit.dimension(), rateLimit.window(), rateLimit.limit());
            throw new RateLimitException(
                    "请求过于频繁，请稍后再试（" + rateLimit.limit() + " 次 / " + rateLimit.window() + " 秒）");
        }

        return pjp.proceed();
    }

    /**
     * 取限流主体。
     *
     * <p>{@code USER} 维度在 UserContext 为空时降级为 IP —— 例如接口没走鉴权拦截器，
     * 或 token 恰好在这一刻过期。
     */
    private String resolveIdentity(RateLimit.Dimension dimension) {
        if (dimension == RateLimit.Dimension.USER) {
            Long userId = UserContext.getUserId();
            if (userId != null) {
                return "u" + userId;
            }
        }
        return RequestUtils.clientIp(request);
    }
}
