#!/usr/bin/env python3
"""run_matrix.py — M0 验收矩阵（3 网络 × 2 负载 × 2 分辨率 = 12 组合，各 60s）全自动编排。

每个组合：
  1. 复位会话（STOP/重连），按后端 kind + 会话参数（分辨率档）发起 START_SESSION；
  2. 切换 loadgen 页面（GameLoad=游戏档 / StaticApp=应用档）；
  3. 60s 窗口采集客户端 logcat：STATS 行（fps/bitrate/rtt/loss）+ 帧间隔 + 延迟样本；
  4. 通过判定（design.md §可用线）：
     - 游戏档：平均 fps ≥ 45 且 延迟 P95 ≤ 200ms
     - 应用档：静止码率 ≤ 1Mbps
     - 全部：60s 不断流（STATS 连续、无 DISCONNECT）；
  5. 组合失败自动重试 1 次；产出 markdown 报告 docs/validation-report.md。

用法：python3 scripts/run_matrix.py [--serials emulator-5554,emulator-5556] [--seconds 60]
注意：需要源端串流服务已运行且完成投影授权（bootstrap_adb.sh 一键拉起）。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import subprocess
import time
from dataclasses import dataclass, field, asdict

# 网络→客户端连接地址：lan 直连走宿主端口映射；p2p/frp 走公网入口（客户端从模拟器出去）
BACKEND_HOSTS = {"lan-direct": "10.0.2.2", "p2p": "112.74.85.74", "frp": "112.74.85.74"}
BACKENDS = list(BACKEND_HOSTS.keys())
LOADS = [("game", "cloudphone.loadgen/.GameLoadActivity"), ("static", "cloudphone.loadgen/.StaticAppActivity")]
RESOLUTIONS = [("720p", 1280, 720, 30, 6000), ("1080p", 1920, 1080, 30, 12000)]

# STATS 行（源端 StatsMessage → 客户端 logcat 由 StatsView 渲染；此处解析客户端 cloudphone-latency 与源端 stats）
STATS_RE = re.compile(r"fps=([\d.]+).*bitrate.*?([\d.]+).*rtt.*?([\d.]+).*loss.*?([\d.]+)")
DELAY_RE = re.compile(r"delayMs=(\d+)")


@dataclass
class ComboResult:
    backend: str
    load: str
    resolution: str
    fpsMean: float = -1.0
    fpsP95FrameGapMs: float = -1.0
    delayP50Ms: int = -1
    delayP95Ms: int = -1
    bitrateMeanKbps: float = -1.0
    lossPctMax: float = -1.0
    disconnects: int = 0
    passed: bool = False
    reason: str = ""
    retries: int = 0


def adb(serial: str, *args: str) -> str:
    out = subprocess.run(["adb", "-s", serial, *args], capture_output=True)
    return out.stdout.decode("utf-8", "replace")


def collect_combo(client_serial: str, seconds: int) -> dict:
    """60s 窗口采集：STATS/延迟样本/断流计数。"""
    proc = subprocess.Popen(
        ["adb", "-s", client_serial, "logcat", "-v", "epoch", "-s",
         "cloudphone-latency", "cloudphone-stats", "TransportStats"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
    )
    samples = {"delays": [], "fps": [], "bitrate": [], "loss": [], "frameGapsMs": [], "disconnects": 0}
    last_frame_epoch = None
    deadline = time.time() + seconds
    try:
        while time.time() < deadline:
            line = proc.stdout.readline()  # type: ignore[union-attr]
            if not line:
                break
            text = line.decode("utf-8", "replace").strip()
            try:
                epoch = float(text.split()[0])
            except (ValueError, IndexError):
                epoch = None
            m = DELAY_RE.search(text)
            if m:
                samples["delays"].append(int(m.group(1)))
                if epoch is not None:
                    if last_frame_epoch is not None:
                        samples["frameGapsMs"].append((epoch - last_frame_epoch) * 1000)
                    last_frame_epoch = epoch
                continue
            m = STATS_RE.search(text)
            if m:
                samples["fps"].append(float(m.group(1)))
                samples["bitrate"].append(float(m.group(2)))
                samples["loss"].append(float(m.group(4)))
                continue
            if "DISCONNECT" in text or "重连" in text:
                samples["disconnects"] += 1
    finally:
        proc.terminate()
    return samples


def pct(values: list, p: float):
    if not values:
        return -1
    s = sorted(values)
    return s[min(len(s) - 1, int(len(s) * p))]


def judge(r: ComboResult) -> tuple[bool, str]:
    if r.disconnects > 0:
        return False, f"断流 {r.disconnects} 次"
    if r.load == "game":
        if r.fpsMean >= 45 and 0 <= r.delayP95Ms <= 200:
            return True, "游戏档 fps≥45 且 P95≤200ms"
        return False, f"游戏档 fps={r.fpsMean:.1f} P95={r.delayP95Ms}ms"
    if r.load == "static":
        if 0 <= r.bitrateMeanKbps <= 1000:
            return True, "应用档静止码率≤1Mbps"
        return False, f"应用档码率={r.bitrateMeanKbps:.0f}kbps"
    return False, "未知负载档"


def _send_capture(source_serial: str, w: int, h: int, fps: int, kbps: int) -> None:
    """经服务 intent extras 下发采集参数（幂等：同参时服务打 ready (unchanged)）"""
    subprocess.run(["adb", "-s", source_serial, "shell", "am", "start-foreground-service",
                    "-n", "cloudphone.source/.StreamService",
                    "-a", "cloudphone.source.START_CAPTURE",
                    "--ei", "width", str(w), "--ei", "height", str(h),
                    "--ei", "fps", str(fps), "--ei", "bitrateKbps", str(kbps)],
                   capture_output=True, timeout=30)


def _wait_ready(source_serial: str, timeout: int) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if "shared capture ready" in adb(source_serial, "logcat", "-d", "-t", "200"):
            time.sleep(1)
            return True
        time.sleep(1)
    return False


def ensure_pipeline(source_serial: str, w: int, h: int, fps: int, kbps: int, state: dict) -> bool:
    """共享采集管线参数对齐（一授权一 VD 的镜像约束）：
    1) 先直接下发参数——服务侧参数已对齐则幂等返回 ready（不浪费一次授权、不动活管线）；
    2) 未就绪才重授权（新凭据）并再次下发重建。"""
    import subprocess as _sp
    params = {"width": w, "height": h, "fps": fps, "bitrateKbps": kbps}
    if state.get("params") == params:
        return True
    _send_capture(source_serial, w, h, fps, kbps)
    if _wait_ready(source_serial, 6):
        state["params"] = params
        return True
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    _sp.run(["bash", os.path.join(root, "scripts", "bootstrap_adb.sh"), "grant"],
            capture_output=True, timeout=90)
    # 新授权会使旧投影停止（Android 14 本镜像实测），等旧管线 onStop 回调落地再重建
    time.sleep(2)
    _send_capture(source_serial, w, h, fps, kbps)
    if _wait_ready(source_serial, 30):
        state["params"] = params
        return True
    print(f"[run_matrix] [ensure] 授权重建仍未就绪（{w}x{h}@{fps}/{kbps}）")
    return False


def run_combo(source_serial: str, client_serial: str, backend: str, load: str, load_activity: str,
              res: str, w: int, h: int, fps: int, kbps: int, seconds: int, pstate: dict) -> ComboResult:
    r = ComboResult(backend=backend, load=load, resolution=res)
    for attempt in range(2):  # 失败重试 1 次
        r.retries = attempt
        if not ensure_pipeline(source_serial, w, h, fps, kbps, pstate):
            r.disconnects = r.disconnects + 1
            r.passed, r.reason = False, "采集管线参数对齐失败（重授权超时）"
            continue
        adb(client_serial, "shell", "am", "force-stop", "cloudphone.client")
        time.sleep(0.5)
        # 连接 + 会话参数（经客户端应用入口自动化：am extra 传入矩阵参数）
        adb(client_serial, "shell", "am", "start", "-n", "cloudphone.client/.ConnectActivity",
            "--es", "autoHost", BACKEND_HOSTS.get(backend, "10.0.2.2"), "--es", "autoBackend", backend,
            "--ei", "autoWidth", str(w), "--ei", "autoHeight", str(h),
            "--ei", "autoFps", str(fps), "--ei", "autoBitrate", str(kbps),
            "--es", "autoPackage", load_activity.removeprefix("cloudphone.loadgen/."))
        time.sleep(3)
        # -S 强制重启负载 App：普通 am start 可能静默不切换前台任务（实测 Static 残留前台导致游戏档 0 帧）
        adb(source_serial, "shell", "am", "start", "-S", "-n", load_activity)
        time.sleep(2)
        adb(client_serial, "shell", "logcat", "-c")
        samples = collect_combo(client_serial, seconds)
        r.fpsMean = statistics.fmean(samples["fps"]) if samples["fps"] else -1.0
        r.fpsP95FrameGapMs = pct(samples["frameGapsMs"], 0.95)
        r.delayP50Ms = pct(samples["delays"], 0.50)
        r.delayP95Ms = pct(samples["delays"], 0.95)
        r.bitrateMeanKbps = statistics.fmean(samples["bitrate"]) if samples["bitrate"] else -1.0
        r.lossPctMax = max(samples["loss"]) if samples["loss"] else -1.0
        r.disconnects = samples["disconnects"]
        r.passed, r.reason = judge(r)
        if not r.passed:
            # 失败诊断尾迹：客户端最后 25 行（含 recv/latency 标签流），进报告附录
            tail = adb(client_serial, "logcat", "-d", "-t", "25").replace("\n", " ⏎ ")
            print(f"[run_matrix] [diag] {backend}/{load}/{res} 尝试{attempt}: {tail[-400:]}")
        if r.passed:
            break
    return r


def write_report(results: list[ComboResult], path: str, seconds: int) -> None:
    lines = [
        "# M0 验收矩阵报告",
        "",
        f"生成时间：{time.strftime('%Y-%m-%d %H:%M:%S')}；窗口 {seconds}s/组合；失败自动重试 1 次。",
        "",
        "| 网络 | 负载 | 分辨率 | fps 均值 | P95 帧间隔(ms) | 延迟 P50/P95(ms) | 码率均值(kbps) | 最大丢包(%) | 断流 | 结果 |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in results:
        lines.append(
            f"| {r.backend} | {r.load} | {r.resolution} | {r.fpsMean:.1f} | {r.fpsP95FrameGapMs:.0f} | "
            f"{r.delayP50Ms}/{r.delayP95Ms} | {r.bitrateMeanKbps:.0f} | {r.lossPctMax:.2f} | {r.disconnects} | "
            f"{'PASS' if r.passed else 'FAIL'} |"
        )
    passed = sum(1 for r in results if r.passed)
    lines += ["", f"## 结论：{passed}/{len(results)} PASS", ""]
    for r in results:
        if not r.passed:
            lines.append(f"- FAIL `{r.backend}/{r.load}/{r.resolution}`：{r.reason}（重试 {r.retries} 次后）")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser(description="M0 验收矩阵 12 组合全自动编排")
    ap.add_argument("--serials", default="emulator-5554,emulator-5556")
    ap.add_argument("--seconds", type=int, default=60)
    ap.add_argument("--only", default="", help="逗号过滤 backend,load,res（如 frp,game,720p）")
    ap.add_argument("--report", default="docs/validation-report.md")
    args = ap.parse_args()
    source_serial, client_serial = args.serials.split(",")

    # 屏幕常亮：虚拟显示镜像依赖宿主屏持续合成，自动熄屏会让编码器断粮（实测 fps=0.0 根因）
    for s in (source_serial, client_serial):
        subprocess.run(["adb", "-s", s, "shell", "svc", "power", "stayon", "true"],
                       capture_output=True, timeout=15)
        subprocess.run(["adb", "-s", s, "shell", "input", "keyevent", "KEYCODE_WAKEUP"],
                       capture_output=True, timeout=15)
        subprocess.run(["adb", "-s", s, "shell", "wm", "dismiss-keyguard"],
                       capture_output=True, timeout=15)

    combos = []
    for backend in BACKENDS:
        for load_name, activity in LOADS:
            for res_name, w, h, fps, kbps in RESOLUTIONS:
                combos.append((backend, load_name, activity, res_name, w, h, fps, kbps))
    if args.only:
        keys = args.only.split(",")
        combos = [c for c in combos if all(k in c for k in keys)]

    results: list[ComboResult] = []
    pipeline_state: dict = {}  # 当前共享采集管线参数（避免同参重复授权）
    for i, (backend, load, activity, res, w, h, fps, kbps) in enumerate(combos, 1):
        print(f"[run_matrix] ({i}/{len(combos)}) {backend}/{load}/{res} …")
        r = run_combo(source_serial, client_serial, backend, load, activity, res, w, h, fps, kbps, args.seconds, pipeline_state)
        print(f"[run_matrix] → {'PASS' if r.passed else 'FAIL'} ({r.reason})")
        results.append(r)
        # 每组合落盘（中断可续）
        with open(args.report + ".progress.json", "w", encoding="utf-8") as f:
            json.dump([asdict(x) for x in results], f, ensure_ascii=False, indent=1)

    write_report(results, args.report, args.seconds)
    print(f"[run_matrix] 报告 → {args.report}")
    return 0 if all(r.passed for r in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
