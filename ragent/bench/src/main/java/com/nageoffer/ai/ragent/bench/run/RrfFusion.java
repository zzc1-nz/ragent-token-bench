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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF 融合器：直接复用 Ragent 的融合算法与权重配置
 * <p>
 * 为什么直接 new 一个 {@link SearchChannelProperties} 而不自己写公式：
 * RRF 的 k 值、通道权重都是 Ragent 调优过的（k=20 而非经典 60，因为候选池只有 20~40 条），
 * 自己重写一遍等于把调优结论丢了。这里通过配置对象驱动 Ragent 的真实实现逻辑，
 * 保证「多路融合」这个实验组的行为与生产一致。
 */
public final class RrfFusion {

    private final SearchChannelProperties properties;

    public RrfFusion() {
        this.properties = new SearchChannelProperties();
        // 与 Ragent application.yaml 的 rag.search.fusion 保持一致
        properties.getFusion().setStrategy("rrf");
        properties.getFusion().setRrfK(20);
        properties.getFusion().setRerankCandidateLimit(40);
    }

    /**
     * 融合多通道结果
     * <p>
     * 单通道时不做 RRF（与 Ragent 行为一致：{@code results.size() > 1} 才融合），
     * 直接按原顺序返回 —— 这样"仅向量"与"多路融合"的差异只来自是否多一路证据。
     *
     * @param vectorHits  向量通道命中
     * @param keywordHits 关键词通道命中（可能为空）
     */
    public List<RetrievedChunk> fuse(List<com.nageoffer.ai.ragent.bench.retrieval.ChunkIndex.ScoredChunk> vectorHits,
                                     List<com.nageoffer.ai.ragent.bench.retrieval.ChunkIndex.ScoredChunk> keywordHits) {
        List<RetrievedChunk> vectorChunks = toChunks(vectorHits);
        if (keywordHits == null || keywordHits.isEmpty()) {
            return vectorChunks;
        }
        List<RetrievedChunk> keywordChunks = toChunks(keywordHits);
        return rrfFuse(List.of(vectorChunks, keywordChunks));
    }

    /**
     * 按 Ragent 的 RRF 公式融合：score = Σ weight / (k + rank)
     * <p>
     * 权重从 {@link SearchChannelProperties} 读，与生产配置同源；
     * 名次从各通道原始召回顺序取，因此"多路命中"的候选会自然上浮。
     */
    private List<RetrievedChunk> rrfFuse(List<List<RetrievedChunk>> channelResults) {
        int k = properties.getFusion().getRrfK();
        SearchChannelProperties.ChannelWeights weights = properties.getFusion().getChannelWeights();

        // 通道顺序固定：0=向量（权重 vector），1=关键词（权重 keyword）
        double[] channelWeights = {weights.getVector(), weights.getKeyword()};

        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, RetrievedChunk> byKey = new LinkedHashMap<>();

        for (int c = 0; c < channelResults.size(); c++) {
            List<RetrievedChunk> chunks = channelResults.get(c);
            double weight = c < channelWeights.length ? channelWeights[c] : weights.getDefaultWeight();
            for (int rank = 0; rank < chunks.size(); rank++) {
                RetrievedChunk chunk = chunks.get(rank);
                String key = chunk.getId() != null ? chunk.getId() : String.valueOf(chunk.getText().hashCode());
                scores.merge(key, weight / (k + rank + 1), Double::sum);
                byKey.putIfAbsent(key, chunk);
            }
        }

        List<RetrievedChunk> fused = new ArrayList<>(byKey.size());
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            RetrievedChunk chunk = byKey.get(entry.getKey());
            // 复制一份再改分数：直接改原对象会污染通道结果，影响后续统计
            fused.add(chunk.toBuilder().score(entry.getValue().floatValue()).build());
        }
        fused.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));

        int limit = properties.getFusion().getRerankCandidateLimit();
        return limit > 0 && fused.size() > limit ? new ArrayList<>(fused.subList(0, limit)) : fused;
    }

    private List<RetrievedChunk> toChunks(
            List<com.nageoffer.ai.ragent.bench.retrieval.ChunkIndex.ScoredChunk> hits) {
        List<RetrievedChunk> chunks = new ArrayList<>(hits.size());
        for (com.nageoffer.ai.ragent.bench.retrieval.ChunkIndex.ScoredChunk hit : hits) {
            chunks.add(hit.chunk().toRetrievedChunk(hit.score()));
        }
        return chunks;
    }
}
