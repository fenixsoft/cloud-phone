#!/usr/bin/env python3
"""uplink_stress.py — 1080p 高熵负载三网络上行压测 runner（gaming-uplink-1080p SPEC）。

复用 run_matrix 的管线授权（ensure_pipeline，授权顺序铁律：FGS 先于投影授权）与
参数注入模式（服务 START_CAPTURE extras + 客户端 autoHost/autoBackend/autoWidth…
应用入口自动化）。差异点：
  - 单组时长默认 120s（--duration），采样 1s 粒度（客户端 cloudphone-stats 1Hz 天然对齐）；
  - 稳态窗口取后 60s（--steady，掐掉启动瞬态）；
  - 矩阵 = {lan-direct, frp, p2p} × 1080p × mid 档；另有 high 档 lan-direct 单跑作编码器极限探针；
  - 每组压测前先跑空载基线（StaticApp，--baseline-seconds，默认 20s；frp 组必跑——
    VPS 中转上限 vs 家庭上行两因素归因依据）；
  - 三段测量（design D2）：编码器段（t5 enc 字节/帧，触摸注入开 2s 插桩窗口）、
    传输段（cloudphone-stats = Socket 层发送字节率/帧率）、网络段（RTT 膨胀 + 丢片率）。

判定（design D3）：frp/p2p 网络段独立判定——稳态 sustained ≥8Mbps 且 fps ≥50 且丢帧 <0.5% →
该路径网络 PASS；编码器输出持续低于 8Mbps 且传输层吐出速率与编码器输出一致、RTT 无膨胀 →
结论改记 ENCODER_CAPPED-真机复核，网络段判定独立记录（编码器瓶颈不得误判为网络故障）。

用法：
  python3 scripts/uplink_stress.py                       # 完整矩阵（3 路径 mid 120s + 基线 + high 探针）
  python3 scripts/uplink_stress.py --backends frp        # 单组（分批前台跑，避开工具超时）
  python3 scripts/uplink_stress.py --calibrate low,mid,high   # 档位标定（lan-direct 60s/档）
  python3 scripts/uplink_stress.py --high-probe          # 编码器极限探针（lan-direct high 60s）

产物：docs/uplink-stress-results.json（逐组数据+判定）+ docs/uplink-report.md 数据段（--report 追加）。
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import subprocess
import threading
import time
from dataclasses import dataclass, field, asdict

# 授权顺序铁律与参数注入模式复用 run_matrix（单一事实源）
import run_matrix as rm

BACKEND_HOSTS = rm.BACKEND_HOSTS
# 1080p 游戏档压测口径：60fps（fps≥50 判定才有意义）；档位控制内容熵，会话码率目标分档
RES_W, RES_H, RES_FPS = 1920, 1080, 60
SESSION_TARGET_KBPS = {"low": 10000, "mid": 10000, "high": 20000}
ACTIVITY_ENTROPY = "cloudphone.loadgen/.GameLoadActivity"
ACTIVITY_STATIC = "cloudphone.loadgen/.StaticAppActivity"

STATS_RE = re.compile(r"fps=([\d.]+) bitrate=([\d.]+) rtt=([\d.]+) loss=([\d.]+)")
DELAY_RE = re.compile(r"delayMs=(\d+)")
T5_RE = re.compile(r"t5 enc seq=(\d+) keyframe=(\w+) bytes=(\d+) gapMs=(\d+)")
TAP_X, TAP_Y = 540, 900  # 触摸注入点：避开底部按钮/overlay，落在高熵画面中央


@dataclass
class Sample:
    """1s 粒度客户端样本（cloudphone-stats = 源端 Socket 层发送侧快照）"""
    epoch: float
    fps: float
    bitrate_kbps: float
    rtt_ms: float
    loss_pct: float


@dataclass
class EncBucket:
    """1s 粒度编码器段样本（t5 enc：输出字节/帧数/帧间隔，触摸注入维持插桩窗口）"""
    epoch: int
    bytes: int = 0
    frames: int = 0
    max_gap_ms: int = 0


@dataclass
class StressGroup:
    backend: str
    tier: str
    duration: int
    kind: str = "stress"  # stress | calibration | probe | baseline
    target_kbps: int = 0
    # 稳态窗口统计（后 steady 秒）
    sustained_kbps: float = -1.0
    fps_mean: float = -1.0
    fps_recv: float = -1.0  # 客户端收帧率（cloudphone-latency 帧到达速率）
    loss_mean: float = -1.0
    loss_max: float = -1.0
    rtt_mean: float = -1.0
    delay_p50: int = -1
    delay_p95: int = -1
    # 编码器段（t5）
    enc_out_mbps: float = -1.0
    enc_fps: float = -1.0
    enc_max_gap_ms: int = -1
    # 网络段（基线对比）
    rtt_baseline: float = -1.0
    rtt_inflate_ms: float = -1.0
    baseline_kbps: float = -1.0
    disconnects: int = 0
    # 判定（Task 2.3 填充）
    encoder_capped: bool = False
    network_pass: bool | None = None
    verdict: str = ""
    attribution: str = ""
    samples: int = 0


def _spawn_logcat(serial: str, tags: list, on_line, deadline: float) -> subprocess.Popen:
    """启动 logcat 读取（readline 阻塞只困在读线程；主线程按 deadline 收尾回收）"""
    proc = subprocess.Popen(
        ["adb", "-s", serial, "logcat", "-v", "epoch", "-s", *tags],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
    )

    def _drain() -> None:
        while time.time() < deadline:
            line = proc.stdout.readline()  # type: ignore[union-attr]
            if not line:
                return
            on_line(line.decode("utf-8", "replace").strip())

    threading.Thread(target=_drain, daemon=True).start()
    return proc


def _stop(proc: subprocess.Popen | None) -> None:
    if proc is None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=5)


def collect_window(source_serial: str, client_serial: str, seconds: int, steady: int,
                   inject_touch: bool = True) -> dict:
    """双端同步采集窗口：客户端 stats/latency + 源端 t5 编码器字节；返回 {samples, enc, delays}。"""
    deadline = time.time() + seconds
    samples: list[Sample] = []
    delays: list[tuple[float, int]] = []  # (epoch, delayMs)——客户端收帧率按稳态窗口计数
    enc_buckets: dict[int, EncBucket] = {}
    disconnects = [0]
    lock = threading.Lock()

    def _on_client(text: str) -> None:
        m = STATS_RE.search(text)
        if m:
            try:
                epoch = float(text.split()[0])
            except (ValueError, IndexError):
                epoch = time.time()
            with lock:
                samples.append(Sample(epoch, float(m.group(1)), float(m.group(2)),
                                      float(m.group(3)), float(m.group(4))))
            return
        if "DISCONNECT" in text or "重连" in text:
            with lock:
                disconnects[0] += 1
            return
        m = DELAY_RE.search(text)
        if m and "cloudphone-latency" in text:
            try:
                epoch = float(text.split()[0])
            except (ValueError, IndexError):
                epoch = time.time()
            with lock:
                delays.append((epoch, int(m.group(1))))

    def _on_source(text: str) -> None:
        m = T5_RE.search(text)
        if not m:
            return
        try:
            epoch = int(float(text.split()[0]))
        except (ValueError, IndexError):
            return
        gap = int(m.group(4))
        with lock:
            b = enc_buckets.setdefault(epoch, EncBucket(epoch))
            b.bytes += int(m.group(3))
            b.frames += 1
            b.max_gap_ms = max(b.max_gap_ms, gap)

    subprocess.run(["adb", "-s", client_serial, "logcat", "-c"], capture_output=True, timeout=15)
    subprocess.run(["adb", "-s", source_serial, "logcat", "-c"], capture_output=True, timeout=15)
    pc = _spawn_logcat(client_serial, ["cloudphone-stats", "cloudphone-latency"], _on_client, deadline)
    ps = _spawn_logcat(source_serial, ["cloudphone-touch"], _on_source, deadline)

    stop_touch = threading.Event()

    def _touch_loop() -> None:
        # 每 1.2s 注入一次触摸：打开源端 2s 编码器插桩窗口（inputTraceUntilMs），
        # 保证 t5 enc 字节/帧间隔近连续覆盖（采样粒度 1s）
        while not stop_touch.is_set() and time.time() < deadline:
            try:
                subprocess.run(["adb", "-s", client_serial, "shell", "input", "tap",
                                str(TAP_X), str(TAP_Y)], capture_output=True, timeout=10)
            except subprocess.TimeoutExpired:
                pass
            stop_touch.wait(1.2)

    th = None
    if inject_touch:
        th = threading.Thread(target=_touch_loop, daemon=True)
        th.start()
    try:
        while time.time() < deadline:
            time.sleep(1)
    finally:
        stop_touch.set()
        _stop(pc)
        _stop(ps)
    return {"samples": samples, "enc": sorted(enc_buckets.values(), key=lambda b: b.epoch),
            "delays": delays, "disconnects": disconnects[0]}


def steady_stats(samples: list[Sample], enc: list[EncBucket], delays: list[tuple[float, int]],
                 steady: int) -> dict:
    """稳态窗口统计（后 steady 秒：掐启动瞬态）。编码器段只统计有样本的秒桶。"""
    if not samples:
        return {}
    t_end = max(s.epoch for s in samples)
    t_cut = t_end - steady
    ss = [s for s in samples if s.epoch >= t_cut]
    se = [b for b in enc if b.epoch >= t_cut]
    sd = [ms for (e, ms) in delays if e >= t_cut]
    out = {
        "steady_samples": len(ss),
        "sustained_kbps": statistics.fmean(s.bitrate_kbps for s in ss) if ss else -1.0,
        "fps_mean": statistics.fmean(s.fps for s in ss) if ss else -1.0,
        "loss_mean": statistics.fmean(s.loss_pct for s in ss) if ss else -1.0,
        "loss_max": max(s.loss_pct for s in ss) if ss else -1.0,
        "rtt_mean": statistics.fmean(s.rtt_ms for s in ss) if ss else -1.0,
        "fps_recv": len(sd) / steady,  # 客户端收帧率（解码到达帧计数 / 稳态秒数）
        "delay_p50": _pct(sd, 0.50),
        "delay_p95": _pct(sd, 0.95),
        "enc_out_mbps": (sum(b.bytes for b in se) * 8.0 / max(1, len(se)) / 1e6) if se else -1.0,
        "enc_fps": (sum(b.frames for b in se) / max(1, len(se))) if se else -1.0,
        "enc_max_gap_ms": max((b.max_gap_ms for b in se), default=-1),
        "enc_buckets": len(se),
    }
    return out


def _pct(values: list, p: float):
    if not values:
        return -1
    s = sorted(values)
    return s[min(len(s) - 1, int(len(s) * p))]


def launch_session(client_serial: str, backend: str, activity_short: str, target_kbps: int) -> None:
    """客户端应用入口自动化（复用 run_matrix 参数注入模式）"""
    rm.adb(client_serial, "shell", "am", "force-stop", "cloudphone.client")
    time.sleep(0.5)
    rm.adb(client_serial, "shell", "am", "start", "-n", "cloudphone.client/.ConnectActivity",
           "--es", "autoHost", BACKEND_HOSTS.get(backend, "10.0.2.2"), "--es", "autoBackend", backend,
           "--ei", "autoWidth", str(RES_W), "--ei", "autoHeight", str(RES_H),
           "--ei", "autoFps", str(RES_FPS), "--ei", "autoBitrate", str(target_kbps),
           "--es", "autoPackage", activity_short.removeprefix("cloudphone.loadgen/."))
    time.sleep(3)


def launch_load(source_serial: str, tier: str | None) -> None:
    """-S 强制重启负载 App（普通 am start 可能静默不切前台，run_matrix 实测）；entropy 档经 extra 下发"""
    if tier:
        rm.adb(source_serial, "shell", "am", "start", "-S", "-n", ACTIVITY_ENTROPY,
               "--es", "entropy", tier)
    else:
        rm.adb(source_serial, "shell", "am", "start", "-S", "-n", ACTIVITY_STATIC)
    time.sleep(2)


def wait_first_stats(client_serial: str, timeout: int = 45) -> bool:
    """等会话首条 stats（帧流已通），失败返回 False"""
    deadline = time.time() + timeout
    proc = subprocess.Popen(["adb", "-s", client_serial, "logcat", "-v", "epoch", "-s",
                             "cloudphone-stats"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    try:
        while time.time() < deadline:
            line = proc.stdout.readline()
            if not line:
                time.sleep(0.5)
                continue
            if STATS_RE.search(line.decode("utf-8", "replace")):
                return True
    finally:
        _stop(proc)
    return False


def run_group(source_serial: str, client_serial: str, backend: str, tier: str | None,
              duration: int, steady: int, baseline_seconds: int, kind: str,
              pstate: dict) -> StressGroup:
    """单组压测：空载基线（可选）→ 管线对齐 → 高熵负载会话 → 双端采集 → 稳态统计"""
    target = SESSION_TARGET_KBPS.get(tier or "mid", 10000)
    g = StressGroup(backend=backend, tier=tier or "-", duration=duration, kind=kind, target_kbps=target)

    # 空载基线（frp 必跑：VPS 中转上限 vs 家庭上行两因素归因；lan/p2p 也采，供 RTT 对比）
    if baseline_seconds > 0:
        print(f"[stress] ({backend}/{tier or 'static'}) 空载基线 {baseline_seconds}s …")
        if not rm.ensure_pipeline(source_serial, RES_W, RES_H, RES_FPS, target, pstate):
            g.verdict = "ERROR: 采集管线参数对齐失败（重授权超时）"
            return g
        launch_session(client_serial, backend, ACTIVITY_STATIC, target)
        launch_load(source_serial, None)
        if not wait_first_stats(client_serial):
            print(f"[stress] [warn] {backend} 空载基线无 stats（会话未通），基线记 -1")
        base = collect_window(source_serial, client_serial, baseline_seconds,
                              min(10, baseline_seconds), inject_touch=False)
        bss = steady_stats(base["samples"], [], base["delays"], min(10, baseline_seconds))
        g.rtt_baseline = bss.get("rtt_mean", -1.0)
        g.baseline_kbps = bss.get("sustained_kbps", -1.0)
        print(f"[stress] 基线: rtt={g.rtt_baseline:.1f}ms idle_kbps={g.baseline_kbps:.0f}")

    # 压测主体：高熵负载（或标定档）会话
    if not rm.ensure_pipeline(source_serial, RES_W, RES_H, RES_FPS, target, pstate):
        g.verdict = "ERROR: 采集管线参数对齐失败（重授权超时）"
        return g
    launch_session(client_serial, backend, ACTIVITY_ENTROPY, target)
    launch_load(source_serial, tier)
    if not wait_first_stats(client_serial):
        g.verdict = "ERROR: 会话 45s 内无 stats（帧流未通）"
        return g
    print(f"[stress] ({backend}/{tier or '-'}/{kind}) 采集 {duration}s …")
    data = collect_window(source_serial, client_serial, duration, steady)
    ss = steady_stats(data["samples"], data["enc"], data["delays"], steady)
    g.sustained_kbps = ss.get("sustained_kbps", -1.0)
    g.fps_mean = ss.get("fps_mean", -1.0)
    g.fps_recv = ss.get("fps_recv", -1.0)
    g.loss_mean = ss.get("loss_mean", -1.0)
    g.loss_max = ss.get("loss_max", -1.0)
    g.rtt_mean = ss.get("rtt_mean", -1.0)
    g.rtt_inflate_ms = (g.rtt_mean - g.rtt_baseline) if (g.rtt_mean >= 0 and g.rtt_baseline >= 0) else -1.0
    g.delay_p50 = ss.get("delay_p50", -1)
    g.delay_p95 = ss.get("delay_p95", -1)
    g.enc_out_mbps = ss.get("enc_out_mbps", -1.0)
    g.enc_fps = ss.get("enc_fps", -1.0)
    g.enc_max_gap_ms = ss.get("enc_max_gap_ms", -1)
    g.samples = ss.get("steady_samples", 0)
    g.disconnects = data["disconnects"]
    return g


# ── 判定与归因（design D2/D3：编码器 → 传输 → 网络 三段链式，两段分开判定）──────────

BITRATE_PASS_KBPS = 8000.0
FPS_PASS = 50.0
LOSS_PASS_PCT = 0.5
TRANSPORT_TRACK_TOL = 0.10   # 传输吐出速率与编码器输出一致判定容差（±10%）
RTT_INFLATE_TOL_MS = 20.0    # RTT 膨胀容差（压测 vs 空载基线）


def judge_group(g: StressGroup) -> StressGroup:
    """两段分开判定：
    - 编码器段：编码器输出持续 < 8Mbps 且输入帧率满（GL 60fps 场景在产）而传输吐出速率与
      编码器输出一致、RTT 无膨胀 → ENCODER_CAPPED（真机复核），网络段判定不受影响；
    - 网络段（frp/p2p 独立）：编码器未受限时 sustained ≥8Mbps & fps ≥50 & 丢帧 <0.5%；
      编码器受限时网络段达标口径 = 传输吐出速率跟上编码器输出（±10%）& 丢帧 <0.5% &
      RTT 膨胀 ≤ max(20ms, 2×基线)。lan-direct 为参照路径，只记数据不记 PASS/FAIL。"""
    track = (g.sustained_kbps >= 0 and g.enc_out_mbps >= 0 and
             abs(g.sustained_kbps - g.enc_out_mbps * 1000.0) <= max(g.enc_out_mbps * 1000.0,
                                                                    1.0) * TRANSPORT_TRACK_TOL)
    delivered = (g.fps_recv >= 0 and g.enc_fps >= 0 and
                 abs(g.fps_recv - g.enc_fps) <= max(g.enc_fps, 1.0) * TRANSPORT_TRACK_TOL)
    rtt_ok = (g.rtt_inflate_ms < 0 or  # 无基线（标定/探针组）时不判网络段 RTT
              g.rtt_inflate_ms <= max(RTT_INFLATE_TOL_MS, 2.0 * max(g.rtt_baseline, 0.0)))
    loss_ok = g.loss_max <= LOSS_PASS_PCT

    if g.enc_out_mbps > 0 and g.enc_out_mbps * 1000.0 < BITRATE_PASS_KBPS and track and delivered:
        g.encoder_capped = True

    if g.kind == "calibration" or g.kind == "probe":
        if g.encoder_capped:
            g.verdict = "ENCODER_CAPPED-真机复核"
            g.attribution = (f"编码器受限：high 熵内容下软编输出仅 {g.enc_out_mbps:.2f}Mbps@"
                             f"{g.enc_fps:.1f}fps（目标 {g.target_kbps}kbps），输出低于目标且传输跟出一致"
                             f"（{g.sustained_kbps:.0f}kbps）→ 编码器吞吐即第一瓶颈")
        else:
            g.verdict = "DATA"
            g.attribution = (f"编码器输出 {g.enc_out_mbps:.2f}Mbps@{g.enc_fps:.1f}fps，"
                             f"传输侧 sustained {g.sustained_kbps:.0f}kbps")
        return g

    if g.backend == "lan-direct":
        g.verdict = "REFERENCE"
        g.network_pass = None
        g.attribution = (f"参照路径：编码器段 {'ENCODER_CAPPED' if g.encoder_capped else '正常'}，"
                         f"传输 {g.sustained_kbps:.0f}kbps ≈ 编码器 {g.enc_out_mbps:.2f}Mbps，"
                         f"丢片 {g.loss_max:.2f}%，RTT 膨胀 {g.rtt_inflate_ms:.1f}ms")
        return g

    if g.encoder_capped:
        g.network_pass = bool(loss_ok and rtt_ok)
        g.verdict = "ENCODER_CAPPED-真机复核"
        g.attribution = (f"编码器受限（输出 {g.enc_out_mbps:.2f}Mbps@{g.enc_fps:.1f}fps < 8Mbps 目标，"
                         f"传输跟出 {g.sustained_kbps:.0f}kbps、收帧 {g.fps_recv:.1f}fps 与编码器一致）→ "
                         f"网络段独立判定：{'PASS' if g.network_pass else 'FAIL'}"
                         f"（丢片 {g.loss_max:.2f}%，RTT 膨胀 {g.rtt_inflate_ms:.1f}ms vs 基线 {g.rtt_baseline:.1f}ms）"
                         f"——码率不达标归因编码器吞吐而非网络故障，真机硬编复核列入检查表")
    else:
        g.network_pass = bool(g.sustained_kbps >= BITRATE_PASS_KBPS and g.fps_mean >= FPS_PASS and loss_ok)
        if g.network_pass:
            g.verdict = "PASS"
            g.attribution = (f"网络段达标：sustained {g.sustained_kbps:.0f}kbps ≥8Mbps、fps {g.fps_mean:.1f} ≥50、"
                             f"丢片 {g.loss_max:.2f}% <0.5%，RTT 膨胀 {g.rtt_inflate_ms:.1f}ms vs 基线 {g.rtt_baseline:.1f}ms")
        else:
            g.verdict = "FAIL"
            why = []
            if g.sustained_kbps < BITRATE_PASS_KBPS:
                why.append(f"sustained {g.sustained_kbps:.0f}kbps <8Mbps")
            if g.fps_mean < FPS_PASS:
                why.append(f"fps {g.fps_mean:.1f} <50")
            if not loss_ok:
                why.append(f"丢片 {g.loss_max:.2f}% ≥0.5%")
            g.attribution = (f"网络段不达标：{'; '.join(why)}；RTT 膨胀 {g.rtt_inflate_ms:.1f}ms "
                             f"vs 基线 {g.rtt_baseline:.1f}ms，空载吞吐 {g.baseline_kbps:.0f}kbps "
                             f"（VPS 中转上限 vs 家庭上行两因素见报告归因）")
    return g


def write_report(groups: list[StressGroup], path: str, steady: int) -> None:
    """写 docs/uplink-report.md 的数据段（标记之间整段替换，重复运行不叠加重复段落）"""
    begin, end = "<!-- uplink-stress:data-begin -->", "<!-- uplink-stress:data-end -->"
    lines = [
        begin,
        "## 压测矩阵数据（uplink_stress.py 自动生成）",
        "",
        f"生成时间：{time.strftime('%Y-%m-%d %H:%M:%S')}；口径 1080p@60fps，稳态窗口后 {steady}s，采样 1s 粒度。",
        "",
        "| 路径 | 档位 | 类别 | 稳态 sustained(kbps) | 编码器输出(Mbps) | 编码器 fps | 收帧 fps | 丢片率%(均值/最大) "
        "| RTT 压测/基线/膨胀(ms) | 空载吞吐(kbps) | 断流 | 判定 |",
        "|---|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for g in groups:
        lines.append(
            f"| {g.backend} | {g.tier} | {g.kind} | {g.sustained_kbps:.0f} | {g.enc_out_mbps:.2f} | "
            f"{g.enc_fps:.1f} | {g.fps_recv:.1f} | {g.loss_mean:.2f}/{g.loss_max:.2f} | "
            f"{g.rtt_mean:.1f}/{g.rtt_baseline:.1f}/{g.rtt_inflate_ms:.1f} | {g.baseline_kbps:.0f} | "
            f"{g.disconnects} | {g.verdict} |"
        )
    lines += ["", "### 逐组归因结论", ""]
    for g in groups:
        lines.append(f"- **{g.backend}/{g.tier}**（{g.kind}）：{g.attribution}")
    lines.append(end)

    try:
        existing = open(path, encoding="utf-8").read()
    except OSError:
        existing = ""
    if begin in existing and end in existing:
        pre = existing.split(begin)[0]
        post = existing.split(end, 1)[1]
        out = pre + "\n".join(lines) + post
    else:
        out = (existing.rstrip() + "\n\n" if existing.strip() else "") + "\n".join(lines) + "\n"
    with open(path, "w", encoding="utf-8") as f:
        f.write(out)


def main() -> int:
    ap = argparse.ArgumentParser(description="1080p 高熵负载三网络上行压测（gaming-uplink-1080p）")
    ap.add_argument("--serials", default="emulator-5554,emulator-5556")
    ap.add_argument("--duration", type=int, default=120, help="每组压测时长（s）")
    ap.add_argument("--steady", type=int, default=60, help="稳态窗口（后 N 秒）")
    ap.add_argument("--baseline-seconds", type=int, default=20, help="空载基线时长（0=跳过）")
    ap.add_argument("--backends", default="lan-direct,frp,p2p")
    ap.add_argument("--tier", default="mid", choices=["low", "mid", "high"])
    ap.add_argument("--calibrate", default="", help="档位标定：逗号分隔档位（lan-direct 60s/档）")
    ap.add_argument("--high-probe", action="store_true", help="编码器极限探针（lan-direct high）")
    ap.add_argument("--json", default="docs/uplink-stress-results.json")
    ap.add_argument("--report", default="docs/uplink-report.md")
    args = ap.parse_args()
    source_serial, client_serial = args.serials.split(",")

    groups: list[StressGroup] = []
    pstate: dict = {}

    def _emit(g: StressGroup) -> None:
        judge_group(g)
        groups.append(g)
        with open(args.json + ".progress.json", "w", encoding="utf-8") as f:
            json.dump([asdict(x) for x in groups], f, ensure_ascii=False, indent=1)

    if args.calibrate:
        cal_duration = max(70, args.duration)  # 标定稳态窗口 ≥60s（SPEC 稳态口径）
        for tier in [t.strip() for t in args.calibrate.split(",") if t.strip()]:
            print(f"[stress] 标定档 {tier}（lan-direct {cal_duration}s, target={SESSION_TARGET_KBPS[tier]}kbps）")
            g = run_group(source_serial, client_serial, "lan-direct", tier, cal_duration, 60,
                          0, "calibration", pstate)
            print(f"[stress] → {tier}: enc={g.enc_out_mbps:.2f}Mbps enc_fps={g.enc_fps:.1f} "
                  f"sustained={g.sustained_kbps:.0f}kbps")
            _emit(g)
    if args.high_probe:
        print("[stress] 编码器极限探针（lan-direct/high/20000kbps）")
        g = run_group(source_serial, client_serial, "lan-direct", "high", min(60, args.duration),
                      min(40, args.steady), 0, "probe", pstate)
        print(f"[stress] → 探针: enc={g.enc_out_mbps:.2f}Mbps enc_fps={g.enc_fps:.1f}")
        _emit(g)
    for backend in [b.strip() for b in args.backends.split(",") if b.strip()]:
        if backend not in BACKEND_HOSTS:
            print(f"[stress] [warn] 未知路径 {backend}，跳过（可用 {list(BACKEND_HOSTS)}）")
            continue
        print(f"[stress] 压测组 {backend}/{args.tier}（{args.duration}s）")
        g = run_group(source_serial, client_serial, backend, args.tier, args.duration, args.steady,
                      args.baseline_seconds, "stress", pstate)
        print(f"[stress] → {backend}: sustained={g.sustained_kbps:.0f}kbps fps={g.fps_mean:.1f} "
              f"enc={g.enc_out_mbps:.2f}Mbps rtt={g.rtt_mean:.1f}ms(基线{g.rtt_baseline:.1f})")
        _emit(g)

    with open(args.json, "w", encoding="utf-8") as f:
        json.dump({"spec": "gaming-uplink-1080p", "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
                   "params": {"res": f"{RES_W}x{RES_H}@{RES_FPS}", "steady": args.steady,
                              "duration": args.duration, "targets": SESSION_TARGET_KBPS},
                   "groups": [asdict(g) for g in groups]}, f, ensure_ascii=False, indent=1)
    write_report(groups, args.report, args.steady)
    print(f"[stress] 结果 → {args.json}（{len(groups)} 组）；报告数据段 → {args.report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
