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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LruResponseCache} 单测。
 * <p>
 * 覆盖点：
 * - 未命中返回 null 且 miss 计数递增
 * - 命中返回值且 hit 计数递增
 * - hitRate 计算（含无查询时的 0.0）
 * - reset 清空内容与统计
 * - LRU 淘汰：超出容量时淘汰最久未访问的 key
 * - accessOrder 生效：访问过的 key 不会被优先淘汰
 * - static key(...) 稳定且长度 32
 * - 构造校验：容量非正抛异常
 */
class LruResponseCacheTest {

    @Test
    @DisplayName("未命中返回 null 并累计 miss")
    void missReturnsNull() {
        LruResponseCache cache = new LruResponseCache(10);
        assertNull(cache.get("absent"));
        assertEquals(0L, cache.hits());
        assertEquals(1L, cache.misses());
        assertEquals(0.0d, cache.hitRate());
    }

    @Test
    @DisplayName("命中返回值并累计 hit")
    void hitReturnsValue() {
        LruResponseCache cache = new LruResponseCache(10);
        cache.put("k1", "v1");
        assertEquals("v1", cache.get("k1"));
        assertEquals(1L, cache.hits());
        assertEquals(0L, cache.misses());
        assertEquals(1.0d, cache.hitRate());
    }

    @Test
    @DisplayName("命中率 = hits / (hits + misses)")
    void hitRateComputation() {
        LruResponseCache cache = new LruResponseCache(10);
        cache.put("k1", "v1");
        cache.put("k2", "v2");
        cache.get("k1");   // hit
        cache.get("k2");   // hit
        cache.get("k3");   // miss
        cache.get("k4");   // miss
        assertEquals(2L, cache.hits());
        assertEquals(2L, cache.misses());
        assertEquals(0.5d, cache.hitRate(), 1e-12);
    }

    @Test
    @DisplayName("无任何查询时命中率为 0.0，不抛异常")
    void hitRateWithoutQueries() {
        LruResponseCache cache = new LruResponseCache(10);
        assertEquals(0.0d, cache.hitRate());
        assertEquals(0L, cache.hits());
        assertEquals(0L, cache.misses());
    }

    @Test
    @DisplayName("reset 清空内容与统计")
    void resetClearsAll() {
        LruResponseCache cache = new LruResponseCache(10);
        cache.put("k1", "v1");
        cache.get("k1");
        cache.get("k2");
        assertEquals(1, cache.size());
        assertEquals(1L, cache.hits());

        cache.reset();

        assertEquals(0, cache.size());
        assertEquals(0L, cache.hits());
        assertEquals(0L, cache.misses());
        assertEquals(0.0d, cache.hitRate());
        assertNull(cache.get("k1"));
    }

    @Test
    @DisplayName("LRU 淘汰：超出容量时淘汰最久未访问的 key")
    void lruEviction() {
        LruResponseCache cache = new LruResponseCache(3);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");
        assertEquals(3, cache.size());

        cache.put("d", "4"); // 容量 3，淘汰最久未访问的 a
        assertEquals(3, cache.size());
        assertNull(cache.get("a"));
        assertEquals("2", cache.get("b"));
        assertEquals("3", cache.get("c"));
        assertEquals("4", cache.get("d"));
    }

    @Test
    @DisplayName("accessOrder 生效：访问过的 key 不会被优先淘汰")
    void accessOrderKeepsRecentlyUsed() {
        LruResponseCache cache = new LruResponseCache(3);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");

        // 访问 a，使 b 成为最久未访问
        assertEquals("1", cache.get("a"));

        cache.put("d", "4"); // 应淘汰 b 而非 a
        assertEquals("1", cache.get("a"));
        assertNull(cache.get("b"));
        assertEquals("3", cache.get("c"));
        assertEquals("4", cache.get("d"));
    }

    @Test
    @DisplayName("put null 值被忽略（避免把未命中写成命中）")
    void putNullIgnored() {
        LruResponseCache cache = new LruResponseCache(10);
        cache.put("k", null);
        assertEquals(0, cache.size());
        assertNull(cache.get("k"));
        cache.put(null, "v");
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("get(null) 记为 miss")
    void getNullKeyIsMiss() {
        LruResponseCache cache = new LruResponseCache(10);
        assertNull(cache.get(null));
        assertEquals(1L, cache.misses());
    }

    @Test
    @DisplayName("默认容量为契约要求的 20000")
    void defaultCapacity() {
        assertEquals(20_000, new LruResponseCache().capacity());
        assertEquals(LruResponseCache.DEFAULT_CAPACITY, new LruResponseCache().capacity());
    }

    @Test
    @DisplayName("容量非正抛 IllegalArgumentException")
    void invalidCapacityRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LruResponseCache(0));
        assertThrows(IllegalArgumentException.class, () -> new LruResponseCache(-1));
    }

    @Test
    @DisplayName("static key：稳定、32 位 hex、不同输入不同 key")
    void staticKeyIsStableAndDistinct() {
        String k1 = ResponseCache.key(MeterStage.GENERATE, "deepseek-flash", "hello");
        String k2 = ResponseCache.key(MeterStage.GENERATE, "deepseek-flash", "hello");
        assertEquals(k1, k2, "同输入必须同 key");
        assertEquals(32, k1.length(), "key 应为 32 位 hex");
        assertTrue(k1.matches("[0-9a-f]{32}"), "key 应为小写 hex: " + k1);

        assertNotEquals(k1, ResponseCache.key(MeterStage.REWRITE, "deepseek-flash", "hello"));
        assertNotEquals(k1, ResponseCache.key(MeterStage.GENERATE, "qwen-plus", "hello"));
        assertNotEquals(k1, ResponseCache.key(MeterStage.GENERATE, "deepseek-flash", "world"));
    }

    @Test
    @DisplayName("static key：null 参数按空串参与计算，不抛异常")
    void staticKeyHandlesNull() {
        String key = ResponseCache.key(null, null, null);
        assertEquals(32, key.length());
        assertEquals(key, ResponseCache.key(null, null, null));
    }

    @Test
    @DisplayName("缓存语义：命中后可用于生成 cacheHit 事件")
    void cacheSemanticsForMetering() {
        LruResponseCache cache = new LruResponseCache(10);
        String key = ResponseCache.key(MeterStage.GENERATE, "deepseek-flash", "prompt");
        String first = cache.get(key);
        assertNull(first, "首次未命中");
        cache.put(key, "cached-answer");

        String second = cache.get(key);
        assertEquals("cached-answer", second, "二次命中");
        assertEquals(1L, cache.hits());
        assertEquals(1L, cache.misses());
        assertEquals(0.5d, cache.hitRate(), 1e-12);
        assertFalse(cache.hitRate() > 0.5d);
    }
}
