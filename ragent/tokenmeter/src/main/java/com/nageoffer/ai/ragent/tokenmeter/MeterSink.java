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
 * 计量事件接收器（对应契约 4.9）。
 * <p>
 * 用途：解耦「产生事件」与「消费事件」。{@link DefaultTokenMeter} 只负责产生 {@link TokenUsage}
 * 并投递到 sink，具体落地方式（内存、jsonl 文件、组合、外部上报）由 sink 实现决定。
 * <p>
 * 实现：
 * - {@link InMemoryMeterSink}：进程内收集，适合单测与短生命周期运行
 * - {@link JsonlMeterSink}：追加写入 UTF-8 jsonl 文件，适合 bench 落盘
 * - {@link CompositeMeterSink}：扇出到多个 sink
 * <p>
 * 实现约定：{@link #accept} 必须线程安全（bench 可能多线程并发跑会话）。
 */
public interface MeterSink {

    /**
     * 接收一条计量事件。
     *
     * @param usage 计量事件，不能为 null
     */
    void accept(TokenUsage usage);

    /**
     * 返回已收集的事件。
     * <p>
     * 内存 sink 返回进程内快照；jsonl sink 从文件读回；组合 sink 返回各子 sink 结果按接收顺序合并。
     *
     * @return 事件列表（可能是副本，修改不影响 sink 内部状态）
     */
    List<TokenUsage> snapshot();

    /**
     * 刷出缓冲数据（如 jsonl 文件的 flush）。内存 sink 为空实现。
     */
    void flush();
}
