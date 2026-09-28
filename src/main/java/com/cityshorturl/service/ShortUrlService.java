package com.cityshorturl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cityshorturl.common.BusinessException;
import com.cityshorturl.dto.CreateShortUrlDTO;
import com.cityshorturl.dto.ShortUrlVO;
import com.cityshorturl.entity.ShortUrl;
import com.cityshorturl.mapper.ShortUrlMapper;
import com.cityshorturl.util.Base62;
import com.cityshorturl.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShortUrlService {

    /** 缓存 key 前缀。统一前缀是为了将来能按前缀批量失效，而不是 KEYS * */
    private static final String CACHE_KEY_PREFIX = "short:url:";

    /** 映射缓存 24 小时。TTL 是防雪崩的最后一道防线（详见 Step 4） */
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final SnowflakeIdGenerator idGenerator;
    private final ShortUrlMapper shortUrlMapper;
    private final BloomFilterService bloomFilterService;
    private final StringRedisTemplate stringRedisTemplate;

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

        // 必须同步写进布隆过滤器：否则新创建的短链会被自己的布隆拦成 404
        bloomFilterService.add(shortCode);

        log.info("短链创建成功: userId={}, shortCode={}, id={}", userId, shortCode, id);

        return ShortUrlVO.builder()
                .shortCode(shortCode)
                .shortUrl(baseUrl + "/" + shortCode)
                .originalUrl(dto.getOriginalUrl())
                .expireAt(entity.getExpireAt())
                .build();
    }

    /**
     * 解析短码 → 原始链接。这是整个系统的读主链路。
     *
     * <h3>四层拦截，每层都为了少查一次 DB</h3>
     * <pre>
     *   布隆过滤器 → Redis → MySQL → 回填 Redis
     *   （挡非法码）  （挡重复读）（兜底）   （下次直接命中）
     * </pre>
     *
     * <p>为什么顺序不能反：布隆是内存操作（纳秒级），Redis 是网络往返（~0.5ms），
     * MySQL 是磁盘 IO（~5ms 起）。越便宜的检查越靠前。
     *
     * <p>注意这里是标准 Cache-Aside（旁路缓存）：
     * <b>读时回填、写时删缓存</b>（写侧在 Step 7 的删除接口里做），
     * 而不是「写时更新缓存」—— 后者在并发下容易产生脏数据。
     */
    public String resolve(String shortCode) {
        // 第 1 层：布隆过滤器。判 false 说明「一定不存在」，直接拒绝，连 Redis 都不用碰
        if (!bloomFilterService.mightContain(shortCode)) {
            log.debug("布隆过滤器拦截非法短码: {}", shortCode);
            throw new BusinessException(404, "短链不存在");
        }

        // 第 2 层：Redis
        String cacheKey = CACHE_KEY_PREFIX + shortCode;
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 第 3 层：MySQL
        ShortUrl entity = shortUrlMapper.selectOne(
                new LambdaQueryWrapper<ShortUrl>().eq(ShortUrl::getShortCode, shortCode));
        if (entity == null || !Integer.valueOf(1).equals(entity.getStatus())) {
            throw new BusinessException(404, "短链不存在或已失效");
        }
        if (entity.getExpireAt() != null && entity.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(404, "短链已过期");
        }

        // 第 4 层：回填缓存，下次同样的短码直接在第 2 层返回
        stringRedisTemplate.opsForValue().set(cacheKey, entity.getOriginalUrl(), CACHE_TTL);
        return entity.getOriginalUrl();
    }
}
