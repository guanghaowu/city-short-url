package com.cityshorturl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cityshorturl.common.BusinessException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cityshorturl.dto.CreateShortUrlDTO;
import com.cityshorturl.dto.PageVO;
import com.cityshorturl.dto.ShortUrlDetailVO;
import com.cityshorturl.dto.ShortUrlVO;
import com.cityshorturl.dto.StatsVO;
import com.cityshorturl.entity.ShortUrl;
import com.cityshorturl.mapper.AccessLogMapper;
import com.cityshorturl.mapper.ShortUrlMapper;
import com.cityshorturl.util.Base62;
import com.cityshorturl.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShortUrlService {

    /** 缓存 key 前缀。统一前缀是为了将来能按前缀批量失效，而不是 KEYS * */
    private static final String CACHE_KEY_PREFIX = "short:url:";

    /** 空值标记 key 前缀。与真实映射分开，避免把空串当成合法 URL 返回 */
    private static final String MISS_KEY_PREFIX = "short:miss:";

    /** 缓存重建互斥锁 key 前缀 */
    private static final String LOCK_KEY_PREFIX = "lock:short:";

    private static final String LOCK_VALUE = "1";

    /**
     * 释放锁的 Lua 脚本：先比对 value 再删。
     *
     * <p><b>为什么不能直接 DEL</b>：如果持锁线程因为 GC 停顿超过了锁的 TTL，
     * 锁已经自动过期并被另一个线程持有，此时直接 DEL 会把<b>别人的锁</b>删掉，
     * 于是两个线程同时进 DB —— 互斥保护失效。
     * 加一层 value 比对（CAS）就杜绝了这种情况。
     */
    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "  return redis.call('del', KEYS[1]) "
                    + "else return 0 end",
            Long.class);

    private final SnowflakeIdGenerator idGenerator;
    private final ShortUrlMapper shortUrlMapper;
    private final AccessLogMapper accessLogMapper;
    private final BloomFilterService bloomFilterService;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${app.short-url.base-url:http://localhost:8082}")
    private String baseUrl;

    @Value("${app.cache.ttl-hours:24}")
    private long ttlHours;

    @Value("${app.cache.ttl-jitter-seconds:1800}")
    private long ttlJitterSeconds;

    @Value("${app.cache.null-ttl-seconds:60}")
    private long nullTtlSeconds;

    @Value("${app.cache.mutex-enabled:true}")
    private boolean mutexEnabled;

    @Value("${app.cache.lock-ttl-seconds:10}")
    private long lockTtlSeconds;

    @Value("${app.cache.spin-wait-ms:50}")
    private long spinWaitMs;

    @Value("${app.cache.spin-max-times:20}")
    private int spinMaxTimes;

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
     *   布隆过滤器 → Redis → 空值标记 → MySQL → 回填 Redis
     *   （挡非法码）（挡重复读）（挡穿透）  （兜底）  （下次直接命中）
     * </pre>
     *
     * <p>为什么顺序不能反：布隆是内存操作（纳秒级），Redis 是网络往返（~0.5ms），
     * MySQL 是磁盘 IO（~5ms 起）。越便宜的检查越靠前。
     *
     * <p>注意这里是标准 Cache-Aside（旁路缓存）：
     * <b>读时回填、写时删缓存</b>（写侧在 Step 6 的删除接口里做），
     * 而不是「写时更新缓存」—— 后者在并发下容易产生脏数据。
     */
    public String resolve(String shortCode) {
        // 第 1 层：布隆过滤器。判 false 说明「一定不存在」，直接拒绝，连 Redis 都不用碰
        if (!bloomFilterService.mightContain(shortCode)) {
            log.debug("布隆过滤器拦截非法短码: {}", shortCode);
            throw notFound("短链不存在");
        }

        String cacheKey = CACHE_KEY_PREFIX + shortCode;

        // 第 2 层：Redis
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 第 2.5 层：空值标记（防穿透的二次兜底）
        // 布隆有 1% 的假阳性，漏过来的非法码会走到这里。第一次查到「确实不存在」
        // 就钉一个短 TTL 的标记，接下来 60 秒内同一个码不再查库。
        // 之所以敢缓存空值：雪花 ID 单调递增、永不复用，一个「不存在」的短码
        // 不可能在 60 秒后突然变成存在。
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(MISS_KEY_PREFIX + shortCode))) {
            log.debug("空值缓存拦截: {}", shortCode);
            throw notFound("短链不存在");
        }

        // 第 3 层：MySQL（带击穿保护）
        return loadWithMutex(shortCode, cacheKey);
    }

    // ------------------------------------------------------------------ 管理接口（Step 6）

    /**
     * 短链详情。<b>必须校验归属</b> —— 否则任何人拿到 shortCode 就能看到别人的原始链接。
     */
    public ShortUrlDetailVO detail(String shortCode, Long userId) {
        return toDetail(requireOwned(shortCode, userId));
    }

    /** 我的短链（分页）。按 userId 过滤是越权防护的第一道闸。 */
    public PageVO<ShortUrlDetailVO> myList(Long userId, long current, long size) {
        Page<ShortUrl> page = new Page<>(current, size);
        LambdaQueryWrapper<ShortUrl> wrapper = new LambdaQueryWrapper<ShortUrl>()
                // 只列有效短链；软删的（status=2）直接不出现在列表里
                .eq(ShortUrl::getUserId, userId)
                .eq(ShortUrl::getStatus, 1)
                .orderByDesc(ShortUrl::getCreatedAt);
        return PageVO.of(shortUrlMapper.selectPage(page, wrapper), this::toDetail);
    }

    /**
     * 删除短链（软删）。
     *
     * <h3>🔴 软删必须同时删缓存</h3>
     * 只把 DB 里的 {@code status} 改成 2，而 Redis 里
     * {@code short:url:{code}} 的映射还在 → <b>短链照样能跳转</b>，
     * 删除形同虚设。这是「写侧必须失效缓存」的经典场景。
     *
     * <h3>为什么布隆过滤器不用（也不能）删</h3>
     * Guava 的 {@code BloomFilter} <b>不支持删除</b>（不像 Counting Bloom Filter）。
     * 已删的短码会一直留在布隆里，于是请求会穿透到 DB，
     * 由 {@code status != 1} 的判断返回 404 —— 正确性没问题，只是多一次 DB 查询。
     * 这也是选 RedisBloom 的一个理由（它支持删），但代价是每请求多一次网络往返。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String shortCode, Long userId) {
        ShortUrl entity = requireOwned(shortCode, userId);

        ShortUrl update = new ShortUrl();
        update.setId(entity.getId());
        update.setStatus(2);
        shortUrlMapper.updateById(update);

        // 关键一步：不删缓存，删除就不生效
        stringRedisTemplate.delete(CACHE_KEY_PREFIX + shortCode);
        // 空值标记也要清掉：它现在应该变成「确实不存在」，
        // 留着也没错（60s 后自动过期），但清掉能让语义更干净
        stringRedisTemplate.delete(MISS_KEY_PREFIX + shortCode);

        log.info("短链已删除: userId={}, shortCode={}", userId, shortCode);
    }

    /** 访问统计：全量 PV/UV + 按天明细。同样要校验归属。 */
    public StatsVO stats(String shortCode, Long userId, int days) {
        requireOwned(shortCode, userId);

        Map<String, Object> total = accessLogMapper.selectTotal(shortCode);
        long totalPv = toLong(total == null ? null : total.get("pv"));
        long totalUv = toLong(total == null ? null : total.get("uv"));

        LocalDateTime since = LocalDate.now().minusDays(days - 1L).atStartOfDay();
        List<StatsVO.DailyStat> daily = new ArrayList<>();
        for (AccessLogMapper.DailyStatRow row : accessLogMapper.selectDailyStats(shortCode, since)) {
            daily.add(StatsVO.DailyStat.builder()
                    .date(String.valueOf(row.getStatDate()))
                    .pv(toLong(row.getPv()))
                    .uv(toLong(row.getUv()))
                    .build());
        }

        return StatsVO.builder()
                .shortCode(shortCode)
                .days(days)
                .totalPv(totalPv)
                .totalUv(totalUv)
                .daily(daily)
                .build();
    }

    // ------------------------------------------------------------------ 内部实现

    /**
     * 回源 DB 并回填缓存。并发回源时只放一个线程进来。
     *
     * <h3>缓存击穿是什么</h3>
     * 某个<b>热点</b>短码的缓存恰好过期，此刻 N 个并发请求同时未命中，
     * 于是 N 个请求一起去查 DB —— 本来 1 次查询能解决的事变成 N 次。
     *
     * <h3>解法：互斥锁重建</h3>
     * 只有一个线程能 SETNX 成功（拿到锁）去查 DB，其余线程自旋等待缓存被回填。
     * 代价是等待的线程有额外延迟（本项目最多 20 × 50ms = 1s），
     * 换来的是 DB 压力从 N 降到 1。
     *
     * <h3>另一种解法：逻辑过期（本项目的备选方案）</h3>
     * 缓存里存 {@code {url, expireAt}} 且永不过期，读到「逻辑已过期」时
     * 返回旧值 + 异步起线程重建。优点是<b>任何请求都不阻塞</b>（可用性更好），
     * 缺点是<b>会短暂读到旧数据</b>，且需要额外的重建线程池。
     * 短链映射「创建后基本不变」，两种方案都能接受；本项目选互斥锁是因为
     * 它不需要引入异步线程池，且「DB 查询从 N 降到 1」的对照更容易量化。
     */
    private String loadWithMutex(String shortCode, String cacheKey) {
        if (!mutexEnabled) {
            // 压测对照模式：不加锁，每个并发请求各自查 DB
            return queryDbAndCache(shortCode, cacheKey);
        }

        String lockKey = LOCK_KEY_PREFIX + shortCode;
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, LOCK_VALUE, Duration.ofSeconds(lockTtlSeconds));

        if (!Boolean.TRUE.equals(locked)) {
            // 没抢到锁 → 自旋等持锁线程把缓存填好
            for (int i = 0; i < spinMaxTimes; i++) {
                sleepQuietly(spinWaitMs);
                String v = stringRedisTemplate.opsForValue().get(cacheKey);
                if (v != null) {
                    return v;
                }
                if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(MISS_KEY_PREFIX + shortCode))) {
                    throw notFound("短链不存在");
                }
            }
            // 自旋超时兜底：可用性优先，宁可多查一次库也不让请求失败。
            // 这一步会让「DB 查询次数」略高于 1，是刻意的取舍，不是 bug。
            log.warn("等待缓存重建超时（{}ms），降级直查 DB: shortCode={}",
                    spinWaitMs * spinMaxTimes, shortCode);
            return queryDbAndCache(shortCode, cacheKey);
        }

        try {
            // 双重检查：等锁的这段时间里，前一个持锁者可能已经回填了缓存，
            // 不检查的话就白白多查一次 DB
            String again = stringRedisTemplate.opsForValue().get(cacheKey);
            if (again != null) {
                return again;
            }
            return queryDbAndCache(shortCode, cacheKey);
        } finally {
            unlock(lockKey);
        }
    }

    /** 真正的 DB 查询 + 缓存回填。所有回源路径最终都收敛到这里。 */
    private String queryDbAndCache(String shortCode, String cacheKey) {
        ShortUrl entity = shortUrlMapper.selectOne(
                new LambdaQueryWrapper<ShortUrl>().eq(ShortUrl::getShortCode, shortCode));

        if (entity == null || !Integer.valueOf(1).equals(entity.getStatus())) {
            markMiss(shortCode);
            throw notFound("短链不存在");
        }
        if (entity.getExpireAt() != null && entity.getExpireAt().isBefore(LocalDateTime.now())) {
            markMiss(shortCode);
            throw notFound("短链已过期");
        }

        stringRedisTemplate.opsForValue()
                .set(cacheKey, entity.getOriginalUrl(), ttlWithJitter());
        return entity.getOriginalUrl();
    }

    /**
     * 带随机抖动的 TTL —— 缓存<b>雪崩</b>防护。
     *
     * <p>如果所有 key 都用固定的 24h，那么在某个时刻批量写入的 key 会在
     * 24 小时后的<b>同一秒</b>集体失效，瞬时流量全部压到 DB。
     * 在基准 TTL 上叠加 [0, 30min] 的随机抖动，就把失效时间摊平了。
     */
    private Duration ttlWithJitter() {
        long jitter = ttlJitterSeconds > 0
                ? ThreadLocalRandom.current().nextLong(ttlJitterSeconds + 1)
                : 0;
        return Duration.ofHours(ttlHours).plusSeconds(jitter);
    }

    private void markMiss(String shortCode) {
        if (nullTtlSeconds > 0) {
            stringRedisTemplate.opsForValue()
                    .set(MISS_KEY_PREFIX + shortCode, "1", Duration.ofSeconds(nullTtlSeconds));
        }
    }

    private void unlock(String lockKey) {
        try {
            stringRedisTemplate.execute(
                    UNLOCK_SCRIPT, Collections.singletonList(lockKey), LOCK_VALUE);
        } catch (Exception e) {
            // 释放失败不能影响主流程：锁有 TTL 兜底，最坏情况是这段时间内
            // 其他线程退化成「各自查库」，正确性不受影响
            log.warn("释放互斥锁失败（将由 TTL 自动过期）: {}", lockKey, e);
        }
    }

    /**
     * 取短链并校验归属。<b>所有管理类接口的共同入口</b> —— 集中一处，
     * 避免「某个新接口忘了加 user_id 过滤」这类越权漏洞。
     *
     * <h3>为什么越权返回 403 而不是 404</h3>
     * 严格来说 403 会<b>确认「这个短码存在」</b>（404 则不泄露存在性），
     * 对不可枚举的资源 ID 应该统一返回 404。
     * 但本项目短码是公开的：任何人都能通过跳转接口
     * （{@code GET /{shortCode}}）枚举出哪些码存在 —— 存在性本来就藏不住，
     * 所以 403 没有额外泄露，换来更清晰的语义。
     * <b>换成不可枚举的资源 ID 时，这个结论要重新评估。</b>
     */
    private ShortUrl requireOwned(String shortCode, Long userId) {
        ShortUrl entity = shortUrlMapper.selectOne(
                new LambdaQueryWrapper<ShortUrl>().eq(ShortUrl::getShortCode, shortCode));
        if (entity == null || !Integer.valueOf(1).equals(entity.getStatus())) {
            throw notFound("短链不存在");
        }
        if (!Objects.equals(entity.getUserId(), userId)) {
            log.warn("越权访问被拦截: shortCode={}, 请求者={}, 归属者={}",
                    shortCode, userId, entity.getUserId());
            throw new BusinessException(403, "无权访问该短链");
        }
        return entity;
    }

    private ShortUrlDetailVO toDetail(ShortUrl entity) {
        String code = entity.getShortCode();
        return ShortUrlDetailVO.builder()
                .shortCode(code)
                .shortUrl(baseUrl + "/" + code)
                .originalUrl(entity.getOriginalUrl())
                .expireAt(entity.getExpireAt())
                .status(entity.getStatus())
                .pv(entity.getPv())
                .uv(entity.getUv())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private static long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(String.valueOf(v));
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(500, "请求被中断");
        }
    }

    private static BusinessException notFound(String msg) {
        return new BusinessException(404, msg);
    }
}
