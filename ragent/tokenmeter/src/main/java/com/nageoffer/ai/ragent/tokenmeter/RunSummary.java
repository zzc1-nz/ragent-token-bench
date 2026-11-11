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

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 单次实验运行的汇总结果（对应契约 4.8）。
 * <p>
 * 用途：bench 每个 {@code runId + experimentId} 跑完后产出的核心指标集合，
 * 是 summary.json（契约 6.4）里 {@code experiments[]} 各项数据的来源。
 * <p>
 * 字段语义：
 * - {@code eventCount} 是事件数（不等于调用数：一个环节可能有多条事件）
 * - {@code cacheHitRate} 按事件数计（{@code cacheHit=true 的事件数 / eventCount}）
 * - {@code tokensByModel} 是各模型总 token（输入+输出）的分布，用于「模型路由省了多少」分析
 *
 * @param runId             实验运行 ID
 * @param experimentId      策略组合 ID
 * @param eventCount        事件总数
 * @param inputTokens       输入 token 合计
 * @param outputTokens      输出 token 合计
 * @param totalTokens       总 token（输入 + 输出）
 * @param cachedInputTokens 缓存命中的输入 token 合计
 * @param cacheHitRate      缓存命中率（按事件计），无事件时为 0.0
 * @param totalCostUsd      成本合计（USD）
 * @param totalLatencyMs    耗时合计（毫秒）
 * @param stages            分环节聚合，按 {@link MeterStage} 声明顺序
 * @param tokensByModel     各模型 token 合计
 */
public record RunSummary(
        @JsonProperty("runId") String runId,
        @JsonProperty("experimentId") String experimentId,
        @JsonProperty("eventCount") int eventCount,
        @JsonProperty("inputTokens") long inputTokens,
        @JsonProperty("outputTokens") long outputTokens,
        @JsonProperty("totalTokens") long totalTokens,
        @JsonProperty("cachedInputTokens") long cachedInputTokens,
        @JsonProperty("cacheHitRate") double cacheHitRate,
        @JsonProperty("totalCostUsd") double totalCostUsd,
        @JsonProperty("totalLatencyMs") long totalLatencyMs,
        @JsonProperty("stages") List<StageAggregate> stages,
        @JsonProperty("tokensByModel") Map<String, Long> tokensByModel
) {

    /**
     * 平均每次事件的耗时。
     *
     * @return {@code totalLatencyMs / eventCount}；无事件时返回 0.0
     */
    public double avgLatencyMs() {
        return eventCount == 0 ? 0.0d : (double) totalLatencyMs / eventCount;
    }

    /**
     * 平均每次事件的成本。
     *
     * @return {@code totalCostUsd / eventCount}；无事件时返回 0.0
     */
    public double costPerEventUsd() {
        return eventCount == 0 ? 0.0d : totalCostUsd / eventCount;
    }

    /**
     * 平均每次事件的 token 数。
     *
     * @return {@code totalTokens / eventCount}；无事件时返回 0.0
     */
    public double tokensPerEvent() {
        return eventCount == 0 ? 0.0d : (double) totalTokens / eventCount;
    }
}
