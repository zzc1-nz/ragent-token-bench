import { useMemo, useState } from 'react'
import Chart from './Chart'
import type { DashboardData } from '../types'
import type { EChartsOption } from '../charts/echarts'
import { fmtCompact, fmtInt, fmtPct, fmtUsd } from '../utils/format'
import { buildColorMap } from '../utils/palette'

interface Props {
  data: DashboardData
}

/**
 * 视图 1：Token 消耗时间轴（契约 §7.1）
 *
 * - X = 事件序号 t，Y = 累计 token（面积折线）
 * - 多实验叠加，图例可点击隐藏
 * - 「阶段过滤」：只保留指定环节的事件重算累计曲线
 *   （注意：过滤后曲线末值会小于 totalTokens，这是正确的——
 *     它表示「该环节累计贡献的 token」，tooltip 会标注占比）
 * - 可切换「累计 / 每事件增量」两种口径
 */
export default function TokenTimeline({ data }: Props) {
  const { experiments } = data
  const [mode, setMode] = useState<'cumulative' | 'incremental'>('cumulative')
  const [stageFilter, setStageFilter] = useState<string>('ALL')
  const [hidden, setHidden] = useState<Set<string>>(new Set())

  const colorMap = useMemo(
    () => buildColorMap(experiments.map((e) => e.experimentId)),
    [experiments],
  )

  /** 所有实验里出现过的环节（用于过滤下拉） */
  const allStages = useMemo(() => {
    const set = new Set<string>()
    for (const e of experiments) {
      for (const p of e.timeline) if (p.stage) set.add(p.stage)
    }
    return [...set].sort()
  }, [experiments])

  /** 过滤后的系列数据 */
  const series = useMemo(() => {
    return experiments
      .filter((e) => !hidden.has(e.experimentId))
      .map((e) => {
        const pts =
          stageFilter === 'ALL' ? e.timeline : e.timeline.filter((p) => p.stage === stageFilter)

        let acc = 0
        const rows = pts.map((p) => {
          acc += p.tokens
          return mode === 'cumulative'
            ? ([p.t, acc] as [number, number])
            : ([p.t, p.tokens] as [number, number])
        })
        return {
          id: e.experimentId,
          name: e.experimentId,
          data: rows,
          total: acc,
          color: colorMap[e.experimentId] ?? '#5b8cff',
        }
      })
      .filter((s) => s.data.length > 0)
  }, [experiments, stageFilter, mode, hidden, colorMap])

  const hasData = series.length > 0

  const option = useMemo<EChartsOption>(() => {
    // t 可能稀疏（真实运行器的采样时间轴，t 为全局事件序号），
    // 因此 X 轴上限取实际最大 t，而不是点数。
    const maxT = series.reduce(
      (m, s) => Math.max(m, s.data.length > 0 ? s.data[s.data.length - 1][0] : 0),
      0,
    )

    return {
      animationDuration: 500,
      animationEasing: 'cubicOut',
      grid: { left: 8, right: 24, top: 44, bottom: 46, containLabel: true },
      legend: {
        type: 'scroll',
        top: 0,
        left: 0,
        itemGap: 16,
        textStyle: { color: '#c3cad6', fontSize: 11 },
        formatter: (name: string) => {
          const s = series.find((x) => x.name === name)
          return s ? `${name}` : name
        },
      },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'line' },
        // 事件多时关闭动画，避免 tooltip 抖动
        transitionDuration: 0.15,
        formatter: (params: unknown) => {
          const arr = (Array.isArray(params) ? params : [params]) as Array<{
            seriesName: string
            value: [number, number]
            color: string
            seriesIndex: number
          }>
          if (arr.length === 0) return ''
          const t = arr[0].value?.[0]
          const rows = arr
            .slice()
            .sort((a, b) => (b.value?.[1] ?? 0) - (a.value?.[1] ?? 0))
            .map((p) => {
              const s = series[p.seriesIndex]
              const pct =
                s && s.total > 0 && mode === 'cumulative' && stageFilter !== 'ALL'
                  ? ` (${fmtPct((p.value?.[1] ?? 0) / s.total, 1)})`
                  : ''
              return (
                `<div style="display:flex;align-items:center;gap:8px;margin-top:3px">` +
                `<span style="width:8px;height:8px;border-radius:2px;background:${p.color};flex:none"></span>` +
                `<span style="flex:1;color:#98a2b3">${p.seriesName}</span>` +
                `<span style="color:#e6eaf2;font-variant-numeric:tabular-nums">${fmtInt(
                  p.value?.[1] ?? 0,
                )}${pct}</span>` +
                `</div>`
              )
            })
            .join('')
          return (
            `<div style="font-size:11px;color:#6b7688;margin-bottom:2px">事件 #${t}` +
            `${stageFilter !== 'ALL' ? ` · 仅 ${stageFilter}` : ''}</div>` + rows
          )
        },
      },
      xAxis: {
        type: 'value',
        name: '事件序号',
        nameLocation: 'middle',
        nameGap: 28,
        min: 0,
        max: maxT > 0 ? maxT : 1,
        axisLabel: {
          formatter: (v: number) => (v >= 1000 ? `${(v / 1000).toFixed(1)}k` : String(v)),
        },
        splitLine: { show: false },
      },
      yAxis: {
        type: 'value',
        name: mode === 'cumulative' ? '累计 Token' : '每事件 Token',
        nameTextStyle: { align: 'left', padding: [0, 0, 6, -8] },
        axisLabel: { formatter: (v: number) => fmtCompact(v) },
        splitLine: { lineStyle: { color: 'rgba(255,255,255,0.055)' } },
      },
      dataZoom: [
        { type: 'inside', throttle: 40 },
        {
          type: 'slider',
          height: 16,
          bottom: 6,
          borderColor: 'transparent',
          backgroundColor: 'rgba(255,255,255,0.03)',
          fillerColor: 'rgba(91,140,255,0.14)',
          handleStyle: { color: '#4b5568', borderColor: '#4b5568' },
          moveHandleStyle: { color: '#333c4e' },
          textStyle: { color: '#6b7688', fontSize: 10 },
          dataBackground: {
            lineStyle: { color: '#333c4e' },
            areaStyle: { color: 'rgba(91,140,255,0.10)' },
          },
          selectedDataBackground: {
            lineStyle: { color: '#5b8cff' },
            areaStyle: { color: 'rgba(91,140,255,0.22)' },
          },
        },
      ],
      series: series.map((s) => ({
        name: s.name,
        type: 'line',
        data: s.data,
        showSymbol: false,
        symbol: 'circle',
        symbolSize: 5,
        smooth: 0.12,
        lineStyle: { width: 1.8, color: s.color },
        itemStyle: { color: s.color },
        emphasis: {
          focus: 'series',
          lineStyle: { width: 2.6 },
          itemStyle: { borderWidth: 2, borderColor: '#0b0d12' },
        },
        areaStyle:
          mode === 'cumulative'
            ? {
                opacity: 0.16,
                color: {
                  type: 'linear',
                  x: 0,
                  y: 0,
                  x2: 0,
                  y2: 1,
                  colorStops: [
                    { offset: 0, color: s.color },
                    { offset: 1, color: 'rgba(0,0,0,0)' },
                  ],
                },
              }
            : undefined,
        sampling: 'lttb',
        large: s.data.length > 4000,
        largeThreshold: 4000,
      })),
    } as EChartsOption
  }, [series, mode, stageFilter])

  const visibleCount = experiments.length - hidden.size

  return (
    <div className="panel">
      <div className="panel-header flex-wrap">
        <div>
          <h2 className="panel-title">Token 消耗时间轴</h2>
          <p className="panel-sub">
            按事件序号累加，叠加对比各策略的 token 增长斜率
            {stageFilter !== 'ALL' && ` · 仅统计「${stageFilter}」环节`}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {/* 阶段过滤 */}
          <select
            value={stageFilter}
            onChange={(e) => setStageFilter(e.target.value)}
            className="rounded-lg border border-ink-750 bg-ink-900/80 px-2 py-1 text-[11px] text-ink-200
              outline-none transition-colors hover:border-ink-600 focus:border-accent/60"
            aria-label="阶段过滤"
          >
            <option value="ALL">全部环节</option>
            {allStages.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>

          {/* 口径切换 */}
          <div className="seg" role="tablist" aria-label="统计口径">
            <button
              type="button"
              role="tab"
              aria-selected={mode === 'cumulative'}
              onClick={() => setMode('cumulative')}
              className={`seg-item ${mode === 'cumulative' ? 'seg-item-active' : ''}`}
            >
              累计
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={mode === 'incremental'}
              onClick={() => setMode('incremental')}
              className={`seg-item ${mode === 'incremental' ? 'seg-item-active' : ''}`}
            >
              单事件
            </button>
          </div>

          <button
            type="button"
            onClick={() => setHidden(new Set())}
            disabled={hidden.size === 0}
            className="rounded-lg border border-ink-750 px-2 py-1 text-[11px] text-ink-400
              transition-colors hover:border-ink-600 hover:text-ink-200
              disabled:cursor-not-allowed disabled:opacity-40"
            title="显示全部实验"
          >
            重置 {hidden.size > 0 && `(${hidden.size})`}
          </button>
        </div>
      </div>

      <div className="px-2 pb-2 pt-1">
        <Chart
          option={option}
          height={340}
          isEmpty={!hasData}
          emptyHint={
            stageFilter === 'ALL'
              ? '暂无 timeline 数据（summary.json 缺少 experiments[].timeline[]）'
              : `环节「${stageFilter}」没有事件数据`
          }
        />
      </div>

      {/* 底部：各实验末值 + 颜色图例（比 ECharts 自带图例更好读） */}
      {hasData && (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5 border-t border-ink-750/70 px-4 py-2.5">
          {experiments.map((e) => {
            const isHidden = hidden.has(e.experimentId)
            return (
              <button
                key={e.experimentId}
                type="button"
                onClick={() =>
                  setHidden((prev) => {
                    const next = new Set(prev)
                    if (next.has(e.experimentId)) next.delete(e.experimentId)
                    else next.add(e.experimentId)
                    return next
                  })
                }
                className={`flex items-center gap-1.5 text-[11px] transition-opacity ${
                  isHidden ? 'opacity-35' : 'opacity-100'
                } hover:opacity-80`}
                title={isHidden ? '点击显示' : '点击隐藏'}
              >
                <span
                  className="h-2 w-2 rounded-sm"
                  style={{ background: colorMap[e.experimentId] }}
                />
                <span className="text-ink-300">{e.experimentId}</span>
                <span className="tabular text-ink-500">
                  {fmtCompact(e.totalTokens)}
                </span>
              </button>
            )
          })}
          <span className="ml-auto text-[10px] text-ink-600">
            共 {fmtInt(experiments.reduce((a, e) => a + e.timeline.length, 0))} 个事件 ·{' '}
            {visibleCount}/{experiments.length} 条曲线可见
          </span>
        </div>
      )}

      {/* 过滤提示 */}
      {stageFilter !== 'ALL' && hasData && (
        <div className="border-t border-ink-750/70 px-4 py-2 text-[10px] text-ink-500">
          过滤后曲线仅反映该环节自身的 token 累计；与该实验总 token（
          {fmtUsd(experiments[0]?.totalCostUsd ?? 0, 2)} 成本 /{' '}
          {fmtCompact(experiments[0]?.totalTokens ?? 0)} token）不同属正常现象。
        </div>
      )}
    </div>
  )
}
