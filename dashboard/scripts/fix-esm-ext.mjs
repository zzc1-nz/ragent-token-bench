/**
 * 给 tsc 产物（.tsbuild/）的相对 import 补上 .js 扩展名。
 *
 * 背景：tsc 输出的 ESM 里 `import { x } from '../types'` 没有扩展名。
 * Rollup 能解析，但 Node 原生 ESM 解析器不行（ERR_MODULE_NOT_FOUND）。
 * logic-check.mjs 需要在 Node 里直接 import 这些产物，故先补全。
 *
 * 只改 .tsbuild/ 下的构建中间产物，不碰源码。
 *
 * 既可作为模块调用（export fixEsmExtensions），也可直接执行。
 */
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/** 修正单个文件的相对 import 扩展名，返回是否改动 */
function fixFile(file) {
  const src = readFileSync(file, 'utf8')

  const addExt = (spec) => {
    if (/\.(js|json|mjs|cjs)$/.test(spec)) return spec
    const abs = resolve(dirname(file), spec)
    if (existsSync(abs + '.js')) return spec + '.js'
    if (existsSync(join(abs, 'index.js'))) return spec + '/index.js'
    return spec
  }

  // `from './x'`
  let out = src.replace(/(from\s+['"])(\.\.?\/[^'"]+?)(['"])/g, (_m, p, spec, q) => p + addExt(spec) + q)
  // 副作用导入 `import './x'`
  out = out.replace(/(import\s+['"])(\.\.?\/[^'"]+?)(['"])/g, (_m, p, spec, q) => p + addExt(spec) + q)

  if (out !== src) {
    writeFileSync(file, out, 'utf8')
    return true
  }
  return false
}

/** 递归修正目录下所有 .js 产物，返回改动文件数 */
export function fixEsmExtensions(dir) {
  let patched = 0
  const walk = (d) => {
    for (const name of readdirSync(d)) {
      const p = join(d, name)
      const st = statSync(p)
      if (st.isDirectory()) walk(p)
      else if (name.endsWith('.js') && fixFile(p)) patched++
    }
  }
  if (existsSync(dir)) walk(dir)
  return patched
}

/* 直接执行时作为 CLI 使用 */
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
  const TSBUILD = resolve(ROOT, '.tsbuild')
  if (!existsSync(TSBUILD)) {
    console.error('✗ .tsbuild/ 不存在，请先运行 node scripts/build.mjs')
    process.exit(1)
  }
  const n = fixEsmExtensions(TSBUILD)
  console.log(`✓ 已为 ${n} 个产物文件补全 import 扩展名`)
}
