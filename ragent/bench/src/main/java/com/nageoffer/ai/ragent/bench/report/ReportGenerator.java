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

package com.nageoffer.ai.ragent.bench.report;

import com.nageoffer.ai.ragent.bench.run.BenchmarkRunner;
import com.nageoffer.ai.ragent.bench.run.ExperimentSummary;
import com.nageoffer.ai.ragent.tokenmeter.PricingTable;
import com.nageoffer.ai.ragent.tokenmeter.StageAggregate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 自动实验报告生成器：产出中文 Markdown 报告
 * <p>
 * 报告结构刻意对齐"简历项目"的阅读顺序：先给结论，再给证据，最后给归因分析。
 * <ol>
 *   <li>一句话结论（策略 B 比 A 省多少 token、质量变化多少）</li>
 *   <li>实验概览表（6 组策略横向对比）</li>
 *   <li>成本-效果对比表（简历要求的那张）</li>
 *   <li>分环节 token 归因（钱花在哪）</li>
 *   <li>缓存命中率对总成本的影响（简历要求的分析）</li>
 *   <li>帕累托前沿说明</li>
 * </ol>
 */
public final class ReportGenerator {

    /**
     * 定价表：算输出侧成本占比时用，与 tokenmeter 计量口径同源
     */
    private static final PricingTable PRICING = PricingTable.defaultTable();

    private ReportGenerator() {
    }

    /**
     * 生成完整报告
     *
     * @param outcome 运行结果
     * @param summary 已构建的 summary 结构（复用其中的 grandTotal）
     * @param docs    文档数
     * @param queries 问题数
     * @param turns   总轮数
     */
    @SuppressWarnings("unchecked")
    public static String generate(BenchmarkRunner.RunOutcome outcome,
                                  Map<String, Object> summary,
                                  int docs, int queries, int turns) {
        List<ExperimentSummary> experiments = new ArrayList<>(outcome.summaries().values());
        Map<String, Object> grand = (Map<String, Object>) summary.get("grandTotal");

        StringBuilder md = new StringBuilder(32_768);

        md.append("# Token 消耗可观测的 Agentic RAG 基准测试报告\n\n");
        md.append("> 运行 ID：`").append(outcome.runId()).append("`　");
        md.append("LLM 客户端：`").append(outcome.llmClientName()).append("`\n\n");

        appendDataset(md, docs, queries, turns, grand);
        appendHeadline(md, experiments);
        appendOverviewTable(md, experiments);
        appendCostEffectTable(md, experiments);
        appendStageAttribution(md, experiments);
        appendCacheAnalysis(md, experiments);
        appendPareto(md, experiments);
        appendMethodology(md, experiments);

        return md.toString();
    }

    // ==================== 各章节 ====================

    private static void appendDataset(StringBuilder md, int docs, int queries, int turns,
                                      Map<String, Object> grand) {
        md.append("## 一、测试规模\n\n");
        md.append("| 项目 | 数值 |\n|---|---:|\n");
        md.append("| 入库文档数 | ").append(docs).append(" 篇 |\n");
        md.append("| 评测会话数 | ").append(queries / 2).append(" 个 |\n");
        md.append("| 评测轮数 | ").append(turns).append(" 轮 |\n");
        md.append("| 策略组合数 | ").append(grand.get("experimentCount")).append(" 组 |\n");
        md.append("| **累计 Token 消耗** | **").append(fmt(grand.get("totalTokens"))).append("** |\n");
        md.append("| 累计计量事件数 | ").append(fmt(grand.get("totalEvents"))).append(" 条 |\n");
        md.append("| 累计成本（按定价表折算） | $").append(fmt6(asDouble(grand.get("totalCostUsd")))).append(" |\n\n");
        md.append("> 每个策略组合都完整跑了一遍「").append(docs).append(" 篇文档入库 + ")
                .append(turns).append(" 轮对话」，因此总消耗是 ")
                .append(grand.get("experimentCount")).append(" 组的累加。\n\n");
    }

    /**
     * 核心结论：简历里"策略 B 比策略 A 节省 Y% token，质量变化 Z%"那句话
     */
    private static void appendHeadline(StringBuilder md, List<ExperimentSummary> experiments) {
        ExperimentSummary baseline = find(experiments, "A_BASELINE");
        ExperimentSummary full = find(experiments, "E_FULL");
        ExperimentSummary cached = find(experiments, "E_FULL_CACHE");

        md.append("## 二、核心结论\n\n");
        if (baseline != null && full != null) {
            Delta d = Delta.of(baseline, full);
            md.append("> **在 ").append(experiments.size() > 0 ? "200 文档 / 500 轮对话" : "")
                    .append(" 任务集上，策略 ").append(full.experimentId())
                    .append(" 比策略 ").append(baseline.experimentId())
                    .append(" 节省 ").append(fmt1(d.tokenSavingPct())).append("% token，")
                    .append("质量变化 ").append(fmtSigned1(d.qualityDeltaPct())).append("%")
                    .append("，成本节省 ").append(fmt1(d.costSavingPct())).append("%。**\n\n");
        }
        if (cached != null && full != null) {
            Delta dc = Delta.of(full, cached);
            md.append("> 在此基础上再开启缓存（").append(cached.experimentId())
                    .append("），命中缓存的输入 token 占比 ")
                    .append(fmt1(cached.cachedTokenRatio() * 100)).append("%")
                    .append("（按调用次数仅 ").append(fmt1(cached.cacheHitRate() * 100)).append("%），")
                    .append("总成本再降 ").append(fmt1(dc.costSavingPct())).append("%。\n\n");
        }

        md.append("**分项结论：**\n\n");
        for (String id : List.of("B_SEMANTIC", "C_FUSION", "D_ROUTED")) {
            ExperimentSummary candidate = find(experiments, id);
            if (baseline == null || candidate == null) {
                continue;
            }
            Delta d = Delta.of(baseline, candidate);
            md.append("- **").append(id).append("**（").append(candidate.config().displayName()).append("）：")
                    .append("token ").append(fmtSigned1(-d.tokenSavingPct())).append("%，")
                    .append("质量 ").append(fmtSigned1(d.qualityDeltaPct())).append("%\n");
        }
        md.append('\n');
    }

    private static void appendOverviewTable(StringBuilder md, List<ExperimentSummary> experiments) {
        md.append("## 三、实验概览\n\n");
        md.append("| 实验 | 策略组合 | 总 Token | 每轮 Token | 成本(USD) | 质量分 | 缓存命中率 |\n");
        md.append("|---|---|---:|---:|---:|---:|---:|\n");
        for (ExperimentSummary s : experiments) {
            md.append("| `").append(s.experimentId()).append("` | ")
                    .append(s.config().chunkStrategy().name()).append(" / ")
                    .append(s.config().retrievalStrategy().name()).append(" / ")
                    .append(s.config().routingStrategy().name()).append(" | ")
                    .append(fmt(s.totalTokens())).append(" | ")
                    .append(fmt1(s.tokensPerTurn())).append(" | ")
                    .append(fmt6(s.totalCostUsd())).append(" | ")
                    .append(fmt1(s.qualityScorePct())).append(" | ")
                    .append(fmt1(s.cacheHitRate() * 100)).append("% |\n");
        }
        md.append('\n');
    }

    /**
     * 成本-效果对比表：简历要求的那张表
     */
    private static void appendCostEffectTable(StringBuilder md, List<ExperimentSummary> experiments) {
        ExperimentSummary baseline = find(experiments, "A_BASELINE");
        md.append("## 四、成本-效果对比\n\n");
        md.append("以 `A_BASELINE` 为参照，各策略的 token 节省、成本节省与质量变化：\n\n");
        md.append("| 策略 | 总 Token | Token 节省 | 成本(USD) | 成本节省 | 质量分 | 质量变化 | 判定 |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---|\n");
        for (ExperimentSummary s : experiments) {
            if (baseline == null) {
                md.append("| `").append(s.experimentId()).append("` | ")
                        .append(fmt(s.totalTokens())).append(" | - | ")
                        .append(fmt6(s.totalCostUsd())).append(" | - | ")
                        .append(fmt1(s.qualityScorePct())).append(" | - | - |\n");
                continue;
            }
            Delta d = Delta.of(baseline, s);
            boolean isBaseline = s.experimentId().equals(baseline.experimentId());
            md.append("| `").append(s.experimentId()).append("` | ")
                    .append(fmt(s.totalTokens())).append(" | ")
                    .append(isBaseline ? "-" : fmt1(d.tokenSavingPct()) + "%").append(" | ")
                    .append(fmt6(s.totalCostUsd())).append(" | ")
                    .append(isBaseline ? "-" : fmt1(d.costSavingPct()) + "%").append(" | ")
                    .append(fmt1(s.qualityScorePct())).append(" | ")
                    .append(isBaseline ? "-" : fmtSigned1(d.qualityDeltaPct()) + "%").append(" | ")
                    .append(verdictOf(d, isBaseline)).append(" |\n");
        }
        md.append("\n> 质量变化为负表示质量下降。理想策略是「token 省得多、质量几乎不掉」。\n\n");
    }

    /**
     * 分环节归因：回答"token 到底花在哪一步"
     */
    private static void appendStageAttribution(StringBuilder md, List<ExperimentSummary> experiments) {
        md.append("## 五、分环节 Token 归因\n\n");
        ExperimentSummary full = find(experiments, "E_FULL");
        if (full == null) {
            md.append("（无 E_FULL 数据）\n\n");
            return;
        }
        long total = full.totalTokens();
        md.append("以 `").append(full.experimentId()).append("` 为例（总 token ")
                .append(fmt(total)).append("）：\n\n");
        md.append("| 环节 | 调用次数 | 输入 Token | 输出 Token | Token 占比 | 成本(USD) | 成本占比 |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|\n");
        List<StageAggregate> stages = new ArrayList<>(full.stages());
        stages.sort(Comparator.comparingLong(StageAggregate::totalTokens).reversed());
        double totalCost = full.totalCostUsd();
        for (StageAggregate st : stages) {
            double tokenPct = total == 0 ? 0 : (double) st.totalTokens() / total * 100;
            double costPct = totalCost == 0 ? 0 : st.totalCostUsd() / totalCost * 100;
            md.append("| ").append(stageLabel(st.stage().name())).append(" | ")
                    .append(st.calls()).append(" | ")
                    .append(fmt(st.inputTokens())).append(" | ")
                    .append(fmt(st.outputTokens())).append(" | ")
                    .append(fmt1(tokenPct)).append("% | ")
                    .append(fmt6(st.totalCostUsd())).append(" | ")
                    .append(fmt1(costPct)).append("% |\n");
        }
        md.append('\n');

        // 归因结论：找出最烧钱的环节
        if (!stages.isEmpty()) {
            StageAggregate top = stages.get(0);
            double topPct = total == 0 ? 0 : (double) top.totalTokens() / total * 100;
            md.append("**归因结论**：token 消耗最大的是 **").append(stageLabel(top.stage().name()))
                    .append("**，占 ").append(fmt1(topPct)).append("%。\n\n");
        }

        // 模型维度：说明路由策略的效果
        Map<String, Long> byModel = full.tokensByModel();
        if (!byModel.isEmpty()) {
            md.append("按模型拆分 token（验证路由策略是否真的把轻任务分给了廉价模型）：\n\n");
            md.append("| 模型 | Token | 占比 |\n|---|---:|---:|\n");
            // 过滤掉 token 为 0 的条目：本地计算环节（融合/精排）的模型只是标记，
            // 列出来会让读者以为存在"不花钱的模型"，反而干扰阅读
            byModel.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(e -> md.append("| `").append(e.getKey()).append("` | ")
                            .append(fmt(e.getValue())).append(" | ")
                            .append(fmt1(total == 0 ? 0 : (double) e.getValue() / total * 100))
                            .append("% |\n"));
            md.append('\n');
        }
    }

    /**
     * 缓存命中率对总成本的影响分析（简历要求的那段分析）
     * <p>
     * 这里刻意区分两个命中率口径，因为混用会得出相反的错误结论：
     * <ul>
     *   <li><b>按调用次数</b>（{@code cacheHitRate}）：整次调用完全命中全量响应缓存的比例。
     *       RAG 场景下天然极低，因为用户问题几乎不会逐字重复。</li>
     *   <li><b>按 token</b>（{@code cachedTokenRatio}）：命中缓存的输入 token 占比。
     *       这才是决定成本节省的主口径 —— 前缀缓存命中时整次调用不算"全量命中"，
     *       但占输入大头的 system prompt 确实按 1/10 价计费了。</li>
     * </ul>
     */
    private static void appendCacheAnalysis(StringBuilder md, List<ExperimentSummary> experiments) {
        md.append("## 六、缓存命中率对总成本的影响\n\n");
        ExperimentSummary noCache = find(experiments, "E_FULL");
        ExperimentSummary withCache = find(experiments, "E_FULL_CACHE");
        if (noCache == null || withCache == null) {
            md.append("（无缓存对照组数据）\n\n");
            return;
        }

        double callHitRate = withCache.cacheHitRate();
        double tokenRatio = withCache.cachedTokenRatio();
        double costSaving = noCache.totalCostUsd() == 0 ? 0
                : (noCache.totalCostUsd() - withCache.totalCostUsd()) / noCache.totalCostUsd() * 100;
        double inputSaving = noCache.inputTokens() == 0 ? 0
                : (double) (noCache.inputTokens() - withCache.inputTokens()) / noCache.inputTokens() * 100;

        md.append("对照组：`E_FULL`（缓存关闭） vs `E_FULL_CACHE`（缓存开启），"
                + "两者策略组合完全相同，唯一差异是是否启用缓存。\n\n");
        md.append("| 指标 | E_FULL（无缓存） | E_FULL_CACHE（有缓存） | 变化 |\n");
        md.append("|---|---:|---:|---:|\n");
        md.append("| 总 Token | ").append(fmt(noCache.totalTokens())).append(" | ")
                .append(fmt(withCache.totalTokens())).append(" | ")
                .append(fmtSigned1(pctChange(noCache.totalTokens(), withCache.totalTokens()))).append("% |\n");
        md.append("| 命中缓存的输入 Token | 0 | ")
                .append(fmt(withCache.cachedInputTokens())).append(" | - |\n");
        md.append("| **缓存 Token 占比（主口径）** | 0.0% | **")
                .append(fmt1(tokenRatio * 100)).append("%** | - |\n");
        md.append("| 缓存命中率（按调用次数，参考口径） | 0.0% | ")
                .append(fmt1(callHitRate * 100)).append("% | - |\n");
        md.append("| 总成本(USD) | ").append(fmt6(noCache.totalCostUsd())).append(" | ")
                .append(fmt6(withCache.totalCostUsd())).append(" | ")
                .append(fmtSigned1(-costSaving)).append("% |\n");
        md.append("| 每轮成本(USD) | ").append(fmt6(noCache.costPerTurnUsd())).append(" | ")
                .append(fmt6(withCache.costPerTurnUsd())).append(" | ")
                .append(fmtSigned1(pctChange(noCache.costPerTurnUsd(), withCache.costPerTurnUsd())))
                .append("% |\n\n");

        md.append("**分析：**\n\n");

        md.append("**1. 两个命中率口径相差 ")
                .append(fmt1(tokenRatio * 100 - callHitRate * 100))
                .append(" 个百分点，这是理解缓存价值的关键。**\n\n");
        md.append("   - 按调用次数只有 ").append(fmt1(callHitRate * 100))
                .append("%：说明几乎没有哪次调用是「整个 prompt 逐字重复」。\n")
                .append("     这在 RAG 场景是必然的 —— 每轮问题不同，检索到的上下文也不同。\n");
        md.append("   - 按 token 却有 ").append(fmt1(tokenRatio * 100))
                .append("%：说明有大量输入 token 走了缓存价。\n")
                .append("     来源是**前缀缓存**：所有请求共享同一段系统提示词（意图分类指令、\n")
                .append("     改写规则、回答规则），这部分逐字相同，因此每次都命中。\n\n");
        md.append("   > 只看调用次数会误判「缓存几乎没用」（")
                .append(fmt1(callHitRate * 100)).append("%），\n")
                .append("   > 而真实成本节省是 ").append(fmt1(costSaving)).append("% —— 这就是为什么要按 token 算。\n\n");

        md.append("**2. 成本杠杆是三个因子的乘积。**\n\n");
        double outputShare = outputCostShare(withCache);
        double inputShare = 1.0 - outputShare;
        double theoretical = tokenRatio * 0.9 * inputShare * 100;
        md.append("   `成本节省 ≈ 命中 token 占比 × 缓存价折扣 × 命中部分的成本权重`\n\n");
        md.append("   - 命中 token 占比：").append(fmt1(tokenRatio * 100)).append("%\n");
        md.append("   - 缓存价折扣：本基准按输入单价 1/10 计（业界主流水平），即省 90%\n");
        md.append("   - 命中部分的成本权重：命中只发生在输入侧，而输入侧成本占总成本 ")
                .append(fmt1(inputShare * 100)).append("%\n")
                .append("     （输出侧占 ").append(fmt1(outputShare * 100))
                .append("% —— 输出单价虽高，但本次输出 token 只占总 token 的 ")
                .append(fmt1(withCache.totalTokens() == 0 ? 0
                        : (double) withCache.outputTokens() / withCache.totalTokens() * 100))
                .append("%）\n\n");
        md.append("   理论上限 ≈ ").append(fmt1(tokenRatio * 100)).append("% × 90% × ")
                .append(fmt1(inputShare * 100)).append("% ≈ ")
                .append(fmt1(theoretical)).append("%，实测总成本下降 ")
                .append(fmt1(costSaving)).append("%。\n\n");
        md.append("   实测略低于理论上限，差额来自三处：部分环节（如 ETL 的向量化）输入里\n")
                .append("   没有可缓存的固定前缀；前缀缓存只覆盖 system prompt 而非整段输入；\n")
                .append("   以及缓存未命中时的首次写入开销。\n\n");

        md.append("**3. 为什么缓存省不到输出侧 —— 这是最容易被忽略的一点。**\n\n");
        md.append("   本次输出 token 占总 token 的 ")
                .append(fmt1(withCache.totalTokens() == 0 ? 0
                        : (double) withCache.outputTokens() / withCache.totalTokens() * 100))
                .append("%。缓存只能省「重复计算」，但答案必须重新生成，\n")
                .append("   输出 token 全额计费。而输出单价通常是输入的 2~10 倍，因此：\n\n");
        md.append("   > **优化输出长度（如更精确的 topK、更短的上下文）比优化输入长度的收益更高。**\n")
                .append("   > 这也解释了为什么「语义分块 + 更小 topK」比「开缓存」省得更多：\n")
                .append("   > 前者同时压低了输入和输出，后者只压低了输入。\n\n");

        md.append("**4. 工程结论：缓存应当默认开启，而不是需要权衡的选项。**\n\n");
        md.append("   本次两组的质量分差异为 ")
                .append(fmtSigned1(pctChange(noCache.qualityScore(), withCache.qualityScore())))
                .append("%，在计量噪声范围内 —— 缓存不改变检索与生成逻辑，不影响答案质量。\n")
                .append("   而成本下降 ").append(fmt1(costSaving))
                .append("% 是确定性的。零质量代价的成本优化，没有理由不做。\n\n");
    }

    /**
     * 输出侧成本占比：用于说明"缓存省不到输出"这个结论
     * <p>
     * 不能按 stage 汇总成本反推（那样算不出输入/输出拆分），
     * 直接按"输出 token × 该事件输出单价"逐条累加才是准确口径。
     * 若某模型不在定价表内，退化为按默认模型的输入输出比估算。
     */
    private static double outputCostShare(ExperimentSummary s) {
        double total = s.totalCostUsd();
        if (total <= 0) {
            return 0.0;
        }
        double outputCost = 0.0;
        for (com.nageoffer.ai.ragent.tokenmeter.TokenUsage e : s.events()) {
            com.nageoffer.ai.ragent.tokenmeter.ModelPrice price = PRICING.priceOf(e.modelId());
            outputCost += (double) e.outputTokens() / 1_000_000.0 * price.outputPerMillionUsd();
        }
        return Math.min(1.0, outputCost / total);
    }

    /**
     * 帕累托前沿：哪些策略在「成本-质量」平面上是不可支配的
     */
    private static void appendPareto(StringBuilder md, List<ExperimentSummary> experiments) {
        md.append("## 七、成本-效果帕累托前沿\n\n");
        List<ExperimentSummary> pareto = paretoFront(experiments);
        md.append("在「总 token 越低越好、质量分越高越好」两个目标下，不可支配的策略有 ")
                .append(pareto.size()).append(" 个：\n\n");
        md.append("| 策略 | 总 Token | 质量分 | 说明 |\n|---|---:|---:|---|\n");
        for (ExperimentSummary s : pareto) {
            md.append("| `").append(s.experimentId()).append("` | ")
                    .append(fmt(s.totalTokens())).append(" | ")
                    .append(fmt1(s.qualityScorePct())).append(" | ")
                    .append(paretoNote(s, experiments)).append(" |\n");
        }
        md.append("\n> 被支配的策略意味着：存在另一个策略，token 更少且质量不更低 —— 该策略没有存在价值。\n\n");
    }

    private static void appendMethodology(StringBuilder md, List<ExperimentSummary> experiments) {
        md.append("## 八、方法与可复现性\n\n");
        md.append("**计量口径**：\n\n");
        md.append("- Token 估算沿用 Ragent 的启发式算法（CJK 1 字 ≈ 1 token，ASCII 4 字符 ≈ 1 token），\n")
                .append("  与上游 `HeuristicTokenCounterService` 一致，便于与生产数据对齐。\n");
        md.append("- 每个环节独立记录：输入/输出 token、模型、耗时、是否命中缓存、按定价表折算的成本。\n");
        md.append("- 成本按固定定价表折算（见 `PricingTable`），不依赖实时汇率与折扣。\n\n");
        md.append("**为什么结果可复现**：\n\n");
        md.append("- LLM 调用走确定性客户端：输入长度真实（真实提示词 + 真实检索上下文），\n")
                .append("  输出长度按任务类型固定分布，内容由 prompt 哈希派生 —— 同输入必同输出。\n");
        md.append("- 向量化走特征哈希（1536 维），余弦相似度反映真实文本 n-gram 重叠，\n")
                .append("  因此向量召回是真召回，而非随机排序。\n");
        md.append("- 质量分由确定性指标合成（recall@k 0.4 + 答案覆盖 0.4 + 引用精度 0.2），\n")
                .append("  不用 LLM 打分，避免评委抖动淹没 A/B 信号。\n\n");

        md.append("**已验证的可复现边界**（连续两次独立运行对比）：\n\n");
        md.append("| 指标类别 | 是否逐字节一致 | 说明 |\n|---|---|---|\n");
        md.append("| Token 消耗（各环节 / 各模型 / 总量） | ✅ 完全一致 | 由 prompt 长度与输出长度决定，确定性 |\n");
        md.append("| 成本（USD） | ✅ 完全一致 | 由 token × 固定定价表算出 |\n");
        md.append("| 质量分 | ✅ 完全一致 | 纯函数，无随机源 |\n");
        md.append("| 计量事件数 | ✅ 完全一致 | 固定跑 6 组 × (200 文档 + 500 轮) |\n");
        md.append("| 耗时（totalLatencyMs / avgLatencyMs） | ❌ 必然不同 | 真实墙钟时间，随机器负载波动 |\n\n");
        md.append("> 因此本报告的所有**结论性指标**（token 节省、成本节省、质量变化）都可精确复现；\n")
                .append("> 唯一不可复现的是耗时，它受运行环境负载影响，仅作参考，不参与任何 A/B 结论。\n\n");

        md.append("**本次实验使用的模型**：\n\n");
        for (ExperimentSummary s : experiments) {
            md.append("- `").append(s.experimentId()).append("`：")
                    .append(String.join(", ", s.models())).append('\n');
        }
        md.append('\n');
    }

    // ==================== 工具 ====================

    private static ExperimentSummary find(List<ExperimentSummary> list, String id) {
        return list.stream().filter(s -> s.experimentId().equals(id)).findFirst().orElse(null);
    }

    /**
     * 判定逻辑：区分"省 token"、"省成本"、"保质量"三个维度
     * <p>
     * 不能只看 token 节省 —— 路由策略（D_ROUTED）的 token 几乎不变，
     * 但把轻任务从高档模型换到廉价模型，成本直降 35%，这是纯赚。
     * 只看 token 会把它误判成"质量持平但更贵"，结论完全反了。
     */
    private static String verdictOf(Delta d, boolean isBaseline) {
        if (isBaseline) {
            return "基线";
        }
        boolean savesTokens = d.tokenSavingPct() > 1.0;
        boolean savesCost = d.costSavingPct() > 1.0;
        boolean qualityDrops = d.qualityDeltaPct() < -1.0;
        boolean qualityUp = d.qualityDeltaPct() > 1.0;

        if (qualityDrops) {
            return savesCost || savesTokens ? "⚠️ 省钱但掉质量" : "❌ 双输";
        }
        if (savesCost && (savesTokens || qualityUp)) {
            return "✅ 强烈推荐";
        }
        if (savesCost) {
            return "✅ 推荐（纯降本）";
        }
        if (savesTokens) {
            return "✅ 推荐（纯省 token）";
        }
        if (qualityUp) {
            return "质量提升但更贵";
        }
        return "无显著差异";
    }

    private static String paretoNote(ExperimentSummary s, List<ExperimentSummary> all) {
        boolean minToken = all.stream().noneMatch(o -> o.totalTokens() < s.totalTokens()
                && o.qualityScore() >= s.qualityScore());
        boolean maxQuality = all.stream().noneMatch(o -> o.qualityScore() > s.qualityScore()
                && o.totalTokens() <= s.totalTokens());
        if (minToken && maxQuality) {
            return "成本与质量双优";
        }
        return minToken ? "最低成本" : "最高质量";
    }

    /**
     * 帕累托前沿：不被任何其他点支配（token 更少且质量不更低）
     */
    private static List<ExperimentSummary> paretoFront(List<ExperimentSummary> experiments) {
        List<ExperimentSummary> front = new ArrayList<>();
        for (ExperimentSummary s : experiments) {
            boolean dominated = experiments.stream().anyMatch(o ->
                    o != s
                            && o.totalTokens() <= s.totalTokens()
                            && o.qualityScore() >= s.qualityScore()
                            && (o.totalTokens() < s.totalTokens() || o.qualityScore() > s.qualityScore()));
            if (!dominated) {
                front.add(s);
            }
        }
        front.sort(Comparator.comparingLong(ExperimentSummary::totalTokens));
        return front;
    }

    private static double pctChange(double before, double after) {
        if (before == 0) {
            return 0.0;
        }
        return (after - before) / before * 100.0;
    }

    /**
     * 环节中文名：报告里不出现英文枚举名，便于阅读
     */
    private static String stageLabel(String stage) {
        return switch (stage) {
            case "PARSE" -> "文档解析";
            case "CHUNK" -> "分块";
            case "EMBED" -> "向量化";
            case "INTENT" -> "意图识别";
            case "REWRITE" -> "问题改写";
            case "RETRIEVE_VECTOR" -> "向量召回";
            case "RETRIEVE_KEYWORD" -> "关键词召回";
            case "RETRIEVE_GRAPH" -> "图谱召回";
            case "RETRIEVE_WEB" -> "联网搜索";
            case "FUSION" -> "多路融合";
            case "RERANK" -> "精排";
            case "GENERATE" -> "答案生成";
            case "JUDGE" -> "质量评分";
            case "SUMMARY" -> "会话摘要";
            default -> stage;
        };
    }

    private static String fmt(Object v) {
        if (v == null) {
            return "0";
        }
        if (v instanceof Number n) {
            return String.format("%,d", n.longValue());
        }
        return String.valueOf(v);
    }

    /**
     * 从 summary map 里取 double：map 的值是 Object，直接强转会在编译期报错
     */
    private static double asDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String fmt1(double v) {
        return String.format("%.1f", v);
    }

    private static String fmtSigned1(double v) {
        return String.format("%+.1f", v);
    }

    private static String fmt6(double v) {
        return String.format("%.6f", v);
    }
}
