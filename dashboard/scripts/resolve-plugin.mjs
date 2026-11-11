/**
 * 极简 Node 模块解析插件（纯 JS，替代 @rollup/plugin-node-resolve）。
 *
 * 为什么自己写：
 *   pnpm 的 hoisted 安装在本环境下缺少若干传递依赖（deepmerge / is-module /
 *   @rollup/pluginutils ...），而每装一个都要等 registry。这个解析器只覆盖
 *   本项目实际需要的场景：
 *     - 裸包名          react / echarts/core / zrender/lib/core/util
 *     - 包内子路径      按 package.json 的 module/main/browser 字段定位
 *     - 无扩展名补全    .js / .mjs / .json
 *     - 目录 index      按 package.json 或 index.js
 *   其它情况（相对路径、绝对路径、虚拟 id）交回 Rollup 默认解析。
 */
import { existsSync, readFileSync, statSync } from 'node:fs'
import { dirname, isAbsolute, join, resolve as resolvePath } from 'node:path'

const EXTENSIONS = ['.js', '.mjs', '.cjs', '.json']
const isFile = (p) => {
  try {
    return statSync(p).isFile()
  } catch {
    return false
  }
}
const isDir = (p) => {
  try {
    return statSync(p).isDirectory()
  } catch {
    return false
  }
}

/** 在给定路径上尝试补全扩展名 / 目录 index */
function resolveAsFile(p) {
  if (isFile(p)) return p
  for (const ext of EXTENSIONS) {
    if (isFile(p + ext)) return p + ext
  }
  if (isDir(p)) {
    const pkg = join(p, 'package.json')
    if (isFile(pkg)) {
      const entry = pickEntry(JSON.parse(readFileSync(pkg, 'utf8')))
      if (entry) {
        const r = resolveAsFile(join(p, entry))
        if (r) return r
      }
    }
    for (const ext of EXTENSIONS) {
      const idx = join(p, 'index' + ext)
      if (isFile(idx)) return idx
    }
  }
  return null
}

/** 从 package.json 里挑一个入口字段 */
function pickEntry(pkg) {
  for (const field of ['module', 'browser', 'main', 'exports']) {
    const v = pkg[field]
    if (typeof v === 'string') return v
    if (field === 'exports' && v && typeof v === 'object') {
      // 处理 exports: { ".": { "import": "./x.js", "default": "./x.js" } }
      const dot = v['.'] ?? v
      if (typeof dot === 'string') return dot
      if (dot && typeof dot === 'object') {
        for (const cond of ['import', 'browser', 'module', 'default', 'require']) {
          if (typeof dot[cond] === 'string') return dot[cond]
        }
      }
    }
  }
  return null
}

/** 从 startDir 向上找 node_modules/<name> */
function findPackageDir(name, startDir) {
  let dir = startDir
  for (;;) {
    const candidate = join(dir, 'node_modules', name)
    if (isDir(candidate)) return candidate
    const parent = dirname(dir)
    if (parent === dir) return null
    dir = parent
  }
}

/** 解析裸模块名（含子路径） */
function resolveBare(specifier, importerDir) {
  // 拆出包名与子路径（支持 @scope/name）
  const parts = specifier.split('/')
  const pkgName = specifier.startsWith('@') ? parts.slice(0, 2).join('/') : parts[0]
  const subPath = specifier.slice(pkgName.length).replace(/^\//, '')

  const pkgDir = findPackageDir(pkgName, importerDir)
  if (!pkgDir) return null

  if (subPath) {
    const direct = resolveAsFile(join(pkgDir, subPath))
    if (direct) return direct
    return null
  }

  // 无子路径：用 package.json 入口
  const pkgJson = join(pkgDir, 'package.json')
  if (isFile(pkgJson)) {
    const entry = pickEntry(JSON.parse(readFileSync(pkgJson, 'utf8')))
    if (entry) {
      const r = resolveAsFile(join(pkgDir, entry))
      if (r) return r
    }
  }
  return resolveAsFile(join(pkgDir, 'index'))
}

/** 解析相对 / 绝对路径 */
function resolveRelative(specifier, importerDir) {
  const base = isAbsolute(specifier) ? specifier : resolvePath(importerDir, specifier)
  return resolveAsFile(base)
}

export default function nodeResolveLite() {
  return {
    name: 'node-resolve-lite',
    resolveId(source, importer) {
      // 虚拟模块与 Rollup 内部 id 不处理
      if (source.startsWith('\0')) return null
      // 交给 Rollup 处理的特殊协议
      if (/^(node:|data:|http:)/.test(source)) return null
      // 绝对路径与相对路径
      if (isAbsolute(source)) {
        const r = resolveAsFile(source)
        return r ?? null
      }
      if (source.startsWith('.')) {
        const importerDir = importer ? dirname(importer) : process.cwd()
        const r = resolveRelative(source, importerDir)
        return r ?? null
      }
      // 裸模块名
      const importerDir = importer ? dirname(importer) : process.cwd()
      const r = resolveBare(source, importerDir)
      return r ?? null
    },
  }
}

export { existsSync }
