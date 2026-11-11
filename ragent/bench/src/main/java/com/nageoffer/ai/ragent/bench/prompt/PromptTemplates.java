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

package com.nageoffer.ai.ragent.bench.prompt;

/**
 * Ragent 真实提示词模板的复刻（用于让 token 计量贴近真实）
 * <p>
 * 为什么不在 bench 里直接读 Ragent 的 {@code prompt/*.st} 资源文件：
 * 那些模板依赖 Spring 的 {@code PromptTemplateLoader} 与运行时变量填充，
 * 在纯 Java main 里跑会带进一堆容器依赖。这里把**与 token 长度相关**的部分复刻出来：
 * 系统提示词正文、上下文包裹格式、引用规则追加。
 * <p>
 * 复刻原则：字数与结构贴近原模板，让「系统提示词 token」这一项不偏离真实值。
 * 若后续要与 Ragent 严格对齐，可把这些常量换成读取 classpath 资源 —— 接口不变。
 */
public final class PromptTemplates {

    private PromptTemplates() {
    }

    /**
     * 意图分类系统提示词（对应 Ragent {@code prompt/intent-classifier.st}）
     * <p>
     * 真实模板 4271 字节，核心是"给你一棵意图树，按问题选出命中的叶子节点并打分"。
     * 这里保留同样的任务描述密度，因为意图分类的输入 token 主要来自意图树本身（由运行器动态拼接）。
     */
    public static final String INTENT_CLASSIFIER = """
            你是意图识别助手。下面给你一棵意图树，请判断用户问题命中的叶子节点，并给出 0~1 的置信度分数。

            要求：
            1. 只输出 JSON，格式为 {"intents":[{"id":"节点ID","score":0.87}]}，不要输出任何解释文字。
            2. 最多返回 3 个命中节点，按分数从高到低排序。
            3. 若问题与任何知识库意图都不相关（闲聊、系统能力询问等），返回 {"intents":[]}。
            4. 分数必须反映真实置信度：明确命中给 0.7 以上，模糊相关给 0.35~0.6，不相关不要返回。
            5. 注意同义表达与行业简称，例如"扫地机"与"扫地机器人"、"保修"与"质保"应视为同一意图。

            意图树：
            """;

    /**
     * 问题改写系统提示词（对应 Ragent {@code prompt/user-question-rewrite.st}）
     * <p>
     * 真实模板 7012 字节，任务是"结合多轮历史消解指代 + 拆分子问题"。
     * 这里保留"输入消息结构 / 输出 JSON 格式 / 改写规则 / 拆分规则"四段，因为它们是 token 的主体。
     */
    public static final String QUERY_REWRITE = """
            你是查询改写助手。请结合对话历史，把用户的最新问题改写成一个自包含、可独立检索的问题，并在必要时拆分为多个子问题。

            消息结构说明：
            - 历史消息按时间升序排列，最后一条恒为当前用户问题。
            - 助手回答只保留最近两条，用于承接实体指代。

            改写规则：
            1. 消解代词与省略：把"它""这个""那个""第二步"等替换为历史中明确出现的实体或步骤名。
            2. 保留用户原意，不扩写、不臆测、不添加用户没提过的约束条件。
            3. 补全省略的主语与限定词，使问题脱离上下文也能被检索到。
            4. 不要回答问题，只做改写。

            拆分规则：
            1. 若问题包含多个并列诉求（用"以及""还有""分别"等连接），拆成独立子问题。
            2. 若问题只有一个诉求，子问题数组只放改写后的问题本身。
            3. 每个子问题都必须是完整问句。

            输出格式（只输出 JSON，不要 Markdown 代码块，不要解释）：
            {"rewrite":"改写后的问题","sub_questions":["子问题1","子问题2"]}
            """;

    /**
     * 答案生成系统提示词（对应 Ragent 的 RAG 回答模板）
     * <p>
     * 真实链路由 {@code RAGPromptService} 动态组装：基础回答规则 + 上下文包裹 + 引用规则。
     * 这里复刻"基础回答规则"部分。
     */
    public static final String ANSWER_BASE = """
            你是企业知识库问答助手。请严格依据下方提供的参考资料回答用户问题。

            回答规则：
            1. 只使用参考资料中的事实，不得引入资料之外的推测或常识补充。
            2. 若参考资料不足以回答，明确说明"当前资料未覆盖该问题"，不要编造。
            3. 回答要具体：涉及型号、价格、天数、时限、参数时，必须给出资料中的确切数值。
            4. 结构清晰：有多个要点时用有序列表；涉及对比时可用 Markdown 表格。
            5. 语气专业、简洁，不使用营销化表达。
            6. 引用规则：每段引用参考资料的内容，在段末用 [N](#cite-N) 标注来源编号。
            """;

    /**
     * 上下文包裹模板（对应 Ragent {@code prompt/context-format.st} 的 kb-doc-block）
     * <p>
     * 真实格式用 XML 标签包裹，这里保持同样结构 —— 标签本身也占 token，
     * 但更重要的是它让"多路融合后上下文更长"这个成本能被真实计量。
     */
    public static final String CONTEXT_BLOCK_TEMPLATE = """
            <content data-ragent-doc-id="%s">
            %s
            </content>
            """;

    /**
     * 质量评分系统提示词（LLM-as-judge，用于质量分的语义维度）
     */
    public static final String QUALITY_JUDGE = """
            你是答案质量评审。请对比"参考答案"与"模型答案"，从事实准确性与要点覆盖度两个维度打分。

            评分标准：
            - 0.9~1.0：关键事实全部正确且覆盖，无编造。
            - 0.7~0.9：关键事实基本正确，遗漏个别次要要点。
            - 0.4~0.7：部分事实正确，但有明显遗漏或轻微偏差。
            - 0.0~0.4：关键事实错误、遗漏严重，或答非所问。

            只输出 JSON：{"score":0.82,"reason":"简短理由"}
            """;
}
