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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 响应缓存（对应契约 4.6）。
 * <p>
 * 用途：产生「缓存命中」的**真实数据**，而不是编造。缓存 key 由「环节 + 模型 + 请求载荷」决定，
 * 命中时下游可直接复用响应，并据此记录 {@code cacheHit=true}、{@code cachedInputTokens=inputTokens}。
 * <p>
 * 语义约定（契约 4.6）：
 * - 命中时 {@code cacheHit=true}、{@code cachedInputTokens=inputTokens}（按缓存价计费）
 * - {@code outputTokens} 仍按正常价计（输出来自缓存，但下游仍然消费）
 * <p>
 * 实现：默认 {@link LruResponseCache}（容量可配，默认 20000，LRU 淘汰）。
 */
public interface ResponseCache {

    /**
     * 查询缓存。
     *
     * @param cacheKey 缓存 key，通常由 {@link #key} 生成
     * @return 命中返回缓存值；未命中返回 null（并计入 miss）
     */
    String get(String cacheKey);

    /**
     * 写入缓存。
     *
     * @param cacheKey 缓存 key
     * @param value    缓存值
     */
    void put(String cacheKey, String value);

    /**
     * 命中次数。
     *
     * @return 累计命中数
     */
    long hits();

    /**
     * 未命中次数。
     *
     * @return 累计未命中数
     */
    long misses();

    /**
     * 命中率。
     *
     * @return {@code hits / (hits + misses)}；无任何查询时返回 0.0
     */
    double hitRate();

    /**
     * 清空缓存内容并重置命中统计。
     */
    void reset();

    /**
     * 生成稳定缓存 key：对 {@code stage + '|' + modelId + '|' + payload} 取 SHA-256，取前 32 位 hex。
     *
     * @param stage   环节，可为 null（按空串参与计算）
     * @param modelId 模型 id，可为 null（按空串参与计算）
     * @param payload 请求载荷，可为 null（按空串参与计算）
     * @return 32 字符小写 hex 字符串
     */
    static String key(MeterStage stage, String modelId, String payload) {
        String raw = (stage == null ? "" : stage.name())
                + "|" + (modelId == null ? "" : modelId)
                + "|" + (payload == null ? "" : payload);
        return sha256Hex32(raw);
    }

    /**
     * 计算字符串的 SHA-256 摘要，取前 32 位小写 hex。
     *
     * @param raw 原文（UTF-8 编码）
     * @return 32 字符小写 hex
     */
    static String sha256Hex32(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，理论上不可达
            throw new IllegalStateException("JDK 未提供 SHA-256 算法", e);
        }
    }
}
