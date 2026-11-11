import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 仪表盘为纯静态产物：summary.json 放在 public/data/ 下，
// 构建时原样拷贝到 dist/data/summary.json，运行时 fetch 相对路径即可。
//
// 沙箱说明：esbuild 原生二进制通过管道 stdio 启动子进程，在受限环境下会报 spawn EPERM。
// 若构建时遇到该错误，请用 scripts/build_dashboard.ps1（会以更大权限重试）。
export default defineConfig({
  plugins: [react()],
  base: './',
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    sourcemap: false,
    chunkSizeWarningLimit: 1200,
    rollupOptions: {
      output: {
        manualChunks: {
          echarts: ['echarts/core', 'echarts/charts', 'echarts/components', 'echarts/renderers'],
          react: ['react', 'react-dom'],
        },
      },
    },
  },
})
