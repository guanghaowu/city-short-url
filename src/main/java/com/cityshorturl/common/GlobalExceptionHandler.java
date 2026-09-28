package com.cityshorturl.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理。
 *
 * <p>约定：业务异常一律 HTTP 200 + {@code code != 0}，
 * 让前端只需判断业务码，不必区分 HTTP 状态。
 * 401 由拦截器直接写出，不走这里。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 限流：返回<b>真正的 HTTP 429</b>，而不是项目统一的「HTTP 200 + 业务码」。
     *
     * <p>为什么这里要破例（Step 5 的设计决策）：
     * 429 是<b>协议语义</b>而非业务失败 —— 网关、CDN、浏览器、HTTP 客户端库都认它，
     * 配合 {@code Retry-After} 还能自动退避。
     * 若包成 HTTP 200，调用方无法区分「我该稍后重试」和「我参数传错了」。
     *
     * <p>响应体仍复用统一的 {@code Result}（业务码也是 429），
     * 所以前端按业务码判断的老逻辑不会坏。
     */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Result<Void>> handleRateLimit(RateLimitException e) {
        log.warn("限流拦截: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Result.fail(429, e.getMessage()));
    }

    /** @Valid 校验失败：取第一条错误信息返回，避免前端拿到一长串 */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<Void> handleValidation(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", msg);
        return Result.fail(400, msg);
    }

    /**
     * 方法不支持 / 路径不存在：这两类都是<b>协议层错误</b>，不是业务失败，也不是服务端故障。
     *
     * <p>为什么必须单独处理（踩过的坑）：
     * 它们原本会被下面的 {@code handleOther} 兜底成 <b>HTTP 200 + code 500「服务器内部错误」</b>，
     * 后果有两个 —— ① 调用方把「我方法写错了」误判成「服务端挂了」，
     * ② 每次客户端写错路径都在 ERROR 级别刷一段几十行的误导性堆栈。
     * 和 429 同理，这里返回<b>真实的 HTTP 状态码</b>，让调用方一眼看出问题在自己这边。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: method={}, path={}", e.getMethod(), e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail(405, "请求方法不支持：" + e.getMethod()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        log.warn("接口或资源不存在: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(404, "接口或资源不存在"));
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("未预期异常", e);
        return Result.fail(500, "服务器内部错误");
    }
}
