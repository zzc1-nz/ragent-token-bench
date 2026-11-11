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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/**
 * 计量上下文（对应契约 4.3）。
 * <p>
 * 用途：在一次实验运行 / 一次问答请求的作用域内，隐式携带 runId、experimentId、
 * conversationId、turnIndex，使埋点方不必层层透传这些参数。
 * <p>
 * 实现要点：
 * - 使用 {@link ThreadLocal} 保存 {@link Deque} 栈，{@link #close()} 出栈恢复上一层，天然支持嵌套
 * - 额外用 {@link InheritableThreadLocal} 兜底：新建子线程可继承父线程的上下文快照
 * （注意：线程池复用线程不会重新继承，异步场景请由调用方显式 {@link #open} 传递，
 * 模块 A 不引入 TTL 依赖以保持零第三方依赖）
 * - 推荐用 try-with-resources：{@code try (MeterContext ctx = MeterContext.open(...)) { ... }}
 * <p>
 * 线程安全：每个线程持有独立的栈；{@link MeterContext} 实例本身被单个线程使用（可变字段仅在该线程内改）。
 */
public final class MeterContext implements AutoCloseable {

    /**
     * 线程内上下文栈。子线程首次访问时从父线程继承的快照初始化，之后与父线程互不影响。
     */
    private static final InheritableThreadLocal<Deque<MeterContext>> STACK =
            new InheritableThreadLocal<>() {
                @Override
                protected Deque<MeterContext> initialValue() {
                    return new ArrayDeque<>(4);
                }

                @Override
                protected Deque<MeterContext> childValue(Deque<MeterContext> parentValue) {
                    // 子线程继承父线程当前上下文的浅拷贝（MeterContext 可变字段不复用，
                    // 但栈元素本身只读，复制栈即可让子线程安全出栈）
                    return new ArrayDeque<>(parentValue);
                }
            };

    private final String runId;
    private final String experimentId;
    private String conversationId;
    private Integer turnIndex;

    private MeterContext(String runId, String experimentId, String conversationId, Integer turnIndex) {
        this.runId = runId;
        this.experimentId = experimentId;
        this.conversationId = conversationId;
        this.turnIndex = turnIndex;
    }

    /**
     * 打开一个计量上下文并压栈。
     * <p>
     * 使用后必须 {@link #close()}（推荐 try-with-resources），否则上下文会泄漏到后续调用。
     *
     * @param runId          实验运行 ID，可为 null
     * @param experimentId   策略组合 ID，可为 null
     * @param conversationId 会话 ID，可为 null（ETL 阶段）
     * @param turnIndex      对话轮次，可为 null（ETL 阶段）
     * @return 新建的上下文（已压栈）
     */
    public static MeterContext open(String runId, String experimentId,
                                    String conversationId, Integer turnIndex) {
        MeterContext ctx = new MeterContext(runId, experimentId, conversationId, turnIndex);
        STACK.get().push(ctx);
        return ctx;
    }

    /**
     * 打开一个只带 runId / experimentId 的上下文（ETL 阶段常用）。
     *
     * @param runId        实验运行 ID
     * @param experimentId 策略组合 ID
     * @return 新建的上下文（已压栈）
     */
    public static MeterContext open(String runId, String experimentId) {
        return open(runId, experimentId, null, null);
    }

    /**
     * 获取当前线程最内层的上下文。
     *
     * @return 当前上下文；未打开任何上下文时返回 null（调用方必须判空）
     */
    public static MeterContext current() {
        Deque<MeterContext> stack = STACK.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * 在指定上下文内执行一段逻辑，结束后自动恢复原上下文。
     *
     * @param ctx  要激活的上下文
     * @param body 待执行逻辑
     * @param <T>  返回值类型
     * @return body 的返回值
     */
    public static <T> T callWith(MeterContext ctx, Supplier<T> body) {
        Deque<MeterContext> stack = STACK.get();
        stack.push(ctx);
        try {
            return body.get();
        } finally {
            // 允许 ctx 并非栈顶（例如被误用），这里按对象移除，避免破坏栈结构
            if (stack.peek() == ctx) {
                stack.pop();
            } else {
                stack.remove(ctx);
            }
            if (stack.isEmpty()) {
                STACK.remove();
            }
        }
    }

    /**
     * 实验运行 ID。
     *
     * @return runId，可能为 null
     */
    public String runId() {
        return runId;
    }

    /**
     * 策略组合 ID。
     *
     * @return experimentId，可能为 null
     */
    public String experimentId() {
        return experimentId;
    }

    /**
     * 会话 ID。
     *
     * @return conversationId，可能为 null
     */
    public String conversationId() {
        return conversationId;
    }

    /**
     * 对话轮次。
     *
     * @return turnIndex，可能为 null
     */
    public Integer turnIndex() {
        return turnIndex;
    }

    /**
     * 允许中途绑定会话 ID（例如 ETL 完成后进入问答时才拿到会话）。
     *
     * @param id 会话 ID
     */
    public void setConversationId(String id) {
        this.conversationId = id;
    }

    /**
     * 允许中途绑定对话轮次。
     *
     * @param idx 轮次索引
     */
    public void setTurnIndex(Integer idx) {
        this.turnIndex = idx;
    }

    /**
     * 关闭上下文，恢复到上一层。
     * <p>
     * 幂等：重复 close 不会破坏栈（只会尝试移除自身）。
     */
    @Override
    public void close() {
        Deque<MeterContext> stack = STACK.get();
        if (stack.peek() == this) {
            stack.pop();
        } else {
            stack.remove(this);
        }
        if (stack.isEmpty()) {
            // 避免线程池线程长期持有空栈引用
            STACK.remove();
        }
    }
}
