import { useMemo } from 'react'
import Chart from './Chart'
import type { DashboardData } from '../types'
import type { EChartsOption } from '../charts/echarts'
import { fmtCompact, fmtPct } from '../utils/format'
import { buildColorMap } from '../utils/palette'

interface Props {
  data: DashboardData
}

/**
 * 缓存命中率分析（契约 §8.4 / task-2 要求）。
 *
 * 左侧：各实验缓存命中率横向柱状图
 * 右侧：缓存命中对成本的量化影响（有/无缓存的同策略对比）
 */
export default function CacheAnalysis({ data }: Props) {
  const { experiments } = data
  const colorMap = useMemo(
    () => buildColorMap(experiments.map((e) => e.experimentId)),
    [experiments],
  )

  // 按缓存命中率降序，方便一眼看出谁命中高
  const sorted = useMemo(
    () => [...experiments].sort((a, b) => b.cacheHitRate - a.cacheHitRate),
    [experiments],
  )

  const option = useMemo<EChartsOption>(() => {
    return {
      animationDuration: 600,
      grid: { left: 8, right: 56, top: 16, bottom: 28, containLabel: true },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (params: unknown) => {
          const arr = params as Array<{ name: string; value: number; color: string }>
          const p = arr[0]
          if (!p) return ''
          const e = experiments.find((x) => x.experimentId === p.name)
          if (!e) return ''
          return (
            `<div style="color:#e6eaf2;font-weight:600;font-size:12px;margin-bottom:4px">${e.experimentId}</div>` +
            `<div style="font-size:11px;color:#98a2b3;line-height:1.8">` +
            `命中率　<span style="color:#a78bfa;float:right;margin-left:16px">${fmtPct(e.cacheHitRate, 2)}</span><br/>` +
            `缓存输入　<span style="color:#c3cad6;float:right;margin-left:16px">${e.cachedInputTokens.toLocaleString('en-US')}</span><br/>` +
            `总成本　　<span style="color:#f7b955;float:right;margin-left:16px">$${e.totalCostUsd.toFixed(4)}</span>` +
            `</div>`
          )
        },
      },
      xAxis: {
        type: 'value',
        max: 100,
        name: '命中率 %',
        nameLocation: 'middle',
        nameGap: 22,
        axisLabel: { formatter: '{value}%' },
      },
      yAxis: {
        type: 'category',
        inverse: true,
        data: sorted.map((e) => e.experimentId),
        axisLabel: { color: '#98a2b3', fontSize: 10 },
        splitLine: { show: false },
      },
      series: [
        {
          type: 'bar',
          barWidth: '52%',
          data: sorted.map((e) => ({
            name: e.experimentId,
            value: Number((e.cacheHitRate * 100).toFixed(2)),
            itemStyle: {
              color: e.cacheHitRate > 0 ? colorMap[e.experimentId] : '#333c4e',
              borderRadius: [0, 3, 3, 0],
              opacity: e.cacheHitRate > 0 ? 0.9 : 0.5,
            },
          })),
          label: {
            show: true,
            position: 'right',
            color: '#98a2b3',
            fontSize: 10,
            formatter: (p: unknown) => {
              const d = p as { value: number }
              return d.value > 0 ? `${d.value.toFixed(1)}%` : '未开启'
            },
          },
          emphasis: { itemStyle: { opacity: 1 } },
        },
      ],
    } as EChartsOption
  }, [sorted, experiments, colorMap])

  // 找出「同策略但缓存开关不同」的一对，量化缓存收益
  const cachePair = useMemo(() => {
    const enabled = experiments.filter((e) => e.cacheStrategy === 'ENABLED')
    if (enabled.length === 0) return null
    const withCache = enabled[0]
    // 找策略组合最接近、但缓存关闭的实验
    const candidates = experiments.filter((e) => e.cacheStrategy !== 'ENABLED')
    if (candidates.length === 0) return null
    const score = (e: (typeof experiments)[number]) =>
      (e.chunkStrategy === withCache.chunkStrategy ? 2 : 0) +
      (e.retrievalStrategy === withCache.retrievalStrategy ? 2 : 0) +
      (e.routingStrategy === withCache.routingStrategy ? 2 : 0)
    const without = candidates.reduce((best, e) => (score(e) > score(best) ? e : best), candidates[0])
    const costSavingPct =
      without.totalCostUsd > 0
        ? ((without.totalCostUsd - withCache.totalCostUsd) / without.totalCostUsd) * 100
        : 0
    return { withCache, without, costSavingPct }
  }, [experiments])

  const hasCacheData = experiments.some((e) => e.cacheHitRate > 0)

  return (
    <div className="panel flex h-full flex-col">
      <div className="panel-header">
        <div>
          <h2 className="panel-title">缓存命中率分析</h2>
          <p className="panel-sub">
            命中缓存的部分输入 token 按缓存价计费（
            <span className="text-ink-300">0.1x</span> 正常价）
          </p>
        </div>
      </div>

      <div className="px-2 pb-1 pt-1">
        <Chart
          option={option}
          height={Math.max(160, sorted.length * 32 + 40)}
          isEmpty={experiments.length === 0 || !hasCacheData}
          emptyHint="所有实验均未开启缓存，无命中率数据"
        />
      </div>

      {/* 缓存收益量化 */}
      {cachePair && (
        <div className="border-t border-ink-750/70 px-4 py-3">
          <div className="mb-2 text-[10px] font-medium uppercase tracking-wider text-ink-500">
            缓存收益量化
          </div>
          <div className="grid grid-cols-3 gap-3">
            <div>
              <div className="text-[10px] text-ink-500">命中率</div>
              <div className="tabular text-[16px] font-semibold leading-none text-violet">
                {fmtPct(cachePair.withCache.cacheHitRate, 1)}
              </div>
              <div className="mt-0.5 truncate text-[9px] text-ink-600">
                {cachePair.withCache.experimentId}
              </div>
            </div>
            <div>
              <div className="text-[10px] text-ink-500">成本节省</div>
              <div className="tabular text-[16px] font-semibold leading-none text-mint">
                {cachePair.costSavingPct.toFixed(1)}%
              </div>
              <div className="mt-0.5 truncate text-[9px] text-ink-600">
                vs {cachePair.without.experimentId}
              </div>
            </div>
            <div>
              <div className="text-[10px] text-ink-500">节省金额</div>
              <div className="tabular text-[16px] font-semibold leading-none text-amber">
                ${(cachePair.without.totalCostUsd - cachePair.withCache.totalCostUsd).toFixed(3)}
              </div>
              <div className="mt-0.5 truncate text-[9px] text-ink-600">
                缓存输入 {fmtCompact(cachePair.withCache.cachedInputTokens)} tokens
              </div>
            </div>
          </div>
          <p className="mt-2 text-[10px] leading-relaxed text-ink-500">
            同策略下开启响应缓存后，{fmtCompact(cachePair.withCache.cachedInputTokens)} 个输入
            token 命中缓存并按 0.1x 计价，总成本从{' '}
            <span className="tabular text-ink-300">
              ${cachePair.without.totalCostUsd.toFixed(4)}
            </span>{' '}
            降至{' '}
            <span className="tabular text-mint">${cachePair.withCache.totalCostUsd.toFixed(4)}</span>
            ，而 token 总量不变。
          </p>
        </div>
      )}
    </div>
  )
}
