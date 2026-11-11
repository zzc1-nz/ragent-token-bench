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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * 单次计量事件（不可变，对应契约 4.2）。
 * <p>
 * 用途：记录一次 LLM / Embedding / Rerank 调用（或一次本地计算环节）的完整消耗画像，
 * 是计量 SDK 的核心数据单元，也是 events.jsonl 的一行。
 * <p>
 * 约定：
 * - 字段名与顺序严格按契约 4.2，序列化后为 camelCase，供 bench / dashboard 直接消费
 * - {@code inputTokens} 为输入 token 总数（含命中缓存的部分）
 * - {@code cachedInputTokens} 是其中命中缓存、按折扣价计费的部分，恒 ≤ {@code inputTokens}
 * - {@code costUsd} 由 {@link DefaultTokenMeter} 按 {@link PricingTable} 算好，事件本身不再重算
 * - 构造推荐使用 {@link #builder()}：自动补 {@code eventId}（UUID）与 {@code timestampMs}（当前时间）
 * - 所有 record 组件都显式标注 {@link JsonProperty}，保证 Jackson 序列化/反序列化字段名稳定
 * （bench 会读回 events.jsonl 做聚合，见 {@link TokenUsageJson}）
 * <p>
 * 线程安全：record 不可变，可安全跨线程共享。
 *
 * @param eventId           事件唯一 ID（UUID 字符串）
 * @param runId             实验运行 ID（一次 benchmark run）
 * @param experimentId      策略组合 ID，如 "chunk=SEMANTIC|retrieval=FUSION|routing=INTENT_ROUTED"
 * @param conversationId    会话 ID（ETL 阶段为 null）
 * @param turnIndex         第几轮对话（ETL 阶段为 null）
 * @param stage             所属流水线环节
 * @param modelId           实际命中的模型 id，如 "deepseek-flash"；本地计算填 "local"
 * @param provider          模型提供方：deepseek / bailian / local / none
 * @param inputTokens       输入 token 总数（含缓存命中部分）
 * @param outputTokens      输出 token 总数
 * @param cachedInputTokens 其中命中缓存、按折扣计价的输入 token
 * @param cacheHit          整次调用是否完全命中响应缓存
 * @param latencyMs         调用耗时（毫秒）
 * @param costUsd           已按定价表算好的成本（USD）
 * @param timestampMs       事件时间戳（epoch 毫秒）
 * @param traceTag          可选备注，如 "batch:32"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenUsage(
        @JsonProperty("eventId") String eventId,
        @JsonProperty("runId") String runId,
        @JsonProperty("experimentId") String experimentId,
        @JsonProperty("conversationId") String conversationId,
        @JsonProperty("turnIndex") Integer turnIndex,
        @JsonProperty("stage") MeterStage stage,
        @JsonProperty("modelId") String modelId,
        @JsonProperty("provider") String provider,
        @JsonProperty("inputTokens") int inputTokens,
        @JsonProperty("outputTokens") int outputTokens,
        @JsonProperty("cachedInputTokens") int cachedInputTokens,
        @JsonProperty("cacheHit") boolean cacheHit,
        @JsonProperty("latencyMs") long latencyMs,
        @JsonProperty("costUsd") double costUsd,
        @JsonProperty("timestampMs") long timestampMs,
        @JsonProperty("traceTag") String traceTag
) {

    /**
     * 紧凑构造器：仅做最小校验，保证聚合阶段不会因 stage 为 null 而崩溃。
     *
     * @throws IllegalArgumentException stage 为 null 时抛出
     */
    public TokenUsage {
        if (stage == null) {
            throw new IllegalArgumentException("TokenUsage.stage 不能为空");
        }
    }

    /**
     * 本次调用消耗的总 token（输入 + 输出）。
     *
     * @return 总 token 数
     */
    public int totalTokens() {
        return inputTokens + outputTokens;
    }

    /**
     * 创建构建器。
     *
     * @return 新的 {@link Builder} 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@link TokenUsage} 的流式构建器。
     * <p>
     * 用途：record 字段较多，用 builder 避免 16 个位置参数写错顺序。
     * {@link #build()} 会在未显式设置时补 {@code eventId} 与 {@code timestampMs}。
     * 本类非线程安全，仅在单线程内构建后即丢弃。
     */
    public static final class Builder {

        private String eventId;
        private String runId;
        private String experimentId;
        private String conversationId;
        private Integer turnIndex;
        private MeterStage stage;
        private String modelId;
        private String provider;
        private int inputTokens;
        private int outputTokens;
        private int cachedInputTokens;
        private boolean cacheHit;
        private long latencyMs;
        private double costUsd;
        private long timestampMs;
        private String traceTag;

        private Builder() {
        }

        /**
         * 设置事件 ID；不设置时由 {@link #build()} 生成 UUID。
         *
         * @param eventId 事件 ID
         * @return this
         */
        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        /**
         * 设置实验运行 ID。
         *
         * @param runId 运行 ID
         * @return this
         */
        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        /**
         * 设置策略组合 ID。
         *
         * @param experimentId 实验 ID
         * @return this
         */
        public Builder experimentId(String experimentId) {
            this.experimentId = experimentId;
            return this;
        }

        /**
         * 设置会话 ID。
         *
         * @param conversationId 会话 ID，ETL 阶段可为 null
         * @return this
         */
        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        /**
         * 设置对话轮次。
         *
         * @param turnIndex 轮次索引，ETL 阶段可为 null
         * @return this
         */
        public Builder turnIndex(Integer turnIndex) {
            this.turnIndex = turnIndex;
            return this;
        }

        /**
         * 设置环节。
         *
         * @param stage 环节，不能为 null
         * @return this
         */
        public Builder stage(MeterStage stage) {
            this.stage = stage;
            return this;
        }

        /**
         * 设置模型 id。
         *
         * @param modelId 模型 id
         * @return this
         */
        public Builder modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        /**
         * 设置模型提供方。
         *
         * @param provider 提供方
         * @return this
         */
        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        /**
         * 设置输入 token 总数。
         *
         * @param inputTokens 输入 token
         * @return this
         */
        public Builder inputTokens(int inputTokens) {
            this.inputTokens = inputTokens;
            return this;
        }

        /**
         * 设置输出 token 总数。
         *
         * @param outputTokens 输出 token
         * @return this
         */
        public Builder outputTokens(int outputTokens) {
            this.outputTokens = outputTokens;
            return this;
        }

        /**
         * 设置命中缓存的输入 token 数。
         *
         * @param cachedInputTokens 缓存命中输入 token
         * @return this
         */
        public Builder cachedInputTokens(int cachedInputTokens) {
            this.cachedInputTokens = cachedInputTokens;
            return this;
        }

        /**
         * 设置是否整体命中响应缓存。
         *
         * @param cacheHit 是否命中
         * @return this
         */
        public Builder cacheHit(boolean cacheHit) {
            this.cacheHit = cacheHit;
            return this;
        }

        /**
         * 设置耗时。
         *
         * @param latencyMs 耗时（毫秒）
         * @return this
         */
        public Builder latencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        /**
         * 设置成本（USD）。
         *
         * @param costUsd 成本
         * @return this
         */
        public Builder costUsd(double costUsd) {
            this.costUsd = costUsd;
            return this;
        }

        /**
         * 设置事件时间戳；不设置或设置为 0 时由 {@link #build()} 取当前时间。
         *
         * @param timestampMs epoch 毫秒
         * @return this
         */
        public Builder timestampMs(long timestampMs) {
            this.timestampMs = timestampMs;
            return this;
        }

        /**
         * 设置备注。
         *
         * @param traceTag 备注
         * @return this
         */
        public Builder traceTag(String traceTag) {
            this.traceTag = traceTag;
            return this;
        }

        /**
         * 构建不可变事件。
         *
         * @return 计量事件
         * @throws IllegalArgumentException stage 为 null 时抛出
         */
        public TokenUsage build() {
            return new TokenUsage(
                    eventId == null ? UUID.randomUUID().toString() : eventId,
                    runId,
                    experimentId,
                    conversationId,
                    turnIndex,
                    stage,
                    modelId,
                    provider,
                    inputTokens,
                    outputTokens,
                    cachedInputTokens,
                    cacheHit,
                    latencyMs,
                    costUsd,
                    timestampMs == 0L ? System.currentTimeMillis() : timestampMs,
                    traceTag
            );
        }
    }
}
