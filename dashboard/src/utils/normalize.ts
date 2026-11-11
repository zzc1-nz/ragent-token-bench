/**
 * 把「可能是残缺的」summary.json 归一化成 DashboardData。
 *
 * 设计原则
 * --------
 * - 绝不对缺失字段抛异常：每个字段都有安全的默认值。
 * - 派生字段（totalTokens / qualityScorePct / tokensPerTurn / cacheHitRate）
 *   若缺失则从已有字段**推算**；推算不出来才用 0。
 * - 所有非致命问题收集进 warnings[]，由 UI 以横幅形式告知，
 *   这样「数据有问题」是可观测的，而不是静默显示 0。
 */
import type {
  DashboardData,
  DeltaVM,
  Experiment,
  ExperimentVM,
  MeterStage,
  StageAggregate,
  StageVM,
  Summary,
  TimelinePointVM,
} from '../types'
import { STAGE_COLORS, STAGE_LABELS } from '../types'

const num = (v: unknown, fallback = 0): number =>
  typeof v === 'number' && Number.isFinite(v) ? v : fallback

const str = (v: unknown, fallback = ''): string =>
  typeof v === 'string' && v.length > 0 ? v : fallback

/** 环节显示名：未知环节回退到原始字符串，不丢信息 */
export function stageLabel(stage: string): string {
  return STAGE_LABELS[stage as MeterStage] ?? stage
}

export function stageColor(stage: string): string {
  return STAGE_COLORS[stage as MeterStage] ?? '#6b7688'
}

/** 环节在链路中的自然顺序，用于堆叠/排序展示 */
const STAGE_ORDER: string[] = [
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

export const stageRank = (s: string): number => {
  const i = STAGE_ORDER.indexOf(s)
  return i === -1 ? STAGE_ORDER.length : i
}

/* ------------------------------------------------------------------ *
 * stages[] 归一化
 * ------------------------------------------------------------------ */
function normalizeStages(raw: StageAggregate[] | undefined, warnings: string[], expId: string): StageVM[] {
  if (!Array.isArray(raw) || raw.length === 0) {
    warnings.push(`实验 ${expId} 缺少 stages[]，环节占比图将不可用`)
    return []
  }

  const out: StageVM[] = []
  for (const s of raw) {
    if (!s || typeof s !== 'object') continue
    const stage = str(s.stage, 'OTHER')
    const inputTokens = num(s.inputTokens)
    const outputTokens = num(s.outputTokens)
    // totalTokens 不在契约的 StageAggregate 里，由 input+output 推导
    const totalTokens = inputTokens + outputTokens
    out.push({
      stage,
      label: stageLabel(stage),
      color: stageColor(stage),
      calls: num(s.calls),
      inputTokens,
      outputTokens,
      totalTokens,
      cachedInputTokens: num(s.cachedInputTokens),
      cacheHits: num(s.cacheHits),
      totalCostUsd: num(s.totalCostUsd),
      totalLatencyMs: num(s.totalLatencyMs),
    })
  }

  // 按链路自然顺序排列，未知环节排最后
  out.sort((a, b) => stageRank(a.stage) - stageRank(b.stage) || a.stage.localeCompare(b.stage))
  return out
}

/* ------------------------------------------------------------------ *
 * timeline[] 归一化
 *
 * 契约保证 timeline 按事件顺序、cumulativeTokens 累加。
 * 但真实数据可能：缺 cumulativeTokens / 乱序 / 重复 t。
 * 这里统一按 t 排序后重新累加，保证曲线单调不减且末值 == totalTokens。
 * ------------------------------------------------------------------ */
function normalizeTimeline(
  raw: unknown,
  warnings: string[],
  expId: string,
): TimelinePointVM[] {
  if (!Array.isArray(raw) || raw.length === 0) {
    warnings.push(`实验 ${expId} 缺少 timeline[]，时间轴曲线将跳过该实验`)
    return []
  }

  const pts = raw
    .filter((p): p is Record<string, unknown> => !!p && typeof p === 'object')
    .map((p, i) => ({
      t: num(p.t, i),
      tokens: num(p.tokens),
      cumulativeTokens:
        typeof p.cumulativeTokens === 'number' && Number.isFinite(p.cumulativeTokens)
          ? (p.cumulativeTokens as number)
          : null,
      stage: str(p.stage, ''),
      turn: num(p.turn, 0),
      _i: i,
    }))
    .sort((a, b) => a.t - b.t || a._i - b._i)

  if (pts.length === 0) {
    warnings.push(`实验 ${expId} 的 timeline[] 无可解析数据点`)
    return []
  }

  // 若所有点都有 cumulativeTokens 且单调，直接信任（真实运行器产出）
  const hasCumulative = pts.every((p) => p.cumulativeTokens !== null)
  const monotonic =
    hasCumulative &&
    pts.every((p, i) => i === 0 || (p.cumulativeTokens as number) >= (pts[i - 1].cumulativeTokens as number))

  if (hasCumulative && monotonic) {
    // 真实运行器可能产出**采样**时间轴：t 是全局事件序号（可稀疏、最大可达数千），
    // cumulativeTokens 才是权威值，tokens 字段常为 0。
    // 因此：
    //   - 保留原始 t（X 轴 = 真实事件序号，符合契约 §6.4 语义）
    //   - 单点 token 用相邻 cumulative 差分推导（tokens 字段为 0 时），
    //     这样「单事件」口径也有意义
    // t 不严格递增时（重复/乱序），退回按序号重排。
    const tStrictlyIncreasing = pts.every((p, i) => i === 0 || p.t > pts[i - 1].t)

    return pts.map((p, i) => {
      const cur = p.cumulativeTokens as number
      const prev = i > 0 ? (pts[i - 1].cumulativeTokens as number) : 0
      const delta = Math.max(0, cur - prev)
      return {
        t: tStrictlyIncreasing ? p.t : i,
        cumulativeTokens: cur,
        // 优先用差分（权威），tokens 字段仅在差分不可用时兜底
        tokens: delta > 0 ? delta : p.tokens || 0,
        stage: p.stage,
        turn: p.turn,
      }
    })
  }

  // 否则自行累加（优先用 tokens，缺失则用相邻 cumulative 差分）
  let acc = 0
  return pts.map((p, i) => {
    let tokens = p.tokens
    if (!tokens && p.cumulativeTokens !== null) {
      const prev = i > 0 ? pts[i - 1].cumulativeTokens : 0
      tokens = Math.max(0, (p.cumulativeTokens as number) - (prev ?? 0))
    }
    acc += tokens
    return { t: i, cumulativeTokens: acc, tokens, stage: p.stage, turn: p.turn }
  })
}

/* ------------------------------------------------------------------ *
 * 单个实验归一化
 * ------------------------------------------------------------------ */
function normalizeExperiment(
  raw: Experiment,
  warnings: string[],
  index: number,
): ExperimentVM | null {
  if (!raw || typeof raw !== 'object') {
    warnings.push(`experiments[${index}] 不是有效对象，已跳过`)
    return null
  }

  const experimentId = str(raw.experimentId, `EXPERIMENT_${index + 1}`)
  const stages = normalizeStages(raw.stages, warnings, experimentId)

  // token 总量：优先用顶层字段，其次用 stages 求和
  const stageTokens = stages.reduce((a, s) => a + s.totalTokens, 0)
  const stageInput = stages.reduce((a, s) => a + s.inputTokens, 0)
  const stageOutput = stages.reduce((a, s) => a + s.outputTokens, 0)
  const stageCached = stages.reduce((a, s) => a + s.cachedInputTokens, 0)
  const stageCost = stages.reduce((a, s) => a + s.totalCostUsd, 0)
  const stageLatency = stages.reduce((a, s) => a + s.totalLatencyMs, 0)

  const inputTokens = num(raw.inputTokens, stageInput)
  const outputTokens = num(raw.outputTokens, stageOutput)
  let totalTokens = num(raw.totalTokens, inputTokens + outputTokens || stageTokens)
  if (totalTokens === 0) totalTokens = stageTokens

  const cachedInputTokens = num(raw.cachedInputTokens, stageCached)
  // 缓存命中率：优先顶层；否则用 cachedInput / input 推算
  let cacheHitRate = num(raw.cacheHitRate, -1)
  if (cacheHitRate < 0) {
    cacheHitRate = inputTokens > 0 ? cachedInputTokens / inputTokens : 0
  }
  cacheHitRate = Math.min(1, Math.max(0, cacheHitRate))

  const totalCostUsd = num(raw.totalCostUsd, stageCost)
  const turns = num(raw.turns, 0)
  const totalLatencyMs = num(raw.totalLatencyMs, stageLatency)
  const avgLatencyMs = num(raw.avgLatencyMs, turns > 0 ? totalLatencyMs / turns : 0)

  // 质量分：qualityScore(0~1) 与 qualityScorePct(0~100) 互为备份
  let qualityScore = num(raw.qualityScore, -1)
  let qualityScorePct = num(raw.qualityScorePct, -1)
  if (qualityScore < 0 && qualityScorePct >= 0) qualityScore = qualityScorePct / 100
  if (qualityScorePct < 0 && qualityScore >= 0) qualityScorePct = qualityScore * 100
  if (qualityScore < 0) qualityScore = 0
  if (qualityScorePct < 0) qualityScorePct = 0

  const tokensPerTurn = num(raw.tokensPerTurn, turns > 0 ? totalTokens / turns : 0)
  const costPerTurnUsd = num(raw.costPerTurnUsd, turns > 0 ? totalCostUsd / turns : 0)

  const tokensByModel = Object.entries(raw.tokensByModel ?? {})
    .filter(([, v]) => typeof v === 'number' && Number.isFinite(v))
    .map(([model, tokens]) => ({ model, tokens: tokens as number }))
    .sort((a, b) => b.tokens - a.tokens)

  const timeline = normalizeTimeline(raw.timeline, warnings, experimentId)

  return {
    experimentId,
    displayName: str(raw.displayName, experimentId),
    chunkStrategy: str(raw.chunkStrategy, '—'),
    retrievalStrategy: str(raw.retrievalStrategy, '—'),
    routingStrategy: str(raw.routingStrategy, '—'),
    cacheStrategy: str(raw.cacheStrategy, '—'),
    inputTokens,
    outputTokens,
    totalTokens,
    cachedInputTokens,
    cacheHitRate,
    totalCostUsd,
    totalLatencyMs,
    avgLatencyMs,
    turns,
    qualityScore,
    qualityScorePct,
    tokensPerTurn,
    costPerTurnUsd,
    stages,
    tokensByModel,
    timeline,
    chunkSize: typeof raw.chunkSize === 'number' ? raw.chunkSize : undefined,
    topK: typeof raw.topK === 'number' ? raw.topK : undefined,
    models: Array.isArray(raw.models) ? raw.models.filter((m) => typeof m === 'string') : [],
  }
}

/* ------------------------------------------------------------------ *
 * deltas[] 归一化：缺失的百分比字段置 null（UI 显示 "—" 而不是 0）
 * ------------------------------------------------------------------ */
function normalizeDeltas(raw: unknown, warnings: string[]): DeltaVM[] {
  if (!Array.isArray(raw) || raw.length === 0) {
    if (raw !== undefined) warnings.push('缺少 deltas[]，对比结论区块将不显示')
    return []
  }
  const optNum = (v: unknown): number | null =>
    typeof v === 'number' && Number.isFinite(v) ? v : null

  return raw
    .filter((d): d is Record<string, unknown> => !!d && typeof d === 'object')
    .map((d, i) => {
      const baseline = str(d.baseline, '?')
      const candidate = str(d.candidate, `?`)
      return {
        baseline,
        candidate,
        tokenSavingPct: optNum(d.tokenSavingPct),
        qualityDeltaPct: optNum(d.qualityDeltaPct),
        costSavingPct: optNum(d.costSavingPct),
        latencyDeltaPct: optNum(d.latencyDeltaPct),
        verdict: str(d.verdict, `${baseline} → ${candidate}`),
        _i: i,
      } as DeltaVM
    })
}

/* ------------------------------------------------------------------ *
 * 顶层
 * ------------------------------------------------------------------ */
export function normalizeSummary(input: unknown): DashboardData {
  const warnings: string[] = []
  const raw = (input && typeof input === 'object' ? input : {}) as Summary

  if (!input || typeof input !== 'object') {
    warnings.push('summary.json 根节点不是对象，已按空数据处理')
  }

  const rawExperiments = Array.isArray(raw.experiments) ? raw.experiments : []
  if (rawExperiments.length === 0) {
    warnings.push('summary.json 中没有任何实验（experiments[] 为空）')
  }

  const experiments: ExperimentVM[] = []
  for (let i = 0; i < rawExperiments.length; i++) {
    const vm = normalizeExperiment(rawExperiments[i], warnings, i)
    if (vm) experiments.push(vm)
  }

  // 去重（同 experimentId 保留第一个）
  const seen = new Set<string>()
  const deduped: ExperimentVM[] = []
  for (const e of experiments) {
    if (seen.has(e.experimentId)) {
      warnings.push(`实验 ID 重复：${e.experimentId}，仅保留第一个`)
      continue
    }
    seen.add(e.experimentId)
    deduped.push(e)
  }

  const totalTokens =
    deduped.reduce((a, e) => a + e.totalTokens, 0) || num(raw.totals?.totalTokens)
  const totalCostUsd =
    deduped.reduce((a, e) => a + e.totalCostUsd, 0) || num(raw.totals?.totalCostUsd)
  const totalLatencyMs = deduped.reduce((a, e) => a + e.totalLatencyMs, 0)
  const events = deduped.reduce((a, e) => a + e.stages.reduce((x, s) => x + s.calls, 0), 0)

  const ds = raw.dataset ?? {}
  const dataset = {
    docs: num(ds.docs),
    conversations: num(ds.conversations),
    turns: num(ds.turns),
  }

  return {
    runId: str(raw.runId, '未命名运行'),
    generatedAt: str(raw.generatedAt, ''),
    dataset,
    experiments: deduped,
    deltas: normalizeDeltas(raw.deltas, warnings),
    totals: {
      experiments: deduped.length,
      events: events || num(raw.totals?.events),
      totalTokens,
      totalCostUsd,
      totalLatencyMs: totalLatencyMs || num(raw.totals?.totalLatencyMs),
      turns: num(raw.totals?.turns, dataset.turns),
    },
    isExample: Boolean(
      (raw.environment as Record<string, unknown> | undefined)?.example,
    ),
    warnings: [...new Set(warnings)],
  }
}

/** 完全空的数据集：fetch 失败时用它渲染「等待数据」而不是白屏 */
export function emptyDashboard(): DashboardData {
  return {
    runId: '',
    generatedAt: '',
    dataset: { docs: 0, conversations: 0, turns: 0 },
    experiments: [],
    deltas: [],
    totals: {
      experiments: 0,
      events: 0,
      totalTokens: 0,
      totalCostUsd: 0,
      totalLatencyMs: 0,
      turns: 0,
    },
    isExample: false,
    warnings: [],
  }
}
