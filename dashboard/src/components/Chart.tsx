import { useEffect, useRef } from 'react'
import { echarts, CHART_THEME } from '../charts/echarts'
import type { EChartsOption } from '../charts/echarts'

interface Props {
  option: EChartsOption
  /** 图表高度（px），默认 320 */
  height?: number
  className?: string
  /** 无数据时显示的占位文案 */
  emptyHint?: string
  isEmpty?: boolean
}

/**
 * 通用 ECharts 容器。
 *
 * 关键点：
 * - 用 ResizeObserver 跟随容器尺寸（栅格布局/侧栏折叠都能正确重排）
 * - 卸载时 dispose，避免内存泄漏与「实例已存在」警告
 * - option 变化走 setOption(option, true)，不做 merge，
 *   避免切换实验后残留上一个实验的系列
 * - 数据为空时不渲染图表，显示占位
 */
export default function Chart({
  option,
  height = 320,
  className = '',
  emptyHint = '暂无数据',
  isEmpty = false,
}: Props) {
  const ref = useRef<HTMLDivElement | null>(null)
  const instRef = useRef<echarts.ECharts | null>(null)

  useEffect(() => {
    const el = ref.current
    if (!el || isEmpty) return

    const inst = echarts.init(el, CHART_THEME, { renderer: 'canvas' })
    instRef.current = inst

    const ro = new ResizeObserver(() => {
      // rAF 内重排，避免 ResizeObserver 循环告警
      requestAnimationFrame(() => inst.resize())
    })
    ro.observe(el)

    return () => {
      ro.disconnect()
      inst.dispose()
      instRef.current = null
    }
  }, [isEmpty])

  useEffect(() => {
    const inst = instRef.current
    if (!inst || isEmpty) return
    inst.setOption(option, true)
  }, [option, isEmpty])

  if (isEmpty) {
    return (
      <div
        className={`flex flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-ink-700/70 bg-ink-850/40 text-ink-400 ${className}`}
        style={{ height }}
      >
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5">
          <path d="M3 3v18h18" strokeLinecap="round" />
          <path d="M7 14l3-3 3 3 4-6" strokeLinecap="round" strokeLinejoin="round" opacity="0.5" />
        </svg>
        <span className="text-xs">{emptyHint}</span>
      </div>
    )
  }

  return <div ref={ref} className={className} style={{ height, width: '100%' }} />
}
