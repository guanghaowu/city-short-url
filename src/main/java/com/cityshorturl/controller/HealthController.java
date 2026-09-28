package com.cityshorturl.controller;

import com.cityshorturl.common.Result;
import com.cityshorturl.service.BloomFilterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查。
 *
 * <p>这是 Step 0 的完成判据：应用能起、MySQL 通、Redis 通。
 * 不连 Docker 时组件会显示 DOWN，但接口本身仍返回 200（方便排查）。
 *
 * <p>Step 4 起额外暴露 {@code features} 段，用于<b>压测自证</b>：
 * 对照组需要把布隆/互斥锁关掉，但「我关掉了」不能靠嘴说 ——
 * 压测脚本会先读这个字段确认开关真的生效了，再开始打流量。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final StringRedisTemplate stringRedisTemplate;
    private final DataSource dataSource;
    private final BloomFilterService bloomFilterService;

    @Value("${app.cache.null-ttl-seconds:60}")
    private long nullTtlSeconds;

    @Value("${app.cache.ttl-jitter-seconds:1800}")
    private long ttlJitterSeconds;

    @Value("${app.cache.mutex-enabled:true}")
    private boolean mutexEnabled;

    @GetMapping("/api/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "city-short-url");
        body.put("version", "0.0.1-SNAPSHOT");
        body.put("redis", probeRedis());
        body.put("db", probeDb());
        body.put("features", features());

        boolean up = "UP".equals(body.get("redis")) && "UP".equals(body.get("db"));
        body.put("status", up ? "UP" : "DEGRADED");
        body.put("timestamp", java.time.LocalDateTime.now().toString());

        log.debug("health check: {}", body);
        return Result.ok(body);
    }

    /**
     * 当前生效的防护开关。压测脚本用它确认对照组真的生效。
     *
     * <p>{@code bloomFilter} 有三种取值：
     * <ul>
     *   <li>{@code ON} —— 已加载完成，正常拦截</li>
     *   <li>{@code LOADING} —— 异步加载还没完，此时保守放行（不拦截）</li>
     *   <li>{@code OFF} —— 被配置关掉，压测对照模式</li>
     * </ul>
     * 布隆是异步加载的，所以压测前必须确认是 ON 而不是 LOADING，
     * 否则会测出「布隆没拦住」的假象。
     */
    private Map<String, Object> features() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("bloomFilter", !bloomFilterService.isEnabled()
                ? "OFF"
                : (bloomFilterService.isReady() ? "ON" : "LOADING"));
        f.put("cacheMutex", mutexEnabled ? "ON" : "OFF");
        f.put("nullCache", nullTtlSeconds > 0 ? "ON" : "OFF");
        f.put("ttlJitterSeconds", ttlJitterSeconds);
        return f;
    }

    private String probeRedis() {
        try {
            stringRedisTemplate.hasKey("__probe__");
            return "UP";
        } catch (Exception e) {
            log.warn("Redis 探测失败: {}", e.getMessage());
            return "DOWN";
        }
    }

    private String probeDb() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(1) ? "UP" : "DOWN";
        } catch (Exception e) {
            log.warn("MySQL 探测失败: {}", e.getMessage());
            return "DOWN";
        }
    }
}
