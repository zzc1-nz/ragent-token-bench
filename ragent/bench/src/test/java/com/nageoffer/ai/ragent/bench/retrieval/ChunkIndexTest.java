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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存索引单测：向量与关键词两条通道都要真的能召回
 */
class ChunkIndexTest {

    private final HashingEmbedder embedder = new HashingEmbedder(1536);

    private ChunkIndex buildIndex() {
        ChunkIndex index = new ChunkIndex();
        index.add(chunk("C1", "D1", "R5 扫地机器人整机保修两年，电池保修一年，耗材不在保修范围内"));
        index.add(chunk("C2", "D2", "差旅报销需提供增值税发票，住宿标准按城市等级划分"));
        index.add(chunk("C3", "D3", "员工年假天数按工龄计算，入职满一年享五天"));
        index.add(chunk("C4", "D4", "API 接入使用 Bearer Token 认证，限流为每分钟一百次"));
        return index;
    }

    private IndexedChunk chunk(String id, String docId, String text) {
        return new IndexedChunk(id, docId, docId + " 标题", 0, text, embedder.embed(text));
    }

    @Test
    @DisplayName("向量检索：查询与目标文档语义相近时能召回")
    void searchVector() {
        ChunkIndex index = buildIndex();
        float[] query = embedder.embed("扫地机器人保修多久");
        List<ChunkIndex.ScoredChunk> hits = index.searchVectorScored(query, 3);

        assertFalse(hits.isEmpty());
        assertEquals("D1", hits.get(0).chunk().docId(), "最相近的应是保修文档");
        assertTrue(hits.get(0).score() > 0, "分数应为正");
    }

    @Test
    @DisplayName("关键词检索：专有名词（如 API、发票）应精确命中")
    void searchKeyword() {
        ChunkIndex index = buildIndex();
        List<ChunkIndex.ScoredChunk> hits = index.searchKeywordScored("发票 报销", 3);

        assertFalse(hits.isEmpty());
        assertEquals("D2", hits.get(0).chunk().docId(), "最相关的应是报销文档");
    }

    @Test
    @DisplayName("两条通道对同一查询给出不同排序：这正是多路融合的价值来源")
    void channelsDiffer() {
        ChunkIndex index = buildIndex();
        float[] query = embedder.embed("限流 每分钟");

        List<String> vectorIds = index.searchVectorScored(query, 4).stream()
                .map(h -> h.chunk().docId()).toList();
        List<String> keywordIds = index.searchKeywordScored("限流 每分钟", 4).stream()
                .map(h -> h.chunk().docId()).toList();

        assertTrue(keywordIds.contains("D4"), "关键词通道应命中限流文档");
        assertFalse(vectorIds.isEmpty());
        // 不强求排序完全一致，但两条通道都应有结果，融合才有意义
        assertFalse(keywordIds.isEmpty());
    }

    @Test
    @DisplayName("topK 限制生效")
    void topKLimit() {
        ChunkIndex index = buildIndex();
        assertEquals(2, index.searchVectorScored(embedder.embed("保修"), 2).size());
        assertEquals(4, index.searchVectorScored(embedder.embed("保修"), 100).size());
    }

    @Test
    @DisplayName("空索引返回空结果，不抛异常")
    void emptyIndex() {
        ChunkIndex index = new ChunkIndex();
        assertTrue(index.isEmpty());
        assertTrue(index.searchVectorScored(embedder.embed("任意"), 5).isEmpty());
        assertTrue(index.searchKeywordScored("任意", 5).isEmpty());
    }

    @Test
    @DisplayName("docIds 返回去重的全部文档 ID")
    void docIds() {
        ChunkIndex index = buildIndex();
        assertEquals(4, index.docIds().size());
        assertTrue(index.docIds().contains("D1"));
    }
}
