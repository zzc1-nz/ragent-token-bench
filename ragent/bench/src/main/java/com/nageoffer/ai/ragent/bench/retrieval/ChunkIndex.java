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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内存索引：向量检索（余弦）+ 关键词检索（BM25），供两个检索通道共用
 * <p>
 * 为什么不用真实 Milvus/ES：基准测试要量化的是 token 消耗与策略收益，
 * 不是检索引擎性能。但**召回语义必须真实**，否则「多路融合 vs 仅向量」的对比就是假的。
 * 所以这里实现的是等价的算法：余弦相似度 + BM25，只是把存储换成内存。
 * <p>
 * 索引是只读的（建好之后查询），因此无需并发控制 —— 运行器按实验顺序建索引、串行查询。
 */
public final class ChunkIndex {

    /**
     * BM25 词频饱和参数：业界默认值，对短文本块表现稳定
     */
    private static final double BM25_K1 = 1.2;

    /**
     * BM25 长度归一化参数：业界默认值
     */
    private static final double BM25_B = 0.75;

    private final List<IndexedChunk> chunks = new ArrayList<>();

    /**
     * 倒排表：词 → 命中块下标（关键词检索用）
     */
    private final Map<String, List<Integer>> inverted = new HashMap<>();

    /**
     * 每块的词数，BM25 的文档长度归一化用
     */
    private final List<Integer> docLengths = new ArrayList<>();

    /**
     * 每块的词频表，避免 BM25 打分时反复分词
     */
    private final List<Map<String, Integer>> termFreqs = new ArrayList<>();

    private double avgDocLength;

    /**
     * 加入一块并增量维护倒排表与词频表
     */
    public void add(IndexedChunk chunk) {
        int idx = chunks.size();
        chunks.add(chunk);

        List<String> tokens = Bm25Tokenizer.tokenize(chunk.text());
        docLengths.add(tokens.size());

        Map<String, Integer> tf = new HashMap<>();
        for (String token : tokens) {
            tf.merge(token, 1, Integer::sum);
        }
        termFreqs.add(tf);

        for (String token : new LinkedHashSet<>(tokens)) {
            inverted.computeIfAbsent(token, k -> new ArrayList<>()).add(idx);
        }
        avgDocLength = docLengths.stream().mapToInt(Integer::intValue).average().orElse(0.0);
    }

    public int size() {
        return chunks.size();
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    public List<IndexedChunk> chunks() {
        return List.copyOf(chunks);
    }

    /**
     * 向量检索：查询向量与全部块向量求余弦，返回 topK
     * <p>
     * 返回的块分数由调用方通过 {@link #vectorScore(float[], IndexedChunk)} 取，
     * 这里只保证顺序 —— 因为下游 Ragent 的融合器只依赖名次，不依赖分数绝对值。
     *
     * @param queryVector 查询向量
     * @param topK        返回条数
     */
    public List<IndexedChunk> searchVector(float[] queryVector, int topK) {
        if (chunks.isEmpty() || queryVector == null || topK <= 0) {
            return List.of();
        }
        List<Scored> scored = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            scored.add(new Scored(i, HashingEmbedder.cosine(queryVector, chunks.get(i).vector())));
        }
        return takeTop(scored, topK);
    }

    /**
     * 向量检索并保留分数：需要写进 RetrievedChunk.score 时用这个
     */
    public List<ScoredChunk> searchVectorScored(float[] queryVector, int topK) {
        if (chunks.isEmpty() || queryVector == null || topK <= 0) {
            return List.of();
        }
        List<Scored> scored = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            scored.add(new Scored(i, HashingEmbedder.cosine(queryVector, chunks.get(i).vector())));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return toScoredChunks(scored, topK);
    }

    /**
     * 关键词检索：BM25 打分，返回 topK
     */
    public List<IndexedChunk> searchKeyword(String query, int topK) {
        return searchKeywordScored(query, topK).stream()
                .map(ScoredChunk::chunk)
                .toList();
    }

    /**
     * 关键词检索并保留分数
     */
    public List<ScoredChunk> searchKeywordScored(String query, int topK) {
        if (chunks.isEmpty() || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        List<String> queryTokens = Bm25Tokenizer.tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        int n = chunks.size();
        Map<Integer, Double> scores = new HashMap<>();

        for (String token : new LinkedHashSet<>(queryTokens)) {
            List<Integer> postings = inverted.get(token);
            if (postings == null || postings.isEmpty()) {
                continue;
            }
            double idf = Math.log(1.0 + (n - postings.size() + 0.5) / (postings.size() + 0.5));
            for (int docIdx : postings) {
                Integer tf = termFreqs.get(docIdx).get(token);
                if (tf == null || tf == 0) {
                    continue;
                }
                double dl = docLengths.get(docIdx);
                double ratio = avgDocLength <= 0 ? 1.0 : dl / avgDocLength;
                double denom = tf + BM25_K1 * (1 - BM25_B + BM25_B * ratio);
                scores.merge(docIdx, idf * (tf * (BM25_K1 + 1)) / denom, Double::sum);
            }
        }

        List<Scored> scored = new ArrayList<>(scores.size());
        scores.forEach((idx, score) -> scored.add(new Scored(idx, score)));
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return toScoredChunks(scored, topK);
    }

    /**
     * 全部文档 ID：评测召回时校验 referenceDocIds 是否真的在库里
     */
    public Set<String> docIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (IndexedChunk chunk : chunks) {
            ids.add(chunk.docId());
        }
        return ids;
    }

    // ==================== 内部 ====================

    private List<IndexedChunk> takeTop(List<Scored> scored, int topK) {
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return toScoredChunks(scored, topK).stream().map(ScoredChunk::chunk).toList();
    }

    private List<ScoredChunk> toScoredChunks(List<Scored> scored, int topK) {
        int limit = Math.min(topK, scored.size());
        List<ScoredChunk> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Scored s = scored.get(i);
            out.add(new ScoredChunk(chunks.get(s.idx()), (float) s.score()));
        }
        return out;
    }

    /**
     * 带下标的打分中间态
     */
    private record Scored(int idx, double score) {
    }

    /**
     * 命中块 + 分数
     *
     * @param chunk 命中的块
     * @param score 该通道给出的相关度分数
     */
    public record ScoredChunk(IndexedChunk chunk, float score) {
    }
}
