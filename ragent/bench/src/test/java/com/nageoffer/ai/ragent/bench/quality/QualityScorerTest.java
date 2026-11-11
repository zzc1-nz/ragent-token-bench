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

package com.nageoffer.ai.ragent.bench.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 质量评分器单测：质量分是 A/B 结论的另一半，算错会让"省 token 但掉质量"变成"双赢"
 */
class QualityScorerTest {

    @Test
    @DisplayName("recall@k：全部命中给满分，全部未命中给零分，部分命中按比例")
    void recallAtK() {
        assertEquals(1.0, QualityScorer.recallAtK(List.of("D1"), List.of("D1", "D2")), 1e-9);
        assertEquals(0.0, QualityScorer.recallAtK(List.of("D1"), List.of("D2", "D3")), 1e-9);
        assertEquals(0.5, QualityScorer.recallAtK(List.of("D1", "D2"), List.of("D1", "D3")), 1e-9);
    }

    @Test
    @DisplayName("recall@k：无标准文档时返回 1.0，不能因为'没召回'扣闲聊的分")
    void recallAtKWithoutReference() {
        assertEquals(1.0, QualityScorer.recallAtK(List.of(), List.of()), 1e-9);
        assertEquals(1.0, QualityScorer.recallAtK(null, List.of("D1")), 1e-9);
    }

    @Test
    @DisplayName("答案覆盖：完全相同给满分，完全不重叠给零分")
    void answerCoverage() {
        assertEquals(1.0, QualityScorer.answerCoverage("保修期为两年", "保修期为两年"), 1e-9);
        assertEquals(0.0, QualityScorer.answerCoverage("保修期为两年", "完全无关的内容"), 1e-9);
    }

    @Test
    @DisplayName("答案覆盖：措辞不同但要点一致时应有较高分（2-gram 容忍改写）")
    void answerCoverageToleratesWording() {
        double score = QualityScorer.answerCoverage(
                "整机保修两年，电池保修一年",
                "根据资料，整机提供保修两年，电池保修一年。");
        assertTrue(score > 0.7, "改写后仍应覆盖大部分要点，实际：" + score);
    }

    @Test
    @DisplayName("引用精度：引对了给满分，引错给零分")
    void citationPrecision() {
        assertEquals(1.0, QualityScorer.citationPrecision(List.of("D1"), List.of("D1")), 1e-9);
        assertEquals(0.0, QualityScorer.citationPrecision(List.of("D1"), List.of("D2")), 1e-9);
    }

    @Test
    @DisplayName("引用精度：需要引用却没引用给 0 分，无需引用给满分")
    void citationPrecisionEdgeCases() {
        assertEquals(0.0, QualityScorer.citationPrecision(List.of("D1"), List.of()), 1e-9);
        assertEquals(1.0, QualityScorer.citationPrecision(List.of(), List.of()), 1e-9);
    }

    @Test
    @DisplayName("总分：三项按 0.4/0.4/0.2 加权，落在 [0,1]")
    void totalScoreWeights() {
        double perfect = QualityScorer.score(
                List.of("D1"), List.of("D1"), "保修两年", "保修两年", List.of("D1"));
        assertEquals(1.0, perfect, 1e-9);

        double zero = QualityScorer.score(
                List.of("D1"), List.of("D2"), "保修两年", "无关内容", List.of("D2"));
        assertEquals(0.0, zero, 1e-9);
    }

    @Test
    @DisplayName("评分范围：可评分轮次必须包含知识库问题，排除纯闲聊")
    void isScorable() {
        assertTrue(QualityScorer.isScorable("kb", List.of("D1")));
        assertTrue(QualityScorer.isScorable("kb", List.of()));
        assertFalse(QualityScorer.isScorable("chitchat", List.of()));
        assertFalse(QualityScorer.isScorable("system", List.of()));
        // 非 kb 但带了标准文档：仍可评分
        assertTrue(QualityScorer.isScorable("chitchat", List.of("D1")));
    }

    @Test
    @DisplayName("确定性：同输入多次调用结果必须完全一致")
    void deterministic() {
        List<String> refs = List.of("D1", "D2");
        List<String> retrieved = List.of("D1", "D3");
        String reference = "整机保修两年，电池保修一年，耗材不在保修范围";
        String answer = "整机保修两年，电池保修一年";
        List<String> cited = List.of("D1");

        double first = QualityScorer.score(refs, retrieved, reference, answer, cited);
        for (int i = 0; i < 20; i++) {
            assertEquals(first, QualityScorer.score(refs, retrieved, reference, answer, cited), 0.0);
        }
    }
}
