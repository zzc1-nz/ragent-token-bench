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

package com.nageoffer.ai.ragent.bench;

import com.nageoffer.ai.ragent.bench.dataset.BenchDocument;
import com.nageoffer.ai.ragent.bench.dataset.BenchQuery;
import com.nageoffer.ai.ragent.bench.dataset.CorpusLoader;
import com.nageoffer.ai.ragent.bench.report.ResultWriter;
import com.nageoffer.ai.ragent.bench.run.BenchmarkRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 基准测试入口：纯 Java main，不启动 Spring 容器
 * <p>
 * 用法：
 * <pre>
 * java -cp bench.jar com.nageoffer.ai.ragent.bench.BenchmarkMain \
 *      --docs data/corpus/docs.jsonl \
 *      --queries data/corpus/queries.jsonl \
 *      --out data/results
 * </pre>
 * 也支持不带参数：默认读 {@code data/corpus/}，写到 {@code data/results/RUN_&lt;时间戳&gt;}。
 */
public final class BenchmarkMain {

    private static final DateTimeFormatter RUN_ID_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private BenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);

        Path docsPath = Paths.get(opts.getOrDefault("docs", "data/corpus/docs.jsonl"));
        Path queriesPath = Paths.get(opts.getOrDefault("queries", "data/corpus/queries.jsonl"));
        Path outRoot = Paths.get(opts.getOrDefault("out", "data/results"));
        String runId = opts.getOrDefault("run-id", "RUN_" + LocalDateTime.now().format(RUN_ID_FMT));

        log("读取数据集...");
        List<BenchDocument> documents = CorpusLoader.loadDocuments(docsPath);
        List<BenchQuery> queries = CorpusLoader.loadQueries(queriesPath);
        log("  文档数：" + documents.size() + "，问题数：" + queries.size());

        long docChars = documents.stream().mapToLong(d -> d.text().length()).sum();
        log("  文档总字符数：" + String.format("%,d", docChars)
                + "，平均 " + (documents.isEmpty() ? 0 : docChars / documents.size()) + " 字符/篇");

        log("开始运行基准测试（6 组策略 × " + documents.size() + " 文档 + "
                + queries.size() + " 轮对话）...");
        long start = System.currentTimeMillis();

        BenchmarkRunner runner = new BenchmarkRunner();
        BenchmarkRunner.RunOutcome outcome = runner.run(documents, queries, runId);

        long elapsed = System.currentTimeMillis() - start;
        log("运行完成，耗时 " + String.format("%.1f", elapsed / 1000.0) + " 秒");
        log("  计量事件数：" + String.format("%,d", outcome.events().size()));

        Path outDir = outRoot.resolve(runId);
        ResultWriter.writeAll(outcome, outDir, documents.size(), queries.size(), queries.size());

        log("结果已写出：");
        log("  " + outDir.resolve("events.jsonl").toAbsolutePath());
        log("  " + outDir.resolve("summary.json").toAbsolutePath());
        log("  " + outDir.resolve("report.md").toAbsolutePath());

        // 打印关键数字，便于命令行直接确认
        printKeyNumbers(outcome);
    }

    /**
     * 打印关键指标：命令行跑完能立刻看到结论，不用打开文件
     */
    private static void printKeyNumbers(BenchmarkRunner.RunOutcome outcome) {
        long totalTokens = outcome.events().stream()
                .mapToLong(e -> (long) e.inputTokens() + e.outputTokens())
                .sum();
        double totalCost = outcome.events().stream().mapToDouble(e -> e.costUsd()).sum();
        long cacheHits = outcome.events().stream().filter(e -> e.cacheHit()).count();

        System.out.println();
        System.out.println("=".repeat(72));
        System.out.println("  基准测试结果摘要");
        System.out.println("=".repeat(72));
        System.out.printf("  累计 Token 消耗 : %,d%n", totalTokens);
        System.out.printf("  累计成本        : $%.4f%n", totalCost);
        System.out.printf("  计量事件数      : %,d%n", outcome.events().size());
        System.out.printf("  缓存命中事件    : %,d (%.1f%%)%n",
                cacheHits, outcome.events().isEmpty() ? 0
                        : (double) cacheHits / outcome.events().size() * 100);
        System.out.println();
        System.out.println("  各策略：");
        outcome.summaries().forEach((id, s) -> System.out.printf(
                "    %-14s token=%,9d  cost=$%.4f  quality=%.1f  cacheHit=%.1f%%%n",
                id, s.totalTokens(), s.totalCostUsd(), s.qualityScorePct(), s.cacheHitRate() * 100));
        System.out.println("=".repeat(72));
    }

    /**
     * 极简参数解析：只支持 {@code --key value} 与 {@code --key=value}
     */
    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new java.util.LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }
            String key = arg.substring(2);
            int eq = key.indexOf('=');
            if (eq > 0) {
                opts.put(key.substring(0, eq), key.substring(eq + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                opts.put(key, args[++i]);
            } else {
                opts.put(key, "true");
            }
        }
        return opts;
    }

    private static void log(String message) {
        System.out.println("[bench] " + message);
    }
}
