# TokenBench 基准数据集

本目录是「Token 消耗可观测的 Agentic RAG 基准测试平台」的**评测数据集**，
被 `bench` 模块（`BenchmarkMain`）消费，用于驱动真实的检索召回、答案生成与质量评分。

数据集严格遵循 [`../../CONTRACT.md`](../../CONTRACT.md) **§6.3 数据集格式**；
`referenceDocIds` / `referenceAnswer` 的质量直接决定 **§9 质量评分**
（recall@k 权重 0.4、answerCoverage 0.4、citationPrecision 0.2）的可信度。

---

## 1. 文件清单

| 文件 | 行数 | 说明 |
|---|---|---|
| `corpus/docs.jsonl` | 200 | 知识库文档，5 领域 × 40 篇 |
| `corpus/queries.jsonl` | 500 | 多轮对话评测集，250 会话 × 2 轮 |
| `scripts/gen_corpus.py` | — | 生成脚本（固定 seed，可重复运行） |
| `scripts/validate_corpus.py` | — | 校验脚本（33 项检查，全部必须通过） |

## 2. 数据格式

### 2.1 `docs.jsonl`（每行一篇）

```json
{"docId":"DOC_0001","title":"R5 扫地机器人核心参数与选型说明","domain":"vacuum","text":"# ...","chunksHint":4}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `docId` | string | `DOC_0001` ~ `DOC_0200`，零填充 4 位，全局唯一且连续 |
| `title` | string | 文档标题，同领域内唯一 |
| `domain` | string | `vacuum` / `finance` / `hr` / `tech` / `policy` |
| `text` | string | Markdown 正文，1200~3000 中文字符 |
| `chunksHint` | int | 按 `FIXED_SIZE(chunkSize=500, overlap=50)` 估算：`ceil(len/450)` |

### 2.2 `queries.jsonl`（每行一轮）

```json
{"conversationId":"CONV_0001","turnIndex":0,"question":"X10 的吸力和电池容量分别是多少？",
 "referenceDocIds":["DOC_0001"],"referenceAnswer":"X10 的标称吸力为 8000Pa……","intentHint":"kb"}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `conversationId` | string | `CONV_0001` ~ `CONV_0250` |
| `turnIndex` | int | `0` = 首轮直接提问，`1` = 指代式追问 |
| `question` | string | 用户问题 |
| `referenceDocIds` | string[] | 正确召回目标，1~3 个，全部真实存在于 `docs.jsonl` |
| `referenceAnswer` | string | 参考答案，80~200 字，含关键事实（数字/时限/错误码/型号） |
| `intentHint` | string | `kb` / `chitchat` / `system` |

---

## 3. 领域分布

| 领域 | 篇数 | 平均长度 | 覆盖内容 |
|---|---|---|---|
| `vacuum` | 40 | 1844.8 字符 | 扫地机器人 R5/R5 Pro/R7/R7 Pro/X10/X10 Ultra：吸力参数、电池续航、耗材更换周期、App 配网步骤、报错码 E01~E14、保修政策、退换货流程 |
| `finance` | 40 | 2639.1 字符 | 费用报销制度、发票类型与要求、差旅标准（按职级 × 城市分级）、付款周期、退款政策、预算审批额度 |
| `hr` | 40 | 2409.6 字符 | 考勤与弹性工作制、年假/病假/婚假/产假/陪产假、绩效考核周期与等级、晋升条件、入离职流程、培训学时 |
| `tech` | 40 | 2830.3 字符 | API 认证与限流、错误码、部署架构、数据库规范、监控告警、故障排查手册、版本发布流程 |
| `policy` | 40 | 2487.8 字符 | 个人信息保护、信息安全四级分级、账号权限、审计要求、外部合作合规、违规处罚标准 |

## 4. 数据统计

```
文档总数        : 200
文档平均长度    : 2442.3 字符
文档长度区间    : 1637 ~ 2985
文档总字符数    : 488,468（约 48.8 万字）
含表格文档      : 70 篇（35.0%）
平均 chunksHint : 5.9  → 全库约 1,180 个块（按 500/50 固定分块估算）

对话总轮数      : 500（250 会话 × 2 轮）
kb              : 426 轮（85.2%）
chitchat        :  50 轮（10.0%）
system          :  24 轮（ 4.8%）
参考答案长度    : 80 ~ 119 字符（平均 95.6）
```

**引用覆盖**：200 篇文档全部被至少 1 轮对话引用，无孤立文档；
被引用最多的文档为 4 次，分布均衡，不存在引用过度集中。

## 5. 生成方式（可复现）

固定 seed `20261110`，脚本内所有随机性都来自该 seed，
**重复运行产出逐字节完全相同的文件**（已用 SHA-256 验证）。

```powershell
$py = "C:\Users\24657\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"

# 生成（覆盖 corpus/ 下两个 jsonl）
& $py scripts/gen_corpus.py

# 只统计不落盘
& $py scripts/gen_corpus.py --check

# 校验（33 项检查，退出码 0 = 全部通过）
& $py scripts/validate_corpus.py
```

当前产物指纹（seed = `20261110`）：

```
docs.jsonl     sha256 = 918035D4D4ED15DCDB0E1868781416B81A92446629E0BDC8B92E4EFEAB62A445
queries.jsonl  sha256 = 69D1D343EE8A7880E2E6F2ECF808772EAACB62DE9A6F10F6F8E8CF2A8038CC77
```

> 注意：若修改了脚本中的任何模板或词表，指纹会变化，属预期行为；
> 未修改脚本时重复运行必须得到相同指纹（已实测两次运行哈希一致）。

### 5.1 生成逻辑要点

1. **事实库驱动**：每个领域维护结构化事实表（型号参数、金额、天数、时限、错误码、步骤），
   文档正文由事实表 + 段落模板组合，保证每篇的数值、型号、编号都不同且自洽。
2. **QA 种子同步产出**：生成正文时同步产出该章节的问答种子（问题 / 参考答案 / 指代式追问），
   `queries.jsonl` 直接由这些种子构造 —— 这是 `referenceAnswer` 与文档事实严格对齐的根本保证。
3. **多轮设计**：第 1 轮为直接提问，第 2 轮为指代式追问
   （「那它保修多久」「这个多少钱」「第三步要注意什么」），
   必须结合第 1 轮语境才能理解，专门用于考多轮记忆能力。
4. **措辞变体**：同一问答模板多次出现时，按前缀/后缀轮转生成不同问法，
   避免评测集出现大量重复问题。

## 6. 校验

`scripts/validate_corpus.py` 共 **33 项**检查，覆盖：

- 结构完整性：行数、docId 唯一与连续、字段完整、conversationId 连续
- 格式合规：正文长度 1200~3000、至少一个 `## ` 标题、至少一个列表、约 1/3 含表格
- 引用对齐：所有 `referenceDocIds` 均存在于 `docs.jsonl`，kb 轮次必须有引用
- 多轮语义：每会话恰好 2 轮、turnIndex 为 0/1、两轮问题与答案均不重复、
  kb 第二轮必须含指代词
- 分布约束：5 领域各 40 篇、intentHint 约 85/10/5
- 去重：正文无完全重复、同领域标题无重复

运行结果（当前产物）：**33 / 33 项通过 ✓**

## 7. 与契约的对应关系

| 契约条款 | 本数据集如何满足 |
|---|---|
| §6.3 数据集格式 | 字段名、类型、取值完全一致（camelCase） |
| §6.3 200 篇 / 1200~3000 字 / 5 领域 | 精确满足，实测 1637~2985 字符 |
| §6.3 250 会话 × 2 轮 | 精确满足，500 行 |
| §6.3 `referenceDocIds` 必须真实存在 | 校验项保证，悬空引用 0 个 |
| §9 `recall@k` 依赖 `referenceDocIds` | 每轮指向 1~3 篇真实文档，且为问答事实的实际来源 |
| §9 `answerCoverage` 依赖 `referenceAnswer` | 参考答案含具体数字/时限/错误码，可直接做 n-gram 覆盖计算 |
| §9 `citationPrecision` | `referenceDocIds` 与参考答案事实一一对应，无凑数引用 |

## 8. 已知边界

- `chitchat` / `system` 两类按契约使用空 `referenceDocIds` 数组，
  因此它们的 recall@k 与 citationPrecision 不参与有效评分（分母为 0），
  在质量聚合时应跳过或单独统计。
- 部分 `chitchat` 第 2 轮会引出一个真实的 kb 型问题（如「对了，我下周要出差，报销上有啥要注意的吗？」），
  按契约仍标记为 `chitchat` 且引用为空；若下游希望按实际语义评分，
  可自行按 `turnIndex=1` 且 `intentHint=chitchat` 的规则重分类。
- 文档中的公司名、纳税人识别号、电话、`KB-*` 文档编号为虚构标识，
  但业务规则、参数结构、错误码体系、流程步骤均为真实可读的企业制度写法。
