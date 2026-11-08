#!/usr/bin/env python3
"""measure_latency.py — 触摸延迟测量（client-ux-and-input-latency 5.2）。

触摸口径：串流静屏下经客户端协议链注入触摸（adb input tap → 客户端 TouchChannel
① → INPUT_EVENT → 源端 ②接收 ③注入 → ④采集出帧 ⑤编码 → 回传 ⑥客户端渲染），
匹配 t1（客户端触摸产生）→ t6（同一 seq 帧渲染完成） Wall 时间差（两端模拟器时钟
同源宿主，logcat -v time 可比），输出 P50/P95（20 次样本）。

用法:
  python3 scripts/measure_latency.py --touch \
      --client emulator-5556 --source emulator-5554 --samples 20 \
      [--x 540 --y 1200] [--out <报告.json>]

前置条件（脚本自动处理）:
  1. 客户端已连上源端并处于 DecodeRenderActivity（全屏串流）。
  2. 源端静屏：脚本打开系统设置页 + 开启 pointer_location 叠加层保证每次触摸
     都产生可捕获的视觉变化（测量结束自动关闭）。
"""
import argparse
import json
import re
import subprocess
import time


def sh(*args: str) -> str:
    r = subprocess.run(list(args), capture_output=True, text=True)
    if r.returncode != 0:
        raise AdbError(
            f"command failed (rc={r.returncode}): {' '.join(args)}"
            + (f": {r.stderr.strip()}" if r.stderr.strip() else ""))
    return r.stdout


class AdbError(RuntimeError):
    """adb 命令非零退出（设备不可达/离线等）"""


def logcat(serial: str, since_time: str) -> list:
    """抓取 threadtime 格式的 cloudphone-touch 行（自 since_time 起）"""
    out = sh("adb", "-s", serial, "logcat", "-d", "-v", "threadtime",
             "-t", since_time, "-s", "cloudphone-touch")
    rows = []
    for line in out.splitlines():
        m = re.match(r"(\d\d-\d\d)\s+(\d\d:\d\d:\d\d\.\d\d\d)\s+\d+\s+\d+\s+[IWD]\s+cloudphone-touch:\s+(.*)", line)
        if not m:
            continue
        ts = time.strptime(f"{time.localtime().tm_year}-{m.group(1)} {m.group(2)[:8]}",
                           "%Y-%m-%d %H:%M:%S")
        ms = int(m.group(2).split(".")[1]) / 1000.0
        wall = time.mktime(ts) + ms
        rows.append((wall, m.group(3)))
    return rows


def parse_t5_seq(body: str):
    m = re.search(r"t5 enc seq=(\d+)", body)
    return int(m.group(1)) if m else None


def percentile(values: list, p: float) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    idx = min(len(s) - 1, max(0, round(p * (len(s) - 1))))
    return s[idx]


def run_touch(args):
    client, source = args.client, args.source
    n = args.samples

    # 前置：源端静屏 + pointer_location（try/finally 保证早退/异常路径也恢复）；
    # --load 模式跳过静屏准备（源端由 60fps 负载画面提供视觉变化，帧率回归口径）
    if not args.load:
        sh("adb", "-s", source, "shell", "am", "start", "-n",
           "com.android.settings/.Settings")
    sh("adb", "-s", source, "shell", "settings", "put", "system",
       "pointer_location", "1")
    try:
        time.sleep(3)

        # 清空两端缓冲区（拿干净起点）
        sh("adb", "-s", client, "logcat", "-c")
        sh("adb", "-s", source, "logcat", "-c")
        since = time.strftime("%m-%d %H:%M:%S.000")

        # 以 300ms 间隔注入 20 次（触摸链并发段：静屏下帧稀疏，seq 匹配仍唯一）
        for i in range(n):
            sh("adb", "-s", client, "shell", "input", "tap", str(args.x), str(args.y))
            time.sleep(0.3)
        # 收尾窗口：等最后一帧回传渲染
        time.sleep(4)

        c_rows = logcat(client, since)
        s_rows = logcat(source, since)

        # t1（客户端，仅 DOWN）按时间排序；t3（源端注入完成，仅 DOWN）→ 之后首个 t5 seq → t6
        t1s = [(w, b) for w, b in c_rows if b.startswith("t1 action=0 ")]
        t3s = [(w, b) for w, b in s_rows if b.startswith("t3 inject done display=0 action=0")]
        t5s = [(w, parse_t5_seq(b)) for w, b in s_rows if b.startswith("t5 ")]
        t6_by_seq = {}
        for w, b in c_rows:
            if b.startswith("t6 "):
                ms = re.search(r"seq=(\d+)", b)
                if ms:
                    t6_by_seq[int(ms.group(1))] = w

        latencies = []
        segments = []  # 每样本分段：(t3-t1, t5-t3, t6-t5)
        t5_idx = 0
        # 时钟自校准：两端模拟器时钟偏差 ~±150ms 且随宿主同步漂移 → 先按最近邻粗配，
        # 取中位数偏差为钟差，再按 ±150ms 窗口精配（300ms 触摸间隔下无歧义）
        raw_pairs = []
        for t3w, _ in t3s:
            best_w = min((w for w, _ in t1s), key=lambda x: abs(x - t3w), default=None)
            if best_w is not None:
                raw_pairs.append((t3w, best_w, best_w - t3w))
        if not raw_pairs:
            print(json.dumps({"mode": "touch", "error": "no touch pairs found"}))
            raise SystemExit(1)
        offsets = sorted(p[2] for p in raw_pairs)
        median_off = offsets[len(offsets) // 2]
        pairs = [(t3w, t1w) for t3w, t1w, off in raw_pairs if abs(off - median_off) <= 0.350]
        for t3w, t1w in pairs:
            # 注入完成后首个编码帧
            while t5_idx < len(t5s) and t5s[t5_idx][0] < t3w:
                t5_idx += 1
            if t5_idx >= len(t5s):
                continue
            t5w, seq = t5s[t5_idx]
            t5_idx += 1
            t6w = t6_by_seq.get(seq)
            if t6w is None:
                continue
            latencies.append((t6w - t1w) * 1000)
            segments.append({
                "input_ms": round((t3w - t1w) * 1000, 1),
                "capture_enc_ms": round((t5w - t3w) * 1000, 1),
                "net_render_ms": round((t6w - t5w) * 1000, 1),
                "seq": seq,
            })

        result = {
            "mode": "touch-load" if args.load else "touch",
            "samples_requested": n,
            "samples_matched": len(latencies),
            "p50_ms": round(percentile(latencies, 0.50), 1),
            "p95_ms": round(percentile(latencies, 0.95), 1),
            "max_ms": round(max(latencies), 1) if latencies else 0,
            "min_ms": round(min(latencies), 1) if latencies else 0,
            "segments": segments[:10],
        }
        if not latencies:
            result["error"] = "no samples matched"
        print(json.dumps(result, ensure_ascii=False, indent=2))
        if args.out:
            with open(args.out, "w") as f:
                json.dump(result, f, ensure_ascii=False, indent=2)
        if "error" in result:
            raise SystemExit(1)
        return result
    finally:
        # 恢复 pointer_location（早退/异常路径同样生效；恢复失败不掩盖测量主错误）
        try:
            sh("adb", "-s", source, "shell", "settings", "put", "system",
               "pointer_location", "0")
        except AdbError:
            pass


def main():
    ap = argparse.ArgumentParser(description="触摸/视觉延迟测量")
    ap.add_argument("--touch", action="store_true", help="触摸口径（t1→t6）")
    ap.add_argument("--load", action="store_true", help="60fps 负载口径（跳过静屏准备）")
    ap.add_argument("--client", default="emulator-5556")
    ap.add_argument("--source", default="emulator-5554")
    ap.add_argument("--samples", type=int, default=20)
    ap.add_argument("--x", type=int, default=540)
    ap.add_argument("--y", type=int, default=1200)
    ap.add_argument("--out", default=None, help="报告 JSON 输出路径")
    args = ap.parse_args()
    if not args.touch:
        ap.error("当前仅支持 --touch 触摸口径（视觉变化口径见 SPEC 5.2 说明）")
    try:
        run_touch(args)
    except AdbError as e:
        print(json.dumps({"mode": "touch", "error": str(e)}, ensure_ascii=False))
        raise SystemExit(1)

if __name__ == "__main__":
    main()
