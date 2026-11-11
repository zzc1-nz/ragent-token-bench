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
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 内存计量 sink（对应契约 4.9）。
 * <p>
 * 用途：在进程内收集计量事件，供单测、短生命周期 benchmark、以及「跑完立刻聚合」的场景使用。
 * <p>
 * 实现要点：
 * - 底层 {@link ConcurrentLinkedQueue}（契约要求的并发集合），{@link #accept} 无锁、线程安全
 * - {@link #snapshot()} 返回按接收顺序排列的 {@link ArrayList} 副本，调用方可自由修改
 * <p>
 * 线程安全：是。
 */
public final class InMemoryMeterSink implements MeterSink {

    private final ConcurrentLinkedQueue<TokenUsage> events = new ConcurrentLinkedQueue<>();

    /**
     * 接收一条事件。
     *
     * @param usage 计量事件，不能为 null
     * @throws IllegalArgumentException usage 为 null 时抛出
     */
    @Override
    public void accept(TokenUsage usage) {
        if (usage == null) {
            throw new IllegalArgumentException("InMemoryMeterSink.accept 的入参不能为 null");
        }
        events.add(usage);
    }

    /**
     * 返回当前已收集事件的副本（按接收顺序）。
     *
     * @return 事件列表副本
     */
    @Override
    public List<TokenUsage> snapshot() {
        return new ArrayList<>(events);
    }

    /**
     * 内存 sink 无需 flush，空实现。
     */
    @Override
    public void flush() {
        // 无缓冲，无需刷出
    }

    /**
     * 清空已收集事件（用于复用同一个 sink 跑多轮）。
     */
    public void clear() {
        events.clear();
    }

    /**
     * 当前已收集事件数。
     *
     * @return 事件数
     */
    public int size() {
        return events.size();
    }
}
