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

package com.nageoffer.ai.ragent.bench.dataset;

/**
 * 基准语料中的一篇文档
 *
 * @param docId      业务文档 ID（如 {@code DOC_0001}），与 queries 的 referenceDocIds 对齐
 * @param title      文档标题
 * @param domain     领域：vacuum / finance / hr / tech / policy
 * @param text       Markdown 正文
 * @param chunksHint 预期分块数（生成数据集时按固定分块估算，仅作参考）
 */
public record BenchDocument(
        String docId,
        String title,
        String domain,
        String text,
        int chunksHint
) {

    public BenchDocument {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId 不能为空");
        }
        text = text == null ? "" : text;
    }

    /**
     * 拼成带标题的 Markdown，模拟真实文档入库时的解析输入
     */
    public String toMarkdown() {
        return "# " + title + "\n\n" + text;
    }
}
