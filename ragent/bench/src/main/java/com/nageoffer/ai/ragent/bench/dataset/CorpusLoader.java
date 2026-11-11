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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语料加载器：读 {@code docs.jsonl} / {@code queries.jsonl}
 * <p>
 * 用 jsonl 而不是一个大 JSON 数组，是为了让 200 篇文档的 diff 可读、可增量编辑；
 * 每行一个对象，空行与 {@code #} 开头的注释行跳过。
 */
public final class CorpusLoader {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private CorpusLoader() {
    }

    /**
     * 加载文档语料
     *
     * @param path docs.jsonl 路径
     */
    public static List<BenchDocument> loadDocuments(Path path) {
        List<BenchDocument> docs = new ArrayList<>();
        for (String line : readLines(path)) {
            Map<String, Object> obj = parseObject(line, path);
            docs.add(new BenchDocument(
                    str(obj, "docId"),
                    str(obj, "title"),
                    str(obj, "domain"),
                    str(obj, "text"),
                    (int) num(obj, "chunksHint", 0)
            ));
        }
        return docs;
    }

    /**
     * 加载评测问题集
     *
     * @param path queries.jsonl 路径
     */
    public static List<BenchQuery> loadQueries(Path path) {
        List<BenchQuery> queries = new ArrayList<>();
        for (String line : readLines(path)) {
            Map<String, Object> obj = parseObject(line, path);
            queries.add(new BenchQuery(
                    str(obj, "conversationId"),
                    (int) num(obj, "turnIndex", 0),
                    str(obj, "question"),
                    strList(obj, "referenceDocIds"),
                    str(obj, "referenceAnswer"),
                    str(obj, "intentHint")
            ));
        }
        return queries;
    }

    /**
     * 把文档按会话分组，保留原顺序 —— 多轮对话必须按会话内顺序回放
     */
    public static Map<String, List<BenchQuery>> groupByConversation(List<BenchQuery> queries) {
        Map<String, List<BenchQuery>> grouped = new LinkedHashMap<>();
        for (BenchQuery q : queries) {
            grouped.computeIfAbsent(q.conversationId(), k -> new ArrayList<>()).add(q);
        }
        grouped.values().forEach(list -> list.sort((a, b) -> Integer.compare(a.turnIndex(), b.turnIndex())));
        return grouped;
    }

    // ==================== 内部工具 ====================

    private static List<String> readLines(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("数据集文件不存在：" + path.toAbsolutePath());
        }
        try {
            List<String> lines = new ArrayList<>();
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                lines.add(line);
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException("读取数据集失败：" + path.toAbsolutePath(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String line, Path path) {
        try {
            return GSON.fromJson(line, Map.class);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException(
                    "数据集 JSON 解析失败，文件：" + path.getFileName() + "，行首：" + preview(line), e);
        }
    }

    private static String preview(String line) {
        return line.length() <= 80 ? line : line.substring(0, 80) + "...";
    }

    private static String str(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static double num(Map<String, Object> obj, String key, double fallback) {
        Object v = obj.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }
}
