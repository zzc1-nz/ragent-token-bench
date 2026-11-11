/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // 暗色专业风：以 Grafana / Vercel Analytics 的中性灰阶为骨架
        ink: {
          950: '#08090c',
          900: '#0b0d12',
          850: '#0f1218',
          800: '#141821',
          750: '#1a1f2b',
          700: '#232936',
          600: '#333c4e',
          500: '#4b5568',
          400: '#6b7688',
          300: '#98a2b3',
          200: '#c3cad6',
          100: '#e6eaf2',
        },
        accent: {
          DEFAULT: '#5b8cff',
          soft: '#7ea6ff',
          dim: '#2f4c8f',
        },
        mint: '#3ddc97',
        amber: '#f7b955',
        rose: '#ff6b81',
        violet: '#a78bfa',
        cyan: '#22d3ee',
      },
      fontFamily: {
        sans: [
          'Inter',
          '-apple-system',
          'BlinkMacSystemFont',
          'Segoe UI',
          'PingFang SC',
          'Hiragino Sans GB',
          'Microsoft YaHei',
          'Noto Sans SC',
          'sans-serif',
        ],
        mono: ['JetBrains Mono', 'SFMono-Regular', 'Consolas', 'Menlo', 'monospace'],
      },
      boxShadow: {
        panel: '0 1px 0 0 rgba(255,255,255,0.04) inset, 0 8px 24px -12px rgba(0,0,0,0.8)',
      },
      keyframes: {
        shimmer: {
          '0%': { backgroundPosition: '-200% 0' },
          '100%': { backgroundPosition: '200% 0' },
        },
      },
      animation: {
        shimmer: 'shimmer 1.6s linear infinite',
      },
    },
  },
  plugins: [],
}
