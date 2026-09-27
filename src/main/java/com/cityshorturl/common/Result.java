package com.cityshorturl.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体。与项目 A 保持同一套约定：
 * <ul>
 *   <li>HTTP 状态码固定 200，业务成败由 code 表达（0 = 成功）</li>
 *   <li>只有鉴权失败才返回 HTTP 401</li>
 * </ul>
 *
 * @param <T> 数据类型
 */
@Data
public class Result<T> implements Serializable {

    /** 0 = 成功，非 0 = 业务错误码 */
    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setCode(0);
        r.setMessage("success");
        r.setData(data);
        return r;
    }

    public static <T> Result<T> fail(Integer code, String message) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }
}
