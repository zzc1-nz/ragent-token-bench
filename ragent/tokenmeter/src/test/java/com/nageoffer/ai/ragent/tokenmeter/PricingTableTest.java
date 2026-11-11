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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PricingTable} 单测。
 * <p>
 * 覆盖点：
 * - 契约 4.5 的默认价格表逐项校验
 * - 未知模型 / null 回退 default 档
 * - 普通计费、缓存折扣计费、混合计费
 * - 边界：cachedIn 超过 in 时截断、负数 token 归零、local 零成本
 * - 构造校验：缺少 default 档时报错
 */
class PricingTableTest {

    private static final double DELTA = 1e-12;

    private final PricingTable table = PricingTable.defaultTable();

    @Test
    @DisplayName("默认价格表与契约 4.5 完全一致")
    void defaultTableMatchesContract() {
        assertPrice("deepseek-flash", 0.14, 0.28, 0.014);
        assertPrice("deepseek-chat", 0.27, 1.10, 0.07);
        assertPrice("qwen-flash", 0.05, 0.40, 0.01);
        assertPrice("qwen-plus", 0.40, 1.20, 0.08);
        assertPrice("qwen3.8", 1.20, 4.80, 0.24);
        assertPrice("gpt-6.1-sol", 2.50, 10.00, 1.25);
        assertPrice("qwen3-rerank", 0.10, 0.00, 0.02);
        assertPrice("text-embedding-v4", 0.02, 0.00, 0.004);
        assertPrice("local", 0.0, 0.0, 0.0);
        assertPrice("default", 0.50, 1.50, 0.10);
    }

    @Test
    @DisplayName("未知模型与 null 回退 default 档")
    void unknownModelFallsBackToDefault() {
        ModelPrice expected = table.priceOf("default");
        assertEquals(expected, table.priceOf("gpt-99-ultra"));
        assertEquals(expected, table.priceOf(null));
        assertEquals(expected, table.priceOf(""));
    }

    @Test
    @DisplayName("普通计费：1M 输入 + 1M 输出 = 输入价 + 输出价")
    void costWithoutCache() {
        // deepseek-chat: 0.27 + 1.10 = 1.37
        assertEquals(1.37, table.cost("deepseek-chat", 1_000_000, 1_000_000, 0), DELTA);
        // qwen-flash: 0.05 + 0.40 = 0.45
        assertEquals(0.45, table.cost("qwen-flash", 1_000_000, 1_000_000, 0), DELTA);
    }

    @Test
    @DisplayName("缓存折扣：全部输入命中缓存时按缓存价计，输出仍按正常价")
    void costWithFullCacheHit() {
        // deepseek-chat: cached 0.07 + output 1.10 = 1.17
        assertEquals(1.17, table.cost("deepseek-chat", 1_000_000, 1_000_000, 1_000_000), DELTA);
        // deepseek-flash: cached 0.014 + output 0.28 = 0.294
        assertEquals(0.294, table.cost("deepseek-flash", 1_000_000, 1_000_000, 1_000_000), DELTA);
    }

    @Test
    @DisplayName("部分缓存：普通输入 + 缓存输入 + 输出三段分别计价")
    void costWithPartialCache() {
        // deepseek-chat, in=1M, cached=400k -> normal 600k
        // 600k*0.27/1M = 0.162, 400k*0.07/1M = 0.028, out 1M*1.10/1M = 1.10 -> 1.29
        assertEquals(1.29, table.cost("deepseek-chat", 1_000_000, 1_000_000, 400_000), DELTA);
    }

    @Test
    @DisplayName("小数据量按比例计费")
    void costProportionalForSmallTokenCounts() {
        // deepseek-flash 1000 输入 + 500 输出
        // 1000*0.14/1M + 500*0.28/1M = 0.00014 + 0.00014 = 0.00028
        assertEquals(0.00028, table.cost("deepseek-flash", 1000, 500, 0), 1e-15);
    }

    @Test
    @DisplayName("cachedIn 超过 in 时按 in 截断（缓存部分不可能多于输入总数）")
    void cachedInputClampedToInput() {
        double clamped = table.cost("deepseek-chat", 1000, 0, 5000);
        double expected = table.cost("deepseek-chat", 1000, 0, 1000);
        assertEquals(expected, clamped, DELTA);
        // 1000 * 0.07 / 1M
        assertEquals(0.00007, clamped, 1e-15);
    }

    @Test
    @DisplayName("负数 token 按 0 处理")
    void negativeTokensTreatedAsZero() {
        assertEquals(0.0, table.cost("deepseek-chat", -100, -100, -100), DELTA);
        assertEquals(table.cost("deepseek-chat", 0, 0, 0), table.cost("deepseek-chat", -5, -5, -5), DELTA);
    }

    @Test
    @DisplayName("local 模型零成本")
    void localModelIsFree() {
        assertEquals(0.0, table.cost("local", 1_000_000, 1_000_000, 0), DELTA);
    }

    @Test
    @DisplayName("rerank/embedding 无输出价：只按输入计费")
    void rerankAndEmbeddingChargeInputOnly() {
        // qwen3-rerank: 1M 输入 = 0.10
        assertEquals(0.10, table.cost("qwen3-rerank", 1_000_000, 1_000_000, 0), DELTA);
        // text-embedding-v4: 1M 输入 = 0.02
        assertEquals(0.02, table.cost("text-embedding-v4", 1_000_000, 1_000_000, 0), DELTA);
    }

    @Test
    @DisplayName("构造时缺少 default 档抛异常")
    void missingDefaultRejected() {
        java.util.Map<String, ModelPrice> noDefault = new java.util.LinkedHashMap<>();
        noDefault.put("m", new ModelPrice("m", 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new PricingTable(noDefault));
        assertThrows(IllegalArgumentException.class, () -> new PricingTable(null));
        assertThrows(IllegalArgumentException.class, () -> new PricingTable(java.util.Map.of()));
    }

    @Test
    @DisplayName("prices() 返回只读视图")
    void pricesIsUnmodifiable() {
        assertTrue(table.prices().containsKey("default"));
        assertThrows(UnsupportedOperationException.class,
                () -> table.prices().put("x", new ModelPrice("x", 1, 1, 1)));
    }

    /**
     * 断言某模型价格三项与期望一致。
     *
     * @param modelId  模型 id
     * @param input    期望输入价
     * @param output   期望输出价
     * @param cachedIn 期望缓存输入价
     */
    private void assertPrice(String modelId, double input, double output, double cachedIn) {
        ModelPrice price = table.priceOf(modelId);
        assertEquals(modelId, price.modelId());
        assertEquals(input, price.inputPerMillionUsd(), DELTA, modelId + ".input");
        assertEquals(output, price.outputPerMillionUsd(), DELTA, modelId + ".output");
        assertEquals(cachedIn, price.cachedInputPerMillionUsd(), DELTA, modelId + ".cachedInput");
    }
}
