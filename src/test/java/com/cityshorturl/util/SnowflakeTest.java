package com.cityshorturl.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 雪花算法单测。
 *
 * <p>这是 Step 2 的完成判据之一：<b>10 万次生成无重复</b>。
 * 光看代码"觉得对"不算数 —— 唯一性必须用集合去重实测。
 */
class SnowflakeTest {

    private final SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);

    @Test
    @DisplayName("单线程 10 万次生成，无重复且严格递增")
    void uniqueAndIncreasing() {
        int total = 100_000;
        Set<Long> seen = new HashSet<>(total * 2);
        long previous = -1L;

        for (int i = 0; i < total; i++) {
            long id = generator.nextId();
            assertTrue(id > 0, "ID 必须为正数，实际: " + id);
            assertTrue(seen.add(id), "出现重复 ID: " + id);
            assertTrue(id > previous, "ID 应严格递增，上一个大: " + previous + "，当前: " + id);
            previous = id;
        }
        assertEquals(total, seen.size());
    }

    @Test
    @DisplayName("8 线程并发 8 万次，无重复（验证 synchronized 的必要性）")
    void uniqueUnderConcurrency() throws Exception {
        int threads = 8;
        int perThread = 10_000;
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicates = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (!seen.add(generator.nextId())) {
                            duplicates.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发测试超时");
        pool.shutdown();

        assertEquals(0, duplicates.get(), "并发下出现重复 ID，synchronized 可能失效");
        assertEquals(threads * perThread, seen.size());
    }

    @Test
    @DisplayName("不同 workerId 生成的 ID 互不冲突")
    void differentWorkerIdNoCollision() {
        SnowflakeIdGenerator a = new SnowflakeIdGenerator(1, 1);
        SnowflakeIdGenerator b = new SnowflakeIdGenerator(2, 1);

        Set<Long> seen = Collections.synchronizedSet(new HashSet<>());
        for (int i = 0; i < 1000; i++) {
            assertTrue(seen.add(a.nextId()), "workerId=1 与其他机器冲突");
            assertTrue(seen.add(b.nextId()), "workerId=2 与其他机器冲突");
        }
    }

    @Test
    @DisplayName("workerId / datacenterId 越界时构造即失败")
    void rejectOutOfRangeIds() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(32, 1));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(1, 32));
    }
}
