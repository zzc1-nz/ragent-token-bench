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

import java.util.List;

/**
 * Token 估算器（对应契约 4.4）。
 * <p>
 * 用途：在拿不到 provider 真实 usage 时（如本地确定性 LLM、缓存命中、纯本地计算环节），
 * 用可复现的算法估算 token 数，保证基准测试数据完整且可比。
 * <p>
 * 实现：
 * - {@link HeuristicTokenEstimator}：启发式估算，与 Ragent 的 HeuristicTokenCounterService 结果一致
 * - {@link ProviderReportedEstimator}：直接采用 provider 返回的 usage 字段
 */
public interface TokenEstimator {

    /**
     * 估算一段文本的 token 数。
     *
     * @param text 文本，可为 null 或空串
     * @return 估算 token 数，非负；空文本返回 0
     */
    int estimate(String text);

    /**
     * 估算多段文本（如 role/content 拼接对）的 token 总数。
     * <p>
     * 简化语义：直接按拼接后的文本估算，不额外加 chat 模板的固定开销。
     *
     * @param roleTextPairs 待估算的文本片段列表，可为 null
     * @return 估算 token 总数，非负
     */
    int estimateMessages(List<String> roleTextPairs);
}
