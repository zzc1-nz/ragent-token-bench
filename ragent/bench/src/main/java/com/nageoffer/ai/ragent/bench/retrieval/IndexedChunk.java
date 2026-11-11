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

package com.nageoffer.ai.ragent.bench.retrieval;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;

/**
 * 索引里的一个块：向量 + 原文 + 归属
 * <p>
 * 直接复用 Ragent 的 {@link RetrievedChunk} 作为检索产物类型，
 * 这样检索层、融合层、精排层、提示词组装层用的都是 Ragent 原生类型，
 * 与真实 RAG 链路完全一致 —— 埋点数据才有可比性。
 */
public final class IndexedChunk {

    private final String chunkId;
    private final String docId;
    private final String docTitle;
    private final int chunkIndex;
    private final String text;
    private final float[] vector;

    public IndexedChunk(String chunkId, String docId, String docTitle, int chunkIndex,
                        String text, float[] vector) {
        this.chunkId = chunkId;
        this.docId = docId;
        this.docTitle = docTitle;
        this.chunkIndex = chunkIndex;
        this.text = text;
        this.vector = vector;
    }

    public String chunkId() {
        return chunkId;
    }

    public String docId() {
        return docId;
    }

    public String docTitle() {
        return docTitle;
    }

    public int chunkIndex() {
        return chunkIndex;
    }

    public String text() {
        return text;
    }

    public float[] vector() {
        return vector;
    }

    /**
     * 转成 Ragent 的检索命中结果，供融合/精排/上下文组装使用
     *
     * @param score 本次检索通道给出的相关度分数
     */
    public RetrievedChunk toRetrievedChunk(float score) {
        return RetrievedChunk.builder()
                .id(chunkId)
                .text(text)
                .score(score)
                .docId(docId)
                .docName(docTitle)
                .chunkIndex(chunkIndex)
                .collectionName("bench_default_store")
                .build();
    }
}
