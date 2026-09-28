package com.cityshorturl.controller;

import com.cityshorturl.common.BusinessException;
import com.cityshorturl.common.RequestUtils;
import com.cityshorturl.common.UserContext;
import com.cityshorturl.mq.AccessLogMessage;
import com.cityshorturl.service.AccessLogService;
import com.cityshorturl.service.ShortUrlService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
    private final AccessLogService accessLogService;

    /**
     * 短码找不到时返回的极简页面。
     *
     * <p>为什么不复用全局的「HTTP 200 + 业务码」模型：
     * 短链的客户端是<b>浏览器</b>，不是前端 SPA。沿用业务码模型的话，
     * 用户点开一个失效短链会看到一坨裸 JSON（{@code {"code":404,...}}），
     * 而浏览器/爬虫/监控都无法通过状态码识别这是「页面不存在」。
     * 所以这一条路径单独返回真正的 HTTP 404。
     */
    private static final String NOT_FOUND_HTML = """
            <!doctype html>
            <html lang="zh-CN"><head><meta charset="utf-8">
            <title>短链不存在</title></head>
            <body style="font-family:system-ui;text-align:center;padding:80px;color:#555">
            <h1 style="font-size:48px;margin:0">404</h1>
            <p>短链不存在或已失效</p>
            </body></html>
            """;

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
     *
     * <h3>两类接口，两套错误语义（刻意的设计）</h3>
     * <ul>
     *   <li><b>API 接口</b>（{@code /api/**}）：HTTP 200 + {@code code != 0}，
     *       前端只需判断业务码，不必解析 HTTP 状态。</li>
     *   <li><b>浏览器直达的短链</b>：真正的 HTTP 404。
     *       这里显式接住 {@link BusinessException} 并转成状态码，
     *       而不是让它冒泡到全局处理器被包成 200。</li>
     * </ul>
     *
     * <h3>Step 7：访问日志异步化</h3>
     * 跳转成功后才记日志，且记日志的动作<b>不写库</b> ——
     * 默认模式下它只是往 RocketMQ 投一条消息（{@code asyncSend}，不等 broker ack）。
     * 真正的 INSERT 由 {@code AccessLogConsumer} 在另一个线程里做。
     *
     * <p>两点刻意为之：
     * <ol>
     *   <li><b>404 不计入 PV</b>：只有 resolve 成功才记日志。否则一个被反复扫描的
     *       失效短码会把 PV 刷得虚高，统计失去意义。</li>
     *   <li><b>记日志放在 302 响应构造之前、但绝不 try-catch 包裹整个跳转</b>：
     *       {@code record()} 内部已经做了 fail-open，这里不需要再包一层；
     *       包了反而会掩盖真正的 bug。</li>
     * </ol>
     */
    @GetMapping("/{shortCode:[0-9A-Za-z]{6,12}}")
    public ResponseEntity<String> redirect(@PathVariable String shortCode, HttpServletRequest request) {
        String target;
        try {
            target = shortUrlService.resolve(shortCode);
        } catch (BusinessException e) {
            log.info("短链跳转失败: shortCode={}, reason={}", shortCode, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body(NOT_FOUND_HTML);
        }

        // 只有真正跳转成功才记日志（404 不计 PV）
        accessLogService.record(buildAccessLogMessage(shortCode, request));

        log.info("短链跳转: {} -> {}", shortCode, target);

        return ResponseEntity.status(HttpStatus.FOUND)   // 302
                .location(URI.create(target))
                .build();
    }

    /**
     * 把请求上下文快照成消息体。
     *
     * <p>为什么在这里快照而不是让消费者自己去取：{@code HttpServletRequest}
     * 只在请求线程内有效，出了请求就失效了 —— 消费者在另一个线程、另一段时间运行，
     * 拿不到 ip / UA / Referer。所以必须由生产侧把上下文「拍扁」成值对象传过去。
     */
    private AccessLogMessage buildAccessLogMessage(String shortCode, HttpServletRequest request) {
        return new AccessLogMessage(
                shortCode,
                // 跳转是公开接口，未登录时为 null —— 这是「访问者」不是「短链归属者」
                UserContext.getUserId(),
                RequestUtils.clientIp(request),
                request.getHeader("User-Agent"),
                request.getHeader("Referer"),
                System.currentTimeMillis());
    }
}
