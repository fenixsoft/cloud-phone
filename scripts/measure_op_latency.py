#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""op-latency 日志聚合（op-latency-measurement 清单 ⑤）。

从 logcat 抓取 cloudphone-latency 的 `op …` / `g2g …` 行与 cloudphone-touch 的
`t1 …` 行，按 opId 配对成样本，输出分段延迟统计（P50/P95/max、e2e 直方图、
按 transport 分组、result 失败拆分）。P50/P95 为最近秩（nearest-rank，下取整）
口径，较线性插值口径略保守。纯 stdlib。

数据来源（二选一，默认自动跑 adb）：
  python3 scripts/measure_op_latency.py                    # 跑 `adb logcat -d` 解析当前缓冲
  python3 scripts/measure_op_latency.py -s emulator-5556   # 指定 adb serial
  python3 scripts/measure_op_latency.py --file log.txt     # 解析已保存的 logcat 文本

事件过滤：默认只统计离散事件 DOWN/UP（触摸测量的标准口径，MOVE 风暴不计）。
  --include-move  额外统计 MOVE（会放大样本量，仅诊断风暴用）
  --include-back  额外统计 BACK（边缘侧滑直发路径）
  --all           不过滤（含无法配对 t1 的样本，action 记 "?")

log 来源端构造样例（真机验收时的标准打法，见 docs/realdevice-retest-checklist.md）：
  adb -s emulator-5556 logcat -c
  # …在客户端上点按/滑动若干次…
  adb -s emulator-5556 logcat -d > /tmp/op-latency.log
  python3 scripts/measure_op_latency.py --file /tmp/op-latency.log
"""

import argparse
import re
import sys
from collections import Counter, defaultdict

OP_RE = re.compile(
    r"op opId=(\d+) transport=(\S+) result=(\S+) e2eMs=(-?\d+) uplinkMs=(-?\d+) "
    r"srvMs=(-?\d+) downMs=(-?\d+) injectMs=(-?\d+) frameWaitMs=(-?\d+) encQueueMs=(-?\d+) g2gMs=(-?\d+)"
)
G2G_RE = re.compile(r"g2g opId=(\d+) g2gMs=(-?\d+)")
T1_RE = re.compile(r"t1 action=(\d+) p=\d+ x=-?\d+ y=-?\d+ opId=(\d+)")

ACTION_NAMES = {0: "DOWN", 1: "UP", 2: "MOVE", 3: "BACK"}
SEGMENTS = ["e2eMs", "uplinkMs", "srvMs", "downMs", "injectMs", "frameWaitMs", "encQueueMs", "g2gMs"]
BUCKETS = [(0, 20), (20, 50), (50, 100), (100, 200), (200, 500), (500, 1000), (1000, None)]
BUCKET_LABELS = ["<20", "20-50", "50-100", "100-200", "200-500", "500-1k", ">=1k"]


def percentile(sorted_vals, pct):
    """最近秩（nearest-rank）口径：取下取整秩对应样本，较线性插值口径略保守。"""
    if not sorted_vals:
        return None
    idx = ((len(sorted_vals) - 1) * pct) // 100
    return sorted_vals[idx]


def load_lines(args):
    if args.file:
        try:
            with open(args.file, "r", encoding="utf-8", errors="replace") as f:
                return f.read().splitlines()
        except FileNotFoundError:
            sys.exit("error: 文件不存在：%s；请确认 --file 路径" % args.file)
    cmd = ["adb"]
    if args.serial:
        cmd += ["-s", args.serial]
    cmd += ["logcat", "-d"]
    try:
        import subprocess
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    except FileNotFoundError:
        sys.exit("error: adb 不可用；请把 logcat 存文件后用 --file 解析")
    except subprocess.TimeoutExpired:
        sys.exit("error: adb logcat -d 超时（30s）；请把 logcat 存文件后用 --file 解析")
    if out.returncode != 0:
        sys.exit("error: adb logcat -d 失败：%s" % out.stderr.strip()[:200])
    return out.stdout.splitlines()


def main():
    ap = argparse.ArgumentParser(description="op-latency 分段延迟统计（logcat → P50/P95/直方图）")
    ap.add_argument("--file", help="从文件解析 logcat 文本（缺省跑 adb logcat -d）")
    ap.add_argument("-s", "--serial", help="adb serial（如 emulator-5556）")
    ap.add_argument("--include-move", action="store_true", help="额外统计 MOVE 事件")
    ap.add_argument("--include-back", action="store_true", help="额外统计 BACK 事件")
    ap.add_argument("--all", action="store_true", help="不过滤事件类型（含无法配对 t1 的样本）")
    args = ap.parse_args()

    lines = load_lines(args)

    action_by_opid = {}      # opId -> 事件名（来自 t1 行）
    ops = {}                 # opId -> 样本 dict
    order = []               # 保序去重
    g2g_late = {}            # op 行先于渲染兑现：g2g 行覆盖
    for line in lines:
        m = OP_RE.search(line)
        if m:
            op_id = int(m.group(1))
            vals = [int(m.group(i)) for i in range(4, 12)]
            sample = dict(zip(SEGMENTS, vals))
            sample["transport"] = m.group(2)
            sample["result"] = m.group(3)
            if op_id not in ops:
                order.append(op_id)
            ops[op_id] = sample  # 同 opId 重复（理论不该发生）取后者
            continue
        m = G2G_RE.search(line)
        if m:
            g2g_late[int(m.group(1))] = int(m.group(2))
            continue
        m = T1_RE.search(line)
        if m:
            action_by_opid[int(m.group(2))] = ACTION_NAMES.get(int(m.group(1)), "?%d" % int(m.group(1)))

    # 渲染兑现回填（-1 → g2g 行值）
    for op_id, g2g in g2g_late.items():
        if op_id in ops and ops[op_id]["g2gMs"] < 0:
            ops[op_id]["g2gMs"] = g2g

    selected = {0, 1}
    if args.include_move:
        selected.add(2)
    if args.include_back:
        selected.add(3)
    if args.all:
        selected = None  # 不过滤

    def wanted(op_id):
        if selected is None:
            return True
        return action_by_opid.get(op_id) in {ACTION_NAMES[s] for s in selected}

    samples = []
    for op_id in order:
        if not wanted(op_id):
            continue
        s = ops[op_id]
        s["opId"] = op_id
        s["action"] = action_by_opid.get(op_id, "?")
        samples.append(s)

    if not samples:
        sys.exit("no op samples matched (filter=%s); 先在客户端上产生触摸，再 adb logcat -d" %
                 ("ALL" if args.all else ",".join(ACTION_NAMES[s] for s in sorted(selected))))

    print("== op-latency 统计（n=%d，事件过滤=%s）==" % (
        len(samples), "ALL" if args.all else "+".join(ACTION_NAMES[s] for s in sorted(selected))))

    # 失败拆分
    results = Counter(s["result"] for s in samples)
    if len(results) > 1 or set(results) != {"OK"}:
        print("result 分布: " + "  ".join("%s=%d" % kv for kv in sorted(results.items())))

    # 按 transport 分组
    by_transport = defaultdict(list)
    for s in samples:
        by_transport[s["transport"]].append(s)
    groups = dict(by_transport)
    groups["ALL"] = samples

    for name, group in groups.items():
        print("\n── transport=%s (n=%d) ──" % (name, len(group)))
        header = "%-12s" % "段位"
        for seg in SEGMENTS:
            header += "%12s" % seg
        print(header)
        rows = [("P50", 50), ("P95", 95), ("max", None)]
        for label, pct in rows:
            row = "%-12s" % label
            for seg in SEGMENTS:
                vals = sorted(s[seg] for s in group if s[seg] >= 0)
                if not vals:
                    row += "%12s" % "—"
                elif pct is None:
                    row += "%12d" % vals[-1]
                else:
                    row += "%12d" % percentile(vals, pct)
            print(row)
        n_g2g = sum(1 for s in group if s["g2gMs"] >= 0)
        print("g2g 配对率: %d/%d" % (n_g2g, len(group)))
        e2e_vals = [s["e2eMs"] for s in group]
        labels, counts = [], []
        for lo, hi in BUCKETS:
            labels.append(("%d-%dms" % (lo, hi)) if hi is not None else (">=%dms" % lo))
            counts.append(sum(1 for v in e2e_vals if v >= lo and (hi is None or v < hi)))
        hist = "  ".join("%s:%d" % (l, c) for l, c in zip(labels, counts) if c)
        n_e2e = sum(1 for v in e2e_vals if v >= 0)
        if n_e2e < len(e2e_vals):
            hist += "  （e2eMs=-1 未兑现: %d 个）" % (len(e2e_vals) - n_e2e)
        print("e2e 直方图: " + hist)

    # 验收提示（P95 口径：e2eAck ≤ 200ms）
    e2e_all = sorted(s["e2eMs"] for s in samples if s["e2eMs"] >= 0)
    if not e2e_all:
        sys.exit("no valid e2e samples (e2eMs 全为 -1 未兑现); 跳过验收线 200ms 判定")
    p95 = percentile(e2e_all, 95)
    verdict = "达标 ✓" if p95 <= 200 else "超标 ✗（优先看 frameWaitMs/encQueueMs 与 downMs 段）"
    print("\nP95(e2e)=%dms — 验收线 200ms：%s" % (p95, verdict))


if __name__ == "__main__":
    main()
