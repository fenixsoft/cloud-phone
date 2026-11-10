#!/usr/bin/env python3
"""自适应协议测试客户端：用与现行管线不同的参数连接源端，验证零令牌 adaptTo。

用法: python3 test_adaptive_client.py [host] [port]
轮次1: HELLO(1080x1920@30/5000) → START_SESSION(同参) → 期望 HelloAck 实际 1080x2400@30/5000 + 出帧
轮次2: 再换参 720x1280@24/3000 → 期望再次 adapt 成功（证明可重复自适应，无需重新授权）
"""
import socket, struct, sys, time

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 22333
TOKEN = "cloudphone-m0"

REC_CTRL, REC_VIDEO = 0x01, 0x02
M_HELLO, M_HELLO_ACK, M_START, M_STATS = 0x01, 0x02, 0x03, 0x07


def u16s(b: bytes) -> bytes:
    return struct.pack(">H", len(b)) + b


def send_record(sock, rtype, payload):
    sock.sendall(struct.pack(">BI", rtype, len(payload)) + payload)


def read_record(sock):
    head = b""
    while len(head) < 5:
        chunk = sock.recv(5 - len(head))
        if not chunk:
            return None
        head += chunk
    rtype = head[0]
    (plen,) = struct.unpack(">I", head[1:5])
    payload = b""
    while len(payload) < plen:
        chunk = sock.recv(min(65536, plen - len(payload)))
        if not chunk:
            return None
        payload += chunk
    return rtype, payload


def hello(w, h, fps, kbps):
    return (
        struct.pack(">B", M_HELLO)
        + struct.pack(">H", 1)
        + u16s(TOKEN.encode())
        + struct.pack(">B", 1)  # FULLSCREEN
        + u16s(b"")
        + struct.pack(">hh", w, h)
        + struct.pack(">B", fps)
        + struct.pack(">I", kbps)
        + struct.pack(">I", 0)
        + u16s(b"")
    )


def start_session(w, h, fps, kbps):
    return (
        struct.pack(">B", M_START)
        + struct.pack(">I", 0)
        + struct.pack(">B", 1)  # FULLSCREEN
        + u16s(b"")
        + struct.pack(">hh", w, h)
        + struct.pack(">B", fps)
        + struct.pack(">I", kbps)
    )


def parse_hello_ack(p):
    off = 1
    status = p[off]; off += 1
    (rlen,) = struct.unpack(">H", p[off:off+2]); off += 2
    reason = p[off:off+rlen].decode(); off += rlen
    sid, = struct.unpack(">I", p[off:off+4]); off += 4
    w, h = struct.unpack(">hh", p[off:off+4]); off += 4
    fps = p[off]; off += 1
    kbps, = struct.unpack(">I", p[off:off+4]); off += 4
    (elen,) = struct.unpack(">H", p[off:off+2]); off += 2
    enc = p[off:off+elen].decode(); off += elen
    disp, = struct.unpack(">i", p[off:off+4]); off += 4
    (alen,) = struct.unpack(">H", p[off:off+2]); off += 2
    ans = p[off:off+alen].decode(); off += alen
    (hcount,) = struct.unpack(">H", p[off:off+2])
    return dict(status=status, reason=reason, sid=sid, w=w, h=h, fps=fps,
                kbps=kbps, enc=enc, disp=disp, hung=hcount)


def run_round(sock, label, w, h, fps, kbps, watch_s=4.0):
    print(f"\n=== {label}: 请求 {w}x{h}@{fps}/{kbps}kbps ===")
    send_record(sock, REC_CTRL, hello(w, h, fps, kbps))
    send_record(sock, REC_CTRL, start_session(w, h, fps, kbps))
    deadline = time.time() + 10
    ack = None
    frames = keyframes = stats = 0
    bytes_rx = 0
    while time.time() < deadline:
        sock.settimeout(max(0.2, deadline - time.time()))
        try:
            rec = read_record(sock)
        except socket.timeout:
            break
        if rec is None:
            print("!! 连接被对端关闭"); return False
        rtype, payload = rec
        if rtype == REC_CTRL and payload and payload[0] == M_HELLO_ACK:
            a = parse_hello_ack(payload)
            print(f"  HelloAck: status={a['status']} reason={a['reason']!r} "
                  f"实际={a['w']}x{a['h']}@{a['fps']}/{a['kbps']}kbps enc={a['enc']!r}")
            if a["status"] != 0:
                print("!! 会话被拒"); return False
            if ack is None:
                ack = a
        elif rtype == REC_CTRL and payload and payload[0] == M_STATS:
            stats += 1
        elif rtype == REC_VIDEO and len(payload) >= 21:
            seq, pts, flags, fi, fc, total = struct.unpack(">IqBHHI", payload[:21])
            frames += 1
            bytes_rx += len(payload)
            if flags & 1:
                keyframes += 1
    print(f"  视频帧: {frames} (IDR {keyframes})  stats: {stats}  收流 {bytes_rx/1024:.0f}KiB "
          f"≈ {bytes_rx*8/1000/watch_s:.0f}kbps")
    return ack is not None and frames > 0


def main():
    sock = socket.create_connection((HOST, PORT), timeout=10)
    print(f"已连接 {HOST}:{PORT}")
    ok1 = run_round(sock, "轮次1（授权档位外首次异参）", 1080, 1920, 30, 5000)
    ok2 = run_round(sock, "轮次2（再次异参，验证可重复 adapt）", 720, 1280, 24, 3000)
    sock.close()
    print(f"\n结果: 轮次1={'PASS' if ok1 else 'FAIL'} 轮次2={'PASS' if ok2 else 'FAIL'}")
    sys.exit(0 if (ok1 and ok2) else 1)


if __name__ == "__main__":
    main()
