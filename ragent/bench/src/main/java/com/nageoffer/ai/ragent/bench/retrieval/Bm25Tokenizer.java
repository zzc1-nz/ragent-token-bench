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
 * 中英文分词器：为 BM25 关键词检索提供词项
 * <p>
 * 不引入 IK 分词器依赖（那需要 ES 插件），这里用"中文二元组 + 英文单词"的经典折中方案：
 * 中文 2-gram 切分对 BM25 效果足够（相当于 bigram 索引），英文按空白与标点切词。
 * <p>
 * 与 {@link HashingEmbedder} 的特征抽取保持一致的切分逻辑，
 * 保证向量通道与关键词通道看到的是同一套词项，融合才有意义。
 */
public final class Bm25Tokenizer {

    private Bm25Tokenizer() {
    }

    /**
     * 分词：返回词项列表（保留重复，供词频统计）
     */
    public static List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder asciiWord = new StringBuilder();
        int prevCjk = -1;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isCjk(ch)) {
                flushAscii(tokens, asciiWord);
                tokens.add(String.valueOf(ch));
                if (prevCjk >= 0) {
                    tokens.add("" + (char) prevCjk + ch);
                }
                prevCjk = ch;
            } else if (Character.isLetterOrDigit(ch)) {
                prevCjk = -1;
                asciiWord.append(Character.toLowerCase(ch));
            } else {
                prevCjk = -1;
                flushAscii(tokens, asciiWord);
            }
        }
        flushAscii(tokens, asciiWord);
        return tokens;
    }

    private static void flushAscii(List<String> tokens, StringBuilder buf) {
        if (buf.length() > 0) {
            tokens.add(buf.toString());
            buf.setLength(0);
        }
    }

    private static boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA;
    }
}
