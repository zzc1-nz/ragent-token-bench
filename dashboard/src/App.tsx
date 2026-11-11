import { useMemo, useState } from 'react'
import Header from './components/Header'
import KpiCards from './components/KpiCards'
import TokenTimeline from './components/TokenTimeline'
import StageBreakdown from './components/StageBreakdown'
import CostEffectScatter from './components/CostEffectScatter'
import ComparisonTable from './components/ComparisonTable'
import DeltaCards from './components/DeltaCards'
import CacheAnalysis from './components/CacheAnalysis'
import WarningsBanner from './components/WarningsBanner'
import EmptyState from './components/EmptyState'
import { useSummaryData } from './hooks/useSummaryData'

/** 顶部视图切换：概览 / 分环节 / 明细 */
const TABS = [
  { key: 'overview', label: '总览' },
  { key: 'stages', label: '分环节' },
  { key: 'details', label: '明细数据' },
] as const

type TabKey = (typeof TABS)[number]['key']

export default function App() {
  const { data, state, error, source } = useSummaryData()
  const [tab, setTab] = useState<TabKey>('overview')

  const hasData = data.experiments.length > 0
  const showEmpty = state === 'error' || (state === 'ready' && !hasData)

  /** 页脚统计 */
  const footerStats = useMemo(() => {
    if (!hasData) return null
    return {
      experiments: data.experiments.length,
      events: data.totals.events,
      deltas: data.deltas.length,
    }
  }, [data, hasData])

  return (
    <div className="mx-auto min-h-full max-w-[1600px] px-5 py-6 lg:px-8">
      <Header data={data} source={state === 'ready' ? source : undefined} />

      {/* 加载中 / 数据缺失 */}
      {(state === 'loading' || showEmpty) && (
        <main className="mt-6">
          <EmptyState
            loading={state === 'loading'}
            message={error ?? undefined}
            hint="public/data/summary.json"
          />
        </main>
      )}

      {/* 正常渲染 */}
      {state === 'ready' && hasData && (
        <main className="mt-5 space-y-4">
          {/* 数据完整性提示 */}
          <WarningsBanner warnings={data.warnings} />

          {/* 视图切换 */}
          <nav className="flex items-center justify-between gap-3">
            <div className="seg" role="tablist" aria-label="视图切换">
              {TABS.map((t) => (
                <button
                  key={t.key}
                  type="button"
                  role="tab"
                  aria-selected={tab === t.key}
                  onClick={() => setTab(t.key)}
                  className={`seg-item px-3.5 py-1.5 text-[12px] ${
                    tab === t.key ? 'seg-item-active' : ''
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </div>

            <div className="hidden items-center gap-3 text-[10px] text-ink-500 sm:flex">
              <span>
                <span className="tabular text-ink-300">{footerStats?.experiments}</span> 组实验
              </span>
              <span className="text-ink-700">·</span>
              <span>
                <span className="tabular text-ink-300">
                  {footerStats?.events.toLocaleString('en-US')}
                </span>{' '}
                条计量事件
              </span>
              <span className="text-ink-700">·</span>
              <span>
                <span className="tabular text-ink-300">{footerStats?.deltas}</span> 组对比
              </span>
            </div>
          </nav>

          {/* ============ 总览 ============ */}
          {tab === 'overview' && (
            <div className="space-y-4">
              <KpiCards data={data} />

              {/* 主图：时间轴（宽） */}
              <TokenTimeline data={data} />

              {/* 散点 + 环节占比 */}
              <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1.05fr)_minmax(0,1fr)]">
                <CostEffectScatter data={data} />
                <StageBreakdown data={data} />
              </div>

              {/* 对比结论 */}
              <DeltaCards data={data} />

              {/* 对比表 */}
              <ComparisonTable data={data} />
            </div>
          )}

          {/* ============ 分环节 ============ */}
          {tab === 'stages' && (
            <div className="space-y-4">
              <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)]">
                <StageBreakdown data={data} />
                <CacheAnalysis data={data} />
              </div>
              <TokenTimeline data={data} />
              <ComparisonTable data={data} />
            </div>
          )}

          {/* ============ 明细数据 ============ */}
          {tab === 'details' && (
            <div className="space-y-4">
              <ComparisonTable data={data} />
              <DeltaCards data={data} />
              <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
                <CacheAnalysis data={data} />
                <ModelBreakdown data={data} />
              </div>
            </div>
          )}
        </main>
      )}

      {/* 页脚 */}
      <footer className="mt-8 border-t border-ink-750/60 pt-3 text-[10px] leading-relaxed text-ink-600">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <span>
            TokenBench · 基于开源项目 Ragent（nageoffer/ragent）二次开发的 Token 可观测性基准平台
          </span>
          <span className="tabular">
            React 18 · Vite 5 · TypeScript · ECharts 5 · Tailwind 3
          </span>
        </div>
        <p className="mt-1">
          计量口径：CONTRACT §4（MeterStage / TokenUsage / PricingTable）；质量分 =
          recall@k×0.4 + answerCoverage×0.4 + citationPrecision×0.2（确定性离线评分）。
        </p>
      </footer>
    </div>
  )
}

/* ------------------------------------------------------------------ *
 * 模型维度分布（明细视图用）
 * 数据来自 experiments[].tokensByModel
 * ------------------------------------------------------------------ */
function ModelBreakdown({ data }: { data: ReturnType<typeof useSummaryData>['data'] }) {
  // 汇总所有实验的模型 token 占用
  const rows = useMemo(() => {
    const agg = new Map<string, number>()
    for (const e of data.experiments) {
      for (const m of e.tokensByModel) {
        agg.set(m.model, (agg.get(m.model) ?? 0) + m.tokens)
      }
    }
    const total = [...agg.values()].reduce((a, v) => a + v, 0)
    return [...agg.entries()]
      .map(([model, tokens]) => ({ model, tokens, pct: total > 0 ? tokens / total : 0 }))
      .sort((a, b) => b.tokens - a.tokens)
  }, [data])

  const total = rows.reduce((a, r) => a + r.tokens, 0)

  return (
    <div className="panel flex h-full flex-col">
      <div className="panel-header">
        <div>
          <h2 className="panel-title">模型 Token 分布</h2>
          <p className="panel-sub">按 modelId 聚合，跨全部实验（含缓存折扣前口径）</p>
        </div>
      </div>
      <div className="flex-1 p-4">
        {rows.length === 0 ? (
          <p className="py-8 text-center text-[11px] text-ink-500">
            暂无 tokensByModel 数据
          </p>
        ) : (
          <div className="space-y-2.5">
            {rows.map((r, i) => (
              <div key={r.model}>
                <div className="mb-1 flex items-baseline justify-between gap-2 text-[11px]">
                  <span className="truncate font-mono text-ink-200">{r.model}</span>
                  <span className="tabular shrink-0 text-ink-400">
                    {r.tokens.toLocaleString('en-US')}
                    <span className="ml-1.5 text-ink-600">{(r.pct * 100).toFixed(1)}%</span>
                  </span>
                </div>
                <div className="h-1.5 w-full overflow-hidden rounded-full bg-ink-800">
                  <div
                    className="h-full rounded-full transition-all duration-700"
                    style={{
                      width: `${Math.max(r.pct * 100, 0.5)}%`,
                      background: `hsl(${215 + i * 32} 78% 62%)`,
                    }}
                  />
                </div>
              </div>
            ))}
            <div className="mt-3 flex items-center justify-between border-t border-ink-750/60 pt-2 text-[10px] text-ink-500">
              <span>{rows.length} 个模型</span>
              <span className="tabular">合计 {total.toLocaleString('en-US')} tokens</span>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
