package com.cityshorturl.service;

import com.cityshorturl.mapper.ShortUrlMapper;
import com.google.common.base.Charsets;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 布隆过滤器：在 Redis 之前再拦一道，挡住「查不存在的短码」这类穿透流量。
 *
 * <h3>为什么要它</h3>
 * 缓存穿透的典型形态是：有人拿随机短码疯狂请求，Redis 全部未命中，
 * 于是每个请求都打到 MySQL —— 缓存形同虚设，DB 被拖垮。
 * 布隆过滤器用极少内存（10 万元素 + 1% 误判率约 120 KB）就能挡掉绝大多数非法请求。
 *
 * <h3>⚠ 选 Guava 本地布隆的代价（面试要主动说）</h3>
 * <ul>
 *   <li><b>多实例不一致</b>：每个实例各自加载一份，A 实例新增的短链，B 实例的布隆里没有
 *       → B 会误判为「不存在」而返回 404。当前靠「启动全量加载 + 单实例部署」规避。</li>
 *   <li><b>重启有冷启动窗口</b>：重启后需要重新从 DB 加载。</li>
 * </ul>
 * <b>生产做法</b>：改用 RedisBloom（Redis 的布隆模块）或 Redisson 的 RBloomFilter，
 * 让所有实例共享同一份位图。代价是多一次网络往返。
 * 本项目选 Guava 是因为演示环境单实例，且能把「布隆的位运算原理」讲清楚。
 *
 * <h3>🔴 异步加载的正确做法</h3>
 * 「启动时异步加载以免阻塞启动」听起来很对，但直接这么做有个致命 bug：
 * <b>加载完成前的窗口期，布隆是空的 —— 所有请求都会被判为「不存在」而返回 404</b>，
 * 真实短链也访问不了。
 *
 * <p>这里的解法是加一个 {@code ready} 标志：
 * <b>未就绪时保守放行</b>（返回 true，让请求继续走 Redis/DB），
 * 宁可多查一次库，也不能误杀真实短链。等加载完成再切换成真实判断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BloomFilterService {

    private final ShortUrlMapper shortUrlMapper;

    /** 预估元素数量。设置过小会导致误判率急剧上升（布隆一旦「满」了就没法扩容） */
    @Value("${app.bloom.expected-insertions:100000}")
    private int expectedInsertions;

    /** 可接受的误判率（假阳性）。越低占用内存越大 */
    @Value("${app.bloom.fpp:0.01}")
    private double fpp;

    private BloomFilter<String> filter;

    /** 加载是否完成。未完成时 {@link #mightContain} 保守放行 */
    private final AtomicBoolean ready = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        filter = BloomFilter.create(
                Funnels.stringFunnel(Charsets.UTF_8), expectedInsertions, fpp);

        // 异步加载：不阻塞应用启动
        Thread loader = new Thread(this::loadAll, "bloom-loader");
        loader.setDaemon(true);
        loader.start();
    }

    private void loadAll() {
        long start = System.currentTimeMillis();
        try {
            List<String> codes = shortUrlMapper.selectAllShortCodes();
            codes.forEach(filter::put);
            ready.set(true);
            log.info("布隆过滤器加载完成: 元素数={}, 预估容量={}, 误判率={}, 耗时={}ms",
                    codes.size(), expectedInsertions, fpp, System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 加载失败不阻断服务：ready 保持 false，所有请求保守放行，退化成「没有布隆」
            log.error("布隆过滤器加载失败，将退化为不做布隆拦截（不影响正确性，只影响性能）", e);
        }
    }

    /**
     * 判断短码<b>可能存在</b>。
     *
     * @return false = 一定不存在（可安全拦截）；true = 可能存在（需继续查 Redis/DB）
     */
    public boolean mightContain(String shortCode) {
        if (!ready.get()) {
            // 未就绪时保守放行，避免误杀真实短链
            return true;
        }
        return filter.mightContain(shortCode);
    }

    /** 新增短链后同步写入，否则新短链会被自己的布隆过滤器拦掉 */
    public void add(String shortCode) {
        filter.put(shortCode);
    }

    /** 供健康检查/排查用 */
    public boolean isReady() {
        return ready.get();
    }
}
