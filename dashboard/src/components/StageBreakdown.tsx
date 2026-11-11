import { useMemo, useState } from 'react'
import Chart from './Chart'
import type { DashboardData, ExperimentVM } from '../types'
import type { EChartsOption } from '../charts/echarts'
import { fmtCompact, fmtInt, fmtPct, fmtUsd } from '../utils/format'

interface Props {
  data: DashboardData
}

/** 环形图中心文字：用 graphic 渲染，随选中实验动态变化 */
function centerGraphic(total: number, label: string) {
  return [
    {
      type: 'text',
      left: 'center',
      top: '42%',
      style: {
        text: fmtCompact(total),
        fill: '#e6eaf2',
        fontSize: 22,
        fontWeight: 600,
        fontFamily: 'Inter, sans-serif',
        textAlign: 'center',
      },
    },
    {
      type: 'text',
      left: 'center',
      top: '52%',
      style: {
        text: label,
        fill: '#6b7688',
        fontSize: 11,
        textAlign: 'center',
      },
    },
  ]
}

/**
 * 视图 2：各环节 Token 占比（契约 §7.2）
 *
 * - 环形图展示单个实验的环节构成
 * - 顶部可切换实验（分段控制器，实验多于 4 个时用下拉）
 * - 右侧明细列表：占比条 + 调用次数 + 成本，比纯图例信息量更大
 * - 支持在「Token 占比 / 成本占比 / 耗时占比」之间切换口径
 */
export default function StageBreakdown({ data }: Props) {
  const { experiments } = data
  const [selectedId, setSelectedId] = useState<string>(
    experiments[0]?.experimentId ?? '',
  )
  const [metric, setMetric] = useState<'tokens' | 'cost' | 'latency'>('tokens')

  const selected: ExperimentVM | undefined =
    experiments.find((e) => e.experimentId === selectedId) ?? experiments[0]

  /** 当前口径下每个环节的值 */
  const rows = useMemo(() => {
    if (!selected) return []
    return selected.stages
      .map((s) => ({
        ...s,
        value:
          metric === 'tokens'
            ? s.totalTokens
            : metric === 'cost'
              ? s.totalCostUsd
              : s.totalLatencyMs,
      }))
      .filter((s) => s.value > 0)
      .sort((a, b) => b.value - a.value)
  }, [selected, metric])

  const totalValue = rows.reduce((a, r) => a + r.value, 0)

  const option = useMemo<EChartsOption>(() => {
    if (!selected || rows.length === 0) return {}
    const fmtVal = (v: number) =>
      metric === 'tokens'
        ? `${fmtInt(v)} tokens`
        : metric === 'cost'
          ? fmtUsd(v, 4)
          : `${(v / 1000).toFixed(1)} s`

    return {
      animationDuration: 600,
      animationEasing: 'cubicOut',
      tooltip: {
        trigger: 'item',
        formatter: (p: unknown) => {
          const d = p as { name: string; value: number; percent: number; color: string }
          return (
            `<div style="display:flex;align-items:center;gap:8px">` +
            `<span style="width:8px;height:8px;border-radius:2px;background:${d.color};flex:none"></span>` +
            `<span style="color:#e6eaf2;font-weight:600">${d.name}</span></div>` +
            `<div style="margin-top:4px;color:#98a2b3;font-size:11px">` +
            `${fmtVal(d.value)} · 占比 <span style="color:#e6eaf2">${d.percent.toFixed(
              2,
            )}%</span></div>`
          )
        },
      },
      legend: { show: false },
      graphic: centerGraphic(
        totalValue,
        metric === 'tokens' ? '总 token' : metric === 'cost' ? '总成本' : '总耗时',
      ),
      series: [
        {
          name: metric === 'tokens' ? 'Token 占比' : metric === 'cost' ? '成本占比' : '耗时占比',
          type: 'pie',
          radius: ['52%', '76%'],
          center: ['50%', '50%'],
          avoidLabelOverlap: true,
          itemStyle: {
            borderColor: '#0f1218',
            borderWidth: 2,
            borderRadius: 3,
          },
          label: {
            show: true,
            position: 'outside',
            color: '#98a2b3',
            fontSize: 10,
            formatter: (p: unknown) => {
              const d = p as { name: string; percent: number }
              // 占比过小的标签隐藏，避免重叠
              return d.percent < 3 ? '' : `${d.name}\n${d.percent.toFixed(1)}%`
            },
            lineHeight: 13,
          },
          labelLine: {
            length: 8,
            length2: 6,
            lineStyle: { color: '#333c4e' },
          },
          emphasis: {
            scaleSize: 6,
            itemStyle: { shadowBlur: 12, shadowColor: 'rgba(0,0,0,0.6)' },
            label: { fontSize: 11, color: '#e6eaf2' },
          },
          data: rows.map((r) => ({
            name: r.label,
            value: Number(r.value.toFixed(6)),
            itemStyle: { color: r.color },
          })),
        },
      ],
    } as EChartsOption
  }, [rows, selected, metric, totalValue])

  if (!selected) {
    return (
      <div className="panel">
        <div className="panel-header">
          <h2 className="panel-title">各环节 Token 占比</h2>
        </div>
        <div className="p-4">
          <Chart option={{}} height={320} isEmpty emptyHint="暂无实验数据" />
        </div>
      </div>
    )
  }

  return (
    <div className="panel flex h-full flex-col">
      <div className="panel-header flex-wrap">
        <div>
          <h2 className="panel-title">各环节 Token 占比</h2>
          <p className="panel-sub">{selected.displayName}</p>
        </div>
        <div className="seg" role="tablist" aria-label="统计口径">
          {(
            [
              ['tokens', 'Token'],
              ['cost', '成本'],
              ['latency', '耗时'],
            ] as const
          ).map(([k, label]) => (
            <button
              key={k}
              type="button"
              role="tab"
              aria-selected={metric === k}
              onClick={() => setMetric(k)}
              className={`seg-item ${metric === k ? 'seg-item-active' : ''}`}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      {/* 实验切换 */}
      <div className="flex flex-wrap gap-1.5 border-b border-ink-750/70 px-4 py-2">
        {experiments.map((e) => {
          const active = e.experimentId === selected.experimentId
          return (
            <button
              key={e.experimentId}
              type="button"
              onClick={() => setSelectedId(e.experimentId)}
              className={`rounded-md px-2 py-1 text-[11px] font-medium transition-colors ${
                active
                  ? 'bg-accent/15 text-accent-soft ring-1 ring-inset ring-accent/40'
                  : 'text-ink-400 hover:bg-ink-800/70 hover:text-ink-200'
              }`}
              aria-pressed={active}
            >
              {e.experimentId}
            </button>
          )
        })}
      </div>

      <div className="grid flex-1 grid-cols-1 gap-2 p-2 lg:grid-cols-[minmax(0,1fr)_minmax(0,0.9fr)]">
        <Chart
          option={option}
          height={300}
          isEmpty={rows.length === 0}
          emptyHint="该实验缺少 stages[] 数据"
        />

        {/* 明细列表：占比条 + 调用/成本 */}
        <div className="max-h-[300px] overflow-y-auto pr-1">
          <table className="w-full border-collapse text-[11px]">
            <thead className="sticky top-0 z-10 bg-ink-850/95 backdrop-blur">
              <tr className="text-left text-[10px] uppercase tracking-wider text-ink-500">
                <th className="py-1.5 pr-2 font-medium">环节</th>
                <th className="py-1.5 pr-2 text-right font-medium">占比</th>
                <th className="py-1.5 pr-2 text-right font-medium">调用</th>
                <th className="py-1.5 text-right font-medium">成本</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const pct = totalValue > 0 ? r.value / totalValue : 0
                return (
                  <tr
                    key={r.stage}
                    className="group border-t border-ink-750/50 transition-colors hover:bg-ink-800/50"
                  >
                    <td className="py-1.5 pr-2">
                      <div className="flex items-center gap-1.5">
                        <span
                          className="h-2 w-2 shrink-0 rounded-sm"
                          style={{ background: r.color }}
                        />
                        <span className="truncate text-ink-200">{r.label}</span>
                      </div>
                      {/* 占比条 */}
                      <div className="mt-1 h-[3px] w-full overflow-hidden rounded-full bg-ink-800">
                        <div
                          className="h-full rounded-full transition-all duration-500"
                          style={{ width: `${Math.max(pct * 100, 0.8)}%`, background: r.color }}
                        />
                      </div>
                    </td>
                    <td className="tabular py-1.5 pr-2 text-right text-ink-100">
                      {fmtPct(pct, 1)}
                    </td>
                    <td className="tabular py-1.5 pr-2 text-right text-ink-400">
                      {fmtInt(r.calls)}
                    </td>
                    <td className="tabular py-1.5 text-right text-ink-300">
                      {fmtUsd(r.totalCostUsd, 3)}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          {rows.length === 0 && (
            <p className="py-6 text-center text-[11px] text-ink-500">暂无环节数据</p>
          )}
        </div>
      </div>

      {/* 底部汇总 */}
      <div className="flex items-center justify-between border-t border-ink-750/70 px-4 py-2 text-[10px] text-ink-500">
        <span>
          {rows.length} 个环节 · 合计{' '}
          <span className="tabular text-ink-300">{fmtInt(totalValue)}</span>{' '}
          {metric === 'tokens' ? 'tokens' : metric === 'cost' ? 'USD' : 'ms'}
        </span>
        <span className="tabular">
          缓存命中 {fmtPct(selected.cacheHitRate, 1)} · 单轮{' '}
          {fmtCompact(selected.tokensPerTurn)} tokens
        </span>
      </div>
    </div>
  )
}
