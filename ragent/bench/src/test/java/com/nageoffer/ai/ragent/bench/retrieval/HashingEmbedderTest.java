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

package com.nageoffer.ai.ragent.bench.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量器与检索单测：召回语义是否真实，决定了「多路融合 vs 仅向量」的 A/B 对比有没有意义
 */
class HashingEmbedderTest {

    private final HashingEmbedder embedder = new HashingEmbedder(1536);

    @Test
    @DisplayName("维度正确且向量已 L2 归一化")
    void dimensionAndNormalization() {
        float[] v = embedder.embed("扫地机器人保修政策");
        assertEquals(1536, v.length);

        double norm = 0.0;
        for (float x : v) {
            norm += (double) x * x;
        }
        assertEquals(1.0, Math.sqrt(norm), 1e-4, "向量应已 L2 归一化");
    }

    @Test
    @DisplayName("相似文本的余弦相似度显著高于不相似文本")
    void similarTextsScoreHigher() {
        float[] query = embedder.embed("扫地机器人保修期多久");
        float[] similar = embedder.embed("R5 扫地机器人整机保修两年，电池保修一年");
        float[] unrelated = embedder.embed("差旅报销标准与发票要求说明");

        double simScore = HashingEmbedder.cosine(query, similar);
        double unrelScore = HashingEmbedder.cosine(query, unrelated);

        assertTrue(simScore > unrelScore,
                "相似文本得分(" + simScore + ")应高于无关文本(" + unrelScore + ")");
        assertTrue(simScore > 0.1, "相关文本相似度应明显大于 0，实际：" + simScore);
    }

    @Test
    @DisplayName("相同文本相似度为 1（自身归一化点积）")
    void identicalTexts() {
        float[] a = embedder.embed("API 限流每分钟 100 次");
        float[] b = embedder.embed("API 限流每分钟 100 次");
        assertEquals(1.0, HashingEmbedder.cosine(a, b), 1e-4);
    }

    @Test
    @DisplayName("空文本返回零向量，不抛异常")
    void emptyText() {
        float[] v = embedder.embed("");
        assertEquals(1536, v.length);
        assertEquals(0.0, HashingEmbedder.cosine(v, v), 1e-9);
    }

    @Test
    @DisplayName("确定性：同文本多次向量化结果完全一致")
    void deterministic() {
        float[] first = embedder.embed("考勤规则与年假天数");
        for (int i = 0; i < 10; i++) {
            float[] again = embedder.embed("考勤规则与年假天数");
            for (int j = 0; j < first.length; j++) {
                assertEquals(first[j], again[j], 0.0f);
            }
        }
    }

    @Test
    @DisplayName("不同文本产生不同向量")
    void differentTextsDiffer() {
        float[] a = embedder.embed("数据隐私合规要求");
        float[] b = embedder.embed("数据库部署架构说明");
        assertNotEquals(1.0, HashingEmbedder.cosine(a, b), 1e-6);
    }

    @Test
    @DisplayName("批量向量化与单条一致")
    void batchMatchesSingle() {
        List<String> texts = List.of("保修政策", "报销标准", "考勤规则");
        List<float[]> batch = embedder.embedAll(texts);
        assertEquals(3, batch.size());
        for (int i = 0; i < texts.size(); i++) {
            float[] single = embedder.embed(texts.get(i));
            assertEquals(1.0, HashingEmbedder.cosine(single, batch.get(i)), 1e-4);
        }
    }
}
