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
import com.nageoffer.ai.ragent.bench.retrieval.ChunkIndex;
import com.nageoffer.ai.ragent.bench.retrieval.HashingEmbedder;

import java.util.List;

/**
 * 单个实验的运行期上下文：配置 + 已建好的索引 + 意图树
 * <p>
 * 把「一次实验里所有轮次共享的东西」集中在一个对象里，
 * 避免每轮都重建索引（200 篇文档 × 6 组实验 = 1200 次建索引，会很慢）。
 *
 * @param config          该实验的策略配置
 * @param index           该实验建好的索引（分块策略不同 → 索引不同）
 * @param embedder        向量器（查询时用，与建索引时同一个实例保证维度一致）
 * @param fusion          融合器
 * @param reranker        精排器
 * @param intentTreeLines 意图树节点行（供意图分类提示词拼接）
 */
public record ExperimentRuntime(
        ExperimentConfig config,
        ChunkIndex index,
        HashingEmbedder embedder,
        RrfFusion fusion,
        SimpleReranker reranker,
        List<String> intentTreeLines
) {

    public ExperimentRuntime {
        if (config == null) {
            throw new IllegalArgumentException("config 不能为空");
        }
        intentTreeLines = intentTreeLines == null ? List.of() : List.copyOf(intentTreeLines);
    }
}
