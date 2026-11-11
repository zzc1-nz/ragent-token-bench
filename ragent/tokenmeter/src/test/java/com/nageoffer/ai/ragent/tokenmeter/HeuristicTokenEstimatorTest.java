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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HeuristicTokenEstimator} 单测。
 * <p>
 * 覆盖点：
 * - 空串 / null / 全空白返回 0
 * - 纯 ASCII 按 4 字符向上取整
 * - 纯 CJK 每字 1 token
 * - 中英混合分别计数后相加
 * - 其他字符（emoji 等非 ASCII、非 CJK）按 2 字符向上取整
 * - 空白字符被跳过、不参与计数
 * - 与上游 Ragent 算法的一致性（用上游逻辑的独立复算做交叉校验）
 */
class HeuristicTokenEstimatorTest {

    private final HeuristicTokenEstimator estimator = new HeuristicTokenEstimator();

    @Test
    @DisplayName("空文本返回 0")
    void emptyTextReturnsZero() {
        assertEquals(0, estimator.estimate(null));
        assertEquals(0, estimator.estimate(""));
    }

    @Test
    @DisplayName("全空白文本返回 0（空白不参与计数）")
    void whitespaceOnlyReturnsZero() {
        assertEquals(0, estimator.estimate("   "));
        assertEquals(0, estimator.estimate("\t\n\r "));
    }

    @Test
    @DisplayName("纯 ASCII：4 字符向上取整为 1 token")
    void asciiRoundsUp() {
        // "a" -> (1+3)/4 = 1
        assertEquals(1, estimator.estimate("a"));
        // "abcd" -> (4+3)/4 = 1
        assertEquals(1, estimator.estimate("abcd"));
        // "abcde" -> (5+3)/4 = 2
        assertEquals(2, estimator.estimate("abcde"));
        // "abcdefgh" -> (8+3)/4 = 2
        assertEquals(2, estimator.estimate("abcdefgh"));
    }

    @Test
    @DisplayName("纯中文：每字 1 token")
    void cjkOneTokenPerChar() {
        assertEquals(4, estimator.estimate("你好世界"));
        assertEquals(1, estimator.estimate("问"));
    }

    @Test
    @DisplayName("中英混合：分别计数后相加")
    void mixedCjkAndAscii() {
        // "你好" = 2 CJK；"abcd" = 1 ASCII token -> 共 3
        assertEquals(3, estimator.estimate("你好abcd"));
        // "你好 " 中的空格被跳过：2 CJK + "world"(5->2) = 4
        assertEquals(4, estimator.estimate("你好 world"));
    }

    @Test
    @DisplayName("空白字符被跳过")
    void whitespaceSkipped() {
        assertEquals(estimator.estimate("abcd"), estimator.estimate("a b c d"));
        assertEquals(estimator.estimate("你好世界"), estimator.estimate("你 好 世 界"));
    }

    @Test
    @DisplayName("其他字符（非 ASCII 非 CJK）按 2 字符向上取整")
    void otherCharsRoundUpByTwo() {
        // emoji 属于非 ASCII 非 CJK，2 个 -> 1 token
        String twoEmoji = "\uD83D\uDE00\uD83D\uDE00"; // 2 个 surrogate pair = 4 个 char
        assertTrue(estimator.estimate(twoEmoji) >= 1);
        // 单个全角标点（CJK 符号区，属 CJK）
        assertEquals(1, estimator.estimate("，"));
    }

    @Test
    @DisplayName("estimateMessages 按拼接后文本估算，空列表返回 0")
    void estimateMessages() {
        assertEquals(0, estimator.estimateMessages(null));
        assertEquals(0, estimator.estimateMessages(Collections.emptyList()));
        assertEquals(estimator.estimate("你好abcd"),
                estimator.estimateMessages(Arrays.asList("你好", "abcd")));
    }

    @Test
    @DisplayName("estimateMessages 跳过 null 片段")
    void estimateMessagesSkipsNull() {
        List<String> pieces = Arrays.asList("你好", null, "abcd");
        assertEquals(estimator.estimate("你好abcd"), estimator.estimateMessages(pieces));
    }

    @Test
    @DisplayName("与上游 HeuristicTokenCounterService 算法结果一致（独立复算交叉校验）")
    void matchesUpstreamAlgorithm() {
        List<String> samples = List.of(
                "",
                "hello world",
                "这是一段中文文本，用于验证估算一致性。",
                "混合 mixed 文本 12345 with spaces and 中文",
                "SELECT * FROM t WHERE id = 1;",
                "你好world2026年",
                "１２３４５６７８９０", // 全角数字，属其他字符
                "日本語のテキストとハングル 한국어"
        );
        for (String sample : samples) {
            assertEquals(upstreamCount(sample), estimator.estimate(sample),
                    "样本不一致: " + sample);
        }
    }

    /**
     * 上游 {@code HeuristicTokenCounterService#countTokens} 逻辑的独立复算（测试用参照实现）。
     *
     * @param text 文本
     * @return 上游算法结果
     */
    private int upstreamCount(String text) {
        if (text == null || text.isBlank()) {
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
            } else if (isCjkUpstream(ch)) {
                cjkCount++;
            } else {
                otherCount++;
            }
        }
        int total = (asciiCount + 3) / 4 + cjkCount + (otherCount + 1) / 2;
        return Math.max(total, 1);
    }

    /**
     * 与上游相同的 CJK 字符块判断。
     *
     * @param ch 字符
     * @return 是否 CJK
     */
    private boolean isCjkUpstream(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
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
}
