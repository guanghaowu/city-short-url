package com.cityshorturl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体。
 *
 * <p>注意：{@code password} 字段存的是 <b>BCrypt 哈希</b>，任何情况下都不返回给前端
 * —— 所以对外一律用 VO，不直接序列化本实体。
 */
@Data
@TableName("user")
public class User {

    /** 用户表用自增 ID，与短链表的雪花 ID 是两套策略（见 application.yml 的 id-type 说明） */
    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 哈希，长度固定 60 */
    private String password;

    private LocalDateTime createdAt;
}
