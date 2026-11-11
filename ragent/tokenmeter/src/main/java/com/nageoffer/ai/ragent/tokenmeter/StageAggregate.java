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

/**
 * 分环节聚合结果（对应契约 4.8）。
 * <p>
 * 用途：回答「Token 花在了哪个环节」。{@link MeterAggregator} 按 {@link MeterStage} 分组后产出本记录，
 * 供 dashboard 的「各环节 Token 占比」环形图与报告的分环节归因表直接消费。
 *
 * @param stage             环节
 * @param calls             调用次数（该环节的事件数）
 * @param inputTokens       输入 token 合计（含缓存命中部分）
 * @param outputTokens      输出 token 合计
 * @param cachedInputTokens 缓存命中的输入 token 合计
 * @param cacheHits         命中缓存的调用次数（{@code cacheHit=true} 的事件数）
 * @param totalCostUsd      成本合计（USD）
 * @param totalLatencyMs    耗时合计（毫秒）
 */
public record StageAggregate(
        @JsonProperty("stage") MeterStage stage,
        @JsonProperty("calls") int calls,
        @JsonProperty("inputTokens") long inputTokens,
        @JsonProperty("outputTokens") long outputTokens,
        @JsonProperty("cachedInputTokens") long cachedInputTokens,
        @JsonProperty("cacheHits") long cacheHits,
        @JsonProperty("totalCostUsd") double totalCostUsd,
        @JsonProperty("totalLatencyMs") long totalLatencyMs
) {

    /**
     * 该环节消耗的总 token。
     *
     * @return 输入 + 输出
     */
    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    /**
     * 该环节的平均耗时。
     *
     * @return {@code totalLatencyMs / calls}；无调用时返回 0.0
     */
    public double avgLatencyMs() {
        return calls == 0 ? 0.0d : (double) totalLatencyMs / calls;
    }
}
