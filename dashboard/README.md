# TokenBench 仪表盘

「Token 消耗可观测的 Agentic RAG 基准测试平台」的前端仪表盘。
把 Java 基准运行器产出的 `summary.json` 变成一套可直接用于简历项目的可视化界面。

> 数据结构唯一事实来源：`token-bench/CONTRACT.md` §6.4

---

## 技术栈

| 层 | 选型 | 版本 |
|---|---|---|
| 框架 | React | 18.3.1 |
| 语言 | TypeScript | 5.6.3 |
| 构建 | Vite（配置）/ Rollup（产物） | 5.4.11 / 4.x |
| 图表 | ECharts（按需引入） | 5.5.1 |
| 样式 | Tailwind CSS | 3.4.17 |

---

## 快速开始

```powershell
# Node（本工作区内置）
$env:PATH = "C:\Users\24657\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\node\bin;" + $env:PATH
$pnpm = "C:\Users\24657\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\pnpm\bin\pnpm.mjs"

cd token-bench\dashboard
node $pnpm install          # 安装依赖
node scripts/fix-node-modules.mjs   # 修复 pnpm 未 hoist 的传递依赖（本环境必需）
node scripts/build.mjs      # 生产构建 → dist/
node scripts/check.mjs      # 全套校验（数据 + 产物 + 逻辑 + 降级）
```

构建产物为纯静态站点，`dist/` 可直接部署到任意静态服务器。
**不需要**常驻 dev server。

### 验证脚本

| 命令 | 校验内容 |
|---|---|
| `node scripts/build.mjs` | 构建 + 产物完整性 + 编码 + 未解析常量 |
| `node scripts/smoke.mjs --dist` | summary.json 结构/自洽性 + dist 产物 |
| `node scripts/logic-check.mjs` | 在 Node 中执行真实归一化与图表数据构造 |
| `node scripts/fallback-check.mjs` | 数据缺失时的降级路径 |
| `node scripts/render-check.mjs` | 无头浏览器渲染（沙箱限制时会自动跳过） |

---

## 数据接入

### 主路径：静态 JSON

```
public/data/summary.json   ← 由 Java 运行器的 data/results/<runId>/summary.json 拷贝而来
```

构建时会被原样拷贝到 `dist/data/summary.json`，运行时 `fetch` 相对路径加载。

### 兜底路径：REST 端点

静态文件不存在时，自动尝试 `http://localhost:8080/api/bench/summary`（模块 B 的端点）。

### 数据缺失的处理

页面**不会白屏或抛异常**。`src/utils/normalize.ts` 是唯一的数据入口：

- 每个字段都有安全默认值，缺失的派生字段（`totalTokens` / `qualityScorePct` /
  `tokensPerTurn` / `cacheHitRate`）会从已有字段推算；
- 推算不出来的问题收集进 `warnings[]`，页面顶部以「数据完整性提示」横幅展示；
- 受影响的单个图表显示空状态，其余视图正常工作；
- `timeline[]` 缺失 `cumulativeTokens` 或乱序时，会按 `t` 排序后重新累加。

### 示例数据

`public/data/summary.json` 当前是**真实运行数据**（由 Java 运行器产出，
`RUN_20261110_220148`，6 组实验共 15,604,356 tokens）。

`public/data/summary.example.json` 是保留的**示例数据**
（`environment.example === true`，由 `scripts/gen-sample-data.mjs` 按契约 §6.4 生成），
用于在真实数据缺失时演示界面，页头会显示「示例数据」徽章。

```powershell
node scripts/gen-sample-data.mjs                              # 生成到 summary.example.json
node scripts/gen-sample-data.mjs --out public/data/summary.json  # 覆盖为主数据
```

示例数据的关键性质（由脚本自检断言）：

- 6 个实验：`A_BASELINE` / `B_SEMANTIC` / `C_FUSION` / `D_ROUTED` / `E_FULL` / `E_FULL_CACHE`
- 每个实验 `Σ stages[].(input+output) === totalTokens`
- `timeline` 末值 `=== totalTokens`，单调不减且 `t` 严格递增
- 成本按 `CONTRACT §4.5` 定价表计算，缓存命中部分按 0.1x 计价
- `E_FULL` 比 `A_BASELINE` 省 38.7% token，质量 +6.6%
- `E_FULL_CACHE` 缓存命中率 43.7%，成本再降 28.8%（token 总量不变）

### 关于 timeline 的语义

真实运行器产出的是**采样**时间轴（约 200 点，`t` 为全局事件序号、可稀疏到数千），
权威字段只有 `cumulativeTokens`，`tokens` 常为 0。
`normalize.ts` 的处理：

- 信任 `cumulativeTokens`，保留原始 `t`（X 轴 = 真实事件序号）
- 单点 token 用相邻 `cumulativeTokens` 差分推导，使「单事件」口径也有意义
- `t` 非严格递增或缺失 `cumulativeTokens` 时，退回按序号排序并自行累加

---

## 视图清单

| # | 视图 | 组件 | 说明 |
|---|---|---|---|
| 1 | **Token 消耗时间轴** | `TokenTimeline` | 面积折线，多实验叠加；可按环节过滤、切换「累计 / 单事件」口径、点图例隐藏曲线 |
| 2 | **各环节 Token 占比** | `StageBreakdown` | 环形图 + 明细表；可切实验、切「Token / 成本 / 耗时」口径 |
| 3 | **成本-效果散点图** | `CostEffectScatter` | X=总 token（或成本），Y=质量分，气泡=成本，标注 experimentId，含帕累托前沿连线与均值参考线 |
| 4 | **实验对比表** | `ComparisonTable` | 实验/策略组合/总token/每轮token/成本/质量分/缓存命中率/延迟，可排序，含相对基线变化 |
| 5 | **KPI 卡片** | `KpiCards` | 总 token、总成本、平均质量分、最佳缓存命中率 + 事件数、最低单轮耗时（带 sparkline） |
| 6 | **A/B 对比结论** | `DeltaCards` | 直接消费 `deltas[]`，主结论按契约 §8 的强制句式生成 |
| 7 | **缓存命中率分析** | `CacheAnalysis` | 命中率横向柱状图 + 缓存收益量化（有/无缓存同策略对比） |
| 8 | **模型 Token 分布** | `App.ModelBreakdown` | 按 `tokensByModel` 聚合 |

顶部三个页签：**总览** / **分环节** / **明细数据**。

---

## 目录结构

```
dashboard/
├── public/data/summary.json      # 数据（示例，待真实数据覆盖）
├── scripts/
│   ├── gen-sample-data.mjs       # 示例数据生成 + 自洽性自检
│   ├── build.mjs                 # 生产构建（tsc → tailwind → rollup → terser）
│   ├── resolve-plugin.mjs        # 极简 Node 模块解析插件
│   └── smoke.mjs                 # 数据 / 产物校验
├── src/
│   ├── charts/echarts.ts         # ECharts 按需注册 + 暗色主题
│   ├── components/               # 视图组件
│   ├── hooks/useSummaryData.ts   # 数据加载（静态 → REST → 空状态）
│   ├── utils/normalize.ts        # 残缺数据归一化（唯一的容错入口）
│   ├── utils/format.ts           # 数字/金额/百分比/耗时格式化
│   ├── utils/palette.ts          # 实验配色（与 ID 绑定，不随筛选跳变）
│   ├── types.ts                  # 契约 §6.4 类型 + 视图模型
│   ├── App.tsx
│   └── main.tsx
├── index.html
└── dist/                         # 构建产物
```

---

## 关于构建脚本

本沙箱环境**禁止「管道 stdio 的子进程」**（`spawn EPERM`），而 `vite build` 在多个环节
硬依赖 esbuild 的原生服务进程（配置加载、TS/JSX 转译、`define` 常量替换），
Windows 路径解析还会 `exec("net use")`；SWC 原生插件也需要沙箱不授予的 ACL 缓存目录。

因此 `scripts/build.mjs` 把构建拆成三个**纯 JS** 阶段：

1. **`tsc`** —— TypeScript/JSX → 纯 ESM JavaScript
2. **Tailwind CLI** —— 扫描源码生成并压缩 CSS
3. **Rollup + Terser** —— 打包与压缩

产物结构与标准 Vite 构建一致（`dist/index.html` + `dist/assets/*` + `dist/data/summary.json`），
业务代码与产物语义完全不变。

`vite.config.ts` 仍然保留，用于在有完整权限的环境里直接 `npx vite build`。

---

## 设计约定

- **暗色专业风**：中性灰阶骨架 + 蓝/绿/琥珀/紫四个强调色，参考 Grafana / Vercel Analytics
- **配色与实验 ID 绑定**（`utils/palette.ts`），筛选后颜色不跳变；基线固定灰色
- **中文界面**，1280px 以上为主布局，移动端纵向堆叠不崩
- **数字等宽**（`tabular-nums`），表格列对齐
- **数据驱动**：所有数值来自 `summary.json`，无任何硬编码示例值
