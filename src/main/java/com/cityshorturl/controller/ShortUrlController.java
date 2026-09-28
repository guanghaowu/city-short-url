package com.cityshorturl.controller;

import com.cityshorturl.annotation.RateLimit;
import com.cityshorturl.common.Result;
import com.cityshorturl.common.UserContext;
import com.cityshorturl.dto.CreateShortUrlDTO;
import com.cityshorturl.dto.ShortUrlVO;
import com.cityshorturl.service.ShortUrlService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/short")
@RequiredArgsConstructor
public class ShortUrlController {

    private final ShortUrlService shortUrlService;

    /**
     * 生成短链。需要登录（userId 从 {@link UserContext} 取，不由前端传，防伪造）。
     *
     * <h3>为什么限流只加在这个接口，不加在短链跳转上</h3>
     * 短链跳转（{@code GET /{shortCode}}）承载的是<b>正常业务流量</b>：
     * 一个爆款短链一分钟几万次访问很正常，而且同一个公司/校园网出口是 NAT 的，
     * 按 IP 限 60 次/分钟会<b>大面积误伤真实用户</b>。
     * 跳转侧的防爬更适合用「行为分析 + 验证码 + 单 IP 异常阈值」这类更粗的手段，
     * 而不是一刀切的固定配额。
     *
     * <p>而「生成短链」是<b>写接口 + 要付费（存储/存储成本）</b>，
     * 正常用户一分钟根本点不到 60 次，被限到的只可能是脚本 —— 这里限流几乎不会误伤。
     *
     * <p><b>判据：限流该加在「异常用法明显区别于正常用法」的接口上。</b>
     */
    @RateLimit(key = "short:create", dimension = RateLimit.Dimension.IP, window = 60, limit = 60)
    @PostMapping("/create")
    public Result<ShortUrlVO> create(@Valid @RequestBody CreateShortUrlDTO dto) {
        return Result.ok(shortUrlService.create(dto, UserContext.getUserId()));
    }
}
