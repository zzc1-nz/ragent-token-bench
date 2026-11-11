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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nageoffer.ai.ragent.bench.run.BenchmarkRunner;
import com.nageoffer.ai.ragent.bench.run.ExperimentSummary;
import com.nageoffer.ai.ragent.tokenmeter.StageAggregate;
import com.nageoffer.ai.ragent.tokenmeter.TokenUsage;
import com.nageoffer.ai.ragent.tokenmeter.TokenUsageJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结果写出器：产出 events.jsonl / summary.json / report.md 三件套
 * <p>
 * 三个文件的消费方不同，因此结构也不同：
 * <ul>
 *   <li>{@code events.jsonl}：逐条原始事件，给做深度分析（如按会话回溯）用</li>
 *   <li>{@code summary.json}：给前端仪表盘直接 fetch，结构按契约 6.4</li>
 *   <li>{@code report.md}：给人读的实验报告，含结论段落</li>
 * </ul>
 */
public final class ResultWriter {

    /**
     * 时间轴采样点数：仪表盘画曲线够用，又不会让 JSON 过大
     */
    private static final int TIMELINE_POINTS = 200;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private ResultWriter() {
    }

    /**
     * 写出全部结果
     *
     * @param outcome    运行结果
     * @param outputDir  输出目录（如 data/results/RUN_20261110_001）
     * @param docs       文档数（写进报告的元信息）
     * @param queries    问题数
     * @param turns      总轮数
     */
    public static void writeAll(BenchmarkRunner.RunOutcome outcome,
                                Path outputDir,
                                int docs,
                                int queries,
                                int turns) {
        try {
            Files.createDirectories(outputDir);
            writeEvents(outcome.events(), outputDir.resolve("events.jsonl"));
            Map<String, Object> summary = buildSummary(outcome, docs, queries, turns);
            writeJson(summary, outputDir.resolve("summary.json"));
            String report = ReportGenerator.generate(outcome, summary, docs, queries, turns);
            Files.writeString(outputDir.resolve("report.md"), report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写出结果失败：" + outputDir.toAbsolutePath(), e);
        }
    }

    /**
     * 写 events.jsonl：每行一个 TokenUsage，字段与 tokenmeter 的 JSON 编码一致
     */
    private static void writeEvents(List<TokenUsage> events, Path file) throws IOException {
        StringBuilder sb = new StringBuilder(events.size() * 256);
        for (TokenUsage event : events) {
            sb.append(TokenUsageJson.toJson(event)).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void writeJson(Object value, Path file) throws IOException {
        Files.writeString(file, GSON.toJson(value), StandardCharsets.UTF_8);
    }

    /**
     * 组装 summary.json：结构严格按契约 6.4
     */
    private static Map<String, Object> buildSummary(BenchmarkRunner.RunOutcome outcome,
                                                    int docs, int queries, int turns) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("runId", outcome.runId());
        root.put("generatedAt", OffsetDateTime.now().format(TS));
        root.put("llmClient", outcome.llmClientName());

        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("docs", docs);
        dataset.put("queries", queries);
        dataset.put("conversations", queries / 2);
        dataset.put("turns", turns);
        root.put("dataset", dataset);

        List<Map<String, Object>> experiments = new ArrayList<>();
        List<ExperimentSummary> ordered = new ArrayList<>(outcome.summaries().values());
        for (ExperimentSummary s : ordered) {
            experiments.add(buildExperiment(s));
        }
        root.put("experiments", experiments);

        List<Map<String, Object>> deltas = new ArrayList<>();
        for (Delta d : Delta.againstBaseline(ordered, "A_BASELINE")) {
            deltas.add(buildDelta(d));
        }
        root.put("deltas", deltas);

        // 全局口径：所有实验加起来的总量，简历上"累计百万级 token"用这个数
        long totalTokens = ordered.stream().mapToLong(ExperimentSummary::totalTokens).sum();
        double totalCost = ordered.stream().mapToDouble(ExperimentSummary::totalCostUsd).sum();
        long totalEvents = outcome.events().size();
        Map<String, Object> grand = new LinkedHashMap<>();
        grand.put("totalTokens", totalTokens);
        grand.put("totalInputTokens", ordered.stream().mapToLong(ExperimentSummary::inputTokens).sum());
        grand.put("totalOutputTokens", ordered.stream().mapToLong(ExperimentSummary::outputTokens).sum());
        grand.put("totalCostUsd", round4(totalCost));
        grand.put("totalEvents", totalEvents);
        grand.put("totalChunks", ordered.isEmpty() ? 0 : ordered.get(0).chunkCount());
        grand.put("experimentCount", ordered.size());
        root.put("grandTotal", grand);

        return root;
    }

    private static Map<String, Object> buildExperiment(ExperimentSummary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("experimentId", s.experimentId());
        m.put("displayName", s.config().displayName());
        m.put("chunkStrategy", s.config().chunkStrategy().name());
        m.put("retrievalStrategy", s.config().retrievalStrategy().name());
        m.put("routingStrategy", s.config().routingStrategy().name());
        m.put("cacheStrategy", s.config().cacheStrategy().name());

        m.put("inputTokens", s.inputTokens());
        m.put("outputTokens", s.outputTokens());
        m.put("totalTokens", s.totalTokens());
        m.put("cachedInputTokens", s.cachedInputTokens());
        m.put("cacheHitRate", round4(s.cacheHitRate()));
        m.put("cachedTokenRatio", round4(s.cachedTokenRatio()));

        m.put("totalCostUsd", round4(s.totalCostUsd()));
        m.put("totalLatencyMs", s.totalLatencyMs());
        m.put("avgLatencyMs", round1(s.avgLatencyMs()));
        m.put("turns", s.turns());
        m.put("chunkCount", s.chunkCount());

        m.put("qualityScore", round4(s.qualityScore()));
        m.put("qualityScorePct", round1(s.qualityScorePct()));
        m.put("tokensPerTurn", round1(s.tokensPerTurn()));
        m.put("costPerTurnUsd", round6(s.costPerTurnUsd()));
        m.put("inputTokensPerTurn", round1(s.inputTokensPerTurn()));
        m.put("outputTokensPerTurn", round1(s.outputTokensPerTurn()));

        List<Map<String, Object>> stages = new ArrayList<>();
        for (StageAggregate st : s.stages()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("stage", st.stage().name());
            sm.put("calls", st.calls());
            sm.put("inputTokens", st.inputTokens());
            sm.put("outputTokens", st.outputTokens());
            sm.put("cachedInputTokens", st.cachedInputTokens());
            sm.put("cacheHits", st.cacheHits());
            sm.put("totalCostUsd", round6(st.totalCostUsd()));
            sm.put("totalLatencyMs", st.totalLatencyMs());
            stages.add(sm);
        }
        m.put("stages", stages);

        m.put("tokensByModel", s.tokensByModel());

        List<Map<String, Object>> timeline = new ArrayList<>();
        for (ExperimentSummary.TimelinePoint p : s.timeline(TIMELINE_POINTS)) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("t", p.t());
            pm.put("cumulativeTokens", p.cumulativeTokens());
            pm.put("stage", p.stage());
            pm.put("tokens", p.tokens());
            timeline.add(pm);
        }
        m.put("timeline", timeline);
        return m;
    }

    private static Map<String, Object> buildDelta(Delta d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baseline", d.baseline());
        m.put("candidate", d.candidate());
        m.put("baselineName", d.baselineName());
        m.put("candidateName", d.candidateName());
        m.put("tokenSavingPct", d.tokenSavingPct());
        m.put("costSavingPct", d.costSavingPct());
        m.put("qualityDeltaPct", d.qualityDeltaPct());
        m.put("latencyDeltaPct", d.latencyDeltaPct());
        m.put("verdict", d.verdict());
        return m;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}
