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

package com.nageoffer.ai.ragent.bench.strategy;

/**
 * 检索路数策略：A/B 对比的第二个自变量
 * <p>
 * 对应 Ragent 的 {@code rag.search.channels.*.enabled} 与后置处理器链：
 * <ul>
 *   <li>{@link #VECTOR_ONLY}：只开向量通道，跳过 RRF 融合（单通道时 Fusion 本就早退），
 *       候选直接进 Rerank —— 最便宜，但同义改写、专有名词精确匹配容易漏</li>
 *   <li>{@link #MULTI_CHANNEL}：向量 + 关键词双通道并行，经 RRF 融合后截断再 Rerank ——
 *       多一路召回意味着多一份候选池，Rerank 输入更长，token 更贵但召回更稳</li>
 * </ul>
 */
public enum RetrievalStrategy {

    /**
     * 仅向量检索：单通道，无融合
     */
    VECTOR_ONLY("仅向量检索"),

    /**
     * 多路融合检索：向量 + 关键词，RRF 融合
     */
    MULTI_CHANNEL("多路融合检索");

    private final String displayName;

    RetrievalStrategy(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
