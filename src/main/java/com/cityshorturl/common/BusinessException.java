package com.cityshorturl.common;

/**
 * 业务异常。
 *
 * <p>与项目 A 保持同一套错误模型：业务失败返回 <b>HTTP 200 + 非 0 业务码</b>，
 * 只有鉴权失败才返回 HTTP 401（由 {@link JwtInterceptor} 直接写响应）。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        this(400, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
