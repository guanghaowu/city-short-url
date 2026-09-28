package com.cityshorturl.controller;

import com.cityshorturl.annotation.RateLimit;
import com.cityshorturl.common.Result;
import com.cityshorturl.common.UserContext;
import com.cityshorturl.dto.CreateShortUrlDTO;
import com.cityshorturl.dto.PageVO;
import com.cityshorturl.dto.ShortUrlDetailVO;
import com.cityshorturl.dto.ShortUrlVO;
import com.cityshorturl.dto.StatsVO;
import com.cityshorturl.service.ShortUrlService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * 短链详情。
     *
     * <p><b>归属校验在 service 层的 {@code requireOwned()}</b>，不在 Controller ——
     * 所有管理接口共用同一个校验入口，避免新接口忘了加判断就上线。
     */
    @GetMapping("/info/{shortCode}")
    public Result<ShortUrlDetailVO> info(@PathVariable String shortCode) {
        return Result.ok(shortUrlService.detail(shortCode, UserContext.getUserId()));
    }

    /**
     * 我的短链（分页）。
     *
     * <p><b>userId 只从 token 取，不接受前端传参</b> —— 一旦允许
     * {@code ?userId=xxx}，任何人传别人的 ID 就能看别人的列表，这是最典型的越权。
     */
    @GetMapping("/my")
    public Result<PageVO<ShortUrlDetailVO>> my(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        // 防止 size 传个 100000 拖垮数据库
        long safeSize = Math.min(Math.max(size, 1), 100);
        return Result.ok(shortUrlService.myList(UserContext.getUserId(), current, safeSize));
    }

    /** 删除短链（软删）。会连带删掉 Redis 缓存，否则短链仍能跳转。 */
    @DeleteMapping("/{shortCode}")
    public Result<Void> delete(@PathVariable String shortCode) {
        shortUrlService.delete(shortCode, UserContext.getUserId());
        return Result.ok();
    }

    /**
     * 访问统计（PV/UV + 按天明细）。
     *
     * <p>数据来自 {@code access_log}，Step 7 起由 RocketMQ 消费者异步写入。
     */
    @GetMapping("/stats/{shortCode}")
    public Result<StatsVO> stats(@PathVariable String shortCode,
                                 @RequestParam(defaultValue = "7") int days) {
        int safeDays = Math.min(Math.max(days, 1), 90);
        return Result.ok(shortUrlService.stats(shortCode, UserContext.getUserId(), safeDays));
    }
}
