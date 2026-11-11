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
import com.nageoffer.ai.ragent.bench.config.ExperimentMatrix;
import com.nageoffer.ai.ragent.bench.dataset.BenchDocument;
import com.nageoffer.ai.ragent.bench.dataset.BenchQuery;
import com.nageoffer.ai.ragent.bench.dataset.CorpusLoader;
import com.nageoffer.ai.ragent.bench.llm.DeterministicLlmClient;
import com.nageoffer.ai.ragent.bench.llm.LlmClient;
import com.nageoffer.ai.ragent.bench.pipeline.ChunkingAdapter;
import com.nageoffer.ai.ragent.bench.quality.QualityScorer;
import com.nageoffer.ai.ragent.bench.retrieval.ChunkIndex;
import com.nageoffer.ai.ragent.bench.retrieval.HashingEmbedder;
import com.nageoffer.ai.ragent.bench.strategy.CacheStrategy;
import com.nageoffer.ai.ragent.tokenmeter.DefaultTokenMeter;
import com.nageoffer.ai.ragent.tokenmeter.HeuristicTokenEstimator;
import com.nageoffer.ai.ragent.tokenmeter.InMemoryMeterSink;
import com.nageoffer.ai.ragent.tokenmeter.LruResponseCache;
import com.nageoffer.ai.ragent.tokenmeter.MeterContext;
import com.nageoffer.ai.ragent.tokenmeter.PricingTable;
import com.nageoffer.ai.ragent.tokenmeter.ResponseCache;
import com.nageoffer.ai.ragent.tokenmeter.TokenEstimator;
import com.nageoffer.ai.ragent.tokenmeter.TokenMeter;
import com.nageoffer.ai.ragent.tokenmeter.TokenUsage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基准测试总编排器：跑完 6 组策略 × (200 篇文档 ETL + 500 轮对话)
 * <p>
 * 编排顺序（顺序本身影响结果，因此固定）：
 * <pre>
 * for 每个实验:
 *     1. ETL：200 篇文档 → 分块 → 向量化 → 建索引（每个实验独立建，因为分块策略可能不同）
 *     2. 对话：500 轮，按会话回放（保证多轮指代能被改写消解）
 *     3. 汇总：该实验的 token / 成本 / 质量分
 * </pre>
 * 缓存策略为 ENABLED 的实验，{@link ResponseCache} 在整个实验内共享 ——
 * 这样"同一批文档被重复 embedding""同一批问题被重复改写"才能真实命中，
 * 缓存收益才是真实测出来的而不是编的。
 */
public final class BenchmarkRunner {

    private final LlmClient llm;
    private final TokenEstimator estimator;
    private final PricingTable pricingTable;

    /**
     * 全部计量事件（跨实验汇总，最后一次性写 events.jsonl）
     */
    private final List<TokenUsage> allEvents = new ArrayList<>();

    /**
     * 每个实验的质量分累加
     */
    private final Map<String, List<Double>> qualityByExperiment = new LinkedHashMap<>();

    /**
     * 每个实验的轮数
     */
    private final Map<String, Integer> turnsByExperiment = new LinkedHashMap<>();

    public BenchmarkRunner() {
        this(new DeterministicLlmClient(), new HeuristicTokenEstimator(), PricingTable.defaultTable());
    }

    public BenchmarkRunner(LlmClient llm, TokenEstimator estimator, PricingTable pricingTable) {
        this.llm = llm;
        this.estimator = estimator;
        this.pricingTable = pricingTable;
    }

    /**
     * 跑完整基准测试
     *
     * @param documents 文档集
     * @param queries   问题集
     * @param runId     本次运行 ID
     * @return 运行结果（含全部事件与每实验汇总）
     */
    public RunOutcome run(List<BenchDocument> documents, List<BenchQuery> queries, String runId) {
        List<ExperimentConfig> experiments = ExperimentMatrix.all();
        Map<String, ExperimentSummary> summaries = new LinkedHashMap<>();

        for (ExperimentConfig config : experiments) {
            runSingleExperiment(documents, queries, runId, config, summaries);
        }
        return new RunOutcome(runId, List.copyOf(allEvents), summaries, llm.clientName());
    }

    /**
     * 跑单个实验：ETL + 全部对话轮次
     */
    private void runSingleExperiment(List<BenchDocument> documents,
                                     List<BenchQuery> queries,
                                     String runId,
                                     ExperimentConfig config,
                                     Map<String, ExperimentSummary> summaries) {
        // 每个实验独立的 sink：便于把事件按实验切片，也便于定位问题
        InMemoryMeterSink sink = new InMemoryMeterSink();
        TokenMeter meter = new DefaultTokenMeter(sink, pricingTable, estimator);
        ResponseCache cache = new LruResponseCache(20_000);
        HashingEmbedder embedder = new HashingEmbedder(1536);

        // ---------- 1. ETL ----------
        ChunkingAdapter chunkingAdapter = new ChunkingAdapter();
        EtlRunner etl = new EtlRunner(chunkingAdapter, llm, meter, estimator, embedder);
        ChunkIndex index = etl.ingestWithContext(runId, config, documents);
        int chunkCount = index.size();

        // ---------- 2. 对话 ----------
        TurnRunner turnRunner = new TurnRunner(llm, meter, estimator, cache);
        ExperimentRuntime runtime = new ExperimentRuntime(
                config,
                index,
                embedder,
                new RrfFusion(),
                new SimpleReranker(embedder),
                buildIntentTree(documents));

        Map<String, List<BenchQuery>> conversations = CorpusLoader.groupByConversation(queries);
        List<Double> qualities = new ArrayList<>();
        int turns = 0;

        for (Map.Entry<String, List<BenchQuery>> entry : conversations.entrySet()) {
            String conversationId = entry.getKey();
            List<BenchQuery> conversationTurns = entry.getValue();
            List<String> history = new ArrayList<>();

            for (BenchQuery query : conversationTurns) {
                try (MeterContext ignored = MeterContext.open(
                        runId, config.experimentId(), conversationId, query.turnIndex())) {
                    TurnResult result = turnRunner.run(query, history, index, runtime);
                    // 只有带标准文档的知识库轮次参与质量均分：
                    // 闲聊/系统询问类轮次没有标准答案，计入均分会给所有策略加一个固定扣分，
                    // 稀释策略间的真实差异（详见 QualityScorer.isScorable 的说明）
                    if (QualityScorer.isScorable(query.intentHint(), query.referenceDocIds())) {
                        qualities.add(result.qualityScore());
                    }
                    turns++;

                    // 维护历史：供下一轮改写消解指代
                    history.add("用户：" + query.question());
                    history.add("助手：" + truncate(result.answer(), 120));
                }
            }
        }

        // ---------- 3. 汇总 ----------
        List<TokenUsage> events = sink.snapshot();
        allEvents.addAll(events);
        qualityByExperiment.put(config.experimentId(), qualities);
        turnsByExperiment.put(config.experimentId(), turns);

        summaries.put(config.experimentId(), new ExperimentSummary(
                config, events, qualities, turns, chunkCount));
    }

    /**
     * 构建意图树节点行：按领域分组，模拟 Ragent 的意图树输入
     * <p>
     * 真实意图树来自数据库；这里按文档领域动态生成，
     * 目的是让意图分类的输入 token 随"知识库规模"变化，而不是一个恒定的小数字。
     */
    private List<String> buildIntentTree(List<BenchDocument> documents) {
        Map<String, Integer> domainCount = new LinkedHashMap<>();
        for (BenchDocument doc : documents) {
            domainCount.merge(doc.domain() == null ? "general" : doc.domain(), 1, Integer::sum);
        }
        List<String> lines = new ArrayList<>();
        domainCount.forEach((domain, count) -> {
            lines.add("kb_" + domain + "（" + domain + " 领域知识库，含 " + count + " 篇文档）");
            lines.add("  └ kb_" + domain + "_faq（常见问题）");
            lines.add("  └ kb_" + domain + "_policy（政策条款）");
        });
        lines.add("system_capability（系统能力询问）");
        lines.add("chitchat（闲聊）");
        return lines;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 运行结果
     *
     * @param runId        本次运行 ID
     * @param events       全部计量事件
     * @param summaries    每实验汇总
     * @param llmClientName LLM 客户端类型（deterministic-local / live）
     */
    public record RunOutcome(
            String runId,
            List<TokenUsage> events,
            Map<String, ExperimentSummary> summaries,
            String llmClientName
    ) {
    }
}
