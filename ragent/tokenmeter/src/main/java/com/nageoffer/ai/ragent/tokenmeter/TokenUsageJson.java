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

package com.nageoffer.ai.ragent.tokenmeter;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link TokenUsage} 的 JSON 编解码工具（供 bench / dashboard 数据管道复用）。
 * <p>
 * 用途：把「事件 ↔ JSON 行」的编解码集中到一处，保证写出的 events.jsonl 与读回的反序列化
 * 使用同一套配置，避免各模块各配一个 {@code ObjectMapper} 导致字段名/数值格式漂移。
 * <p>
 * 字段约定（契约 4.2 / 6.4）：全部 camelCase，16 个字段始终输出（值为 null 时输出 {@code null}），
 * 顺序与 record 组件顺序一致，便于人工比对与 diff。
 * <p>
 * 线程安全：内部 {@link ObjectMapper} 构造后只读使用，Jackson 的 ObjectMapper 在配置完成后是线程安全的。
 */
public final class TokenUsageJson {

    /**
     * 共享 ObjectMapper。
     * <p>
     * 配置：
     * - {@link JsonInclude.Include#ALWAYS}：null 字段也输出，保证每行字段集稳定
     * - 忽略未知字段：向后兼容 bench 读取旧版本事件文件
     * - 关闭「空 Bean 报错」等无关特性
     * <p>
     * 使用 {@link JsonMapper#builder()} 构造，避免 Jackson 2.21 中已废弃的 {@code configure(...)} 链式 API。
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.ALWAYS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();

    private TokenUsageJson() {
        throw new UnsupportedOperationException("工具类不允许实例化");
    }

    /**
     * 共享的 ObjectMapper（只读使用）。
     *
     * @return 已配置好的 ObjectMapper
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * 序列化为单行 JSON（不含换行符）。
     *
     * @param usage 计量事件，不能为 null
     * @return JSON 字符串
     * @throws IllegalArgumentException usage 为 null 时抛出
     * @throws IllegalStateException    序列化失败时抛出
     */
    public static String toJson(TokenUsage usage) {
        if (usage == null) {
            throw new IllegalArgumentException("TokenUsageJson.toJson 的入参不能为 null");
        }
        try {
            return MAPPER.writeValueAsString(usage);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("TokenUsage 序列化失败", e);
        }
    }

    /**
     * 从单行 JSON 反序列化。
     *
     * @param json JSON 字符串，不能为 null
     * @return 计量事件
     * @throws IllegalArgumentException json 为 null 时抛出
     * @throws IllegalStateException    JSON 非法时抛出
     */
    public static TokenUsage fromJson(String json) {
        if (json == null) {
            throw new IllegalArgumentException("TokenUsageJson.fromJson 的入参不能为 null");
        }
        try {
            return MAPPER.readValue(json, TokenUsage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("TokenUsage 反序列化失败: " + abbreviate(json), e);
        }
    }

    /**
     * 读取 jsonl 文件中的全部事件（跳过空行）。
     * <p>
     * 供 bench 读回 {@code events.jsonl} 后直接交给 {@link MeterAggregator} 聚合。
     *
     * @param file jsonl 文件路径
     * @return 事件列表（保持文件顺序）；文件不存在时返回空列表
     * @throws UncheckedIOException 读取失败时抛出
     */
    public static List<TokenUsage> readAll(Path file) {
        if (file == null || !Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<TokenUsage> result = new ArrayList<>(lines.size());
            for (String line : lines) {
                String trimmed = line == null ? "" : line.trim();
                if (!trimmed.isEmpty()) {
                    result.add(fromJson(trimmed));
                }
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("读取 jsonl 文件失败: " + file, e);
        }
    }

    /**
     * 截断过长的 JSON 文本用于异常信息。
     *
     * @param text 原文
     * @return 最多 200 字符的摘要
     */
    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
