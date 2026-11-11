import { useMemo, useState } from 'react'
import Chart from './Chart'
import type { DashboardData, ExperimentVM } from '../types'
import type { EChartsOption } from '../charts/echarts'
import { fmtCompact, fmtInt, fmtPctValue, fmtUsd } from '../utils/format'
import { buildColorMap } from '../utils/palette'

interface Props {
  data: DashboardData
}

/**
 * 计算帕累托前沿：在「token 越少越好、质量越高越好」两目标下不被支配的点。
 * 返回按 token 升序排列的前沿点，用于连线。
 */
function paretoFrontier(points: ExperimentVM[]): ExperimentVM[] {
  const sorted = [...points].sort((a, b) => a.totalTokens - b.totalTokens)
  const front: ExperimentVM[] = []
  let bestQuality = -Infinity
  for (const p of sorted) {
    if (p.qualityScorePct > bestQuality) {
      front.push(p)
      bestQuality = p.qualityScorePct
    }
  }
  return front
}

/**
 * 视图 3：成本-效果散点图（契约 §7.3）
 *
 * - X = 总 token，Y = 质量分（%）
 * - 气泡大小 = 总成本（开方缩放，避免极值把其他点压成针尖）
 * - 标签 = experimentId
 * - 帕累托前沿连线：标出「同样 token 下质量最高」的候选集
 * - 可选：切换 X 轴为「总成本」，直接看成本-效果关系
 */
export default function CostEffectScatter({ data }: Props) {
  const { experiments } = data
  const [xMetric, setXMetric] = useState<'tokens' | 'cost'>('tokens')
  const [showPareto, setShowPareto] = useState(true)

  const colorMap = useMemo(
    () => buildColorMap(experiments.map((e) => e.experimentId)),
    [experiments],
  )

  const frontier = useMemo(() => paretoFrontier(experiments), [experiments])
  const frontierIds = useMemo(
    () => new Set(frontier.map((f) => f.experimentId)),
    [frontier],
  )

  /** 气泡半径：成本开方映射到 [14, 52] */
  const maxCost = Math.max(...experiments.map((e) => e.totalCostUsd), 0.0001)
  const bubbleSize = (cost: number) => {
    const norm = Math.sqrt(cost / maxCost) // 0..1
    return 16 + norm * 36
  }

  const option = useMemo<EChartsOption>(() => {
    if (experiments.length === 0) return {}

    const xVal = (e: ExperimentVM) =>
      xMetric === 'tokens' ? e.totalTokens : e.totalCostUsd

    const scatterData = experiments.map((e) => ({
      name: e.experimentId,
      value: [xVal(e), e.qualityScorePct, e.totalCostUsd, e.tokensPerTurn] as number[],
      symbolSize: bubbleSize(e.totalCostUsd),
      itemStyle: {
        color: colorMap[e.experimentId],
        opacity: 0.86,
        borderColor: frontierIds.has(e.experimentId) ? '#e6eaf2' : 'transparent',
        borderWidth: frontierIds.has(e.experimentId) ? 1.5 : 0,
        shadowBlur: 14,
        shadowColor: 'rgba(0,0,0,0.55)',
      },
      label: {
        show: true,
        position: 'top' as const,
        distance: 6,
        formatter: e.experimentId,
        color: '#c3cad6',
        fontSize: 10,
        fontWeight: 500 as const,
      },
      emphasis: {
        scale: 1.08,
        itemStyle: { opacity: 1 },
        label: { color: '#e6eaf2', fontSize: 11 },
      },
    }))

    const paretoLine = showPareto
      ? frontier.map((e) => [xVal(e), e.qualityScorePct])
      : []

    // 质量分均值参考线
    const avgQuality =
      experiments.reduce((a, e) => a + e.qualityScorePct, 0) / experiments.length

    return {
      animationDuration: 600,
      grid: { left: 8, right: 40, top: 40, bottom: 40, containLabel: true },
      tooltip: {
        trigger: 'item',
        formatter: (p: unknown) => {
          const d = p as { name: string; value: number[]; color: string }
          const e = experiments.find((x) => x.experimentId === d.name)
          if (!e) return ''
          const isFrontier = frontierIds.has(e.experimentId)
          return (
            `<div style="display:flex;align-items:center;gap:6px;margin-bottom:6px">` +
            `<span style="width:8px;height:8px;border-radius:2px;background:${d.color}"></span>` +
            `<span style="color:#e6eaf2;font-weight:600;font-size:12px">${e.experimentId}</span>` +
            (isFrontier
              ? `<span style="background:rgba(61,220,151,0.16);color:#3ddc97;font-size:9px;padding:1px 5px;border-radius:4px">帕累托最优</span>`
              : '') +
            `</div>` +
            `<div style="font-size:11px;color:#98a2b3;line-height:1.8">` +
            `总 Token　<span style="color:#e6eaf2;float:right;margin-left:16px">${fmtInt(e.totalTokens)}</span><br/>` +
            `质量分　　<span style="color:#3ddc97;float:right;margin-left:16px">${e.qualityScorePct.toFixed(2)}%</span><br/>` +
            `总成本　　<span style="color:#f7b955;float:right;margin-left:16px">${fmtUsd(e.totalCostUsd, 4)}</span><br/>` +
            `每轮 Token <span style="color:#c3cad6;float:right;margin-left:16px">${fmtInt(e.tokensPerTurn)}</span><br/>` +
            `缓存命中　<span style="color:#a78bfa;float:right;margin-left:16px">${fmtPctValue(e.cacheHitRate * 100, 1)}</span>` +
            `</div>`
          )
        },
      },
      legend: { show: false },
      xAxis: {
        type: 'value',
        name: xMetric === 'tokens' ? '总 Token 消耗' : '总成本 (USD)',
        nameLocation: 'middle',
        nameGap: 26,
        scale: true,
        axisLabel: {
          formatter: (v: number) =>
            xMetric === 'tokens' ? fmtCompact(v) : `$${v.toFixed(2)}`,
        },
        splitLine: { lineStyle: { color: 'rgba(255,255,255,0.045)' } },
      },
      yAxis: {
        type: 'value',
        name: '质量分 (%)',
        nameTextStyle: { align: 'left', padding: [0, 0, 6, -10] },
        scale: true,
        axisLabel: { formatter: (v: number) => `${v.toFixed(0)}%` },
        splitLine: { lineStyle: { color: 'rgba(255,255,255,0.055)' } },
      },
      series: [
        // 帕累托前沿连线（放底层）
        ...(showPareto && paretoLine.length > 1
          ? [
              {
                name: '帕累托前沿',
                type: 'line',
                data: paretoLine,
                showSymbol: false,
                silent: true,
                z: 1,
                lineStyle: {
                  color: 'rgba(61,220,151,0.42)',
                  width: 1.4,
                  type: 'dashed',
                },
                tooltip: { show: false },
              },
            ]
          : []),
        {
          name: '实验',
          type: 'scatter',
          data: scatterData,
          z: 3,
          // 质量分均值参考线
          markLine: {
            silent: true,
            symbol: 'none',
            lineStyle: { color: 'rgba(255,255,255,0.14)', type: 'dashed', width: 1 },
            label: {
              formatter: `平均质量 ${avgQuality.toFixed(1)}%`,
              color: '#6b7688',
              fontSize: 10,
              position: 'insideEndTop',
            },
            data: [{ yAxis: Number(avgQuality.toFixed(2)) }],
          },
        },
      ],
    } as EChartsOption
  }, [experiments, xMetric, showPareto, frontier, frontierIds, colorMap])

  const best = useMemo(
    () =>
      experiments.reduce<ExperimentVM | null>(
        (b, e) => (!b || e.qualityScorePct > b.qualityScorePct ? e : b),
        null,
      ),
    [experiments],
  )

  return (
    <div className="panel flex h-full flex-col">
      <div className="panel-header flex-wrap">
        <div>
          <h2 className="panel-title">成本-效果散点图</h2>
          <p className="panel-sub">
            气泡大小 = 总成本 · 虚线为帕累托前沿（同等 token 下质量最优）
          </p>
        </div>
        <div className="flex items-center gap-2">
          <div className="seg" role="tablist" aria-label="X 轴口径">
            <button
              type="button"
              role="tab"
              aria-selected={xMetric === 'tokens'}
              onClick={() => setXMetric('tokens')}
              className={`seg-item ${xMetric === 'tokens' ? 'seg-item-active' : ''}`}
            >
              X=Token
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={xMetric === 'cost'}
              onClick={() => setXMetric('cost')}
              className={`seg-item ${xMetric === 'cost' ? 'seg-item-active' : ''}`}
            >
              X=成本
            </button>
          </div>
          <button
            type="button"
            onClick={() => setShowPareto((v) => !v)}
            className={`rounded-lg border px-2 py-1 text-[11px] transition-colors ${
              showPareto
                ? 'border-mint/40 bg-mint/10 text-mint'
                : 'border-ink-750 text-ink-400 hover:text-ink-200'
            }`}
            aria-pressed={showPareto}
            title="切换帕累托前沿"
          >
            前沿
          </button>
        </div>
      </div>

      <div className="flex-1 px-2 pb-2 pt-1">
        <Chart
          option={option}
          height={340}
          isEmpty={experiments.length === 0}
          emptyHint="暂无实验数据"
        />
      </div>

      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 border-t border-ink-750/70 px-4 py-2 text-[10px] text-ink-500">
        <span>
          质量最优{' '}
          <span className="font-medium text-mint">{best?.experimentId ?? '—'}</span>
          {best && <span className="tabular text-ink-400"> · {best.qualityScorePct.toFixed(2)}%</span>}
        </span>
        <span className="text-ink-600">|</span>
        <span>
          帕累托前沿{' '}
          <span className="tabular text-ink-300">
            {frontier.map((f) => f.experimentId).join(' → ') || '—'}
          </span>
        </span>
      </div>
    </div>
  )
}
