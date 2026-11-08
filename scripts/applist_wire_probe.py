#!/usr/bin/env python3
"""applist_wire_probe.py — APP_LIST 协议线级验证（3.3）。

两个场景，直连源端 TCP 控制通道：
  A. 无会话：HELLO(ACCEPTED) 后不发 START_SESSION，直接 APP_LIST_REQUEST
     → 预期 2s 内无 APP_LIST 应答（源端静默忽略）。
  B. 会话在线：HELLO → START_SESSION(FULLSCREEN 720x1280@60) → 先发未知
     msgType=0x0C（老客户端兼容：必须被忽略且不断会话）→ APP_LIST_REQUEST
     → 预期 ≤1s 收到 APP_LIST 且含 cloudphone.loadgen → STOP_SESSION 收尾。

线格式依据 docs/protocol.md（大端、u16 前缀字符串、记录 [type][len][payload]）。
"""
import socket
import struct
import sys
import time

HOST = "127.0.0.1"
PORT = 22333

MSG_HELLO = 0x01
MSG_HELLO_ACK = 0x02
MSG_START = 0x03
MSG_STOP = 0x04
MSG_APP_LIST_REQUEST = 0x0A
MSG_APP_LIST = 0x0B


def s(x: str) -> bytes:
    b = x.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def rec(msg_type: int, payload: bytes) -> bytes:
    body = bytes([msg_type]) + payload
    return bytes([0x01]) + struct.pack(">I", len(body)) + body


def hello() -> bytes:
    return rec(MSG_HELLO,
               struct.pack(">H", 1) + s("cloudphone-m0") + bytes([1]) + s("")
               + struct.pack(">HH", 0, 0) + bytes([60]) + struct.pack(">I", 8000)
               + struct.pack(">i", 0) + s(""))


def start_session(w: int, h: int) -> bytes:
    return rec(MSG_START, struct.pack(">i", 0) + bytes([1]) + s("")
               + struct.pack(">HH", w, h) + bytes([60]) + struct.pack(">I", 8000))


def read_records(sock, window_s: float) -> list:
    """窗口期内读取全部控制记录（msgType, payload）"""
    sock.settimeout(0.5)
    out = []
    deadline = time.time() + window_s
    buf = b""
    while time.time() < deadline:
        try:
            chunk = sock.recv(65536)
            if not chunk:
                break
            buf += chunk
        except socket.timeout:
            continue
        except OSError as e:
            print(f"[!] 连接异常中断（疑似对端重置/崩溃）: {e}")
            break
        while len(buf) >= 5:
            rtype = buf[0]
            (plen,) = struct.unpack(">I", buf[1:5])
            if len(buf) < 5 + plen:
                break
            payload = buf[5:5 + plen]
            buf = buf[5 + plen:]
            if rtype == 0x01 and payload:
                out.append((payload[0], payload[1:]))
    return out


def parse_hello_ack(payload: bytes):
    # status u8, reason str, sessionId i32
    if len(payload) < 3:
        print("[x] 响应截断/格式非法（HELLO_ACK 过短）")
        return None
    (slen,) = struct.unpack(">H", payload[1:3])
    if len(payload) < 3 + slen + 4:
        print("[x] 响应截断/格式非法（HELLO_ACK 字段不完整）")
        return None
    status = payload[0]
    reason = payload[3:3 + slen].decode("utf-8", "replace")
    (sid,) = struct.unpack(">i", payload[3 + slen:7 + slen])
    return status, reason, sid


def parse_app_list(payload: bytes):
    if len(payload) < 6:
        print("[x] 响应截断/格式非法（APP_LIST 过短）")
        return None
    (req_id,) = struct.unpack(">i", payload[0:4])
    (count,) = struct.unpack(">H", payload[4:6])
    off = 6
    apps = []
    for _ in range(count):
        if len(payload) < off + 2:
            print("[x] 响应截断/格式非法（APP_LIST 条目不完整）")
            return None
        (plen,) = struct.unpack(">H", payload[off:off + 2])
        if len(payload) < off + 2 + plen:
            print("[x] 响应截断/格式非法（APP_LIST 条目不完整）")
            return None
        pkg = payload[off + 2:off + 2 + plen].decode("utf-8", "replace")
        off += 2 + plen
        if len(payload) < off + 2:
            print("[x] 响应截断/格式非法（APP_LIST 条目不完整）")
            return None
        (llen,) = struct.unpack(">H", payload[off:off + 2])
        if len(payload) < off + 2 + llen:
            print("[x] 响应截断/格式非法（APP_LIST 条目不完整）")
            return None
        label = payload[off + 2:off + 2 + llen].decode("utf-8", "replace")
        off += 2 + llen
        apps.append((pkg, label))
    return req_id, apps


def scenario_a():
    """无会话 → APP_LIST_REQUEST 必须被静默忽略"""
    sock = socket.create_connection((HOST, PORT), timeout=5)
    sock.sendall(hello())
    acks = read_records(sock, 2.0)
    ack = next((p for t, p in acks if t == MSG_HELLO_ACK), None)
    if ack is None:
        print("[A] FAIL: HELLO 无应答")
        sock.close()
        return False
    parsed = parse_hello_ack(ack)
    if parsed is None:
        sock.close()
        return False
    status, reason, _ = parsed
    if status != 0:
        print(f"[A] FAIL: HELLO 被拒: {reason}")
        sock.close()
        return False
    print("[A] HELLO ACCEPTED（无会话）")
    t0 = time.time()
    sock.sendall(rec(MSG_APP_LIST_REQUEST, struct.pack(">i", 777)))
    msgs = read_records(sock, 2.0)
    got = [p for t, p in msgs if t == MSG_APP_LIST]
    elapsed = time.time() - t0
    verdict = "PASS: 2s 无应答（静默忽略）" if not got else f"FAIL: 意外收到 {len(got)} 条 APP_LIST"
    print(f"[A] {verdict} (窗口 {elapsed:.1f}s)")
    sock.close()
    return not got


def scenario_b():
    """会话在线 → 未知消息容忍 + APP_LIST ≤1s 应答"""
    sock = socket.create_connection((HOST, PORT), timeout=5)
    sock.sendall(hello())
    acks = read_records(sock, 2.0)
    ack = next((p for t, p in acks if t == MSG_HELLO_ACK), None)
    if ack is None:
        print("[B] FAIL: HELLO 无应答")
        sock.close()
        return False
    sock.sendall(start_session(720, 1280))
    acks = read_records(sock, 3.0)
    ack = next((p for t, p in acks if t == MSG_HELLO_ACK), None)
    if ack is None:
        print("[B] FAIL: START_SESSION 无应答")
        sock.close()
        return False
    parsed = parse_hello_ack(ack)
    if parsed is None:
        sock.close()
        return False
    status, reason, sid = parsed
    if status != 0:
        print(f"[B] FAIL: START_SESSION 被拒: {reason}")
        sock.close()
        return False
    print(f"[B] START_SESSION ACCEPTED sid={sid}")
    # 未知消息类型（老客户端兼容：忽略且不中断会话）
    sock.sendall(bytes([0x01]) + struct.pack(">I", 5) + bytes([0x0C, 1, 2, 3, 4]))
    time.sleep(0.5)
    # APP_LIST 请求
    t0 = time.time()
    sock.sendall(rec(MSG_APP_LIST_REQUEST, struct.pack(">i", 42)))
    got = None
    buf = b""
    sock.settimeout(1.0)
    while time.time() - t0 < 2.0:
        try:
            chunk = sock.recv(65536)
            if not chunk:
                break
            buf += chunk
        except socket.timeout:
            continue
        except OSError as e:
            print(f"[!] 连接异常中断（疑似对端重置/崩溃）: {e}")
            break
        while len(buf) >= 5:
            rtype = buf[0]
            (plen,) = struct.unpack(">I", buf[1:5])
            if len(buf) < 5 + plen:
                break
            payload = buf[5:5 + plen]
            buf = buf[5 + plen:]
            if rtype == 0x01 and payload and payload[0] == MSG_APP_LIST:
                got = payload[1:]
                break
        if got is not None:
            break
    elapsed = time.time() - t0
    if got is None:
        print("[B] FAIL: 未收到 APP_LIST")
        sock.close()
        return False
    parsed = parse_app_list(got)
    if parsed is None:
        sock.close()
        return False
    req_id, apps = parsed
    pkgs = [a[0] for a in apps]
    has_loadgen = "cloudphone.loadgen" in pkgs
    print(f"[B] APP_LIST req={req_id} n={len(apps)} elapsed={elapsed*1000:.0f}ms "
          f"loadgen={'有' if has_loadgen else '无'} 首个={apps[0] if apps else '-'}")
    # 会话未被未知消息破坏：STOP_SESSION 正常收尾
    sock.sendall(rec(MSG_STOP, struct.pack(">i", sid)))
    time.sleep(0.5)
    sock.close()
    ok = req_id == 42 and has_loadgen and elapsed <= 1.0
    print(f"[B] {'PASS: ≤1s 应答 + 未知消息容忍' if ok else 'FAIL'}")
    return ok


if __name__ == "__main__":
    a = scenario_a()
    b = scenario_b()
    print(f"RESULT: {'PASS' if a and b else 'FAIL'} (no-session-ignore={a}, online-reply={b})")
    sys.exit(0 if a and b else 1)
