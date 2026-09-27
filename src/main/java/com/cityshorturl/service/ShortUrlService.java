package com.cityshorturl.service;

import com.cityshorturl.dto.CreateShortUrlDTO;
import com.cityshorturl.dto.ShortUrlVO;
import com.cityshorturl.entity.ShortUrl;
import com.cityshorturl.mapper.ShortUrlMapper;
import com.cityshorturl.util.Base62;
import com.cityshorturl.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShortUrlService {

    private final SnowflakeIdGenerator idGenerator;
    private final ShortUrlMapper shortUrlMapper;

    @Value("${app.short-url.base-url:http://localhost:8082}")
    private String baseUrl;

    /**
     * 生成短链。
     *
     * <h3>为什么这里不需要「查重 + 重试」</h3>
     * 旧 PRD 写的是「生成后查 DB 是否已存在，冲突则重新生成」—— 这一步是多余的：
     * <b>base62 编码是双射（一一对应）</b>，只要雪花 ID 不重复，shortCode 就必然不重复。
     * 而雪花 ID 的唯一性由「时间戳 + 机器位 + 序号」三者共同保证。
     * 多一次 SELECT 只会白白增加一次 DB 往返。
     *
     * <p>表上的 {@code UNIQUE(short_code)} 索引仍然保留 —— 它是最后一道防线，
     * 万一将来换了 ID 生成策略，数据库会直接拒绝写入，而不是静默产生脏数据。
     */
    public ShortUrlVO create(CreateShortUrlDTO dto, Long userId) {
        long id = idGenerator.nextId();
        String shortCode = Base62.encode(id);

        ShortUrl entity = new ShortUrl();
        entity.setId(id);
        entity.setShortCode(shortCode);
        entity.setOriginalUrl(dto.getOriginalUrl());
        entity.setUserId(userId);
        entity.setStatus(1);
        entity.setPv(0L);
        entity.setUv(0L);

        Integer days = dto.getExpireDays();
        if (days != null && days > 0) {
            entity.setExpireAt(LocalDateTime.now().plusDays(days));
        }

        shortUrlMapper.insert(entity);

        log.info("短链创建成功: userId={}, shortCode={}, id={}", userId, shortCode, id);

        return ShortUrlVO.builder()
                .shortCode(shortCode)
                .shortUrl(baseUrl + "/" + shortCode)
                .originalUrl(dto.getOriginalUrl())
                .expireAt(entity.getExpireAt())
                .build();
    }
}
