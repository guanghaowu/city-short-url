package com.cityshorturl.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具。
 *
 * <p>secret 从配置读，配置里写成 {@code ${JWT_SECRET:默认值}} —— 生产用环境变量注入，
 * 默认值只为本地开发方便，<b>绝不把真实密钥硬编码进仓库</b>。
 */
@Component
public class JwtUtil {

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expire-hours}")
    private long expireHours;

    private SecretKey key;

    @PostConstruct
    public void init() {
        // JJWT 0.12.x 要求 HMAC key >= 32 字节（256 bit），太短会在启动时直接抛异常
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generate(Long userId, String username) {
        Date now = new Date();
        Date expire = new Date(now.getTime() + expireHours * 3600 * 1000);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(now)
                .expiration(expire)
                .signWith(key)
                .compact();
    }

    /** 解析 userId；token 非法或过期会抛 JwtException */
    public Long parseUserId(String token) {
        return Long.parseLong(parseClaims(token).getSubject());
    }

    public String parseUsername(String token) {
        return parseClaims(token).get("username", String.class);
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
