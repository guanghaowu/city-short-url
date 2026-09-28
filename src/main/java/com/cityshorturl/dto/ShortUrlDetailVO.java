package com.cityshorturl.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 短链详情。
 *
 * <p>⚠ 只暴露<b>创建者本人</b>可见的字段。特别注意不要带 {@code userId} ——
 * 那是内部归属字段，回显出去既没用又可能被人拿来撞库。
 */
@Data
@Builder
public class ShortUrlDetailVO {

    private String shortCode;

    /** 完整短链，形如 http://localhost:8082/QnH7KCENZQ */
    private String shortUrl;

    private String originalUrl;

    /** null = 永久有效 */
    private LocalDateTime expireAt;

    /** 1=有效 2=已删除 */
    private Integer status;

    private Long pv;
    private Long uv;

    private LocalDateTime createdAt;
}
