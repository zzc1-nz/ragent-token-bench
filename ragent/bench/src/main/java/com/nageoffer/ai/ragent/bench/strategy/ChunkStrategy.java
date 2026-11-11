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
 * 分块策略：同一批文档，切法不同 → 块数不同 → 入库 embedding token 不同 → 检索召回粒度不同
 * <p>
 * 这是 A/B 对比的第一个自变量。两种策略都走 Ragent 真实的 {@code ChunkingService} 入口，
 * 区别只在于预算与是否启用 Block 感知分发：
 * <ul>
 *   <li>{@link #FIXED_SIZE}：固定字符窗口 + 重叠，退化为纯文本滑窗，模拟"最朴素的分块"</li>
 *   <li>{@link #SEMANTIC}：走 Ragent 的 BlockAwareChunkerDispatcher，按标题/段落/列表/表格语义边界切，
 *       切口落在语义单元边界上，块数通常更少但单块信息更完整</li>
 * </ul>
 */
public enum ChunkStrategy {

    /**
     * 固定大小分块：按 maxChars 滑窗，overlap 重叠，切口落在自然边界（换行/句末）附近
     */
    FIXED_SIZE("固定大小分块"),

    /**
     * 语义分块：按 Markdown Block 结构（标题层级 / 段落 / 列表 / 表格）分块并按章节打包
     */
    SEMANTIC("语义分块");

    private final String displayName;

    ChunkStrategy(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
