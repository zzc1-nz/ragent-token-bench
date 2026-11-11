#!/usr/bin/env node
/**
 * 修复 pnpm 安装遗留的依赖布局问题。
 *
 * 现象：本环境下 `pnpm install` 完成后，node_modules 顶层只有 14 个包，
 * 而虚拟仓 node_modules/.pnpm 里有 157 个 —— 也就是说 pnpm 的 hoisting
 * 没有把传递依赖提升到顶层，导致 `require('terser')` 找不到
 * `@jridgewell/source-map` 这类间接依赖（ERR_MODULE_NOT_FOUND）。
 *
 * 处理：把虚拟仓里「顶层缺失」的包，用 junction 补到 node_modules/ 顶层。
 * junction 指向虚拟仓里的真实目录，因此不会重复占空间，也不破坏 pnpm 布局。
 *
 * 用法：node scripts/fix-node-modules.mjs
 */
import { existsSync, lstatSync, mkdirSync, readdirSync, readlinkSync, symlinkSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(__dirname, '..')
const NM = join(ROOT, 'node_modules')
const VSTORE = join(NM, '.pnpm')

/** 枚举虚拟仓里所有可用的包：name -> 真实目录 */
function collectAvailable() {
  const map = new Map()
  if (!existsSync(VSTORE)) return map
  for (const entry of readdirSync(VSTORE)) {
    const nm = join(VSTORE, entry, 'node_modules')
    if (!existsSync(nm)) continue
    for (const name of readdirSync(nm)) {
      if (name.startsWith('.')) continue
      if (name.startsWith('@')) {
        const scopeDir = join(nm, name)
        let subs = []
        try {
          subs = readdirSync(scopeDir)
        } catch {
          continue
        }
        for (const sub of subs) {
          const key = `${name}/${sub}`
          if (!map.has(key)) map.set(key, join(scopeDir, sub))
        }
      } else {
        if (!map.has(name)) map.set(name, join(nm, name))
      }
    }
  }
  return map
}

/** 顶层是否已能解析到该包 */
function existsAtTop(name) {
  const p = join(NM, name)
  try {
    const st = lstatSync(p)
    if (st.isSymbolicLink()) {
      // 断链也算缺失
      return existsSync(resolve(join(NM, readlinkSync(p))))
    }
    return true
  } catch {
    return false
  }
}

const available = collectAvailable()
if (available.size === 0) {
  console.error('✗ 找不到 node_modules/.pnpm 虚拟仓，请先运行 pnpm install')
  process.exit(1)
}

let linked = 0
let skipped = 0
const failed = []

for (const [name, target] of available) {
  if (existsAtTop(name)) {
    skipped++
    continue
  }
  try {
    mkdirSync(dirname(join(NM, name)), { recursive: true })
    // Windows 用 junction（无需管理员权限），其它平台用 dir 符号链接
    symlinkSync(target, join(NM, name), process.platform === 'win32' ? 'junction' : 'dir')
    linked++
  } catch (e) {
    // EEXIST：目标已存在（pnpm 自己建过的链接），属于正常情况
    if (e.code === 'EEXIST') {
      skipped++
      continue
    }
    failed.push(`${name}: ${e.code || e.message}`)
  }
}

console.log(`✓ 依赖布局修复完成`)
console.log(`  虚拟仓可用包: ${available.size}`)
console.log(`  已补链: ${linked}`)
console.log(`  已存在跳过: ${skipped}`)
if (failed.length) {
  console.log(`  失败: ${failed.length}`)
  for (const f of failed.slice(0, 10)) console.log(`    - ${f}`)
  process.exit(1)
}

/* ---------- 自检：关键包能否 require ---------- */
const { createRequire } = await import('node:module')
const req = createRequire(join(ROOT, 'package.json'))
const critical = ['react', 'react-dom', 'echarts', 'rollup', 'terser', 'typescript', 'tailwindcss']
let bad = 0
console.log('\n  关键包自检:')
for (const pkg of critical) {
  try {
    req.resolve(pkg)
    console.log(`    ✓ ${pkg}`)
  } catch (e) {
    console.log(`    ✗ ${pkg} — ${e.code || e.message}`)
    bad++
  }
}

process.exit(bad === 0 ? 0 : 1)
