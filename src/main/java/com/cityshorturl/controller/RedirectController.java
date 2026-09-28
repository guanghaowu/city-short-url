package com.cityshorturl.controller;

import com.cityshorturl.service.ShortUrlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 短链跳转。这是全系统唯一的公开接口（不需要登录）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RedirectController {

    private final ShortUrlService shortUrlService;

    /**
     * 短码 → 302 跳转到原始链接。
     *
     * <h3>为什么路径上要加正则 {@code [0-9A-Za-z]{6,12}}</h3>
     * 短链跳转挂的是根路径 {@code /{shortCode}}，会「吞掉」所有单段路径。
     * 不加约束的话，{@code /favicon.ico}、{@code /doc.html} 这类请求
     * 也会被当成短码去查一遍布隆和 Redis —— 白白浪费，还会污染日志。
     *
     * <p>加上正则后：含 {@code .} 的静态资源路径、长度不符的路径直接不匹配本方法，
     * 交给后续的静态资源处理器。长度取 6-12 是给将来留余量
     * （当前 base62(雪花ID) 是 10 位，见 {@link com.cityshorturl.util.Base62}）。
     *
     * <h3>为什么用 ResponseEntity 而不是返回 String</h3>
     * 返回 String 会被 Spring MVC 当成「视图名」去解析模板，得到 404 而不是跳转。
     */
    @GetMapping("/{shortCode:[0-9A-Za-z]{6,12}}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String target = shortUrlService.resolve(shortCode);

        log.info("短链跳转: {} -> {}", shortCode, target);

        return ResponseEntity.status(HttpStatus.FOUND)   // 302
                .location(URI.create(target))
                .build();
    }
}
