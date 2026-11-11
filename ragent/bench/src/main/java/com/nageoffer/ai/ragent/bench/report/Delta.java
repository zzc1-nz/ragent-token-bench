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

import com.nageoffer.ai.ragent.bench.run.ExperimentSummary;

import java.util.ArrayList;
import java.util.List;

/**
 * 策略间差异：报告里"策略 B 比策略 A 节省 Y% token，质量变化 Z%"的数据源
 *
 * @param baseline         基线实验 ID
 * @param candidate        对比实验 ID
 * @param baselineName     基线展示名
 * @param candidateName    对比实验展示名
 * @param tokenSavingPct   token 节省百分比（正数表示省了）
 * @param costSavingPct    成本节省百分比
 * @param qualityDeltaPct  质量分变化百分比（正数表示变好）
 * @param latencyDeltaPct  耗时变化百分比（正数表示变慢）
 * @param verdict          一句话结论
 */
public record Delta(
        String baseline,
        String candidate,
        String baselineName,
        String candidateName,
        double tokenSavingPct,
        double costSavingPct,
        double qualityDeltaPct,
        double latencyDeltaPct,
        String verdict
) {

    /**
     * 计算候选实验相对基线的差异
     *
     * @param baseline  基线实验汇总
     * @param candidate 候选实验汇总
     */
    public static Delta of(ExperimentSummary baseline, ExperimentSummary candidate) {
        double tokenSaving = savingPct(baseline.totalTokens(), candidate.totalTokens());
        double costSaving = savingPct(baseline.totalCostUsd(), candidate.totalCostUsd());
        double qualityDelta = deltaPct(baseline.qualityScore(), candidate.qualityScore());
        double latencyDelta = deltaPct(baseline.avgLatencyMs(), candidate.avgLatencyMs());

        String verdict = String.format(
                "策略 %s 比策略 %s 节省 %.1f%% token，质量变化 %+.1f%%，成本节省 %.1f%%",
                candidate.experimentId(), baseline.experimentId(),
                tokenSaving, qualityDelta, costSaving);

        return new Delta(
                baseline.experimentId(), candidate.experimentId(),
                baseline.config().displayName(), candidate.config().displayName(),
                round1(tokenSaving), round1(costSaving), round1(qualityDelta), round1(latencyDelta),
                verdict);
    }

    /**
     * 一组对比：基线 vs 全部其余实验
     */
    public static List<Delta> againstBaseline(List<ExperimentSummary> summaries, String baselineId) {
        ExperimentSummary baseline = summaries.stream()
                .filter(s -> s.experimentId().equals(baselineId))
                .findFirst()
                .orElse(null);
        if (baseline == null) {
            return List.of();
        }
        List<Delta> deltas = new ArrayList<>();
        for (ExperimentSummary s : summaries) {
            if (!s.experimentId().equals(baselineId)) {
                deltas.add(of(baseline, s));
            }
        }
        return deltas;
    }

    /**
     * 节省百分比：正数表示 candidate 比 baseline 少花了
     */
    private static double savingPct(double baselineValue, double candidateValue) {
        if (baselineValue == 0) {
            return 0.0;
        }
        return (baselineValue - candidateValue) / baselineValue * 100.0;
    }

    /**
     * 变化百分比：正数表示 candidate 比 baseline 更高
     */
    private static double deltaPct(double baselineValue, double candidateValue) {
        if (baselineValue == 0) {
            return 0.0;
        }
        return (candidateValue - baselineValue) / baselineValue * 100.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
