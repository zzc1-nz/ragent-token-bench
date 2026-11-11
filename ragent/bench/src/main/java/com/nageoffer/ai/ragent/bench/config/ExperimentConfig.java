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
 * 一次基准实验的完整策略组合（不可变）
 * <p>
 * 这是「实验配置层」的核心数据结构：把 Ragent 里散落在
 * {@code rag.*} / {@code ai.chat.*} / 后置处理器开关里的策略选择，
 * 收敛成一个可序列化、可对比、可复现的单一配置对象。
 * <p>
 * 每个字段都对应一个 A/B 自变量，{@code experimentId} 是这组自变量的稳定指纹，
 * 同一个 experimentId 必须产出同样的 token 计量结果（确定性运行器的前提）。
 *
 * @param experimentId      实验稳定标识，如 {@code "B_SEMANTIC"}
 * @param displayName       中文展示名，用于报告与仪表盘
 * @param chunkStrategy     分块策略
 * @param retrievalStrategy 检索路数策略
 * @param routingStrategy   模型路由策略
 * @param cacheStrategy     响应缓存策略
 * @param chunkSize         分块大小（字符数），FIXED_SIZE 与 SEMANTIC 的打包上限都用它
 * @param chunkOverlap      相邻块重叠字符数，仅 FIXED_SIZE 生效（语义分块按节边界切，天然不重叠）
 * @param topK              最终进入 LLM 的条数
 * @param recallBudget      每通道召回条数
 * @param rerankCandidateLimit 融合后送入 Rerank 的候选池上限
 */
public record ExperimentConfig(
        String experimentId,
        String displayName,
        ChunkStrategy chunkStrategy,
        RetrievalStrategy retrievalStrategy,
        RoutingStrategy routingStrategy,
        CacheStrategy cacheStrategy,
        int chunkSize,
        int chunkOverlap,
        int topK,
        int recallBudget,
        int rerankCandidateLimit,
        List<String> models
) {

    /**
     * 校验并做防御性拷贝：配置错误要在启动时炸掉，而不是跑到一半产出脏数据
     */
    public ExperimentConfig {
        if (experimentId == null || experimentId.isBlank()) {
            throw new IllegalArgumentException("experimentId 不能为空");
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须 > 0，实际：" + chunkSize);
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap 必须落在 [0, chunkSize) 区间，实际：" + chunkOverlap);
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须 > 0，实际：" + topK);
        }
        if (recallBudget < topK) {
            throw new IllegalArgumentException("recallBudget(" + recallBudget + ") 必须 >= topK(" + topK + ")");
        }
        if (rerankCandidateLimit > 0 && rerankCandidateLimit < topK) {
            throw new IllegalArgumentException(
                    "rerankCandidateLimit(" + rerankCandidateLimit + ") 必须 >= topK(" + topK + ")");
        }
        models = models == null ? List.of() : List.copyOf(models);
    }

    /**
     * 廉价模型 id：路由策略为 INTENT_ROUTED 时，意图识别与问题改写走它
     */
    public String cheapModel() {
        return models.isEmpty() ? "qwen-flash" : models.get(0);
    }

    /**
     * 高档模型 id：答案生成与质量评分始终走它
     */
    public String premiumModel() {
        return models.size() < 2 ? "qwen3.8" : models.get(1);
    }

    /**
     * 该策略组合是否开启缓存
     */
    public boolean cacheEnabled() {
        return cacheStrategy == CacheStrategy.ENABLED;
    }

    /**
     * 生成配置指纹行，用于报告表格与仪表盘 tooltip
     */
    public String signature() {
        return chunkStrategy + " / " + retrievalStrategy + " / " + routingStrategy + " / " + cacheStrategy;
    }
}
