package com.cityshorturl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cityshorturl.common.BusinessException;
import com.cityshorturl.common.JwtUtil;
import com.cityshorturl.dto.LoginDTO;
import com.cityshorturl.dto.LoginVO;
import com.cityshorturl.dto.RegisterDTO;
import com.cityshorturl.entity.User;
import com.cityshorturl.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;

    /** 无状态、线程安全，可以直接作为字段复用 */
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public void register(RegisterDTO dto) {
        Long exists = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));
        if (exists != null && exists > 0) {
            throw new BusinessException("用户名已存在");
        }

        User user = new User();
        user.setUsername(dto.getUsername());
        // 只存哈希。BCrypt 自带随机盐，同一密码每次哈希结果都不同，
        // 所以登录校验必须用 matches() 而不是比较字符串。
        user.setPassword(encoder.encode(dto.getPassword()));
        userMapper.insert(user);

        log.info("用户注册成功: id={}, username={}", user.getId(), user.getUsername());
    }

    public LoginVO login(LoginDTO dto) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));

        // 用户名不存在与密码错误返回同一句提示，避免暴露「该用户名是否已注册」
        if (user == null || !encoder.matches(dto.getPassword(), user.getPassword())) {
            // 注意用业务码 400 而非 401：401 会触发前端「跳登录页」逻辑，
            // 而这里就是登录接口本身，返回 401 会造成死循环。
            throw new BusinessException(400, "用户名或密码错误");
        }

        String token = jwtUtil.generate(user.getId(), user.getUsername());
        log.info("用户登录成功: id={}, username={}", user.getId(), user.getUsername());
        return new LoginVO(token, user.getId(), user.getUsername());
    }

    public User getById(Long id) {
        return userMapper.selectById(id);
    }
}
