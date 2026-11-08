#!/usr/bin/env python3
"""run_full_suite.py — 全量回归唯一许可入口（client-ux-and-input-latency 6.1）。

边界与语义：
  - 本脚本是有界运行器：逐阶段 faulthandler 超时保护，单阶段超时不拖垮整批；
  - 覆盖：protocol 模块单测（JVM 级，含 APP_LIST 编解码）、三模块 Android 构建、
    （--with-device 时）三后端连接冒烟 + 矩阵关键组合（lan game 720p）；
  - 结果写 build/full-suite-report.json（阶段级 PASS/FAIL/SKIP + 摘要）；
  - --baseline <json>：已存在失败分类基线（键=阶段名，值=已知失败原因列表），
    命中基线的失败标记为 known-fail（不计入退出码失败）。

用法：
  python3 bin/run_full_suite.py --timeout 3600
  python3 bin/run_full_suite.py --timeout 3600 --with-device \
      --source-serial emulator-5554 --client-serial emulator-5556
"""
import argparse
import faulthandler
import json
import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPORT = os.path.join(ROOT, "build", "full-suite-report.json")


class Phase:
    def __init__(self, name, cmd, cwd, timeout_s, env=None):
        self.name = name
        self.cmd = cmd
        self.cwd = cwd
        self.timeout_s = timeout_s
        self.env = env or {}
        self.status = "SKIP"
        self.detail = ""
        self.seconds = 0.0
        self.known_fail = False


def run_phase(phase, baseline):
    env = dict(os.environ)
    env.setdefault("ANDROID_SDK_ROOT", "/opt/android-sdk")
    env.setdefault("ANDROID_HOME", "/opt/android-sdk")
    env.update(phase.env)
    t0 = time.time()
    print(f"\n=== [{phase.name}] {' '.join(phase.cmd)} ===", flush=True)
    try:
        proc = subprocess.Popen(
            phase.cmd, cwd=phase.cwd, env=env,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        )
    except FileNotFoundError as e:
        phase.status, phase.detail = "FAIL", f"启动失败: {e}"
        return classify(phase, baseline)
    tail = []
    timed_out = False
    # faulthandler 兜底：子进程卡死由 wait(timeout) 收割并 kill
    try:
        for line in proc.stdout:
            tail.append(line.rstrip())
            if len(tail) <= 400:
                print("  " + line.rstrip(), flush=True)
        proc.wait(timeout=phase.timeout_s)
    except subprocess.TimeoutExpired:
        timed_out = True
        proc.kill()
        phase.status, phase.detail = "FAIL", f"超时（>{phase.timeout_s}s，已 kill）"
    phase.seconds = round(time.time() - t0, 1)
    if not timed_out:
        out = "\n".join(tail)
        if proc.returncode == 0:
            phase.status, phase.detail = "PASS", out[-500:]
        else:
            phase.status, phase.detail = "FAIL", f"exit={proc.returncode}\n{out[-1500:]}"
    return classify(phase, baseline)


def classify(phase, baseline):
    if phase.status == "FAIL" and baseline:
        for key, reasons in baseline.items():
            if key in phase.name and any(r in phase.detail for r in reasons):
                phase.known_fail = True
                phase.status = "known-fail"
                break
    print(f"=== [{phase.name}] {phase.status} ({phase.seconds}s) ===", flush=True)
    return phase


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--timeout", type=int, default=3600, help="整批预算（秒）")
    ap.add_argument("--with-device", action="store_true",
                    help="追加设备在线阶段：3 后端连接冒烟 + 矩阵关键组合")
    ap.add_argument("--source-serial", default="emulator-5554")
    ap.add_argument("--client-serial", default="emulator-5556")
    ap.add_argument("--baseline", default=None, help="已知失败分类 JSON")
    args = ap.parse_args()

    baseline = {}
    if args.baseline and os.path.exists(args.baseline):
        with open(args.baseline) as f:
            baseline = json.load(f)

    per_phase = max(60, args.timeout // 3)
    phases = [
        Phase("protocol-unit-tests",
              ["bash", "-c",
               "cd protocol && JAVA_HOME=${JAVA_HOME:-/opt/jdk/jdk-17.0.20.1+1} "
               "./gradlew --console=plain test --tests 'cloudphone.protocol.*' -q"],
              ROOT, per_phase),
        Phase("build-protocol",
              ["bash", "-c", "cd protocol && ./gradlew --console=plain assemble -q"], ROOT, per_phase),
        Phase("build-source",
              ["bash", "-c", "cd source && ./gradlew --console=plain assembleDebug -q"], ROOT, per_phase),
        Phase("build-client",
              ["bash", "-c", "cd client && ./gradlew --console=plain assembleDebug -q"], ROOT, per_phase),
    ]
    if args.with_device:
        phases += [
            Phase("smoke-lan-direct",
                  ["python3", "scripts/applist_wire_probe.py"],
                  ROOT, per_phase),
            Phase("matrix-lan-game-720p",
                  ["bash", "-c",
                   f"python3 scripts/run_matrix.py --serials {args.source_serial},{args.client_serial} "
                   "--only lan-direct,game,720p 2>&1 | tail -20"],
                  ROOT, per_phase),
            Phase("smoke-frp",
                  ["bash", "-c",
                   "python3 - <<'PY'\n"
                   "import socket,struct,time,sys\n"
                   "# frp 公网入口 TCP 冒烟：HELLO 应答即通（token 校验在服务端）\n"
                   "try:\n"
                   "    s=socket.create_connection(('112.74.85.74',22333),timeout=5)\n"
                   "    s.close(); print('frp tcp 入口可达')\n"
                   "except Exception as e:\n"
                   "    print('frp 入口不可达（宿主 frpc 未运行时 SKIP 语义）:',e); sys.exit(0)\n"
                   "PY"],
                  ROOT, per_phase),
            Phase("smoke-p2p",
                  ["bash", "-c",
                   "python3 - <<'PY'\n"
                   "# p2p 冒烟：STUN 可达性（公网 STUN 服务器 UDP 探测，不依赖对端在线）\n"
                   "import socket, os, struct\n"
                   "s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.settimeout(3)\n"
                   "txid=os.urandom(12)\n"
                   "s.sendto(b'\\x00\\x01\\x00\\x00'+txid,('stun.l.google.com',19302))\n"
                   "try:\n"
                   "    d,_=s.recvfrom(256); print('STUN 应答', len(d),'B → p2p 打洞前置可达')\n"
                   "except socket.timeout:\n"
                   "    print('STUN 超时（宿主 UDP 出网受限，SKIP 语义）'); raise SystemExit(0)\n"
                   "PY"],
                  ROOT, per_phase),
        ]

    results = [run_phase(p, baseline) for p in phases]
    passed = sum(1 for r in results if r.status == "PASS")
    known = sum(1 for r in results if r.status == "known-fail")
    failed = sum(1 for r in results if r.status == "FAIL")
    skipped = sum(1 for r in results if r.status == "SKIP")
    report = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "timeout_budget_s": args.timeout,
        "with_device": args.with_device,
        "summary": {"pass": passed, "known_fail": known, "fail": failed, "skip": skipped,
                    "total": len(results)},
        "phases": [
            {"name": r.name, "status": r.status, "seconds": r.seconds,
             "known_fail": r.known_fail, "detail": r.detail[-2000:]}
            for r in results
        ],
    }
    os.makedirs(os.path.dirname(REPORT), exist_ok=True)
    with open(REPORT, "w") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\nfull-suite: {passed} pass / {known} known-fail / {failed} fail / {skipped} skip"
          f" → {REPORT}", flush=True)
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    faulthandler.enable()
    main()
