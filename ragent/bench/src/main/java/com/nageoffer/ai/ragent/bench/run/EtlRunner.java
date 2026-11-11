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

import com.nageoffer.ai.ragent.bench.config.ExperimentConfig;
import com.nageoffer.ai.ragent.bench.dataset.BenchDocument;
import com.nageoffer.ai.ragent.bench.llm.LlmClient;
import com.nageoffer.ai.ragent.bench.pipeline.ChunkingAdapter;
import com.nageoffer.ai.ragent.bench.retrieval.ChunkIndex;
import com.nageoffer.ai.ragent.bench.retrieval.HashingEmbedder;
import com.nageoffer.ai.ragent.bench.retrieval.IndexedChunk;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.tokenmeter.MeterContext;
import com.nageoffer.ai.ragent.tokenmeter.MeterStage;
import com.nageoffer.ai.ragent.tokenmeter.TokenEstimator;
import com.nageoffer.ai.ragent.tokenmeter.TokenMeter;

import java.util.ArrayList;
import java.util.List;

/**
 * ETL 执行器：文档解析 → 分块 → 向量化 → 建索引，每一步都埋计量点
 * <p>
 * 这是 token 消耗的大头之一：200 篇文档 × 每篇 3~6 块 × 每块 500 字符，
 * 光 embedding 输入就有几十万 token。不同分块策略在这里产生的差异最直接 ——
 * 语义分块块数少 → embedding 调用次数少 → 入库成本低。
 */
public final class EtlRunner {

    private final ChunkingAdapter chunkingAdapter;
    private final LlmClient llm;
    private final TokenMeter meter;
    private final TokenEstimator estimator;
    private final HashingEmbedder embedder;

    /**
     * embedding 批大小：真实调用通常按批提交（百炼单次最多 25 条），
     * 这里按 16 模拟，让"批量调用次数"这个维度也真实
     */
    private static final int EMBED_BATCH_SIZE = 16;

    public EtlRunner(ChunkingAdapter chunkingAdapter, LlmClient llm, TokenMeter meter,
                     TokenEstimator estimator, HashingEmbedder embedder) {
        this.chunkingAdapter = chunkingAdapter;
        this.llm = llm;
        this.meter = meter;
        this.estimator = estimator;
        this.embedder = embedder;
    }

    /**
     * 执行一次完整 ETL，返回可查询的索引
     *
     * @param documents  文档集
     * @param experiment 实验配置（决定分块策略与预算）
     * @return 建好的索引
     */
    public ChunkIndex ingest(List<BenchDocument> documents, ExperimentConfig experiment) {
        ChunkIndex index = new ChunkIndex();
        List<String> pendingTexts = new ArrayList<>(EMBED_BATCH_SIZE);
        List<Chunk> pendingChunks = new ArrayList<>(EMBED_BATCH_SIZE);
        List<BenchDocument> pendingDocs = new ArrayList<>(EMBED_BATCH_SIZE);

        for (BenchDocument doc : documents) {
            // ---------- 1. 解析：Markdown → Block ----------
            // 解析是本地 CPU 计算，不调用任何模型，因此 token 一律记 0（只记耗时与规模）。
            // 若把文档字符数记成 inputTokens，会让「token 消耗」口径混入非模型开销，
            // 分环节占比里出现一个 8% 的"解析"项，误导读者以为解析很贵。
            long parseStart = System.nanoTime();
            String markdown = doc.toMarkdown();
            int docChars = markdown.length();
            long parseLatency = Math.max(1, (System.nanoTime() - parseStart) / 1_000_000);
            meter.record(MeterStage.PARSE, "local", "local", 0, 0, 0,
                    false, parseLatency, "doc=" + doc.docId() + ",chars=" + docChars);

            // ---------- 2. 分块 ----------
            // 同理：分块是本地 CPU 计算，token 记 0，块数与字符数进 traceTag 供分析
            long chunkStart = System.nanoTime();
            List<Chunk> chunks = chunkingAdapter.chunk(
                    doc, experiment.chunkStrategy(), experiment.chunkSize(), experiment.chunkOverlap());
            long chunkLatency = Math.max(1, (System.nanoTime() - chunkStart) / 1_000_000);
            int chunkChars = chunks.stream()
                    .mapToInt(c -> c.embeddingText().length())
                    .sum();
            meter.record(MeterStage.CHUNK, "local", "local", 0, 0,
                    0, false, chunkLatency,
                    "doc=" + doc.docId() + ",chunks=" + chunks.size() + ",chars=" + chunkChars);

            // ---------- 3. 向量化：按批提交 ----------
            for (Chunk chunk : chunks) {
                pendingTexts.add(chunk.embeddingText());
                pendingChunks.add(chunk);
                pendingDocs.add(doc);
                if (pendingTexts.size() >= EMBED_BATCH_SIZE) {
                    flushEmbedBatch(index, pendingTexts, pendingChunks, pendingDocs, experiment);
                }
            }
        }
        // 收尾：不足一批的剩余部分
        if (!pendingTexts.isEmpty()) {
            flushEmbedBatch(index, pendingTexts, pendingChunks, pendingDocs, experiment);
        }
        return index;
    }

    /**
     * 提交一批 embedding：调用客户端 + 计量 + 写入索引
     */
    private void flushEmbedBatch(ChunkIndex index,
                                 List<String> texts,
                                 List<Chunk> chunks,
                                 List<BenchDocument> docs,
                                 ExperimentConfig experiment) {
        long start = System.nanoTime();
        List<float[]> vectors = llm.embedBatch("text-embedding-v4", texts);
        long latency = Math.max(1, (System.nanoTime() - start) / 1_000_000);

        int inputTokens = texts.stream().mapToInt(estimator::estimate).sum();
        // embedding 没有输出 token，输出恒为 0
        meter.record(MeterStage.EMBED, "text-embedding-v4", "bailian",
                inputTokens, 0, 0, false, latency,
                "batch=" + texts.size() + ",experiment=" + experiment.experimentId());

        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            BenchDocument doc = docs.get(i);
            index.add(new IndexedChunk(
                    chunk.chunkId(),
                    doc.docId(),
                    doc.title(),
                    chunk.index(),
                    chunk.embeddingText(),
                    vectors.get(i)));
        }
        texts.clear();
        chunks.clear();
        docs.clear();
    }

    /**
     * 建索引的便捷入口：自动开 MeterContext（ETL 阶段 conversationId / turnIndex 为 null）
     *
     * @param runId      本次运行 ID
     * @param experiment 实验配置
     * @param documents  文档集
     */
    public ChunkIndex ingestWithContext(String runId, ExperimentConfig experiment, List<BenchDocument> documents) {
        try (MeterContext ignored = MeterContext.open(runId, experiment.experimentId(), null, null)) {
            return ingest(documents, experiment);
        }
    }
}
