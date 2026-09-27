package com.cityshorturl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 短链实体。
 */
@Data
@TableName("short_url")
public class ShortUrl {

    /**
     * 用 {@link IdType#INPUT} 而非全局的 ASSIGN_ID：
     * 主键由 {@link com.cityshorturl.util.SnowflakeIdGenerator} 生成后手动 set，
     * 显式声明可以避免「到底谁生成的 ID」这种歧义。
     */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** base62(雪花 ID)，当前 10 位 */
    private String shortCode;

    private String originalUrl;

    private Long userId;

    /** null = 永久有效 */
    private LocalDateTime expireAt;

    /** 1=有效 2=失效 */
    private Integer status;

    /** 冗余计数，由 MQ 消费者异步累加（Step 7） */
    private Long pv;
    private Long uv;

    private LocalDateTime createdAt;
}
