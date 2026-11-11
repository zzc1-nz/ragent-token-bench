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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * JSONL 计量 sink（对应契约 4.9）。
 * <p>
 * 用途：把计量事件追加写入 UTF-8 的 jsonl 文件（每行一个 {@link TokenUsage} JSON，
 * 字段与契约 4.2 完全一致），是 bench 产出 {@code data/results/<runId>/events.jsonl} 的落地实现。
 * <p>
 * 实现要点：
 * - 追加写（{@link StandardOpenOption#APPEND}），父目录不存在时自动创建
 * - 写入用 {@code synchronized} 块串行化，保证多线程下不产生交错的半行
 * - UTF-8 编码，行尾统一使用 {@code \n}
 * - 每个事件写入后立即 flush：bench 可能在进程被中断前需要完整事件文件，
 * 且事件量级（百万 token 对应数万事件）下 flush 开销可接受
 * - {@link #snapshot()} 从文件读回全部事件（复用 {@link TokenUsageJson#readAll}）
 * <p>
 * 线程安全：是。
 */
public final class JsonlMeterSink implements MeterSink {

    private static final Logger log = LoggerFactory.getLogger(JsonlMeterSink.class);

    private final Path file;
    private final Object lock = new Object();
    private BufferedWriter writer;
    private boolean closed;

    /**
     * 构造 jsonl sink（不立即打开文件，首次写入时懒加载）。
     *
     * @param file 目标文件路径，不能为 null
     * @throws IllegalArgumentException file 为 null 时抛出
     */
    public JsonlMeterSink(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("JsonlMeterSink.file 不能为 null");
        }
        this.file = file;
    }

    /**
     * 目标文件路径。
     *
     * @return 文件路径
     */
    public Path file() {
        return file;
    }

    /**
     * 接收一条事件：序列化为单行 JSON 并追加写入文件。
     *
     * @param usage 计量事件，不能为 null
     * @throws IllegalArgumentException usage 为 null 时抛出
     * @throws UncheckedIOException     IO 失败时抛出
     */
    @Override
    public void accept(TokenUsage usage) {
        if (usage == null) {
            throw new IllegalArgumentException("JsonlMeterSink.accept 的入参不能为 null");
        }
        String line = TokenUsageJson.toJson(usage);
        synchronized (lock) {
            try {
                ensureOpen();
                writer.write(line);
                writer.write('\n');
                writer.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("写入 jsonl 失败: " + file, e);
            }
        }
    }

    /**
     * 从文件读回全部事件（跳过空行）。
     *
     * @return 事件列表（保持文件顺序）
     * @throws UncheckedIOException 读取失败时抛出
     */
    @Override
    public List<TokenUsage> snapshot() {
        synchronized (lock) {
            flushWriter();
            return new ArrayList<>(TokenUsageJson.readAll(file));
        }
    }

    /**
     * 刷出文件缓冲。
     *
     * @throws UncheckedIOException IO 失败时抛出
     */
    @Override
    public void flush() {
        synchronized (lock) {
            flushWriter();
        }
    }

    /**
     * 关闭底层 writer（关闭后再写入会自动重新打开并继续追加）。
     * <p>
     * 实现 {@link AutoCloseable}，推荐在 bench 结束时调用；未调用时由 JVM 退出兜底 flush。
     */
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    log.warn("关闭 JsonlMeterSink 失败: {}", file, e);
                } finally {
                    writer = null;
                }
            }
        }
    }

    /**
     * 懒加载 writer；文件已关闭后再次写入会重新以追加模式打开。
     *
     * @throws IOException 打开失败时抛出
     */
    private void ensureOpen() throws IOException {
        if (writer != null) {
            return;
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        closed = false;
    }

    /**
     * 刷出 writer 缓冲（writer 未打开时为空操作）。
     *
     * @throws UncheckedIOException IO 失败时抛出
     */
    private void flushWriter() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("flush jsonl 失败: " + file, e);
        }
    }
}
