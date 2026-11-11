/**
 * 实验的稳定配色。
 *
 * 关键：颜色必须与「实验 ID」绑定而不是与数组下标绑定，
 * 这样切换/筛选实验后同一实验的颜色不会跳变。
 */

const PALETTE = [
  '#5b8cff', // 蓝
  '#3ddc97', // 绿
  '#f7b955', // 琥珀
  '#a78bfa', // 紫
  '#22d3ee', // 青
  '#ff6b81', // 玫红
  '#34d399', // 翠绿
  '#fb923c', // 橙
  '#38bdf8', // 天蓝
  '#c084fc', // 淡紫
]

/** 基线实验固定用灰色，语义上「基准」应与「候选」区分开 */
const BASELINE_COLOR = '#7c8ba1'

export function buildColorMap(ids: string[]): Record<string, string> {
  const map: Record<string, string> = {}
  let slot = 0
  for (const id of ids) {
    if (/BASELINE/i.test(id)) {
      map[id] = BASELINE_COLOR
    } else {
      map[id] = PALETTE[slot % PALETTE.length]
      slot++
    }
  }
  return map
}
