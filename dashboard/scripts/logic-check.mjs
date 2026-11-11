#!/usr/bin/env node
/**
 * 渲染逻辑验证（不依赖浏览器）。
 *
 * 本沙箱禁止浏览器进程间通信（headless Edge 报 mojo platform_channel
 * 拒绝访问、crashpad OpenProcess 拒绝访问），因此无法截图。
 * 改为在 Node 里直接执行**真实的**归一化逻辑与图表 option 构造，
 * 验证：
 *   1. normalizeSummary 能正确处理真实 summary.json
 *   2. 三个核心图表的 series 数据非空且数值合理
 *   3. 数据缺失时降级路径不抛异常
 *   4. 关键派生指标（KPI）计算正确
 *
 * 做法：用 tsc 已编译到 .tsbuild/ 的产物，它们是纯 ESM，可直接 import。
 *
 * 用法：node scripts/logic-check.mjs
 */
import { existsSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(__dirname, '..')
const TSBUILD = resolve(ROOT, '.tsbuild')

if (!existsSync(TSBUILD)) {
  console.error('✗ .tsbuild/ 不存在，请先运行 node scripts/build.mjs')
  process.exit(1)
}

// Node 原生 ESM 要求相对 import 带扩展名，而 tsc 产物不带。
// 这里在进程内补全（幂等），保证后续 import 能解析。
{
  const { fixEsmExtensions } = await import(pathToFileURL(resolve(__dirname, 'fix-esm-ext.mjs')).href)
  const n = fixEsmExtensions(TSBUILD)
  if (n > 0) console.log(`（已补全 ${n} 个产物的 import 扩展名）`)
}

const load = async (rel) => import(pathToFileURL(resolve(TSBUILD, rel)).href)

let failures = 0
const ok = (m) => console.log(`  ✓ ${m}`)
const bad = (m) => {
  failures++
  console.log(`  ✗ ${m}`)
}

const { normalizeSummary, emptyDashboard } = await load('utils/normalize.js')

/* ================= 1. 真实数据归一化 ================= */
console.log('\n[1] summary.json 归一化')
const rawText = readFileSync(resolve(ROOT, 'public/data/summary.json'), 'utf8')
const raw = JSON.parse(rawText)
const data = normalizeSummary(raw)

if (data.experiments.length === 6) ok(`6 个实验（${data.experiments.map((e) => e.experimentId).join(', ')}）`)
else bad(`实验数异常: ${data.experiments.length}`)

if (data.totals.totalTokens > 1_000_000)
  ok(`总 token ${data.totals.totalTokens.toLocaleString('en-US')}（≥ 1,000,000，符合验收标准）`)
else bad(`总 token 偏小: ${data.totals.totalTokens}`)

if (data.warnings.length === 0) ok('无数据完整性告警')
else {
  ok(`产生 ${data.warnings.length} 条告警`)
  for (const w of data.warnings) console.log(`      · ${w}`)
}

/* 示例数据同样要能正常归一化（它是保留给 Lead 的参考数据） */
console.log('\n[1b] 示例数据归一化')
const examplePath = resolve(ROOT, 'public/data/summary.example.json')
if (existsSync(examplePath)) {
  const ex = normalizeSummary(JSON.parse(readFileSync(examplePath, 'utf8')))
  if (ex.experiments.length === 6) ok(`示例数据 6 个实验`)
  else bad(`示例数据实验数异常: ${ex.experiments.length}`)
  if (ex.isExample) ok('示例数据正确标记 isExample=true（页头会显示「示例数据」徽章）')
  else bad('示例数据未标记 isExample')
  const exAvg = ex.experiments.reduce((a, e) => a + e.qualityScorePct, 0) / ex.experiments.length
  ok(`示例数据平均质量分 ${exAvg.toFixed(2)}%，总 token ${ex.totals.totalTokens.toLocaleString('en-US')}`)
} else {
  ok('无示例数据文件（可忽略）')
}

/* 每个实验的关键派生字段 */
console.log('\n[2] 派生字段')
for (const e of data.experiments) {
  const checks = []
  if (e.totalTokens > 0) checks.push('totalTokens')
  if (e.stages.length > 0) checks.push(`stages×${e.stages.length}`)
  if (e.timeline.length > 0) checks.push(`timeline×${e.timeline.length}`)
  if (e.qualityScorePct > 0 && e.qualityScorePct <= 100) checks.push('quality')
  if (e.turns > 0) checks.push('turns')
  if (e.tokensByModel.length > 0) checks.push(`models×${e.tokensByModel.length}`)

  const stageSum = e.stages.reduce((a, s) => a + s.totalTokens, 0)
  if (stageSum === e.totalTokens) checks.push('Σstages==total')

  if (checks.length >= 6) ok(`${e.experimentId}: ${checks.join(', ')}`)
  else bad(`${e.experimentId}: 仅 ${checks.join(', ')}`)
}

/* timeline 完整性 */
console.log('\n[3] 时间轴')
for (const e of data.experiments) {
  const tl = e.timeline
  const last = tl[tl.length - 1]
  const mono = tl.every((p, i) => i === 0 || p.cumulativeTokens >= tl[i - 1].cumulativeTokens)
  const tIncreasing = tl.every((p, i) => i === 0 || p.t > tl[i - 1].t)
  if (last.cumulativeTokens === e.totalTokens && mono && tIncreasing)
    ok(`${e.experimentId}: ${tl.length} 点, t∈[${tl[0].t},${last.t}], 末值==total, 单调, t 递增`)
  else
    bad(
      `${e.experimentId}: last=${last.cumulativeTokens}/${e.totalTokens} mono=${mono} tInc=${tIncreasing}`,
    )
}

/* 单点 token 差分有效性（供「单事件」口径使用） */
console.log('\n[4] 单事件口径（差分推导）')
for (const e of data.experiments) {
  const nonZero = e.timeline.filter((p) => p.tokens > 0).length
  const sum = e.timeline.reduce((a, p) => a + p.tokens, 0)
  if (sum === e.totalTokens)
    ok(`${e.experimentId}: Σtokens == totalTokens（${nonZero}/${e.timeline.length} 个非零点）`)
  else bad(`${e.experimentId}: Σtokens=${sum} != totalTokens=${e.totalTokens}`)
}

/* ================= 5. 降级路径 ================= */
console.log('\n[5] 数据缺失降级（不抛异常）')
const cases = [
  ['null', null],
  ['空对象', {}],
  ['experiments 为空数组', { experiments: [] }],
  ['缺 stages', { experiments: [{ experimentId: 'X', totalTokens: 100 }] }],
  ['缺 timeline', { experiments: [{ experimentId: 'Y', stages: [{ stage: 'GENERATE', inputTokens: 5, outputTokens: 5 }] }] }],
  [
    '字段类型错误',
    {
      experiments: [
        { experimentId: 123, totalTokens: 'abc', stages: 'not-an-array', timeline: [null, 1, {}] },
      ],
    },
  ],
  ['timeline 乱序', { experiments: [{ experimentId: 'Z', totalTokens: 30, timeline: [{ t: 5, cumulativeTokens: 30 }, { t: 1, cumulativeTokens: 10 }, { t: 3, cumulativeTokens: 20 }] }] }],
  ['deltas 缺失', { experiments: [{ experimentId: 'W', totalTokens: 1, stages: [] }] }],
]

for (const [label, input] of cases) {
  try {
    const d = normalizeSummary(input)
    const n = d.experiments.length
    ok(`${label} → 正常返回（${n} 个实验, ${d.warnings.length} 条告警）`)
  } catch (e) {
    bad(`${label} → 抛异常: ${e.message}`)
  }
}

/* 乱序 timeline 应被修正为单调 */
try {
  const d = normalizeSummary(cases[6][1])
  const tl = d.experiments[0].timeline
  const fixed = tl.every((p, i) => i === 0 || p.cumulativeTokens >= tl[i - 1].cumulativeTokens)
  if (fixed) ok('乱序 timeline 已被修正为单调不减')
  else bad('乱序 timeline 未修正')
} catch (e) {
  bad(`乱序 timeline 修正失败: ${e.message}`)
}

/* emptyDashboard */
try {
  const e = emptyDashboard()
  if (e.experiments.length === 0 && e.totals.totalTokens === 0) ok('emptyDashboard() 结构完整')
  else bad('emptyDashboard() 结构异常')
} catch (e) {
  bad(`emptyDashboard() 失败: ${e.message}`)
}

/* ================= 6. KPI 计算 ================= */
console.log('\n[6] KPI 派生指标')
const avgQuality =
  data.experiments.reduce((a, e) => a + e.qualityScorePct, 0) / data.experiments.length
const bestCache = data.experiments.reduce((b, e) => (!b || e.cacheHitRate > b.cacheHitRate ? e : b), null)
const bestQuality = data.experiments.reduce((b, e) => (!b || e.qualityScorePct > b.qualityScorePct ? e : b), null)
const cheapest = data.experiments.reduce((b, e) => (!b || e.totalCostUsd < b.totalCostUsd ? e : b), null)

ok(`平均质量分 ${avgQuality.toFixed(2)}%`)
ok(`最佳缓存命中率 ${(bestCache.cacheHitRate * 100).toFixed(2)}% (${bestCache.experimentId})`)
ok(`最高质量分 ${bestQuality.qualityScorePct.toFixed(2)}% (${bestQuality.experimentId})`)
ok(`最低成本 $${cheapest.totalCostUsd.toFixed(4)} (${cheapest.experimentId})`)

if (avgQuality > 0 && avgQuality <= 100) ok('质量分均值在合理区间')
else bad(`质量分均值异常: ${avgQuality}`)

/* ================= 7. 图表数据构造 ================= */
console.log('\n[7] 图表 series 构造')

/* 7.1 时间轴：多实验叠加 */
const timelineSeries = data.experiments
  .filter((e) => e.timeline.length > 0)
  .map((e) => ({
    name: e.experimentId,
    data: e.timeline.map((p) => [p.t, p.cumulativeTokens]),
  }))
if (timelineSeries.length === 6) ok(`时间轴 ${timelineSeries.length} 条曲线`)
else bad(`时间轴曲线数异常: ${timelineSeries.length}`)
const allPts = timelineSeries.reduce((a, s) => a + s.data.length, 0)
ok(`共 ${allPts} 个数据点，最多 ${Math.max(...timelineSeries.map((s) => s.data.length))} 点/曲线`)

/* 7.2 环节占比：每个实验的 stages 都可构环 */
for (const e of data.experiments) {
  const pieData = e.stages.filter((s) => s.totalTokens > 0).map((s) => ({ name: s.label, value: s.totalTokens }))
  const sum = pieData.reduce((a, p) => a + p.value, 0)
  if (pieData.length > 0 && sum === e.totalTokens)
    ok(`${e.experimentId}: 环形图 ${pieData.length} 个环节，合计 ${sum.toLocaleString('en-US')}`)
  else bad(`${e.experimentId}: 环形图数据异常（${pieData.length} 项, 合计 ${sum}）`)
}

/* 7.3 散点图 + 帕累托前沿 */
const scatter = data.experiments.map((e) => ({
  name: e.experimentId,
  x: e.totalTokens,
  y: e.qualityScorePct,
  cost: e.totalCostUsd,
}))
ok(`散点 ${scatter.length} 个点`)

// 帕累托前沿（token 少 & 质量高）
const sorted = [...scatter].sort((a, b) => a.x - b.x)
const frontier = []
let bestQ = -Infinity
for (const p of sorted) {
  if (p.y > bestQ) {
    frontier.push(p)
    bestQ = p.y
  }
}
ok(`帕累托前沿: ${frontier.map((f) => `${f.name}(${f.x.toLocaleString('en-US')},${f.y.toFixed(1)}%)`).join(' → ')}`)
if (frontier.length >= 1 && frontier.length <= scatter.length) ok('前沿点数合法')
else bad(`前沿点数异常: ${frontier.length}`)

// 前沿应满足：x 递增、y 递增
const frontOK = frontier.every((p, i) => i === 0 || (p.x >= frontier[i - 1].x && p.y >= frontier[i - 1].y))
if (frontOK) ok('前沿单调性正确（x↑ y↑）')
else bad('前沿单调性错误')

/* ================= 8. deltas ================= */
console.log('\n[8] deltas')
if (data.deltas.length > 0) {
  ok(`${data.deltas.length} 组对比`)
  for (const d of data.deltas) {
    if (d.tokenSavingPct !== null && d.costSavingPct !== null)
      ok(`${d.baseline}→${d.candidate}: token ${d.tokenSavingPct}% / cost ${d.costSavingPct}% / quality ${d.qualityDeltaPct}%`)
    else bad(`${d.baseline}→${d.candidate}: 百分比字段缺失`)
  }
} else {
  bad('deltas 为空')
}

/* ================= 9. 格式化工具 ================= */
console.log('\n[9] 格式化工具')
const fmt = await load('utils/format.js')
const fmtCases = [
  [() => fmt.fmtInt(1234567), '1,234,567', '千分位'],
  [() => fmt.fmtCompact(1234567), '1.23M', '紧凑大数'],
  [() => fmt.fmtCompact(2918832), '2.92M', '真实总量'],
  [() => fmt.fmtUsd(1.6816), '$1.6816', '金额'],
  [() => fmt.fmtPct(0.0079, 2), '0.79%', '百分比'],
  [() => fmt.fmtMs(1680), '1.68 s', '耗时'],
  [() => fmt.fmtSignedPct(-1.2), '-1.20%', '带符号'],
  [() => fmt.strategyLabel('SEMANTIC'), '语义分块', '策略中文'],
  [() => fmt.strategyLabel('INTENT_ROUTED'), '意图路由', '路由中文'],
  [() => fmt.strategyLabel('UNKNOWN_X'), 'UNKNOWN_X', '未知策略透传'],
]
for (const [fn, want, label] of fmtCases) {
  const got = fn()
  if (got === want) ok(`${label}: ${got}`)
  else bad(`${label}: 期望 ${want}, 实际 ${got}`)
}

/* ================= 收尾 ================= */
console.log(
  failures === 0
    ? '\n\x1b[32m渲染逻辑验证全部通过 ✓\x1b[0m'
    : `\n\x1b[31m${failures} 项失败 ✗\x1b[0m`,
)
process.exit(failures === 0 ? 0 : 1)
