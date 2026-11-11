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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 计量事件聚合器（对应契约 4.8）。
 * <p>
 * 用途：把一批 {@link TokenUsage} 事件汇聚成 {@link RunSummary} / {@link StageAggregate}，
 * 供报告生成器与 dashboard 消费。纯函数实现（无状态、无随机、不依赖时间），
 * 同一批输入必然产出同一结果，便于测试与复现。
 * <p>
 * 约定：
 * - 空输入返回 {@code eventCount=0} 的零值汇总，而不是 null
 * - {@code stages} 按 {@link MeterStage} 声明顺序输出（稳定，便于 diff 与图表排序）
 * - {@code tokensByModel} 按模型 id 字典序输出（稳定）
 * - 浮点累加使用 Kahan 补偿求和，避免上万事件累加后的精度漂移
 * <p>
 * 线程安全：无状态静态方法，可并发调用。
 */
public final class MeterAggregator {

    private MeterAggregator() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 汇总单个实验运行的事件。
     *
     * @param runId  实验运行 ID（可为 null）
     * @param events 事件列表（可为 null，视为空）
     * @return 汇总结果，永不为 null
     */
    public static RunSummary summarize(String runId, List<TokenUsage> events) {
        List<TokenUsage> safeEvents = events == null ? List.of() : events;

        Map<MeterStage, StageAccumulator> byStage = new EnumMap<>(MeterStage.class);
        Map<String, Long> tokensByModel = new TreeMap<>();
        KahanSum costSum = new KahanSum();

        long inputTokens = 0L;
        long outputTokens = 0L;
        long cachedInputTokens = 0L;
        long totalLatencyMs = 0L;
        int cacheHitEvents = 0;
        int eventCount = 0;
        String experimentId = null;

        for (TokenUsage usage : safeEvents) {
            if (usage == null) {
                continue;
            }
            eventCount++;
            inputTokens += usage.inputTokens();
            outputTokens += usage.outputTokens();
            cachedInputTokens += usage.cachedInputTokens();
            totalLatencyMs += usage.latencyMs();
            costSum.add(usage.costUsd());
            if (usage.cacheHit()) {
                cacheHitEvents++;
            }
            if (experimentId == null && usage.experimentId() != null) {
                experimentId = usage.experimentId();
            }

            StageAccumulator stageAcc = byStage.computeIfAbsent(usage.stage(), k -> new StageAccumulator());
            stageAcc.add(usage);

            String modelKey = usage.modelId() == null ? "unknown" : usage.modelId();
            tokensByModel.merge(modelKey, (long) usage.totalTokens(), Long::sum);
        }

        List<StageAggregate> stages = new ArrayList<>(byStage.size());
        // 按枚举声明顺序输出，保证稳定
        for (MeterStage stage : MeterStage.values()) {
            StageAccumulator acc = byStage.get(stage);
            if (acc != null) {
                stages.add(acc.toAggregate(stage));
            }
        }

        double cacheHitRate = eventCount == 0 ? 0.0d : (double) cacheHitEvents / eventCount;

        return new RunSummary(
                runId,
                experimentId,
                eventCount,
                inputTokens,
                outputTokens,
                inputTokens + outputTokens,
                cachedInputTokens,
                cacheHitRate,
                costSum.value(),
                totalLatencyMs,
                List.copyOf(stages),
                Map.copyOf(new LinkedHashMap<>(tokensByModel))
        );
    }

    /**
     * 按 runId 分组后逐个汇总。
     * <p>
     * 返回顺序为 runId 首次出现顺序（保持输入的事件时间序，便于阅读）；
     * {@code experimentId} 取该 run 内第一条带 experimentId 的事件。
     *
     * @param events 事件列表（可为 null，视为空）
     * @return 每个 run 一条汇总；输入为空时返回空列表
     */
    public static List<RunSummary> summarizeAll(List<TokenUsage> events) {
        List<TokenUsage> safeEvents = events == null ? List.of() : events;
        Map<String, List<TokenUsage>> grouped = new LinkedHashMap<>();
        for (TokenUsage usage : safeEvents) {
            if (usage == null) {
                continue;
            }
            grouped.computeIfAbsent(usage.runId(), k -> new ArrayList<>()).add(usage);
        }
        List<RunSummary> summaries = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<TokenUsage>> entry : grouped.entrySet()) {
            summaries.add(summarize(entry.getKey(), entry.getValue()));
        }
        return summaries;
    }

    /**
     * 按 {@link MeterStage} 累加的中间状态。
     * <p>
     * 非线程安全：仅在 {@link #summarize} 的单次调用内使用。
     */
    private static final class StageAccumulator {

        private int calls;
        private long inputTokens;
        private long outputTokens;
        private long cachedInputTokens;
        private long cacheHits;
        private long totalLatencyMs;
        private final KahanSum cost = new KahanSum();

        private void add(TokenUsage usage) {
            calls++;
            inputTokens += usage.inputTokens();
            outputTokens += usage.outputTokens();
            cachedInputTokens += usage.cachedInputTokens();
            totalLatencyMs += usage.latencyMs();
            if (usage.cacheHit()) {
                cacheHits++;
            }
            cost.add(usage.costUsd());
        }

        private StageAggregate toAggregate(MeterStage stage) {
            return new StageAggregate(stage, calls, inputTokens, outputTokens,
                    cachedInputTokens, cacheHits, cost.value(), totalLatencyMs);
        }
    }

    /**
     * Kahan 补偿求和，减少大量浮点累加的精度损失。
     * <p>
     * 非线程安全：仅在单次聚合调用内使用。
     */
    private static final class KahanSum {

        private double sum;
        private double compensation;

        private void add(double value) {
            double y = value - compensation;
            double t = sum + y;
            compensation = (t - sum) - y;
            sum = t;
        }

        private double value() {
            return sum;
        }
    }
}
