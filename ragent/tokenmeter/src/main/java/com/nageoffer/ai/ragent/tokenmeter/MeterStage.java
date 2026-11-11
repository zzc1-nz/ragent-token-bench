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

package com.nageoffer.ai.ragent.tokenmeter;

/**
 * 计量环节枚举（对应契约 4.1）。
 * <p>
 * 用途：标识一次 Token 消耗发生在 Agentic RAG 流水线的哪一个环节，用于分环节归因分析。
 * 枚举顺序即为报表中阶段展示顺序（{@link MeterAggregator} 依赖该顺序稳定输出）。
 * <p>
 * 说明：
 * - ETL / 文档入库链路：{@link #PARSE}、{@link #CHUNK}、{@link #EMBED}
 * - 在线问答链路：{@link #INTENT} 起至 {@link #SUMMARY}
 * - {@link #FUSION} 为本地计算（RRF 融合），token 恒为 0，仅用于耗时观测
 * - 契约 4.1 原文共 15 个枚举值，此处与契约保持完全一致
 */
public enum MeterStage {

    // ---- ETL / 文档入库 ----

    /**
     * 文档解析：PDF / DOCX / MD 等原始文档转换为文本块。
     */
    PARSE,

    /**
     * 分块：把解析后的长文本切分为检索单元（固定大小或语义分块）。
     */
    CHUNK,

    /**
     * 向量化：文本转向量，含批量调用。
     */
    EMBED,

    // ---- 在线问答 ----

    /**
     * 意图识别 / 分类。
     */
    INTENT,

    /**
     * 问题改写 + 子问题拆分。
     */
    REWRITE,

    /**
     * 向量召回。
     */
    RETRIEVE_VECTOR,

    /**
     * 关键词召回。
     */
    RETRIEVE_KEYWORD,

    /**
     * 图谱召回。
     */
    RETRIEVE_GRAPH,

    /**
     * 联网搜索召回。
     */
    RETRIEVE_WEB,

    /**
     * 多路融合（RRF）：本地计算，token 恒为 0，仅用于耗时观测。
     */
    FUSION,

    /**
     * 精排（Rerank）。
     */
    RERANK,

    /**
     * 答案生成。
     */
    GENERATE,

    /**
     * 质量评分（LLM-as-judge）。
     */
    JUDGE,

    /**
     * 会话摘要 / 记忆压缩。
     */
    SUMMARY,

    /**
     * 其他未归类的调用。
     */
    OTHER
}
