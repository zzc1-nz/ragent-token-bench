interface Props {
  /** 加载失败原因 */
  message?: string
  /** 是否处于加载中（骨架屏） */
  loading?: boolean
  /** 数据文件路径提示 */
  hint?: string
}

/**
 * 「等待数据」占位。
 *
 * 这是契约 §7 明确要求的降级路径：summary.json 缺失/fetch 失败时，
 * 页面必须显示占位而不是白屏或崩溃。
 */
export default function EmptyState({ message, loading = false, hint }: Props) {
  if (loading) {
    return (
      <div className="panel flex min-h-[420px] flex-col items-center justify-center gap-4 p-10">
        <div className="flex items-center gap-2">
          <span className="h-2 w-2 animate-pulse rounded-full bg-accent" />
          <span className="h-2 w-2 animate-pulse rounded-full bg-accent [animation-delay:150ms]" />
          <span className="h-2 w-2 animate-pulse rounded-full bg-accent [animation-delay:300ms]" />
        </div>
        <p className="text-[12px] text-ink-400">正在加载 summary.json …</p>
        <div className="mt-2 grid w-full max-w-2xl grid-cols-3 gap-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="skeleton h-16" />
          ))}
        </div>
      </div>
    )
  }

  return (
    <div className="panel flex min-h-[420px] flex-col items-center justify-center gap-4 p-10 text-center">
      {/* 空数据图标 */}
      <div className="relative">
        <div className="absolute inset-0 rounded-full bg-amber/10 blur-xl" />
        <svg
          className="relative h-14 w-14 text-amber/70"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.3"
          aria-hidden="true"
        >
          <path d="M3 3v18h18" strokeLinecap="round" />
          <path d="M7 15l3-4 3 3 4-6" strokeLinecap="round" strokeLinejoin="round" opacity="0.55" />
          <circle cx="17" cy="8" r="2.5" strokeDasharray="2 2" opacity="0.7" />
        </svg>
      </div>

      <div className="max-w-md space-y-2">
        <h2 className="text-[15px] font-semibold text-ink-100">等待基准数据</h2>
        <p className="text-[12px] leading-relaxed text-ink-400">
          {message ??
            '仪表盘需要一个 summary.json 才能渲染。该文件由 Java 基准运行器产出，拷贝到 public/data/ 后刷新即可。'}
        </p>
      </div>

      {hint && (
        <code className="rounded-lg border border-ink-750 bg-ink-900/80 px-3 py-1.5 font-mono text-[11px] text-ink-300">
          {hint}
        </code>
      )}

      {/* 期望的数据来源说明 */}
      <div className="mt-2 w-full max-w-lg space-y-1.5 rounded-lg border border-ink-750/70 bg-ink-900/40 p-3 text-left">
        <p className="text-[10px] font-medium uppercase tracking-wider text-ink-500">
          数据来源（按优先级尝试）
        </p>
        {[
          ['静态文件', 'public/data/summary.json'],
          ['REST 端点', 'http://localhost:8080/api/bench/summary'],
        ].map(([k, v]) => (
          <div key={k} className="flex items-center gap-2 text-[11px]">
            <span className="w-16 shrink-0 text-ink-500">{k}</span>
            <code className="truncate font-mono text-ink-300">{v}</code>
          </div>
        ))}
      </div>

      <p className="text-[10px] text-ink-600">
        页面不会因数据缺失而崩溃——所有图表都会降级为空状态。
      </p>
    </div>
  )
}
