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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProviderReportedEstimator} 单测。
 * <p>
 * 覆盖点：直接采用 provider 上报的 usage、负数归零、getter 与总 token、不可变相等性。
 */
class ProviderReportedEstimatorTest {

    @Test
    @DisplayName("直接返回 provider 上报的输入 token，忽略文本内容")
    void returnsReportedTokens() {
        ProviderReportedEstimator estimator = new ProviderReportedEstimator(1200, 340);

        assertEquals(1200, estimator.estimate("任意文本"));
        assertEquals(1200, estimator.estimate(null));
        assertEquals(1200, estimator.estimate(""));
        assertEquals(1200, estimator.estimateMessages(List.of("a", "b")));
        assertEquals(1200, estimator.estimateMessages(null));
    }

    @Test
    @DisplayName("上报值 getter 与总 token")
    void accessors() {
        ProviderReportedEstimator estimator = new ProviderReportedEstimator(1200, 340);
        assertEquals(1200, estimator.reportedInputTokens());
        assertEquals(340, estimator.reportedOutputTokens());
        assertEquals(1540, estimator.reportedTotalTokens());
    }

    @Test
    @DisplayName("负数上报值归零")
    void negativeClamped() {
        ProviderReportedEstimator estimator = new ProviderReportedEstimator(-100, -50);
        assertEquals(0, estimator.reportedInputTokens());
        assertEquals(0, estimator.reportedOutputTokens());
        assertEquals(0, estimator.reportedTotalTokens());
        assertEquals(0, estimator.estimate("x"));
    }

    @Test
    @DisplayName("值相等的两个估算器 equals 相等")
    void equalsByValue() {
        ProviderReportedEstimator a = new ProviderReportedEstimator(10, 20);
        ProviderReportedEstimator b = new ProviderReportedEstimator(10, 20);
        ProviderReportedEstimator c = new ProviderReportedEstimator(10, 21);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertTrue(a.toString().contains("input=10"));
    }
}
