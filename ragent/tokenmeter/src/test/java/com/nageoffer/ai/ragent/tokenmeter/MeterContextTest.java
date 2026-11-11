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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link MeterContext} 单测。
 * <p>
 * 覆盖点：
 * - open 后 current() 可读、close 后恢复为 null
 * - 嵌套 open：内层覆盖外层，close 逐层恢复
 * - setConversationId / setTurnIndex 中途绑定
 * - 重复 close 幂等
 * - callWith 自动恢复
 * - try-with-resources 用法
 * - 子线程继承（InheritableThreadLocal 兜底）
 */
class MeterContextTest {

    @Test
    @DisplayName("open 后 current 可见，close 后恢复 null")
    void openAndClose() {
        assertNull(MeterContext.current(), "测试开始应无上下文");

        try (MeterContext ctx = MeterContext.open("RUN_1", "EXP_A")) {
            assertSame(ctx, MeterContext.current());
            assertEquals("RUN_1", ctx.runId());
            assertEquals("EXP_A", ctx.experimentId());
            assertNull(ctx.conversationId());
            assertNull(ctx.turnIndex());
        }

        assertNull(MeterContext.current(), "close 后应恢复为 null");
    }

    @Test
    @DisplayName("嵌套 open：内层覆盖外层，close 逐层恢复（LIFO）")
    void nestedContexts() {
        try (MeterContext outer = MeterContext.open("RUN_OUTER", "EXP_OUTER", "CONV_1", 0)) {
            assertSame(outer, MeterContext.current());
            assertEquals("RUN_OUTER", MeterContext.current().runId());

            try (MeterContext inner = MeterContext.open("RUN_INNER", "EXP_INNER", "CONV_2", 1)) {
                assertSame(inner, MeterContext.current());
                assertEquals("RUN_INNER", MeterContext.current().runId());
                assertEquals("EXP_INNER", MeterContext.current().experimentId());
                assertEquals("CONV_2", MeterContext.current().conversationId());
                assertEquals(1, MeterContext.current().turnIndex());
            }

            assertSame(outer, MeterContext.current(), "内层 close 后应恢复外层");
            assertEquals("RUN_OUTER", MeterContext.current().runId());
            assertEquals("CONV_1", MeterContext.current().conversationId());
        }

        assertNull(MeterContext.current());
    }

    @Test
    @DisplayName("三层嵌套按 LIFO 逐层恢复")
    void tripleNesting() {
        try (MeterContext l1 = MeterContext.open("R1", "E1")) {
            try (MeterContext l2 = MeterContext.open("R2", "E2")) {
                try (MeterContext l3 = MeterContext.open("R3", "E3")) {
                    assertEquals("R3", MeterContext.current().runId());
                }
                assertEquals("R2", MeterContext.current().runId());
                assertSame(l2, MeterContext.current());
            }
            assertEquals("R1", MeterContext.current().runId());
            assertSame(l1, MeterContext.current());
        }
        assertNull(MeterContext.current());
    }

    @Test
    @DisplayName("setConversationId / setTurnIndex 允许中途绑定")
    void midFlightBinding() {
        try (MeterContext ctx = MeterContext.open("RUN_1", "EXP_A")) {
            assertNull(ctx.conversationId());
            assertNull(ctx.turnIndex());

            ctx.setConversationId("CONV_9");
            ctx.setTurnIndex(3);

            assertEquals("CONV_9", ctx.conversationId());
            assertEquals(3, ctx.turnIndex());
            // current() 与持有的实例是同一个对象
            assertEquals("CONV_9", MeterContext.current().conversationId());
            assertEquals(3, MeterContext.current().turnIndex());
        }
    }

    @Test
    @DisplayName("重复 close 幂等，不破坏栈")
    void doubleCloseIsIdempotent() {
        MeterContext outer = MeterContext.open("R1", "E1");
        MeterContext inner = MeterContext.open("R2", "E2");

        inner.close();
        inner.close(); // 重复 close 不应把外层也弹掉
        assertSame(outer, MeterContext.current());

        outer.close();
        outer.close();
        assertNull(MeterContext.current());
    }

    @Test
    @DisplayName("callWith 执行后自动恢复原上下文")
    void callWithRestores() {
        try (MeterContext outer = MeterContext.open("R_OUTER", "E_OUTER")) {
            MeterContext temporary = MeterContext.open("R_TEMP", "E_TEMP");
            // 先把临时上下文弹掉，再交给 callWith 激活
            temporary.close();
            assertSame(outer, MeterContext.current());

            String seen = MeterContext.callWith(temporary, () -> MeterContext.current().runId());
            assertEquals("R_TEMP", seen);
            assertSame(outer, MeterContext.current(), "callWith 结束后应恢复外层");
        }
        assertNull(MeterContext.current());
    }

    @Test
    @DisplayName("callWith 在 body 抛异常时也恢复上下文")
    void callWithRestoresOnException() {
        try (MeterContext outer = MeterContext.open("R_OUTER", "E_OUTER")) {
            MeterContext temporary = MeterContext.open("R_TEMP", "E_TEMP");
            temporary.close();

            assertThrows(IllegalStateException.class, () ->
                    MeterContext.callWith(temporary, () -> {
                        throw new IllegalStateException("boom");
                    }));
            assertSame(outer, MeterContext.current());
        }
        assertNull(MeterContext.current());
    }

    @Test
    @DisplayName("子线程可继承父线程上下文（InheritableThreadLocal 兜底）")
    void childThreadInheritsContext() throws InterruptedException {
        try (MeterContext ctx = MeterContext.open("RUN_PARENT", "EXP_PARENT", "CONV_PARENT", 0)) {
            final String[] childSeen = new String[1];
            Thread child = new Thread(() -> {
                MeterContext childCtx = MeterContext.current();
                childSeen[0] = childCtx == null ? null : childCtx.runId();
                // 子线程 close 不影响父线程栈
                if (childCtx != null) {
                    childCtx.close();
                }
            });
            child.start();
            child.join();

            assertEquals("RUN_PARENT", childSeen[0], "子线程应继承父线程上下文");
            assertEquals("RUN_PARENT", MeterContext.current().runId(), "父线程上下文不受子线程影响");
        }
        assertNull(MeterContext.current());
    }

    @Test
    @DisplayName("open(runId, experimentId) 便捷方法 conversationId/turnIndex 为 null")
    void convenienceOpen() {
        try (MeterContext ctx = MeterContext.open("RUN_X", "EXP_Y")) {
            assertEquals("RUN_X", ctx.runId());
            assertEquals("EXP_Y", ctx.experimentId());
            assertNull(ctx.conversationId());
            assertNull(ctx.turnIndex());
        }
    }
}
