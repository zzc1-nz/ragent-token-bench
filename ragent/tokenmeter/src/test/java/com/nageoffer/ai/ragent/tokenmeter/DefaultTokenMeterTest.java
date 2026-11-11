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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultTokenMeter} 单测。
 * <p>
 * 覆盖点：
 * - record 自动从 {@link MeterContext} 填充 runId / experimentId / conversationId / turnIndex
 * - 无上下文时上下文字段为 null，但仍能记录
 * - 成本按定价表算好（含缓存折扣）
 * - cacheHit=true 时 cachedInputTokens 自动补为 inputTokens（契约 4.6）
 * - 负数 token / 耗时归零
 * - measure 自动测耗时、估算输入 token、按 outputTokenFn 算输出 token
 * - measure 返回 null 时仍记录（输出 0）
 * - measure 调用抛异常时不记录事件、异常原样抛出
 * - reset 清空内存 sink
 */
class DefaultTokenMeterTest {

    private static final double DELTA = 1e-15;

    @Test
    @DisplayName("record 从 MeterContext 自动填充运行/实验/会话/轮次")
    void recordFillsContext() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        TokenMeter meter = new DefaultTokenMeter(sink);

        TokenUsage usage;
        try (MeterContext ignored = MeterContext.open("RUN_1", "EXP_A", "CONV_7", 2)) {
            usage = meter.record(MeterStage.GENERATE, "deepseek-chat", "deepseek",
                    1000, 500, 0, false, 120L, "turn:2");
        }

        assertEquals("RUN_1", usage.runId());
        assertEquals("EXP_A", usage.experimentId());
        assertEquals("CONV_7", usage.conversationId());
        assertEquals(2, usage.turnIndex());
        assertEquals(MeterStage.GENERATE, usage.stage());
        assertEquals("deepseek-chat", usage.modelId());
        assertEquals("deepseek", usage.provider());
        assertEquals(1000, usage.inputTokens());
        assertEquals(500, usage.outputTokens());
        assertEquals(1500, usage.totalTokens());
        assertEquals(120L, usage.latencyMs());
        assertEquals("turn:2", usage.traceTag());
        assertNotNull(usage.eventId());
        assertTrue(usage.timestampMs() > 0L);

        assertEquals(1, sink.size());
        assertEquals(usage, sink.snapshot().get(0));
    }

    @Test
    @DisplayName("无 MeterContext 时上下文字段为 null，但仍正常记录")
    void recordWithoutContext() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        TokenMeter meter = new DefaultTokenMeter(sink);

        assertNull(MeterContext.current());
        TokenUsage usage = meter.record(MeterStage.PARSE, "local", "local", 0, 0, 0, false, 5L, null);

        assertNull(usage.runId());
        assertNull(usage.experimentId());
        assertNull(usage.conversationId());
        assertNull(usage.turnIndex());
        assertEquals(1, sink.size());
    }

    @Test
    @DisplayName("成本按定价表算好：deepseek-chat 1000 输入 + 500 输出")
    void recordComputesCost() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        TokenMeter meter = new DefaultTokenMeter(sink);

        TokenUsage usage = meter.record(MeterStage.GENERATE, "deepseek-chat", "deepseek",
                1000, 500, 0, false, 10L, null);

        // 1000*0.27/1M + 500*1.10/1M = 0.00027 + 0.00055 = 0.00082
        assertEquals(0.00082, usage.costUsd(), DELTA);
    }

    @Test
    @DisplayName("缓存折扣：cachedInputTokens 按缓存价计，输出按正常价")
    void recordAppliesCacheDiscount() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        TokenMeter meter = new DefaultTokenMeter(sink);

        TokenUsage usage = meter.record(MeterStage.GENERATE, "deepseek-chat", "deepseek",
                1000, 500, 1000, true, 10L, null);

        assertEquals(1000, usage.cachedInputTokens());
        assertTrue(usage.cacheHit());
        // 1000*0.07/1M + 500*1.10/1M = 0.00007 + 0.00055 = 0.00062
        assertEquals(0.00062, usage.costUsd(), DELTA);
    }

    @Test
    @DisplayName("cacheHit=true 且未给缓存 token 时，自动视为全部输入命中（契约 4.6）")
    void cacheHitImpliesFullCachedInput() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        TokenMeter meter = new DefaultTokenMeter(sink);

        TokenUsage usage = meter.record(MeterStage.GENERATE, "deepseek-flash", "deepseek",
                800, 100, 0, true, 3L, "cache");

        assertEquals(800, usage.cachedInputTokens());
        // 800*0.014/1M + 100*0.28/1M = 0.0000112 + 0.000028 = 0.0000392
        assertEquals(0.0000392, usage.costUsd(), 1e-16);
    }

    @Test
    @DisplayName("负数 token / 耗时归零；cachedIn 超过 in 时截断")
    void recordNormalizesInputs() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        TokenMeter meter = new DefaultTokenMeter(sink);

        TokenUsage usage = meter.record(MeterStage.GENERATE, "deepseek-chat", "deepseek",
                -100, -50, 9999, false, -20L, null);

        assertEquals(0, usage.inputTokens());
        assertEquals(0, usage.outputTokens());
        assertEquals(0, usage.cachedInputTokens());
        assertEquals(0L, usage.latencyMs());
        assertEquals(0.0d, usage.costUsd(), DELTA);
    }

    @Test
    @DisplayName("modelId/provider 为 null 或空时归一化为 local/none")
    void recordNormalizesModelAndProvider() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        TokenMeter meter = new DefaultTokenMeter(sink);

        TokenUsage usage = meter.record(MeterStage.FUSION, null, null, 0, 0, 0, false, 1L, null);
        assertEquals("local", usage.modelId());
        assertEquals("none", usage.provider());

        TokenUsage empty = meter.record(MeterStage.FUSION, "", "", 0, 0, 0, false, 1L, null);
        assertEquals("local", empty.modelId());
        assertEquals("none", empty.provider());
    }

    @Test
    @DisplayName("record 拒绝 null stage")
    void recordRejectsNullStage() {
        TokenMeter meter = new DefaultTokenMeter(new InMemoryMeterSink());
        assertThrows(IllegalArgumentException.class,
                () -> meter.record(null, "m", "p", 0, 0, 0, false, 0L, null));
    }

    @Test
    @DisplayName("measure 自动测耗时、估算输入 token、按 outputTokenFn 算输出 token")
    void measureWrapsCall() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        TokenMeter meter = new DefaultTokenMeter(sink);

        String payload = "请解释一下 RAG 的检索增强生成流程";
        String result;
        try (MeterContext ignored = MeterContext.open("RUN_M", "EXP_M", "CONV_M", 0)) {
            result = meter.measure(MeterStage.GENERATE, "deepseek-chat", "deepseek",
                    payload, () -> "这是模型的回答内容", String::length);
        }

        assertEquals("这是模型的回答内容", result);
        assertEquals(1, sink.size());
        TokenUsage usage = sink.snapshot().get(0);
        assertEquals(MeterStage.GENERATE, usage.stage());
        assertEquals("RUN_M", usage.runId());
        assertEquals("CONV_M", usage.conversationId());
        assertEquals(HeuristicTokenEstimator.INSTANCE.estimate(payload), usage.inputTokens());
        assertEquals("这是模型的回答内容".length(), usage.outputTokens());
        assertTrue(usage.latencyMs() >= 0L);
        assertFalse(usage.cacheHit());
        assertEquals(0, usage.cachedInputTokens());
    }

    @Test
    @DisplayName("measure 返回 null 时仍记录事件（输出 token 记 0）")
    void measureRecordsNullResult() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        TokenMeter meter = new DefaultTokenMeter(sink);

        String result = meter.measure(MeterStage.INTENT, "qwen-flash", "bailian",
                "判断意图", () -> null, String::length);

        assertNull(result);
        assertEquals(1, sink.size());
        TokenUsage usage = sink.snapshot().get(0);
        assertEquals(0, usage.outputTokens());
        assertEquals(HeuristicTokenEstimator.INSTANCE.estimate("判断意图"), usage.inputTokens());
    }

    @Test
    @DisplayName("measure 的 outputTokenFn 为 null 时输出 token 记 0")
    void measureWithoutOutputTokenFn() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        TokenMeter meter = new DefaultTokenMeter(sink);

        String result = meter.measure(MeterStage.SUMMARY, "local", "local", "摘要", () -> "ok", null);

        assertEquals("ok", result);
        assertEquals(0, sink.snapshot().get(0).outputTokens());
    }

    @Test
    @DisplayName("measure 调用抛异常时不记录事件，异常原样抛出")
    void measureDoesNotRecordOnFailure() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        TokenMeter meter = new DefaultTokenMeter(sink);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                meter.measure(MeterStage.GENERATE, "deepseek-chat", "deepseek", "x",
                        () -> {
                            throw new IllegalStateException("provider down");
                        }, String::length));

        assertEquals("provider down", thrown.getMessage());
        assertEquals(0, sink.size(), "失败的调用不应计费");
    }

    @Test
    @DisplayName("measure 拒绝 null stage / null call")
    void measureRejectsInvalidArgs() {
        TokenMeter meter = new DefaultTokenMeter(new InMemoryMeterSink());
        assertThrows(IllegalArgumentException.class,
                () -> meter.measure(null, "m", "p", "x", () -> "ok", String::length));
        assertThrows(IllegalArgumentException.class,
                () -> meter.measure(MeterStage.GENERATE, "m", "p", "x", null, String::length));
    }

    @Test
    @DisplayName("reset 清空内存 sink")
    void resetClearsMemorySink() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        TokenMeter meter = new DefaultTokenMeter(sink);
        meter.record(MeterStage.PARSE, "local", "local", 1, 1, 0, false, 1L, null);
        assertEquals(1, sink.size());

        meter.reset();

        assertEquals(0, sink.size());
        assertTrue(meter.sink().snapshot().isEmpty());
    }

    @Test
    @DisplayName("sink() 返回构造时注入的 sink")
    void sinkAccessor() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        TokenMeter meter = new DefaultTokenMeter(sink);
        assertEquals(sink, meter.sink());
    }

    @Test
    @DisplayName("完整构造可注入自定义定价表与估算器")
    void customPricingAndEstimator() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        PricingTable table = PricingTable.defaultTable();
        ProviderReportedEstimator estimator = new ProviderReportedEstimator(777, 111);
        DefaultTokenMeter meter = new DefaultTokenMeter(sink, table, estimator);

        meter.measure(MeterStage.GENERATE, "deepseek-chat", "deepseek", "任意文本", () -> "答案", String::length);

        TokenUsage usage = sink.snapshot().get(0);
        assertEquals(777, usage.inputTokens(), "应使用注入的 ProviderReportedEstimator");
        assertEquals(2, usage.outputTokens());
        assertEquals(777, estimator.reportedInputTokens());
        assertEquals(111, estimator.reportedOutputTokens());
        assertEquals(888, estimator.reportedTotalTokens());
    }

    @Test
    @DisplayName("并发 record 不丢事件（线程安全）")
    void concurrentRecord() throws InterruptedException {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        TokenMeter meter = new DefaultTokenMeter(sink);
        int threads = 6;
        int perThread = 100;
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int threadIndex = t;
            workers[t] = new Thread(() -> {
                try (MeterContext ignored = MeterContext.open("RUN_" + threadIndex, "EXP")) {
                    for (int i = 0; i < perThread; i++) {
                        meter.record(MeterStage.GENERATE, "deepseek-chat", "deepseek",
                                100, 50, 0, false, 1L, null);
                    }
                }
            });
            workers[t].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        assertEquals(threads * perThread, sink.size());
        List<TokenUsage> events = sink.snapshot();
        assertEquals(threads * perThread, events.stream().map(TokenUsage::eventId).distinct().count(),
                "每条事件应有独立 eventId");
        assertEquals(threads, events.stream().map(TokenUsage::runId).distinct().count());
    }
}
