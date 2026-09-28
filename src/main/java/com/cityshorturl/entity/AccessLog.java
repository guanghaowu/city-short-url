package com.cityshorturl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 访问日志。
 *
 * <p>Step 7 起由 RocketMQ 消费者<b>异步</b>落库 —— 跳转主链路只发消息不写库，
 * 避免一次 DB 写把「302 跳转」这个本该极快的操作拖慢。
 */
@Data
@TableName("access_log")
public class AccessLog {

    /** 自增主键：日志表没有业务主键，用自增最简单且写入有序 */
    @TableId(type = IdType.AUTO)
    private Long id;

    private String shortCode;

    /** 访问者（未登录时为 null）。注意这是<b>访问者</b>不是短链创建者 */
    private Long userId;

    private String ip;

    private String userAgent;

    private String referer;

    private LocalDateTime accessTime;
}
