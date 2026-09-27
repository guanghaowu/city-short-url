package com.cityshorturl.common;

/**
 * 当前登录用户上下文。
 *
 * <p>拦截器校验 token 后写入，业务层用 {@link #getUserId()} 取，
 * 请求结束时由拦截器 {@code afterCompletion} 清理 —— <b>必须清理</b>，
 * 否则 Tomcat 线程复用会把上一个请求的 userId 带给下一个请求（越权事故）。
 */
public final class UserContext {

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId) {
        HOLDER.set(userId);
    }

    public static Long getUserId() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
