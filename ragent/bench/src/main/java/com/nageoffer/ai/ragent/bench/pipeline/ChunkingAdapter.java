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

package com.nageoffer.ai.ragent.bench.pipeline;

import com.nageoffer.ai.ragent.bench.dataset.BenchDocument;
import com.nageoffer.ai.ragent.bench.strategy.ChunkStrategy;
import com.nageoffer.ai.ragent.core.chunk.ChunkingService;
import com.nageoffer.ai.ragent.core.chunk.blockaware.BlockAwareChunkerDispatcher;
import com.nageoffer.ai.ragent.core.chunk.blockaware.BlockChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ChunkPacker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.CodeChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.HeadingChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.HeadingHandler;
import com.nageoffer.ai.ragent.core.chunk.blockaware.HtmlTableChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ImageChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ListChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.ParagraphChunker;
import com.nageoffer.ai.ragent.core.chunk.blockaware.TableChunker;
import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkBudget;
import com.nageoffer.ai.ragent.core.chunk.text.TextSplitter;
import com.nageoffer.ai.ragent.core.parser.MarkdownDocumentParser;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 分块适配器：把「策略」翻译成 Ragent 真实的 {@link ChunkingService} 调用
 * <p>
 * 这是本平台"不重写 Ragent、只做增量扩展"的关键落点：
 * 两种策略都不是自己实现的分块器，而是把同一批 Markdown 交给 Ragent 真实的解析器与分块器，
 * 只改喂给它们的预算与入口：
 * <ul>
 *   <li>{@link ChunkStrategy#SEMANTIC}：走 {@link BlockAwareChunkerDispatcher}，
 *       按标题/段落/列表/表格的 Block 类型分发，再按章节打包</li>
 *   <li>{@link ChunkStrategy#FIXED_SIZE}：绕过 Block 感知，直接对纯文本做
 *       {@link TextSplitter#split} 滑窗，模拟"最朴素的分块"</li>
 * </ul>
 * 两条路径产出的都是 Ragent 的 {@link Chunk}，下游 embedding 与检索代码完全共用。
 */
public final class ChunkingAdapter {

    /**
     * 表格每块数据行上限：取 Ragent 默认值，保证表格不会被切碎
     */
    private static final int ROWS_PER_CHUNK = 50;

    /**
     * 容差倍数：允许整节略超预算以保住语义完整性，取 Ragent 默认值 3
     */
    private static final int TOLERANCE_FACTOR = 3;

    private final MarkdownDocumentParser parser;
    private final ChunkingService semanticChunkingService;

    public ChunkingAdapter() {
        this.parser = new MarkdownDocumentParser();
        this.semanticChunkingService = new ChunkingService(buildDispatcher());
    }

    /**
     * 装配 Block 感知分块器：与 Spring 容器里的 bean 图等价，只是这里手工 new
     */
    private static BlockAwareChunkerDispatcher buildDispatcher() {
        List<BlockChunker<?>> chunkers = new ArrayList<>();
        chunkers.add(new HeadingChunker());
        chunkers.add(new ParagraphChunker());
        chunkers.add(new ListChunker());
        chunkers.add(new CodeChunker());
        chunkers.add(new TableChunker());
        chunkers.add(new HtmlTableChunker());
        chunkers.add(new ImageChunker());
        return new BlockAwareChunkerDispatcher(new HeadingHandler(), new ChunkPacker(), chunkers);
    }

    /**
     * 按策略切分一篇文档
     *
     * @param doc       文档
     * @param strategy  分块策略
     * @param chunkSize 目标块大小（字符）
     * @param overlap   重叠字符（仅 FIXED_SIZE 生效）
     * @return Ragent 的 Chunk 列表，序号从 0 递增
     */
    public List<Chunk> chunk(BenchDocument doc, ChunkStrategy strategy, int chunkSize, int overlap) {
        String markdown = doc.toMarkdown();
        return switch (strategy) {
            case SEMANTIC -> chunkSemantic(markdown, doc, chunkSize, overlap);
            case FIXED_SIZE -> chunkFixed(markdown, doc, chunkSize, overlap);
        };
    }

    /**
     * 语义分块：真实走 Ragent 的解析器 + Block 感知分发器
     */
    private List<Chunk> chunkSemantic(String markdown, BenchDocument doc, int chunkSize, int overlap) {
        ParsedDocument parsed = parser.parseStructured(
                markdown.getBytes(StandardCharsets.UTF_8),
                "text/markdown",
                Map.of("sourceFile", doc.docId() + ".md"));
        List<Block> blocks = parsed.blocks();
        if (blocks.isEmpty()) {
            return List.of();
        }
        ChunkBudget budget = new ChunkBudget(chunkSize, overlap, ROWS_PER_CHUNK, TOLERANCE_FACTOR);
        return semanticChunkingService.chunk(blocks, budget);
    }

    /**
     * 固定大小分块：绕过 Block 感知，直接对纯文本滑窗
     * <p>
     * 仍然调用 Ragent 的 {@link TextSplitter}，因此"切口落在自然边界"这点与语义分块一致，
     * 差异只在于「是否有章节语义作为切分单位」——这正是要对比的自变量。
     */
    private List<Chunk> chunkFixed(String markdown, BenchDocument doc, int chunkSize, int overlap) {
        List<String> pieces = TextSplitter.split(markdown, chunkSize, overlap);
        if (pieces.isEmpty()) {
            return List.of();
        }
        List<Chunk> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            chunks.add(ChunkAssemblerBridge.assemble(i, pieces.get(i), doc.docId()));
        }
        return chunks;
    }
}
