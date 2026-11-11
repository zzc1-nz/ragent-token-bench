import { useEffect, useState } from 'react'
import type { DashboardData } from '../types'
import { emptyDashboard, normalizeSummary } from '../utils/normalize'

export type LoadState = 'loading' | 'ready' | 'error'

interface Result {
  data: DashboardData
  state: LoadState
  error: string | null
  /** 数据实际来源，UI 上标注以便区分示例数据与真实运行数据 */
  source: string
}

/**
 * 加载 summary.json。
 *
 * 候选来源（按顺序尝试，第一个成功的即用）：
 *   1. ./data/summary.json   —— 静态产物（public/data/ 拷贝而来，主路径）
 *   2. data/summary.json     —— 兼容不带 ./ 前缀的相对解析
 *   3. http://localhost:8080/api/bench/summary —— 模块 B 的 REST 端点（契约 §7 可选）
 *
 * 全部失败时返回 state='error' + 空数据集，由 App 渲染「等待数据」占位，绝不白屏。
 *
 * 注意：这里刻意**不使用** `import.meta.env.BASE_URL`。
 * 本项目的构建脚本是 Rollup 直出（见 scripts/build.mjs），不走 Vite 的
 * define 替换，`import.meta.env` 在产物里会保留为 undefined，
 * 拼出的 URL 会变成 "undefineddata/summary.json"。
 * 由于 `base: './'`，相对路径本身就正确，直接用相对写法更稳。
 */
const STATIC_CANDIDATES = ['./data/summary.json', 'data/summary.json']

const API_URL = 'http://localhost:8080/api/bench/summary'

async function tryFetchJson(url: string, timeoutMs = 8000): Promise<unknown | null> {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), timeoutMs)
  try {
    const res = await fetch(url, { signal: ctrl.signal, cache: 'no-cache' })
    if (!res.ok) return null
    const text = await res.text()
    if (!text.trim()) return null
    return JSON.parse(text)
  } catch {
    return null
  } finally {
    clearTimeout(timer)
  }
}

export function useSummaryData(): Result {
  const [result, setResult] = useState<Result>({
    data: emptyDashboard(),
    state: 'loading',
    error: null,
    source: '',
  })

  useEffect(() => {
    let cancelled = false

    ;(async () => {
      // 静态文件优先：这是构建产物里的权威数据
      for (const url of [...new Set(STATIC_CANDIDATES)]) {
        const json = await tryFetchJson(url)
        if (cancelled) return
        if (json) {
          const data = normalizeSummary(json)
          setResult({ data, state: 'ready', error: null, source: url })
          return
        }
      }

      // 静态文件不存在时，回退到模块 B 的 REST 端点（本地联调场景）
      const apiJson = await tryFetchJson(API_URL, 3000)
      if (cancelled) return
      if (apiJson) {
        const data = normalizeSummary(apiJson)
        setResult({ data, state: 'ready', error: null, source: API_URL })
        return
      }

      setResult({
        data: emptyDashboard(),
        state: 'error',
        error:
          '未能加载 summary.json。请确认 public/data/summary.json 存在（真实运行结果由 Java 运行器产出后拷贝到该路径）。',
        source: '',
      })
    })()

    return () => {
      cancelled = true
    }
  }, [])

  return result
}
