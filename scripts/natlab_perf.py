#!/usr/bin/env python3
"""natlab 性能口径：1080p mid 档（1920x1080@30fps≈8000kbps → 每帧 33_333B 合成载荷）
加密开（AES-128-GCM）vs 关（none）端到端对比——真实 transport 栈（P2P 直连 UDP 路径）
上的 fps / 码率（应用层 + 线路含 tag）/ RTT（1Hz PING/PONG）测量。

实验室口径（docs/device-notes.md §8.3）：同宿主 netns 直连路径，时延数字不含真实公网 RTT；
本实验量化的是加密层在同负载下的吞吐/RTT 开销，非真实外网数据。

用法：python3 scripts/natlab_perf.py [--seconds 12] [--rounds 2] [--data-out docs/natlab-perf.json]
前提：natlab 拓扑已可重建（脚本自行 setup up/down；需要 root netns 权限）。
"""
import argparse
import json
import statistics
import sys
import time

sys.path.insert(0, 'scripts')
import natlab_run as nr

# 1080p mid 档：8_000_000 bps ÷ 8 ÷ 30 fps ≈ 33_333 B/帧（docs/protocol.md §3.3 目标档位语义）
MID_1080P_FPS = 30
MID_1080P_FRAME_BYTES = 33_333


def run_arm(encrypt, stuns, seconds, payload_bytes, fps):
    """跑一轮 ei×ei P2P 直连（打洞最快路径），返回（stats 事件列表, pongs, transport）。"""
    enc_args = [] if encrypt else ["--no-encrypt"]
    src = nr.Peer(nr.NS_SRV, [
        "--mode", "source", "--frp-host", nr.SRV_GW, *nr.STUN_ARGS,
        "--udp-port", "0", "--fps", str(fps), "--payload-bytes", str(payload_bytes),
        "--timeout", str(int(seconds * 1000) + 8000),
    ], "source")
    src.wait_event("listening", 8)
    cli = nr.Peer(nr.NS_CL, [
        "--mode", "client", "--frp-host", nr.SRV_GW, *nr.STUN_ARGS, "--perf", *enc_args,
        # payload 尺寸两端一致（client 侧 stats 用它换算码率；帧载荷由 source 泵出）
        "--payload-bytes", str(payload_bytes),
        # frames 目标设为不可达：会话运行到超时，覆盖完整测量窗口
        "--frames", "1000000", "--timeout", str(int(seconds * 1000) + 6000),
    ], "client")
    cli.wait_event("session_started", 25)
    punch = cli.wait_event("punch_established", 6)
    transport = "direct" if punch else "fallback"
    stats, pongs = [], []
    t0 = time.time()
    while time.time() - t0 < seconds + 6:
        for e in cli.drain():
            if e.get("event") == "stats":
                stats.append(e)
            elif e.get("event") == "pong":
                pongs.append(e.get("rtt_ms"))
        if cli.p.poll() is not None:
            break
        time.sleep(0.3)
    cli.kill()
    src.kill()
    return stats, pongs, transport


def summarize(stats, pongs, encrypt, transport):
    """丢弃前 2 个统计窗口（预热：打洞残留 + TCP→UDP 切换），汇总稳态。"""
    steady = stats[2:]
    fps = [s["fps"] for s in steady]
    app = [s["bitrate_kbps"] for s in steady]
    wire = [s["wire_bitrate_kbps"] for s in steady]
    return {
        "crypto": "AES128GCM" if encrypt else "none",
        "transport": transport,
        "windows": len(steady),
        "fps_mean": round(statistics.mean(fps), 1) if fps else None,
        "fps_min": min(fps) if fps else None,
        "app_bitrate_kbps_mean": round(statistics.mean(app), 0) if app else None,
        "wire_bitrate_kbps_mean": round(statistics.mean(wire), 0) if wire else None,
        "rtt_ms_mean": round(statistics.mean(pongs), 2) if pongs else None,
        "rtt_ms_p95": round(sorted(pongs)[int(len(pongs) * 0.95) - 1], 2) if len(pongs) >= 2 else None,
    }


def main():
    ap = argparse.ArgumentParser(description="natlab 1080p mid 加密开/关性能对比（实验室口径）")
    ap.add_argument("--seconds", type=float, default=12.0)
    ap.add_argument("--rounds", type=int, default=2)
    ap.add_argument("--data-out", default="docs/natlab-perf.json")
    args = ap.parse_args()

    nr.sh_setup("up")
    nr.sh_setup("profile", "ei", "both")  # ei×ei 直连路径（up 只建拓扑，NAT 档需显式套用）
    stuns = nr.run_stun()
    data = {"sizing": {"fps": MID_1080P_FPS, "frame_bytes": MID_1080P_FRAME_BYTES,
                       "app_bitrate_kbps": MID_1080P_FRAME_BYTES * 8 * MID_1080P_FPS // 1000},
            "arms": []}
    try:
        for encrypt in (True, False):
            for r in range(args.rounds):
                stats, pongs, transport = run_arm(encrypt, stuns, args.seconds, MID_1080P_FRAME_BYTES, MID_1080P_FPS)
                s = summarize(stats, pongs, encrypt, transport)
                s["round"] = r + 1
                data["arms"].append(s)
                print(f"  [{'加密开' if encrypt else '加密关'}] round{r + 1}: fps={s['fps_mean']} "
                      f"app={s['app_bitrate_kbps_mean']}kbps wire={s['wire_bitrate_kbps_mean']}kbps "
                      f"rtt={s['rtt_ms_mean']}ms p95={s['rtt_ms_p95']}ms")
    finally:
        for s in stuns:
            s.kill()
        nr.sh_setup("down")

    def agg(encrypt):
        rows = [a for a in data["arms"] if (a["crypto"] == "AES128GCM") == encrypt]
        keys = ("fps_mean", "app_bitrate_kbps_mean", "wire_bitrate_kbps_mean", "rtt_ms_mean", "rtt_ms_p95")
        return {k: round(statistics.mean([r[k] for r in rows if r[k] is not None]), 2) for k in keys}

    if data["arms"]:
        on, off = agg(True), agg(False)
        data["summary"] = {
            "crypto_on": on, "crypto_off": off,
            "delta": {k: round(on[k] - off[k], 2) for k in on},
        }
        print(f"开: {data['summary']['crypto_on']}")
        print(f"关: {data['summary']['crypto_off']}")
        print(f"差值: {data['summary']['delta']}")
    with open(args.data_out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    print(f"数据文件：{args.data_out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
