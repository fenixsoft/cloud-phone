#!/usr/bin/env python3
"""measure_latency.py — M0 端到端延迟测量（白闪 + PTS 差值双口径）。

数据来源（同一源端+客户端模拟器对）：
1. 白闪口径：客户端注入点击 → loadgen 白闪 120ms → 客户端解码到白帧。
   以客户端 logcat `cloudphone-latency` 的 touch→first-white 时间差衡量；
   脚本侧通过 adb 注入点击并时间戳标记（touchmark 行），随后抓取首个
   `white-frame` 行计算视觉往返延迟。
2. PTS 差值口径：客户端逐帧 log `frame ... delayMs=<N>`（源端编码 PTS 与
   客户端渲染时刻差，要求两端时钟同源——同宿主机启动的模拟器族满足）。

用法：
  python3 scripts/measure_latency.py --client-serial emulator-5556 \
      [--duration 30] [--samples 20] [--report docs/latency-samples.json]

输出：P50/P95/均值 + 每样本明细（JSON 存档）。
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import subprocess
import time


def adb(serial: str, *args: str, binary: bool = False):
    cmd = ["adb", "-s", serial, *args]
    out = subprocess.run(cmd, capture_output=True, check=True)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def clear_logcat(serial: str) -> None:
    subprocess.run(["adb", "-s", serial, "logcat", "-c"], capture_output=True)


def collect_latency_lines(serial: str, duration_s: int) -> list[dict]:
    """抓取 duration 秒内客户端 cloudphone-latency 日志。"""
    deadline = time.time() + duration_s
    samples: list[dict] = []
    proc = subprocess.Popen(
        ["adb", "-s", serial, "logcat", "-v", "time", "-s", "cloudphone-latency"],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    try:
        while time.time() < deadline:
            line = proc.stdout.readline()  # type: ignore[union-attr]
            if not line:
                break
            text = line.decode("utf-8", "replace").strip()
            m = re.search(r"delayMs=(\d+)", text)
            if m:
                samples.append({"kind": "pts-delta", "delayMs": int(m.group(1))})
                continue
            m = re.search(r"white-flash Ms=(\d+)", text)
            if m:
                samples.append({"kind": "white-flash", "delayMs": int(m.group(1))})
    finally:
        proc.terminate()
    return samples


def inject_clicks(serial: str, count: int, interval_s: float) -> None:
    """在客户端屏幕中心注入点击（经客户端→源端→loadgen 白闪，测量视觉往返）。"""
    for _ in range(count):
        adb(serial, "shell", "input", "tap", "540", "960")
        time.sleep(interval_s)


def summarize(samples: list[dict]) -> dict:
    def pct(values: list[int], p: float) -> int:
        if not values:
            return -1
        s = sorted(values)
        return s[min(len(s) - 1, int(len(s) * p))]

    pts = [s["delayMs"] for s in samples if s["kind"] == "pts-delta"]
    flash = [s["delayMs"] for s in samples if s["kind"] == "white-flash"]
    return {
        "ptsDelta": {
            "count": len(pts),
            "p50Ms": pct(pts, 0.50),
            "p95Ms": pct(pts, 0.95),
            "meanMs": round(statistics.fmean(pts), 1) if pts else -1,
        },
        "whiteFlash": {
            "count": len(flash),
            "p50Ms": pct(flash, 0.50),
            "p95Ms": pct(flash, 0.95),
            "meanMs": round(statistics.fmean(flash), 1) if flash else -1,
        },
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="M0 端到端延迟测量（白闪+PTS 差值）")
    ap.add_argument("--client-serial", required=True, help="客户端模拟器 serial（如 emulator-5556）")
    ap.add_argument("--duration", type=int, default=30, help="采集窗口秒数")
    ap.add_argument("--clicks", type=int, default=5, help="注入点击次数（白闪样本）")
    ap.add_argument("--report", default="docs/latency-samples.json", help="样本 JSON 存档路径")
    args = ap.parse_args()

    clear_logcat(args.client_serial)
    print(f"[measure_latency] 采集窗口 {args.duration}s，注入 {args.clicks} 次点击…")
    # 后台注入点击（白闪标记），前台并行抓日志
    import threading

    clicker = threading.Thread(
        target=inject_clicks, args=(args.client_serial, args.clicks, max(2.0, args.duration / (args.clicks + 1)))
    )
    clicker.start()
    samples = collect_latency_lines(args.client_serial, args.duration)
    clicker.join()

    summary = summarize(samples)
    result = {"clientSerial": args.client_serial, "samples": samples, "summary": summary}
    with open(args.report, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"[measure_latency] 样本已存档 → {args.report}")
    ok = summary["ptsDelta"]["count"] > 0
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
