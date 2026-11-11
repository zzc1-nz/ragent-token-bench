/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.tokenmeter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LRU 响应缓存（对应契约 4.6）。
 * <p>
 * 用途：{@link ResponseCache} 的默认实现，用于在基准测试中产生真实的缓存命中数据。
 * <p>
 * 实现要点：
 * - 底层 {@link LinkedHashMap}（{@code accessOrder=true}）实现 LRU 淘汰
 * - 所有读写都在 {@code synchronized} 块内完成（契约要求同步块），保证线程安全
 * - 命中/未命中计数用 {@link AtomicLong}，便于无锁读取统计
 * - 容量默认 20000；容量必须为正数
 * <p>
 * 线程安全：是。
 */
public final class LruResponseCache implements ResponseCache {

    /**
     * 默认容量（契约 4.6）。
     */
    public static final int DEFAULT_CAPACITY = 20_000;

    private final int capacity;
    private final LinkedHashMap<String, String> store;
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();

    /**
     * 用默认容量构造。
     */
    public LruResponseCache() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 指定容量构造。
     *
     * @param capacity 最大条目数，必须为正
     * @throws IllegalArgumentException capacity <= 0 时抛出
     */
    public LruResponseCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("LruResponseCache.capacity 必须为正数，实际=" + capacity);
        }
        this.capacity = capacity;
        this.store = new LinkedHashMap<>(Math.min(capacity, 1024), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > LruResponseCache.this.capacity;
            }
        };
    }

    /**
     * 查询缓存；命中返回值，未命中返回 null 并累加 miss。
     *
     * @param cacheKey 缓存 key
     * @return 缓存值或 null
     */
    @Override
    public String get(String cacheKey) {
        if (cacheKey == null) {
            missCount.incrementAndGet();
            return null;
        }
        synchronized (store) {
            String value = store.get(cacheKey);
            if (value == null) {
                missCount.incrementAndGet();
                return null;
            }
            hitCount.incrementAndGet();
            return value;
        }
    }

    /**
     * 写入缓存（值为 null 时忽略，避免把「未命中」写成「命中」）。
     *
     * @param cacheKey 缓存 key
     * @param value    缓存值
     */
    @Override
    public void put(String cacheKey, String value) {
        if (cacheKey == null || value == null) {
            return;
        }
        synchronized (store) {
            store.put(cacheKey, value);
        }
    }

    /**
     * 累计命中次数。
     *
     * @return 命中数
     */
    @Override
    public long hits() {
        return hitCount.get();
    }

    /**
     * 累计未命中次数。
     *
     * @return 未命中数
     */
    @Override
    public long misses() {
        return missCount.get();
    }

    /**
     * 命中率。
     *
     * @return {@code hits / (hits + misses)}；无查询时返回 0.0
     */
    @Override
    public double hitRate() {
        long hits = hitCount.get();
        long total = hits + missCount.get();
        return total == 0L ? 0.0d : (double) hits / total;
    }

    /**
     * 清空缓存内容并重置统计。
     */
    @Override
    public void reset() {
        synchronized (store) {
            store.clear();
        }
        hitCount.set(0L);
        missCount.set(0L);
    }

    /**
     * 当前缓存条目数（用于测试与观测）。
     *
     * @return 条目数
     */
    public int size() {
        synchronized (store) {
            return store.size();
        }
    }

    /**
     * 配置容量。
     *
     * @return 容量
     */
    public int capacity() {
        return capacity;
    }
}
