#!/usr/bin/env python3
"""run_full_suite.py — 全量回归唯一许可入口（client-ux-and-input-latency 6.1）。

边界与语义：
  - 本脚本是有界运行器：逐阶段超时保护（覆盖输出读取，超时即 kill 并回收），
    单阶段超时不拖垮整批，各阶段超时按剩余整批预算动态收缩；
  - 覆盖：protocol 模块单测（JVM 级，含 APP_LIST 编解码）、三模块 Android 构建、
    （--with-device 时）三后端连接冒烟 + 矩阵关键组合（lan game 720p）；
  - 结果写 build/full-suite-report.json（阶段级 PASS/FAIL/SKIP + 摘要）；
  - --baseline <json>：已存在失败分类基线（键=阶段名，须与阶段名精确相等，
    值=已知失败原因列表），命中基线的失败标记为 known-fail（不计入退出码失败）。

用法：
  python3 bin/run_full_suite.py --timeout 3600
  python3 bin/run_full_suite.py --timeout 3600 --with-device \
      --source-serial emulator-5554 --client-serial emulator-5556
"""
import argparse
import faulthandler
import json
import os
import re
import subprocess
import sys
import threading
import time
from collections import deque
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPORT = os.path.join(ROOT, "build", "full-suite-report.json")

# 环境特定端点/路径：默认值维持原字面量，可经 CLI 参数（--frp-host 等）或同名环境变量覆盖，
# 换环境（换出口 IP / 无外网 STUN / JDK 升级换路径）无需改源码
FRP_HOST = os.environ.get("FRP_HOST", "112.74.85.74")
FRP_PORT = os.environ.get("FRP_PORT", "22333")
STUN_HOST = os.environ.get("STUN_HOST", "stun.l.google.com")
STUN_PORT = os.environ.get("STUN_PORT", "19302")
JDK_HOME = os.environ.get("JAVA_HOME", "/opt/jdk/jdk-17.0.20.1+1")


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
            errors="replace",  # 非 UTF-8 字节以 U+FFFD 代替，不让运行器崩溃
        )
    except FileNotFoundError as e:
        phase.status, phase.detail = "FAIL", f"启动失败: {e}"
        return classify(phase, baseline)

    tail = deque(maxlen=400)  # 只保留尾部 N 行，内存上界恒定
    n_seen = 0
    timed_out = False

    def _drain():
        # 输出读取放在 daemon 线程：子进程持管道挂死也不再阻塞主线程的超时判定
        nonlocal n_seen
        try:
            for line in proc.stdout:
                n_seen += 1
                tail.append(line.rstrip())
                if n_seen <= 400:
                    print("  " + line.rstrip(), flush=True)
        except (ValueError, OSError):
            pass  # 主线程收尾关闭管道后的残余 I/O 错误

    reader = threading.Thread(target=_drain, daemon=True)
    reader.start()
    # 超时保护覆盖子进程整个生命周期（含输出读取）：主线程只 wait(timeout)
    try:
        proc.wait(timeout=phase.timeout_s)
    except subprocess.TimeoutExpired:
        timed_out = True
        proc.kill()
        try:
            proc.wait(timeout=10)  # kill 后回收子进程，避免僵尸
        except subprocess.TimeoutExpired:
            pass  # 极端不可中断态；daemon 读线程不阻塞主流程退出
    reader.join(timeout=5)
    output_ready = not reader.is_alive()
    if not output_ready:
        # 读线程 join 超时仍在写 tail：放弃本次输出采集，避免与读线程并发读写
        tail = None
    try:
        proc.stdout.close()
    except (OSError, ValueError):
        pass
    phase.seconds = round(time.time() - t0, 1)
    if not timed_out:
        if output_ready:
            out = "\n".join(tail)
            if proc.returncode == 0:
                if any("[SKIP-ENV]" in ln for ln in tail):
                    # 冒烟探测环境不可达：脚本 exit 0 但带 [SKIP-ENV] 标记 → 记 SKIP
                    phase.status = "SKIP"
                    phase.detail = "环境不可达，未验证\n" + out[-500:]
                else:
                    phase.status, phase.detail = "PASS", out[-500:]
            else:
                phase.status, phase.detail = "FAIL", f"exit={proc.returncode}\n{out[-1500:]}"
        else:
            phase.status = "PASS" if proc.returncode == 0 else "FAIL"
            phase.detail = "输出采集未完成（读线程超时未退出）"
            if proc.returncode != 0:
                phase.detail += f"，exit={proc.returncode}"
    else:
        detail = f"超时（>{phase.timeout_s}s，已 kill）"
        if output_ready and tail:
            detail += "\n" + "\n".join(tail)[-500:]  # 保留卡点前最后的输出供排障
        phase.status, phase.detail = "FAIL", detail
    return classify(phase, baseline)


def classify(phase, baseline):
    if phase.status == "FAIL" and baseline:
        for key, reasons in baseline.items():
            if key == phase.name and any(
                    isinstance(r, str) and r in phase.detail for r in reasons):
                phase.known_fail = True
                phase.status = "known-fail"
                break
    print(f"=== [{phase.name}] {phase.status} ({phase.seconds}s) ===", flush=True)
    return phase


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--timeout", type=int, default=3600,
                    help="整批预算（秒）；各阶段超时按剩余预算动态收缩")
    ap.add_argument("--with-device", action="store_true",
                    help="追加设备在线阶段：3 后端连接冒烟 + 矩阵关键组合")
    ap.add_argument("--source-serial", default="emulator-5554")
    ap.add_argument("--client-serial", default="emulator-5556")
    ap.add_argument("--frp-host", default=FRP_HOST,
                    help="frp 公网入口主机（env: FRP_HOST）")
    ap.add_argument("--frp-port", default=FRP_PORT,
                    help="frp 公网入口端口（env: FRP_PORT）")
    ap.add_argument("--stun-host", default=STUN_HOST,
                    help="公网 STUN 服务器主机（env: STUN_HOST）")
    ap.add_argument("--stun-port", default=STUN_PORT,
                    help="公网 STUN 服务器端口（env: STUN_PORT）")
    ap.add_argument("--jdk-home", default=JDK_HOME,
                    help="protocol 单测 JAVA_HOME 兜底默认值（env: JAVA_HOME）")
    ap.add_argument("--baseline", default=None,
                    help="已知失败分类 JSON（键=阶段名，须与阶段名精确相等；值=已知失败原因列表）")
    args = ap.parse_args()

    if args.timeout <= 0:
        print(f"错误: --timeout 必须为正整数（秒），收到: {args.timeout}",
              file=sys.stderr, flush=True)
        sys.exit(2)
    # serial 只允许安全字符集，防止 shell 元字符经阶段命令注入任意命令
    for opt in ("source_serial", "client_serial"):
        serial = getattr(args, opt)
        if not re.fullmatch(r"[\w.:\-]+", serial):
            print(f"错误: --{opt.replace('_', '-')} 含非法字符: {serial!r}"
                  f"（仅允许字母/数字/下划线/点/冒号/连字符）",
                  file=sys.stderr, flush=True)
            sys.exit(2)

    baseline = {}
    if args.baseline:
        if not os.path.exists(args.baseline):
            print(f"警告: baseline 文件不存在: {args.baseline}（按空基线继续，"
                  f"known-fail 将全部计为真 FAIL）", file=sys.stderr, flush=True)
        else:
            try:
                with open(args.baseline) as f:
                    baseline = json.load(f)
            except (json.JSONDecodeError, OSError, ValueError) as e:
                print(f"错误: baseline JSON 解析失败 {args.baseline}: {e}",
                      file=sys.stderr, flush=True)
                sys.exit(2)
            if not isinstance(baseline, dict) or not all(
                    isinstance(reasons, list)
                    and all(isinstance(r, str) for r in reasons)
                    for reasons in baseline.values()):
                print(f"错误: baseline 结构不合规 {args.baseline}: "
                      f"顶层须为对象，且每个值为字符串列表",
                      file=sys.stderr, flush=True)
                sys.exit(2)

    per_phase = max(60, args.timeout // 3)
    phases = [
        Phase("protocol-unit-tests",
              ["bash", "-c",
               f"cd protocol && JAVA_HOME=${{JAVA_HOME:-{args.jdk_home}}} "
               "./gradlew --console=plain test --tests 'cloudphone.protocol.*' -q"],
              ROOT, per_phase),
        Phase("build-protocol",
              ["bash", "-c", "cd protocol && ./gradlew --console=plain assemble -q"], ROOT, per_phase),
        Phase("build-source",
              ["bash", "-c", "cd source && ./gradlew --console=plain assembleDebug -q"], ROOT, per_phase),
        Phase("build-client",
              ["bash", "-c", "cd client && ./gradlew --console=plain assembleDebug -q"], ROOT, per_phase),
        # windows-desktop-client SPEC：桌面客户端构建产物与模块单测（98 例，含 FFmpeg 合成流解码）
        Phase("build-client-windows",
              ["bash", "-c", "cd client-windows && ./gradlew --console=plain createDistributable -q"], ROOT, per_phase),
        Phase("client-windows-unit-tests",
              ["bash", "-c", "cd client-windows && ./gradlew --console=plain test -q"], ROOT, per_phase),
    ]
    if args.with_device:
        phases += [
            Phase("smoke-lan-direct",
                  ["python3", "scripts/applist_wire_probe.py"],
                  ROOT, per_phase),
            Phase("matrix-lan-game-720p",
                  ["bash", "-c",
                   # pipefail：管道以 tail 收尾会吞掉 run_matrix.py 的非零退出码
                   "set -o pipefail; "
                   f"python3 scripts/run_matrix.py --serials {args.source_serial},{args.client_serial} "
                   "--only lan-direct,game,720p 2>&1 | tail -20"],
                  ROOT, per_phase),
            Phase("smoke-frp",
                  ["bash", "-c",
                   "python3 - <<'PY'\n"
                   "import socket,struct,time,sys\n"
                   "# frp 公网入口 TCP 冒烟：HELLO 应答即通（token 校验在服务端）\n"
                   "try:\n"
                   f"    s=socket.create_connection(('{args.frp_host}',{args.frp_port}),timeout=5)\n"
                   "    s.close(); print('frp tcp 入口可达')\n"
                   "except Exception as e:\n"
                   "    print('[SKIP-ENV] frp 入口不可达（宿主 frpc 未运行），未验证:',e); sys.exit(0)\n"
                   "PY"],
                  ROOT, per_phase),
            Phase("smoke-p2p",
                  ["bash", "-c",
                   "python3 - <<'PY'\n"
                   "# p2p 冒烟：STUN 可达性（公网 STUN 服务器 UDP 探测，不依赖对端在线）\n"
                   "import socket, os, struct\n"
                   "s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.settimeout(3)\n"
                   "txid=os.urandom(12)\n"
                   f"s.sendto(b'\\x00\\x01\\x00\\x00'+txid,('{args.stun_host}',{args.stun_port}))\n"
                   "try:\n"
                   "    d,_=s.recvfrom(256); print('STUN 应答', len(d),'B → p2p 打洞前置可达')\n"
                   "except socket.timeout:\n"
                   "    print('[SKIP-ENV] STUN 超时（宿主 UDP 出网受限），未验证'); raise SystemExit(0)\n"
                   "PY"],
                  ROOT, per_phase),
        ]

    # 整批预算：逐阶段超时按剩余预算动态收缩，预算耗尽后的阶段记 SKIP 不再执行，
    # 保证多阶段串行总耗时不突破 --timeout
    deadline = time.monotonic() + args.timeout
    results = []
    for p in phases:
        remaining = int(deadline - time.monotonic())
        if remaining <= 0:
            p.status, p.detail = "SKIP", "整批预算耗尽，未执行"
            results.append(classify(p, baseline))
            continue
        p.timeout_s = min(p.timeout_s, remaining)
        results.append(run_phase(p, baseline))
    passed = sum(1 for r in results if r.status == "PASS")
    known = sum(1 for r in results if r.status == "known-fail")
    failed = sum(1 for r in results if r.status == "FAIL")
    skipped = sum(1 for r in results if r.status == "SKIP")
    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
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
