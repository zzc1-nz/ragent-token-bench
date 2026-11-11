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

package com.nageoffer.ai.ragent.bench.run;

import com.nageoffer.ai.ragent.bench.dataset.BenchQuery;

import java.util.List;

/**
 * 一轮对话的执行结果
 *
 * @param query            原问题
 * @param retrievedDocIds  实际检索到的文档 ID（按精排后名次）
 * @param answer           生成的答案
 * @param qualityScore     [0,1] 质量分
 * @param fusedCount       融合后候选数
 * @param rerankedCount    精排后进入上下文的条数
 */
public record TurnResult(
        BenchQuery query,
        List<String> retrievedDocIds,
        String answer,
        double qualityScore,
        int fusedCount,
        int rerankedCount
) {

    public TurnResult {
        retrievedDocIds = retrievedDocIds == null ? List.of() : List.copyOf(retrievedDocIds);
        answer = answer == null ? "" : answer;
    }
}
