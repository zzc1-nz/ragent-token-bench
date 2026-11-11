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

import java.util.List;

/**
 * 启发式 Token 估算器（对应契约 4.4）。
 * <p>
 * 用途：在无 provider usage 时给出可复现的 token 估算值。
 * <p>
 * 算法与 Ragent 的 {@code HeuristicTokenCounterService} **逐行等价**（契约 4.4 强制要求，
 * bench 会用它对齐真实 usage 的偏差率）：
 * <ol>
 *   <li>遍历字符，跳过所有空白字符（{@link Character#isWhitespace(char)}）</li>
 *   <li>按码点分类计数：ASCII（{@code ch <= 0x7F}）/ CJK（含假名、谚文等）/ 其他</li>
 *   <li>ASCII 每 4 字符 ≈ 1 token，向上取整：{@code (ascii + 3) / 4}</li>
 *   <li>CJK 每字 = 1 token</li>
 *   <li>其他字符每 2 字符 ≈ 1 token，向上取整：{@code (other + 1) / 2}</li>
 *   <li>总计最少为 1（有非空白内容时）；空白或空串返回 0</li>
 * </ol>
 * <p>
 * 线程安全：无状态，可复用为单例（{@link #INSTANCE}）。
 */
public final class HeuristicTokenEstimator implements TokenEstimator {

    /**
     * 共享无状态实例。
     */
    public static final HeuristicTokenEstimator INSTANCE = new HeuristicTokenEstimator();

    /**
     * 构造估算器（无状态，可自由创建）。
     */
    public HeuristicTokenEstimator() {
    }

    /**
     * 估算单段文本的 token 数。
     * <p>
     * 与上游一致：null / 空串 / **全空白** 均返回 0
     * （上游用 {@code StringUtils.hasText} 判定，该工具对全空白返回 false）。
     *
     * @param text 文本，可为 null 或空串
     * @return 估算 token 数；null / 空串 / 全空白返回 0
     */
    @Override
    public int estimate(String text) {
        if (!hasText(text)) {
            return 0;
        }

        int asciiCount = 0;
        int cjkCount = 0;
        int otherCount = 0;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            if (ch <= 0x7F) {
                asciiCount++;
            } else if (isCjk(ch)) {
                cjkCount++;
            } else {
                otherCount++;
            }
        }

        int asciiTokens = (asciiCount + 3) / 4; // 英文等按 4 字符约 1 token
        int otherTokens = (otherCount + 1) / 2; // 其他字符按 2 字符约 1 token
        int total = asciiTokens + cjkCount + otherTokens;
        return Math.max(total, 1);
    }

    /**
     * 估算多段文本的 token 总数（按拼接后文本估算）。
     *
     * @param roleTextPairs 文本片段列表，可为 null
     * @return 估算 token 总数；列表为空时返回 0
     */
    @Override
    public int estimateMessages(List<String> roleTextPairs) {
        if (roleTextPairs == null || roleTextPairs.isEmpty()) {
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        for (String piece : roleTextPairs) {
            if (piece != null) {
                sb.append(piece);
            }
        }
        return estimate(sb.toString());
    }

    /**
     * 判断字符是否属于 CJK / 假名 / 谚文等「一字约一 token」的字符块。
     * <p>
     * 与上游实现保持完全相同的字符块集合，保证估算结果一致。
     *
     * @param ch 待判断字符
     * @return 属于则 true
     */
    private boolean isCjk(char ch) {        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT
                || block == Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO;
    }

    /**
     * 判断文本是否含有非空白内容（等价于 Spring 的 {@code StringUtils.hasText}，但不引入 Spring 依赖）。
     *
     * @param text 文本
     * @return 含至少一个非空白字符则 true
     */
    private static boolean hasText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
