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

/**
 * 任务类型：决定输出长度的期望分布
 * <p>
 * 输出长度是 token 成本里最贵的一半（输出单价通常是输入的 2~10 倍），
 * 所以不能所有任务都给同一个长度，否则成本模型失真。
 * 这里的字符数是按真实模型在对应任务上的典型输出反推的经验值。
 */
public enum LlmTask {

    /**
     * 意图分类：输出是一小段 JSON（意图 ID + 分数），极短
     */
    INTENT_CLASSIFY("意图分类", 90),

    /**
     * 问题改写 + 子问题拆分：输出是一段 JSON，含改写后问题与子问题数组
     */
    QUERY_REWRITE("问题改写", 140),

    /**
     * 答案生成：输出是完整自然语言回答，最长
     */
    ANSWER_GENERATE("答案生成", 320),

    /**
     * 质量评分（LLM-as-judge）：输出是一段评分 JSON + 简短理由
     */
    QUALITY_JUDGE("质量评分", 160),

    /**
     * 会话摘要压缩：输出是历史摘要
     */
    SUMMARY("会话摘要", 220);

    private final String displayName;

    /**
     * 期望输出字符数：确定性客户端按此生成等长文本，使 token 计量可复现
     */
    private final int expectedOutputChars;

    LlmTask(String displayName, int expectedOutputChars) {
        this.displayName = displayName;
        this.expectedOutputChars = expectedOutputChars;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getExpectedOutputChars() {
        return expectedOutputChars;
    }
}
