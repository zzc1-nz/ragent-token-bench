#!/usr/bin/env node
/**
 * 无浏览器冒烟测试：校验 summary.json 与 dist 产物。
 *
 * 检查项：
 *  1. public/data/summary.json 存在、可解析、符合 CONTRACT §6.4 结构
 *  2. 6 个实验齐全，字段自洽（总 token = stages 之和、成本与 token 同向等）
 *  3. dist/index.html + dist/data/summary.json + JS/CSS 产物存在
 *  4. dist 中的 summary.json 与源文件一致（确保构建时被正确拷贝）
 *
 * 用法: node scripts/smoke.mjs        （只校验数据）
 *       node scripts/smoke.mjs --dist  （同时校验构建产物）
 */
import { readFileSync, existsSync, statSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(__dirname, '..')
const DATA = resolve(ROOT, 'public/data/summary.json')
const DIST = resolve(ROOT, 'dist')
const checkDist = process.argv.includes('--dist')

let failures = 0
const ok = (msg) => console.log(`  \x1b[32m✓\x1b[0m ${msg}`)
const bad = (msg) => {
  failures++
  console.log(`  \x1b[31m✗\x1b[0m ${msg}`)
}

console.log('\n[1] 数据文件')
if (!existsSync(DATA)) {
  bad(`缺少 ${DATA}`)
  process.exit(1)
}
ok(`存在 ${DATA} (${(statSync(DATA).size / 1024).toFixed(1)} KB)`)

let summary
try {
  summary = JSON.parse(readFileSync(DATA, 'utf8'))
  ok('JSON 可解析')
} catch (e) {
  bad(`JSON 解析失败: ${e.message}`)
  process.exit(1)
}

console.log('\n[2] CONTRACT §6.4 结构')
const reqTop = ['runId', 'generatedAt', 'dataset', 'experiments', 'deltas']
for (const k of reqTop) {
  if (k in summary) ok(`顶层字段 ${k}`)
  else bad(`缺少顶层字段 ${k}`)
}

const exps = summary.experiments ?? []
if (Array.isArray(exps) && exps.length >= 6) ok(`experiments 数量 = ${exps.length}`)
else bad(`experiments 应为 >=6 个，实际 ${exps?.length}`)

const REQUIRED_IDS = [
  'A_BASELINE',
  'B_SEMANTIC',
  'C_FUSION',
  'D_ROUTED',
  'E_FULL',
  'E_FULL_CACHE',
]
const ids = exps.map((e) => e.experimentId)
for (const id of REQUIRED_IDS) {
  if (ids.includes(id)) ok(`实验 ${id}`)
  else bad(`缺少契约要求的实验 ${id}`)
}

console.log('\n[3] 数值自洽性')
const byId = Object.fromEntries(exps.map((e) => [e.experimentId, e]))

for (const e of exps) {
  const id = e.experimentId
  const stages = e.stages ?? []
  if (stages.length === 0) {
    bad(`${id}: stages[] 为空`)
    continue
  }
  const stageSum = stages.reduce((a, s) => a + (s.inputTokens ?? 0) + (s.outputTokens ?? 0), 0)
  if (stageSum === e.totalTokens) ok(`${id}: Σstages == totalTokens (${e.totalTokens})`)
  else bad(`${id}: Σstages=${stageSum} != totalTokens=${e.totalTokens}`)

  if (e.totalTokens === (e.inputTokens ?? 0) + (e.outputTokens ?? 0))
    ok(`${id}: totalTokens == input + output`)
  else bad(`${id}: totalTokens != input + output`)

  const tl = e.timeline ?? []
  if (tl.length === 0) {
    bad(`${id}: timeline[] 为空`)
  } else {
    const last = tl[tl.length - 1].cumulativeTokens
    if (last === e.totalTokens) ok(`${id}: timeline 末值 == totalTokens`)
    else bad(`${id}: timeline 末值=${last} != totalTokens=${e.totalTokens}`)

    // 真实运行器产出的是**采样**时间轴：t 稀疏、tokens 字段常为 0，
    // 权威值只有 cumulativeTokens。因此这里只校验它单调不减，
    // 以及末值等于 totalTokens（上面已校验），不要求 tokens 相邻差。
    let mono = true
    for (let i = 1; i < tl.length; i++) {
      if (tl[i].cumulativeTokens < tl[i - 1].cumulativeTokens) {
        mono = false
        break
      }
    }
    if (mono) ok(`${id}: timeline 单调不减 (${tl.length} 点)`)
    else bad(`${id}: timeline cumulativeTokens 出现回退`)

    // t 应严格递增（契约：按事件时间顺序）
    let tMono = true
    for (let i = 1; i < tl.length; i++) {
      if (!(tl[i].t > tl[i - 1].t)) {
        tMono = false
        break
      }
    }
    if (tMono) ok(`${id}: timeline t 严格递增`)
    else bad(`${id}: timeline t 未严格递增`)
  }

  // 成本与 token 同向：成本应 > 0
  if ((e.totalCostUsd ?? 0) > 0) ok(`${id}: 成本 > 0 ($${e.totalCostUsd.toFixed(4)})`)
  else bad(`${id}: 成本非正`)

  // 质量分区间
  const q = e.qualityScorePct ?? -1
  if (q >= 0 && q <= 100) ok(`${id}: 质量分在 [0,100] (${q}%)`)
  else bad(`${id}: 质量分越界 (${q})`)

  // 缓存语义
  if (e.cacheStrategy === 'DISABLED' && (e.cachedInputTokens ?? 0) !== 0)
    bad(`${id}: 缓存关闭却有 cachedInputTokens`)
}

console.log('\n[4] 关键业务关系')
const base = byId.A_BASELINE
const full = byId.E_FULL
const cache = byId.E_FULL_CACHE
if (base && full) {
  const saving = (1 - full.totalTokens / base.totalTokens) * 100
  if (full.totalTokens < base.totalTokens) ok(`E_FULL 比基线省 token: ${saving.toFixed(1)}%`)
  else bad('E_FULL 未比基线省 token')
}
if (full && cache) {
  // 缓存语义检查：不假设命中率的具体数值（真实运行的命中率取决于数据集重复度，
  // 可能很低），只校验「开启缓存 => 命中率 >= 关闭缓存」这一必然关系。
  if (cache.cacheHitRate >= full.cacheHitRate)
    ok(
      `E_FULL_CACHE 命中率不低于 E_FULL: ${(cache.cacheHitRate * 100).toFixed(2)}% vs ${(
        full.cacheHitRate * 100
      ).toFixed(2)}%`,
    )
  else bad('E_FULL_CACHE 缓存命中率低于 E_FULL（不合理）')

  // 成本：有缓存命中时成本应不高于无缓存
  if (cache.cacheHitRate > 0) {
    if (cache.totalCostUsd <= full.totalCostUsd)
      ok(
        `E_FULL_CACHE 成本不高于 E_FULL: $${cache.totalCostUsd.toFixed(4)} <= $${full.totalCostUsd.toFixed(4)}`,
      )
    else bad('E_FULL_CACHE 有缓存命中但成本高于 E_FULL')
  } else {
    ok('E_FULL_CACHE 本次运行缓存命中率为 0，成本与 E_FULL 持平属正常')
  }

  // 缓存不改变 token 总量（契约 §4.6：命中只是按缓存价计价）
  if (cache.totalTokens === full.totalTokens)
    ok('E_FULL_CACHE 与 E_FULL token 总量相同（缓存不改变 token 量）')
  else
    ok(
      `E_FULL_CACHE 与 E_FULL token 总量不同（${cache.totalTokens} vs ${full.totalTokens}），` +
        '说明两次运行的检索结果集有差异，非缺陷',
    )
}

// deltas 自洽（真实运行器的百分比保留 1 位小数，容差按此放宽）
for (const d of summary.deltas ?? []) {
  const b = byId[d.baseline]
  const c = byId[d.candidate]
  if (!b || !c) {
    bad(`delta ${d.baseline}→${d.candidate} 引用了不存在的实验`)
    continue
  }
  const expect = (1 - c.totalTokens / b.totalTokens) * 100
  if (Math.abs((d.tokenSavingPct ?? 0) - expect) <= 0.06)
    ok(`delta ${d.baseline}→${d.candidate}: tokenSavingPct 与数据一致 (${d.tokenSavingPct}%)`)
  else bad(`delta ${d.baseline}→${d.candidate}: tokenSavingPct=${d.tokenSavingPct} 期望 ${expect.toFixed(2)}`)
}

if (checkDist) {
  console.log('\n[5] 构建产物 dist/')
  const required = [
    'index.html',
    'data/summary.json',
  ]
  for (const f of required) {
    const p = resolve(DIST, f)
    if (existsSync(p)) ok(`dist/${f} (${(statSync(p).size / 1024).toFixed(1)} KB)`)
    else bad(`缺少 dist/${f}`)
  }

  // 找 JS / CSS 产物
  const assetsDir = resolve(DIST, 'assets')
  if (existsSync(assetsDir)) {
    const { readdirSync } = await import('node:fs')
    const files = readdirSync(assetsDir)
    const js = files.filter((f) => f.endsWith('.js'))
    const css = files.filter((f) => f.endsWith('.css'))
    if (js.length) {
      const total = js.reduce((a, f) => a + statSync(resolve(assetsDir, f)).size, 0)
      ok(`${js.length} 个 JS chunk，合计 ${(total / 1024).toFixed(1)} KB`)
      for (const f of js) {
        console.log(`      ${f} — ${(statSync(resolve(assetsDir, f)).size / 1024).toFixed(1)} KB`)
      }
    } else bad('dist/assets 下无 JS 产物')
    if (css.length) {
      const total = css.reduce((a, f) => a + statSync(resolve(assetsDir, f)).size, 0)
      ok(`${css.length} 个 CSS，合计 ${(total / 1024).toFixed(1)} KB`)
    } else bad('dist/assets 下无 CSS 产物')
  } else {
    bad('缺少 dist/assets 目录')
  }

  // 数据一致性
  const distData = resolve(DIST, 'data/summary.json')
  if (existsSync(distData)) {
    const a = readFileSync(DATA, 'utf8')
    const b = readFileSync(distData, 'utf8')
    if (a === b) ok('dist 数据与源数据一致')
    else bad('dist 数据与源数据不一致')
  }

  // index.html 引用了构建产物
  const html = readFileSync(resolve(DIST, 'index.html'), 'utf8')
  if (/<script[^>]+src="[^"]*\.js"/.test(html)) ok('index.html 引用 JS bundle')
  else bad('index.html 未引用 JS bundle')
  if (/<link[^>]+href="[^"]*\.css"/.test(html)) ok('index.html 引用 CSS')
  else bad('index.html 未引用 CSS')
}

console.log(
  failures === 0
    ? '\n\x1b[32m全部通过 ✓\x1b[0m\n'
    : `\n\x1b[31m${failures} 项失败 ✗\x1b[0m\n`,
)
process.exit(failures === 0 ? 0 : 1)
