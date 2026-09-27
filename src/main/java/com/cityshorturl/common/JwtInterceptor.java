package com.cityshorturl.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * JWT 拦截器：从 {@code Authorization: Bearer xxx} 取 token，
 * 解析出 userId 写入 {@link UserContext}。
 *
 * <p>失败返回 <b>HTTP 401</b> —— 这是全项目唯一不使用「200 + 业务码」的地方，
 * 因为鉴权失败属于传输层语义，前端 axios 拦截器据此跳登录页。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            return true;
        }

        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeUnauthorized(resp, "未登录或 token 缺失");
            return false;
        }

        try {
            UserContext.set(jwtUtil.parseUserId(header.substring(7)));
            return true;
        } catch (Exception e) {
            log.warn("JWT 解析失败: {}", e.getMessage());
            writeUnauthorized(resp, "Token 无效或已过期");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp,
                                Object handler, Exception ex) {
        // 必须清理：Tomcat 线程池会复用线程，不清理会把 userId 泄漏给下一个请求
        UserContext.clear();
    }

    private void writeUnauthorized(HttpServletResponse resp, String msg) throws Exception {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(objectMapper.writeValueAsString(Result.fail(401, msg)));
    }
}
