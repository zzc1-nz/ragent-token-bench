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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * 组合计量 sink（对应契约 4.9）。
 * <p>
 * 用途：把同一条事件同时投递给多个 sink，例如「内存收集（便于实时聚合）+ jsonl 落盘（便于事后分析）」。
 * <p>
 * 实现要点：
 * - 按构造顺序依次投递；单个子 sink 抛异常时记录并继续投递其余 sink，
 * 保证一个 sink 故障不会让整个计量链路失效（事件不丢比严格失败更重要）
 * - {@link #snapshot()} 返回各子 sink 结果按顺序拼接（同一事件可能出现多次，因为每个子 sink 都收集了它）
 * - {@link #flush()} 依次刷出所有子 sink
 * <p>
 * 线程安全：是（子 sink 自身线程安全，本类无状态）。
 */
public final class CompositeMeterSink implements MeterSink {

    private final List<MeterSink> sinks;

    /**
     * 构造组合 sink。
     *
     * @param sinks 子 sink 列表，可为 null（视为空）
     */
    public CompositeMeterSink(List<MeterSink> sinks) {
        this.sinks = sinks == null ? List.of() : List.copyOf(sinks);
    }

    /**
     * 构造组合 sink（可变参数便捷写法）。
     *
     * @param sinks 子 sink
     */
    public CompositeMeterSink(MeterSink... sinks) {
        this(sinks == null ? List.of() : Arrays.asList(sinks));
    }

    /**
     * 把事件投递给所有子 sink。
     *
     * @param usage 计量事件，不能为 null
     * @throws IllegalArgumentException usage 为 null 时抛出
     */
    @Override
    public void accept(TokenUsage usage) {
        if (usage == null) {
            throw new IllegalArgumentException("CompositeMeterSink.accept 的入参不能为 null");
        }
        for (MeterSink sink : sinks) {
            try {
                sink.accept(usage);
            } catch (RuntimeException e) {
                // 单个 sink 失败不影响其他 sink，事件仍会落到其余 sink
                System.err.println("CompositeMeterSink: 子 sink 投递失败 " + sink.getClass().getName()
                        + ": " + e.getMessage());
            }
        }
    }

    /**
     * 返回所有子 sink 事件的拼接副本。
     *
     * @return 事件列表
     */
    @Override
    public List<TokenUsage> snapshot() {
        List<TokenUsage> result = new ArrayList<>();
        for (MeterSink sink : sinks) {
            result.addAll(sink.snapshot());
        }
        return result;
    }

    /**
     * 依次刷出所有子 sink。
     */
    @Override
    public void flush() {
        for (MeterSink sink : sinks) {
            try {
                sink.flush();
            } catch (RuntimeException e) {
                System.err.println("CompositeMeterSink: 子 sink flush 失败 " + sink.getClass().getName()
                        + ": " + e.getMessage());
            }
        }
    }

    /**
     * 子 sink 列表（只读）。
     *
     * @return 子 sink
     */
    public Collection<MeterSink> sinks() {
        return sinks;
    }
}
