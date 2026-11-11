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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确定性 LLM 客户端单测：输出长度是否真的按任务分布，直接决定成本模型准不准
 */
class DeterministicLlmClientTest {

    private final DeterministicLlmClient client = new DeterministicLlmClient();

    @Test
    @DisplayName("输出长度精确等于任务的期望长度：长度漂移会让实验间不可比")
    void outputLengthMatchesTask() {
        for (LlmTask task : LlmTask.values()) {
            String out = client.chat("qwen-flash", task, "系统提示", "用户问题");
            assertEquals(task.getExpectedOutputChars(), out.length(),
                    "任务 " + task.name() + " 的输出长度应精确等于期望值");
        }
    }

    @Test
    @DisplayName("确定性：同输入多次调用输出完全一致")
    void deterministic() {
        String first = client.chat("qwen-flash", LlmTask.ANSWER_GENERATE, "sys", "问题A");
        for (int i = 0; i < 10; i++) {
            assertEquals(first, client.chat("qwen-flash", LlmTask.ANSWER_GENERATE, "sys", "问题A"));
        }
    }

    @Test
    @DisplayName("不同输入产生不同输出：避免所有答案雷同导致质量分失真")
    void differentInputsDiffer() {
        String a = client.chat("qwen-flash", LlmTask.ANSWER_GENERATE, "sys", "问题A");
        String b = client.chat("qwen-flash", LlmTask.ANSWER_GENERATE, "sys", "问题B");
        assertFalse(a.equals(b), "不同问题的答案不应完全相同");
    }

    @Test
    @DisplayName("答案生成会真的用上上下文：命中上下文的句子应出现在答案里")
    void answerUsesContext() {
        String prompt = """
                <references>
                <document index="1">
                <content data-ragent-doc-id="DOC_0001">R5 扫地机器人整机保修两年，电池保修一年。</content>
                </document>
                </references>

                <question>
                扫地机器人保修多久？
                </question>
                """;
        String answer = client.chat("qwen3.8", LlmTask.ANSWER_GENERATE, "回答规则", prompt);
        assertTrue(answer.contains("保修"), "答案应包含上下文里的关键词，实际：" + answer.substring(0, 40));
    }

    @Test
    @DisplayName("无检索上下文时如实回答资料不足，而不是编造")
    void answerWithoutContext() {
        String answer = client.chat("qwen3.8", LlmTask.ANSWER_GENERATE, "规则", "问题：某某型号价格？");
        assertTrue(answer.contains("未覆盖") || answer.contains("无法"),
                "无上下文时不应编造答案，实际：" + answer.substring(0, 40));
    }

    @Test
    @DisplayName("意图分类：闲聊类问题返回空意图，知识类返回 kb 意图")
    void intentClassification() {
        String chitchat = client.chat("qwen-flash", LlmTask.INTENT_CLASSIFY, "规则",
                "意图树：\n- kb_general\n\n用户问题：你好呀");
        assertTrue(chitchat.contains("\"intents\":[]"), "闲聊应返回空意图，实际：" + chitchat);

        String kb = client.chat("qwen-flash", LlmTask.INTENT_CLASSIFY, "规则",
                "意图树：\n- kb_general\n\n用户问题：R5 保修多久？");
        assertTrue(kb.contains("kb_general"), "知识类问题应返回 kb 意图，实际：" + kb);
    }

    @Test
    @DisplayName("向量化返回与输入等长的结果，维度 1536")
    void embedBatch() {
        List<float[]> vectors = client.embedBatch("text-embedding-v4", List.of("文本一", "文本二"));
        assertEquals(2, vectors.size());
        assertEquals(1536, vectors.get(0).length);
    }

    @Test
    @DisplayName("客户端自报为确定性本地实现，报告里要如实标注")
    void clientName() {
        assertEquals("deterministic-local", client.clientName());
        assertFalse(client.isLive(), "确定性客户端不应声称是真实联网调用");
    }
}
