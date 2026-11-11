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

package com.nageoffer.ai.ragent.bench.run;

import com.nageoffer.ai.ragent.bench.retrieval.Bm25Tokenizer;
import com.nageoffer.ai.ragent.bench.retrieval.HashingEmbedder;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 轻量精排器：用「查询-文档词项重叠 + 向量相似度」混合打分
 * <p>
 * 为什么需要它：Ragent 的 Rerank 走百炼 API，基准测试不能依赖外部服务。
 * 但精排这一环不能省 —— 它是"候选池 40 条 → 最终 10 条"的漏斗口，
 * 直接决定进 prompt 的上下文长度，也就是答案生成的输入 token。
 * <p>
 * 打分公式（与真实 reranker 的语义一致：query-document 相关性）：
 * <pre>
 * score = 0.6 * 向量余弦相似度 + 0.4 * BM25 归一化分
 * </pre>
 * 混合而不是单用向量：向量对语义改写鲁棒，BM25 对专有名词（型号、错误码）精确，
 * 两者互补正是真实 reranker 的训练目标。
 */
public final class SimpleReranker {

    /**
     * 向量相似度权重
     */
    private static final double W_VECTOR = 0.6;

    /**
     * 关键词重叠权重
     */
    private static final double W_LEXICAL = 0.4;

    private final HashingEmbedder embedder;

    public SimpleReranker(HashingEmbedder embedder) {
        this.embedder = embedder;
    }

    /**
     * 精排并截断到 topN
     *
     * @param query      查询（已改写）
     * @param candidates 融合后的候选池
     * @param topN       最终保留条数
     */
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        float[] queryVector = embedder.embed(query);
        Set<String> queryTerms = new LinkedHashSet<>(Bm25Tokenizer.tokenize(query));

        record Scored(RetrievedChunk chunk, double score) {
        }
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (RetrievedChunk chunk : candidates) {
            String text = chunk.getText() == null ? "" : chunk.getText();
            double vectorSim = HashingEmbedder.cosine(queryVector, embedder.embed(text));
            double lexical = lexicalOverlap(queryTerms, text);
            double combined = W_VECTOR * vectorSim + W_LEXICAL * lexical;
            scored.add(new Scored(chunk, combined));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));

        int limit = Math.min(Math.max(0, topN), scored.size());
        List<RetrievedChunk> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Scored s = scored.get(i);
            // 精排分写进 rerankScore，score 保留上游 RRF 分 —— 与 Ragent 的字段语义一致
            out.add(s.chunk().toBuilder()
                    .rerankScore((float) s.score())
                    .build());
        }
        return out;
    }

    /**
     * 词项重叠度：查询词项在文档中的覆盖率，落在 [0,1]
     */
    private double lexicalOverlap(Set<String> queryTerms, String text) {
        if (queryTerms.isEmpty()) {
            return 0.0;
        }
        Set<String> docTerms = new LinkedHashSet<>(Bm25Tokenizer.tokenize(text));
        long hit = queryTerms.stream().filter(docTerms::contains).count();
        return (double) hit / queryTerms.size();
    }
}
