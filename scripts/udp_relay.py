#!/usr/bin/env python3
"""udp_relay.py — 主机侧 UDP 中继（adb 不支持 UDP 转发的补偿）。

模式：
  redir  （默认）经模拟器控制台 `redir add udp:<host>:<guest>` 建立主机→源端模拟器
         的 UDP 重定向（模拟器原生 NAT 转发，零拷贝）。控制台 token 取
         ~/.emulator_console_auth_token。
  proxy  纯用户态 UDP 代理：本机 listen 端口 ↔ upstream（host:port）双向转发，
         用于源端模拟器不在本机的拓扑（如远程宿主机）。

用法：
  python3 scripts/udp_relay.py redir --serial emulator-5554 --host-port 22334 --guest-port 22334
  python3 scripts/udp_relay.py proxy --listen 127.0.0.1:22334 --upstream 10.0.0.5:22334
"""
from __future__ import annotations

import argparse
import os
import re
import socket
import subprocess
import sys
import threading
import time


def console_redir(serial: str, host_port: int, guest_port: int) -> bool:
    """经模拟器控制台添加 UDP 重定向。"""
    if not re.fullmatch(r"emulator-\d+", serial):
        print(f"[udp_relay] serial 须为 emulator-N 形式（收到：{serial}）", file=sys.stderr)
        return False
    for name, port in (("--host-port", host_port), ("--guest-port", guest_port)):
        if not 1 <= port <= 65535:
            print(f"[udp_relay] {name} 端口须为 1-65535（收到：{port}）", file=sys.stderr)
            return False
    token_path = os.path.expanduser("~/.emulator_console_auth_token")
    token = ""
    if os.path.exists(token_path):
        with open(token_path) as f:
            token = f.read().strip()
    console_port = serial.replace("emulator-", "")
    try:
        with socket.create_connection(("127.0.0.1", int(console_port)), timeout=3) as s:
            f = s.makefile("rw", encoding="utf-8")
            f.readline()  # banner
            if token:
                f.write(f"auth {token}\n")
                f.flush()
                f.readline()
            f.write(f"redir add udp:{host_port}:{guest_port}\n")
            f.flush()
            resp = f.readline()
            f.write("quit\n")
            f.flush()
            ok = "OK" in resp
            print(f"[udp_relay] redir add udp:{host_port}:{guest_port} → {resp.strip()}")
            return ok
    except OSError as e:
        print(f"[udp_relay] 控制台不可达（{e}）", file=sys.stderr)
        return False


def parse_hostport(spec: str, name: str) -> tuple[str, int]:
    """解析 IPv4 形式的 host:port；显式拒绝 IPv6 并校验端口 1-65535。"""
    if spec.count(":") != 1:
        raise SystemExit(f"[udp_relay] {name} 仅支持 IPv4 的 host:port（收到：{spec}）")
    host, port = spec.rsplit(":", 1)
    if not port.isdigit() or not 1 <= int(port) <= 65535:
        raise SystemExit(f"[udp_relay] {name} 端口须为 1-65535（收到：{spec}）")
    return host, int(port)


def udp_proxy(listen: str, upstream: str) -> None:
    """双向 UDP 代理。

    实现：全部客户端共享同一个上游 socket（上游只看到单一 NAT 会话），
    仅支持单客户端拓扑；多于一个活跃客户端时彼此会收到对方应答（串扰），
    检测到即打印告警。
    """
    lhost, lport = parse_hostport(listen, "--listen")
    uhost, uport = parse_hostport(upstream, "--upstream")
    lsock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    lsock.bind((lhost, lport))
    usock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    client_addrs: dict[tuple, float] = {}
    print(f"[udp_relay] proxy {listen} ↔ {upstream}")
    if lhost == "0.0.0.0":
        print("[udp_relay] 警告：--listen 0.0.0.0 为无鉴权转发面，"
              "同网段任何主机均可借道访问 upstream；仅本机使用请改 127.0.0.1", file=sys.stderr)

    def pump_upstream_to_client():
        warned_multi = False
        failures = 0
        while True:
            try:
                data, _ = usock.recvfrom(65535)
                now = time.time()
                active = 0
                for addr, ts in list(client_addrs.items()):
                    if now - ts >= 30:
                        del client_addrs[addr]
                        continue
                    active += 1
                    lsock.sendto(data, addr)
            except OSError as e:
                failures += 1
                print(f"[udp_relay] 下行通道异常（连续 {failures} 次）：{e}", file=sys.stderr)
                if failures >= 10:
                    print("[udp_relay] 下行通道连续失败，退出进程以免静默半边不通", file=sys.stderr)
                    os._exit(1)
                continue
            failures = 0
            if active > 1 and not warned_multi:
                warned_multi = True
                print("[udp_relay] 警告：检测到多于一个活跃客户端，"
                      "它们共享同一上游 socket，应答会互相串扰（仅支持单客户端）", file=sys.stderr)

    threading.Thread(target=pump_upstream_to_client, daemon=True).start()
    while True:
        data, addr = lsock.recvfrom(65535)
        client_addrs[addr] = time.time()
        usock.sendto(data, (uhost, uport))


def main() -> int:
    ap = argparse.ArgumentParser(description="主机侧 UDP 中继")
    sub = ap.add_subparsers(dest="mode", required=True)
    p1 = sub.add_parser("redir", help="模拟器控制台 redir（主机→模拟器 UDP 重定向）")
    p1.add_argument("--serial", default="emulator-5554")
    p1.add_argument("--host-port", type=int, default=22334)
    p1.add_argument("--guest-port", type=int, default=22334)
    p2 = sub.add_parser("proxy", help="用户态 UDP 双向代理")
    p2.add_argument("--listen", default="127.0.0.1:22334")
    p2.add_argument("--upstream", required=True)
    args = ap.parse_args()

    if args.mode == "redir":
        return 0 if console_redir(args.serial, args.host_port, args.guest_port) else 1
    udp_proxy(args.listen, args.upstream)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
