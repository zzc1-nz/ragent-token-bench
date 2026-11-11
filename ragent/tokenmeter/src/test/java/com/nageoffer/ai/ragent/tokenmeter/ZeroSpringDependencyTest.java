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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 零 Spring 依赖守卫测试。
 * <p>
 * 用途：契约 3 要求 tokenmeter「零第三方依赖（仅 JDK + slf4j-api + jackson）」，
 * 且必须能在纯 Java main 中运行。本测试扫描编译产物的字节码常量池，
 * 断言主代码中不出现任何 {@code org/springframework} 引用 ——
 * 一旦有人误加 Spring 注解/工具类，本测试立刻失败。
 * <p>
 * 注意：父 POM 以 provided 方式引入 Spring 仅供编译期使用，本测试校验的是
 * **源码/字节码层面**确实没有用到它。
 */
class ZeroSpringDependencyTest {

    @Test
    @DisplayName("主代码字节码中不含任何 Spring 引用（零 Spring 依赖守卫）")
    void mainClassesHaveNoSpringReferences() throws IOException {
        Path classesDir = resolveMainClassesDir();
        assertTrue(Files.isDirectory(classesDir), "未找到编译产物目录: " + classesDir);

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classesDir)) {
            List<Path> classFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .toList();
            assertTrue(!classFiles.isEmpty(), "编译产物目录中没有 .class 文件: " + classesDir);

            for (Path classFile : classFiles) {
                String content = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
                if (content.contains("org/springframework")) {
                    violations.add(classesDir.relativize(classFile).toString());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "以下类引用了 Spring，违反零 Spring 依赖约束: " + violations);
    }

    /**
     * 定位主代码编译产物目录（与当前类所在 target 目录同级的 classes）。
     *
     * @return {@code target/classes} 路径
     */
    private Path resolveMainClassesDir() {
        try {
            // 本测试类位于 target/test-classes/...，主代码在同级 target/classes
            Path testClasses = Path.of(
                    ZeroSpringDependencyTest.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
            return testClasses.getParent().resolve("classes");
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("无法定位 target/classes 目录", e));
        }
    }
}
