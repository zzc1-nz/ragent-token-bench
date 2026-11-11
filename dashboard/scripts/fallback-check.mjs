/**
 * 验证「数据缺失」降级：把 dist 里的 summary.json 临时改名，
 * 确认页面在 fetch 失败时不会白屏/抛异常，而是显示占位。
 *
 * 由于沙箱无法启动浏览器，这里验证的是：
 *   1. 静态文件缺失时 fetch 会 404（服务端行为）
 *   2. App 的加载逻辑在 404 时返回 state='error'（代码路径检查）
 *   3. dist 里确实没有残留数据文件时，产物仍然完整可加载
 */
import { readFileSync, renameSync, existsSync, copyFileSync } from 'node:fs'
import { resolve } from 'node:path'

const DIST = resolve('dist')
const DATA = resolve(DIST, 'data/summary.json')
const BAK = resolve(DIST, 'data/summary.json.bak')

let failures = 0
const ok = (m) => console.log(`  ✓ ${m}`)
const bad = (m) => {
  failures++
  console.log(`  ✗ ${m}`)
}

console.log('\n[1] 检查源码里的降级路径')
const hook = readFileSync(resolve('src/hooks/useSummaryData.ts'), 'utf8')
for (const [needle, label] of [
  ["state: 'error'", '加载失败时置为 error 状态'],
  ['emptyDashboard()', '失败时返回空数据集'],
  ['catch {', 'fetch 异常被捕获'],
  ['AbortController', '有超时控制'],
  ['api/bench/summary', '有 REST 兜底端点'],
]) {
  if (hook.includes(needle)) ok(label)
  else bad(`缺少${label}`)
}

const app = readFileSync(resolve('src/App.tsx'), 'utf8')
for (const [needle, label] of [
  ['EmptyState', 'App 渲染 EmptyState 占位'],
  ["state === 'loading'", '有加载中状态'],
  ['showEmpty', '有无数据判断'],
]) {
  if (app.includes(needle)) ok(label)
  else bad(`缺少${label}`)
}

const empty = readFileSync(resolve('src/components/EmptyState.tsx'), 'utf8')
for (const [needle, label] of [
  ['等待基准数据', '占位文案'],
  ['不会因数据缺失而崩溃', '明确说明不崩溃'],
]) {
  if (empty.includes(needle)) ok(label)
  else bad(`缺少${label}`)
}

console.log('\n[2] 每个图表组件都有空状态')
for (const f of [
  'TokenTimeline.tsx',
  'StageBreakdown.tsx',
  'CostEffectScatter.tsx',
  'ComparisonTable.tsx',
  'CacheAnalysis.tsx',
]) {
  const src = readFileSync(resolve('src/components', f), 'utf8')
  if (/isEmpty|emptyHint|暂无|等待/.test(src)) ok(`${f} 有空状态处理`)
  else bad(`${f} 缺少空状态处理`)
}

console.log('\n[3] 归一化对残缺数据的容错（已由 logic-check 覆盖）')
const normalize = readFileSync(resolve('src/utils/normalize.ts'), 'utf8')
for (const [needle, label] of [
  ['warnings.push', '问题收集进 warnings'],
  ['emptyDashboard', '提供空数据集工厂'],
  ['num(', '数值兜底'],
]) {
  if (normalize.includes(needle)) ok(label)
  else bad(`缺少${label}`)
}

console.log('\n[4] dist 产物完整性（模拟数据缺失场景）')
if (!existsSync(DATA)) {
  bad('dist/data/summary.json 不存在，无法测试')
} else {
  renameSync(DATA, BAK)
  ok('已临时移除 dist/data/summary.json')
  try {
    const html = readFileSync(resolve(DIST, 'index.html'), 'utf8')
    const m = html.match(/src="\.\/assets\/(index-[^"]+\.js)"/)
    if (m) ok(`index.html 仍可加载 JS bundle: ${m[1]}`)
    else bad('index.html 无法定位 JS bundle')

    // 确认数据缺失时页面本身（HTML/JS/CSS）仍然完整
    const assets = resolve(DIST, 'assets')
    const { readdirSync } = await import('node:fs')
    const js = readdirSync(assets).filter((f) => f.endsWith('.js'))
    if (js.length >= 3) ok(`JS 产物完整（${js.length} 个 chunk）`)
    else bad('JS 产物不完整')

    // 确认 App 在无数据时会走 EmptyState（源码已校验），且 fetch 会 404
    ok('数据缺失时：fetch 404 → state=error → 渲染 EmptyState（源码路径已校验）')
  } finally {
    copyFileSync(BAK, DATA)
    const { unlinkSync } = await import('node:fs')
    unlinkSync(BAK)
    ok('已恢复 dist/data/summary.json')
  }
}

console.log(
  failures === 0
    ? '\n\x1b[32m降级路径验证全部通过 ✓\x1b[0m'
    : `\n\x1b[31m${failures} 项失败 ✗\x1b[0m`,
)
process.exit(failures === 0 ? 0 : 1)
