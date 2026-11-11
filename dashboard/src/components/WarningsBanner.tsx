interface Props {
  warnings: string[]
}

/**
 * 数据质量横幅。
 *
 * 契约要求「字段缺失时优雅降级」——但降级不能静默：
 * 这里把 normalize 阶段收集到的所有问题显式展示出来，
 * 让使用者一眼知道「这块图是空的，因为数据缺了 X」。
 */
export default function WarningsBanner({ warnings }: Props) {
  if (warnings.length === 0) return null

  return (
    <div className="rounded-xl border border-amber/25 bg-amber/[0.06] px-4 py-3">
      <div className="flex items-start gap-2.5">
        <svg
          className="mt-0.5 h-4 w-4 shrink-0 text-amber"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          aria-hidden="true"
        >
          <path d="M12 9v4M12 17h.01" strokeLinecap="round" />
          <path
            d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
        <div className="min-w-0 flex-1">
          <p className="text-[11.5px] font-medium text-amber">
            数据完整性提示（{warnings.length} 项）
          </p>
          <ul className="mt-1 space-y-0.5">
            {warnings.slice(0, 8).map((w, i) => (
              <li key={i} className="text-[11px] leading-relaxed text-ink-300">
                · {w}
              </li>
            ))}
            {warnings.length > 8 && (
              <li className="text-[10px] text-ink-500">…另有 {warnings.length - 8} 项</li>
            )}
          </ul>
          <p className="mt-1 text-[10px] text-ink-500">
            受影响的图表会显示空状态，其余视图正常工作。
          </p>
        </div>
      </div>
    </div>
  )
}
