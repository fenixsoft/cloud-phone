#!/usr/bin/env python3
"""自适应协议测试客户端：用与现行管线不同的参数连接源端，验证零令牌 adaptTo。

用法: python3 test_adaptive_client.py [host] [port]
轮次1: HELLO(1080x1920@30/5000) → START_SESSION(同参) → 期望 HelloAck 实际 1080x2400@30/5000 + 出帧
轮次2: 再换参 720x1280@24/3000 → 期望再次 adapt 成功（证明可重复自适应，无需重新授权）
"""
import socket, struct, sys, time

TOKEN = "cloudphone-m0"

REC_CTRL, REC_VIDEO = 0x01, 0x02
M_HELLO, M_HELLO_ACK, M_START, M_STATS = 0x01, 0x02, 0x03, 0x07

MAX_RECORD_BYTES = 1 << 20  # 单条记录载荷上限：对端异常宣告超大长度时报错断连，而非无限收包撑内存


class MalformedFrame(Exception):
    """协议报文截断/越界/超长等畸形情形，携带可读中文原因。"""


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
    if plen > MAX_RECORD_BYTES:
        raise MalformedFrame(f"记录载荷超限：rtype=0x{rtype:02x} 宣告 {plen}B（上限 {MAX_RECORD_BYTES}B）")
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
    """逐字段带边界取读固定头部；hungCount 之后的挂机项/可选加密块尾部不读不校验（合法变长）。"""
    off = 1  # 跳过 msgType

    def take(n):
        nonlocal off
        if off + n > len(p):
            raise MalformedFrame(f"HelloAck 截断：偏移 {off} 处需读 {n}B，载荷仅 {len(p)}B")
        b = p[off:off + n]
        off += n
        return b

    status = take(1)[0]
    (rlen,) = struct.unpack(">H", take(2))
    reason = take(rlen).decode(errors="replace")
    sid, = struct.unpack(">I", take(4))
    w, h = struct.unpack(">hh", take(4))
    fps = take(1)[0]
    kbps, = struct.unpack(">I", take(4))
    (elen,) = struct.unpack(">H", take(2))
    enc = take(elen).decode(errors="replace")
    disp, = struct.unpack(">i", take(4))
    (alen,) = struct.unpack(">H", take(2))
    ans = take(alen).decode(errors="replace")
    (hcount,) = struct.unpack(">H", take(2))
    return dict(status=status, reason=reason, sid=sid, w=w, h=h, fps=fps,
                kbps=kbps, enc=enc, disp=disp, hung=hcount)


def run_round(sock, label, w, h, fps, kbps):
    print(f"\n=== {label}: 请求 {w}x{h}@{fps}/{kbps}kbps ===")
    send_record(sock, REC_CTRL, hello(w, h, fps, kbps))
    send_record(sock, REC_CTRL, start_session(w, h, fps, kbps))
    t_start = time.time()
    deadline = t_start + 10
    ack = None
    frames = keyframes = stats = 0
    bytes_rx = 0
    while time.time() < deadline:
        sock.settimeout(max(0.2, deadline - time.time()))
        try:
            rec = read_record(sock)
        except socket.timeout:
            break
        except MalformedFrame as e:
            print(f"!! 记录畸形：{e}")
            return False
        if rec is None:
            print("!! 连接被对端关闭"); return False
        rtype, payload = rec
        if rtype == REC_CTRL and payload and payload[0] == M_HELLO_ACK:
            try:
                a = parse_hello_ack(payload)
            except MalformedFrame as e:
                print(f"!! HelloAck 畸形/截断：{e}")
                return False
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
    elapsed = max(time.time() - t_start, 0.001)
    print(f"  视频帧: {frames} (IDR {keyframes})  stats: {stats}  收流 {bytes_rx/1024:.0f}KiB "
          f"≈ {bytes_rx*8/1000/elapsed:.0f}kbps（按实测收流窗口 {elapsed:.1f}s 计）")
    return ack is not None and frames > 0


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    try:
        port = int(sys.argv[2]) if len(sys.argv) > 2 else 22333
    except ValueError:
        print(f"!! 端口参数非法：{sys.argv[2]!r}，应为数字端口")
        sys.exit(2)
    try:
        sock = socket.create_connection((host, port), timeout=10)
    except ConnectionRefusedError:
        print(f"!! 连接被拒：{host}:{port}，确认源端已监听")
        sys.exit(2)
    except socket.gaierror:
        print(f"!! 地址无法解析：{host}，检查主机名/IP 是否正确")
        sys.exit(2)
    except OSError as e:  # 含连接超时、网络不可达
        print(f"!! 连接失败：{host}:{port}（{e}），确认源端可达且端口正确")
        sys.exit(2)
    print(f"已连接 {host}:{port}")
    ok1 = run_round(sock, "轮次1（授权档位外首次异参）", 1080, 1920, 30, 5000)
    ok2 = run_round(sock, "轮次2（再次异参，验证可重复 adapt）", 720, 1280, 24, 3000)
    sock.close()
    print(f"\n结果: 轮次1={'PASS' if ok1 else 'FAIL'} 轮次2={'PASS' if ok2 else 'FAIL'}")
    sys.exit(0 if (ok1 and ok2) else 1)


if __name__ == "__main__":
    main()
