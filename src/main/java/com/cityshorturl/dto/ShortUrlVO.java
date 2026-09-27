package com.cityshorturl.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ShortUrlVO {

    private String shortCode;

    /** 完整的可访问短链，前端直接展示/复制 */
    private String shortUrl;

    private String originalUrl;

    /** null = 永久有效 */
    private LocalDateTime expireAt;
}
