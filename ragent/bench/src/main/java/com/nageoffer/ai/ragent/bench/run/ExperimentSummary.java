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

package com.nageoffer.ai.ragent.bench.run;

import com.nageoffer.ai.ragent.bench.config.ExperimentConfig;
import com.nageoffer.ai.ragent.tokenmeter.MeterStage;
import com.nageoffer.ai.ragent.tokenmeter.StageAggregate;
import com.nageoffer.ai.ragent.tokenmeter.TokenUsage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单个实验的汇总结果：把散落的 {@link TokenUsage} 事件折叠成可展示的指标
 * <p>
 * 这里刻意不用 tokenmeter 的 {@code MeterAggregator}：那边是"按 runId 聚合"的通用实现，
 * 而报告需要的是"按 experimentId 聚合 + 质量分 + 每轮均值 + 时间轴"，
 * 且要保留 stage 维度的成本拆分（哪个环节最烧钱是报告的核心结论之一）。
 */
public final class ExperimentSummary {

    private final ExperimentConfig config;
    private final List<TokenUsage> events;
    private final List<Double> qualities;
    private final int turns;
    private final int chunkCount;

    public ExperimentSummary(ExperimentConfig config, List<TokenUsage> events,
                             List<Double> qualities, int turns, int chunkCount) {
        this.config = config;
        this.events = List.copyOf(events);
        this.qualities = List.copyOf(qualities);
        this.turns = turns;
        this.chunkCount = chunkCount;
    }

    public ExperimentConfig config() {
        return config;
    }

    public String experimentId() {
        return config.experimentId();
    }

    public int turns() {
        return turns;
    }

    public int chunkCount() {
        return chunkCount;
    }

    /**
     * 总输入 token
     */
    public long inputTokens() {
        return events.stream().mapToLong(TokenUsage::inputTokens).sum();
    }

    /**
     * 总输出 token
     */
    public long outputTokens() {
        return events.stream().mapToLong(TokenUsage::outputTokens).sum();
    }

    /**
     * 总 token = 输入 + 输出
     */
    public long totalTokens() {
        return inputTokens() + outputTokens();
    }

    /**
     * 命中缓存的输入 token
     */
    public long cachedInputTokens() {
        return events.stream().mapToLong(TokenUsage::cachedInputTokens).sum();
    }

    /**
     * 缓存命中率（按调用次数）
     * <p>
     * 这个口径统计的是"整次调用完全命中全量响应缓存"的比例。在真实 RAG 系统里它天然很低
     * （用户问题几乎不会逐字重复），因此**不适合单独用来衡量缓存价值**。
     * 衡量缓存收益应看 {@link #cachedTokenRatio()}（命中 token 占比）——
     * 前缀缓存命中时，整次调用不算"全量命中"，但 system prompt 那部分输入确实省了钱。
     */
    public double cacheHitRate() {
        if (events.isEmpty()) {
            return 0.0;
        }
        long hits = events.stream().filter(TokenUsage::cacheHit).count();
        return (double) hits / events.size();
    }

    /**
     * 缓存 token 占比：命中缓存的输入 token / 总输入 token
     * <p>
     * 这是衡量缓存价值的**主口径**：它直接决定成本节省幅度。
     * 报告里两个口径都会给出，并解释差异来源（前缀缓存 vs 全量缓存）。
     */
    public double cachedTokenRatio() {
        long in = inputTokens();
        return in == 0 ? 0.0 : (double) cachedInputTokens() / in;
    }

    /**
     * 总成本（USD）
     */
    public double totalCostUsd() {
        return events.stream().mapToDouble(TokenUsage::costUsd).sum();
    }

    /**
     * 总耗时（毫秒）
     */
    public long totalLatencyMs() {
        return events.stream().mapToLong(TokenUsage::latencyMs).sum();
    }

    /**
     * 平均每轮耗时
     */
    public double avgLatencyMs() {
        return turns == 0 ? 0.0 : (double) totalLatencyMs() / turns;
    }

    /**
     * 平均质量分 [0,1]
     */
    public double qualityScore() {
        return qualities.isEmpty() ? 0.0 : qualities.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    /**
     * 质量分百分制
     */
    public double qualityScorePct() {
        return qualityScore() * 100.0;
    }

    /**
     * 每轮平均 token
     */
    public double tokensPerTurn() {
        return turns == 0 ? 0.0 : (double) totalTokens() / turns;
    }

    /**
     * 每轮平均成本（USD）
     */
    public double costPerTurnUsd() {
        return turns == 0 ? 0.0 : totalCostUsd() / turns;
    }

    /**
     * 每轮平均输入 token：回答"是上下文太长还是回答太长"
     */
    public double inputTokensPerTurn() {
        return turns == 0 ? 0.0 : (double) inputTokens() / turns;
    }

    /**
     * 每轮平均输出 token
     */
    public double outputTokensPerTurn() {
        return turns == 0 ? 0.0 : (double) outputTokens() / turns;
    }

    /**
     * 分环节聚合：报告里"各环节 token 占比"与"成本归因"的数据源
     */
    public List<StageAggregate> stages() {
        Map<MeterStage, StageAccumulator> byStage = new LinkedHashMap<>();
        for (TokenUsage e : events) {
            byStage.computeIfAbsent(e.stage(), k -> new StageAccumulator()).add(e);
        }
        List<StageAggregate> out = new ArrayList<>(byStage.size());
        byStage.forEach((stage, acc) -> out.add(acc.toAggregate(stage)));
        out.sort((a, b) -> Long.compare(b.totalTokens(), a.totalTokens()));
        return out;
    }

    /**
     * 按模型聚合 token：回答"廉价模型到底分担了多少"
     */
    public Map<String, Long> tokensByModel() {
        Map<String, Long> byModel = new LinkedHashMap<>();
        for (TokenUsage e : events) {
            byModel.merge(e.modelId() == null ? "unknown" : e.modelId(),
                    (long) e.totalTokens(), Long::sum);
        }
        return byModel;
    }

    /**
     * 按模型聚合成本：用于说明"路由到廉价模型省了多少钱"
     */
    public Map<String, Double> costByModel() {
        Map<String, Double> byModel = new LinkedHashMap<>();
        for (TokenUsage e : events) {
            byModel.merge(e.modelId() == null ? "unknown" : e.modelId(),
                    e.costUsd(), Double::sum);
        }
        return byModel;
    }

    /**
     * 时间轴：按事件顺序累加 token，供仪表盘画消耗曲线
     * <p>
     * 返回的是"每 N 个事件取一个采样点"的压缩序列 —— 全量事件可能上万条，
     * 直接画会糊成一团；采样后曲线形状不变，但数据量可控。
     *
     * @param maxPoints 最多采样点数
     */
    public List<TimelinePoint> timeline(int maxPoints) {
        int total = events.size();
        if (total == 0) {
            return List.of();
        }
        int step = Math.max(1, total / Math.max(1, maxPoints));
        List<TimelinePoint> points = new ArrayList<>();
        long cumulative = 0;
        int i = 0;
        for (TokenUsage e : events) {
            cumulative += e.totalTokens();
            if (i % step == 0 || i == total - 1) {
                points.add(new TimelinePoint(i, cumulative, e.stage().name(), e.totalTokens()));
            }
            i++;
        }
        return points;
    }

    public List<TokenUsage> events() {
        return events;
    }

    /**
     * 时间轴采样点
     *
     * @param t                事件序号（0-based）
     * @param cumulativeTokens 累计 token
     * @param stage            该点所属环节
     * @param tokens           该事件本身的 token
     */
    public record TimelinePoint(int t, long cumulativeTokens, String stage, int tokens) {
    }

    /**
     * 分环节累加器
     */
    private static final class StageAccumulator {
        private int calls;
        private long input;
        private long output;
        private long cached;
        private long hits;
        private double cost;
        private long latency;

        void add(TokenUsage e) {
            calls++;
            input += e.inputTokens();
            output += e.outputTokens();
            cached += e.cachedInputTokens();
            if (e.cacheHit()) {
                hits++;
            }
            cost += e.costUsd();
            latency += e.latencyMs();
        }

        StageAggregate toAggregate(MeterStage stage) {
            return new StageAggregate(stage, calls, input, output, cached, hits, cost, latency);
        }
    }

    /**
     * 该实验用到的模型集合（报告里列出，避免读者以为只有一个大模型）
     */
    public Set<String> models() {
        Set<String> models = new LinkedHashSet<>();
        for (TokenUsage e : events) {
            models.add(e.modelId());
        }
        return models;
    }
}
