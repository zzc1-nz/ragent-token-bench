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

package com.nageoffer.ai.ragent.bench.dataset;

import java.util.List;

/**
 * 基准测试中的一轮对话
 *
 * @param conversationId  会话 ID（如 {@code CONV_0001}），多轮追问共享
 * @param turnIndex       轮次下标，0 起
 * @param question        用户问题
 * @param referenceDocIds 标准答案所在的文档 ID 列表，用于算 recall@k
 * @param referenceAnswer 参考答案，用于算答案要点覆盖率
 * @param intentHint      意图提示：kb / chitchat / system，用于校验路由决策
 */
public record BenchQuery(
        String conversationId,
        int turnIndex,
        String question,
        List<String> referenceDocIds,
        String referenceAnswer,
        String intentHint
) {

    public BenchQuery {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        if (turnIndex < 0) {
            throw new IllegalArgumentException("turnIndex 必须 >= 0");
        }
        question = question == null ? "" : question;
        referenceDocIds = referenceDocIds == null ? List.of() : List.copyOf(referenceDocIds);
        referenceAnswer = referenceAnswer == null ? "" : referenceAnswer;
        intentHint = intentHint == null || intentHint.isBlank() ? "kb" : intentHint;
    }

    /**
     * 是否知识库类问题（需要检索）
     */
    public boolean isKnowledgeQuery() {
        return "kb".equalsIgnoreCase(intentHint);
    }
}
