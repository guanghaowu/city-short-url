package com.cityshorturl.controller;

import com.cityshorturl.common.BusinessException;
import com.cityshorturl.common.Result;
import com.cityshorturl.common.UserContext;
import com.cityshorturl.dto.LoginDTO;
import com.cityshorturl.dto.LoginVO;
import com.cityshorturl.dto.RegisterDTO;
import com.cityshorturl.entity.User;
import com.cityshorturl.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDTO dto) {
        userService.register(dto);
        return Result.ok();
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok(userService.login(dto));
    }

    /**
     * 当前登录用户信息。
     *
     * <p>这是 Step 1 的验证接口：它受 JwtInterceptor 保护，
     * 不带 token 访问必须返回 401。同时也是前端「登录态校验」的接口。
     */
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        Long userId = UserContext.getUserId();
        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(401, "用户不存在");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", user.getId());
        body.put("username", user.getUsername());
        body.put("createdAt", user.getCreatedAt());
        return Result.ok(body);
    }
}
