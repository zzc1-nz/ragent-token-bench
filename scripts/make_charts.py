"""
生成 README 所需的图表（用 PIL 手绘，不依赖 matplotlib —— 环境网络不稳，matplotlib 装不上）。

产出：
  charts/token-curve.png     —— Token 消耗时间轴（6 组策略叠加）
  charts/cost-effect.png     —— 成本-效果散点图（含帕累托前沿）
  charts/stage-breakdown.png —— 各环节 Token 占比（E_FULL）

数据源：data/results/<最新 run>/summary.json（bench 模块产出，非手写）
"""

import json
import os
import sys

from PIL import Image, ImageDraw, ImageFont

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = os.path.join(BASE, "data", "results")
CHARTS = os.path.join(BASE, "charts")

# ---------- 暗色主题 ----------
BG = (15, 20, 32)
PANEL = (22, 28, 43)
FG = (230, 237, 247)
MUTED = (150, 163, 184)
GRID = (42, 52, 70)

COLORS = {
    "A_BASELINE": (255, 107, 107),
    "B_SEMANTIC": (77, 171, 247),
    "C_FUSION": (255, 212, 59),
    "D_ROUTED": (167, 139, 250),
    "E_FULL": (61, 220, 151),
    "E_FULL_CACHE": (34, 211, 238),
}

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
    r"C:\Windows\Fonts\simsun.ttc",
]


def load_font(size, bold=False):
    """加载中文字体；优先雅黑，失败则退回默认（中文会显示为方块但不报错）"""
    candidates = ([r"C:\Windows\Fonts\msyhbd.ttc"] if bold else []) + FONT_CANDIDATES
    for path in candidates:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    print("警告：未找到中文字体", file=sys.stderr)
    return ImageFont.load_default()


def load_latest():
    runs = sorted(d for d in os.listdir(RESULTS) if os.path.isdir(os.path.join(RESULTS, d)))
    if not runs:
        raise SystemExit("没有找到运行结果，请先跑 bench")
    run = runs[-1]
    with open(os.path.join(RESULTS, run, "summary.json"), encoding="utf-8") as f:
        return run, json.load(f)


def nice_ceil(v):
    """把坐标轴上限取整到好看的刻度"""
    if v <= 0:
        return 1
    exp = 10 ** int(f"{v:e}".split("e")[1])
    for m in (1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10):
        if m * exp >= v:
            return m * exp
    return 10 * exp


def fmt_tokens(v):
    if v >= 1e6:
        return f"{v/1e6:.1f}M"
    if v >= 1e3:
        return f"{v/1e3:.0f}K"
    return f"{v:.0f}"


# ==================== 图 1：Token 消耗时间轴 ====================

def chart_token_curve(data, out):
    W, H = 1760, 860
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)

    f_title = load_font(34, bold=True)
    f_label = load_font(22)
    f_tick = load_font(19)
    f_legend = load_font(20)

    left, right, top, bottom = 130, W - 320, 110, H - 90
    pw, ph = right - left, bottom - top

    d.text((left, 44), f"Token 消耗时间轴 · 6 组策略对比", font=f_title, fill=FG)
    d.text((left, 84), f"run: {data['runId']}　每点为一个计量事件，按 ETL → 500 轮对话的时间顺序累加",
           font=load_font(19), fill=MUTED)

    exps = data["experiments"]
    # 注意：timeline 是「按事件序号采样」后的压缩序列，t 是真实事件下标（可达上万），
    # 因此 x 轴上限必须取 t 的最大值，而不是采样点个数 —— 否则曲线会被压到最左边一小段
    max_x = max((p["t"] for e in exps for p in (e.get("timeline") or [])), default=1) or 1
    max_y = max((e.get("timeline") or [{}])[-1].get("cumulativeTokens", 0) for e in exps) or 1
    y_top = nice_ceil(max_y)

    # 网格 + Y 轴刻度
    for i in range(6):
        y = bottom - ph * i / 5
        d.line([(left, y), (right, y)], fill=GRID, width=1)
        d.text((left - 16, y - 11), fmt_tokens(y_top * i / 5), font=f_tick, fill=MUTED, anchor="ra")

    # X 轴刻度
    for i in range(6):
        x = left + pw * i / 5
        d.line([(x, top), (x, bottom)], fill=GRID, width=1)
        d.text((x, bottom + 14), f"{int(max_x * i / 5):,}", font=f_tick, fill=MUTED, anchor="ma")

    d.line([(left, bottom), (right, bottom)], fill=MUTED, width=2)
    d.line([(left, top), (left, bottom)], fill=MUTED, width=2)

    # 曲线
    for exp in exps:
        eid = exp["experimentId"]
        tl = exp.get("timeline") or []
        if len(tl) < 2:
            continue
        color = COLORS.get(eid, (136, 136, 136))
        pts = []
        for p in tl:
            x = left + pw * (p["t"] / max(1, max_x - 1))
            y = bottom - ph * (p["cumulativeTokens"] / y_top)
            pts.append((x, y))
        width = 4 if eid in ("A_BASELINE", "E_FULL") else 3
        d.line(pts, fill=color, width=width, joint="curve")

    # 图例
    lx, ly = right + 28, top + 6
    d.text((lx, ly - 34), "策略（总 Token）", font=load_font(20, bold=True), fill=FG)
    for i, exp in enumerate(exps):
        eid = exp["experimentId"]
        y = ly + i * 40
        color = COLORS.get(eid, (136, 136, 136))
        d.line([(lx, y + 10), (lx + 34, y + 10)], fill=color, width=4)
        d.text((lx + 46, y), eid, font=f_legend, fill=FG)
        d.text((lx + 46, y + 20), f"{exp['totalTokens']:,}", font=load_font(17), fill=MUTED)

    d.text((left + pw / 2, H - 44), "计量事件序号", font=f_label, fill=MUTED, anchor="ma")

    os.makedirs(CHARTS, exist_ok=True)
    img.save(out)
    print(f"已生成 {out}")


# ==================== 图 2：成本-效果散点 ====================

def chart_cost_effect(data, out):
    W, H = 1500, 900
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)

    f_title = load_font(34, bold=True)
    f_label = load_font(22)
    f_tick = load_font(19)
    f_tag = load_font(20, bold=True)

    left, right, top, bottom = 140, W - 90, 120, H - 110
    pw, ph = right - left, bottom - top

    d.text((left, 46), "成本-效果散点图", font=f_title, fill=FG)
    d.text((left, 88), "横轴 = 总 Token 消耗（越低越好）　纵轴 = 质量评分（越高越好）　气泡大小 = 总成本",
           font=load_font(19), fill=MUTED)

    exps = data["experiments"]
    xs = [e["totalTokens"] for e in exps]
    ys = [e["qualityScorePct"] for e in exps]

    x_min, x_max = min(xs) * 0.92, max(xs) * 1.06
    y_min, y_max = min(ys) - 2.5, max(ys) + 2.5

    # 网格
    for i in range(6):
        y = bottom - ph * i / 5
        d.line([(left, y), (right, y)], fill=GRID, width=1)
        val = y_min + (y_max - y_min) * i / 5
        d.text((left - 16, y - 11), f"{val:.0f}", font=f_tick, fill=MUTED, anchor="ra")
        x = left + pw * i / 5
        d.line([(x, top), (x, bottom)], fill=GRID, width=1)
        xv = x_min + (x_max - x_min) * i / 5
        d.text((x, bottom + 14), f"{xv/1e6:.2f}M", font=f_tick, fill=MUTED, anchor="ma")

    d.line([(left, bottom), (right, bottom)], fill=MUTED, width=2)
    d.line([(left, top), (left, bottom)], fill=MUTED, width=2)

    def to_xy(e):
        x = left + pw * (e["totalTokens"] - x_min) / (x_max - x_min)
        y = bottom - ph * (e["qualityScorePct"] - y_min) / (y_max - y_min)
        return x, y

    # 帕累托前沿
    pareto = []
    for e in exps:
        dominated = any(
            o["totalTokens"] <= e["totalTokens"]
            and o["qualityScorePct"] >= e["qualityScorePct"]
            and (o["totalTokens"] < e["totalTokens"] or o["qualityScorePct"] > e["qualityScorePct"])
            for o in exps if o is not e
        )
        if not dominated:
            pareto.append(e)
    pareto.sort(key=lambda e: e["totalTokens"])
    if len(pareto) > 1:
        pts = [to_xy(p) for p in pareto]
        for a, b in zip(pts, pts[1:]):
            d.line([a, b], fill=(34, 211, 238), width=2)
        # 标签放在前沿线段左侧的空白区，避开气泡标签（气泡标签在上下两侧）
        first = pts[0]
        d.text((first[0] - 150, first[1] + 60), "帕累托前沿", font=load_font(18), fill=(34, 211, 238))

    # 气泡
    # 注意：有些实验的 (token, 质量) 完全重合（A_BASELINE/D_ROUTED —— 路由只改计价不改 token；
    # E_FULL/E_FULL_CACHE —— 缓存只改成本不改 token/质量）。直接按坐标画会完全重叠，
    # 因此对重合点做偏移：气泡水平错开，标签垂直错开（一个在上、一个在下），保证都看得见。
    max_cost = max(e["totalCostUsd"] for e in exps) or 1
    placed = {}
    for e in exps:
        eid = e["experimentId"]
        base_x, base_y = to_xy(e)
        key = (round(base_x), round(base_y))
        k = placed.get(key, 0)
        placed[key] = k + 1

        cx = base_x + k * 82          # 气泡水平错开
        cy = base_y
        r = 16 + 30 * (e["totalCostUsd"] / max_cost)
        color = COLORS.get(eid, (136, 136, 136))
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color, outline=FG, width=2)

        # 标签：第 0 个放上方，第 1 个放下方，避免两行文字压在一起
        if k == 0:
            d.text((cx, cy - r - 30), eid, font=f_tag, fill=FG, anchor="ma")
        else:
            d.text((cx, cy + r + 10), eid, font=f_tag, fill=FG, anchor="ma")

        d.text((cx, cy - 10), f"${e['totalCostUsd']:.2f}", font=load_font(17),
               fill=(20, 24, 33), anchor="ma")

    d.text((left + 20, top + 14),
           "注：A_BASELINE/D_ROUTED、E_FULL/E_FULL_CACHE 坐标重合 —— 这两类策略只改计价，不改 token 与质量",
           font=load_font(17), fill=MUTED)

    d.text((left + pw / 2, H - 46), "总 Token 消耗", font=f_label, fill=MUTED, anchor="ma")
    # 纵轴标题竖排
    ylabel = "质量评分"
    tmp = Image.new("RGBA", (200, 40), (0, 0, 0, 0))
    td = ImageDraw.Draw(tmp)
    td.text((0, 0), ylabel, font=f_label, fill=MUTED)
    rotated = tmp.rotate(90, expand=True)
    img.paste(rotated, (40, top + ph // 2 - 90), rotated)

    os.makedirs(CHARTS, exist_ok=True)
    img.save(out)
    print(f"已生成 {out}")


# ==================== 图 3：分环节占比 ====================

def chart_stage_breakdown(data, out):
    exp = next((e for e in data["experiments"] if e["experimentId"] == "E_FULL"), data["experiments"][0])
    stages = [s for s in exp["stages"] if (s["inputTokens"] + s["outputTokens"]) > 0]
    stages.sort(key=lambda s: s["inputTokens"] + s["outputTokens"], reverse=True)

    STAGE_CN = {
        "PARSE": "文档解析", "CHUNK": "分块", "EMBED": "向量化",
        "INTENT": "意图识别", "REWRITE": "问题改写",
        "RETRIEVE_VECTOR": "向量召回", "RETRIEVE_KEYWORD": "关键词召回",
        "RETRIEVE_GRAPH": "图谱召回", "RETRIEVE_WEB": "联网搜索",
        "FUSION": "多路融合", "RERANK": "精排",
        "GENERATE": "答案生成", "JUDGE": "质量评分", "SUMMARY": "会话摘要",
    }

    W, H = 1400, 260 + len(stages) * 74
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)

    f_title = load_font(34, bold=True)
    f_label = load_font(21)
    f_val = load_font(20, bold=True)

    total = sum(s["inputTokens"] + s["outputTokens"] for s in stages)
    d.text((120, 44), f"各环节 Token 占比 · {exp['experimentId']}", font=f_title, fill=FG)
    d.text((120, 86), f"总 {total:,} tokens　（同一段文本只在一个环节计 token，避免重复计数）",
           font=load_font(19), fill=MUTED)

    palette = [(61, 220, 151), (34, 211, 238), (255, 143, 163), (167, 139, 250),
               (192, 132, 252), (91, 140, 255), (56, 189, 248), (247, 185, 85),
               (148, 163, 184), (107, 118, 136)]

    bar_left, bar_w = 340, W - 340 - 190
    top = 160
    max_pct = max((s["inputTokens"] + s["outputTokens"]) / total * 100 for s in stages)

    for i, s in enumerate(stages):
        y = top + i * 74
        tokens = s["inputTokens"] + s["outputTokens"]
        pct = tokens / total * 100
        name = STAGE_CN.get(s["stage"], s["stage"])

        d.text((120, y + 12), name, font=f_label, fill=FG)

        # 轨道
        d.rounded_rectangle([bar_left, y + 6, bar_left + bar_w, y + 44], radius=6, fill=PANEL)
        # 填充
        w = max(4, int(bar_w * pct / max(max_pct, 1)))
        d.rounded_rectangle([bar_left, y + 6, bar_left + w, y + 44], radius=6,
                            fill=palette[i % len(palette)])
        # 数值
        d.text((bar_left + bar_w + 18, y + 14), f"{pct:.1f}%", font=f_val, fill=FG)
        d.text((bar_left + bar_w + 108, y + 16), fmt_tokens(tokens), font=load_font(18), fill=MUTED)

    os.makedirs(CHARTS, exist_ok=True)
    img.save(out)
    print(f"已生成 {out}")


def export_table(data):
    """导出 markdown 表格，README 直接引用，避免手抄数字出错"""
    lines = ["| 策略 | 总 Token | 成本(USD) | 质量分 | 每轮 Token |", "|---|---:|---:|---:|---:|"]
    for e in data["experiments"]:
        lines.append(f"| `{e['experimentId']}` | {e['totalTokens']:,} | "
                     f"{e['totalCostUsd']:.4f} | {e['qualityScorePct']:.1f} | "
                     f"{e['tokensPerTurn']:,.0f} |")
    path = os.path.join(CHARTS, "cost-effect-table.md")
    os.makedirs(CHARTS, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"已生成 {path}")


def main():
    run, data = load_latest()
    print(f"数据源：{run}，共 {len(data['experiments'])} 组实验")
    chart_token_curve(data, os.path.join(CHARTS, "token-curve.png"))
    chart_cost_effect(data, os.path.join(CHARTS, "cost-effect.png"))
    chart_stage_breakdown(data, os.path.join(CHARTS, "stage-breakdown.png"))
    export_table(data)


if __name__ == "__main__":
    main()
