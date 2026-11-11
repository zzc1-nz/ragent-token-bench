#!/usr/bin/env node
/**
 * 生产构建脚本（Rollup 直出，绕开 Vite 的 esbuild 依赖）。
 *
 * 为什么不用 `vite build`？
 * ------------------------------------------------------------------
 * 本沙箱环境禁止「管道 stdio 的子进程」（`spawn EPERM`）。Vite 内部有两处硬依赖：
 *   1. Windows 路径解析会 `exec("net use")`；
 *   2. `definePlugin` 用 esbuild.transform 替换 `process.env.NODE_ENV`；
 *   3. 以及配置加载、TS/JSX 转译全部走 esbuild 的原生服务进程。
 * SWC 原生插件同样需要沙箱不授予的 ACL 缓存目录。
 *
 * 因此本脚本把构建拆成三个纯 JS 阶段：
 *   [1] tsc      —— TypeScript/JSX → 纯 ESM JavaScript（tsc 是纯 JS 实现）
 *   [2] tailwind —— 扫描源码生成 CSS（Tailwind CLI 是纯 JS）
 *   [3] rollup   —— 打包 JS + terser 压缩（Rollup/Terser 都是纯 JS）
 *
 * 产物结构与标准 Vite 构建一致：dist/index.html + dist/assets/* + dist/data/summary.json
 */
import { spawnSync } from 'node:child_process'
import {
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(__dirname, '..')
const TSBUILD = resolve(ROOT, '.tsbuild')
const DIST = resolve(ROOT, 'dist')
const ASSETS = resolve(DIST, 'assets')

const t0 = Date.now()
const log = (m = '') => console.log(m)
const step = (n, m) => log(`▶ [${n}/6] ${m}`)

/* ================= 1. 清理 ================= */
step(1, '清理旧产物')
rmSync(TSBUILD, { recursive: true, force: true })
rmSync(DIST, { recursive: true, force: true })
mkdirSync(TSBUILD, { recursive: true })
mkdirSync(ASSETS, { recursive: true })

/* ================= 2. tsc 转译 ================= */
step(2, 'tsc 转译 src/ → .tsbuild/（TS + JSX → ESM）')
const tsc = spawnSync(
  process.execPath,
  [resolve(ROOT, 'node_modules/typescript/bin/tsc'), '-p', 'tsconfig.build.json'],
  { cwd: ROOT, stdio: 'inherit' },
)
if (tsc.status !== 0) {
  console.error('\n✗ TypeScript 编译失败')
  process.exit(tsc.status ?? 1)
}

/* ================= 3. Tailwind 生成 CSS ================= */
step(3, 'Tailwind CLI 生成 CSS')
const cssOut = resolve(TSBUILD, 'index.css')
const tw = spawnSync(
  process.execPath,
  [
    resolve(ROOT, 'node_modules/tailwindcss/lib/cli.js'),
    '-i',
    resolve(ROOT, 'src/index.css'),
    '-o',
    cssOut,
    '--minify',
  ],
  { cwd: ROOT, stdio: 'inherit' },
)
if (tw.status !== 0) {
  console.error('\n✗ Tailwind 构建失败')
  process.exit(tw.status ?? 1)
}
const cssSize = statSync(cssOut).size
log(`   CSS 生成完成 ${(cssSize / 1024).toFixed(1)} KB`)

/* ================= 4. Rollup 打包 ================= */
step(4, 'Rollup 打包 + Terser 压缩')

const { rollup } = await import('rollup')
const { default: nodeResolveLite } = await import('./resolve-plugin.mjs')
const { default: commonjs } = await import('@rollup/plugin-commonjs')
const { minify } = await import('terser')

/**
 * 内联 Terser 插件。
 * 不用 @rollup/plugin-terser：它依赖 smob 等传递依赖，
 * 而 pnpm hoisted 安装在沙箱里缺这些包。直接调 terser API 更可靠。
 */
function terserPlugin(options = {}) {
  return {
    name: 'inline-terser',
    async renderChunk(code, chunk) {
      const result = await minify(
        { [chunk.fileName]: code },
        {
          compress: { drop_debugger: true, passes: 1 },
          format: { comments: false },
          mangle: true,
          ...options,
        },
      )
      if (!result?.code) return null
      return { code: result.code, map: result.map ?? null }
    },
  }
}

/**
 * 内联 replace 插件：把 process.env.NODE_ENV 替换成 "production"。
 * 不替换的话，react / react-dom 的 CJS 入口会同时打包 development 与
 * production 两份实现（体积翻倍），且运行时会走开发模式的慢路径。
 */
function replaceNodeEnv(mode = 'production') {
  const re = /process\.env\.NODE_ENV/g
  return {
    name: 'replace-node-env',
    transform(code, id) {
      if (!id.includes('node_modules')) return null
      if (!code.includes('process.env.NODE_ENV')) return null
      return { code: code.replace(re, JSON.stringify(mode)), map: null }
    },
  }
}

let bundle
try {
  bundle = await rollup({
    input: resolve(TSBUILD, 'main.js'),
    // 自带的极简解析器：不依赖额外传递依赖（沙箱内安装成本高）
    plugins: [
      // CSS 由 Tailwind 单独产出：把 import './index.css' 变成空操作。
      // 必须排在解析器**之前**，否则 .css 会先被解析成真实文件路径。
      {
        name: 'ignore-css',
        resolveId(source) {
          if (source.endsWith('.css')) return '\0css-stub'
          return null
        },
        load(id) {
          if (id === '\0css-stub' || id.endsWith('.css')) return 'export default {}'
          return null
        },
      },
      nodeResolveLite(),
      replaceNodeEnv('production'),
      // React 等依赖是 CommonJS，需要转换才能被 Rollup 静态分析
      commonjs({ include: /node_modules/, transformMixedEsModules: true }),
    ],
    onwarn(w, warn) {
      // 忽略第三方库的常见无害警告
      if (w.code === 'CIRCULAR_DEPENDENCY') return
      if (w.code === 'MODULE_LEVEL_DIRECTIVE') return
      warn(w)
    },
  })

  await bundle.write({
    dir: ASSETS,
    format: 'es',
    sourcemap: false,
    entryFileNames: 'index-[hash].js',
    chunkFileNames: '[name]-[hash].js',
    assetFileNames: '[name]-[hash][extname]',
    // 分包：echarts 体积大，单独拆出来利于缓存
    manualChunks(id) {
      if (id.includes('node_modules')) {
        if (id.includes('echarts') || id.includes('zrender')) return 'echarts'
        if (id.includes('react') || id.includes('scheduler')) return 'react'
        return 'vendor'
      }
      return undefined
    },
    plugins: [terserPlugin()],
  })
  log('   Rollup 打包完成')
} catch (e) {
  console.error('\n✗ Rollup 打包失败:', e?.message ?? e)
  if (e?.stack) console.error(e.stack.split('\n').slice(0, 8).join('\n'))
  process.exit(1)
} finally {
  await bundle?.close()
}

/* ================= 5. 生成 index.html ================= */
step(5, '生成 index.html（注入带 hash 的产物引用）')

const htmlSrc = readFileSync(resolve(ROOT, 'index.html'), 'utf8')
const jsFiles = readdirSync(ASSETS).filter((f) => f.endsWith('.js'))
const entryJs = jsFiles.find((f) => f.startsWith('index-')) ?? jsFiles[0]
if (!entryJs) {
  console.error('✗ 未生成 JS 入口产物')
  process.exit(1)
}

// 把 Tailwind 产出的 CSS 放到 assets 下
const cssAssetName = 'index-[hash].css'
const cssHashed = cssAssetName.replace('[hash]', hashString(readFileSync(cssOut, 'utf8')))
cpSync(cssOut, resolve(ASSETS, cssHashed))

let html = htmlSrc
  .replace(
    /<script[^>]*src="\/src\/main\.tsx"[^>]*><\/script>/,
    `<script type="module" crossorigin src="./assets/${entryJs}"></script>`,
  )
  .replace(
    '</head>',
    `  <link rel="stylesheet" crossorigin href="./assets/${cssHashed}">\n  </head>`,
  )

writeFileSync(resolve(DIST, 'index.html'), html, 'utf8')

/* ================= 6. 拷贝静态资源 + 校验 ================= */
step(6, '拷贝 public/ 静态资源并校验产物')

const publicDir = resolve(ROOT, 'public')
if (existsSync(publicDir)) {
  cpSync(publicDir, DIST, { recursive: true })
}

log('')
log('  dist/')
let jsTotal = 0
let bad = 0

for (const f of readdirSync(ASSETS).sort()) {
  const size = statSync(resolve(ASSETS, f)).size
  if (f.endsWith('.js')) jsTotal += size
  log(`    assets/${f.padEnd(30)} ${(size / 1024).toFixed(1).padStart(7)} KB`)
}
log(`    index.html${' '.repeat(24)} ${(statSync(resolve(DIST, 'index.html')).size / 1024).toFixed(1).padStart(7)} KB`)

// 数据文件
const distData = resolve(DIST, 'data/summary.json')
const srcData = resolve(publicDir, 'data/summary.json')
if (existsSync(distData)) {
  log(`    data/summary.json${' '.repeat(16)} ${(statSync(distData).size / 1024).toFixed(1).padStart(7)} KB`)
  if (existsSync(srcData) && readFileSync(distData, 'utf8') === readFileSync(srcData, 'utf8')) {
    log('  ✓ dist/data/summary.json 与源数据一致')
  } else if (existsSync(srcData)) {
    console.error('  ✗ dist 数据与源数据不一致')
    bad++
  }
} else {
  console.error('  ✗ 缺少 dist/data/summary.json')
  bad++
}

// index.html 引用校验
const builtHtml = readFileSync(resolve(DIST, 'index.html'), 'utf8')
if (new RegExp(`src="\\./assets/${entryJs.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}"`).test(builtHtml)) {
  log('  ✓ index.html 引用 JS bundle')
} else {
  console.error('  ✗ index.html 未正确引用 JS bundle')
  bad++
}
if (/<link[^>]+href="\.\/assets\/index-[^"]+\.css"/.test(builtHtml)) {
  log('  ✓ index.html 引用 CSS')
} else {
  console.error('  ✗ index.html 未引用 CSS')
  bad++
}

// UTF-8 编码校验：中文文案不能出现替换字符（说明读写编码不一致）
if (builtHtml.includes('\uFFFD')) {
  console.error('  ✗ index.html 出现 U+FFFD 替换字符，编码可能损坏')
  bad++
} else if (builtHtml.includes('可观测性')) {
  log('  ✓ index.html 中文文案编码正确（UTF-8）')
} else {
  console.error('  ✗ index.html 缺少预期中文文案，编码可能有问题')
  bad++
}

// 构建产物里的中文（JS bundle）同样校验
const jsAll = readdirSync(ASSETS)
  .filter((f) => f.endsWith('.js'))
  .map((f) => readFileSync(resolve(ASSETS, f), 'utf8'))
  .join('')
if (jsAll.includes('\uFFFD')) {
  console.error('  ✗ JS 产物出现 U+FFFD 替换字符，编码可能损坏')
  bad++
} else if (jsAll.includes('Token 消耗时间轴')) {
  log('  ✓ JS 产物中文文案编码正确（UTF-8）')
} else {
  console.error('  ✗ JS 产物缺少预期中文文案')
  bad++
}

// 未解析的构建期常量校验。
// 本项目不用 Vite 的 define 替换，`import.meta.env.*` 若残留会变成 undefined，
// 导致运行时 URL/配置错误（例如数据路径拼成 "undefineddata/summary.json"）。
const unresolved = jsAll.match(/import\.meta\.env/g)
if (unresolved) {
  console.error(`  ✗ JS 产物残留 ${unresolved.length} 处未解析的 import.meta.env（会导致运行时 undefined）`)
  bad++
} else {
  log('  ✓ JS 产物无未解析的 import.meta.env')
}

if (jsTotal === 0) {
  console.error('  ✗ 无 JS 产物')
  bad++
}
if (cssSize === 0) {
  console.error('  ✗ CSS 为空')
  bad++
}

log('')
log(`  JS  合计 ${(jsTotal / 1024).toFixed(1)} KB`)
log(`  CSS 合计 ${(cssSize / 1024).toFixed(1)} KB`)
log(`  总计     ${((jsTotal + cssSize) / 1024).toFixed(1)} KB`)

if (bad > 0) {
  console.error(`\n✗ 构建校验失败（${bad} 项）`)
  process.exit(1)
}

log('')
log(`✓ 构建完成，用时 ${((Date.now() - t0) / 1000).toFixed(2)}s`)
log(`  产物目录: ${DIST}`)

/* ---------- 工具 ---------- */
function hashString(s) {
  let h = 2166136261
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return (h >>> 0).toString(16).padStart(8, '0')
}
