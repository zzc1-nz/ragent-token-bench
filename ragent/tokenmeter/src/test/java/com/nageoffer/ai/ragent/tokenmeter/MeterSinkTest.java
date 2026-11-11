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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryMeterSink} / {@link CompositeMeterSink} 单测。
 * <p>
 * 覆盖点：
 * - InMemoryMeterSink 收集、快照、clear、线程安全
 * - CompositeMeterSink 扇出到多个 sink、单 sink 失败不影响其他
 */
class MeterSinkTest {

    @Test
    @DisplayName("InMemoryMeterSink 收集事件并按接收顺序快照")
    void inMemoryCollectsInOrder() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        TokenUsage first = usage(MeterStage.PARSE, 10, 0);
        TokenUsage second = usage(MeterStage.GENERATE, 100, 50);

        sink.accept(first);
        sink.accept(second);

        assertEquals(2, sink.size());
        List<TokenUsage> snapshot = sink.snapshot();
        assertEquals(2, snapshot.size());
        assertEquals(first, snapshot.get(0));
        assertEquals(second, snapshot.get(1));
    }

    @Test
    @DisplayName("InMemoryMeterSink.snapshot 返回副本，修改不影响内部状态")
    void snapshotIsCopy() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        sink.accept(usage(MeterStage.PARSE, 10, 0));

        List<TokenUsage> snapshot = sink.snapshot();
        snapshot.clear();

        assertEquals(1, sink.size());
    }

    @Test
    @DisplayName("InMemoryMeterSink.clear 清空事件")
    void inMemoryClear() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        sink.accept(usage(MeterStage.PARSE, 10, 0));
        sink.clear();
        assertEquals(0, sink.size());
        assertTrue(sink.snapshot().isEmpty());
    }

    @Test
    @DisplayName("InMemoryMeterSink 拒绝 null 事件")
    void inMemoryRejectsNull() {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        assertThrows(IllegalArgumentException.class, () -> sink.accept(null));
    }

    @Test
    @DisplayName("InMemoryMeterSink 并发写入不丢事件")
    void inMemoryThreadSafe() throws InterruptedException {
        InMemoryMeterSink sink = new InMemoryMeterSink();
        int threads = 8;
        int perThread = 200;
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    sink.accept(usage(MeterStage.GENERATE, 1, 1));
                }
            });
            workers[t].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        assertEquals(threads * perThread, sink.size());
    }

    @Test
    @DisplayName("CompositeMeterSink 把同一事件扇出到所有子 sink")
    void compositeFansOut() {
        InMemoryMeterSink first = new InMemoryMeterSink();
        InMemoryMeterSink second = new InMemoryMeterSink();
        CompositeMeterSink composite = new CompositeMeterSink(first, second);

        TokenUsage event = usage(MeterStage.GENERATE, 100, 50);
        composite.accept(event);

        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertEquals(event, first.snapshot().get(0));
        assertEquals(event, second.snapshot().get(0));
    }

    @Test
    @DisplayName("CompositeMeterSink.snapshot 拼接各子 sink 结果")
    void compositeSnapshotConcatenates() {
        InMemoryMeterSink first = new InMemoryMeterSink();
        InMemoryMeterSink second = new InMemoryMeterSink();
        CompositeMeterSink composite = new CompositeMeterSink(List.of(first, second));

        composite.accept(usage(MeterStage.PARSE, 10, 0));

        // 同一事件在每个子 sink 各一份
        assertEquals(2, composite.snapshot().size());
    }

    @Test
    @DisplayName("CompositeMeterSink 单个子 sink 失败不影响其他子 sink")
    void compositeIsolatesFailures() {
        InMemoryMeterSink healthy = new InMemoryMeterSink();
        MeterSink broken = new MeterSink() {
            @Override
            public void accept(TokenUsage usage) {
                throw new IllegalStateException("故意失败");
            }

            @Override
            public List<TokenUsage> snapshot() {
                return List.of();
            }

            @Override
            public void flush() {
                throw new IllegalStateException("flush 也失败");
            }
        };
        CompositeMeterSink composite = new CompositeMeterSink(broken, healthy);

        composite.accept(usage(MeterStage.GENERATE, 1, 1));

        assertEquals(1, healthy.size(), "健康的 sink 仍应收到事件");
        composite.flush(); // 不应抛出
    }

    @Test
    @DisplayName("CompositeMeterSink 空构造安全")
    void compositeEmpty() {
        CompositeMeterSink composite = new CompositeMeterSink(List.of());
        composite.accept(usage(MeterStage.PARSE, 1, 1));
        composite.flush();
        assertTrue(composite.snapshot().isEmpty());
        assertFalse(composite.sinks().iterator().hasNext());
    }

    @Test
    @DisplayName("CompositeMeterSink 拒绝 null 事件")
    void compositeRejectsNull() {
        CompositeMeterSink composite = new CompositeMeterSink(new InMemoryMeterSink());
        assertThrows(IllegalArgumentException.class, () -> composite.accept(null));
    }

    /**
     * 构造测试事件。
     *
     * @param stage 环节
     * @param in    输入 token
     * @param out   输出 token
     * @return 事件
     */
    private TokenUsage usage(MeterStage stage, int in, int out) {
        return TokenUsage.builder()
                .stage(stage)
                .modelId("deepseek-chat")
                .provider("deepseek")
                .inputTokens(in)
                .outputTokens(out)
                .timestampMs(1_700_000_000_000L)
                .build();
    }
}
