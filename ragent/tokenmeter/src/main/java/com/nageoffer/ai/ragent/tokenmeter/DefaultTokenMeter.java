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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * {@link TokenMeter} 的默认实现（对应契约 4.7）。
 * <p>
 * 职责：
 * <ol>
 *   <li>从 {@link MeterContext#current()} 自动补齐 runId / experimentId / conversationId / turnIndex，
 *   埋点方无需层层透传</li>
 *   <li>用 {@link PricingTable} 把 token 换算成 {@code costUsd}（缓存部分按缓存价）</li>
 *   <li>补 {@code eventId} / {@code timestampMs}，投递到 {@link MeterSink}</li>
 * </ol>
 * <p>
 * 线程安全：是。除 sink 与 pricingTable（均线程安全/不可变）外无可变状态。
 */
public final class DefaultTokenMeter implements TokenMeter {

    private static final Logger log = LoggerFactory.getLogger(DefaultTokenMeter.class);

    /**
     * 本地计算环节默认的模型 id（零成本）。
     */
    public static final String LOCAL_MODEL_ID = PricingTable.LOCAL_MODEL_ID;

    /**
     * 本地计算环节默认的 provider。
     */
    public static final String LOCAL_PROVIDER = "local";

    private final MeterSink sink;
    private final PricingTable pricingTable;
    private final TokenEstimator inputEstimator;

    /**
     * 用默认定价表与启发式估算器构造。
     *
     * @param sink 计量事件接收器，不能为 null
     */
    public DefaultTokenMeter(MeterSink sink) {
        this(sink, PricingTable.defaultTable(), HeuristicTokenEstimator.INSTANCE);
    }

    /**
     * 完整构造。
     *
     * @param sink           计量事件接收器，不能为 null
     * @param pricingTable   定价表，不能为 null
     * @param inputEstimator 输入 token 估算器，不能为 null
     * @throws NullPointerException 任一参数为 null 时抛出
     */
    public DefaultTokenMeter(MeterSink sink, PricingTable pricingTable, TokenEstimator inputEstimator) {
        this.sink = Objects.requireNonNull(sink, "sink 不能为 null");
        this.pricingTable = Objects.requireNonNull(pricingTable, "pricingTable 不能为 null");
        this.inputEstimator = Objects.requireNonNull(inputEstimator, "inputEstimator 不能为 null");
    }

    /**
     * 直接记录一次已发生的调用。
     * <p>
     * 校验与归一化：
     * - 负数 token 按 0 处理
     * - {@code cachedInputTokens} 超过 {@code inputTokens} 时按输入总数截断
     * - {@code cacheHit=true} 且未显式给缓存 token 时，视为全部输入命中缓存（契约 4.6 语义）
     *
     * @param stage             环节，不能为 null
     * @param modelId           模型 id；null 或空时按 "local" 处理
     * @param provider          提供方；null 或空时按 "none" 处理
     * @param inputTokens       输入 token 总数
     * @param outputTokens      输出 token 数
     * @param cachedInputTokens 其中命中缓存的输入 token
     * @param cacheHit          是否整体命中响应缓存
     * @param latencyMs         耗时（毫秒，负数按 0）
     * @param traceTag          可选备注
     * @return 实际投递的事件
     * @throws IllegalArgumentException stage 为 null 时抛出
     */
    @Override
    public TokenUsage record(MeterStage stage, String modelId, String provider,
                             int inputTokens, int outputTokens, int cachedInputTokens,
                             boolean cacheHit, long latencyMs, String traceTag) {
        if (stage == null) {
            throw new IllegalArgumentException("TokenMeter.record 的 stage 不能为 null");
        }

        String effectiveModelId = normalize(modelId, LOCAL_MODEL_ID);
        String effectiveProvider = normalize(provider, "none");
        int in = Math.max(inputTokens, 0);
        int out = Math.max(outputTokens, 0);
        int cachedIn = Math.max(cachedInputTokens, 0);
        if (cacheHit && cachedIn == 0) {
            // 契约 4.6：命中时 cachedInputTokens = inputTokens
            cachedIn = in;
        }
        cachedIn = Math.min(cachedIn, in);

        double cost = pricingTable.cost(effectiveModelId, in, out, cachedIn);
        MeterContext ctx = MeterContext.current();

        TokenUsage usage = TokenUsage.builder()
                .runId(ctx == null ? null : ctx.runId())
                .experimentId(ctx == null ? null : ctx.experimentId())
                .conversationId(ctx == null ? null : ctx.conversationId())
                .turnIndex(ctx == null ? null : ctx.turnIndex())
                .stage(stage)
                .modelId(effectiveModelId)
                .provider(effectiveProvider)
                .inputTokens(in)
                .outputTokens(out)
                .cachedInputTokens(cachedIn)
                .cacheHit(cacheHit)
                .latencyMs(Math.max(latencyMs, 0L))
                .costUsd(cost)
                .traceTag(traceTag)
                .build();

        sink.accept(usage);
        return usage;
    }

    /**
     * 包住一次调用，自动测耗时并记录。
     * <p>
     * 输入 token 用构造时注入的 {@link TokenEstimator} 对 {@code inputPayload} 估算；
     * 输出 token 由 {@code outputTokenFn} 计算（返回 null 或函数为 null 时记 0）。
     * 调用抛异常时不记录事件，异常原样抛出。
     *
     * @param stage         环节，不能为 null
     * @param modelId       模型 id；null 或空时按 "local" 处理
     * @param provider      提供方；null 或空时按 "none" 处理
     * @param inputPayload  请求载荷，可为 null
     * @param call          待测量的调用，不能为 null
     * @param outputTokenFn 输出 token 计算函数，可为 null
     * @param <T>           返回值类型
     * @return {@code call} 的返回值
     * @throws IllegalArgumentException stage 或 call 为 null 时抛出
     */
    @Override
    public <T> T measure(MeterStage stage, String modelId, String provider,
                         String inputPayload, Supplier<T> call,
                         ToIntFunction<T> outputTokenFn) {
        if (stage == null) {
            throw new IllegalArgumentException("TokenMeter.measure 的 stage 不能为 null");
        }
        if (call == null) {
            throw new IllegalArgumentException("TokenMeter.measure 的 call 不能为 null");
        }

        int estimatedInput = inputEstimator.estimate(inputPayload);
        long startNanos = System.nanoTime();
        T result;
        try {
            result = call.get();
        } catch (RuntimeException e) {
            // 失败调用不计费，避免把异常路径也算成一次成功消耗
            log.debug("计量包装的调用失败，跳过记录: stage={}, modelId={}", stage, modelId, e);
            throw e;
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        int outputTokens = 0;
        if (result != null && outputTokenFn != null) {
            outputTokens = Math.max(outputTokenFn.applyAsInt(result), 0);
        }

        record(stage, modelId, provider, estimatedInput, outputTokens, 0, false, elapsedMs, null);
        return result;
    }

    /**
     * 关联的 sink。
     *
     * @return sink
     */
    @Override
    public MeterSink sink() {
        return sink;
    }

    /**
     * 重置：清空 sink 内已收集事件（jsonl sink 不支持截断，调用其 flush）。
     */
    @Override
    public void reset() {
        if (sink instanceof InMemoryMeterSink memorySink) {
            memorySink.clear();
        } else {
            sink.flush();
        }
    }

    /**
     * 定价表（供调用方查价）。
     *
     * @return 定价表
     */
    public PricingTable pricingTable() {
        return pricingTable;
    }

    /**
     * 输入 token 估算器。
     *
     * @return 估算器
     */
    public TokenEstimator inputEstimator() {
        return inputEstimator;
    }

    /**
     * 空字符串/null 归一化为默认值。
     *
     * @param value        原值
     * @param defaultValue 默认值
     * @return 归一化后的值
     */
    private static String normalize(String value, String defaultValue) {
        return (value == null || value.isEmpty()) ? defaultValue : value;
    }
}
