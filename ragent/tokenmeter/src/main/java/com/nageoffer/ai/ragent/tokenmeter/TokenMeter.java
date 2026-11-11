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

import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * 计量门面（对应契约 4.7）。
 * <p>
 * 用途：埋点方唯一的入口。两种使用方式：
 * <ol>
 *   <li>{@link #record}：调用已经发生（或由 provider 直接返回 usage）时，把数字交进来即可</li>
 *   <li>{@link #measure}：把调用包进来，自动测耗时、按需估算输入 token、用 {@code outputTokenFn} 计算输出 token</li>
 * </ol>
 * <p>
 * 默认实现 {@link DefaultTokenMeter}：从 {@link MeterContext#current()} 补齐 runId / experimentId /
 * conversationId / turnIndex，按 {@link PricingTable} 算好 {@code costUsd} 后投递到 {@link MeterSink}。
 */
public interface TokenMeter {

    /**
     * 直接记录一次已发生的调用。
     *
     * @param stage             环节
     * @param modelId           模型 id（本地计算填 "local"）
     * @param provider          提供方（deepseek / bailian / local / none）
     * @param inputTokens       输入 token 总数（含缓存命中部分）
     * @param outputTokens      输出 token 数
     * @param cachedInputTokens 其中命中缓存的输入 token
     * @param cacheHit          是否整体命中响应缓存
     * @param latencyMs         耗时（毫秒）
     * @param traceTag          可选备注
     * @return 实际投递到 sink 的不可变事件
     */
    TokenUsage record(MeterStage stage, String modelId, String provider,
                      int inputTokens, int outputTokens, int cachedInputTokens,
                      boolean cacheHit, long latencyMs, String traceTag);

    /**
     * 包住一次调用，自动测耗时并记录。
     * <p>
     * 行为：
     * - 调用前后取 {@link System#nanoTime()} 计算耗时
     * - 输入 token 用启发式算法对 {@code inputPayload} 估算
     * - 输出 token 用 {@code outputTokenFn} 对返回值计算（返回 null 时记 0）
     * - 即使 {@code call} 返回 null 也照常记录事件
     * - {@code call} 抛异常时不记录事件，异常原样抛出（失败的调用不计费）
     *
     * @param stage         环节
     * @param modelId       模型 id
     * @param provider      提供方
     * @param inputPayload  请求载荷（用于估算输入 token），可为 null
     * @param call          待测量的调用
     * @param outputTokenFn 由返回值计算输出 token 的函数，可为 null（视为 0）
     * @param <T>           返回值类型
     * @return {@code call} 的返回值
     */
    <T> T measure(MeterStage stage, String modelId, String provider,
                  String inputPayload, Supplier<T> call,
                  ToIntFunction<T> outputTokenFn);

    /**
     * 关联的 sink。
     *
     * @return sink
     */
    MeterSink sink();

    /**
     * 清空 sink 内已收集的事件（重置一次运行）。
     */
    void reset();
}
