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

package com.nageoffer.ai.ragent.bench.llm;

import java.util.List;

/**
 * 基准测试用的 LLM 调用抽象
 * <p>
 * 存在的意义：基准测试要量化的是 **token 消耗与成本**，不是模型回答得好不好。
 * 而 token 消耗由「prompt 有多长」+「回答有多长」决定，这两件事都可以在本地精确复现：
 * <ul>
 *   <li>prompt：由真实的 RAG 提示词模板 + 真实检索到的 chunk 拼出来，长度是真实的</li>
 *   <li>回答：按任务类型给出符合真实分布的输出长度</li>
 * </ul>
 * 因此默认实现 {@link DeterministicLlmClient} 不联网、无随机、完全可复现，
 * 却产出与真实调用同量级的 token 计量 —— 这是让 500 轮 × 6 组实验（3000 次调用）
 * 能在几分钟内跑完、且结果可复现的前提。
 * <p>
 * 需要真实模型时切到 {@link OpenAiCompatibleLlmClient}，接口完全一致，
 * 计量层与报告层无需改动。
 */
public interface LlmClient {

    /**
     * 一次同步对话调用
     *
     * @param modelId 模型 id（决定计价，也决定是否走"廉价/高档"档位）
     * @param task    任务类型，决定输出长度分布
     * @param system  系统提示词（可为 null）
     * @param user    用户消息（含 RAG 上下文，长度是真实的）
     * @return 模型输出文本
     */
    String chat(String modelId, LlmTask task, String system, String user);

    /**
     * 批量 embedding：返回每个输入文本的向量维度占位
     * <p>
     * 基准测试只需要 embedding 的 **token 数**（输入文本长度）与调用次数，
     * 不需要真实向量；因此返回每个文本一个固定长度的占位向量即可。
     *
     * @param modelId 向量模型 id
     * @param texts   待向量化文本
     * @return 与 texts 等长的向量列表
     */
    List<float[]> embedBatch(String modelId, List<String> texts);

    /**
     * 客户端类型名，写进报告以便区分"模拟"与"真实"
     */
    String clientName();

    /**
     * 是否真实联网调用
     */
    default boolean isLive() {
        return false;
    }
}
