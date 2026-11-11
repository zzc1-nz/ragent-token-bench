#!/usr/bin/env node
/**
 * 全套校验：构建 → 数据/产物 → 渲染逻辑 → 降级路径。
 *
 * 用法：node scripts/check.mjs
 * 任何一步失败即退出非 0。
 */
import { spawnSync } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ROOT = resolve(__dirname, '..')

/**
 * 注意：本沙箱禁止「管道 stdio 的子进程」，因此这里用 stdio:'inherit'
 * 直接继承当前终端的 stdio（这是被允许的模式）。
 */
function run(label, script, args = []) {
  console.log(`\n${'='.repeat(64)}`)
  console.log(`▶ ${label}`)
  console.log('='.repeat(64))
  const r = spawnSync(process.execPath, [resolve(__dirname, script), ...args], {
    cwd: ROOT,
    stdio: 'inherit',
  })
  if (r.status !== 0) {
    console.error(`\n✗ ${label} 失败（exit ${r.status}）`)
    process.exit(r.status ?? 1)
  }
  return true
}

run('构建', 'build.mjs')
run('数据与产物校验', 'smoke.mjs', ['--dist'])
run('渲染逻辑校验', 'logic-check.mjs')
run('降级路径校验', 'fallback-check.mjs')
run('浏览器渲染校验（沙箱受限时自动跳过）', 'render-check.mjs')

console.log(`\n${'='.repeat(64)}`)
console.log('\x1b[32m✓ 全套校验通过\x1b[0m')
console.log('='.repeat(64))
