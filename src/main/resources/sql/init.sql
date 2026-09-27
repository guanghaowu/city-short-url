-- =====================================================
-- 高并发短链系统 建表脚本（4 张表）
-- 来源：docs/项目B-短链系统-PRD.md 第四节
-- =====================================================

CREATE DATABASE IF NOT EXISTS city_url
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE city_url;

-- 1. 用户表
CREATE TABLE IF NOT EXISTS user (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    username      VARCHAR(50) UNIQUE NOT NULL,
    password      VARCHAR(100) NOT NULL,
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. 短链表
--    id 由雪花算法生成后转 base62 得到 short_code，因此不用 AUTO_INCREMENT。
--    pv/uv 为冗余计数，由 MQ 消费者异步累加（Step 7）。
CREATE TABLE IF NOT EXISTS short_url (
    id            BIGINT PRIMARY KEY COMMENT '雪花算法生成',
    short_code    VARCHAR(10) UNIQUE NOT NULL COMMENT 'base62 编码后的短链码',
    original_url  TEXT NOT NULL,
    user_id       BIGINT NOT NULL,
    expire_at     DATETIME COMMENT '过期时间，NULL=永久',
    status        TINYINT DEFAULT 1 COMMENT '1=有效 2=失效',
    pv            BIGINT DEFAULT 0,
    uv            BIGINT DEFAULT 0,
    created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_short_code (short_code),
    INDEX idx_user_created (user_id, created_at DESC),
    INDEX idx_status_expire (status, expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. 访问日志表（演示用单表，生产按天分表）
CREATE TABLE IF NOT EXISTS access_log (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    short_code    VARCHAR(10) NOT NULL,
    user_id       BIGINT,
    ip            VARCHAR(45),
    user_agent    VARCHAR(255),
    referer       VARCHAR(255),
    access_time   DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_short_code (short_code),
    INDEX idx_access_time (access_time),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. 访问统计表（按天聚合）
CREATE TABLE IF NOT EXISTS access_stats (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    short_code    VARCHAR(10) NOT NULL,
    stat_date     DATE NOT NULL,
    pv            INT DEFAULT 0,
    uv            INT DEFAULT 0,
    updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_short_date (short_code, stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
