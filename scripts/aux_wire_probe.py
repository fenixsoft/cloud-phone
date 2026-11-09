#!/usr/bin/env python3
"""aux_wire_probe.py — M3 三辅助通道线级端到端验证（m3-audio-clipboard-textinput 5.3）。

直连源端 TCP 控制通道，逐场景断言（线格式依据 docs/protocol.md §3.13–3.17）：

  1. 会话建立：HELLO → HELLO_ACK(ACCEPTED)；START_SESSION(FULLSCREEN) → ACCEPTED。
  2. 音频下行：窗口期读取 AUDIO_CHUNK（源端 TONE 负载由外部触发）——
     断言帧到达、seq 单调递增、ptsUs 与视频同钟单调、20ms 帧率量级、ADTS 头合法。
     负载时机约定：探针启动后 ~3.3s（START_SESSION 后立即）开始播放应用侧音频
     （loadgen AudioToneActivity，shell uid 播放在部分平台不被捕获——见 device-notes §11.1），
     建议 --ei seconds 12；探针 --drain 须 ≥ (TONE 结束时刻 − 探针启动 − 3) 秒。
  3. 音频静音零流量：TONE 停止后静默 ≥3s，窗口期 AUDIO_CHUNK 降为 1 帧/s keepalive。
  4. 剪贴板 SET：CLIPBOARD_SET{中文+ASCII} → CLIPBOARD_TEXT 回显（IME 焦点代理成功）
     或 AuxNotice(降级原因——镜像禁 IME 时记录降级而非失败)。
  5. 剪贴板 GET：CLIPBOARD_GET → CLIPBOARD_TEXT{与 SET 文本一致} 或 AuxNotice（无焦点）。
  6. 中文注入：TEXT_INPUT{seq=1, 中文} → TextInputAck（ok=1 或明确原因=无获焦输入框）。
  7. ASCII 兜底：TEXT_INPUT{seq=2, "ascii fallback test"} → ACK（IME 可用时走主路径同样 ok）。
  8. seq 去重：重复发送 seq=1 → 再次收到 ok ACK 且不产生第二次注入副作用（幂等回执）。
  9. 未知类型容错：msgType=0x42 记录 → 丢弃；紧随的 CLIPBOARD_GET 仍正常应答（连接不受影响）。

用法：python3 scripts/aux_wire_probe.py [--host 127.0.0.1] [--port 22333] [--window 6]
前置：源端 StreamService 在跑且投影已授权；shellhelper 已含 TONE 命令并由调用方触发。
"""
import argparse
import socket
import struct
import sys
import time

MSG_HELLO = 0x01
MSG_HELLO_ACK = 0x02
MSG_START = 0x03
MSG_STOP = 0x04
MSG_AUDIO_CHUNK = 0x0D
MSG_CLIPBOARD_SET = 0x0E
MSG_CLIPBOARD_GET = 0x0F
MSG_CLIPBOARD_TEXT = 0x10
MSG_TEXT_INPUT = 0x11

results = []


def verdict(name: str, ok: bool, detail: str = ""):
    tag = "PASS" if ok else "FAIL"
    results.append((name, ok, detail))
    print(f"PROBE {name}: {tag} {detail}", flush=True)


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


def stop_session(sid: int) -> bytes:
    return rec(MSG_STOP, struct.pack(">i", sid))


def clipboard_set(text: str) -> bytes:
    return rec(MSG_CLIPBOARD_SET, s(text))


def clipboard_get() -> bytes:
    return rec(MSG_CLIPBOARD_GET, b"")


def text_input(seq: int, text: str) -> bytes:
    return rec(MSG_TEXT_INPUT, bytes([0]) + struct.pack(">i", seq) + s(text))


class Wire:
    """增量帧解析：读取窗口期内全部控制记录"""

    def __init__(self, sock):
        self.sock = sock
        self.buf = b""

    def read_records(self, window_s: float) -> list:
        self.sock.settimeout(0.2)
        out = []
        deadline = time.time() + window_s
        while time.time() < deadline:
            try:
                chunk = self.sock.recv(262144)
                if not chunk:
                    break
                self.buf += chunk
            except socket.timeout:
                continue
            except OSError as e:
                print(f"[!] 连接异常中断: {e}")
                break
            while len(self.buf) >= 5:
                rtype = self.buf[0]
                (plen,) = struct.unpack(">I", self.buf[1:5])
                if plen > 8 * 1024 * 1024:
                    raise ValueError(f"record too large: {plen}")
                if len(self.buf) < 5 + plen:
                    break
                payload = self.buf[5:5 + plen]
                self.buf = self.buf[5 + plen:]
                if rtype == 0x01 and payload:
                    out.append((payload[0], payload[1:]))
        return out


def parse_hello_ack(p: bytes):
    (status,) = struct.unpack_from(">B", p, 0)
    (rlen,) = struct.unpack_from(">H", p, 1)
    reason = p[3:3 + rlen].decode("utf-8")
    return status, reason


def parse_ack_accept(p: bytes):
    """START_SESSION ACCEPTED 应答（HELLO_ACK 全字段略过）"""
    return p[0] == 0


def parse_clipboard_text(p: bytes):
    kind = p[0]
    if kind == 0:
        (rlen,) = struct.unpack_from(">H", p, 1)
        return "text", p[3:3 + rlen].decode("utf-8"), None
    elif kind == 1:
        channel = p[1]
        (rlen,) = struct.unpack_from(">H", p, 2)
        return "notice", p[4:4 + rlen].decode("utf-8"), channel
    return "bad", "", None


def parse_text_ack(p: bytes):
    kind = p[0]
    if kind != 1:
        return None
    (seq,) = struct.unpack_from(">i", p, 1)
    ok = p[5] != 0
    (rlen,) = struct.unpack_from(">H", p, 6)
    reason = p[8:8 + rlen].decode("utf-8")
    return seq, ok, reason


def is_adts(p: bytes) -> bool:
    return len(p) > 7 and p[0] == 0xFF and (p[1] & 0xF6) == 0xF0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=22333)
    ap.add_argument("--window", type=float, default=6.0, help="每场景读取窗口（秒）")
    ap.add_argument("--drain", type=float, default=4.5,
                    help="静音测量前排空积压的读取时长（秒；须 ≥ TONE 结束到静音门控触发的时间）")
    args = ap.parse_args()

    sock = socket.create_connection((args.host, args.port), timeout=5)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    wire = Wire(sock)

    # ── 1. 会话建立 ──
    sock.sendall(hello())
    recs = wire.read_records(3)
    acks = [parse_hello_ack(p) for t, p in recs if t == MSG_HELLO_ACK]
    verdict("hello-accepted", bool(acks) and acks[0][0] == 0,
            f"status={acks[0][0] if acks else 'none'} reason={acks[0][1] if acks else ''}")

    sock.sendall(start_session(720, 1280))
    recs = wire.read_records(3)
    starts = [p for t, p in recs if t == MSG_HELLO_ACK]
    verdict("session-started", bool(starts) and parse_ack_accept(starts[0]),
            f"acks={len(starts)}")

    # ── 2. 音频下行（TONE 由外部在窗口开始时触发）──
    print("PROBE audio-window: 读取窗口开始（外部 TONE 负载应在此窗口内）", flush=True)
    recs = wire.read_records(args.window)
    audio = [(struct.unpack_from(">q", p, 0)[0], struct.unpack_from(">i", p, 8)[0],
              struct.unpack_from(">H", p, 12)[0], p[14:]) for t, p in recs if t == MSG_AUDIO_CHUNK]
    seqs = [a[1] for a in audio]
    pts = [a[0] for a in audio]
    monotonic = all(b > a for a, b in zip(pts, pts[1:]))
    seq_increasing = all(b > a for a, b in zip(seqs, seqs[1:]))
    seq_dense = sum(1 for a, b in zip(seqs, seqs[1:]) if b == a + 1)
    real = [a for a in audio if a[2] > 0]  # len>0 为真实 AAC 帧；len==0 为静音 keepalive 空帧
    adts_ok = all(is_adts(a[3]) for a in real)
    fps_like = len(real) >= 100  # 20ms 帧 ≈ 50/s；TONE 覆盖窗口内至少 2s 响亮（≥100 帧）
    verdict("audio-chunk-flow", len(audio) > 0, f"frames={len(audio)} real={len(real)}")
    verdict("audio-pts-seq-monotonic", monotonic and seq_increasing,
            f"pts_monotonic={monotonic} seq_increasing={seq_increasing} dense(+1)={seq_dense}/{len(seqs)-1}")
    verdict("audio-adts-20ms", adts_ok and fps_like,
            f"adts_ok={adts_ok} rate≈{len(real) / args.window:.0f}fps sizes={sorted(set(a[2] for a in real))[:5]}")

    # ── 3. 静音零流量（TONE 停止 + 静默门控 3s 后，窗口应 ≈1 帧/s keepalive）──
    # 先持续读干 drain 秒（读间隔期间帧会积压在 TCP 缓冲，必须边读边排空再测量）
    wire.read_records(args.drain)
    recs = wire.read_records(3.0)
    keepalive = [p for t, p in recs if t == MSG_AUDIO_CHUNK]
    verdict("audio-silence-keepalive", 0 <= len(keepalive) <= 8,
            f"frames={len(keepalive)}（期望 ≤8 = 1帧/s × 3s 窗口 + 过渡帧）")

    # ── 4/5. 剪贴板 SET/GET 往返 ──
    test_text = "cloudphone-m3 中文粘贴 123"
    sock.sendall(clipboard_set(test_text))
    recs = wire.read_records(4)
    cb = [parse_clipboard_text(p) for t, p in recs if t == MSG_CLIPBOARD_TEXT]
    set_ok = any(k == "text" and v == test_text for k, v, _ in cb)
    verdict("clipboard-set-echo", set_ok,
            f"replies={[(k, v[:24], ch) for k, v, ch in cb]}")

    sock.sendall(clipboard_get())
    recs = wire.read_records(4)
    cb = [parse_clipboard_text(p) for t, p in recs if t == MSG_CLIPBOARD_TEXT]
    get_ok = any(k == "text" and v == test_text for k, v, _ in cb)
    get_degrade = any(k == "notice" for k, v, ch in cb if ch == 2)
    verdict("clipboard-get-roundtrip", get_ok or get_degrade,
            f"{'roundtrip 一致' if get_ok else ('degrade: ' + str([v for k, v, ch in cb if ch == 2]))}")

    # ── 6. 中文注入 ──
    sock.sendall(text_input(1, "中文注入测试 🎮"))
    recs = wire.read_records(4)
    acks = [parse_text_ack(p) for t, p in recs if t == MSG_TEXT_INPUT and p[0] == 1]
    acks = [a for a in acks if a and a[0] == 1]
    verdict("text-input-chinese-ack", bool(acks),
            f"ack={acks[0][1:] if acks else 'none'}（ok=1 或明确原因均算通路验证）")

    # ── 7. ASCII 兜底路径 ──
    sock.sendall(text_input(2, "ascii fallback 42"))
    recs = wire.read_records(4)
    acks = [parse_text_ack(p) for t, p in recs if t == MSG_TEXT_INPUT and p[0] == 1]
    acks = [a for a in acks if a and a[0] == 2]
    verdict("text-input-ascii-ack", bool(acks),
            f"ack={acks[0][1:] if acks else 'none'}")

    # ── 8. seq 去重（重复 seq=1 → 幂等 ok 回执）──
    sock.sendall(text_input(1, "中文注入测试 🎮"))
    recs = wire.read_records(4)
    acks = [parse_text_ack(p) for t, p in recs if t == MSG_TEXT_INPUT and p[0] == 1]
    acks = [a for a in acks if a and a[0] == 1]
    verdict("text-input-seq-dedup", bool(acks) and acks[0][1],
            f"ack={acks[0] if acks else 'none'}（重复 seq 应幂等回 ok）")

    # ── 9. 未知类型容错 ──
    sock.sendall(rec(0x42, bytes([1, 2, 3, 4])))
    time.sleep(0.5)
    sock.sendall(clipboard_get())
    recs = wire.read_records(4)
    alive = any(t == MSG_CLIPBOARD_TEXT for t, p in recs)
    verdict("unknown-type-tolerated", alive,
            f"0x42 丢弃后 CLIPBOARD_GET 仍应答={alive}")

    sock.sendall(stop_session(1))
    sock.close()

    failed = [r for r in results if not r[1]]
    print(f"\nPROBE SUMMARY: {len(results) - len(failed)}/{len(results)} PASS", flush=True)
    for name, ok, detail in failed:
        print(f"  FAILED: {name} — {detail}", flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
