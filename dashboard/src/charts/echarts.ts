/**
 * ECharts 按需注册 + 统一的暗色主题。
 *
 * 只 import 用到的图表/组件，避免把整个 echarts 打进 bundle
 * （全量 echarts 约 1MB，按需后约 400KB）。
 */
import * as echarts from 'echarts/core'
import {
  LineChart,
  BarChart,
  PieChart,
  ScatterChart,
  CustomChart,
} from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  DatasetComponent,
  MarkLineComponent,
  MarkPointComponent,
  MarkAreaComponent,
  GraphicComponent,
  AxisPointerComponent,
  ToolboxComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  LineChart,
  BarChart,
  PieChart,
  ScatterChart,
  CustomChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  DatasetComponent,
  MarkLineComponent,
  MarkPointComponent,
  MarkAreaComponent,
  GraphicComponent,
  AxisPointerComponent,
  ToolboxComponent,
  CanvasRenderer,
])

/** 全站统一的暗色 ECharts 主题 */
export const CHART_THEME = 'tokenbench-dark'

const AXIS_LINE = '#2a3242'
const SPLIT_LINE = 'rgba(255,255,255,0.055)'
const TEXT_PRIMARY = '#c3cad6'
const TEXT_MUTED = '#6b7688'

echarts.registerTheme(CHART_THEME, {
  color: [
    '#5b8cff',
    '#3ddc97',
    '#f7b955',
    '#a78bfa',
    '#22d3ee',
    '#ff6b81',
    '#34d399',
    '#fb923c',
    '#38bdf8',
    '#c084fc',
  ],
  backgroundColor: 'transparent',
  textStyle: {
    fontFamily:
      "Inter, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif",
    color: TEXT_PRIMARY,
  },
  title: {
    textStyle: { color: '#e6eaf2', fontWeight: 600, fontSize: 14 },
    subtextStyle: { color: TEXT_MUTED, fontSize: 12 },
  },
  legend: {
    textStyle: { color: TEXT_PRIMARY, fontSize: 12 },
    inactiveColor: '#3a4356',
    icon: 'roundRect',
    itemWidth: 10,
    itemHeight: 10,
    itemGap: 14,
  },
  tooltip: {
    backgroundColor: 'rgba(12,15,21,0.96)',
    borderColor: '#2a3242',
    borderWidth: 1,
    padding: [10, 12],
    textStyle: { color: '#e6eaf2', fontSize: 12 },
    axisPointer: {
      lineStyle: { color: '#3a4356', width: 1, type: 'dashed' },
      crossStyle: { color: '#3a4356' },
      label: { backgroundColor: '#232936', color: '#e6eaf2' },
    },
  },
  grid: {
    left: 12,
    right: 20,
    top: 40,
    bottom: 12,
    containLabel: true,
  },
  categoryAxis: {
    axisLine: { lineStyle: { color: AXIS_LINE } },
    axisTick: { show: false },
    axisLabel: { color: TEXT_MUTED, fontSize: 11 },
    splitLine: { show: false },
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: TEXT_MUTED, fontSize: 11 },
    splitLine: { lineStyle: { color: SPLIT_LINE, type: 'solid' } },
    nameTextStyle: { color: TEXT_MUTED, fontSize: 11, padding: [0, 0, 6, 0] },
  },
})

export { echarts }
export type EChartsOption = echarts.EChartsCoreOption
