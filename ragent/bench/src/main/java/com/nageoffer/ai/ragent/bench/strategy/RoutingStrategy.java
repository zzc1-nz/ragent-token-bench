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
 * 模型路由策略：A/B 对比的第三个自变量
 * <p>
 * 对应 Ragent 的 {@code ModelSelector} 档位机制（fast / standard / deep）：
 * <ul>
 *   <li>{@link #FIXED_PREMIUM}：所有环节一律走高档模型（standard 档的 qwen3.8），
 *       质量天花板高，但意图分类、改写这类"轻任务"也付高档价，是典型的成本浪费</li>
 *   <li>{@link #INTENT_ROUTED}：按任务性质路由 —— 意图识别/问题改写这类短输出、低难度任务
 *       走 fast 档廉价模型（qwen-flash），只有答案生成与质量评分走 standard 档。
 *       这正是 Ragent 档位机制存在的意义，本策略把它显式化以便量化收益</li>
 * </ul>
 */
public enum RoutingStrategy {

    /**
     * 固定高档模型：所有环节统一 standard 档
     */
    FIXED_PREMIUM("固定高档模型"),

    /**
     * 按意图/任务路由：轻任务走 fast 档廉价模型，重任务走 standard 档
     */
    INTENT_ROUTED("按意图路由");

    private final String displayName;

    RoutingStrategy(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
