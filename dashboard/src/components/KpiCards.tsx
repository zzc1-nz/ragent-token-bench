import type { DashboardData, ExperimentVM } from '../types'
import { fmtCompact, fmtInt, fmtMs, fmtPct, fmtUsd } from '../utils/format'

interface Props {
  data: DashboardData
}

interface Kpi {
  key: string
  label: string
  value: string
  unit?: string
  sub: string
  accent: string
  /** 可选的迷你趋势数据（sparkline） */
  spark?: number[]
  sparkColor?: string
}

/** 生成 SVG 折线 sparkline 的 path（归一化到给定宽高） */
function sparkPath(values: number[], w: number, h: number): string {
  if (values.length < 2) return ''
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || 1
  return values
    .map((v, i) => {
      const x = (i / (values.length - 1)) * w
      const y = h - ((v - min) / span) * h
      return `${i === 0 ? 'M' : 'L'}${x.toFixed(2)},${y.toFixed(2)}`
    })
    .join(' ')
}

function Spark({ values, color }: { values: number[]; color: string }) {
  if (values.length < 2) return null
  const w = 96
  const h = 26
  const d = sparkPath(values, w, h)
  const gradId = `sp-${color.replace('#', '')}`
  return (
    <svg
      width={w}
      height={h}
      viewBox={`0 0 ${w} ${h}`}
      className="shrink-0 opacity-80"
      aria-hidden="true"
    >
      <defs>
        <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.28" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={`${d} L${w},${h} L0,${h} Z`} fill={`url(#${gradId})`} />
      <path d={d} fill="none" stroke={color} strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  )
}

/**
 * 顶部 KPI 卡片区。
 * 契约要求 4 个：总 token / 总成本 / 平均质量分 / 最佳缓存命中率。
 * 额外补 2 个增强可观测性的指标（事件数、平均单轮耗时），共 6 张。
 */
export default function KpiCards({ data }: Props) {
  const { experiments, totals } = data
  if (experiments.length === 0) return null

  const avgQuality =
    experiments.reduce((a, e) => a + e.qualityScorePct, 0) / experiments.length

  const bestCache = experiments.reduce<ExperimentVM | null>(
    (best, e) => (!best || e.cacheHitRate > best.cacheHitRate ? e : best),
    null,
  )

  const bestQuality = experiments.reduce<ExperimentVM | null>(
    (best, e) => (!best || e.qualityScorePct > best.qualityScorePct ? e : best),
    null,
  )

  const cheapest = experiments.reduce<ExperimentVM | null>(
    (best, e) => (!best || e.totalCostUsd < best.totalCostUsd ? e : best),
    null,
  )

  const turns = totals.turns || experiments[0]?.turns || 0

  // sparkline：按实验顺序的质量分 / 成本曲线（用于给 KPI 一点趋势感）
  const qualitySpark = experiments.map((e) => e.qualityScorePct)
  const costSpark = experiments.map((e) => e.totalCostUsd)
  const tokenSpark = experiments.map((e) => e.totalTokens)

  const kpis: Kpi[] = [
    {
      key: 'tokens',
      label: '累计 Token 消耗',
      value: fmtCompact(totals.totalTokens),
      unit: 'tokens',
      sub: `${totals.experiments} 组实验 · ${fmtInt(totals.totalTokens)} 精确值`,
      accent: '#5b8cff',
      spark: tokenSpark,
      sparkColor: '#5b8cff',
    },
    {
      key: 'cost',
      label: '累计成本',
      value: fmtUsd(totals.totalCostUsd, 2),
      sub: turns > 0 ? `${fmtUsd(totals.totalCostUsd / turns)} / 轮（全部实验合计）` : '全部实验合计',
      accent: '#f7b955',
      spark: costSpark,
      sparkColor: '#f7b955',
    },
    {
      key: 'quality',
      label: '平均质量分',
      value: avgQuality.toFixed(2),
      unit: '%',
      sub: bestQuality
        ? `最高 ${bestQuality.experimentId} · ${bestQuality.qualityScorePct.toFixed(2)}%`
        : '—',
      accent: '#3ddc97',
      spark: qualitySpark,
      sparkColor: '#3ddc97',
    },
    {
      key: 'cache',
      label: '最佳缓存命中率',
      value: bestCache ? fmtPct(bestCache.cacheHitRate, 1) : '—',
      sub: bestCache
        ? `${bestCache.experimentId} · 缓存${bestCache.cacheStrategy === 'ENABLED' ? '开启' : '关闭'}`
        : '无缓存数据',
      accent: '#a78bfa',
      spark: experiments.map((e) => e.cacheHitRate * 100),
      sparkColor: '#a78bfa',
    },
    {
      key: 'events',
      label: '计量事件数',
      value: fmtCompact(totals.events),
      unit: '条',
      sub: `${fmtInt(totals.events)} 条 TokenUsage 记录`,
      accent: '#22d3ee',
    },
    {
      key: 'latency',
      label: '最低单轮耗时',
      value: cheapest ? fmtMs(cheapest.avgLatencyMs) : '—',
      sub: cheapest ? `${cheapest.experimentId} · 平均每轮` : '—',
      accent: '#ff6b81',
      spark: experiments.map((e) => e.avgLatencyMs),
      sparkColor: '#ff6b81',
    },
  ]

  return (
    <section aria-label="关键指标" className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
      {kpis.map((k) => (
        <div
          key={k.key}
          className="panel group relative overflow-hidden px-3.5 py-3 transition-colors duration-200 hover:border-ink-600"
        >
          {/* 顶部彩色细线，呼应指标色 */}
          <div
            className="absolute inset-x-0 top-0 h-px opacity-60"
            style={{ background: `linear-gradient(90deg, transparent, ${k.accent}, transparent)` }}
          />
          <div className="flex items-start justify-between gap-2">
            <span className="text-[11px] font-medium text-ink-400">{k.label}</span>
          </div>
          <div className="mt-1.5 flex items-baseline gap-1">
            <span
              className="tabular text-[22px] font-semibold leading-none tracking-tight"
              style={{ color: k.accent }}
            >
              {k.value}
            </span>
            {k.unit && <span className="text-[10px] text-ink-500">{k.unit}</span>}
          </div>
          <div className="mt-2 flex items-end justify-between gap-2">
            <span className="truncate text-[10px] leading-tight text-ink-500" title={k.sub}>
              {k.sub}
            </span>
            {k.spark && k.sparkColor && <Spark values={k.spark} color={k.sparkColor} />}
          </div>
        </div>
      ))}
    </section>
  )
}
