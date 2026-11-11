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
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端集成冒烟测试（契约 4 全链路串联）。
 * <p>
 * 用途：把 SDK 的所有组件按 bench 的真实用法串起来跑一遍，验证它们能协同工作：
 * ETL 阶段（无会话上下文）→ 问答阶段（有会话/轮次）→ 响应缓存命中 →
 * 组合 sink 双写（内存 + jsonl）→ 从 jsonl 读回 → 聚合出 {@link RunSummary}。
 * <p>
 * 本类同时提供 {@link #main(String[])}：可在**不含 Spring 的裸 classpath**
 * （tokenmeter + jackson + slf4j-api）下直接 {@code java} 运行，
 * 作为「tokenmeter 必须能在纯 Java main 里用」的活证据。
 */
class EndToEndSmokeTest {

    @Test
    @DisplayName("全链路：ETL + 问答 + 缓存命中 + 双写 sink + 读回 + 聚合")
    void fullPipeline(@TempDir Path tempDir) throws Exception {
        Path eventsFile = tempDir.resolve("results/RUN_SMOKE/events.jsonl");

        InMemoryMeterSink memory = new InMemoryMeterSink();
        JsonlMeterSink jsonl = new JsonlMeterSink(eventsFile);
        TokenMeter meter = new DefaultTokenMeter(new CompositeMeterSink(memory, jsonl));
        ResponseCache cache = new LruResponseCache(1000);
        HeuristicTokenEstimator estimator = HeuristicTokenEstimator.INSTANCE;

        try {
            // ---- 1) ETL 阶段：无会话上下文 ----
            try (MeterContext etl = MeterContext.open("RUN_SMOKE", "A_BASELINE")) {
                meter.record(MeterStage.PARSE, "local", "local", 0, 0, 0, false, 12L, "doc:DOC_0001");
                meter.record(MeterStage.CHUNK, "local", "local", 0, 0, 0, false, 3L, null);
                meter.record(MeterStage.EMBED, "text-embedding-v4", "bailian",
                        estimator.estimate("扫地机器人的滤网多久更换一次"), 0, 0, false, 40L, "batch:32");
            }

            // ---- 2) 问答阶段：首轮未命中 -> 真实调用 -> 写缓存 ----
            String payload = "扫地机器人的滤网多久更换一次？";
            String cacheKey = ResponseCache.key(MeterStage.GENERATE, "deepseek-chat", payload);

            try (MeterContext qa = MeterContext.open("RUN_SMOKE", "A_BASELINE", "CONV_0001", 0)) {
                assertEquals(null, cache.get(cacheKey), "首轮应未命中");
                String answer = meter.measure(MeterStage.GENERATE, "deepseek-chat", "deepseek",
                        payload, () -> "建议每 3 至 6 个月更换一次滤网，具体以使用频率为准。", String::length);
                assertTrue(answer != null && !answer.isEmpty());
                cache.put(cacheKey, answer);

                // 第 2 轮：缓存命中，按契约 4.6 语义记录
                String hit = cache.get(cacheKey);
                assertEquals(answer, hit);
                meter.record(MeterStage.GENERATE, "deepseek-chat", "deepseek",
                        estimator.estimate(payload), estimator.estimate(hit), 0, true, 2L, "cache:hit");
            }
        } finally {
            jsonl.close();
        }

        // ---- 3) 从 jsonl 读回（bench 的真实消费路径）----
        List<TokenUsage> events = TokenUsageJson.readAll(eventsFile);
        assertEquals(5, events.size(), "应落盘 5 条事件");
        assertEquals(memory.snapshot(), events, "jsonl 读回结果应与内存 sink 完全一致");

        // ---- 4) 聚合 ----
        RunSummary summary = MeterAggregator.summarize("RUN_SMOKE", events);
        assertEquals(5, summary.eventCount());
        assertEquals("A_BASELINE", summary.experimentId());
        assertEquals(4, summary.stages().size(), "PARSE/CHUNK/EMBED/GENERATE 四个环节");
        assertEquals(2, summary.stages().stream()
                .filter(s -> s.stage() == MeterStage.GENERATE).findFirst().orElseThrow().calls());
        // 5 条事件中 1 条 cacheHit
        assertEquals(0.2d, summary.cacheHitRate(), 1e-12);
        assertTrue(summary.totalCostUsd() > 0d);
        assertEquals(summary.totalTokens(), summary.inputTokens() + summary.outputTokens());
        assertTrue(summary.tokensByModel().containsKey("deepseek-chat"));
        assertTrue(summary.tokensByModel().containsKey("text-embedding-v4"));

        // ---- 5) 缓存统计 ----
        assertEquals(1L, cache.hits());
        assertEquals(1L, cache.misses());
        assertEquals(0.5d, cache.hitRate(), 1e-12);

        // ---- 6) 落盘格式校验：UTF-8、每行一个 JSON、行尾 LF ----
        byte[] raw = Files.readAllBytes(eventsFile);
        String content = new String(raw, StandardCharsets.UTF_8);
        assertEquals(5, content.split("\n", -1).length - 1, "应有 5 个 LF 行分隔符");
        assertFalse(content.contains("\r"), "行尾不应含 CR");
        for (String line : content.split("\n")) {
            if (!line.isEmpty()) {
                assertTrue(line.startsWith("{") && line.endsWith("}"), "每行应为完整 JSON 对象: " + line);
                assertTrue(line.contains("\"runId\":\"RUN_SMOKE\""), "字段名应为 camelCase: " + line);
            }
        }
    }

    /**
     * 纯 Java main 入口：在不含 Spring 的裸 classpath 下验证 SDK 可用。
     * <p>
     * 运行方式（classpath 仅含 tokenmeter + jackson-databind/core/annotations + slf4j-api）：
     * <pre>{@code java -cp tokenmeter.jar;jackson-*.jar;slf4j-api.jar SmokeMain [输出目录]}</pre>
     *
     * @param args 可选：输出目录，默认 {@code target/smoke}
     * @throws Exception 运行失败时抛出
     */
    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "target/smoke");
        Files.createDirectories(outDir);
        Path eventsFile = outDir.resolve("events.jsonl");
        Files.deleteIfExists(eventsFile);

        InMemoryMeterSink memory = new InMemoryMeterSink();
        JsonlMeterSink jsonl = new JsonlMeterSink(eventsFile);
        TokenMeter meter = new DefaultTokenMeter(new CompositeMeterSink(memory, jsonl));
        ResponseCache cache = new LruResponseCache(1000);
        HeuristicTokenEstimator estimator = HeuristicTokenEstimator.INSTANCE;

        try {
            try (MeterContext etl = MeterContext.open("RUN_SMOKE", "A_BASELINE")) {
                meter.record(MeterStage.PARSE, "local", "local", 0, 0, 0, false, 12L, "doc:DOC_0001");
                meter.record(MeterStage.CHUNK, "local", "local", 0, 0, 0, false, 3L, null);
                meter.record(MeterStage.EMBED, "text-embedding-v4", "bailian",
                        estimator.estimate("扫地机器人的滤网多久更换一次"), 0, 0, false, 40L, "batch:32");
            }

            String payload = "扫地机器人的滤网多久更换一次？";
            String cacheKey = ResponseCache.key(MeterStage.GENERATE, "deepseek-chat", payload);
            try (MeterContext qa = MeterContext.open("RUN_SMOKE", "A_BASELINE", "CONV_0001", 0)) {
                if (cache.get(cacheKey) == null) {
                    String answer = meter.measure(MeterStage.GENERATE, "deepseek-chat", "deepseek",
                            payload, () -> "建议每 3 至 6 个月更换一次滤网，具体以使用频率为准。", String::length);
                    cache.put(cacheKey, answer);
                }
                String hit = cache.get(cacheKey);
                meter.record(MeterStage.GENERATE, "deepseek-chat", "deepseek",
                        estimator.estimate(payload), estimator.estimate(hit), 0, true, 2L, "cache:hit");
            }
        } finally {
            jsonl.close();
        }

        List<TokenUsage> events = TokenUsageJson.readAll(eventsFile);
        RunSummary summary = MeterAggregator.summarize("RUN_SMOKE", events);

        System.out.println("== tokenmeter pure-Java smoke ==");
        System.out.println("events=" + summary.eventCount()
                + " input=" + summary.inputTokens()
                + " output=" + summary.outputTokens()
                + " total=" + summary.totalTokens());
        System.out.printf("cost=%.8f USD cacheHitRate=%.2f%n",
                summary.totalCostUsd(), summary.cacheHitRate());
        for (StageAggregate stage : summary.stages()) {
            System.out.printf("  %-18s calls=%-2d tokens=%-6d cost=%.8f latency=%dms%n",
                    stage.stage(), stage.calls(), stage.totalTokens(),
                    stage.totalCostUsd(), stage.totalLatencyMs());
        }
        System.out.println("tokensByModel=" + summary.tokensByModel());
        System.out.println("jsonl=" + eventsFile.toAbsolutePath() + " lines=" + events.size());

        if (summary.eventCount() != 5) {
            throw new AssertionError("expected 5 events, got " + summary.eventCount());
        }
        if (!events.equals(memory.snapshot())) {
            throw new AssertionError("jsonl read-back mismatch");
        }
        if (cache.hits() != 1 || cache.misses() != 1) {
            throw new AssertionError("cache stats wrong: hits=" + cache.hits() + " misses=" + cache.misses());
        }
        System.out.println("OK: no Spring required, pure Java main works");
    }
}
