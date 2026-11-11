import { useMemo, useState } from 'react'
import type { DashboardData, ExperimentVM } from '../types'
import {
  fmtCompact,
  fmtInt,
  fmtMs,
  fmtPct,
  fmtUsd,
  strategyLabel,
} from '../utils/format'
import { buildColorMap } from '../utils/palette'

interface Props {
  data: DashboardData
}

type SortKey =
  | 'experimentId'
  | 'totalTokens'
  | 'tokensPerTurn'
  | 'totalCostUsd'
  | 'qualityScorePct'
  | 'cacheHitRate'
  | 'avgLatencyMs'

interface Col {
  key: SortKey
  label: string
  align: 'left' | 'right'
  width?: string
  title?: string
}

const COLS: Col[] = [
  { key: 'experimentId', label: '实验', align: 'left', width: 'minmax(150px,1.4fr)' },
  { key: 'totalTokens', label: '总 Token', align: 'right' },
  { key: 'tokensPerTurn', label: '每轮 Token', align: 'right' },
  { key: 'totalCostUsd', label: '总成本', align: 'right' },
  { key: 'qualityScorePct', label: '质量分', align: 'right' },
  { key: 'cacheHitRate', label: '缓存命中率', align: 'right' },
  { key: 'avgLatencyMs', label: '平均延迟', align: 'right' },
]

/** 相对基线的变化色：省 token / 省钱 = 绿，质量上升 = 绿 */
function deltaTone(pct: number | null, goodWhenNegative: boolean): string {
  if (pct === null) return 'text-ink-500'
  const good = goodWhenNegative ? pct < 0 : pct > 0
  if (Math.abs(pct) < 0.05) return 'text-ink-400'
  return good ? 'text-mint' : 'text-rose'
}

/**
 * 实验对比表（契约 §7 附加要求）。
 *
 * 列：实验 / 分块 / 检索 / 路由 / 缓存 / 总token / 每轮token / 成本 / 质量分 / 缓存命中率
 * 额外：可排序、基线相对变化、策略徽章、行内质量分条。
 */
export default function ComparisonTable({ data }: Props) {
  const { experiments } = data
  const [sortKey, setSortKey] = useState<SortKey>('totalTokens')
  const [asc, setAsc] = useState(true)

  const colorMap = useMemo(
    () => buildColorMap(experiments.map((e) => e.experimentId)),
    [experiments],
  )

  /** 基线：优先 A_BASELINE，否则第一个实验 */
  const baseline: ExperimentVM | undefined =
    experiments.find((e) => /BASELINE/i.test(e.experimentId)) ?? experiments[0]

  const sorted = useMemo(() => {
    const arr = [...experiments]
    arr.sort((a, b) => {
      const av = a[sortKey]
      const bv = b[sortKey]
      let cmp: number
      if (typeof av === 'number' && typeof bv === 'number') cmp = av - bv
      else cmp = String(av).localeCompare(String(bv))
      return asc ? cmp : -cmp
    })
    return arr
  }, [experiments, sortKey, asc])

  const maxQuality = Math.max(...experiments.map((e) => e.qualityScorePct), 0.0001)
  const minTokens = Math.min(...experiments.map((e) => e.totalTokens), Infinity)

  const onSort = (key: SortKey) => {
    if (key === sortKey) setAsc((v) => !v)
    else {
      setSortKey(key)
      setAsc(key === 'experimentId')
    }
  }

  if (experiments.length === 0) {
    return (
      <div className="panel">
        <div className="panel-header">
          <h2 className="panel-title">实验对比表</h2>
        </div>
        <p className="px-4 py-8 text-center text-[11px] text-ink-500">
          暂无实验数据（summary.json 的 experiments[] 为空）
        </p>
      </div>
    )
  }

  const relPct = (v: number, base: number): number | null =>
    base > 0 ? ((v - base) / base) * 100 : null

  return (
    <div className="panel overflow-hidden">
      <div className="panel-header">
        <div>
          <h2 className="panel-title">实验对比表</h2>
          <p className="panel-sub">
            6 组策略组合横向对比 · 变化列相对基线 {baseline?.experimentId ?? '—'}
          </p>
        </div>
        <span className="badge bg-ink-800 text-ink-400">{experiments.length} 组</span>
      </div>

      <div className="overflow-x-auto">
        <table className="w-full min-w-[1080px] border-collapse text-[11.5px]">
          <thead>
            <tr className="border-b border-ink-750/80 text-[10px] uppercase tracking-wider text-ink-500">
              <th className="px-3 py-2 text-left font-medium">实验</th>
              <th className="px-2 py-2 text-left font-medium">策略组合</th>
              {COLS.slice(1).map((c) => (
                <th
                  key={c.key}
                  className="cursor-pointer select-none px-2.5 py-2 text-right font-medium
                    transition-colors hover:text-ink-300"
                  onClick={() => onSort(c.key)}
                  title={`按 ${c.label} 排序`}
                >
                  <span className="inline-flex items-center gap-1">
                    {c.label}
                    <span
                      className={`text-[9px] transition-opacity ${
                        sortKey === c.key ? 'text-accent opacity-100' : 'opacity-0'
                      }`}
                    >
                      {asc ? '▲' : '▼'}
                    </span>
                  </span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {sorted.map((e) => {
              const isBaseline = e.experimentId === baseline?.experimentId
              const isBestQuality = e.qualityScorePct === maxQuality
              const isCheapestTokens = e.totalTokens === minTokens
              const dToken = baseline ? relPct(e.totalTokens, baseline.totalTokens) : null
              const dCost = baseline ? relPct(e.totalCostUsd, baseline.totalCostUsd) : null
              const dQuality = baseline
                ? relPct(e.qualityScorePct, baseline.qualityScorePct)
                : null

              return (
                <tr
                  key={e.experimentId}
                  className={`border-b border-ink-750/40 transition-colors last:border-0
                    hover:bg-ink-800/45 ${isBaseline ? 'bg-ink-800/25' : ''}`}
                >
                  {/* 实验名 + 色块 */}
                  <td className="px-3 py-2.5">
                    <div className="flex items-center gap-2">
                      <span
                        className="h-2.5 w-2.5 shrink-0 rounded-sm"
                        style={{ background: colorMap[e.experimentId] }}
                      />
                      <span className="font-medium text-ink-100">{e.experimentId}</span>
                      {isBaseline && (
                        <span className="badge bg-ink-700/80 text-ink-300">基线</span>
                      )}
                      {isBestQuality && (
                        <span className="badge bg-mint/15 text-mint">最高质量</span>
                      )}
                      {isCheapestTokens && !isBestQuality && (
                        <span className="badge bg-accent/15 text-accent-soft">最省 token</span>
                      )}
                    </div>
                    <div className="mt-0.5 truncate pl-4.5 text-[10px] text-ink-500" title={e.displayName}>
                      {e.displayName}
                    </div>
                  </td>

                  {/* 策略组合徽章 */}
                  <td className="px-2 py-2.5">
                    <div className="flex flex-wrap gap-1">
                      <span className="badge bg-ink-800 text-ink-300">
                        {strategyLabel(e.chunkStrategy)}
                      </span>
                      <span className="badge bg-ink-800 text-ink-300">
                        {strategyLabel(e.retrievalStrategy)}
                      </span>
                      <span className="badge bg-ink-800 text-ink-300">
                        {strategyLabel(e.routingStrategy)}
                      </span>
                      <span
                        className={`badge ${
                          e.cacheStrategy === 'ENABLED'
                            ? 'bg-violet/15 text-violet'
                            : 'bg-ink-800 text-ink-500'
                        }`}
                      >
                        缓存{strategyLabel(e.cacheStrategy)}
                      </span>
                    </div>
                  </td>

                  {/* 总 token */}
                  <td className="px-2.5 py-2.5 text-right">
                    <div className="tabular font-medium text-ink-100">{fmtInt(e.totalTokens)}</div>
                    {dToken !== null && !isBaseline && (
                      <div className={`tabular text-[10px] ${deltaTone(dToken, true)}`}>
                        {dToken > 0 ? '+' : ''}
                        {dToken.toFixed(1)}%
                      </div>
                    )}
                    {isBaseline && <div className="text-[10px] text-ink-600">基准</div>}
                  </td>

                  {/* 每轮 token */}
                  <td className="tabular px-2.5 py-2.5 text-right text-ink-300">
                    {fmtInt(e.tokensPerTurn)}
                  </td>

                  {/* 成本 */}
                  <td className="px-2.5 py-2.5 text-right">
                    <div className="tabular font-medium text-amber">{fmtUsd(e.totalCostUsd, 4)}</div>
                    {dCost !== null && !isBaseline && (
                      <div className={`tabular text-[10px] ${deltaTone(dCost, true)}`}>
                        {dCost > 0 ? '+' : ''}
                        {dCost.toFixed(1)}%
                      </div>
                    )}
                    {isBaseline && (
                      <div className="tabular text-[10px] text-ink-600">
                        {fmtUsd(e.costPerTurnUsd, 5)}/轮
                      </div>
                    )}
                  </td>

                  {/* 质量分 + 条 */}
                  <td className="px-2.5 py-2.5 text-right">
                    <div className="tabular font-medium text-mint">
                      {e.qualityScorePct.toFixed(2)}%
                    </div>
                    <div className="mt-1 ml-auto h-[3px] w-14 overflow-hidden rounded-full bg-ink-800">
                      <div
                        className="h-full rounded-full bg-mint/80"
                        style={{ width: `${(e.qualityScorePct / 100) * 100}%` }}
                      />
                    </div>
                    {dQuality !== null && !isBaseline && (
                      <div className={`tabular mt-0.5 text-[10px] ${deltaTone(dQuality, false)}`}>
                        {dQuality > 0 ? '+' : ''}
                        {dQuality.toFixed(2)}%
                      </div>
                    )}
                  </td>

                  {/* 缓存命中率 */}
                  <td className="px-2.5 py-2.5 text-right">
                    <div
                      className={`tabular font-medium ${
                        e.cacheHitRate > 0 ? 'text-violet' : 'text-ink-500'
                      }`}
                    >
                      {fmtPct(e.cacheHitRate, 1)}
                    </div>
                    <div className="tabular text-[10px] text-ink-600">
                      {fmtCompact(e.cachedInputTokens)} cached
                    </div>
                  </td>

                  {/* 平均延迟 */}
                  <td className="tabular px-2.5 py-2.5 text-right text-ink-300">
                    {fmtMs(e.avgLatencyMs)}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 border-t border-ink-750/70 px-4 py-2 text-[10px] text-ink-500">
        <span>点击表头排序</span>
        <span className="text-ink-600">|</span>
        <span>
          数据集：{fmtInt(data.dataset.docs)} 文档 · {fmtInt(data.dataset.conversations)} 会话 ·{' '}
          {fmtInt(data.dataset.turns)} 轮
        </span>
        {data.totals.events > 0 && (
          <>
            <span className="text-ink-600">|</span>
            <span>计量事件 {fmtInt(data.totals.events)} 条</span>
          </>
        )}
      </div>
    </div>
  )
}
