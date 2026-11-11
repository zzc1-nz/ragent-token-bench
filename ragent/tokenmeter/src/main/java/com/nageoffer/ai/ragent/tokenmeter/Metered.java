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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 计量埋点注解（对应契约 4.10）。
 * <p>
 * 用途：标记「需要计量」的方法，供模块 B 在 Spring 环境里用 {@code @Aspect} 切面读取，
 * 再转调 {@link TokenMeter#measure}。
 * <p>
 * 模块 A 本身**不引入 spring-aop**（保持零依赖），因此这里只提供注解元数据，不提供切面实现。
 * 纯 Java 场景下也可自行读取该注解做反射代理。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Metered {

    /**
     * 被计量方法所属的环节。
     *
     * @return 环节
     */
    MeterStage stage();

    /**
     * 模型 id；空串表示由切面在运行时决定（例如从方法参数或上下文推断）。
     *
     * @return 模型 id，默认空串
     */
    String modelId() default "";
}
