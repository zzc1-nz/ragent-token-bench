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

package com.nageoffer.ai.ragent.bench.prompt;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;

import java.util.List;

/**
 * 提示词组装器：把检索结果拼成真实 RAG 提示词
 * <p>
 * 这是 token 计量里「输入 token」的**唯一来源**。它决定了策略差异如何转化为成本差异：
 * <ul>
 *   <li>分块策略不同 → chunk 长度不同 → 同样 topK 条，上下文总字符数不同</li>
 *   <li>检索策略不同 → 融合后候选池更大 → 若 topK 不变则上下文长度相近，但 Rerank 输入更长</li>
 *   <li>路由策略不同 → 不改上下文长度，只改计价模型</li>
 * </ul>
 * 因此这里必须**如实按 Ragent 的上下文格式拼接**，不能偷工减料，
 * 否则各策略之间的 token 差异会被压缩，A/B 结论失真。
 */
public final class PromptAssembler {

    private PromptAssembler() {
    }

    /**
     * 组装答案生成的用户消息：系统提示词 + 上下文块 + 问题
     * <p>
     * 与 Ragent {@code RAGPromptService.buildStructuredMessages} 的结构一致：
     * 参考资料按文档聚合，每份资料用 {@code <content>} 包裹并带编号。
     *
     * @param question 用户问题（已改写）
     * @param chunks   检索并精排后的 chunk 列表
     */
    public static String buildAnswerUserMessage(String question, List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<references>\n");
        int index = 1;
        for (RetrievedChunk chunk : chunks) {
            String docName = chunk.getDocName() == null ? "unknown" : chunk.getDocName();
            String block = String.format(PromptTemplates.CONTEXT_BLOCK_TEMPLATE, docName, chunk.getText());
            sb.append("<document index=\"").append(index++).append("\">\n");
            sb.append(block);
            sb.append("</document>\n");
        }
        sb.append("</references>\n\n");
        sb.append("<question>\n").append(question).append("\n</question>");
        return sb.toString();
    }

    /**
     * 组装意图分类的输入：意图树 + 问题
     * <p>
     * 意图树来自文档领域，这里按检索到的候选文档标题反推一棵小树 ——
     * 目的是让意图分类的输入长度随「知识库规模」变化，而不是恒定值。
     *
     * @param question 用户问题
     * @param treeLines 意图树节点行（每行一个节点描述）
     */
    public static String buildIntentUserMessage(String question, List<String> treeLines) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("意图树：\n");
        for (String line : treeLines) {
            sb.append("- ").append(line).append('\n');
        }
        sb.append("\n用户问题：").append(question);
        return sb.toString();
    }

    /**
     * 组装问题改写的输入：历史 + 当前问题
     *
     * @param question 当前问题
     * @param history  历史轮次（"用户：..."/"助手：..." 形式）
     */
    public static String buildRewriteUserMessage(String question, List<String> history) {
        StringBuilder sb = new StringBuilder(1024);
        if (history != null && !history.isEmpty()) {
            sb.append("对话历史：\n");
            for (String turn : history) {
                sb.append(turn).append('\n');
            }
            sb.append('\n');
        }
        sb.append("当前问题：").append(question);
        return sb.toString();
    }

    /**
     * 组装质量评分的输入：问题 + 参考答案 + 模型答案
     */
    public static String buildJudgeUserMessage(String question, String referenceAnswer, String modelAnswer) {
        return "问题：" + question
                + "\n\n参考答案：" + referenceAnswer
                + "\n\n模型答案：" + modelAnswer;
    }
}
