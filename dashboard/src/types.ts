/**
 * summary.json 的类型定义。
 *
 * 唯一事实来源：token-bench/CONTRACT.md §6.4。
 * 所有字段都按「可选 / 可缺失」对待——真实运行器可能少产出某些字段，
 * 页面必须在缺字段时优雅降级，而不是抛异常。
 */

/** 计量环节（CONTRACT §4.1） */
export type MeterStage =
  | 'PARSE'
  | 'CHUNK'
  | 'EMBED'
  | 'INTENT'
  | 'REWRITE'
  | 'RETRIEVE_VECTOR'
  | 'RETRIEVE_KEYWORD'
  | 'RETRIEVE_GRAPH'
  | 'RETRIEVE_WEB'
  | 'FUSION'
  | 'RERANK'
  | 'GENERATE'
  | 'JUDGE'
  | 'SUMMARY'
  | 'OTHER'

export const STAGE_LABELS: Record<MeterStage, string> = {
  PARSE: '文档解析',
  CHUNK: '分块',
  EMBED: '向量化',
  INTENT: '意图识别',
  REWRITE: '问题改写',
  RETRIEVE_VECTOR: '向量召回',
  RETRIEVE_KEYWORD: '关键词召回',
  RETRIEVE_GRAPH: '图谱召回',
  RETRIEVE_WEB: '联网搜索',
  FUSION: '多路融合',
  RERANK: '精排',
  GENERATE: '答案生成',
  JUDGE: '质量评分',
  SUMMARY: '会话摘要',
  OTHER: '其他',
}

/** 环节在图表中的固定配色（暗色背景下高可读） */
export const STAGE_COLORS: Record<MeterStage, string> = {
  PARSE: '#64748b',
  CHUNK: '#7c8ba1',
  EMBED: '#22d3ee',
  INTENT: '#a78bfa',
  REWRITE: '#c084fc',
  RETRIEVE_VECTOR: '#5b8cff',
  RETRIEVE_KEYWORD: '#38bdf8',
  RETRIEVE_GRAPH: '#2dd4bf',
  RETRIEVE_WEB: '#34d399',
  FUSION: '#94a3b8',
  RERANK: '#f7b955',
  GENERATE: '#3ddc97',
  JUDGE: '#ff8fa3',
  SUMMARY: '#fb923c',
  OTHER: '#6b7688',
}

export type ChunkStrategy = 'FIXED_SIZE' | 'SEMANTIC'
export type RetrievalStrategy = 'VECTOR_ONLY' | 'MULTI_CHANNEL'
export type RoutingStrategy = 'FIXED_PREMIUM' | 'INTENT_ROUTED'
export type CacheStrategy = 'DISABLED' | 'ENABLED'

export const CHUNK_LABELS: Record<string, string> = {
  FIXED_SIZE: '固定大小',
  SEMANTIC: '语义分块',
}
export const RETRIEVAL_LABELS: Record<string, string> = {
  VECTOR_ONLY: '仅向量',
  MULTI_CHANNEL: '多路融合',
}
export const ROUTING_LABELS: Record<string, string> = {
  FIXED_PREMIUM: '固定高档',
  INTENT_ROUTED: '意图路由',
}
export const CACHE_LABELS: Record<string, string> = {
  DISABLED: '关闭',
  ENABLED: '开启',
}

/** CONTRACT §4.8 StageAggregate */
export interface StageAggregate {
  stage: MeterStage | string
  calls?: number
  inputTokens?: number
  outputTokens?: number
  cachedInputTokens?: number
  cacheHits?: number
  totalCostUsd?: number
  totalLatencyMs?: number
}

/** CONTRACT §6.4 timeline 单点 */
export interface TimelinePoint {
  t: number
  cumulativeTokens?: number
  stage?: MeterStage | string
  tokens?: number
  turn?: number
}

/** CONTRACT §6.4 experiments[] 单项 */
export interface Experiment {
  experimentId: string
  displayName?: string
  chunkStrategy?: ChunkStrategy | string
  retrievalStrategy?: RetrievalStrategy | string
  routingStrategy?: RoutingStrategy | string
  cacheStrategy?: CacheStrategy | string
  inputTokens?: number
  outputTokens?: number
  totalTokens?: number
  cachedInputTokens?: number
  cacheHitRate?: number
  totalCostUsd?: number
  totalLatencyMs?: number
  avgLatencyMs?: number
  turns?: number
  qualityScore?: number
  qualityScorePct?: number
  tokensPerTurn?: number
  costPerTurnUsd?: number
  stages?: StageAggregate[]
  tokensByModel?: Record<string, number>
  timeline?: TimelinePoint[]
  // 运行器可能额外带的配置字段（示例数据里有，真实数据可能有）
  chunkSize?: number
  chunkOverlap?: number
  topK?: number
  rerankCandidateLimit?: number
  models?: string[]
  stageModels?: Record<string, string>
}

/** CONTRACT §6.4 deltas[] 单项 */
export interface Delta {
  baseline: string
  candidate: string
  tokenSavingPct?: number
  qualityDeltaPct?: number
  costSavingPct?: number
  latencyDeltaPct?: number
  verdict?: string
}

/** CONTRACT §6.4 顶层 */
export interface Summary {
  schemaVersion?: string
  runId?: string
  generatedAt?: string
  dataset?: { docs?: number; conversations?: number; turns?: number }
  environment?: Record<string, unknown>
  totals?: {
    experiments?: number
    events?: number
    totalTokens?: number
    totalCostUsd?: number
    totalLatencyMs?: number
    turns?: number
  }
  experiments?: Experiment[]
  deltas?: Delta[]
}

/* ------------------------------------------------------------------ *
 * 归一化后的视图模型
 * 所有派生字段在此算好，图表组件只消费确定存在的数字，
 * 从而把「数据缺失」的处理集中在 utils/normalize.ts 一处。
 * ------------------------------------------------------------------ */
export interface StageVM {
  stage: MeterStage | string
  label: string
  color: string
  calls: number
  inputTokens: number
  outputTokens: number
  totalTokens: number
  cachedInputTokens: number
  cacheHits: number
  totalCostUsd: number
  totalLatencyMs: number
}

export interface TimelinePointVM {
  t: number
  cumulativeTokens: number
  tokens: number
  stage: string
  turn: number
}

export interface ExperimentVM {
  experimentId: string
  displayName: string
  chunkStrategy: string
  retrievalStrategy: string
  routingStrategy: string
  cacheStrategy: string
  inputTokens: number
  outputTokens: number
  totalTokens: number
  cachedInputTokens: number
  cacheHitRate: number
  totalCostUsd: number
  totalLatencyMs: number
  avgLatencyMs: number
  turns: number
  qualityScore: number
  qualityScorePct: number
  tokensPerTurn: number
  costPerTurnUsd: number
  stages: StageVM[]
  tokensByModel: Array<{ model: string; tokens: number }>
  timeline: TimelinePointVM[]
  chunkSize?: number
  topK?: number
  models: string[]
}

export interface DeltaVM {
  baseline: string
  candidate: string
  tokenSavingPct: number | null
  qualityDeltaPct: number | null
  costSavingPct: number | null
  latencyDeltaPct: number | null
  verdict: string
}

export interface DashboardData {
  runId: string
  generatedAt: string
  dataset: { docs: number; conversations: number; turns: number }
  experiments: ExperimentVM[]
  deltas: DeltaVM[]
  totals: {
    experiments: number
    events: number
    totalTokens: number
    totalCostUsd: number
    totalLatencyMs: number
    turns: number
  }
  isExample: boolean
  /** 数据加载/解析过程中的非致命问题，页面顶部以横幅提示 */
  warnings: string[]
}
