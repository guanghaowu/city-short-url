package com.cityshorturl.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录响应。只暴露 token 与用户名，<b>绝不带 password</b>。
 */
@Data
@AllArgsConstructor
public class LoginVO {

    private String token;
    private Long userId;
    private String username;
}
