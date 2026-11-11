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

package com.nageoffer.ai.ragent.bench.strategy;

/**
 * 响应缓存策略：用于量化"缓存命中率对总成本的影响"这一独立命题
 * <p>
 * 基准测试里缓存命中是真实发生的，不是编造的：同一批文档在 6 个实验里被重复入库、
 * 同一批问题在不同策略下被重复改写/分类，键相同即命中。
 * <ul>
 *   <li>{@link #DISABLED}：每次调用都真打模型，用于测"无缓存"的成本基线</li>
 *   <li>{@link #ENABLED}：开启 LRU 响应缓存，命中时输入按缓存价（通常 1/10）计价</li>
 * </ul>
 */
public enum CacheStrategy {

    /**
     * 关闭缓存：每次调用都产生完整输入成本
     */
    DISABLED("缓存关闭"),

    /**
     * 开启缓存：命中时输入 token 按缓存单价计价
     */
    ENABLED("缓存开启");

    private final String displayName;

    CacheStrategy(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
