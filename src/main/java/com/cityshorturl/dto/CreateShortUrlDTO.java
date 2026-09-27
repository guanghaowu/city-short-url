package com.cityshorturl.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateShortUrlDTO {

    /**
     * 长链接。
     *
     * <p>只允许 http/https：如果放开 {@code javascript:} 或 {@code data:} 协议，
     * 短链就成了 XSS 载荷的传播工具 —— 这是短链服务的经典安全坑。
     * 长度上限 2048，防止超长 URL 撑爆存储。
     */
    @NotBlank(message = "原始链接不能为空")
    @Size(max = 2048, message = "原始链接不能超过 2048 字符")
    @Pattern(regexp = "^https?://.+", message = "只支持 http/https 协议的链接")
    private String originalUrl;

    /** 过期天数。不传或传 0 表示永久有效 */
    @Min(value = 0, message = "过期天数不能为负")
    @Max(value = 3650, message = "过期天数不能超过 3650 天")
    private Integer expireDays;
}
