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
import java.util.Objects;

/**
 * Provider 上报型估算器（对应契约 4.4）。
 * <p>
 * 用途：真实调用 LLM 时，provider 会在响应里返回 {@code usage} 字段（prompt_tokens / completion_tokens 等）。
 * 这类数据比启发式估算更准确，应优先使用。本类把 provider 上报的数值直接作为估算结果，
 * 让调用方可以在「估算」和「真实 usage」之间用同一套 {@link TokenEstimator} 接口无缝切换。
 * <p>
 * 实现说明：本类为「值持有型」估算器 —— 构造时传入 provider 上报的 usage，
 * 之后 {@link #estimate(String)} 返回该上报值（忽略入参文本），
 * 适用于「一次调用对应一次 usage 上报」的场景。
 * <p>
 * 线程安全：字段 final 且不可变，可安全跨线程共享。
 */
public final class ProviderReportedEstimator implements TokenEstimator {

    private final int reportedInputTokens;
    private final int reportedOutputTokens;

    /**
     * 构造估算器。
     *
     * @param reportedInputTokens  provider 上报的输入 token（负数按 0 处理）
     * @param reportedOutputTokens provider 上报的输出 token（负数按 0 处理）
     */
    public ProviderReportedEstimator(int reportedInputTokens, int reportedOutputTokens) {
        this.reportedInputTokens = Math.max(reportedInputTokens, 0);
        this.reportedOutputTokens = Math.max(reportedOutputTokens, 0);
    }

    /**
     * 返回 provider 上报的输入 token（忽略文本内容）。
     *
     * @param text 文本（本实现不使用）
     * @return provider 上报的输入 token
     */
    @Override
    public int estimate(String text) {
        return reportedInputTokens;
    }

    /**
     * 返回 provider 上报的输入 token（忽略文本片段）。
     *
     * @param roleTextPairs 文本片段（本实现不使用）
     * @return provider 上报的输入 token
     */
    @Override
    public int estimateMessages(List<String> roleTextPairs) {
        return reportedInputTokens;
    }

    /**
     * provider 上报的输入 token。
     *
     * @return 输入 token
     */
    public int reportedInputTokens() {
        return reportedInputTokens;
    }

    /**
     * provider 上报的输出 token。
     *
     * @return 输出 token
     */
    public int reportedOutputTokens() {
        return reportedOutputTokens;
    }

    /**
     * provider 上报的总 token。
     *
     * @return 输入 + 输出
     */
    public int reportedTotalTokens() {
        return reportedInputTokens + reportedOutputTokens;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProviderReportedEstimator other)) {
            return false;
        }
        return reportedInputTokens == other.reportedInputTokens
                && reportedOutputTokens == other.reportedOutputTokens;
    }

    @Override
    public int hashCode() {
        return Objects.hash(reportedInputTokens, reportedOutputTokens);
    }

    @Override
    public String toString() {
        return "ProviderReportedEstimator{input=" + reportedInputTokens
                + ", output=" + reportedOutputTokens + '}';
    }
}
