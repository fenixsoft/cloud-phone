#!/usr/bin/env python3
"""natlab_run.py — 3×3 NAT 组合矩阵打洞验证（p2p-crossnet-traversal 4.3）

在 scripts/natlab_setup.sh 建好的 netns 拓扑里，拉起两台 JVM 协议级对端
（protocol 库直跑真实 transport 栈，见 protocol/src/main/kotlin/cloudphone/natlab/NatlabPeer.kt），
按 NAT 档位组合矩阵执行打洞验证，输出各组合成功率与平均耗时表，数据落盘供回归对比。

覆盖场景（对应 spec Scenario）：
  1. 3×3 矩阵（client 档 × server 档 ∈ {ei, ap-rr, symmetric}，默认每组合 2 轮），
     每轮给出明确结论：direct（直连打洞成功）/ prediction（端口预测命中直连）/
     frp_fallback（打洞失败 FRP 中转兜底，会话仍建立）/ fail（会话未建立）。
  2. 打洞增强开关对比：symmetric 相关组合在 --no-prediction（预测禁用）下复跑对比。
  3. 端点迁移：ei×ei 直连建立后，改 client netns 出口 IP（10.200.1.2→10.200.1.3）
     并重应用档位规则，客户端帧看门狗断流检测 → 重建控制面 → 重新打洞 → 视频恢复，
     校验 sessionId 不变、源端帧序列连续（统计不重置）、恢复后继续收帧。

无 adb 依赖（4.4）：全程纯 JVM 对端 + netns，adb 缺失或 devices 为空不影响任何步骤。

前置：protocol installDist 已构建（gradle :protocol:test 同目录 `gradle installDist`）。
用法：
  python3 scripts/natlab_run.py                    # 全量（矩阵+预测对比+迁移）
  --rounds N          每组合轮数（默认 2）
  --profiles a,b,c    限定档位子集（默认 ei,ap-rr,symmetric；prediction_toggle 固定追加
                      sym-seq 作业，不受子集影响）
  --client-timeout S  单轮客户端超时秒（默认 15；迁移场景自动加倍）
  --data-out PATH     数据文件（默认 docs/natlab-matrix.json）
  --keep              收尾不拆拓扑（默认 natlab_setup.sh down）
  --skip-setup        跳过建拓扑（假定 natlab_setup.sh up 已执行）
  --skip-matrix       只跑迁移/开关场景
  --skip-migration    跳过迁移场景
"""

import argparse
import json
import os
import signal
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SETUP = os.path.join(REPO, "scripts", "natlab_setup.sh")
PEER_BIN = os.path.join(REPO, "protocol", "build", "install", "natlab-peer", "bin", "natlab-peer")

NS_CL, NS_SRV, NS_WAN = "natlab-cl", "natlab-srv", "natlab-wan"
WAN_IP, STUN_PORT = "10.200.100.1", 3478
STUN_PORT2 = 3479              # 同 IP 双端口节点 → symmetric 映射判定材料（distinct 端口对）
STUN_ARGS = ["--stun", f"{WAN_IP}:{STUN_PORT}", "--stun", f"{WAN_IP}:{STUN_PORT2}"]
SRV_GW = "10.200.2.1"          # 源端 FRP 静态转发入口（两对端 --frp-host）
CL_PEER, CL_PEER_NEW = "10.200.1.2", "10.200.1.3"
IF_CL_P = "vcl-peer"
PROFILES = ["ei", "ap-rr", "symmetric"]


def sh_setup(*args):
    r = subprocess.run([SETUP, *args], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"natlab_setup.sh {' '.join(args)} 失败：{r.stderr.strip() or r.stdout.strip()}")
    return r.stdout.strip()


def netns_run(ns, *cmd):
    return subprocess.run(["ip", "netns", "exec", ns, *cmd], capture_output=True, text=True)


class Peer:
    """natlab-peer JVM 进程包装：stdout JSON-line 事件流采集。"""

    def __init__(self, ns, args, label):
        self.label = label
        self.events = []
        self._cursor = 0
        self._cv = threading.Condition()
        cmd = ["ip", "netns", "exec", ns, PEER_BIN, *args]
        self.p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                  errors="replace")  # 非 UTF-8 字节以 U+FFFD 代替，不让采集线程崩溃（同 bin/run_full_suite.py）
        self._t = threading.Thread(target=self._read, daemon=True)
        self._t.start()

    def _read(self):
        try:
            for line in self.p.stdout:
                line = line.strip()
                if not line.startswith("{"):
                    continue
                try:
                    ev = json.loads(line)
                except ValueError:
                    continue
                with self._cv:
                    self.events.append(ev)
                    self._cv.notify_all()
        except Exception as e:
            # 采集线程异常退出前补一条本地错误事件，避免事件流中断无痕
            with self._cv:
                self.events.append({"event": "error", "reader_error": f"stdout 采集线程异常退出：{e!r}"})
                self._cv.notify_all()

    def drain(self):
        """返回自上次 drain 以来的新事件（读侧游标推进），供性能采集等消费方使用。"""
        with self._cv:
            new = self.events[self._cursor:]
            self._cursor = len(self.events)
            return list(new)

    def snapshot(self):
        """当前全部事件的加锁副本：外部消费方一律走此接口，不直接访问 events。"""
        with self._cv:
            return list(self.events)

    def is_running(self):
        """对端进程是否仍在运行。"""
        return self.p.poll() is None

    def wait_event(self, name, timeout):
        deadline = time.time() + timeout
        with self._cv:
            while True:
                for ev in self.events:
                    if ev.get("event") == name:
                        return ev
                remain = deadline - time.time()
                if remain <= 0:
                    return None
                self._cv.wait(remain)

    def kill(self):
        if self.p.poll() is None:
            try:
                self.p.send_signal(signal.SIGKILL)
            except OSError:
                pass
        try:
            self.p.wait(timeout=3)
        except subprocess.TimeoutExpired:
            pass


def _parse_trace(raw):
    """punch_trace 在 JSON-line 事件中是转义 JSON 字符串（NatlabPeer emitEvent String 通路）。"""
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str):
        try:
            return json.loads(raw)
        except ValueError:
            return {}
    return {}


def last_result(events):
    """result 事件唯一选取口径：取末条（轮询与 classify 共用，防首末漂移）。"""
    return next((e for e in reversed(events) if e.get("event") == "result"), None)


def classify(cli):
    """按客户端事件流给出结论：direct / prediction / frp_fallback / fail。"""
    events = cli.snapshot()
    result = last_result(events)
    established = [e for e in events if e.get("event") == "punch_established"]
    fallback = any(e.get("event") == "punch_fallback" for e in events)
    prediction_stage = next(
        (s for e in events if e.get("event") == "result"
         for s in _parse_trace(e.get("punch_trace")).get("stages", [])
         if s.get("stage") == "port_prediction"), None)
    prediction_hit = bool(prediction_stage) and str(prediction_stage.get("detail", "")).startswith("hit")
    if established and prediction_hit:
        outcome = "prediction"
    elif established:
        outcome = "direct"
    elif fallback and result and result.get("ok"):
        outcome = "frp_fallback"
    else:
        outcome = "fail"
    punch_ms = result.get("punch_ms") if result else None
    return outcome, punch_ms, result, prediction_stage


def _cl_addr_change(action, addr):
    """client netns 内 ip addr add/del 并校验返回码（失败抛 RuntimeError → 该轮以 infra 失败呈现）。"""
    r = subprocess.run(["ip", "netns", "exec", NS_CL, "ip", "addr", action, addr + "/24", "dev", IF_CL_P],
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"client netns ip addr {action} {addr} 失败：{(r.stderr or r.stdout).strip()}")


def switch_client_ip(profile):
    """迁移场景：client netns 出口 IP 10.200.1.2 → 10.200.1.3，重应用档位（规则跟随新地址）。"""
    _cl_addr_change("add", CL_PEER_NEW)  # add 失败即抛出，跳过 del 老地址，避免 client 无出口 IP
    _cl_addr_change("del", CL_PEER)
    sh_setup("profile", profile, "cl")


def restore_client_ip(profile):
    cur = netns_run(NS_CL, "ip", "-4", "addr", "show", IF_CL_P).stdout
    if CL_PEER_NEW in cur:
        _cl_addr_change("add", CL_PEER)
        _cl_addr_change("del", CL_PEER_NEW)
        sh_setup("profile", profile, "cl")


def _infra_fail_rec(cl_prof, srv_prof, reason):
    """infra 失败轮的统一记录口径（同「source 未监听」分支）：该轮记 infra_fail 不抛出，后续作业继续。"""
    return {"outcome": "infra_fail", "reason": reason, "cl": cl_prof, "srv": srv_prof,
            "ok": False, "punch_ms": None, "prediction_stage": None}


def run_combo(cl_prof, srv_prof, prediction=True, migration=False,
              client_timeout=15.0, frames=5):
    """跑一轮组合；返回轮记录 dict。基础设施失败按该轮 infra_fail 记录呈现，不中断整个矩阵。"""
    try:
        restore_client_ip(cl_prof)          # 迁移场景残留复位（幂等）
        sh_setup("profile", cl_prof, "cl")
        sh_setup("profile", srv_prof, "srv")
    except Exception as e:
        return _infra_fail_rec(cl_prof, srv_prof, f"档位/地址准备失败：{e}")

    src = Peer(NS_SRV, ["--mode", "source", "--frp-host", SRV_GW, *STUN_ARGS,
                        # P2P-only lab：打洞 socket 用 ephemeral（固定绑 udpPort 会被内核
                        # SNAT 端口保留优先原端口，破坏池化档的随机映射语义）
                        "--udp-port", "0",
                        "--timeout", str(int(client_timeout * 1000) + 5000)], "source")
    listening = src.wait_event("listening", 8.0)
    if not listening:
        src.kill()
        return _infra_fail_rec(cl_prof, srv_prof, "source 未在 8s 内监听")

    cargs = ["--mode", "client", "--frp-host", SRV_GW, *STUN_ARGS,
             "--timeout", str(int(client_timeout * 1000)), "--frames", str(frames)]
    if not prediction:
        cargs.append("--no-prediction")
    if migration:
        cargs += ["--expect-migration", "--migrate-watch", "--migrate-interval-ms", "500"]

    switched = False
    deadline = time.time() + client_timeout + 15
    result = None
    cli = None
    infra_err = None                     # 本轮基础设施失败原因：finally 回收后按 infra_fail 记轮，不向上抛
    try:
        cli = Peer(NS_CL, cargs, "client")   # 构造纳入 try：失败也走 finally 回收 src
        while time.time() < deadline:
            events = cli.snapshot()
            result = last_result(events)
            frames_ev = next((e for e in events if e.get("event") == "frames"), None)
            errored = any(e.get("event") == "error" for e in events)
            if result is not None:
                break
            if migration and not switched and frames_ev is not None:
                time.sleep(0.5)                      # 让后续若干帧继续走老路径
                try:
                    switch_client_ip(cl_prof)
                except Exception as e:
                    infra_err = f"出口 IP 切换失败：{e}"
                    break                # 走 finally 回收两端，随后按 infra_fail 记本轮
                switched = True
            if errored and not cli.is_running():
                break
            time.sleep(0.2)
    finally:
        if cli is not None:
            cli.kill()
        src.kill()
        time.sleep(0.3)

    if cli is None:
        # client 对端进程启动失败（如 netns 已被删除致 ip netns exec 失败）：该轮按 infra_fail 呈现，不让整个 run 崩溃
        return _infra_fail_rec(cl_prof, srv_prof, "client 对端进程启动失败")
    if infra_err is not None:
        return _infra_fail_rec(cl_prof, srv_prof, infra_err)

    outcome, punch_ms, result, prediction_stage = classify(cli)
    events = cli.snapshot()
    rec = {
        "cl": cl_prof, "srv": srv_prof, "outcome": outcome,
        "prediction_enabled": prediction,
        "punch_ms": punch_ms,
        "ok": bool(result and result.get("ok")),
        "frames": result.get("frames") if result else None,
        "crypto": result.get("crypto") if result else None,
        "nat_symmetric_client": result.get("nat_symmetric") if result else None,
        "prediction_stage": prediction_stage.get("detail") if prediction_stage else None,
        "migrated": bool(result and result.get("migrated")),
    }
    if outcome == "fail":
        rec["events_tail"] = [e for e in events if e.get("event") in
                              ("result", "error", "punch_fallback", "connected")][-4:]
    if migration:
        seqs = [e.get("sequence") for e in events if e.get("event") == "frames"]
        rec.update({
            "migration_ip_switched": switched,
            "reconnects": result.get("reconnects") if result else None,
            "resumed_frames": result.get("resumed_frames") if result else None,
            "frame_sequences": seqs,
            "session_continuous": bool(
                result and result.get("ok") and (result.get("resumed_frames") or 0) > 0),
        })
    return rec


def run_stun():
    """公网区双节点（同 IP 双端口）：给 symmetric 映射判定提供 distinct 目标对。"""
    stuns = []
    for port in (STUN_PORT, STUN_PORT2):
        p = Peer(NS_WAN, ["--mode", "stun", "--stun", f"{WAN_IP}:{port}"], f"stun:{port}")
        ev = p.wait_event("listening", 8.0)
        if not ev:
            for s in stuns:
                s.kill()
            p.kill()
            raise RuntimeError(f"STUN 应答器（:{port}）未在 8s 内监听（natlab-wan）")
        stuns.append(p)
    return stuns


def matrix(data, profiles, rounds, prediction, client_timeout):
    print(f"── 3×3 矩阵：{profiles} × {profiles}，每组合 {rounds} 轮 ──")
    for cl in profiles:
        for srv in profiles:
            rounds_rec = []
            for i in range(rounds):
                rec = run_combo(cl, srv, prediction=prediction, client_timeout=client_timeout)
                rounds_rec.append(rec)
                print(f"  [{cl}×{srv}] round{i + 1}: {rec['outcome']} punch_ms={rec.get('punch_ms')}"
                      + (f" prediction={rec.get('prediction_stage')}" if rec.get("prediction_stage") else ""))
            wins = [r for r in rounds_rec if r["outcome"] in ("direct", "prediction")]
            punch_vals = [r["punch_ms"] for r in wins if r["punch_ms"]]
            data["matrix"].append({
                "client": cl, "server": srv, "rounds": rounds_rec,
                "direct": sum(r["outcome"] == "direct" for r in rounds_rec),
                "prediction": sum(r["outcome"] == "prediction" for r in rounds_rec),
                "frp_fallback": sum(r["outcome"] == "frp_fallback" for r in rounds_rec),
                "fail": sum(r["outcome"] in ("fail", "infra_fail") for r in rounds_rec),
                "success_rate": round(len(wins) / len(rounds_rec), 3),
                "avg_punch_ms": round(sum(punch_vals) / len(punch_vals), 1) if punch_vals else None,
                "session_established_rate": round(
                    sum(1 for r in rounds_rec if r["ok"]) / len(rounds_rec), 3),
            })


def prediction_toggle(data, profiles, client_timeout):
    """预测开关对比：随机 symmetric 对（全域随机，预测窗口大概率不 arm）+
    sym-seq 有界池化对（窗口可与分配段相交，命中率实验档）。
    sym-seq 对启用侧跑多轮以估算命中率；每对再以 --no-prediction 复跑对照。"""
    print("── 预测开关对比（启用多轮 vs --no-prediction 复跑）──")
    pairs = [(c, s) for c in profiles for s in profiles if "symmetric" in (c, s)]
    jobs = [(cl, srv, 1) for cl, srv in pairs]
    jobs += [("sym-seq", "sym-seq", 4), ("sym-seq", "symmetric", 2)]
    for cl, srv, n_on in jobs:
        on_rounds = [run_combo(cl, srv, prediction=True, client_timeout=client_timeout)
                     for _ in range(n_on)]
        off = run_combo(cl, srv, prediction=False, client_timeout=client_timeout)
        armed = sum(1 for r in on_rounds if r["prediction_stage"])
        hits = sum(1 for r in on_rounds if str(r["prediction_stage"] or "").startswith("hit"))
        direct_on = sum(1 for r in on_rounds if r["outcome"] in ("direct", "prediction"))
        data["prediction_toggle"].append({
            "client": cl, "server": srv, "enabled_rounds": n_on,
            "enabled": [{"outcome": r["outcome"], "punch_ms": r["punch_ms"],
                         "prediction_stage": r["prediction_stage"]} for r in on_rounds],
            "enabled_summary": {"armed": armed, "hit": hits,
                                "hit_rate": round(hits / n_on, 3), "direct": direct_on},
            "disabled": {"outcome": off["outcome"], "punch_ms": off["punch_ms"],
                         "prediction_stage": off["prediction_stage"]},
        })
        print(f"  [{cl}×{srv}] on×{n_on}: armed={armed} hit={hits} direct={direct_on}"
              f" | off: {off['outcome']}")


def migration_scenario(data, client_timeout):
    print("── 端点迁移：ei×ei 直连后 client 出口 IP 切换（10.200.1.2→10.200.1.3）──")
    rec = run_combo("ei", "ei", prediction=True, migration=True,
                    client_timeout=max(client_timeout * 2, 25.0), frames=5)
    data["migration"] = rec
    print(f"  迁移恢复={rec.get('session_continuous')} 重连={rec.get('reconnects')} "
          f"恢复帧={rec.get('resumed_frames')} 帧序列={rec.get('frame_sequences')}")


def print_table(data):
    print("\n=== 3×3 NAT 矩阵结果（client 档 × server 档）===")
    print(f"{'组合':<18}{'直连':>4}{'预测':>4}{'兜底':>4}{'失败':>4}{'打洞成功率':>10}{'会话建立':>9}{'平均耗时ms':>11}")
    for row in data["matrix"]:
        label = f"{row['client']}×{row['server']}"
        avg = f"{row['avg_punch_ms']:.0f}" if row["avg_punch_ms"] is not None else "-"
        print(f"{label:<18}{row['direct']:>4}{row['prediction']:>4}{row['frp_fallback']:>4}"
              f"{row['fail']:>4}{row['success_rate'] * 100:>9.0f}%{row['session_established_rate'] * 100:>8.0f}%{avg:>11}")


def main():
    ap = argparse.ArgumentParser(description="natlab 3×3 NAT 打洞矩阵验证（纯 JVM，无 adb 依赖）")
    ap.add_argument("--rounds", type=int, default=2)
    ap.add_argument("--profiles", default=",".join(PROFILES),
                    help="档位子集（默认 ei,ap-rr,symmetric）；prediction_toggle 固定追加 sym-seq 作业，不受子集影响")
    ap.add_argument("--client-timeout", type=float, default=15.0)
    ap.add_argument("--data-out", default=os.path.join(REPO, "docs", "natlab-matrix.json"))
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--skip-setup", action="store_true")
    ap.add_argument("--skip-matrix", action="store_true")
    ap.add_argument("--skip-migration", action="store_true")
    args = ap.parse_args()

    if os.geteuid() != 0:
        print("[natlab_run] 需要 root（netns/iptables）", file=sys.stderr)
        return 2
    if not os.path.isfile(PEER_BIN):
        print(f"[natlab_run] 缺 {PEER_BIN}——先在 protocol/ 下执行 `gradle installDist`", file=sys.stderr)
        return 2

    profiles = [p.strip() for p in args.profiles.split(",") if p.strip()]
    data = {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "scope": "natlab 实验室口径（netns+iptables 三档 NAT，JVM 协议级对端；非真机结论）",
        "peer": "protocol/build/install/natlab-peer（NatlabPeer.kt，protocol 库直跑 transport 栈）",
        "matrix": [], "prediction_toggle": [], "migration": None,
    }
    stun = None
    code = 0
    try:
        if not args.skip_setup:
            sh_setup("up")
        stun = run_stun()
        if not args.skip_matrix:
            matrix(data, profiles, args.rounds, prediction=True,
                   client_timeout=args.client_timeout)
        prediction_toggle(data, profiles, args.client_timeout)
        if not args.skip_migration:
            migration_scenario(data, args.client_timeout)
    except Exception as e:
        print(f"[natlab_run] 基础设施失败：{e}", file=sys.stderr)
        code = 1
    except KeyboardInterrupt:
        print("[natlab_run] 用户中断（Ctrl+C）：数据照常落盘，拓扑照常拆除", file=sys.stderr)
        code = 130
    finally:
        if stun:
            for s in stun:
                s.kill()

    data_dir = os.path.dirname(args.data_out)
    if data_dir:
        os.makedirs(data_dir, exist_ok=True)
    with open(args.data_out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    print(f"\n数据文件：{args.data_out}")
    print_table(data)
    mig = data.get("migration") or {}
    print(f"迁移场景：ip切换={mig.get('migration_ip_switched')} 会话恢复={mig.get('session_continuous')} "
          f"恢复帧={mig.get('resumed_frames')}")

    if not args.keep:
        try:
            sh_setup("down")
        except Exception:
            pass
    return code


if __name__ == "__main__":
    sys.exit(main())
