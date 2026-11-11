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

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 单个模型的价格（对应契约 4.5）。
 * <p>
 * 用途：描述某模型每百万 token 的美元单价，供 {@link PricingTable#cost} 计算单次调用成本。
 *
 * @param modelId                    模型 id
 * @param inputPerMillionUsd         输入 token 单价（USD / 1M tokens）
 * @param outputPerMillionUsd        输出 token 单价（USD / 1M tokens）
 * @param cachedInputPerMillionUsd   命中缓存的输入 token 单价（USD / 1M tokens）
 */
public record ModelPrice(
        @JsonProperty("modelId") String modelId,
        @JsonProperty("inputPerMillionUsd") double inputPerMillionUsd,
        @JsonProperty("outputPerMillionUsd") double outputPerMillionUsd,
        @JsonProperty("cachedInputPerMillionUsd") double cachedInputPerMillionUsd
) {
}
