import type { DashboardData } from '../types'
import { fmtSignedPct } from '../utils/format'

interface Props {
  data: DashboardData
}

/** 单个 delta 指标块 */
function DeltaMetric({
  label,
  value,
  goodWhenNegative,
  suffix,
}: {
  label: string
  value: number | null
  goodWhenNegative: boolean
  suffix?: string
}) {
  const isGood =
    value !== null && (goodWhenNegative ? value < 0 : value > 0) && Math.abs(value) >= 0.05
  const isBad =
    value !== null && (goodWhenNegative ? value > 0 : value < 0) && Math.abs(value) >= 0.05
  const tone = isGood ? 'text-mint' : isBad ? 'text-rose' : 'text-ink-400'

  return (
    <div className="min-w-0">
      <div className="text-[10px] text-ink-500">{label}</div>
      <div className={`tabular mt-0.5 text-[15px] font-semibold leading-none ${tone}`}>
        {fmtSignedPct(value, 1)}
        {suffix && <span className="ml-0.5 text-[10px] font-normal text-ink-500">{suffix}</span>}
      </div>
    </div>
  )
}

/**
 * A/B 对比结论卡片（契约 §6.4 deltas[] + §8 的结论句式）。
 *
 * 直接消费 deltas[]，不硬编码任何实验名与数值：
 * 换一份真实数据，这里的结论会自动跟着变。
 */
export default function DeltaCards({ data }: Props) {
  const { deltas, experiments } = data

  if (deltas.length === 0) {
    return (
      <div className="panel">
        <div className="panel-header">
          <h2 className="panel-title">A/B 策略对比结论</h2>
        </div>
        <p className="px-4 py-8 text-center text-[11px] text-ink-500">
          暂无 deltas[] 数据（运行器未产出对比结果）
        </p>
      </div>
    )
  }

  const byId = new Map(experiments.map((e) => [e.experimentId, e]))

  // 主结论：优先取「基线 → 最省 token 的候选」那条；否则取第一条
  const baselineId = deltas[0]?.baseline
  const primary =
    deltas
      .filter((d) => d.baseline === baselineId)
      .reduce<(typeof deltas)[number] | null>(
        (best, d) => (best === null || (d.tokenSavingPct ?? 0) > (best.tokenSavingPct ?? 0) ? d : best),
        null,
      ) ?? deltas[0]

  return (
    <div className="panel">
      <div className="panel-header">
        <div>
          <h2 className="panel-title">A/B 策略对比结论</h2>
          <p className="panel-sub">相对基线策略的 token / 成本 / 质量 / 延迟变化</p>
        </div>
        <span className="badge bg-ink-800 text-ink-400">{deltas.length} 组对比</span>
      </div>

      {/* 主结论：契约 §8 的强制句式 */}
      {primary && (
        <div className="border-b border-ink-750/70 bg-gradient-to-r from-accent/[0.07] to-transparent px-4 py-3">
          <div className="flex items-start gap-2">
            <svg
              className="mt-0.5 h-4 w-4 shrink-0 text-accent"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              aria-hidden="true"
            >
              <path d="M9 11l3 3L22 4" strokeLinecap="round" strokeLinejoin="round" />
              <path d="M21 12v7a2 2 0 01-2 2H5a2 2 0 01-2-2V5a2 2 0 012-2h11" strokeLinecap="round" />
            </svg>
            <p className="text-[12px] leading-relaxed text-ink-200">
              在{' '}
              <span className="tabular text-ink-100">
                {data.dataset.docs} 文档 / {data.dataset.turns} 轮对话
              </span>{' '}
              任务集上，策略{' '}
              <span className="font-semibold text-accent-soft">{primary.candidate}</span> 比策略{' '}
              <span className="font-semibold text-ink-100">{primary.baseline}</span>{' '}
              {primary.tokenSavingPct !== null && primary.tokenSavingPct >= 0 ? '节省' : '多用'}{' '}
              <span className="font-semibold text-mint">
                {Math.abs(primary.tokenSavingPct ?? 0).toFixed(1)}%
              </span>{' '}
              token，质量变化{' '}
              <span
                className={`font-semibold ${
                  (primary.qualityDeltaPct ?? 0) >= 0 ? 'text-mint' : 'text-rose'
                }`}
              >
                {fmtSignedPct(primary.qualityDeltaPct, 1)}
              </span>
              ，成本
              {(primary.costSavingPct ?? 0) >= 0 ? '节省' : '增加'}{' '}
              <span className="font-semibold text-amber">
                {Math.abs(primary.costSavingPct ?? 0).toFixed(1)}%
              </span>
              。
            </p>
          </div>
        </div>
      )}

      {/* 明细：每条 delta 一行 */}
      <div className="divide-y divide-ink-750/50">
        {deltas.map((d) => {
          const b = byId.get(d.baseline)
          const c = byId.get(d.candidate)
          return (
            <div key={`${d.baseline}-${d.candidate}`} className="px-4 py-2.5">
              <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
                {/* 对比路径 */}
                <div className="flex min-w-[200px] items-center gap-1.5 text-[11px]">
                  <span className="text-ink-400">{d.baseline}</span>
                  <svg
                    className="h-3 w-3 text-ink-600"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2.5"
                    aria-hidden="true"
                  >
                    <path d="M5 12h14M13 6l6 6-6 6" strokeLinecap="round" strokeLinejoin="round" />
                  </svg>
                  <span className="font-medium text-ink-100">{d.candidate}</span>
                </div>

                {/* 四指标 */}
                <div className="flex flex-1 flex-wrap items-center gap-x-5 gap-y-2">
                  <DeltaMetric label="Token 变化" value={d.tokenSavingPct !== null ? -d.tokenSavingPct : null} goodWhenNegative />
                  <DeltaMetric label="成本变化" value={d.costSavingPct !== null ? -d.costSavingPct : null} goodWhenNegative />
                  <DeltaMetric label="质量变化" value={d.qualityDeltaPct} goodWhenNegative={false} />
                  <DeltaMetric label="延迟变化" value={d.latencyDeltaPct} goodWhenNegative />
                </div>
              </div>

              {/* 绝对量对照 */}
              {b && c && (
                <div className="mt-1.5 flex flex-wrap gap-x-4 gap-y-0.5 text-[10px] text-ink-600">
                  <span>
                    token {b.totalTokens.toLocaleString('en-US')} →{' '}
                    <span className="text-ink-400">{c.totalTokens.toLocaleString('en-US')}</span>
                  </span>
                  <span>
                    成本 ${b.totalCostUsd.toFixed(4)} →{' '}
                    <span className="text-ink-400">${c.totalCostUsd.toFixed(4)}</span>
                  </span>
                  <span>
                    质量 {b.qualityScorePct.toFixed(2)}% →{' '}
                    <span className="text-ink-400">{c.qualityScorePct.toFixed(2)}%</span>
                  </span>
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
