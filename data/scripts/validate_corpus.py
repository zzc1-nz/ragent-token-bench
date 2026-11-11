# -*- coding: utf-8 -*-
"""TokenBench 基准数据集校验脚本。

校验项（全部必须通过）：
  1. docs.jsonl 行数 == 200，docId 唯一且 DOC_0001..DOC_0200 连续
  2. queries.jsonl 行数 == 500，conversationId 数 == 250，每会话恰好 2 轮且 turnIndex 为 0/1
  3. 所有 referenceDocIds 都能在 docs.jsonl 中找到
  4. 每篇正文长度 1200~3000
  5. 5 个领域各 40 篇
  6. 统计输出：平均文档长度、总字符数、领域分布、intentHint 分布

用法：
  python validate_corpus.py            # 校验并打印报告，退出码 0=全部通过
  python validate_corpus.py --json     # 额外输出机器可读的 JSON 摘要
"""

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve()
CORPUS_DIR = HERE.parents[1] / "corpus"
DOCS = CORPUS_DIR / "docs.jsonl"
QUERIES = CORPUS_DIR / "queries.jsonl"

DOMAINS = ["vacuum", "finance", "hr", "tech", "policy"]
INTENTS = ["kb", "chitchat", "system"]
MIN_LEN, MAX_LEN = 1200, 3000

checks = []   # [(名称, 是否通过, 详情)]


def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))
    return ok


def load_jsonl(path):
    rows = []
    with open(path, encoding="utf-8") as fh:
        for i, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as e:
                raise SystemExit("第 %d 行 JSON 解析失败: %s" % (i, e))
    return rows


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true", help="输出机器可读 JSON 摘要")
    args = ap.parse_args(argv)

    print("=" * 78)
    print("TokenBench 数据集校验报告")
    print("=" * 78)
    print("docs   :", DOCS)
    print("queries:", QUERIES)
    print()

    if not DOCS.exists() or not QUERIES.exists():
        print("[FAIL] 语料文件缺失，请先运行 gen_corpus.py")
        return 2

    docs = load_jsonl(DOCS)
    rows = load_jsonl(QUERIES)

    # ---------------- 1. docs 数量与 docId ----------------
    check("docs 行数 == 200", len(docs) == 200, "实际 %d" % len(docs))

    ids = [d.get("docId") for d in docs]
    check("docId 唯一", len(ids) == len(set(ids)),
          "唯一 %d / 总 %d" % (len(set(ids)), len(ids)))
    expect = ["DOC_%04d" % i for i in range(1, 201)]
    check("docId 为 DOC_0001..DOC_0200 连续", ids == expect,
          "首=%s 尾=%s" % (ids[0] if ids else "-", ids[-1] if ids else "-"))

    # 必填字段
    miss = [d.get("docId") for d in docs
            if not all(k in d for k in ("docId", "title", "domain", "text", "chunksHint"))]
    check("docs 字段完整 (docId/title/domain/text/chunksHint)", not miss,
          "缺失 %s" % miss[:5] if miss else "全部完整")

    # ---------------- 2. 正文长度与结构 ----------------
    lens = [len(d["text"]) for d in docs]
    bad_len = [(d["docId"], len(d["text"])) for d in docs
               if not (MIN_LEN <= len(d["text"]) <= MAX_LEN)]
    check("每篇正文长度 %d~%d" % (MIN_LEN, MAX_LEN), not bad_len,
          "越界 %s" % bad_len[:5] if bad_len else
          "min=%d max=%d avg=%.1f" % (min(lens), max(lens), sum(lens) / len(lens)))

    no_h2 = [d["docId"] for d in docs if "## " not in d["text"]]
    check("每篇至少一个 `## ` 二级标题", not no_h2, "缺失 %s" % no_h2[:5] if no_h2 else "全部满足")

    no_list = [d["docId"] for d in docs
               if not ("\n- " in d["text"] or "\n1. " in d["text"])]
    check("每篇至少一个列表", not no_list, "缺失 %s" % no_list[:5] if no_list else "全部满足")

    has_table = [d["docId"] for d in docs if "\n|" in d["text"] and "|---" in d["text"]]
    table_ratio = len(has_table) / len(docs) if docs else 0
    check("约 1/3 文档含 Markdown 表格", 0.2 <= table_ratio <= 0.6,
          "%d 篇 (%.1f%%)" % (len(has_table), table_ratio * 100))

    dup_text = len(docs) - len(set(d["text"] for d in docs))
    check("正文无完全重复", dup_text == 0, "重复 %d 篇" % dup_text)

    dup_title = len(docs) - len(set((d["domain"], d["title"]) for d in docs))
    check("同领域标题无重复", dup_title == 0, "重复 %d 篇" % dup_title)

    # ---------------- 3. 领域分布 ----------------
    dist = Counter(d["domain"] for d in docs)
    check("5 个领域各 40 篇", all(dist.get(x, 0) == 40 for x in DOMAINS),
          ", ".join("%s=%d" % (x, dist.get(x, 0)) for x in DOMAINS))

    unknown = set(dist) - set(DOMAINS)
    check("领域取值合法", not unknown, "非法领域 %s" % sorted(unknown) if unknown else "仅 5 个合法领域")

    # ---------------- 4. queries 数量与轮次 ----------------
    check("queries 行数 == 500", len(rows) == 500, "实际 %d" % len(rows))

    for k in ("conversationId", "turnIndex", "question", "referenceDocIds",
              "referenceAnswer", "intentHint"):
        miss = [i for i, r in enumerate(rows) if k not in r]
        check("queries 含字段 %s" % k, not miss,
              "缺失行 %s" % miss[:5] if miss else "全部含该字段")

    convs = defaultdict(list)
    for r in rows:
        convs[r["conversationId"]].append(r)
    check("conversationId 数 == 250", len(convs) == 250, "实际 %d" % len(convs))

    bad_turns = []
    for cid, ts in convs.items():
        idx = sorted(t["turnIndex"] for t in ts)
        if len(ts) != 2 or idx != [0, 1]:
            bad_turns.append((cid, idx))
    check("每会话恰好 2 轮且 turnIndex 为 0/1", not bad_turns,
          "异常 %s" % bad_turns[:5] if bad_turns else "250 会话全部合规")

    cids = sorted(convs)
    expect_c = ["CONV_%04d" % i for i in range(1, 251)]
    check("conversationId 为 CONV_0001..CONV_0250", cids == expect_c,
          "首=%s 尾=%s" % (cids[0] if cids else "-", cids[-1] if cids else "-"))

    # ---------------- 5. 引用可对齐 ----------------
    idset = set(ids)
    bad_ref = []
    ref_counts = Counter()
    for r in rows:
        refs = r["referenceDocIds"]
        if not isinstance(refs, list):
            bad_ref.append((r["conversationId"], r["turnIndex"], "not-list"))
            continue
        for x in refs:
            if x not in idset:
                bad_ref.append((r["conversationId"], r["turnIndex"], x))
        ref_counts[len(refs)] += 1
    check("所有 referenceDocIds 存在于 docs", not bad_ref,
          "悬空引用 %s" % bad_ref[:5] if bad_ref else
          "1~3 篇引用分布 %s" % dict(sorted(ref_counts.items())))

    too_many = [(r["conversationId"], r["turnIndex"], len(r["referenceDocIds"]))
                for r in rows if len(r["referenceDocIds"]) > 3]
    check("referenceDocIds 数量 1~3（kb）", not too_many,
          "超限 %s" % too_many[:5] if too_many else "全部 ≤3")

    # kb 必须有引用；chitchat 必须为空数组（契约允许空）
    kb_no_ref = [(r["conversationId"], r["turnIndex"]) for r in rows
                 if r["intentHint"] == "kb" and not r["referenceDocIds"]]
    check("kb 轮次均有 referenceDocIds", not kb_no_ref,
          "缺失 %s" % kb_no_ref[:5] if kb_no_ref else "全部有引用")

    # ---------------- 6. intentHint 分布 ----------------
    idist = Counter(r["intentHint"] for r in rows)
    check("intentHint 取值合法", not (set(idist) - set(INTENTS)),
          ", ".join("%s=%d" % (x, idist.get(x, 0)) for x in INTENTS))
    kb_pct = idist.get("kb", 0) / len(rows) * 100 if rows else 0
    chit_pct = idist.get("chitchat", 0) / len(rows) * 100 if rows else 0
    sys_pct = idist.get("system", 0) / len(rows) * 100 if rows else 0
    check("intentHint 约 85% kb / 10% chitchat / 5% system",
          80 <= kb_pct <= 90 and 7 <= chit_pct <= 13 and 3 <= sys_pct <= 8,
          "kb=%.1f%% chitchat=%.1f%% system=%.1f%%" % (kb_pct, chit_pct, sys_pct))

    # ---------------- 7. 答案与追问质量 ----------------
    bad_ans = [(r["conversationId"], r["turnIndex"], len(r["referenceAnswer"]))
               for r in rows if not (80 <= len(r["referenceAnswer"]) <= 200)]
    check("referenceAnswer 长度 80~200", not bad_ans,
          "越界 %s" % bad_ans[:5] if bad_ans else "全部合规")

    empty_q = [(r["conversationId"], r["turnIndex"]) for r in rows if not r["question"].strip()]
    check("question 非空", not empty_q, "空问题 %s" % empty_q[:5] if empty_q else "全部非空")

    # 第二轮必须是指代式追问（依赖第 1 轮语境）
    REF_WORDS = ["那", "它", "这个", "那个", "该", "这", "其", "第二步", "第三步", "呢",
                 "上面", "刚才", "再确认", "另外", "顺着", "补充"]
    t1 = [r for r in rows if r["turnIndex"] == 1 and r["intentHint"] == "kb"]
    no_ref = [(r["conversationId"], r["question"]) for r in t1
              if not any(w in r["question"] for w in REF_WORDS)]
    check("kb 第二轮均为指代式追问", not no_ref,
          "非指代 %s" % no_ref[:3] if no_ref else "%d 轮全部含指代词" % len(t1))

    # 两轮问题不应完全相同（否则不构成多轮）
    same_q = []
    for cid, ts in convs.items():
        ts = sorted(ts, key=lambda x: x["turnIndex"])
        if len(ts) == 2 and ts[0]["question"] == ts[1]["question"]:
            same_q.append(cid)
    check("同会话两轮问题不重复", not same_q,
          "重复 %s" % same_q[:5] if same_q else "250 会话两轮均不同")

    # 两轮答案不应完全相同
    same_a = []
    for cid, ts in convs.items():
        ts = sorted(ts, key=lambda x: x["turnIndex"])
        if len(ts) == 2 and ts[0]["referenceAnswer"] == ts[1]["referenceAnswer"]:
            same_a.append(cid)
    check("同会话两轮答案不重复", not same_a,
          "重复 %s" % same_a[:5] if same_a else "250 会话两轮均不同")

    # ---------------- 8. chunksHint 合理性 ----------------
    bad_chunk = [(d["docId"], d["chunksHint"], len(d["text"]))
                 for d in docs
                 if not isinstance(d["chunksHint"], int) or d["chunksHint"] < 1]
    check("chunksHint 为正整数", not bad_chunk,
          "异常 %s" % bad_chunk[:5] if bad_chunk else "全部合法")

    # ---------------- 统计输出 ----------------
    print("-" * 78)
    print("统计信息")
    print("-" * 78)
    total_chars = sum(lens)
    print("文档总数          : %d" % len(docs))
    print("对话总轮数        : %d（%d 会话 × 2 轮）" % (len(rows), len(convs)))
    print("文档平均长度      : %.1f 字符" % (sum(lens) / len(lens)))
    print("文档长度区间      : %d ~ %d" % (min(lens), max(lens)))
    print("文档总字符数      : %d（约 %.1f 万）" % (total_chars, total_chars / 10000))
    print("queries 总字符数  : %d" % sum(len(r["question"]) + len(r["referenceAnswer"]) for r in rows))
    print("含表格文档数      : %d（%.1f%%）" % (len(has_table), table_ratio * 100))
    print("平均 chunksHint   : %.1f（FIXED_SIZE 500/50）"
          % (sum(d["chunksHint"] for d in docs) / len(docs)))
    print()
    print("领域分布:")
    for x in DOMAINS:
        dl = [len(d["text"]) for d in docs if d["domain"] == x]
        print("  %-8s %3d 篇   平均 %6.1f 字符   总 %7d 字符"
              % (x, dist.get(x, 0), sum(dl) / len(dl) if dl else 0, sum(dl)))
    print()
    print("intentHint 分布:")
    for x in INTENTS:
        print("  %-9s %3d 轮  (%.1f%%)" % (x, idist.get(x, 0), idist.get(x, 0) / len(rows) * 100))
    print()
    print("引用数量分布（每轮引用文档数）:")
    for k in sorted(ref_counts):
        print("  %d 篇引用 : %3d 轮" % (k, ref_counts[k]))
    print()

    # 引用最多的文档 TOP5（检查是否过度集中）
    cited = Counter()
    for r in rows:
        for x in r["referenceDocIds"]:
            cited[x] += 1
    print("被引用最多的 5 篇文档:")
    for did, c in cited.most_common(5):
        t = next((d["title"] for d in docs if d["docId"] == did), "")
        print("  %s  被引用 %d 次  %s" % (did, c, t))
    print("有引用的文档数    : %d / %d" % (len(cited), len(docs)))
    print()

    # ---------------- 汇总 ----------------
    print("=" * 78)
    passed = sum(1 for _, ok, _ in checks if ok)
    total = len(checks)
    for name, ok, detail in checks:
        flag = "PASS" if ok else "FAIL"
        print("[%s] %-46s %s" % (flag, name, detail))
    print("=" * 78)
    print("结果: %d / %d 项通过" % (passed, total))
    if passed == total:
        print("全部校验通过 ✓")
    else:
        print("存在未通过项 ✗")
    print("=" * 78)

    if args.json:
        print()
        print(json.dumps({
            "ok": passed == total,
            "passed": passed,
            "total": total,
            "docs": len(docs),
            "queries": len(rows),
            "conversations": len(convs),
            "avgDocLen": round(sum(lens) / len(lens), 1),
            "totalChars": total_chars,
            "domainDist": dict(dist),
            "intentDist": dict(idist),
            "failed": [n for n, ok, _ in checks if not ok],
        }, ensure_ascii=False, indent=2))

    return 0 if passed == total else 1


if __name__ == "__main__":
    # Windows 控制台默认 GBK，输出 ✓ 等符号会抛 UnicodeEncodeError 导致脚本以非 0 退出，
    # 让「校验全过」被误读成「校验失败」。这里显式把标准输出切成 UTF-8。
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    sys.exit(main())
