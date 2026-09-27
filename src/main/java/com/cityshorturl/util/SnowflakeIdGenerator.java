package com.cityshorturl.util;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 雪花算法 ID 生成器。
 *
 * <h3>64 位怎么分的</h3>
 * <pre>
 *   1 位符号位（恒 0，保证 ID 为正数）
 * | 41 位时间戳（相对 EPOCH 的毫秒差，够用 69 年）
 * |  5 位 datacenterId（最多 32 个机房）
 * |  5 位 workerId    （每机房最多 32 台机器）
 * | 12 位 sequence    （同一毫秒内最多 4096 个 ID）
 * </pre>
 * 理论峰值：单机 4096 ID/ms ≈ 409 万 ID/秒，对短链场景绰绰有余。
 *
 * <h3>为什么用雪花而不是自增</h3>
 * 自增需要中心化发号器（DB 或 Redis），既多一次网络往返，又引入单点。
 * 雪花在本地就能算出全局唯一 ID，无网络开销。
 * <b>代价</b>：ID 偏大（约 3.6e17），转 base62 后是 <b>10 位</b>而不是 6-8 位
 * —— 详见 {@link Base62} 的类注释。
 *
 * <h3>时钟回拨怎么处理（面试高频）</h3>
 * 服务器 NTP 校时可能让时钟往后退。若直接生成，会算出与历史重复的 ID。
 * 这里的策略是<b>分级处理</b>：
 * <ul>
 *   <li>回拨 ≤ {@value #MAX_BACKWARD_MS} ms：自旋等到时钟追上，对调用方无感</li>
 *   <li>回拨 &gt; 该阈值：抛异常拒绝生成。宁可报错，也不能发出重复 ID</li>
 * </ul>
 * 生产上更彻底的做法是「备用 workerId」——回拨时切换到一个新的 workerId 继续服务，
 * 但这需要 workerId 的分配与回收机制，本项目规模下不值得。
 */
@Slf4j
@Component
public class SnowflakeIdGenerator {

    /** 起始时间戳：2024-01-01 00:00:00 UTC。取值越近，ID 越小、base62 越短 */
    private static final long EPOCH = 1704038400000L;

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);         // 31
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS); // 31

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;                              // 12
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;         // 17
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS; // 22

    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);          // 4095

    /** 可容忍的时钟回拨毫秒数 */
    private static final long MAX_BACKWARD_MS = 5L;

    private final long workerId;
    private final long datacenterId;

    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator(@Value("${snowflake.worker-id:1}") long workerId,
                                @Value("${snowflake.datacenter-id:1}") long datacenterId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId 必须在 [0, " + MAX_WORKER_ID + "] 之间");
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException("datacenterId 必须在 [0, " + MAX_DATACENTER_ID + "] 之间");
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    @PostConstruct
    public void logConfig() {
        log.info("雪花算法初始化: workerId={}, datacenterId={}, epoch={}", workerId, datacenterId, EPOCH);
    }

    /**
     * 生成下一个 ID。
     *
     * <p>{@code synchronized}：sequence 与 lastTimestamp 是有状态的可变字段，
     * 单机必须串行。这也是雪花的已知瓶颈——真到单机 400 万 QPS 时，
     * 解法是按线程分桶（每个线程持有独立 sequence），而不是去掉锁。
     */
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        if (timestamp < lastTimestamp) {
            long backward = lastTimestamp - timestamp;
            if (backward <= MAX_BACKWARD_MS) {
                log.warn("检测到时钟回拨 {}ms（≤{}ms），等待时钟追上", backward, MAX_BACKWARD_MS);
                timestamp = waitUntil(lastTimestamp);
            } else {
                throw new IllegalStateException(
                        "时钟回拨 " + backward + "ms，超过容忍阈值 " + MAX_BACKWARD_MS + "ms，拒绝生成 ID");
            }
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 同一毫秒内 4096 个序号用完了，等到下一毫秒
                timestamp = waitUntil(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /** 自旋等待到指定时间戳之后 */
    private long waitUntil(long target) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= target) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
