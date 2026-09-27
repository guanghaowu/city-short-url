package com.cityshorturl.controller;

import com.cityshorturl.common.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final StringRedisTemplate stringRedisTemplate;
    private final DataSource dataSource;

    @GetMapping("/api/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "city-short-url");
        body.put("version", "0.0.1-SNAPSHOT");
        body.put("redis", probeRedis());
        body.put("db", probeDb());

        boolean up = "UP".equals(body.get("redis")) && "UP".equals(body.get("db"));
        body.put("status", up ? "UP" : "DEGRADED");
        body.put("timestamp", java.time.LocalDateTime.now().toString());

        log.debug("health check: {}", body);
        return Result.ok(body);
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
