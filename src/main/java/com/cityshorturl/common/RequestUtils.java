package com.cityshorturl.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * HTTP 请求工具。
 *
 * <p>抽出来是为了让<b>限流</b>（{@link com.cityshorturl.aspect.RateLimitAspect}）
 * 和<b>访问日志</b>（{@link com.cityshorturl.controller.RedirectController}）
 * 用同一套 IP 解析逻辑 —— 两份实现漂移的话，会出现「限流按 A 口径、
 * 统计按 B 口径」的诡异现象，排查起来极其费劲。
 */
public final class RequestUtils {

    private RequestUtils() {
    }

    /**
     * 取客户端真实 IP。
     *
     * <p><b>安全提醒（面试会问）</b>：{@code X-Forwarded-For} 是<b>请求头，可以被伪造</b>。
     * 只有在<b>可信代理</b>之后才认它 —— 正确做法是让 Nginx 用
     * {@code proxy_set_header X-Forwarded-For $remote_addr} <b>覆盖</b>（而不是追加）
     * 该头，或者取「从右往左数第 N 个可信代理之后」的那个 IP。
     * 直接信客户端传来的 XFF，攻击者换个头就绕过了限流。
     */
    public static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // XFF 可能是「客户端, 代理1, 代理2」的链，最左边是原始客户端
            return xff.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }
}
