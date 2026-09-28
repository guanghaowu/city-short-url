package com.cityshorturl.common;

/**
 * 限流异常。
 *
 * <h3>为什么单独一个异常类，而不是复用 {@code BusinessException(429)}</h3>
 * 本项目的 API 统一约定是「HTTP 200 + {@code code != 0}」，前端只看业务码。
 * 但限流<b>必须返回真正的 HTTP 429</b>，原因：
 * <ul>
 *   <li>429 是<b>协议语义</b>，不是业务失败。网关、CDN、浏览器、HTTP 客户端库
 *       （OkHttp / axios / Spring RestTemplate）都认这个状态码，
 *       配合 {@code Retry-After} 还能自动退避重试。</li>
 *   <li>包成 HTTP 200 会让调用方分不清「我该稍后重试」和「我参数传错了」，
 *       两者处理方式完全不同。</li>
 * </ul>
 * 所以这里用一个独立异常，由 {@code GlobalExceptionHandler} 映射成 HTTP 429，
 * 响应体仍然复用统一的 {@code Result}，两者不冲突。
 */
public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }
}
