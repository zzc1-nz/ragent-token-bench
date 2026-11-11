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

import com.nageoffer.ai.ragent.bench.llm.LlmClient;
import com.nageoffer.ai.ragent.bench.llm.LlmTask;
import com.nageoffer.ai.ragent.bench.prompt.PromptAssembler;
import com.nageoffer.ai.ragent.bench.prompt.PromptTemplates;
import com.nageoffer.ai.ragent.bench.quality.QualityScorer;
import com.nageoffer.ai.ragent.bench.retrieval.ChunkIndex;
import com.nageoffer.ai.ragent.bench.strategy.RetrievalStrategy;
import com.nageoffer.ai.ragent.bench.strategy.RoutingStrategy;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.tokenmeter.MeterStage;
import com.nageoffer.ai.ragent.tokenmeter.ResponseCache;
import com.nageoffer.ai.ragent.tokenmeter.TokenEstimator;
import com.nageoffer.ai.ragent.tokenmeter.TokenMeter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单轮对话执行器：把一轮问答的完整 RAG 链路跑一遍，每个环节都埋一个计量点
 * <p>
 * 链路严格对齐 Ragent 的真实流水线（{@code StreamChatPipeline}）：
 * <pre>
 * 意图分类 → 问题改写 → 多路检索 → 融合 → 精排 → 上下文组装 → 答案生成 → 质量评分
 * </pre>
 * 每一步都调用 {@link TokenMeter#record} 记录：输入/输出 token、模型、耗时、是否命中缓存。
 * <p>
 * <b>计量口径的关键约定</b>（决定报告数字是否可信）：
 * 同一段文本只在一个环节计 token。检索召回的 chunk 文本最终会进入答案生成的 prompt，
 * 其 token 在 GENERATE 的输入里计一次；因此检索/融合/精排环节的 token 一律记 0，
 * 只记耗时与候选条数。否则同一批文本被算两三遍，会让"检索环节占比"虚高到 40% 以上，
 * 掩盖真正的成本大头（答案生成）。
 */
public final class TurnRunner {

    private final LlmClient llm;
    private final TokenMeter meter;
    private final TokenEstimator estimator;
    private final ResponseCache cache;

    public TurnRunner(LlmClient llm, TokenMeter meter, TokenEstimator estimator, ResponseCache cache) {
        this.llm = llm;
        this.meter = meter;
        this.estimator = estimator;
        this.cache = cache;
    }

    /**
     * 跑一轮对话
     *
     * @param query      本轮问题
     * @param history    该会话的历史轮次（用于改写消解指代）
     * @param index      该实验建好的索引
     * @param experiment 当前实验的策略参数
     * @return 本轮结果（含质量分、检索到的文档、答案）
     */
    public TurnResult run(com.nageoffer.ai.ragent.bench.dataset.BenchQuery query,
                          List<String> history,
                          ChunkIndex index,
                          ExperimentRuntime experiment) {

        String modelCheap = experiment.config().cheapModel();
        String modelPremium = experiment.config().premiumModel();
        boolean routed = experiment.config().routingStrategy() == RoutingStrategy.INTENT_ROUTED;
        boolean cacheEnabled = experiment.config().cacheEnabled();

        // ==================== 1. 意图分类 ====================
        // 轻任务：routed 策略走廉价模型，否则统一走高档
        String intentModel = routed ? modelCheap : modelPremium;
        List<String> intentTree = experiment.intentTreeLines();
        String intentPrompt = PromptAssembler.buildIntentUserMessage(query.question(), intentTree);
        callWithMetering(MeterStage.INTENT, intentModel, LlmTask.INTENT_CLASSIFY,
                PromptTemplates.INTENT_CLASSIFIER, intentPrompt, cacheEnabled);

        // 非知识库问题：直接走系统回答，不检索
        if (!query.isKnowledgeQuery()) {
            String answerModel = routed ? modelCheap : modelPremium;
            String answerPrompt = PromptAssembler.buildAnswerUserMessage(query.question(), List.of());
            String answer = callWithMetering(
                    MeterStage.GENERATE, answerModel, LlmTask.ANSWER_GENERATE,
                    PromptTemplates.ANSWER_BASE, answerPrompt, cacheEnabled);
            double quality = QualityScorer.score(query.referenceDocIds(), List.of(),
                    query.referenceAnswer(), answer, List.of());
            return new TurnResult(query, List.of(), answer, quality, 0, 0);
        }

        // ==================== 2. 问题改写 ====================
        String rewriteModel = routed ? modelCheap : modelPremium;
        String rewritePrompt = PromptAssembler.buildRewriteUserMessage(query.question(), history);
        String rewriteOutput = callWithMetering(
                MeterStage.REWRITE, rewriteModel, LlmTask.QUERY_REWRITE,
                PromptTemplates.QUERY_REWRITE, rewritePrompt, cacheEnabled);
        String rewrittenQuestion = extractRewrittenQuestion(rewriteOutput, query.question());

        // ==================== 3. 多路检索 ====================
        float[] queryVector = experiment.embedder().embed(rewrittenQuestion);
        int recallBudget = experiment.config().recallBudget();

        // 向量通道：始终开启
        List<ChunkIndex.ScoredChunk> vectorHits = index.searchVectorScored(queryVector, recallBudget);
        recordRetrieval(MeterStage.RETRIEVE_VECTOR, rewrittenQuestion, vectorHits, "vector");

        List<ChunkIndex.ScoredChunk> keywordHits = List.of();
        if (experiment.config().retrievalStrategy() == RetrievalStrategy.MULTI_CHANNEL) {
            keywordHits = index.searchKeywordScored(rewrittenQuestion, recallBudget);
            recordRetrieval(MeterStage.RETRIEVE_KEYWORD, rewrittenQuestion, keywordHits, "keyword");
        }

        // ==================== 4. 融合（本地计算，token 为 0，只记耗时）====================
        List<RetrievedChunk> fused = experiment.fusion().fuse(vectorHits, keywordHits);
        recordLocalCompute(MeterStage.FUSION, "local", fused.size(),
                "channels=" + (keywordHits.isEmpty() ? 1 : 2));

        // ==================== 5. 精排 ====================
        List<RetrievedChunk> reranked = experiment.reranker().rerank(
                rewrittenQuestion, fused, experiment.config().topK());
        recordLocalCompute(MeterStage.RERANK, "qwen3-rerank", fused.size(),
                "candidates=" + fused.size() + ",kept=" + reranked.size());

        // ==================== 6. 上下文组装 + 答案生成 ====================
        String answerPrompt = PromptAssembler.buildAnswerUserMessage(rewrittenQuestion, reranked);
        // 答案生成始终走高档次模型：这是质量的最后一道闸门，不省钱
        String answer = callWithMetering(
                MeterStage.GENERATE, modelPremium, LlmTask.ANSWER_GENERATE,
                PromptTemplates.ANSWER_BASE, answerPrompt, cacheEnabled);

        // ==================== 7. 质量评分 ====================
        String judgeModel = routed ? modelCheap : modelPremium;
        String judgePrompt = PromptAssembler.buildJudgeUserMessage(
                query.question(), query.referenceAnswer(), answer);
        callWithMetering(MeterStage.JUDGE, judgeModel, LlmTask.QUALITY_JUDGE,
                PromptTemplates.QUALITY_JUDGE, judgePrompt, cacheEnabled);

        // 质量分用确定性指标算，不用 LLM 打分（避免评委抖动淹没 A/B 信号）
        List<String> retrievedDocIds = reranked.stream()
                .map(RetrievedChunk::getDocId)
                .filter(id -> id != null)
                .toList();
        List<String> citedDocIds = extractCitations(answer, reranked);
        double quality = QualityScorer.score(
                query.referenceDocIds(), retrievedDocIds, query.referenceAnswer(), answer, citedDocIds);

        return new TurnResult(query, retrievedDocIds, answer, quality, fused.size(), reranked.size());
    }

    // ==================== 计量辅助 ====================

    /**
     * 带缓存的 LLM 调用 + 计量（两级缓存，对齐真实供应商的缓存机制）
     * <p>
     * 为什么必须分两级：真实大模型供应商的缓存有两种，命中率天差地别 ——
     * <ol>
     *   <li><b>前缀缓存（prefix caching）</b>：system prompt / 固定指令部分逐字相同即命中。
     *       这是真实 RAG 系统里缓存收益的绝对主体，命中率通常 80%+，
     *       因为所有请求共享同一段系统提示词与引用规则。</li>
     *   <li><b>全量响应缓存</b>：整个 prompt 逐字相同才命中。生产环境里命中率通常只有个位数，
     *       因为用户问题几乎不会完全重复。</li>
     * </ol>
     * 如果只实现全量缓存，测出来的命中率会低到 1% 以下、成本节省几乎为零，
     * 报告里"缓存命中率对成本的影响"就会得出"缓存没用"的错误结论 ——
     * 这不是缓存没用，而是缓存实现方式不对。
     * <p>
     * 计价：前缀命中的部分按缓存单价（通常 1/10）计，未命中部分按正常输入价，输出始终按正常价。
     */
    private String callWithMetering(MeterStage stage, String modelId, LlmTask task,
                                    String system, String user, boolean cacheEnabled) {
        int systemTokens = estimator.estimate(system);
        int userTokens = estimator.estimate(user);
        int inputTokens = systemTokens + userTokens;

        // ---------- 第一级：前缀缓存（system prompt）----------
        // key 只含环节 + 模型 + system，因此同一环节的所有请求都命中同一份前缀
        String prefixKey = ResponseCache.key(stage, modelId, "PREFIX\u0000" + system);
        boolean prefixHit = cacheEnabled && systemTokens > 0 && cache.get(prefixKey) != null;
        if (cacheEnabled && systemTokens > 0 && !prefixHit) {
            cache.put(prefixKey, "1");
        }

        // ---------- 第二级：全量响应缓存 ----------
        String fullKey = ResponseCache.key(stage, modelId, system + "\u0000" + user);
        String cachedFull = cacheEnabled ? cache.get(fullKey) : null;
        boolean fullHit = cachedFull != null;

        long start = System.nanoTime();
        String output;
        if (fullHit) {
            output = cachedFull;
        } else {
            output = llm.chat(modelId, task, system, user);
            if (cacheEnabled) {
                cache.put(fullKey, output);
            }
        }
        long latencyMs = Math.max(1, (System.nanoTime() - start) / 1_000_000);

        int outputTokens = estimator.estimate(output);
        // 命中缓存的输入 token：全量命中 = 全部输入；仅前缀命中 = 只有 system 那部分
        int cachedInputTokens = fullHit ? inputTokens : (prefixHit ? systemTokens : 0);

        meter.record(stage, modelId, providerOf(modelId), inputTokens, outputTokens,
                cachedInputTokens, fullHit, latencyMs,
                "task=" + task.name() + (prefixHit && !fullHit ? ",prefixHit" : ""));
        return output;
    }

    /**
     * 检索通道计量：只计「查询向量化的 embedding 输入 token」
     * <p>
     * 召回到的 chunk 不能记为检索环节的输出 token —— 那些文本会随上下文进入答案生成，
     * 在 GENERATE 的输入里已经计过一次；这里再记等于同一批文本算两遍。
     * 召回条数写进 traceTag，供分析"多路召回带来多少候选"而不污染 token 口径。
     */
    private void recordRetrieval(MeterStage stage, String query,
                                 List<ChunkIndex.ScoredChunk> hits, String tag) {
        int inputTokens = estimator.estimate(query);
        meter.record(stage, "text-embedding-v4", "bailian", inputTokens, 0,
                0, false, Math.max(1, hits.size()),
                "channel=" + tag + ",hits=" + hits.size());
    }

    /**
     * 本地计算环节计量：token 为 0，只记耗时与调用量
     * <p>
     * 融合与精排都是本地 CPU 计算，不产生 LLM 费用；它们的输入文本 token 已计入 GENERATE，
     * 因此这里只记录耗时与候选规模，避免重复计数。
     */
    private void recordLocalCompute(MeterStage stage, String modelId, int candidates, String tag) {
        meter.record(stage, modelId, "bailian", 0, 0, 0, false,
                Math.max(1, candidates), tag);
    }

    private String providerOf(String modelId) {
        if (modelId == null) {
            return "unknown";
        }
        if (modelId.startsWith("qwen")) {
            return "bailian";
        }
        if (modelId.startsWith("deepseek")) {
            return "deepseek";
        }
        if (modelId.startsWith("gpt")) {
            return "aihubmix";
        }
        return "local";
    }

    /**
     * 从改写 JSON 输出里抽改写后的问题
     * <p>
     * 确定性客户端的输出可能被截断，解析失败时回落到原问题 ——
     * 这不影响 token 计量（输出长度已按真实分布生成），只影响检索文本。
     */
    private String extractRewrittenQuestion(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        int start = raw.indexOf("\"rewrite\":\"");
        if (start < 0) {
            return fallback;
        }
        int from = start + "\"rewrite\":\"".length();
        int end = raw.indexOf('"', from);
        if (end <= from) {
            return fallback;
        }
        String value = raw.substring(from, end).strip();
        return value.isEmpty() ? fallback : value;
    }

    /**
     * 从答案里抽引用：按答案中出现的文档标题近似识别引用
     * <p>
     * 真实链路里引用是 {@code [N](#cite-N)} 形式；确定性客户端产出的答案也带该格式，
     * 但编号是上下文内的序号而非 docId，因此这里按"答案是否提到该文档标题"来判定引用，
     * 保证 citationPrecision 有真实输入，而不是恒为常数。
     */
    private List<String> extractCitations(String answer, List<RetrievedChunk> reranked) {
        if (answer == null || reranked.isEmpty()) {
            return List.of();
        }
        Set<String> cited = new LinkedHashSet<>();
        for (RetrievedChunk chunk : reranked) {
            String docName = chunk.getDocName();
            if (docName != null && !docName.isBlank() && answer.contains(docName)) {
                cited.add(chunk.getDocId());
            }
        }
        return new ArrayList<>(cited);
    }
}
