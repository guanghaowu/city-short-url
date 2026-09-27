package com.cityshorturl.controller;

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

    /** 生成短链。需要登录（userId 从 UserContext 取，不由前端传，防伪造）。 */
    @PostMapping("/create")
    public Result<ShortUrlVO> create(@Valid @RequestBody CreateShortUrlDTO dto) {
        return Result.ok(shortUrlService.create(dto, UserContext.getUserId()));
    }
}
