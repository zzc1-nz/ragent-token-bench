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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型定价表（对应契约 4.5）。
 * <p>
 * 用途：把 token 数量换算成美元成本。默认价格写死于 {@link #defaultTable()}（契约 4.5 表格，
 * 来源为各厂商公开定价页，单位 USD / 1M tokens），未知模型回退 {@code default} 档。
 * <p>
 * 计费规则（契约 4.6 语义配套）：
 * - 普通输入 token 按 {@code inputPerMillionUsd}
 * - 命中缓存的输入 token 按 {@code cachedInputPerMillionUsd}（折扣价）
 * - 输出 token 始终按 {@code outputPerMillionUsd}（即使响应来自缓存，输出仍被下游消费）
 * <p>
 * 线程安全：内部价格表在构造时复制为不可变 Map，构造后只读，可安全跨线程共享。
 */
public final class PricingTable {

    /**
     * 未知模型回退用的模型 id。
     */
    public static final String DEFAULT_MODEL_ID = "default";

    /**
     * 本地计算（无外部调用）使用的模型 id。
     */
    public static final String LOCAL_MODEL_ID = "local";

    private static final double ONE_MILLION = 1_000_000d;

    private final Map<String, ModelPrice> prices;

    /**
     * 构造定价表。
     *
     * @param prices 价格表；必须包含 {@code default} 档，否则回退时抛异常
     * @throws IllegalArgumentException prices 为 null / 空 / 缺少 default 档时抛出
     */
    public PricingTable(Map<String, ModelPrice> prices) {
        if (prices == null || prices.isEmpty()) {
            throw new IllegalArgumentException("PricingTable.prices 不能为空");
        }
        if (!prices.containsKey(DEFAULT_MODEL_ID)) {
            throw new IllegalArgumentException("PricingTable.prices 必须包含 default 档");
        }
        this.prices = Collections.unmodifiableMap(new LinkedHashMap<>(prices));
    }

    /**
     * 默认定价表（契约 4.5 写死，USD / 1M tokens）。
     * <p>
     * 价格来源：
     * - deepseek-flash / deepseek-chat：DeepSeek 开放平台定价页
     * - qwen-flash / qwen-plus / qwen3.8：阿里云百炼（DashScope）定价页
     * - gpt-6.1-sol：OpenAI 定价页
     * - qwen3-rerank：百炼 rerank 定价（仅输入计费）
     * - text-embedding-v4：百炼 embedding 定价（仅输入计费）
     * - local：本地推理/本地计算，零成本
     * - default：未知模型的保守回退档
     * <p>
     * 每次调用返回新实例（避免调用方改动共享状态；价格表本身不可变）。
     *
     * @return 默认定价表
     */
    public static PricingTable defaultTable() {
        Map<String, ModelPrice> map = new LinkedHashMap<>();
        map.put("deepseek-flash", new ModelPrice("deepseek-flash", 0.14, 0.28, 0.014));
        map.put("deepseek-chat", new ModelPrice("deepseek-chat", 0.27, 1.10, 0.07));
        map.put("qwen-flash", new ModelPrice("qwen-flash", 0.05, 0.40, 0.01));
        map.put("qwen-plus", new ModelPrice("qwen-plus", 0.40, 1.20, 0.08));
        map.put("qwen3.8", new ModelPrice("qwen3.8", 1.20, 4.80, 0.24));
        map.put("gpt-6.1-sol", new ModelPrice("gpt-6.1-sol", 2.50, 10.00, 1.25));
        map.put("qwen3-rerank", new ModelPrice("qwen3-rerank", 0.10, 0.00, 0.02));
        map.put("text-embedding-v4", new ModelPrice("text-embedding-v4", 0.02, 0.00, 0.004));
        map.put(LOCAL_MODEL_ID, new ModelPrice(LOCAL_MODEL_ID, 0.0, 0.0, 0.0));
        map.put(DEFAULT_MODEL_ID, new ModelPrice(DEFAULT_MODEL_ID, 0.50, 1.50, 0.10));
        return new PricingTable(map);
    }

    /**
     * 查询模型价格；未知模型回退 {@code default} 档。
     *
     * @param modelId 模型 id，可为 null
     * @return 对应价格；modelId 为 null 或未登记时返回 default 档价格
     */
    public ModelPrice priceOf(String modelId) {
        ModelPrice price = modelId == null ? null : prices.get(modelId);
        return price == null ? prices.get(DEFAULT_MODEL_ID) : price;
    }

    /**
     * 计算一次调用的成本（USD）。
     * <p>
     * 公式：{@code (普通输入 × 输入价 + 缓存输入 × 缓存价 + 输出 × 输出价) / 1_000_000}
     * <p>
     * 边界处理：
     * - {@code cachedIn} 超过 {@code in} 时按 {@code in} 截断（缓存部分不可能多于输入总数）
     * - 任一 token 数为负按 0 处理
     *
     * @param modelId  模型 id，未知回退 default
     * @param in       输入 token 总数（含缓存命中部分）
     * @param out      输出 token 数
     * @param cachedIn 其中命中缓存的输入 token 数
     * @return 成本（USD），非负
     */
    public double cost(String modelId, int in, int out, int cachedIn) {
        ModelPrice price = priceOf(modelId);
        int inputTotal = Math.max(in, 0);
        int outputTotal = Math.max(out, 0);
        int cachedTotal = Math.min(Math.max(cachedIn, 0), inputTotal);
        int normalInput = inputTotal - cachedTotal;

        double cost = (normalInput * price.inputPerMillionUsd()
                + cachedTotal * price.cachedInputPerMillionUsd()
                + outputTotal * price.outputPerMillionUsd()) / ONE_MILLION;
        return cost < 0d ? 0d : cost;
    }

    /**
     * 已登记的价格条目（只读视图）。
     *
     * @return 不可变 Map，key 为 modelId
     */
    public Map<String, ModelPrice> prices() {
        return prices;
    }
}
