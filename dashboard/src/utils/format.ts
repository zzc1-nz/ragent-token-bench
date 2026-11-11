/** 数值格式化工具：统一全站数字/金额/百分比/耗时的呈现，保证千分位与精度一致 */

/** 1234567 -> "1,234,567" */
export function fmtInt(n: number): string {
  if (!Number.isFinite(n)) return '—'
  return Math.round(n).toLocaleString('en-US')
}

/** 大数字紧凑显示：1234567 -> "1.23M"；用于坐标轴与 KPI 副标题 */
export function fmtCompact(n: number): string {
  if (!Number.isFinite(n)) return '—'
  const abs = Math.abs(n)
  if (abs >= 1_000_000_000) return (n / 1_000_000_000).toFixed(2) + 'B'
  if (abs >= 1_000_000) return (n / 1_000_000).toFixed(2) + 'M'
  if (abs >= 10_000) return (n / 1_000).toFixed(1) + 'K'
  return fmtInt(n)
}

/** 金额：默认 4 位小数（单轮成本在 0.00x 量级，必须够精度） */
export function fmtUsd(n: number, digits = 4): string {
  if (!Number.isFinite(n)) return '—'
  if (n !== 0 && Math.abs(n) < 0.0001) return '<$0.0001'
  return '$' + n.toFixed(digits)
}

/** 百分比：0.4372 -> "43.72%" */
export function fmtPct(ratio: number, digits = 2): string {
  if (!Number.isFinite(ratio)) return '—'
  return (ratio * 100).toFixed(digits) + '%'
}

/** 已是百分数的值：43.72 -> "43.72%" */
export function fmtPctValue(pct: number, digits = 2): string {
  if (!Number.isFinite(pct)) return '—'
  return pct.toFixed(digits) + '%'
}

/** 带符号的百分比变化：-1.2 -> "-1.20%"；0 -> "0.00%" */
export function fmtSignedPct(pct: number | null, digits = 2): string {
  if (pct === null || !Number.isFinite(pct)) return '—'
  const sign = pct > 0 ? '+' : ''
  return sign + pct.toFixed(digits) + '%'
}

/** 耗时：1834 -> "1.83 s"；45231 -> "45.2 s" */
export function fmtMs(ms: number): string {
  if (!Number.isFinite(ms)) return '—'
  if (ms >= 60_000) return (ms / 60_000).toFixed(2) + ' min'
  if (ms >= 1_000) return (ms / 1_000).toFixed(2) + ' s'
  return Math.round(ms) + ' ms'
}

/** ISO 时间 -> "2026-11-10 21:30" */
export function fmtDateTime(iso: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** 策略枚举 -> 中文 */
export const STRATEGY_LABELS: Record<string, string> = {
  FIXED_SIZE: '固定大小',
  SEMANTIC: '语义分块',
  VECTOR_ONLY: '仅向量',
  MULTI_CHANNEL: '多路融合',
  FIXED_PREMIUM: '固定高档',
  INTENT_ROUTED: '意图路由',
  DISABLED: '关闭',
  ENABLED: '开启',
}

export const strategyLabel = (v: string): string => STRATEGY_LABELS[v] ?? v ?? '—'
