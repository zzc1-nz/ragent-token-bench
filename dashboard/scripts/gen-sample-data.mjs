#!/usr/bin/env node
/**
 * 生成一份「结构完全符合 CONTRACT.md §6.4」的示例 summary.json。
 *
 * 设计原则
 * --------
 * 1. 所有派生数值（totalTokens / totalCostUsd / cacheHitRate / tokensPerTurn /
 *    tokensByModel / timeline.cumulativeTokens / deltas）都由「每轮各环节的
 *    token 量」+ CONTRACT §4.5 的定价表**计算**得出，绝不手写，因此恒自洽。
 * 2. 数据是示例，但量级/比例贴近真实的 Agentic RAG 链路（检索 rerank 输入大、
 *    GENERATE 占比最高、缓存命中按折扣计价）。
 * 3. 真实 summary.json 由 Java 运行器产出后直接覆盖本文件即可，
 *    前端渲染逻辑完全由数据结构驱动。
 *
 * 用法: node scripts/gen-sample-data.mjs
 */
import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
// 默认写到 summary.example.json，避免覆盖真实运行数据。
// 需要把它当作主数据时传 --out 指定路径，例如：
//   node scripts/gen-sample-data.mjs --out public/data/summary.json
const outArgIdx = process.argv.indexOf('--out')
const OUT =
  outArgIdx > -1 && process.argv[outArgIdx + 1]
    ? resolve(process.argv[outArgIdx + 1])
    : resolve(__dirname, '../public/data/summary.example.json')

/* ------------------------------------------------------------------ *
 * CONTRACT §4.5 定价表（USD / 1M tokens）
 * ------------------------------------------------------------------ */
const PRICES = {
  'deepseek-flash': { in: 0.14, out: 0.28, cached: 0.014 },
  'deepseek-chat': { in: 0.27, out: 1.1, cached: 0.07 },
  'qwen-flash': { in: 0.05, out: 0.4, cached: 0.01 },
  'qwen-plus': { in: 0.4, out: 1.2, cached: 0.08 },
  'qwen3.8': { in: 1.2, out: 4.8, cached: 0.24 },
  'gpt-6.1-sol': { in: 2.5, out: 10.0, cached: 1.25 },
  'qwen3-rerank': { in: 0.1, out: 0.0, cached: 0.02 },
  'text-embedding-v4': { in: 0.02, out: 0.0, cached: 0.004 },
  local: { in: 0, out: 0, cached: 0 },
  default: { in: 0.5, out: 1.5, cached: 0.1 },
}

const priceOf = (m) => PRICES[m] ?? PRICES.default

/** 与 CONTRACT §4.6 语义一致：命中缓存部分按 cachedInput 价，其余输入按正常价，输出按正常价 */
function cost(model, inputTokens, outputTokens, cachedInputTokens = 0) {
  const p = priceOf(model)
  const billableIn = Math.max(0, inputTokens - cachedInputTokens)
  return (
    (billableIn * p.in + cachedInputTokens * p.cached + outputTokens * p.out) / 1_000_000
  )
}

const r2 = (n) => Math.round(n * 100) / 100
const r4 = (n) => Math.round(n * 10000) / 10000
const r6 = (n) => Math.round(n * 1000000) / 1000000

/* ------------------------------------------------------------------ *
 * 实验定义
 *
 * 每轮（turn）各环节 token 用量是「设计值」，calls = 该环节被调用的次数。
 * 500 轮对话（250 会话 × 2 轮）。SUMMARY 每 2 轮触发一次 => calls = 250。
 * ------------------------------------------------------------------ */
const TURNS = 500
const CONVERSATIONS = 250
const DOCS = 200

/**
 * 用「每轮每环节的 token 量」描述一个实验，随后统一乘以 calls 展开。
 * callsRatio: 相对总轮数的调用比例（1 => 每轮一次，0.5 => 每两轮一次）
 */
function buildStages(spec, cacheHitRate = 0) {
  return spec.map((s) => {
    const calls = Math.round(TURNS * s.callsRatio)
    const inputTokens = s.inPerCall * calls
    const outputTokens = s.outPerCall * calls
    // 缓存命中：命中部分的输入 token 按缓存价计费（§4.6）
    const cacheable = s.cacheable === false ? 0 : 1
    const cachedInputTokens = Math.round(inputTokens * cacheHitRate * cacheable)
    const cacheHits = Math.round(calls * cacheHitRate * cacheable)
    return {
      stage: s.stage,
      model: s.model,
      calls,
      inputTokens,
      outputTokens,
      cachedInputTokens,
      cacheHits,
      totalCostUsd: r6(cost(s.model, inputTokens, outputTokens, cachedInputTokens)),
      totalLatencyMs: s.latencyMs * calls,
    }
  })
}

/** 一个环节的简写构造器 */
const st = (stage, model, inPerCall, outPerCall, latencyMs, callsRatio = 1, extra = {}) => ({
  stage,
  model,
  inPerCall,
  outPerCall,
  latencyMs,
  callsRatio,
  ...extra,
})

/* ---- A_BASELINE：固定分块 + 仅向量 + 固定高档模型（gpt-6.1-sol）+ 无缓存 ---- */
const A_BASELINE = [
  st('INTENT', 'gpt-6.1-sol', 486, 24, 210),
  st('REWRITE', 'gpt-6.1-sol', 642, 96, 340),
  st('RETRIEVE_VECTOR', 'text-embedding-v4', 42, 0, 46),
  st('RETRIEVE_KEYWORD', 'local', 0, 0, 38, 1, { cacheable: false }),
  st('RERANK', 'qwen3-rerank', 3240, 0, 168),
  st('GENERATE', 'gpt-6.1-sol', 6810, 382, 1860),
  st('JUDGE', 'gpt-6.1-sol', 3402, 128, 620),
  st('SUMMARY', 'gpt-6.1-sol', 706, 52, 300, 0.5),
]

/* ---- B_SEMANTIC：只改分块（语义分块 => 块更少更准，rerank/context 输入下降） ---- */
const B_SEMANTIC = [
  st('INTENT', 'gpt-6.1-sol', 486, 24, 210),
  st('REWRITE', 'gpt-6.1-sol', 642, 96, 340),
  st('RETRIEVE_VECTOR', 'text-embedding-v4', 42, 0, 46),
  st('RETRIEVE_KEYWORD', 'local', 0, 0, 38, 1, { cacheable: false }),
  st('RERANK', 'qwen3-rerank', 2280, 0, 148),
  st('GENERATE', 'gpt-6.1-sol', 5420, 388, 1760),
  st('JUDGE', 'gpt-6.1-sol', 2980, 128, 590),
  st('SUMMARY', 'gpt-6.1-sol', 706, 52, 300, 0.5),
]

/* ---- C_FUSION：只改检索（多路召回 => 候选更多、fusion 本地计算、rerank 输入更大） ---- */
const C_FUSION = [
  st('INTENT', 'gpt-6.1-sol', 486, 24, 210),
  st('REWRITE', 'gpt-6.1-sol', 642, 96, 340),
  st('RETRIEVE_VECTOR', 'text-embedding-v4', 42, 0, 46),
  st('RETRIEVE_KEYWORD', 'local', 0, 0, 52, 1, { cacheable: false }),
  st('RETRIEVE_GRAPH', 'local', 0, 0, 41, 1, { cacheable: false }),
  st('FUSION', 'local', 0, 0, 12, 1, { cacheable: false }),
  st('RERANK', 'qwen3-rerank', 4380, 0, 196),
  st('GENERATE', 'gpt-6.1-sol', 7240, 396, 1880),
  st('JUDGE', 'gpt-6.1-sol', 3402, 128, 620),
  st('SUMMARY', 'gpt-6.1-sol', 706, 52, 300, 0.5),
]

/* ---- D_ROUTED：只改路由（按意图分流：~12% 闲聊走廉价模型且跳过重链路） ---- */
const D_ROUTED = [
  st('INTENT', 'deepseek-flash', 452, 22, 96),
  st('REWRITE', 'qwen-plus', 588, 84, 260),
  st('RETRIEVE_VECTOR', 'text-embedding-v4', 42, 0, 46),
  st('RETRIEVE_KEYWORD', 'local', 0, 0, 38, 1, { cacheable: false }),
  st('RERANK', 'qwen3-rerank', 3180, 0, 164),
  st('GENERATE', 'qwen-plus', 6380, 366, 1720),
  st('JUDGE', 'deepseek-flash', 2980, 118, 480),
  st('SUMMARY', 'deepseek-flash', 664, 46, 240, 0.5),
]

/* ---- E_FULL：语义分块 + 多路融合 + 意图路由 + 无缓存 ----
 * 三者叠加后链路明显「瘦身」：语义分块减少候选冗余、多路融合让 rerank 更有针对性、
 * 意图路由让大部分轮次走廉价模型且跳过重上下文，因此 token 显著低于基线。 */
const E_FULL = [
  st('INTENT', 'deepseek-flash', 402, 20, 88),
  st('REWRITE', 'qwen-plus', 512, 74, 232),
  st('RETRIEVE_VECTOR', 'text-embedding-v4', 42, 0, 44),
  st('RETRIEVE_KEYWORD', 'local', 0, 0, 52, 1, { cacheable: false }),
  st('RETRIEVE_GRAPH', 'local', 0, 0, 41, 1, { cacheable: false }),
  st('FUSION', 'local', 0, 0, 12, 1, { cacheable: false }),
  st('RERANK', 'qwen3-rerank', 1960, 0, 132),
  st('GENERATE', 'qwen-plus', 3740, 352, 1420),
  st('JUDGE', 'deepseek-flash', 2060, 104, 396),
  st('SUMMARY', 'deepseek-flash', 592, 42, 216, 0.5),
]

/* ---- E_FULL_CACHE：同 E_FULL，但开启响应缓存（§4.6） ---- */
const E_FULL_CACHE = E_FULL

const EXPERIMENT_META = [
  {
    experimentId: 'A_BASELINE',
    displayName: '基线：固定分块 + 仅向量召回 + 固定高档模型',
    chunkStrategy: 'FIXED_SIZE',
    retrievalStrategy: 'VECTOR_ONLY',
    routingStrategy: 'FIXED_PREMIUM',
    cacheStrategy: 'DISABLED',
    chunkSize: 500,
    chunkOverlap: 50,
    topK: 8,
    rerankCandidateLimit: 24,
    models: ['gpt-6.1-sol', 'qwen3-rerank', 'text-embedding-v4'],
    qualityScore: 0.7123,
    stages: A_BASELINE,
    cacheHitRate: 0,
    latencyScale: 1.0,
  },
  {
    experimentId: 'B_SEMANTIC',
    displayName: '语义分块：块边界对齐段落语义',
    chunkStrategy: 'SEMANTIC',
    retrievalStrategy: 'VECTOR_ONLY',
    routingStrategy: 'FIXED_PREMIUM',
    cacheStrategy: 'DISABLED',
    chunkSize: 480,
    chunkOverlap: 40,
    topK: 6,
    rerankCandidateLimit: 18,
    models: ['gpt-6.1-sol', 'qwen3-rerank', 'text-embedding-v4'],
    qualityScore: 0.7402,
    stages: B_SEMANTIC,
    cacheHitRate: 0,
    latencyScale: 0.96,
  },
  {
    experimentId: 'C_FUSION',
    displayName: '多路融合：向量 + 关键词 + 图谱 RRF',
    chunkStrategy: 'FIXED_SIZE',
    retrievalStrategy: 'MULTI_CHANNEL',
    routingStrategy: 'FIXED_PREMIUM',
    cacheStrategy: 'DISABLED',
    chunkSize: 500,
    chunkOverlap: 50,
    topK: 12,
    rerankCandidateLimit: 32,
    models: ['gpt-6.1-sol', 'qwen3-rerank', 'text-embedding-v4'],
    qualityScore: 0.7488,
    stages: C_FUSION,
    cacheHitRate: 0,
    latencyScale: 1.12,
  },
  {
    experimentId: 'D_ROUTED',
    displayName: '意图路由：闲聊/知识问答分流到不同价位模型',
    chunkStrategy: 'FIXED_SIZE',
    retrievalStrategy: 'VECTOR_ONLY',
    routingStrategy: 'INTENT_ROUTED',
    cacheStrategy: 'DISABLED',
    chunkSize: 500,
    chunkOverlap: 50,
    topK: 8,
    rerankCandidateLimit: 24,
    models: ['deepseek-flash', 'qwen-plus', 'qwen3-rerank', 'text-embedding-v4'],
    qualityScore: 0.7056,
    stages: D_ROUTED,
    cacheHitRate: 0,
    latencyScale: 0.62,
  },
  {
    experimentId: 'E_FULL',
    displayName: '全量策略：语义分块 + 多路融合 + 意图路由',
    chunkStrategy: 'SEMANTIC',
    retrievalStrategy: 'MULTI_CHANNEL',
    routingStrategy: 'INTENT_ROUTED',
    cacheStrategy: 'DISABLED',
    chunkSize: 480,
    chunkOverlap: 40,
    topK: 10,
    rerankCandidateLimit: 28,
    models: ['deepseek-flash', 'qwen-plus', 'qwen3-rerank', 'text-embedding-v4'],
    qualityScore: 0.7591,
    stages: E_FULL,
    cacheHitRate: 0,
    latencyScale: 0.71,
  },
  {
    experimentId: 'E_FULL_CACHE',
    displayName: '全量策略 + 响应缓存（命中按缓存价计费）',
    chunkStrategy: 'SEMANTIC',
    retrievalStrategy: 'MULTI_CHANNEL',
    routingStrategy: 'INTENT_ROUTED',
    cacheStrategy: 'ENABLED',
    chunkSize: 480,
    chunkOverlap: 40,
    topK: 10,
    rerankCandidateLimit: 28,
    models: ['deepseek-flash', 'qwen-plus', 'qwen3-rerank', 'text-embedding-v4'],
    qualityScore: 0.7583,
    stages: E_FULL_CACHE,
    cacheHitRate: 0.4372,
    latencyScale: 0.58,
  },
]

/* ------------------------------------------------------------------ *
 * 展开为 §6.4 的 experiments[]
 * ------------------------------------------------------------------ */
const experiments = EXPERIMENT_META.map((meta) => {
  const stages = buildStages(meta.stages, meta.cacheHitRate)

  const inputTokens = stages.reduce((s, x) => s + x.inputTokens, 0)
  const outputTokens = stages.reduce((s, x) => s + x.outputTokens, 0)
  const cachedInputTokens = stages.reduce((s, x) => s + x.cachedInputTokens, 0)
  const totalTokens = inputTokens + outputTokens
  const totalCostUsd = stages.reduce((s, x) => s + x.totalCostUsd, 0)
  const totalLatencyMs = Math.round(
    stages.reduce((s, x) => s + x.totalLatencyMs, 0) * meta.latencyScale,
  )

  // tokensByModel：按环节归属聚合
  const tokensByModel = {}
  for (const s of stages) {
    tokensByModel[s.model] = (tokensByModel[s.model] ?? 0) + s.inputTokens + s.outputTokens
  }

  const cacheHitRate = inputTokens > 0 ? r6(cachedInputTokens / inputTokens) : 0

  return {
    experimentId: meta.experimentId,
    displayName: meta.displayName,
    chunkStrategy: meta.chunkStrategy,
    retrievalStrategy: meta.retrievalStrategy,
    routingStrategy: meta.routingStrategy,
    cacheStrategy: meta.cacheStrategy,
    chunkSize: meta.chunkSize,
    chunkOverlap: meta.chunkOverlap,
    topK: meta.topK,
    rerankCandidateLimit: meta.rerankCandidateLimit,
    models: meta.models,

    inputTokens,
    outputTokens,
    totalTokens,
    cachedInputTokens,
    cacheHitRate,
    totalCostUsd: r6(totalCostUsd),
    totalLatencyMs,
    avgLatencyMs: r2(totalLatencyMs / TURNS),
    turns: TURNS,
    qualityScore: meta.qualityScore,
    qualityScorePct: r2(meta.qualityScore * 100),
    tokensPerTurn: r2(totalTokens / TURNS),
    costPerTurnUsd: r6(totalCostUsd / TURNS),

    stages: stages.map(({ model: _model, ...rest }) => rest),
    stageModels: Object.fromEntries(stages.map((s) => [s.stage, s.model])),
    tokensByModel,

    timeline: buildTimeline(stages, meta),
  }
})

/* ------------------------------------------------------------------ *
 * timeline：按事件顺序累加（§6.4）。每个 stage 的每次调用是一个事件。
 * callsRatio = 1   => 每轮一次，事件 t = turn*<stageCount> + idx
 * callsRatio = 0.5 => 每两轮一次
 * 为了让多条实验的曲线可比，t 统一为「该实验内的事件序号」。
 * ------------------------------------------------------------------ */
function buildTimeline(stages, meta) {
  const events = []
  // 只把「真正消耗 token」的环节放进 token 时间轴：
  // RETRIEVE_KEYWORD / RETRIEVE_GRAPH / FUSION 是本地计算，token=0（§4.1），
  // 它们的耗时在 stages[].totalLatencyMs 里体现，不应污染 token 曲线。
  const active = stages.filter(
    (s) => s.calls > 0 && s.inputTokens + s.outputTokens > 0,
  )

  // 预计算每个 stage 每次调用的 token 数（尾差并入最后一次调用，保证合计精确）
  const perCall = active.map((s) => {
    const base = Math.floor((s.inputTokens + s.outputTokens) / s.calls)
    const last = s.inputTokens + s.outputTokens - base * (s.calls - 1)
    return { stage: s.stage, base, last, calls: s.calls }
  })

  // 交错展开：按轮次推进，模拟真实调用顺序（INTENT→REWRITE→RETRIEVE→RERANK→GENERATE→JUDGE）
  const stageOrder = [
    'PARSE',
    'CHUNK',
    'EMBED',
    'INTENT',
    'REWRITE',
    'RETRIEVE_VECTOR',
    'RETRIEVE_KEYWORD',
    'RETRIEVE_GRAPH',
    'RETRIEVE_WEB',
    'FUSION',
    'RERANK',
    'GENERATE',
    'JUDGE',
    'SUMMARY',
    'OTHER',
  ]
  const ordered = [...perCall].sort(
    (a, b) => stageOrder.indexOf(a.stage) - stageOrder.indexOf(b.stage),
  )

  // 每个 stage 已消耗的调用次数
  const used = new Map(ordered.map((p) => [p.stage, 0]))
  let cumulative = 0
  let t = 0

  for (let turn = 0; turn < TURNS; turn++) {
    for (const p of ordered) {
      const done = used.get(p.stage) ?? 0
      if (done >= p.calls) continue
      // callsRatio 0.5 的环节（SUMMARY）每两轮一次
      const ratio = p.calls / TURNS
      if (ratio < 0.999 && turn % Math.round(1 / ratio) !== 0) continue

      const tokens = done === p.calls - 1 ? p.last : p.base
      cumulative += tokens
      events.push({
        t: t++,
        turn,
        stage: p.stage,
        tokens,
        cumulativeTokens: cumulative,
      })
      used.set(p.stage, done + 1)
    }
  }

  // 抖动：给每条曲线一点真实波动感（确定性伪随机，基于 experimentId + t）。
  // 用「最大余数法」把 totalTokens 精确分配到各事件，保证末值 == totalTokens，
  // 即抖动只改变曲线形状，不改变总量（自洽性优先）。
  const seed = hash(meta.experimentId)
  const target = cumulative
  const weights = events.map((e, i) => e.tokens * (0.82 + 0.36 * rand(seed, i))) // ±18%
  const weightSum = weights.reduce((a, w) => a + w, 0)
  const raw = weights.map((w) => (w / weightSum) * target)
  const floors = raw.map((x) => Math.floor(x))
  const remainder = target - floors.reduce((a, x) => a + x, 0)
  // 按小数部分从大到小补 1，保证总和精确等于 target
  const order = raw
    .map((x, i) => ({ i, frac: x - Math.floor(x) }))
    .sort((a, b) => b.frac - a.frac || a.i - b.i)
  for (let k = 0; k < remainder; k++) floors[order[k % order.length].i] += 1

  let acc = 0
  for (let i = 0; i < events.length; i++) {
    const e = events[i]
    e.tokens = floors[i]
    acc += e.tokens
    e.cumulativeTokens = acc
  }

  // 采样：真实运行器的时间轴约 200 点（按事件序号稀疏采样）。
  // 全量 3250 点会让示例文件膨胀到 3MB，这里按目标点数等距采样，
  // 保留首末点，使末值仍精确等于 totalTokens。
  const TARGET_POINTS = 200
  let sampled = events
  if (events.length > TARGET_POINTS) {
    const step = events.length / (TARGET_POINTS - 1)
    const picked = []
    for (let i = 0; i < TARGET_POINTS - 1; i++) {
      picked.push(events[Math.min(Math.round(i * step), events.length - 1)])
    }
    picked.push(events[events.length - 1]) // 保证末值精确
    sampled = picked
  }

  return sampled
}

function hash(str) {
  let h = 2166136261
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}

function rand(seed, i) {
  let x = (seed ^ (i * 2654435761)) >>> 0
  x ^= x << 13
  x >>>= 0
  x ^= x >> 17
  x ^= x << 5
  x >>>= 0
  return x / 4294967296
}

/* ------------------------------------------------------------------ *
 * deltas：所有「基线 vs 候选」以及关键相邻对比
 * ------------------------------------------------------------------ */
const byId = Object.fromEntries(experiments.map((e) => [e.experimentId, e]))

function delta(baseline, candidate) {
  const b = byId[baseline]
  const c = byId[candidate]
  const tokenSavingPct = r2((1 - c.totalTokens / b.totalTokens) * 100)
  const costSavingPct = r2((1 - c.totalCostUsd / b.totalCostUsd) * 100)
  const qualityDeltaPct = r2((c.qualityScore / b.qualityScore - 1) * 100)
  const latencyDeltaPct = r2((c.avgLatencyMs / b.avgLatencyMs - 1) * 100)
  const verdict =
    `${tokenSavingPct >= 0 ? '节省' : '多用'} ${Math.abs(tokenSavingPct).toFixed(1)}% token，` +
    `质量变化 ${qualityDeltaPct >= 0 ? '+' : ''}${qualityDeltaPct.toFixed(1)}%，` +
    `成本${costSavingPct >= 0 ? '节省' : '增加'} ${Math.abs(costSavingPct).toFixed(1)}%`
  return {
    baseline,
    candidate,
    tokenSavingPct,
    costSavingPct,
    qualityDeltaPct,
    latencyDeltaPct,
    verdict,
  }
}

const deltas = [
  delta('A_BASELINE', 'B_SEMANTIC'),
  delta('A_BASELINE', 'C_FUSION'),
  delta('A_BASELINE', 'D_ROUTED'),
  delta('A_BASELINE', 'E_FULL'),
  delta('A_BASELINE', 'E_FULL_CACHE'),
  delta('E_FULL', 'E_FULL_CACHE'),
]

/* ------------------------------------------------------------------ *
 * 顶层汇总 + 落盘
 * ------------------------------------------------------------------ */
const totalTokens = experiments.reduce((s, e) => s + e.totalTokens, 0)
const totalCostUsd = experiments.reduce((s, e) => s + e.totalCostUsd, 0)
const totalLatencyMs = experiments.reduce((s, e) => s + e.totalLatencyMs, 0)

const summary = {
  schemaVersion: '1.0.0',
  runId: 'RUN_20261110_001',
  generatedAt: '2026-11-10T21:30:00+08:00',
  dataset: { docs: DOCS, conversations: CONVERSATIONS, turns: TURNS },
  environment: {
    note: '示例数据（ExampleFixture）：由 scripts/gen-sample-data.mjs 按 CONTRACT §6.4 生成，真实运行会覆盖此文件',
    example: true,
    jvm: 'OpenJDK 25.0.2',
    pricingTableVersion: 'contract-4.5',
    qualityScorer: 'recall@k(0.4) + answerCoverage(0.4) + citationPrecision(0.2)',
  },
  totals: {
    experiments: experiments.length,
    events: experiments.reduce(
      (s, e) => s + e.stages.reduce((a, x) => a + x.calls, 0),
      0,
    ),
    totalTokens,
    totalCostUsd: r6(totalCostUsd),
    totalLatencyMs,
    turns: TURNS,
  },
  experiments,
  deltas,
}

mkdirSync(dirname(OUT), { recursive: true })
writeFileSync(OUT, JSON.stringify(summary, null, 2) + '\n', 'utf8')

/* ------------------------------------------------------------------ *
 * 自检：断言自洽性（构建时跑一次，防止示例数据本身出现矛盾）
 * ------------------------------------------------------------------ */
const errors = []
for (const e of experiments) {
  const sum = e.stages.reduce((a, s) => a + s.inputTokens + s.outputTokens, 0)
  if (sum !== e.totalTokens) errors.push(`${e.experimentId}: stages 合计 != totalTokens`)
  if (e.totalTokens !== e.inputTokens + e.outputTokens)
    errors.push(`${e.experimentId}: totalTokens != input + output`)
  const tl = e.timeline
  if (tl.length === 0) errors.push(`${e.experimentId}: timeline 为空`)
  if (tl.length && tl[tl.length - 1].cumulativeTokens !== e.totalTokens)
    errors.push(
      `${e.experimentId}: timeline 末值 ${tl[tl.length - 1].cumulativeTokens} != totalTokens ${e.totalTokens}`,
    )
  // timeline 是采样序列：只要求单调不减 + t 严格递增（与真实运行器语义一致）
  for (let i = 1; i < tl.length; i++) {
    if (tl[i].cumulativeTokens < tl[i - 1].cumulativeTokens) {
      errors.push(`${e.experimentId}: timeline cumulativeTokens 回退 @${i}`)
      break
    }
    if (!(tl[i].t > tl[i - 1].t)) {
      errors.push(`${e.experimentId}: timeline t 未严格递增 @${i}`)
      break
    }
  }
  if (e.cacheStrategy === 'DISABLED' && e.cachedInputTokens !== 0)
    errors.push(`${e.experimentId}: 缓存关闭却有 cachedInputTokens`)
}

const base = byId['A_BASELINE']
for (const id of ['B_SEMANTIC', 'C_FUSION', 'D_ROUTED', 'E_FULL']) {
  if (byId[id].totalTokens >= base.totalTokens && id !== 'C_FUSION')
    errors.push(`${id}: 应当比基线省 token`)
}
const full = byId['E_FULL']
const cache = byId['E_FULL_CACHE']
if (!(cache.cacheHitRate > full.cacheHitRate))
  errors.push('E_FULL_CACHE 的缓存命中率应高于 E_FULL')
if (!(cache.totalCostUsd < full.totalCostUsd))
  errors.push('E_FULL_CACHE 的成本应低于 E_FULL')
if (!(cache.cachedInputTokens > 0)) errors.push('E_FULL_CACHE 应有缓存命中的输入 token')

if (errors.length) {
  console.error('✗ 示例数据自检失败:')
  for (const e of errors) console.error('  - ' + e)
  process.exit(1)
}

console.log('✓ 示例数据已生成:', OUT)
console.log(
  `  实验数 ${experiments.length} | 事件数 ${summary.totals.events} | 总 token ${totalTokens.toLocaleString()} | 总成本 $${r2(totalCostUsd)}`,
)
console.log('  各实验:')
for (const e of experiments) {
  console.log(
    `    ${e.experimentId.padEnd(14)} token=${String(e.totalTokens).padStart(9)} ` +
      `cost=$${e.totalCostUsd.toFixed(4).padStart(9)} quality=${e.qualityScorePct.toFixed(2)}% ` +
      `cacheHit=${(e.cacheHitRate * 100).toFixed(1)}% avgLat=${e.avgLatencyMs.toFixed(0)}ms`,
  )
}
console.log('  deltas:')
for (const d of deltas) console.log(`    ${d.baseline} → ${d.candidate}: ${d.verdict}`)
