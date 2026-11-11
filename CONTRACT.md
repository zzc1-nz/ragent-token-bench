# TokenBench 二次开发契约（所有成员必读）

> 本文件是 **唯一事实来源**。任何跨模块的接口、字段名、目录归属以本文件为准。
> 修改契约必须先告知 Lead，由 Lead 更新本文件。

---

## 1. 项目定位

基于开源项目 **Ragent**（`nageoffer/ragent`，Spring Boot 4.1 + React）做**增量扩展**，
构建「Token 消耗可观测的 Agentic RAG 基准测试平台」。

**不做的事**：不重写 Ragent、不改动其业务语义、不替换其架构。
**做的事**：新增两个 Maven 模块 + 在 Ragent 现有流水线上以装饰器方式埋点 + 新增前端仪表盘。

---

## 2. 目录与写权限（写作用域，互不重叠）

```
token-bench/
├── ragent/                       # 上游 Ragent 源码（Lead 负责其内改动）
│   ├── tokenmeter/               # ★新增模块 A：计量 SDK   → 成员 A
│   ├── bench/                    # ★新增模块 B：实验层+运行器 → 成员 B
│   └── (framework/infra-ai/rag/agent/bootstrap/...)  # 上游，仅 Lead 可改
├── dashboard/                    # ★新增：React 仪表盘 → 成员 C
├── data/                         # ★新增：语料/结果/报告 → 成员 D
├── charts/                       # 图表产物（Lead）
├── CONTRACT.md                   # 本文件（Lead）
└── README.md                     # 根 README（Lead + D 协作，D 只写 data/ 下的素材）
```

**铁律**：只写自己作用域内的文件。需要改别人作用域 → 发消息给 Lead。

---

## 3. Maven 模块坐标

| 模块 | groupId | artifactId | 说明 |
|---|---|---|---|
| A | `com.nageoffer.ai` | `tokenmeter` | 计量 SDK，**零第三方依赖**（仅 JDK + slf4j-api + jackson） |
| B | `com.nageoffer.ai` | `bench` | 实验层/运行器/报告，依赖 tokenmeter + ragent 的 `rag` 模块 |

根 `ragent/pom.xml` 的 `<modules>` 由 Lead 追加 `tokenmeter`、`bench`。
两个模块的 `<parent>` 都指向 `com.nageoffer.ai:ragent:0.0.1-SNAPSHOT`。

**构建命令（必须用这个，否则 Lombok 在 JDK25 下失效）**：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.2"
$mvn = "C:\Users\24657\Documents\deepseek-harness\default-workspace\_toolchain\apache-maven-3.9.9\bin\mvn.cmd"
$st  = "C:\Users\24657\Documents\deepseek-harness\default-workspace\_toolchain\settings-workspace.xml"
cd C:\Users\24657\Documents\deepseek-harness\default-workspace\token-bench\ragent
& $mvn -s $st -B "-Dmaven.compiler.proc=full" "-Dmaven.test.skip=true" "-Dspotless.check.skip=true" install
```

> `-Dmaven.compiler.proc=full` 是**必须**的：JDK 25 默认关闭隐式注解处理，Lombok 会静默失效。
> `settings-workspace.xml` 把本地仓库指向 `_toolchain/m2repo`（沙箱外写不进去）。

---

## 4. 计量核心模型（模块 A 提供，B/C/D 依赖）

### 4.1 环节枚举 `MeterStage`

```java
package com.nageoffer.ai.ragent.tokenmeter;

public enum MeterStage {
    // ---- ETL / 文档入库 ----
    PARSE,          // 文档解析（PDF/DOCX/MD → 文本块）
    CHUNK,          // 分块
    EMBED,          // 向量化（含批量）
    // ---- 在线问答 ----
    INTENT,         // 意图识别/分类
    REWRITE,        // 问题改写 + 子问题拆分
    RETRIEVE_VECTOR,    // 向量召回
    RETRIEVE_KEYWORD,   // 关键词召回
    RETRIEVE_GRAPH,     // 图谱召回
    RETRIEVE_WEB,       // 联网搜索
    FUSION,         // 多路融合（RRF，本地计算，token=0，用于耗时观测）
    RERANK,         // 精排
    GENERATE,       // 答案生成
    JUDGE,          // 质量评分（LLM-as-judge）
    SUMMARY,        // 会话摘要 / 记忆压缩
    OTHER
}
```

### 4.2 单次计量事件 `TokenUsage`（不可变）

```java
public record TokenUsage(
    String  eventId,        // UUID
    String  runId,          // 实验运行 ID（一次 benchmark run）
    String  experimentId,   // 策略组合 ID，如 "chunk=SEMANTIC|retrieval=FUSION|routing=INTENT_ROUTED"
    String  conversationId, // 会话 ID（ETL 阶段为 null）
    Integer turnIndex,      // 第几轮（ETL 阶段为 null）
    MeterStage stage,
    String  modelId,        // 实际命中的模型 id，如 "deepseek-flash"；本地计算填 "local"
    String  provider,       // deepseek / bailian / local / none
    int     inputTokens,
    int     outputTokens,
    int     cachedInputTokens,  // 其中命中缓存、按折扣计价的输入 token
    boolean cacheHit,           // 整次调用是否完全命中响应缓存
    long    latencyMs,
    double  costUsd,            // 已按定价表算好
    long    timestampMs,
    String  traceTag            // 可选备注（如 "batch:32"）
) {
    public int totalTokens() { return inputTokens + outputTokens; }
}
```

**构造用 builder**（record 字段多，提供 `TokenUsage.builder()...build()`）。

### 4.3 上下文 `MeterContext`

```java
public final class MeterContext implements AutoCloseable {
    public static MeterContext open(String runId, String experimentId,
                                    String conversationId, Integer turnIndex);
    public static MeterContext current();            // 可能返回 null
    public String runId();
    public String experimentId();
    public String conversationId();
    public Integer turnIndex();
    public void setConversationId(String id);        // 允许中途绑定
    public void setTurnIndex(Integer idx);
    @Override public void close();                    // 恢复上一层（支持嵌套）
}
```

实现用 `ThreadLocal<Deque<MeterContext>>` + `InheritableThreadLocal` 兜底；
异步场景由调用方显式 `open()` 传递（不引入 TTL 依赖，模块 A 保持零依赖）。

### 4.4 Token 估算 `TokenEstimator`

```java
public interface TokenEstimator {
    int estimate(String text);
    int estimateMessages(List<String> roleTextPairs); // 简化：直接传拼接后的文本
}
public final class HeuristicTokenEstimator implements TokenEstimator { ... }
```

算法沿用 Ragent 的 `HeuristicTokenCounterService` 思路（CJK 1字≈1token，ASCII 4字符≈1token），
**必须与 Ragent 现有实现结果一致**（B 会拿它对齐真实 usage 的偏差率）。
另提供 `ProviderReportedEstimator`：直接吃 provider 返回的 `usage` 字段。

### 4.5 定价 `PricingTable`

```java
public record ModelPrice(String modelId, double inputPerMillionUsd,
                         double outputPerMillionUsd, double cachedInputPerMillionUsd) {}
public final class PricingTable {
    public static PricingTable defaultTable();
    public ModelPrice priceOf(String modelId);        // 未知模型回退 default
    public double cost(String modelId, int in, int out, int cachedIn);
}
```

**默认价格（USD / 1M tokens，写死，来源见注释）**：

| modelId | input | output | cached input |
|---|---|---|---|
| `deepseek-flash` | 0.14 | 0.28 | 0.014 |
| `deepseek-chat` | 0.27 | 1.10 | 0.07 |
| `qwen-flash` | 0.05 | 0.40 | 0.01 |
| `qwen-plus` | 0.40 | 1.20 | 0.08 |
| `qwen3.8` | 1.20 | 4.80 | 0.24 |
| `gpt-6.1-sol` | 2.50 | 10.00 | 1.25 |
| `qwen3-rerank` | 0.10 | 0.00 | 0.02 |
| `text-embedding-v4` | 0.02 | 0.00 | 0.004 |
| `local` | 0.0 | 0.0 | 0.0 |
| `default` | 0.50 | 1.50 | 0.10 |

### 4.6 响应缓存 `ResponseCache`

用于产生「缓存命中」真实数据，而不是编造。

```java
public interface ResponseCache {
    /** 命中返回缓存值，未命中返回 null 并记录 */
    String get(String cacheKey);
    void put(String cacheKey, String value);
    long hits();
    long misses();
    double hitRate();
    void reset();
    static String key(MeterStage stage, String modelId, String payload); // SHA-256 前 32 hex
}
```

默认实现 `LruResponseCache`（容量可配，默认 20000，`LinkedHashMap` accessOrder）。
**语义**：命中时 `cacheHit=true`、`cachedInputTokens=inputTokens`（按缓存价计费），
`outputTokens` 仍按正常价计（因为输出来自缓存但下游仍消费）。

### 4.7 计量门面 `TokenMeter`

```java
public interface TokenMeter {
    /** 直接记录一次已发生的调用 */
    TokenUsage record(MeterStage stage, String modelId, String provider,
                      int inputTokens, int outputTokens, int cachedInputTokens,
                      boolean cacheHit, long latencyMs, String traceTag);

    /** 包住一次调用，自动测耗时；supplier 返回 null 时仍记录 */
    <T> T measure(MeterStage stage, String modelId, String provider,
                  String inputPayload, java.util.function.Supplier<T> call,
                  java.util.function.ToIntFunction<T> outputTokenFn);

    MeterSink sink();
    void reset();
}
```

默认实现 `DefaultTokenMeter`：拿 `MeterContext.current()` 填充 runId/experimentId，
用 `PricingTable` 算 cost，`sink.accept(usage)`。

### 4.8 汇聚 `MeterAggregator` + 汇总模型

```java
public record StageAggregate(MeterStage stage, int calls, long inputTokens,
                             long outputTokens, long cachedInputTokens,
                             long cacheHits, double totalCostUsd, long totalLatencyMs) {}

public record RunSummary(String runId, String experimentId, int eventCount,
                         long inputTokens, long outputTokens, long totalTokens,
                         long cachedInputTokens, double cacheHitRate,
                         double totalCostUsd, long totalLatencyMs,
                         List<StageAggregate> stages,
                         Map<String, Long> tokensByModel) {}

public final class MeterAggregator {
    public static RunSummary summarize(String runId, List<TokenUsage> events);
    public static List<RunSummary> summarizeAll(List<TokenUsage> events); // 按 runId 分组
}
```

### 4.9 Sink

```java
public interface MeterSink {
    void accept(TokenUsage usage);
    List<TokenUsage> snapshot();   // 返回已收集事件（内存 sink 有效，jsonl sink 读回）
    void flush();
}
```
- `InMemoryMeterSink`：线程安全 `CopyOnWriteArrayList` 或 `ConcurrentLinkedQueue`
- `JsonlMeterSink(Path file)`：追加写，线程安全（同步块），UTF-8
- `CompositeMeterSink(List<MeterSink>)`

### 4.10 AOP 切面（可选，模块 A 内，不强制 Spring 依赖）

模块 A **不要引入 spring-aop**（保持零依赖）。切面能力由模块 B 在 Spring 环境里用 `@Aspect` 实现，
复用 A 的 `TokenMeter.measure(...)`。A 只提供一个注解：

```java
@Retention(RUNTIME) @Target(METHOD)
public @interface Metered {
    MeterStage stage();
    String modelId() default "";
}
```

---

## 5. 埋点落位（Lead 在 Ragent 上游模块完成）

以**装饰器**方式接入，不改原类：

| 环节 | 埋点位置 | 装饰目标 |
|---|---|---|
| PARSE / CHUNK / EMBED | `rag` 模块 `ingestion` 链路 | `ChunkingService`、`EmbeddingService` |
| REWRITE | `MultiQuestionRewriteService` 的 LLM 调用 | `LLMService.chat` |
| INTENT | `DefaultIntentClassifier` 的 LLM 调用 | 同上 |
| RETRIEVE_* | `MultiChannelRetrievalEngine` 各通道 | `SearchChannel` |
| RERANK | `RerankPostProcessor` | `RerankService` |
| GENERATE | `StreamChatPipeline.streamLLMResponse` | `LLMService.streamChat` |

统一入口：`TokenMeteringLLMService`（装饰 `LLMService`）、`TokenMeteringEmbeddingService`、
`TokenMeteringRerankService`，全部 `@ConditionalOnProperty(prefix="ragent.tokenmeter", name="enabled", havingValue="true")`。

---

## 6. 实验配置层（模块 B）

### 6.1 策略枚举

```java
public enum ChunkStrategy   { FIXED_SIZE, SEMANTIC }          // 固定大小 vs 语义分块
public enum RetrievalStrategy { VECTOR_ONLY, MULTI_CHANNEL }   // 仅向量 vs 多路融合
public enum RoutingStrategy  { FIXED_PREMIUM, INTENT_ROUTED }  // 固定高档 vs 按意图路由廉价
public enum CacheStrategy    { DISABLED, ENABLED }             // 缓存开关（用于缓存命中率分析）
```

### 6.2 实验配置

```java
public record ExperimentConfig(
    String experimentId,     // 稳定 ID，如 "B_SEMANTIC_FUSION_ROUTED"
    String displayName,
    ChunkStrategy chunkStrategy,
    RetrievalStrategy retrievalStrategy,
    RoutingStrategy routingStrategy,
    CacheStrategy cacheStrategy,
    int chunkSize,           // FIXED_SIZE 用
    int chunkOverlap,
    int topK,
    int rerankCandidateLimit,
    List<String> models        // 允许使用的模型池
) {}
```

**必测策略组合（A/B 对比，至少 6 组）**：

| experimentId | chunk | retrieval | routing | cache | 定位 |
|---|---|---|---|---|---|
| `A_BASELINE` | FIXED_SIZE | VECTOR_ONLY | FIXED_PREMIUM | DISABLED | 基线（贵、简单） |
| `B_SEMANTIC` | SEMANTIC | VECTOR_ONLY | FIXED_PREMIUM | DISABLED | 只改分块 |
| `C_FUSION` | FIXED_SIZE | MULTI_CHANNEL | FIXED_PREMIUM | DISABLED | 只改检索 |
| `D_ROUTED` | FIXED_SIZE | VECTOR_ONLY | INTENT_ROUTED | DISABLED | 只改路由 |
| `E_FULL` | SEMANTIC | MULTI_CHANNEL | INTENT_ROUTED | DISABLED | 全开 |
| `E_FULL_CACHE` | SEMANTIC | MULTI_CHANNEL | INTENT_ROUTED | ENABLED | 全开+缓存 |

### 6.3 数据集格式（成员 D 生产，成员 B 消费）

`data/corpus/docs.jsonl` — 200 篇文档，每行：
```json
{"docId":"DOC_0001","title":"...","domain":"vacuum|finance|hr|tech|policy","text":"...","chunksHint":12}
```
要求：中文为主，单篇 1200~3000 字，覆盖 5 个领域，含表格/列表等结构（Markdown 片段）。

`data/corpus/queries.jsonl` — 500 轮对话，每行：
```json
{"conversationId":"CONV_0001","turnIndex":0,"question":"...","referenceDocIds":["DOC_0003"],"referenceAnswer":"...","intentHint":"kb|chitchat|system"}
```
要求：250 个会话 × 2 轮（多轮含指代，如「它多少钱」），
`referenceDocIds` 必须真实存在于 docs.jsonl。

### 6.4 运行结果格式（成员 B 生产，成员 C 消费）

`data/results/<runId>/events.jsonl` — 每行一个 `TokenUsage` JSON（字段名与 4.2 完全一致，camelCase）。

`data/results/<runId>/summary.json`：
```json
{
  "runId":"RUN_20261110_001",
  "generatedAt":"2026-11-10T21:30:00+08:00",
  "dataset":{"docs":200,"conversations":250,"turns":500},
  "experiments":[
    {
      "experimentId":"A_BASELINE","displayName":"基线：固定分块+仅向量+固定高档模型",
      "chunkStrategy":"FIXED_SIZE","retrievalStrategy":"VECTOR_ONLY",
      "routingStrategy":"FIXED_PREMIUM","cacheStrategy":"DISABLED",
      "inputTokens":123456,"outputTokens":45678,"totalTokens":169134,
      "cachedInputTokens":0,"cacheHitRate":0.0,
      "totalCostUsd":1.2345,"totalLatencyMs":987654,
      "avgLatencyMs":1975.3,"turns":500,
      "qualityScore":0.7123,"qualityScorePct":71.23,
      "tokensPerTurn":338.3,"costPerTurnUsd":0.0025,
      "stages":[{"stage":"GENERATE","calls":500,"inputTokens":100000,
                 "outputTokens":40000,"cachedInputTokens":0,"cacheHits":0,
                 "totalCostUsd":0.9,"totalLatencyMs":500000}],
      "tokensByModel":{"deepseek-flash":120000,"qwen-flash":49134},
      "timeline":[{"t":0,"cumulativeTokens":1200,"stage":"GENERATE","tokens":1200}]
    }
  ],
  "deltas":[
    {"baseline":"A_BASELINE","candidate":"E_FULL",
     "tokenSavingPct":42.7,"qualityDeltaPct":-1.2,"costSavingPct":55.1,
     "verdict":"节省 42.7% token，质量变化 -1.2%"}
  ]
}
```

`timeline` 用于仪表盘 Token 消耗时间轴：按事件时间顺序累加，`t` 为事件序号（0-based）。

`data/results/<runId>/report.md` — 自动生成的中文实验报告（格式见 §8）。

---

## 7. 前端仪表盘（成员 C）

- 技术栈：**React 18 + Vite + TypeScript + ECharts + Tailwind**
- 目录：`token-bench/dashboard/`
- 数据来源：**静态 JSON**（`public/data/summary.json`，由 Lead/构建脚本从 `data/results/<runId>/` 拷贝）
  + 可选 fetch `http://localhost:8080/api/bench/summary`（模块 B 的 REST 端点）
- **必须实现 3 个视图**：
  1. **Token 消耗时间轴**（折线/面积图，按实验叠加，可切换阶段过滤）
  2. **各环节 Token 占比**（环形图 / 堆叠柱状图，可切实验）
  3. **成本-效果散点图**（X=总 token，Y=质量分，气泡=成本，标注 experimentId）
- 另加：实验对比表、缓存命中率卡片。
- 必须能 `npm install && npm run build` 成功（Node 在 `_toolchain` 外，用 `load_workspace_dependencies` 的 node/pnpm 路径）。
- **不要** 启动常驻 dev server；用 `npm run build` 产物 + 单测（可选）验证。

---

## 8. 自动报告（模块 B 生成，`data/results/<runId>/report.md`）

必须包含：
1. 实验概览表（6 组策略 × 总token/成本/质量分/每轮token）
2. **结论段落**，句式强制：
   > 在 XX 任务集（200 文档 / 500 轮对话）上，策略 **E_FULL** 比策略 **A_BASELINE** 节省 **42.7%** token，质量变化 **-1.2%**，成本节省 **55.1%**。
3. 分环节 token 归因表
4. 缓存命中率对总成本的影响分析
5. 质量-成本帕累托前沿说明

---

## 9. 质量评分（模块 B）

`QualityScorer` 产出 `[0,1]` 分数，**离线确定性**（不调外部 LLM），由三部分加权：

- `recall@k`（检索命中 referenceDocIds 的比例）权重 0.4
- `answerCoverage`（参考要点在答案中的覆盖率，按关键词/字符 n-gram 重叠）权重 0.4
- `citationPrecision`（引用 docId 中正确的比例）权重 0.2

必须**可复现**（同输入同输出，无随机）。

---

## 10. 运行入口（模块 B）

```java
public final class BenchmarkMain {
    public static void main(String[] args);  // args: --docs data/corpus/docs.jsonl --queries ... --out data/results/RUN_x
}
```

要求：**纯 Java main 可跑**，不依赖 Spring 容器、不依赖数据库/Redis/MQ。
但内部**复用 Ragent 真实类**（`ChunkingService`、`BlockAwareChunkerDispatcher`、`FusionPostProcessor`
等无外部依赖的组件），LLM/Embedding 走确定性本地实现（`LocalDeterministicLLM`）。

产出：`events.jsonl`、`summary.json`、`report.md`。

---

## 11. 验收标准

- [ ] `mvn install` 全绿（含 tokenmeter + bench）
- [ ] `java -jar`/`java -cp` 能跑出 `data/results/RUN_*/` 三件套
- [ ] 累计 token ≥ 1,000,000（200 文档入库 + 500 轮对话）
- [ ] `dashboard` `npm run build` 成功
- [ ] README 含：token 消耗曲线图、成本-效果对比表、缓存命中率分析
- [ ] 每个模块有至少 1 个可运行的单测
