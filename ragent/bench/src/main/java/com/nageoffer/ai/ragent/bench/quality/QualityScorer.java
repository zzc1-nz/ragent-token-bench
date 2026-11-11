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

package com.nageoffer.ai.ragent.bench.quality;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 质量评分器：把「答案好不好」变成 [0,1] 的确定性数值
 * <p>
 * 为什么不用 LLM-as-judge 作为唯一依据：LLM 打分有随机性，同一个答案两次打分可能差 0.1，
 * 而我们要对比的是"策略 B 比策略 A 质量变化 -1.2%"这种小数点后一位的差异 ——
 * 评委抖动会直接淹没信号。所以主评分器是**确定性的字面指标**：
 * <ol>
 *   <li><b>recall@k</b>（权重 0.4）：检索到的 chunk 是否覆盖了标准答案所在文档。
 *       这是 RAG 的命门 —— 文档没捞到，答案必然编。</li>
 *   <li><b>answerCoverage</b>（权重 0.4）：参考答案的关键要点有多少出现在生成答案里。
 *       用字级 2-gram 重叠衡量，能反映"答到点子上了吗"。</li>
 *   <li><b>citationPrecision</b>（权重 0.2）：答案引用到的文档有多少是标准答案文档。
 *       衡量"引对了没"，防止捞对了但引错。</li>
 * </ol>
 * 三项都是纯函数，同输入必同输出，可复现、可回归。
 * <p>
 * <b>统计口径的重要约定</b>：闲聊（chitchat）与系统能力询问（system）类轮次没有标准文档，
 * 它们的 recall / citation 天然无法定义。这类轮次**不应计入质量均分**，
 * 否则会给所有策略统一拉低一个常数分，压缩策略间的区分度（0.4 权重变成死权重）。
 * 因此 {@link #isScorable} 把这类轮次排除在均分之外，由调用方单独统计覆盖率。
 */
public final class QualityScorer {

    /**
     * 检索召回权重
     */
    public static final double WEIGHT_RECALL = 0.4;

    /**
     * 答案覆盖权重
     */
    public static final double WEIGHT_COVERAGE = 0.4;

    /**
     * 引用精度权重
     */
    public static final double WEIGHT_CITATION = 0.2;

    private QualityScorer() {
    }

    /**
     * 该轮次是否参与质量均分
     * <p>
     * 只有需要检索的知识库问题才有"标准文档"，才能算 recall@k 与引用精度。
     * 闲聊/系统询问类轮次没有标准文档，把它们计入均分等于给每个策略加一个固定扣分，
     * 会稀释策略间的真实差异，因此排除。
     *
     * @param intentHint 轮次的意图提示（kb / chitchat / system）
     * @param referenceDocIds 标准文档 ID 列表
     * @return true 表示应计入质量均分
     */
    public static boolean isScorable(String intentHint, List<String> referenceDocIds) {
        if ("kb".equalsIgnoreCase(intentHint)) {
            return true;
        }
        // 非 kb 但带了标准文档：仍可评分（数据集允许这种情况）
        return referenceDocIds != null && !referenceDocIds.isEmpty();
    }

    /**
     * 计算一轮对话的质量分
     *
     * @param referenceDocIds 标准答案文档 ID
     * @param retrievedDocIds 本次实际检索到的文档 ID（按名次）
     * @param referenceAnswer 参考答案
     * @param modelAnswer     模型答案
     * @param citedDocIds     答案里引用的文档 ID
     * @return [0,1] 质量分
     */
    public static double score(List<String> referenceDocIds,
                               List<String> retrievedDocIds,
                               String referenceAnswer,
                               String modelAnswer,
                               List<String> citedDocIds) {
        double recall = recallAtK(referenceDocIds, retrievedDocIds);
        double coverage = answerCoverage(referenceAnswer, modelAnswer);
        double citation = citationPrecision(referenceDocIds, citedDocIds);
        return WEIGHT_RECALL * recall + WEIGHT_COVERAGE * coverage + WEIGHT_CITATION * citation;
    }

    /**
     * recall@k：标准答案文档中被检索到的比例
     * <p>
     * 参考文档为空（闲聊/系统类问题）时返回 1.0 —— 这类问题不需要检索，
     * 不能因为"没召回"而扣分，否则会系统性低估路由策略的收益。
     */
    public static double recallAtK(List<String> referenceDocIds, List<String> retrievedDocIds) {
        if (referenceDocIds == null || referenceDocIds.isEmpty()) {
            return 1.0;
        }
        if (retrievedDocIds == null || retrievedDocIds.isEmpty()) {
            return 0.0;
        }
        Set<String> retrieved = new LinkedHashSet<>(retrievedDocIds);
        long hit = referenceDocIds.stream().filter(retrieved::contains).count();
        return (double) hit / referenceDocIds.size();
    }

    /**
     * 答案覆盖度：参考答案的字符 2-gram 有多少出现在模型答案中
     * <p>
     * 用 2-gram 而不是整句匹配，是为了容忍措辞差异（"保修两年"与"提供两年保修"应算命中）；
     * 用 2-gram 而不是单字，是为了避免"的""了"这类高频字把分数抬到虚高。
     */
    public static double answerCoverage(String referenceAnswer, String modelAnswer) {
        if (referenceAnswer == null || referenceAnswer.isBlank()) {
            return 1.0;
        }
        if (modelAnswer == null || modelAnswer.isBlank()) {
            return 0.0;
        }
        List<String> refGrams = bigrams(normalize(referenceAnswer));
        if (refGrams.isEmpty()) {
            return 0.0;
        }
        Set<String> answerGrams = new LinkedHashSet<>(bigrams(normalize(modelAnswer)));
        long hit = refGrams.stream().filter(answerGrams::contains).count();
        return (double) hit / refGrams.size();
    }

    /**
     * 引用精度：引用文档中属于标准答案文档的比例
     * <p>
     * 没有引用时：若该问题本就无需引用（参考文档为空）给 1.0，否则给 0.0
     * —— "该引用却没引用"和"引用错了"同属缺陷，但不该和"无需引用"混为一谈。
     */
    public static double citationPrecision(List<String> referenceDocIds, List<String> citedDocIds) {
        boolean needsCitation = referenceDocIds != null && !referenceDocIds.isEmpty();
        boolean hasCitation = citedDocIds != null && !citedDocIds.isEmpty();
        if (!needsCitation) {
            return 1.0;
        }
        if (!hasCitation) {
            return 0.0;
        }
        Set<String> reference = new LinkedHashSet<>(referenceDocIds);
        long correct = citedDocIds.stream().distinct().filter(reference::contains).count();
        long distinctCited = citedDocIds.stream().distinct().count();
        return distinctCited == 0 ? 0.0 : (double) correct / distinctCited;
    }

    /**
     * 归一化：去掉空白与标点，只留文字与数字，避免标点差异影响覆盖率
     */
    static String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }

    /**
     * 字符 2-gram 切分
     */
    static List<String> bigrams(String normalized) {
        List<String> grams = new ArrayList<>();
        for (int i = 0; i + 1 < normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }
}
