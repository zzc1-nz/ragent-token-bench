import type { DashboardData } from '../types'

interface Props {
  data: DashboardData
  /** 数据来源 URL，用于在页脚标注 */
  source?: string
}

/**
 * 页头：运行元信息 + 数据来源标识。
 *
 * 重要：当数据是示例数据（environment.example === true）时，
 * 必须显式打上「示例数据」徽章——避免把假数字当成真实 benchmark 结果。
 */
export default function Header({ data, source }: Props) {
  return (
    <header className="flex flex-wrap items-end justify-between gap-4 border-b border-ink-750/80 pb-4">
      <div>
        <div className="flex items-center gap-2.5">
          {/* logo mark */}
          <div className="relative flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-accent/25 to-mint/15 ring-1 ring-inset ring-accent/30">
            <svg
              className="h-4 w-4 text-accent-soft"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              aria-hidden="true"
            >
              <path d="M3 17l5-6 4 4 3-4 6 6" strokeLinecap="round" strokeLinejoin="round" />
              <circle cx="8" cy="6" r="1.6" fill="currentColor" stroke="none" />
              <circle cx="16" cy="6" r="1.6" fill="currentColor" stroke="none" opacity="0.5" />
            </svg>
          </div>
          <div>
            <h1 className="text-[17px] font-semibold leading-tight tracking-tight text-ink-100">
              TokenBench
            </h1>
            <p className="text-[10.5px] leading-tight text-ink-500">
              Agentic RAG · Token 消耗可观测性基准测试平台
            </p>
          </div>
        </div>
      </div>

      {/* 运行元信息 */}
      <div className="flex flex-wrap items-center gap-x-5 gap-y-2 text-[11px]">
        {data.runId && (
          <div>
            <div className="text-[9px] uppercase tracking-wider text-ink-600">Run ID</div>
            <div className="tabular font-medium text-ink-200">{data.runId}</div>
          </div>
        )}
        {data.generatedAt && (
          <div>
            <div className="text-[9px] uppercase tracking-wider text-ink-600">生成时间</div>
            <div className="tabular text-ink-300">
              {new Date(data.generatedAt).toLocaleString('zh-CN', {
                year: 'numeric',
                month: '2-digit',
                day: '2-digit',
                hour: '2-digit',
                minute: '2-digit',
              })}
            </div>
          </div>
        )}
        <div>
          <div className="text-[9px] uppercase tracking-wider text-ink-600">数据集</div>
          <div className="tabular text-ink-300">
            {data.dataset.docs} 文档 · {data.dataset.turns} 轮
          </div>
        </div>

        {/* 数据状态徽章 */}
        {data.isExample ? (
          <span
            className="badge border border-amber/35 bg-amber/10 px-2 py-1 text-amber"
            title="当前渲染的是 scripts/gen-sample-data.mjs 生成的示例数据，等待 Java 运行器产出真实结果覆盖"
          >
            <span className="h-1.5 w-1.5 rounded-full bg-amber" />
            示例数据
          </span>
        ) : (
          <span className="badge border border-mint/35 bg-mint/10 px-2 py-1 text-mint">
            <span className="h-1.5 w-1.5 rounded-full bg-mint" />
            真实运行数据
          </span>
        )}
      </div>

      {source && (
        <div className="w-full text-right text-[9px] text-ink-600">
          数据来源 <code className="font-mono">{source}</code>
        </div>
      )}
    </header>
  )
}
