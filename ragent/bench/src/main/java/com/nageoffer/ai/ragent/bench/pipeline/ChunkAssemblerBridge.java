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

import com.nageoffer.ai.ragent.core.chunk.model.Chunk;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkAssembler;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkDraft;
import com.nageoffer.ai.ragent.core.chunk.model.ChunkMetadata;
import com.nageoffer.ai.ragent.core.parser.model.Provenance;

/**
 * 分块装配桥：把纯文本片段包装成 Ragent 的 {@link Chunk}
 * <p>
 * 直接复用 {@link ChunkAssembler} 而不是手搓 Chunk 构造，是为了让固定大小分块产出的块
 * 与语义分块在字段语义上完全一致（content / embeddingText / metadata / chunkId 生成规则），
 * 下游 embedding 与检索无需区分来源。
 */
final class ChunkAssemblerBridge {

    private ChunkAssemblerBridge() {
    }

    /**
     * 装配单块：写入来源文件元数据，便于评测时回溯到 docId
     *
     * @param index   块序号
     * @param content 块正文
     * @param docId   来源文档 ID
     */
    static Chunk assemble(int index, String content, String docId) {
        ChunkMetadata metadata = ChunkMetadata.builder()
                .provenance(Provenance.ofFile(docId + ".md"))
                .build();
        return ChunkAssembler.assemble(index, ChunkDraft.of(content, metadata));
    }
}
