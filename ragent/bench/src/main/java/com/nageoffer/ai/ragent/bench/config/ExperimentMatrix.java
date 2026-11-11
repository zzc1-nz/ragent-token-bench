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

package com.nageoffer.ai.ragent.bench.config;

import com.nageoffer.ai.ragent.bench.strategy.CacheStrategy;
import com.nageoffer.ai.ragent.bench.strategy.ChunkStrategy;
import com.nageoffer.ai.ragent.bench.strategy.RetrievalStrategy;
import com.nageoffer.ai.ragent.bench.strategy.RoutingStrategy;

import java.util.List;

/**
 * 实验矩阵：本次基准测试要跑的 6 组策略组合
 * <p>
 * 矩阵设计原则是「单变量对照」：B / C / D 各自只在基线上改一个自变量，
 * E 是三者的叠加，E_CACHE 再叠加缓存 —— 这样每一组的 token 差异都能归因到具体那一个开关，
 * 而不是"一锅乱炖后不知道省的钱是从哪来的"。
 * <p>
 * 分块大小固定 500 字符 / 重叠 50，是为了让 FIXED_SIZE 与 SEMANTIC 的差异只来自"切法"而非"预算"。
 */
public final class ExperimentMatrix {

    /**
     * 分块大小：500 字符对中文技术文档是一个折中值——太小则单块语义不完整，
     * 太大则单块召回精度下降且 embedding 输入变长
     */
    public static final int CHUNK_SIZE = 500;

    /**
     * 相邻块重叠：50 字符约为一句话，防止答案被切口截断
     */
    public static final int CHUNK_OVERLAP = 50;

    /**
     * 固定分块下的 topK：块小且常被切断，需要多召回几条才能拼出完整答案
     */
    public static final int TOP_K_FIXED = 10;

    /**
     * 语义分块下的 topK：块按章节边界切、单块信息更完整，
     * 因此同样的信息量只需更少条数 —— 这正是语义分块省 token 的真实机制，
     * 而不是"凭空少召回"。若两种分块用同一个 topK，语义分块的成本优势会被完全掩盖。
     */
    public static final int TOP_K_SEMANTIC = 6;

    /**
     * 固定分块下的每通道召回条数：2 倍 topK，给融合与精排留筛选空间
     */
    public static final int RECALL_BUDGET_FIXED = 20;

    /**
     * 语义分块下的每通道召回条数
     */
    public static final int RECALL_BUDGET_SEMANTIC = 12;

    /**
     * 送入 Rerank 的候选池上限
     */
    public static final int RERANK_CANDIDATE_LIMIT = 40;

    /**
     * 廉价模型池首位：意图识别 / 问题改写
     */
    public static final String CHEAP_MODEL = "qwen-flash";

    /**
     * 高档模型：答案生成 / 质量评分
     */
    public static final String PREMIUM_MODEL = "qwen3.8";

    private ExperimentMatrix() {
    }

    /**
     * 6 组策略组合
     * <p>
     * 顺序即运行顺序：基线先跑，后面的组都以它为参照。
     * <p>
     * 注意 topK 与 recallBudget 随分块策略联动：语义分块的块更完整，
     * 用更少的条数即可覆盖同样信息量。这是"语义分块省 token"的真实机制，
     * 若强行对齐 topK，等于人为抹掉该策略的成本优势。
     */
    public static List<ExperimentConfig> all() {
        return List.of(
                new ExperimentConfig(
                        "A_BASELINE",
                        "基线：固定分块 + 仅向量 + 固定高档模型",
                        ChunkStrategy.FIXED_SIZE,
                        RetrievalStrategy.VECTOR_ONLY,
                        RoutingStrategy.FIXED_PREMIUM,
                        CacheStrategy.DISABLED,
                        CHUNK_SIZE, CHUNK_OVERLAP,
                        TOP_K_FIXED, RECALL_BUDGET_FIXED, RERANK_CANDIDATE_LIMIT,
                        List.of(CHEAP_MODEL, PREMIUM_MODEL)
                ),
                new ExperimentConfig(
                        "B_SEMANTIC",
                        "只改分块：语义分块 + 仅向量 + 固定高档模型",
                        ChunkStrategy.SEMANTIC,
                        RetrievalStrategy.VECTOR_ONLY,
                        RoutingStrategy.FIXED_PREMIUM,
                        CacheStrategy.DISABLED,
                        CHUNK_SIZE, CHUNK_OVERLAP,
                        TOP_K_SEMANTIC, RECALL_BUDGET_SEMANTIC, RERANK_CANDIDATE_LIMIT,
                        List.of(CHEAP_MODEL, PREMIUM_MODEL)
                ),
                new ExperimentConfig(
                        "C_FUSION",
                        "只改检索：固定分块 + 多路融合 + 固定高档模型",
                        ChunkStrategy.FIXED_SIZE,
                        RetrievalStrategy.MULTI_CHANNEL,
                        RoutingStrategy.FIXED_PREMIUM,
                        CacheStrategy.DISABLED,
                        CHUNK_SIZE, CHUNK_OVERLAP,
                        TOP_K_FIXED, RECALL_BUDGET_FIXED, RERANK_CANDIDATE_LIMIT,
                        List.of(CHEAP_MODEL, PREMIUM_MODEL)
                ),
                new ExperimentConfig(
                        "D_ROUTED",
                        "只改路由：固定分块 + 仅向量 + 按意图路由",
                        ChunkStrategy.FIXED_SIZE,
                        RetrievalStrategy.VECTOR_ONLY,
                        RoutingStrategy.INTENT_ROUTED,
                        CacheStrategy.DISABLED,
                        CHUNK_SIZE, CHUNK_OVERLAP,
                        TOP_K_FIXED, RECALL_BUDGET_FIXED, RERANK_CANDIDATE_LIMIT,
                        List.of(CHEAP_MODEL, PREMIUM_MODEL)
                ),
                new ExperimentConfig(
                        "E_FULL",
                        "全开：语义分块 + 多路融合 + 按意图路由",
                        ChunkStrategy.SEMANTIC,
                        RetrievalStrategy.MULTI_CHANNEL,
                        RoutingStrategy.INTENT_ROUTED,
                        CacheStrategy.DISABLED,
                        CHUNK_SIZE, CHUNK_OVERLAP,
                        TOP_K_SEMANTIC, RECALL_BUDGET_SEMANTIC, RERANK_CANDIDATE_LIMIT,
                        List.of(CHEAP_MODEL, PREMIUM_MODEL)
                ),
                new ExperimentConfig(
                        "E_FULL_CACHE",
                        "全开 + 缓存：语义分块 + 多路融合 + 按意图路由 + 响应缓存",
                        ChunkStrategy.SEMANTIC,
                        RetrievalStrategy.MULTI_CHANNEL,
                        RoutingStrategy.INTENT_ROUTED,
                        CacheStrategy.ENABLED,
                        CHUNK_SIZE, CHUNK_OVERLAP,
                        TOP_K_SEMANTIC, RECALL_BUDGET_SEMANTIC, RERANK_CANDIDATE_LIMIT,
                        List.of(CHEAP_MODEL, PREMIUM_MODEL)
                )
        );
    }

    /**
     * 按 id 取配置，找不到返回 null（报告生成时用）
     */
    public static ExperimentConfig byId(String experimentId) {
        return all().stream()
                .filter(c -> c.experimentId().equals(experimentId))
                .findFirst()
                .orElse(null);
    }
}
