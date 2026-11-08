#!/usr/bin/env python3
"""STUN NAT type probe (cloud-phone 直连诊断雏形).

Determines external UDP mapping behavior by querying two public STUN
servers from the SAME local socket:
  - identical XOR-MAPPED-ADDRESS  -> endpoint-independent mapping (P2P-friendly)
  - different addresses/ports     -> symmetric NAT (hole punching ~hopeless)
A second probe from a DIFFERENT local port to server A estimates port
prediction difficulty (delta between allocations).
"""
import os
import socket
import struct
import sys
import secrets

SERVERS = [("stun.qq.com", 3478), ("stun.miwifi.com", 3478),
           ("stun.cloudflare.com", 3478)]
RETRIES = 3

MAGIC = 0x2112A442


def rand_tid():
    return secrets.token_bytes(12)


def binding_request():
    # STUN Binding Request, no attributes, with SOFTWARE omitted
    return struct.pack("!HHI", 0x0001, 0, MAGIC) + rand_tid()


def parse_xor_mapped(resp, tid):
    """Extract XOR-MAPPED-ADDRESS (0x0020) or MAPPED-ADDRESS (0x0001)."""
    if len(resp) < 20:
        return None
    _type, _len, _magic, rtid = struct.unpack("!HHI12s", resp[:20])
    if rtid != tid:
        return None
    off = 20
    while off + 4 <= len(resp):
        atype, alen = struct.unpack("!HH", resp[off:off + 4])
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
    """Send one Binding Request over the GIVEN socket (reused across servers)."""
    sock.settimeout(timeout)
    tid = rand_tid()
    req = struct.pack("!HHI", 0x0001, 0, MAGIC) + tid
    sock.sendto(req, server)
    data, _ = sock.recvfrom(2048)
    return parse_xor_mapped(data, tid)


def main():
    # SAME socket asks BOTH servers: this is the correct symmetric-NAT probe.
    # Different mappings per server => per-peer mapping => symmetric NAT.
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", 0))
    lport = sock.getsockname()[1]
    print(f"local socket :0.0.0.0:{lport}")

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
            print(f"{srv[0]:24s} -> external:{mapped}")
        else:
            print(f"{srv[0]:24s} FAILED after {RETRIES} tries")
            results.append((srv[0], None))
    sock.close()

    ok = [r for r in results if r[1]]
    if len(ok) >= 2:
        a, b = ok[0][1], ok[1][1]
        if a == b:
            print("VERDICT: endpoint-independent mapping (cone) — P2P 打洞友好")
        else:
            print("VERDICT: mapping differs per server on SAME socket — "
                  "symmetric NAT, 双端对称时打洞成功率≈0, 须走中继/端口预测")

    # port prediction probe: fresh socket, same server, measure allocation delta
    if ok:
        ip = socket.gethostbyname(SERVERS[0][0])
        try:
            sock2 = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock2.bind(("0.0.0.0", 0))
            lport2 = sock2.getsockname()[1]
            mapped2 = query(sock2, (ip, SERVERS[0][1]))
            sock2.close()
            if mapped2:
                delta = mapped2[1] - ok[0][1][1]
                print(f"port-alloc probe: local {lport}->{lport2}, "
                      f"ext {ok[0][1][1]}->{mapped2[1]} delta={delta:+d} "
                      f"(小而稳定 => 端口预测打洞有戏)")
        except Exception as e:
            print(f"port-alloc probe FAILED: {e}")


if __name__ == "__main__":
    main()
