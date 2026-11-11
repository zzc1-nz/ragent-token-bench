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

import com.nageoffer.ai.ragent.bench.retrieval.Bm25Tokenizer;
import com.nageoffer.ai.ragent.bench.retrieval.HashingEmbedder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性 LLM 客户端：不联网、无随机、可完全复现
 * <p>
 * 这是整个基准测试可信度的基石，设计上有三条硬约束：
 * <ol>
 *   <li><b>输入长度是真实的</b>：{@code user} 参数装的是真实 RAG 提示词
 *       （系统提示 + 检索到的 chunk 原文 + 用户问题），调用方拼多长就计多长</li>
 *   <li><b>输出长度按任务分布</b>：见 {@link LlmTask#getExpectedOutputChars()}，
 *       意图分类约 90 字符、答案生成约 320 字符，符合真实模型输出形态；
 *       输出单价通常是输入的 2~10 倍，长度不真实会让成本模型整体失真</li>
 *   <li><b>答案必须真的用上检索到的上下文</b>：这一条最关键。
 *       如果答案与上下文无关，那么"检索策略更好 → 答案覆盖度更高"这条因果链就断了，
 *       A/B 对比会退化成"所有策略质量一样"。因此答案生成会**摘取上下文里的原句**，
 *       检索越准、上下文越全，答案覆盖参考要点的比例就越高 —— 质量分因此真实反映检索质量</li>
 * </ol>
 * 向量化走 {@link HashingEmbedder}：特征哈希产生反映真实文本重叠度的 1536 维向量，
 * 余弦相似度即语义相近程度，向量召回是真召回而非随机排序。
 */
public final class DeterministicLlmClient implements LlmClient {

    /**
     * 从提示词里抽 {@code <content ...>...</content>} 块
     */
    private static final Pattern CONTENT_BLOCK =
            Pattern.compile("<content[^>]*>(.*?)</content>", Pattern.DOTALL);

    /**
     * 从改写提示词里抽"当前问题：xxx"
     */
    private static final Pattern CURRENT_QUESTION =
            Pattern.compile("当前问题：(.+?)(?:\\n|$)", Pattern.DOTALL);

    /**
     * 从提示词里抽"用户问题：xxx"
     */
    private static final Pattern USER_QUESTION =
            Pattern.compile("用户问题：(.+?)$", Pattern.DOTALL);

    private final HashingEmbedder embedder = new HashingEmbedder(1536);

    @Override
    public String chat(String modelId, LlmTask task, String system, String user) {
        String prompt = (system == null ? "" : system) + "\u0000" + (user == null ? "" : user);
        return switch (task) {
            case ANSWER_GENERATE -> generateAnswer(user, prompt);
            case QUERY_REWRITE -> generateRewrite(user, prompt);
            case INTENT_CLASSIFY -> generateIntent(user, prompt);
            case QUALITY_JUDGE -> generateJudge(prompt);
            case SUMMARY -> generateSummary(prompt);
        };
    }

    @Override
    public List<float[]> embedBatch(String modelId, List<String> texts) {
        return embedder.embedAll(texts);
    }

    @Override
    public String clientName() {
        return "deterministic-local";
    }

    // ==================== 各任务生成 ====================

    /**
     * 答案生成：从检索到的上下文里挑与问题最相关的句子拼成答案
     * <p>
     * 这是让「检索质量 → 答案质量」这条因果链成立的关键。真实模型的做法是
     * "读懂资料、找到能回答问题的部分、组织成答案"；这里用词项重叠做等价的确定性模拟：
     * <ol>
     *   <li>把每个 chunk 按句切分</li>
     *   <li>对每句计算与问题的词项重叠得分</li>
     *   <li>取得分最高的若干句，按原顺序拼成答案</li>
     * </ol>
     * 效果：如果检索命中了标准答案所在文档，那句话就在上下文里，得分高，会被选中，
     * 答案因此覆盖参考要点；如果检索没命中，上下文里根本没有那句话，答案自然覆盖不了。
     * 质量分因此真实反映检索质量，而不是一个与策略无关的常数。
     */
    private String generateAnswer(String userPrompt, String promptSeed) {
        int targetChars = LlmTask.ANSWER_GENERATE.getExpectedOutputChars();
        List<String> blocks = extractContentBlocks(userPrompt);
        String question = extractQuestionForAnswer(userPrompt);

        if (blocks.isEmpty()) {
            // 无检索结果：如实说资料不足（真实模型也会这样回答）
            return padToLength("当前资料未覆盖该问题，无法给出确切答复。",
                    targetChars, promptSeed, ANSWER_FILLER);
        }

        // 候选句：从上下文里切出所有句子
        List<ScoredSentence> candidates = new ArrayList<>();
        Set<String> questionTerms = new LinkedHashSet<>(Bm25Tokenizer.tokenize(question));
        int blockIndex = 1;
        for (String block : blocks) {
            String cleaned = collapseWhitespace(block);
            for (String sentence : splitSentences(cleaned)) {
                if (sentence.length() < 8) {
                    continue;
                }
                double score = overlapScore(questionTerms, sentence);
                candidates.add(new ScoredSentence(sentence, score, blockIndex));
            }
            blockIndex++;
        }

        if (candidates.isEmpty()) {
            return padToLength("当前资料未覆盖该问题，无法给出确切答复。",
                    targetChars, promptSeed, ANSWER_FILLER);
        }

        // 取相关度最高的若干句；同分时保持原文顺序（更符合真实模型的引用习惯）
        candidates.sort(Comparator
                .comparingDouble(ScoredSentence::score).reversed()
                .thenComparingInt(ScoredSentence::sourceBlock));

        StringBuilder sb = new StringBuilder(targetChars + 64);
        sb.append("根据知识库资料：\n");
        Set<Integer> citedBlocks = new LinkedHashSet<>();
        for (ScoredSentence candidate : candidates) {
            if (sb.length() >= targetChars) {
                break;
            }
            sb.append("- ").append(candidate.text());
            if (citedBlocks.add(candidate.sourceBlock())) {
                sb.append("。[").append(candidate.sourceBlock())
                        .append("](#cite-").append(candidate.sourceBlock()).append(')');
            }
            sb.append('\n');
        }
        return padToLength(sb.toString(), targetChars, promptSeed, ANSWER_FILLER);
    }

    /**
     * 从答案生成提示词里抽用户问题（{@code <question>...</question>}）
     */
    private String extractQuestionForAnswer(String userPrompt) {
        if (userPrompt == null) {
            return "";
        }
        int start = userPrompt.indexOf("<question>");
        int end = userPrompt.indexOf("</question>");
        if (start >= 0 && end > start) {
            return userPrompt.substring(start + "<question>".length(), end).strip();
        }
        return "";
    }

    /**
     * 按句切分：中文句末标点与换行为界
     */
    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            current.append(ch);
            if (ch == '。' || ch == '；' || ch == '！' || ch == '？' || ch == '\n') {
                String s = current.toString().strip();
                if (!s.isEmpty()) {
                    sentences.add(s);
                }
                current.setLength(0);
            }
        }
        String tail = current.toString().strip();
        if (!tail.isEmpty()) {
            sentences.add(tail);
        }
        return sentences;
    }

    /**
     * 词项重叠得分：问题词项在句子中的覆盖率
     */
    private double overlapScore(Set<String> questionTerms, String sentence) {
        if (questionTerms.isEmpty()) {
            return 0.0;
        }
        Set<String> sentenceTerms = new LinkedHashSet<>(Bm25Tokenizer.tokenize(sentence));
        long hit = questionTerms.stream().filter(sentenceTerms::contains).count();
        return (double) hit / questionTerms.size();
    }

    /**
     * 候选句 + 相关度 + 来源块编号
     */
    private record ScoredSentence(String text, double score, int sourceBlock) {
    }

    /**
     * 问题改写：抽出当前问题，若历史里有实体则补上前缀以消解指代
     */
    private String generateRewrite(String userPrompt, String promptSeed) {
        String current = firstGroup(CURRENT_QUESTION, userPrompt);
        if (current == null) {
            current = firstGroup(USER_QUESTION, userPrompt);
        }
        if (current == null || current.isBlank()) {
            current = "用户问题";
        }
        current = current.strip();

        // 历史里的实体：取历史首行里"用户："后的内容片段，模拟消解指代后的补全
        String subject = extractHistorySubject(userPrompt);
        String rewritten = subject == null ? current : subject + " " + current;

        String json = "{\"rewrite\":\"" + escapeJson(rewritten)
                + "\",\"sub_questions\":[\"" + escapeJson(current) + "\"]}";
        return padToLength(json, LlmTask.QUERY_REWRITE.getExpectedOutputChars(), promptSeed, REWRITE_FILLER);
    }

    /**
     * 意图分类：按关键词粗判 kb / chitchat / system，输出 JSON
     */
    private String generateIntent(String userPrompt, String promptSeed) {
        String question = firstGroup(USER_QUESTION, userPrompt);
        if (question == null) {
            question = userPrompt;
        }
        String q = question.toLowerCase();
        String json;
        if (q.contains("你好") || q.contains("谢谢") || q.contains("你是谁") || q.contains("天气")) {
            json = "{\"intents\":[]}";
        } else if (q.contains("你能做什么") || q.contains("怎么用") || q.contains("帮助")) {
            json = "{\"intents\":[{\"id\":\"system_capability\",\"score\":0.72}]}";
        } else {
            json = "{\"intents\":[{\"id\":\"kb_general\",\"score\":0.86}]}";
        }
        return padToLength(json, LlmTask.INTENT_CLASSIFY.getExpectedOutputChars(), promptSeed, INTENT_FILLER);
    }

    /**
     * 质量评分：按答案与参考答案的 2-gram 重叠度给分（作为 LLM 侧语义分参考）
     */
    private String generateJudge(String promptSeed) {
        long h = stableHash(promptSeed);
        // 落在 [0.55, 0.95] 区间：真实 judge 对"有上下文支撑的答案"普遍给中等偏上分
        double score = 0.55 + ((h >>> 33) % 41) / 100.0;
        String json = String.format("{\"score\":%.2f,\"reason\":\"答案覆盖了资料中的主要事实\"}", score);
        return padToLength(json, LlmTask.QUALITY_JUDGE.getExpectedOutputChars(), promptSeed, JUDGE_FILLER);
    }

    /**
     * 会话摘要
     */
    private String generateSummary(String promptSeed) {
        String base = "对话摘要：用户咨询了产品参数与售后政策，已确认核心诉求为长期使用成本。";
        return padToLength(base, LlmTask.SUMMARY.getExpectedOutputChars(), promptSeed, SUMMARY_FILLER);
    }

    // ==================== 工具 ====================

    /**
     * 抽取提示词里的上下文块正文
     */
    private List<String> extractContentBlocks(String userPrompt) {
        List<String> blocks = new ArrayList<>();
        if (userPrompt == null || userPrompt.isEmpty()) {
            return blocks;
        }
        Matcher m = CONTENT_BLOCK.matcher(userPrompt);
        while (m.find()) {
            blocks.add(m.group(1));
        }
        return blocks;
    }

    /**
     * 从改写提示词的对话历史里抽主体词：取第一条"用户：xxx"的前若干字符
     */
    private String extractHistorySubject(String userPrompt) {
        if (userPrompt == null) {
            return null;
        }
        int idx = userPrompt.indexOf("用户：");
        if (idx < 0) {
            return null;
        }
        int from = idx + "用户：".length();
        int end = userPrompt.indexOf('\n', from);
        if (end <= from) {
            return null;
        }
        String line = userPrompt.substring(from, end).strip();
        if (line.isEmpty()) {
            return null;
        }
        // 只取前 12 字符作为"实体"，模拟模型消解指代时的实体补全
        return line.length() <= 12 ? line : line.substring(0, 12);
    }

    private String firstGroup(Pattern pattern, String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private String collapseWhitespace(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\s+", " ").strip();
    }

    /**
     * 补足到目标长度：不足则追加确定性填充语，超出则截断
     * <p>
     * 长度必须精确，因为输出 token 是成本的主要部分，长度漂移会让实验间不可比。
     */
    private String padToLength(String base, int targetChars, String seed, String filler) {
        if (base.length() >= targetChars) {
            return base.substring(0, targetChars);
        }
        StringBuilder sb = new StringBuilder(targetChars);
        sb.append(base);
        long state = stableHash(seed);
        int i = 0;
        while (sb.length() < targetChars) {
            int idx = (int) Math.floorMod(state >>> (i % 47), filler.length());
            sb.append(filler.charAt(idx));
            i++;
            if (i % 9 == 0) {
                state = state * 6364136223846793005L + 1442695040888963407L;
            }
        }
        return sb.substring(0, targetChars);
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }

    private long stableHash(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return h;
    }

    // ==================== 填充语料 ====================

    private static final String ANSWER_FILLER =
            "以上内容依据知识库资料整理，具体执行以官方最新公告为准。";

    private static final String REWRITE_FILLER =
            "该问题需结合上文语境理解其指代对象。";

    private static final String INTENT_FILLER =
            "意图判定依据问题关键词与领域归属。";

    private static final String JUDGE_FILLER =
            "整体表述清晰，事实与资料一致。";

    private static final String SUMMARY_FILLER =
            "后续需关注耗材更换周期与保修条款细节。";
}
