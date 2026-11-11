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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TokenUsageJson} 单测。
 * <p>
 * 覆盖点（对应契约 4.2 字段名要求）：
 * - 序列化字段名为 camelCase，16 个字段齐全，null 字段也输出
 * - 反序列化 round-trip 后 record 完全相等（bench 读回 events.jsonl 依赖此能力）
 * - 反序列化忽略未知字段（向后兼容）
 * - 非法 JSON 抛 IllegalStateException
 * - readAll 跳过空行、文件不存在返回空列表
 * - 与 JsonlMeterSink 联合使用：写入后读回一致
 */
class TokenUsageJsonTest {

    @Test
    @DisplayName("序列化字段名与契约 4.2 完全一致（camelCase，16 个字段）")
    void serializeFieldNames() {
        TokenUsage usage = fullUsage();
        String json = TokenUsageJson.toJson(usage);

        String[] expectedFields = {
                "eventId", "runId", "experimentId", "conversationId", "turnIndex",
                "stage", "modelId", "provider", "inputTokens", "outputTokens",
                "cachedInputTokens", "cacheHit", "latencyMs", "costUsd", "timestampMs", "traceTag"
        };
        for (String field : expectedFields) {
            assertTrue(json.contains("\"" + field + "\""), "缺少字段: " + field + " in " + json);
        }
        // 不应出现 snake_case 或大写开头字段
        assertFalse(json.contains("event_id"));
        assertFalse(json.contains("EventId"));
        // stage 序列化为枚举名字符串
        assertTrue(json.contains("\"stage\":\"GENERATE\""), json);
    }

    @Test
    @DisplayName("round-trip：序列化再反序列化后 record 完全相等")
    void roundTrip() {
        TokenUsage original = fullUsage();
        String json = TokenUsageJson.toJson(original);
        TokenUsage restored = TokenUsageJson.fromJson(json);

        assertEquals(original, restored);
        assertEquals(original.eventId(), restored.eventId());
        assertEquals(original.runId(), restored.runId());
        assertEquals(original.experimentId(), restored.experimentId());
        assertEquals(original.conversationId(), restored.conversationId());
        assertEquals(original.turnIndex(), restored.turnIndex());
        assertEquals(original.stage(), restored.stage());
        assertEquals(original.modelId(), restored.modelId());
        assertEquals(original.provider(), restored.provider());
        assertEquals(original.inputTokens(), restored.inputTokens());
        assertEquals(original.outputTokens(), restored.outputTokens());
        assertEquals(original.cachedInputTokens(), restored.cachedInputTokens());
        assertEquals(original.cacheHit(), restored.cacheHit());
        assertEquals(original.latencyMs(), restored.latencyMs());
        assertEquals(original.costUsd(), restored.costUsd());
        assertEquals(original.timestampMs(), restored.timestampMs());
        assertEquals(original.traceTag(), restored.traceTag());
        assertEquals(original.totalTokens(), restored.totalTokens());
    }

    @Test
    @DisplayName("ETL 阶段事件（conversationId/turnIndex 为 null）可正常 round-trip")
    void roundTripEtlEvent() {
        TokenUsage original = TokenUsage.builder()
                .runId("RUN_ETL")
                .experimentId("A_BASELINE")
                .stage(MeterStage.EMBED)
                .modelId("text-embedding-v4")
                .provider("bailian")
                .inputTokens(12000)
                .outputTokens(0)
                .cachedInputTokens(0)
                .cacheHit(false)
                .latencyMs(85L)
                .costUsd(0.00024)
                .timestampMs(1_700_000_000_123L)
                .traceTag("batch:32")
                .build();

        TokenUsage restored = TokenUsageJson.fromJson(TokenUsageJson.toJson(original));

        assertEquals(original, restored);
        assertNull(restored.conversationId());
        assertNull(restored.turnIndex());
        assertEquals("batch:32", restored.traceTag());
    }

    @Test
    @DisplayName("反序列化忽略未知字段（向后兼容）")
    void deserializeIgnoresUnknownFields() {
        String json = "{\"eventId\":\"e1\",\"stage\":\"GENERATE\",\"inputTokens\":10,"
                + "\"outputTokens\":5,\"futureField\":\"ignored\",\"anotherOne\":123}";

        TokenUsage usage = TokenUsageJson.fromJson(json);

        assertEquals("e1", usage.eventId());
        assertEquals(MeterStage.GENERATE, usage.stage());
        assertEquals(10, usage.inputTokens());
        assertEquals(5, usage.outputTokens());
    }

    @Test
    @DisplayName("非法 JSON 抛 IllegalStateException")
    void invalidJsonThrows() {
        assertThrows(IllegalStateException.class, () -> TokenUsageJson.fromJson("{not json"));
        assertThrows(IllegalStateException.class, () -> TokenUsageJson.fromJson(""));
    }

    @Test
    @DisplayName("null 入参抛 IllegalArgumentException")
    void nullArgsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TokenUsageJson.toJson(null));
        assertThrows(IllegalArgumentException.class, () -> TokenUsageJson.fromJson(null));
    }

    @Test
    @DisplayName("readAll 读取 jsonl 并跳过空行；文件不存在返回空列表")
    void readAllHandlesFile(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("events.jsonl");
        TokenUsage first = fullUsage();
        TokenUsage second = TokenUsage.builder()
                .runId("RUN_2").experimentId("EXP_2").stage(MeterStage.PARSE)
                .modelId("local").provider("local").timestampMs(1_700_000_000_001L).build();

        Files.writeString(file, TokenUsageJson.toJson(first) + "\n\n"
                + TokenUsageJson.toJson(second) + "\n", StandardCharsets.UTF_8);

        List<TokenUsage> events = TokenUsageJson.readAll(file);

        assertEquals(2, events.size());
        assertEquals(first, events.get(0));
        assertEquals(second, events.get(1));

        assertTrue(TokenUsageJson.readAll(tempDir.resolve("absent.jsonl")).isEmpty());
        assertTrue(TokenUsageJson.readAll(null).isEmpty());
    }

    @Test
    @DisplayName("与 JsonlMeterSink 联合：写入文件后读回完全一致（bench 消费路径）")
    void jsonlSinkRoundTrip(@TempDir Path tempDir) {
        Path file = tempDir.resolve("nested/dir/events.jsonl");
        JsonlMeterSink sink = new JsonlMeterSink(file);
        try {
            TokenUsage first = fullUsage();
            TokenUsage second = TokenUsage.builder()
                    .runId("RUN_J").experimentId("EXP_J").stage(MeterStage.RERANK)
                    .modelId("qwen3-rerank").provider("bailian")
                    .inputTokens(4000).outputTokens(0).latencyMs(60L).costUsd(0.0004)
                    .timestampMs(1_700_000_000_002L).build();
            sink.accept(first);
            sink.accept(second);

            List<TokenUsage> fromSink = sink.snapshot();
            List<TokenUsage> fromFile = TokenUsageJson.readAll(file);

            assertEquals(2, fromSink.size());
            assertEquals(fromSink, fromFile);
            assertEquals(first, fromFile.get(0));
            assertEquals(second, fromFile.get(1));
        } finally {
            sink.close();
        }
    }

    @Test
    @DisplayName("mapper() 暴露共享 ObjectMapper")
    void mapperAccessible() {
        assertTrue(TokenUsageJson.mapper() != null);
    }

    /**
     * 构造一条字段全部有值的测试事件。
     *
     * @return 事件
     */
    private TokenUsage fullUsage() {
        return TokenUsage.builder()
                .eventId("11111111-2222-3333-4444-555555555555")
                .runId("RUN_20261110_001")
                .experimentId("B_SEMANTIC_FUSION_ROUTED")
                .conversationId("CONV_0001")
                .turnIndex(1)
                .stage(MeterStage.GENERATE)
                .modelId("deepseek-chat")
                .provider("deepseek")
                .inputTokens(1234)
                .outputTokens(567)
                .cachedInputTokens(200)
                .cacheHit(true)
                .latencyMs(1234L)
                .costUsd(0.001234)
                .timestampMs(1_700_000_000_000L)
                .traceTag("turn:1")
                .build();
    }
}
