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

import java.util.ArrayList;
import java.util.List;

/**
 * 特征哈希向量器（feature hashing / hashing trick）
 * <p>
 * 为什么不用随机向量：基准测试要对比「仅向量 vs 多路融合」的召回质量，
 * 如果向量是随机的，向量通道召回等于乱序，融合策略的收益就无从谈起，
 * 整个检索 A/B 对比会变成噪声。
 * <p>
 * 特征哈希给出的是**真实反映文本重叠度的向量**：
 * <ul>
 *   <li>中文按 1-gram 与 2-gram 切，英文/数字按词切</li>
 *   <li>每个特征哈希到固定维度的某一维，按 TF 累加后 L2 归一化</li>
 *   <li>余弦相似度因此正比于两段文本的 n-gram 重叠 —— 这是真实可用的稀疏检索语义</li>
 * </ul>
 * 维度取 1536（与 Ragent 默认 {@code rag.default.dimension} 一致），
 * 冲突率对 200 篇文档 × 每篇 3~6 块的规模完全可接受。
 */
public final class HashingEmbedder {

    private final int dimension;

    public HashingEmbedder(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension 必须 > 0，实际：" + dimension);
        }
        this.dimension = dimension;
    }

    public int dimension() {
        return dimension;
    }

    /**
     * 批量向量化
     */
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts == null ? 0 : texts.size());
        if (texts == null) {
            return out;
        }
        for (String text : texts) {
            out.add(embed(text));
        }
        return out;
    }

    /**
     * 单条向量化：TF 累加 + L2 归一化
     */
    public float[] embed(String text) {
        float[] vec = new float[dimension];
        if (text == null || text.isEmpty()) {
            return vec;
        }
        for (String feature : features(text)) {
            int idx = bucketOf(feature);
            vec[idx] += 1.0f;
        }
        return l2Normalize(vec);
    }

    /**
     * 余弦相似度：两向量都已 L2 归一化，点积即余弦
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }

    // ==================== 特征抽取 ====================

    /**
     * 抽取特征：中文 1-gram + 2-gram，英文数字按词
     * <p>
     * 中文加 2-gram 是关键：单字重叠太泛（"的""了"到处都有），
     * 双字词才能区分"保修"与"保险"这类语义差异。
     */
    private List<String> features(String text) {
        List<String> out = new ArrayList<>(text.length() * 2);
        StringBuilder asciiWord = new StringBuilder();
        int prevCjk = -1;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isCjk(ch)) {
                if (asciiWord.length() > 0) {
                    out.add("w:" + asciiWord);
                    asciiWord.setLength(0);
                }
                out.add("c1:" + ch);
                if (prevCjk >= 0) {
                    out.add("c2:" + (char) prevCjk + ch);
                }
                prevCjk = ch;
            } else if (Character.isLetterOrDigit(ch)) {
                prevCjk = -1;
                asciiWord.append(Character.toLowerCase(ch));
            } else {
                prevCjk = -1;
                if (asciiWord.length() > 0) {
                    out.add("w:" + asciiWord);
                    asciiWord.setLength(0);
                }
            }
        }
        if (asciiWord.length() > 0) {
            out.add("w:" + asciiWord);
        }
        return out;
    }

    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA;
    }

    /**
     * 特征 → 桶下标：FNV-1a 哈希，稳定且分布均匀
     */
    private int bucketOf(String feature) {
        int hash = 0x811C9DC5;
        for (int i = 0; i < feature.length(); i++) {
            hash ^= feature.charAt(i);
            hash *= 0x01000193;
        }
        return Math.floorMod(hash, dimension);
    }

    private float[] l2Normalize(float[] vec) {
        double sumSq = 0.0;
        for (float v : vec) {
            sumSq += (double) v * v;
        }
        if (sumSq <= 0.0) {
            return vec;
        }
        float norm = (float) Math.sqrt(sumSq);
        for (int i = 0; i < vec.length; i++) {
            vec[i] /= norm;
        }
        return vec;
    }
}
