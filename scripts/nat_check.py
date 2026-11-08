#!/usr/bin/env python3
"""STUN NAT 类型探测（cloud-phone 直连诊断雏形）。

从同一个本地 socket 向多台公网 STUN 服务器发探测，比对出口 UDP 映射：
  - 各服务器 XOR-MAPPED-ADDRESS 一致  -> endpoint-independent mapping（锥形，P2P 友好）
  - 各服务器映射不同                  -> 对称 NAT（双端对称时打洞≈无望，须走中继/端口预测）
再用另一个本地端口向 SERVERS[0] 发探测，以端口分配差值（delta）估算端口预测难度。
"""
import socket
import struct
import sys
import time
import secrets

SERVERS = [("stun.qq.com", 3478), ("stun.miwifi.com", 3478),
           ("stun.cloudflare.com", 3478)]
RETRIES = 3

MAGIC = 0x2112A442


def rand_tid():
    return secrets.token_bytes(12)


def binding_request():
    # STUN Binding Request：无属性，省略 SOFTWARE
    return struct.pack("!HHI", 0x0001, 0, MAGIC) + rand_tid()


def parse_xor_mapped(resp, tid):
    """解析 XOR-MAPPED-ADDRESS (0x0020) 或 MAPPED-ADDRESS (0x0001)。"""
    if len(resp) < 20:
        return None
    _type, _len, _magic, rtid = struct.unpack("!HHI12s", resp[:20])
    if rtid != tid:
        return None
    off = 20
    while off + 4 <= len(resp):
        atype, alen = struct.unpack("!HH", resp[off:off + 4])
        if off + 4 + alen > len(resp):
            return None  # 属性体被截断（响应不完整），按无效包丢弃，避免 unpack 越界
        aval = resp[off + 4:off + 4 + alen]
        if atype == 0x0020 and alen == 8:
            port = struct.unpack("!H", aval[2:4])[0] ^ (MAGIC >> 16)
            ip = bytes(a ^ b for a, b in zip(aval[4:8],
                                             struct.pack("!I", MAGIC)))
            return (socket.inet_ntoa(ip), port)
        if atype == 0x0001 and alen == 8:
            port = struct.unpack("!H", aval[2:4])[0]
            return (socket.inet_ntoa(aval[4:8]), port)
        off += 4 + alen + ((4 - alen % 4) % 4)
    return None


def query(sock, server, timeout=4.0):
    """经指定 socket（跨服务器复用）发送一次 Binding Request 并等待应答。"""
    tid = rand_tid()
    req = struct.pack("!HHI", 0x0001, 0, MAGIC) + tid
    sock.sendto(req, server)
    # 循环收包直到 tid 匹配或总超时：迟到/串台的应答直接丢弃继续收，
    # 避免占掉本次 recv 导致该服务器被误判无应答（RETRIES 未真正用满）
    deadline = time.monotonic() + timeout
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError(f"STUN 应答超时（{timeout}s）: {server}")
        sock.settimeout(remaining)
        data, _ = sock.recvfrom(2048)
        mapped = parse_xor_mapped(data, tid)
        if mapped is not None:
            return mapped


def main():
    # 同一个 socket 问所有服务器：这才是正确的对称 NAT 探测。
    # 各服务器映射不同 => 按对端分配映射 => 对称 NAT。
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", 0))
    lport = sock.getsockname()[1]
    print(f"本地 socket :0.0.0.0:{lport}")

    results = []
    for srv in SERVERS:
        ip = socket.gethostbyname(srv[0])
        mapped = None
        for _ in range(RETRIES):
            try:
                mapped = query(sock, (ip, srv[1]))
                break
            except Exception:
                continue
        if mapped:
            results.append((srv[0], mapped))
            print(f"{srv[0]:24s} -> 外部映射:{mapped}")
        else:
            print(f"{srv[0]:24s} 探测失败（重试 {RETRIES} 次）")
            results.append((srv[0], None))
    sock.close()

    ok = [r for r in results if r[1]]
    verdict = 0
    if len(ok) >= 2:
        a, b = ok[0][1], ok[1][1]
        if a == b:
            print("VERDICT: endpoint-independent mapping (cone) — P2P 打洞友好")
        else:
            print("VERDICT: mapping differs per server on SAME socket — "
                  "symmetric NAT, 双端对称时打洞成功率≈0, 须走中继/端口预测")
    else:
        # 可达服务器不足 2 台时无法判定，以非零退出码告知上层探测失败
        print(f"VERDICT: 无法判定（应答服务器不足 2 台，仅 {len(ok)}/{len(SERVERS)} 可达）")
        verdict = 1

    # 端口预测探测：新 socket 向 SERVERS[0] 发探测，基线取 SERVERS[0] 自己的映射
    # （其失败则跳过本探测：基线若取首个成功服务器，delta 会跨服务器比较而失真）
    base = dict(results).get(SERVERS[0][0])
    if base:
        ip = socket.gethostbyname(SERVERS[0][0])
        try:
            sock2 = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock2.bind(("0.0.0.0", 0))
            lport2 = sock2.getsockname()[1]
            mapped2 = query(sock2, (ip, SERVERS[0][1]))
            sock2.close()
            if mapped2:
                delta = mapped2[1] - base[1]
                print(f"port-alloc probe: local {lport}->{lport2}, "
                      f"ext {base[1]}->{mapped2[1]} delta={delta:+d} "
                      f"(小而稳定 => 端口预测打洞有戏)")
        except Exception as e:
            print(f"port-alloc probe 探测失败: {e}")
    return verdict


if __name__ == "__main__":
    raise SystemExit(main())
