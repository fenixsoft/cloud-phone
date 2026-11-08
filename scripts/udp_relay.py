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
  python3 scripts/udp_relay.py proxy --listen 0.0.0.0:22334 --upstream 10.0.0.5:22334
"""
from __future__ import annotations

import argparse
import os
import socket
import subprocess
import sys
import threading
import time


def console_redir(serial: str, host_port: int, guest_port: int) -> bool:
    """经模拟器控制台添加 UDP 重定向。"""
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


def udp_proxy(listen: str, upstream: str) -> None:
    """双向 UDP 代理（每个对端一个 socket，NAT 会话表保持）。"""
    lhost, lport = listen.rsplit(":", 1)
    uhost, uport = upstream.rsplit(":", 1)
    lsock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    lsock.bind((lhost, int(lport)))
    usock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    client_addrs: dict[tuple, float] = {}
    print(f"[udp_relay] proxy {listen} ↔ {upstream}")

    def pump_upstream_to_client():
        while True:
            data, _ = usock.recvfrom(65535)
            now = time.time()
            for addr, ts in list(client_addrs.items()):
                if now - ts < 30:
                    lsock.sendto(data, addr)

    threading.Thread(target=pump_upstream_to_client, daemon=True).start()
    while True:
        data, addr = lsock.recvfrom(65535)
        client_addrs[addr] = time.time()
        usock.sendto(data, (uhost, int(uport)))


def main() -> int:
    ap = argparse.ArgumentParser(description="主机侧 UDP 中继")
    sub = ap.add_subparsers(dest="mode", required=True)
    p1 = sub.add_parser("redir", help="模拟器控制台 redir（主机→模拟器 UDP 重定向）")
    p1.add_argument("--serial", default="emulator-5554")
    p1.add_argument("--host-port", type=int, default=22334)
    p1.add_argument("--guest-port", type=int, default=22334)
    p2 = sub.add_parser("proxy", help="用户态 UDP 双向代理")
    p2.add_argument("--listen", default="0.0.0.0:22334")
    p2.add_argument("--upstream", required=True)
    args = ap.parse_args()

    if args.mode == "redir":
        return 0 if console_redir(args.serial, args.host_port, args.guest_port) else 1
    udp_proxy(args.listen, args.upstream)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
