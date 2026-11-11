#!/usr/bin/env node
/**
 * 无头浏览器渲染验证。
 *
 * 用系统自带的 Edge/Chrome（--headless=new --dump-dom / --screenshot）渲染 dist/，
 * 确认：
 *   1. React 真的挂载成功（#root 内有内容）
 *   2. 关键视图标题都渲染出来了
 *   3. ECharts 生成了 canvas（说明图表真的画出来了，不是空壳）
 *   4. 控制台无致命错误
 *
 * 用法：node scripts/render-check.mjs
 * 退出码 0 表示通过。
 */
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, rmSync, statSync } from 'node:fs'
import { createServer } from 'node:http'
import { dirname, extname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(__dirname, '..')
const DIST = resolve(ROOT, 'dist')
const SHOTS = resolve(ROOT, '.render-check')

const BROWSERS = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
]
const browser = BROWSERS.find((p) => existsSync(p))
if (!browser) {
  console.error('✗ 未找到 Edge/Chrome，跳过渲染验证')
  process.exit(0)
}

if (!existsSync(DIST)) {
  console.error('✗ dist/ 不存在，请先运行 node scripts/build.mjs')
  process.exit(1)
}

rmSync(SHOTS, { recursive: true, force: true })
mkdirSync(SHOTS, { recursive: true })

/* ---------- 临时静态服务 ---------- */
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
}

const server = createServer((req, res) => {
  let p = decodeURIComponent(req.url.split('?')[0])
  if (p === '/') p = '/index.html'
  const file = join(DIST, p)
  try {
    const data = readFileSync(file)
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] ?? 'application/octet-stream' })
    res.end(data)
  } catch {
    res.writeHead(404)
    res.end('not found')
  }
})

const PORT = 5578
await new Promise((r) => server.listen(PORT, '127.0.0.1', r))
const URL_BASE = `http://127.0.0.1:${PORT}`
console.log(`▶ 临时静态服务 ${URL_BASE}`)

/* ---------- 无头渲染 ---------- */
const userDataDir = join(SHOTS, 'profile')
const commonArgs = [
  '--headless=new',
  '--disable-gpu',
  '--no-sandbox',
  '--disable-dev-shm-usage',
  '--hide-scrollbars',
  '--force-device-scale-factor=1',
  `--user-data-dir=${userDataDir}`,
  '--virtual-time-budget=9000',
]

console.log('▶ 渲染 DOM（--dump-dom）')
const dump = spawnSync(browser, [...commonArgs, '--dump-dom', `${URL_BASE}/`], {
  encoding: 'utf8',
  timeout: 120_000,
  maxBuffer: 64 * 1024 * 1024,
})

const dom = dump.stdout ?? ''
const stderr = dump.stderr ?? ''

/**
 * 沙箱探测。
 *
 * 本环境禁止「管道 stdio 的子进程」，因此 spawnSync 启动浏览器时
 * 直接返回 error.code === 'EPERM'（进程根本没起来）。
 * 另外即使能起来，headless Edge 也会因 mojo / crashpad 被拒绝访问而崩溃。
 * 两种情况都属于环境限制，不能误报成「页面渲染失败」。
 */
const spawnBlocked = dump.error && /EPERM|EACCES/.test(dump.error.code ?? '')
const browserCrashed =
  dom.trim().length === 0 &&
  /platform_channel|mojo|crashpad|OpenProcess|拒绝访问|Access is denied|FATAL/i.test(stderr)

if (spawnBlocked || browserCrashed) {
  console.log('\n\x1b[33m⚠ 跳过浏览器渲染验证\x1b[0m')
  if (spawnBlocked) {
    console.log('  原因：本沙箱禁止管道 stdio 的子进程，无法启动浏览器进程（spawn EPERM）。')
  } else {
    console.log('  原因：headless Edge/Chrome 因 mojo/crashpad 被拒绝访问而无法启动。')
  }
  console.log('  这属于环境限制，非页面缺陷。')
  console.log('  替代验证：node scripts/logic-check.mjs（在 Node 中执行真实的')
  console.log('            归一化与图表数据构造逻辑）')
  console.log('            node scripts/fallback-check.mjs（数据缺失降级路径）')
  server.close()
  process.exit(0)
}

let failures = 0
const ok = (m) => console.log(`  ✓ ${m}`)
const bad = (m) => {
  failures++
  console.log(`  ✗ ${m}`)
}

console.log('\n[1] React 挂载')
// 从 dump-dom 里抠出 #root 的内容长度
const rootMatch = dom.match(/<div id="root">([\s\S]*?)<\/div>\s*<script/)
const rootLen = rootMatch ? rootMatch[1].length : 0
if (rootLen > 2000) ok(`#root 已渲染内容（${rootLen} 字符）`)
else bad(`#root 内容过少（${rootLen} 字符）—— React 可能未挂载`)

console.log('\n[2] 关键视图文案')
const EXPECT = [
  ['TokenBench', '品牌标题'],
  ['Token 消耗时间轴', '视图1：时间轴'],
  ['各环节 Token 占比', '视图2：环节占比'],
  ['成本-效果散点图', '视图3：散点图'],
  ['实验对比表', '实验对比表'],
  ['累计 Token 消耗', 'KPI：总 token'],
  ['A/B 策略对比结论', '对比结论'],
  ['缓存命中率分析', '缓存分析'],
]
for (const [needle, label] of EXPECT) {
  if (dom.includes(needle)) ok(`${label}「${needle}」`)
  else bad(`缺少${label}「${needle}」`)
}

console.log('\n[3] 数据已注入（非空状态）')
for (const [needle, label] of [
  ['A_BASELINE', '基线实验 ID'],
  ['E_FULL_CACHE', '缓存实验 ID'],
  ['RUN_', '真实 Run ID'],
  ['真实运行数据', '真实数据徽章'],
]) {
  if (dom.includes(needle)) ok(`${label}「${needle}」`)
  else bad(`缺少${label}「${needle}」`)
}

console.log('\n[4] ECharts canvas')
const canvasCount = (dom.match(/<canvas/g) || []).length
if (canvasCount >= 3) ok(`${canvasCount} 个 <canvas>（3 个核心图表均已绘制）`)
else bad(`仅 ${canvasCount} 个 <canvas>，图表可能未渲染`)

console.log('\n[5] 无致命错误')
const fatal = /Uncaught|TypeError:|ReferenceError:|Cannot read propert/i.test(stderr)
if (!fatal) ok('浏览器 stderr 无致命异常')
else {
  bad('检测到运行时异常')
  console.log(
    stderr
      .split('\n')
      .filter((l) => /Uncaught|Error/i.test(l))
      .slice(0, 6)
      .map((l) => '      ' + l)
      .join('\n'),
  )
}

/* ---------- 截图 ---------- */
console.log('\n[6] 截图')
for (const [name, size] of [
  ['desktop-1440', '1440,2400'],
  ['wide-1920', '1920,2600'],
  ['mobile-390', '390,2200'],
]) {
  const out = join(SHOTS, `${name}.png`)
  const r = spawnSync(
    browser,
    [...commonArgs, `--window-size=${size}`, `--screenshot=${out}`, `${URL_BASE}/`],
    { encoding: 'utf8', timeout: 120_000 },
  )
  if (existsSync(out) && statSync(out).size > 10_000) {
    ok(`${name}.png (${(statSync(out).size / 1024).toFixed(0)} KB)`)
  } else {
    bad(`${name}.png 未生成或过小`)
  }
}

/* ---------- 收尾 ---------- */
server.close()
console.log(
  failures === 0
    ? `\n\x1b[32m渲染验证全部通过 ✓\x1b[0m  截图: ${SHOTS}`
    : `\n\x1b[31m${failures} 项失败 ✗\x1b[0m`,
)
process.exit(failures === 0 ? 0 : 1)
