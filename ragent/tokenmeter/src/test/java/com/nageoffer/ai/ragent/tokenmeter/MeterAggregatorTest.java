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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MeterAggregator} 单测。
 * <p>
 * 覆盖点：
 * - 分环节归因：每个 stage 的 calls / token / 成本 / 耗时独立累加，不串味
 * - 总量正确性：totalTokens = input + output，成本求和
 * - 缓存命中率按事件计
 * - tokensByModel 按模型聚合
 * - stages 按枚举声明顺序输出
 * - 空输入返回零值汇总而非 null
 * - summarizeAll 按 runId 分组
 */
class MeterAggregatorTest {

    private static final double DELTA = 1e-12;

    @Test
    @DisplayName("分环节归因：GENERATE 与 REWRITE 各自独立累加")
    void stageAttribution() {
        List<TokenUsage> events = List.of(
                usage("RUN_1", "EXP_A", MeterStage.GENERATE, "deepseek-chat", 1000, 500, 0, false, 120L, 0.00082),
                usage("RUN_1", "EXP_A", MeterStage.GENERATE, "deepseek-chat", 2000, 800, 0, false, 200L, 0.00142),
                usage("RUN_1", "EXP_A", MeterStage.REWRITE, "qwen-flash", 300, 100, 0, false, 50L, 0.000055)
        );

        RunSummary summary = MeterAggregator.summarize("RUN_1", events);

        assertEquals(3, summary.eventCount());
        assertEquals("RUN_1", summary.runId());
        assertEquals("EXP_A", summary.experimentId());
        assertEquals(3300L, summary.inputTokens());
        assertEquals(1400L, summary.outputTokens());
        assertEquals(4700L, summary.totalTokens());
        assertEquals(370L, summary.totalLatencyMs());

        assertEquals(2, summary.stages().size());
        StageAggregate generate = findStage(summary, MeterStage.GENERATE);
        assertEquals(2, generate.calls());
        assertEquals(3000L, generate.inputTokens());
        assertEquals(1300L, generate.outputTokens());
        assertEquals(4300L, generate.totalTokens());
        assertEquals(320L, generate.totalLatencyMs());
        assertEquals(160.0d, generate.avgLatencyMs(), DELTA);
        assertEquals(0.00082 + 0.00142, generate.totalCostUsd(), DELTA);

        StageAggregate rewrite = findStage(summary, MeterStage.REWRITE);
        assertEquals(1, rewrite.calls());
        assertEquals(300L, rewrite.inputTokens());
        assertEquals(100L, rewrite.outputTokens());
        assertEquals(50L, rewrite.totalLatencyMs());
        assertEquals(0.000055, rewrite.totalCostUsd(), DELTA);
    }

    @Test
    @DisplayName("缓存命中率按事件数计，cachedInputTokens 单独累加")
    void cacheMetrics() {
        List<TokenUsage> events = List.of(
                usage("RUN_1", "EXP_C", MeterStage.GENERATE, "deepseek-flash", 1000, 200, 1000, true, 10L, 0.0002),
                usage("RUN_1", "EXP_C", MeterStage.GENERATE, "deepseek-flash", 1000, 200, 0, false, 300L, 0.0004),
                usage("RUN_1", "EXP_C", MeterStage.RETRIEVE_VECTOR, "text-embedding-v4", 500, 0, 0, false, 20L, 0.00001),
                usage("RUN_1", "EXP_C", MeterStage.RETRIEVE_VECTOR, "text-embedding-v4", 500, 0, 0, false, 20L, 0.00001)
        );

        RunSummary summary = MeterAggregator.summarize("RUN_1", events);

        assertEquals(4, summary.eventCount());
        assertEquals(1000L, summary.cachedInputTokens());
        // 4 个事件中 1 个命中 -> 0.25
        assertEquals(0.25d, summary.cacheHitRate(), DELTA);

        StageAggregate generate = findStage(summary, MeterStage.GENERATE);
        assertEquals(1L, generate.cacheHits());
        assertEquals(1000L, generate.cachedInputTokens());
        assertEquals(2, generate.calls());

        StageAggregate retrieve = findStage(summary, MeterStage.RETRIEVE_VECTOR);
        assertEquals(0L, retrieve.cacheHits());
        assertEquals(0L, retrieve.cachedInputTokens());
        assertEquals(2, retrieve.calls());
    }

    @Test
    @DisplayName("tokensByModel 按模型聚合总 token")
    void tokensByModel() {
        List<TokenUsage> events = List.of(
                usage("RUN_1", "EXP_A", MeterStage.GENERATE, "deepseek-flash", 1000, 500, 0, false, 10L, 0.001),
                usage("RUN_1", "EXP_A", MeterStage.GENERATE, "deepseek-flash", 500, 200, 0, false, 10L, 0.001),
                usage("RUN_1", "EXP_A", MeterStage.GENERATE, "qwen-plus", 300, 100, 0, false, 10L, 0.001),
                usage("RUN_1", "EXP_A", MeterStage.FUSION, "local", 0, 0, 0, false, 5L, 0.0)
        );

        RunSummary summary = MeterAggregator.summarize("RUN_1", events);

        assertEquals(2200L, summary.tokensByModel().get("deepseek-flash")); // (1000+500)+(500+200)
        assertEquals(400L, summary.tokensByModel().get("qwen-plus"));
        assertEquals(0L, summary.tokensByModel().get("local"));
        assertEquals(3, summary.tokensByModel().size());
    }

    @Test
    @DisplayName("stages 按 MeterStage 枚举声明顺序输出（稳定）")
    void stageOrderIsEnumOrder() {
        List<TokenUsage> events = List.of(
                usage("RUN_1", "EXP_A", MeterStage.GENERATE, "m", 1, 1, 0, false, 1L, 0.0),
                usage("RUN_1", "EXP_A", MeterStage.PARSE, "m", 1, 1, 0, false, 1L, 0.0),
                usage("RUN_1", "EXP_A", MeterStage.RERANK, "m", 1, 1, 0, false, 1L, 0.0)
        );

        RunSummary summary = MeterAggregator.summarize("RUN_1", events);

        assertEquals(3, summary.stages().size());
        assertEquals(MeterStage.PARSE, summary.stages().get(0).stage());
        assertEquals(MeterStage.RERANK, summary.stages().get(1).stage());
        assertEquals(MeterStage.GENERATE, summary.stages().get(2).stage());
    }

    @Test
    @DisplayName("空输入返回零值汇总，不返回 null")
    void emptyInputReturnsZeroSummary() {
        RunSummary summary = MeterAggregator.summarize("RUN_EMPTY", List.of());
        assertNotNull(summary);
        assertEquals("RUN_EMPTY", summary.runId());
        assertEquals(0, summary.eventCount());
        assertEquals(0L, summary.inputTokens());
        assertEquals(0L, summary.outputTokens());
        assertEquals(0L, summary.totalTokens());
        assertEquals(0.0d, summary.cacheHitRate());
        assertEquals(0.0d, summary.totalCostUsd());
        assertTrue(summary.stages().isEmpty());
        assertTrue(summary.tokensByModel().isEmpty());
        assertEquals(0.0d, summary.avgLatencyMs());
        assertEquals(0.0d, summary.tokensPerEvent());

        // null 入参同样安全
        assertNotNull(MeterAggregator.summarize("RUN_EMPTY", null));
        assertTrue(MeterAggregator.summarizeAll(null).isEmpty());
    }

    @Test
    @DisplayName("汇总总量与各环节累加之和一致（守恒校验）")
    void totalsConservedAcrossStages() {
        List<TokenUsage> events = List.of(
                usage("RUN_1", "EXP_A", MeterStage.PARSE, "local", 0, 0, 0, false, 30L, 0.0),
                usage("RUN_1", "EXP_A", MeterStage.CHUNK, "local", 0, 0, 0, false, 20L, 0.0),
                usage("RUN_1", "EXP_A", MeterStage.EMBED, "text-embedding-v4", 12000, 0, 0, false, 80L, 0.00024),
                usage("RUN_1", "EXP_A", MeterStage.INTENT, "qwen-flash", 200, 20, 0, false, 40L, 0.000018),
                usage("RUN_1", "EXP_A", MeterStage.GENERATE, "deepseek-chat", 3000, 1200, 500, true, 900L, 0.00175)
        );

        RunSummary summary = MeterAggregator.summarize("RUN_1", events);

        long stageInputSum = summary.stages().stream().mapToLong(StageAggregate::inputTokens).sum();
        long stageOutputSum = summary.stages().stream().mapToLong(StageAggregate::outputTokens).sum();
        double stageCost = summary.stages().stream().mapToDouble(StageAggregate::totalCostUsd).sum();
        long stageLatencySum = summary.stages().stream().mapToLong(StageAggregate::totalLatencyMs).sum();

        assertEquals(summary.inputTokens(), stageInputSum);
        assertEquals(summary.outputTokens(), stageOutputSum);
        assertEquals(summary.totalLatencyMs(), stageLatencySum);
        assertEquals(summary.totalCostUsd(), stageCost, 1e-12);
        assertEquals(summary.totalTokens(), summary.inputTokens() + summary.outputTokens());
        assertEquals(5, summary.stages().stream().mapToInt(StageAggregate::calls).sum());
    }

    @Test
    @DisplayName("summarizeAll 按 runId 分组，保持首次出现顺序")
    void summarizeAllGroupsByRunId() {
        List<TokenUsage> events = List.of(
                usage("RUN_A", "EXP_1", MeterStage.GENERATE, "deepseek-chat", 100, 50, 0, false, 10L, 0.001),
                usage("RUN_B", "EXP_2", MeterStage.GENERATE, "qwen-plus", 200, 60, 0, false, 20L, 0.002),
                usage("RUN_A", "EXP_1", MeterStage.GENERATE, "deepseek-chat", 300, 70, 0, false, 30L, 0.003)
        );

        List<RunSummary> summaries = MeterAggregator.summarizeAll(events);

        assertEquals(2, summaries.size());
        assertEquals("RUN_A", summaries.get(0).runId());
        assertEquals(2, summaries.get(0).eventCount());
        assertEquals(400L, summaries.get(0).inputTokens());
        assertEquals(120L, summaries.get(0).outputTokens());
        assertEquals(40L, summaries.get(0).totalLatencyMs());

        assertEquals("RUN_B", summaries.get(1).runId());
        assertEquals(1, summaries.get(1).eventCount());
        assertEquals(260L, summaries.get(1).totalTokens());
    }

    @Test
    @DisplayName("跳过 null 事件，不影响其他事件统计")
    void nullEventsSkipped() {
        List<TokenUsage> events = java.util.Arrays.asList(
                usage("RUN_1", "EXP_A", MeterStage.GENERATE, "m", 100, 50, 0, false, 10L, 0.001),
                null,
                usage("RUN_1", "EXP_A", MeterStage.GENERATE, "m", 100, 50, 0, false, 10L, 0.001)
        );

        RunSummary summary = MeterAggregator.summarize("RUN_1", events);
        assertEquals(2, summary.eventCount());
        assertEquals(200L, summary.inputTokens());
    }

    /**
     * 构造一条测试事件。
     *
     * @param runId        运行 ID
     * @param experimentId 实验 ID
     * @param stage        环节
     * @param modelId      模型 id
     * @param in           输入 token
     * @param out          输出 token
     * @param cachedIn     缓存输入 token
     * @param cacheHit     是否命中缓存
     * @param latencyMs    耗时
     * @param costUsd      成本
     * @return 事件
     */
    private TokenUsage usage(String runId, String experimentId, MeterStage stage, String modelId,
                             int in, int out, int cachedIn, boolean cacheHit,
                             long latencyMs, double costUsd) {
        return TokenUsage.builder()
                .runId(runId)
                .experimentId(experimentId)
                .stage(stage)
                .modelId(modelId)
                .provider("test")
                .inputTokens(in)
                .outputTokens(out)
                .cachedInputTokens(cachedIn)
                .cacheHit(cacheHit)
                .latencyMs(latencyMs)
                .costUsd(costUsd)
                .timestampMs(1_700_000_000_000L)
                .build();
    }

    /**
     * 在汇总结果中按环节查找聚合项。
     *
     * @param summary 汇总
     * @param stage   环节
     * @return 聚合项
     */
    private StageAggregate findStage(RunSummary summary, MeterStage stage) {
        return summary.stages().stream()
                .filter(s -> s.stage() == stage)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到环节: " + stage));
    }
}
