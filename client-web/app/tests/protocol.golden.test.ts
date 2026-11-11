/**
 * 协议 golden 单测（vitest）——TS 协议库与线格式真相源 docs/protocol.md 的逐字节锚定。
 *
 * golden 字节向量导出自 Kotlin FramingTest.kt / MessagesTest.kt 用例的字段取值
 * （同一字段值按线格式机械展开为十六进制字面量），双向断言：
 *   ① 编码方向：encodeControlMessage(msg) 与 golden 逐字节一致；
 *   ② 解码方向：decodeControlMessage(golden) 与期望消息字段一致。
 * 另含设计 D1 容忍纪律用例：HELLO 可选尾部字节出现/缺失两形态、未知尾部字节容忍、
 * 未知 msgType 整段保留 Unknown 不中断会话。
 */
import { describe, expect, it } from 'vitest';

import {
  MAX_RECORD_BYTES,
  ProtocolError,
  RECORD_CONTROL,
  RECORD_VIDEO,
  RecordStreamParser,
  SequenceTracker,
  VIDEO_HEADER_BYTES,
  decodeTcpVideoFrame,
  decodeVideoHeader,
  encodeRecord,
  encodeVideoPayload,
} from '../src/protocol/Framing';
import {
  AuxChannel,
  HelloStatus,
  InputAction,
  MsgType,
  TransportKind,
  WatchMode,
  decodeControlMessage,
  encodeControlMessage,
  type ControlMessage,
} from '../src/protocol/Messages';

/** 十六进制字面量（空白分隔）→ 字节 */
function unhex(s: string): Uint8Array {
  const clean = s.replace(/\s+/g, '');
  if (clean.length % 2 !== 0) throw new Error(`odd hex length: ${clean.length}`);
  const out = new Uint8Array(clean.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(clean.slice(i * 2, i * 2 + 2), 16);
  return out;
}

/** 字节 → 十六进制字符串（断言输出用；Uint8Array.toString('hex') 不是合法 API，须自行映射） */
function hex(b: Uint8Array): string {
  return Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
}

// ── golden 向量（从 Kotlin 测试用例字段值导出，逐字节手展）──

// MessagesTest `hello encryptOn off wire identical to v1 baseline`：
// Hello{version=1, token="tok", SINGLE_APP, "cloudphone.loadgen", 4x3, 60fps, 8000kbps, sessionId=7, p2pOffer=""}
const GOLDEN_HELLO_LEGACY = `
01 0001 0003 74 6f 6b 02
0012 63 6c 6f 75 64 70 68 6f 6e 65 2e 6c 6f 61 64 67 65 6e
0004 0003 3c 00001f40 00000007 0000`;

// 同上 + 1 字节 takeover 尾字节（传输层 last-HELLO-wins 语义的可选尾标记；会话层不读）
const GOLDEN_HELLO_TAKEOVER = `${GOLDEN_HELLO_LEGACY} 01`;

// MessagesTest `hello crypto negotiation roundtrip`：
// Hello{token="t", FULLSCREEN, "p", 0,0,0,0, sessionId=0, p2pOffer="", crypto={AES128GCM, on, nonce=01..20}}
const GOLDEN_HELLO_CRYPTO = `
01 0001 0001 74 01 0001 70 0000 0000 00 00000000 00000000 0000
01 01 01 02 03 04 05 06 07 08 09 0a 0b 0c 0d 0e 0f 10 11 12 13 14 15 16 17 18 19 1a 1b 1c 1d 1e 1f 20`;

// FramingTest `helloAck accepted round trip with hung sessions`
const GOLDEN_HELLO_ACK_ACCEPTED = `
02 00 0000 00000007 0500 02d0 3c 00001f40
001a 4f 4d 58 2e 71 63 6f 6d 2e 76 69 64 65 6f 2e 65 6e 63 6f 64 65 72 2e 61 76 63
00000002
0014 31 34 2e 31 35 35 2e 31 30 32 2e 31 30 34 3a 31 37 33 31 38
0002
00000003 02 0012 63 6c 6f 75 64 70 68 6f 6e 65 2e 6c 6f 61 64 67 65 6e 0500 02d0
00000004 01 0000 0438 0780`;

// MessagesTest `hello ack rejected carries reason`（REJECT_BAD_TOKEN, displayId=-1）
const GOLDEN_HELLO_ACK_BAD_TOKEN = `
02 01 000e 74 6f 6b 65 6e 20 6d 69 73 6d 61 74 63 68
00000000 0000 0000 00 00000000 0000 ffffffff 0000 0000`;

// REJECT_BUSY：设计 R——传输层观看端占用闸门缺省文案（LanDirectBackend/FrpBackend 同构）
const GOLDEN_HELLO_ACK_BUSY = `
02 02 001e e6 ba 90 e7 ab af e6 ad a3 e8 a2 ab e5 85 b6 e4 bb 96 e8 ae be e5 a4 87 e8 a7 82 e7 9c 8b
00000000 0000 0000 00 00000000 0000 ffffffff 0000 0000`;

// FramingTest `session lifecycle and input messages round trip`
const GOLDEN_START_SESSION = `
03 00000000 02 0012 63 6c 6f 75 64 70 68 6f 6e 65 2e 6c 6f 61 64 67 65 6e 0780 0438 1e 00002ee0`;
const GOLDEN_STOP_SESSION = `04 00000009`;
const GOLDEN_IDR_REQUEST = `05 00000009`;
const GOLDEN_INPUT_MOVE = `06 00000009 02 01 8000 0400`;
const GOLDEN_INPUT_BACK = `06 00000009 03 00 0000 0000`;

// FramingTest `stats ping pong round trip`（f32 大端：58.4/8123.5/3.2/0.41）
const GOLDEN_STATS = `07 00000009 0000000f 4269999a 45fddc00 404ccccd 3ed1eb85 02`;
const GOLDEN_PING = `08 1122334455667788 000000003b9aca7b`;
const GOLDEN_PONG = `09 1122334455667788 000000003b9aca7b 000000003b9aca91`;

// MessagesTest APP_LIST 用例（requestId=1，3 条目含 CJK 标签）
const GOLDEN_APP_LIST_REQUEST = `0a 0000002a`;
const GOLDEN_APP_LIST = `
0b 00000001 0003
0012 63 6c 6f 75 64 70 68 6f 6e 65 2e 6c 6f 61 64 67 65 6e 000e 4c 6f 61 64 20 47 65 6e 65 72 61 74 6f 72
0014 63 6f 6d 2e 61 6e 64 72 6f 69 64 2e 73 65 74 74 69 6e 67 73 0006 e8 ae be e7 bd ae
001a 6f 72 67 2e 63 68 72 6f 6d 69 75 6d 2e 77 65 62 76 69 65 77 5f 73 68 65 6c 6c 000d 57 65 62 56 69 65 77 20 53 68 65 6c 6c`;

// MessagesTest `p2p migrate message roundtrip`
const GOLDEN_P2P_MIGRATE =
  '0c 0039 32 30 30 31 3a 64 62 38 3a 3a 31 3a 34 30 30 30 30 2c 31 2e 32 2e 33 2e 34 3a 34 30 30 30 31 2c' +
  '31 39 32 2e 31 36 38 2e 31 2e 35 3a 34 31 30 30 30 3b 6e 61 74 3d 73 79 6d';

// MessagesTest aux 五条（0x0D–0x11）
const GOLDEN_AUDIO_CHUNK = `0d 00000000075bcd15 00000007 0005 ff 56 00 13 7f`;
const GOLDEN_CLIPBOARD_SET = `0e 001a e5 89 aa e8 b4 b4 e6 9d bf e6 96 87 e6 9c ac 20 f0 9f 93 8b 20 73 70 61 63 65`;
const GOLDEN_CLIPBOARD_GET = `0f`;
const GOLDEN_CLIPBOARD_TEXT = `10 00 001c e5 a4 8d e5 88 b6 e7 9a 84 e4 b8 ad e6 96 87 e5 86 85 e5 ae b9 20 e2 9c 82 ef b8 8f`;
const GOLDEN_AUX_NOTICE = `10 01 02 0009 e6 97 a0 e7 84 a6 e7 82 b9`;
const GOLDEN_TEXT_INPUT = `11 00 0000002a 0014 e4 b8 ad e6 96 87 e8 be 93 e5 85 a5 20 4f 4b 20 f0 9f 8e ae`;
const GOLDEN_TEXT_ACK_OK = `11 01 0000002a 01 0000`;
const GOLDEN_TEXT_ACK_FAIL = `11 01 0000002b 00 001e e9 9c 80 e5 90 af e7 94 a8 e4 ba 91 e5 89 aa e8 b4 b4 e6 9d bf e8 be 93 e5 85 a5 e6 b3 95`;

// Kotlin FramingTest `video header round trip preserves all fields`（21B 帧头 golden）
const GOLDEN_VIDEO_HEADER = `ff ff ff ff 3f ff ff ff ff ff ff ff 01 00 03 00 07 00 01 e2 40`;

// Kotlin FramingTest `stream records round trip` 的视频载荷（seq=1, ptsUs=123456, IDR, frag 0/1, totalLen=4）
const GOLDEN_TCP_VIDEO_FRAME =
  `00 00 00 01 00 00 00 00 00 01 e2 40 01 00 00 00 01 00 00 00 04` + ` 01 02 03 04`;

// ── 期望消息对象 ──

const HELLO_LEGACY_MSG: ControlMessage = {
  kind: 'hello',
  version: 1,
  token: 'tok',
  mode: WatchMode.SINGLE_APP,
  packageName: 'cloudphone.loadgen',
  width: 4,
  height: 3,
  fps: 60,
  bitrateKbps: 8000,
  sessionId: 7,
  p2pOffer: '',
  crypto: null,
};

const HELLO_ACK_ACCEPTED_MSG: ControlMessage = {
  kind: 'helloAck',
  status: HelloStatus.ACCEPTED,
  statusName: 'ACCEPTED',
  reason: '',
  sessionId: 7,
  width: 1280,
  height: 720,
  fps: 60,
  bitrateKbps: 8000,
  encoder: 'OMX.qcom.video.encoder.avc',
  displayId: 2,
  p2pAnswer: '14.155.102.104:17318',
  hungSessions: [
    { sessionId: 3, mode: WatchMode.SINGLE_APP, packageName: 'cloudphone.loadgen', width: 1280, height: 720 },
    { sessionId: 4, mode: WatchMode.FULLSCREEN, packageName: '', width: 1080, height: 1920 },
  ],
  crypto: null,
};

describe('Messages golden vectors（双向逐字节）', () => {
  it('HELLO：无尾部字节（v1 基线）双向一致', () => {
    const bytes = encodeControlMessage(HELLO_LEGACY_MSG);
    expect(hex(bytes)).toBe(hex(unhex(GOLDEN_HELLO_LEGACY)));
    expect(decodeControlMessage(unhex(GOLDEN_HELLO_LEGACY))).toEqual(HELLO_LEGACY_MSG);
  });

  it('HELLO：takeover 尾字节出现/缺失两形态解码等价（容忍纪律）', () => {
    const withTail = decodeControlMessage(unhex(GOLDEN_HELLO_TAKEOVER));
    const withoutTail = decodeControlMessage(unhex(GOLDEN_HELLO_LEGACY));
    expect(withTail).toEqual(withoutTail);
    expect(withTail).toEqual(HELLO_LEGACY_MSG);
  });

  it('HELLO：未知尾部字节（7B 垃圾尾）一律容忍跳过', () => {
    const garbage = unhex(`${GOLDEN_HELLO_LEGACY} de ad be ef 00 11 22`);
    expect(decodeControlMessage(garbage)).toEqual(HELLO_LEGACY_MSG);
  });

  it('HELLO：34B 加密协商块出现时解码协商结果（缺失时回落 none）', () => {
    const msg = decodeControlMessage(unhex(GOLDEN_HELLO_CRYPTO));
    expect(msg.kind).toBe('hello');
    if (msg.kind === 'hello') {
      expect(msg.token).toBe('t');
      expect(msg.crypto).not.toBeNull();
      expect(msg.crypto?.encryptOn).toBe(true);
      expect(msg.crypto?.cryptoAlgo).toBe(1);
      expect(Array.from(msg.crypto?.cryptoNonce ?? [])).toEqual(
        Array.from({ length: 32 }, (_, i) => i + 1),
      );
    }
    // 与 Kotlin MessagesTest `old peer hello without crypto block decodes none` 同语义
    expect(HELLO_LEGACY_MSG.crypto).toBeNull();
  });

  it('HELLO_ACK：accepted + 挂机会话列表双向一致', () => {
    expect(
      hex(
        encodeControlMessage(HELLO_ACK_ACCEPTED_MSG),
      ),
    ).toBe(hex(unhex(GOLDEN_HELLO_ACK_ACCEPTED)));
    expect(decodeControlMessage(unhex(GOLDEN_HELLO_ACK_ACCEPTED))).toEqual(HELLO_ACK_ACCEPTED_MSG);
  });

  it('HELLO_ACK：REJECT_BAD_TOKEN（displayId=-1）双向一致', () => {
    const msg: ControlMessage = {
      kind: 'helloAck',
      status: HelloStatus.REJECT_BAD_TOKEN,
      statusName: 'REJECT_BAD_TOKEN',
      reason: 'token mismatch',
      sessionId: 0,
      width: 0,
      height: 0,
      fps: 0,
      bitrateKbps: 0,
      encoder: '',
      displayId: -1,
      p2pAnswer: '',
      hungSessions: [],
      crypto: null,
    };
    expect(hex(encodeControlMessage(msg))).toBe(
      hex(unhex(GOLDEN_HELLO_ACK_BAD_TOKEN)),
    );
    expect(decodeControlMessage(unhex(GOLDEN_HELLO_ACK_BAD_TOKEN))).toEqual(msg);
  });

  it('HELLO_ACK：REJECT_BUSY 占用拒绝（中文原因）解码一致', () => {
    const msg = decodeControlMessage(unhex(GOLDEN_HELLO_ACK_BUSY));
    expect(msg.kind).toBe('helloAck');
    if (msg.kind === 'helloAck') {
      expect(msg.status).toBe(HelloStatus.REJECT_BUSY);
      expect(msg.reason).toBe('源端正被其他设备观看');
    }
  });

  it('HELLO_ACK：未知尾部字节容忍', () => {
    const withTail = unhex(`${GOLDEN_HELLO_ACK_ACCEPTED} ff ff`);
    expect(decodeControlMessage(withTail)).toEqual(HELLO_ACK_ACCEPTED_MSG);
  });

  it('START_SESSION / STOP_SESSION / IDR_REQUEST golden 双向', () => {
    const start: ControlMessage = {
      kind: 'startSession',
      sessionId: 0,
      mode: WatchMode.SINGLE_APP,
      packageName: 'cloudphone.loadgen',
      width: 1920,
      height: 1080,
      fps: 30,
      bitrateKbps: 12000,
    };
    expect(hex(encodeControlMessage(start))).toBe(
      hex(unhex(GOLDEN_START_SESSION)),
    );
    expect(decodeControlMessage(unhex(GOLDEN_START_SESSION))).toEqual(start);
    expect(decodeControlMessage(unhex(GOLDEN_STOP_SESSION))).toEqual({ kind: 'stopSession', sessionId: 9 });
    expect(encodeControlMessage({ kind: 'stopSession', sessionId: 9 })).toEqual(unhex(GOLDEN_STOP_SESSION));
    expect(decodeControlMessage(unhex(GOLDEN_IDR_REQUEST))).toEqual({ kind: 'idrRequest', sessionId: 9 });
    expect(encodeControlMessage({ kind: 'idrRequest', sessionId: 9 })).toEqual(unhex(GOLDEN_IDR_REQUEST));
  });

  it('INPUT_EVENT：MOVE 与 BACK（action=3）golden 双向', () => {
    const move: ControlMessage = {
      kind: 'inputEvent',
      sessionId: 9,
      action: InputAction.MOVE,
      pointerId: 1,
      x: 32768,
      y: 1024,
    };
    expect(hex(encodeControlMessage(move))).toBe(hex(unhex(GOLDEN_INPUT_MOVE)));
    expect(decodeControlMessage(unhex(GOLDEN_INPUT_MOVE))).toEqual(move);
    const back: ControlMessage = {
      kind: 'inputEvent',
      sessionId: 9,
      action: InputAction.BACK,
      pointerId: 0,
      x: 0,
      y: 0,
    };
    expect(encodeControlMessage(back)).toEqual(unhex(GOLDEN_INPUT_BACK));
    expect(decodeControlMessage(unhex(GOLDEN_INPUT_BACK))).toEqual(back);
  });

  it('STATS：f32 大端字段 golden 双向（float32 精度锚定）', () => {
    const stats: ControlMessage = {
      kind: 'stats',
      sessionId: 9,
      seq: 15,
      fps: Math.fround(58.4),
      bitrateKbps: Math.fround(8123.5),
      rttMs: Math.fround(3.2),
      lossPct: Math.fround(0.41),
      transport: TransportKind.FRP,
    };
    expect(hex(encodeControlMessage(stats))).toBe(hex(unhex(GOLDEN_STATS)));
    expect(decodeControlMessage(unhex(GOLDEN_STATS))).toEqual(stats);
  });

  it('PING / PONG：i64 nonce 超 2^53 精度安全（bigint 承载）', () => {
    const ping: ControlMessage = { kind: 'ping', nonce: 0x1122334455667788n, senderUs: 1000000123n };
    expect(hex(encodeControlMessage(ping))).toBe(hex(unhex(GOLDEN_PING)));
    expect(decodeControlMessage(unhex(GOLDEN_PING))).toEqual(ping);
    const pong: ControlMessage = {
      kind: 'pong',
      nonce: 0x1122334455667788n,
      senderUs: 1000000123n,
      echoUs: 1000000145n,
    };
    expect(hex(encodeControlMessage(pong))).toBe(hex(unhex(GOLDEN_PONG)));
    expect(decodeControlMessage(unhex(GOLDEN_PONG))).toEqual(pong);
  });

  it('APP_LIST_REQUEST / APP_LIST（CJK 标签）golden 双向', () => {
    expect(encodeControlMessage({ kind: 'appListRequest', requestId: 42 })).toEqual(
      unhex(GOLDEN_APP_LIST_REQUEST),
    );
    expect(decodeControlMessage(unhex(GOLDEN_APP_LIST_REQUEST))).toEqual({
      kind: 'appListRequest',
      requestId: 42,
    });
    const list: ControlMessage = {
      kind: 'appList',
      requestId: 1,
      apps: [
        { packageName: 'cloudphone.loadgen', label: 'Load Generator' },
        { packageName: 'com.android.settings', label: '设置' },
        { packageName: 'org.chromium.webview_shell', label: 'WebView Shell' },
      ],
    };
    expect(hex(encodeControlMessage(list))).toBe(hex(unhex(GOLDEN_APP_LIST)));
    expect(decodeControlMessage(unhex(GOLDEN_APP_LIST))).toEqual(list);
  });

  it('P2P_MIGRATE golden 双向', () => {
    const msg: ControlMessage = {
      kind: 'p2pMigrate',
      candidates: '2001:db8::1:40000,1.2.3.4:40001,192.168.1.5:41000;nat=sym',
    };
    expect(hex(encodeControlMessage(msg))).toBe(hex(unhex(GOLDEN_P2P_MIGRATE)));
    expect(decodeControlMessage(unhex(GOLDEN_P2P_MIGRATE))).toEqual(msg);
  });

  it('AUDIO_CHUNK golden 双向', () => {
    const msg: ControlMessage = {
      kind: 'audioChunk',
      ptsUs: 123456789n,
      seq: 7,
      payload: unhex('ff 56 00 13 7f'),
    };
    expect(hex(encodeControlMessage(msg))).toBe(hex(unhex(GOLDEN_AUDIO_CHUNK)));
    expect(decodeControlMessage(unhex(GOLDEN_AUDIO_CHUNK))).toEqual(msg);
  });

  it('CLIPBOARD_SET / CLIPBOARD_GET / CLIPBOARD_TEXT golden 双向', () => {
    const set: ControlMessage = { kind: 'clipboardSet', text: '剪贴板文本 📋 space' };
    expect(hex(encodeControlMessage(set))).toBe(hex(unhex(GOLDEN_CLIPBOARD_SET)));
    expect(decodeControlMessage(unhex(GOLDEN_CLIPBOARD_SET))).toEqual(set);
    expect(encodeControlMessage({ kind: 'clipboardGet' })).toEqual(unhex(GOLDEN_CLIPBOARD_GET));
    expect(decodeControlMessage(unhex(GOLDEN_CLIPBOARD_GET))).toEqual({ kind: 'clipboardGet' });
    const text: ControlMessage = { kind: 'clipboardText', text: '复制的中文内容 ✂️' };
    expect(hex(encodeControlMessage(text))).toBe(
      hex(unhex(GOLDEN_CLIPBOARD_TEXT)),
    );
    expect(decodeControlMessage(unhex(GOLDEN_CLIPBOARD_TEXT))).toEqual(text);
  });

  it('CLIPBOARD_TEXT kind=1 = AuxNotice（通道降级回执）双向', () => {
    const msg: ControlMessage = { kind: 'auxNotice', channel: AuxChannel.CLIPBOARD, reason: '无焦点' };
    expect(hex(encodeControlMessage(msg))).toBe(hex(unhex(GOLDEN_AUX_NOTICE)));
    expect(decodeControlMessage(unhex(GOLDEN_AUX_NOTICE))).toEqual(msg);
  });

  it('TEXT_INPUT(kind=0) 与 ACK(kind=1) 成败两形态 golden 双向', () => {
    const input: ControlMessage = { kind: 'textInput', seq: 42, text: '中文输入 OK 🎮' };
    expect(hex(encodeControlMessage(input))).toBe(hex(unhex(GOLDEN_TEXT_INPUT)));
    expect(decodeControlMessage(unhex(GOLDEN_TEXT_INPUT))).toEqual(input);
    const ok: ControlMessage = { kind: 'textInputAck', seq: 42, ok: true, reason: '' };
    expect(hex(encodeControlMessage(ok))).toBe(hex(unhex(GOLDEN_TEXT_ACK_OK)));
    expect(decodeControlMessage(unhex(GOLDEN_TEXT_ACK_OK))).toEqual(ok);
    const fail: ControlMessage = { kind: 'textInputAck', seq: 43, ok: false, reason: '需启用云剪贴板输入法' };
    expect(hex(encodeControlMessage(fail))).toBe(hex(unhex(GOLDEN_TEXT_ACK_FAIL)));
    expect(decodeControlMessage(unhex(GOLDEN_TEXT_ACK_FAIL))).toEqual(fail);
  });

  it('未知 msgType（0x00/0x12/0x20/0x7F/0xFF）整段保留 Unknown 且后续消息不受影响', () => {
    for (const t of [0x00, 0x12, 0x20, 0x7f, 0xff]) {
      const payload = unhex(`${t.toString(16).padStart(2, '0')} 01 02 03`);
      const msg = decodeControlMessage(payload);
      expect(msg.kind).toBe('unknown');
      if (msg.kind === 'unknown') {
        expect(msg.unknownType).toBe(t);
        expect(Array.from(msg.payload)).toEqual([0x01, 0x02, 0x03]);
      }
    }
    // 紧随其后的已定义消息仍正常解析（连接/会话不受未知记录影响）
    expect(decodeControlMessage(unhex(GOLDEN_CLIPBOARD_GET))).toEqual({ kind: 'clipboardGet' });
  });

  it('0x0C–0x11 均为已定义类型，msgType 常量冻结（docs/protocol.md §6.4）', () => {
    expect(MsgType.P2P_MIGRATE).toBe(0x0c);
    expect(MsgType.AUDIO_CHUNK).toBe(0x0d);
    expect(MsgType.CLIPBOARD_SET).toBe(0x0e);
    expect(MsgType.CLIPBOARD_GET).toBe(0x0f);
    expect(MsgType.CLIPBOARD_TEXT).toBe(0x10);
    expect(MsgType.TEXT_INPUT).toBe(0x11);
  });

  it('空载荷解码拒绝', () => {
    expect(() => decodeControlMessage(new Uint8Array(0))).toThrow(ProtocolError);
  });
});

describe('Framing：流记录与视频帧', () => {
  it('控制记录封帧 golden：[0x01][u32 len][payload]', () => {
    const pingPayload = unhex(GOLDEN_PING);
    const record = encodeRecord(RECORD_CONTROL, pingPayload);
    const expected = unhex(`01 ${pingPayload.length.toString(16).padStart(8, '0')} ${GOLDEN_PING}`);
    expect(record).toEqual(expected);
  });

  it('字节流任意切分仍逐记录完整解析（WS 段边界与记录边界无关）', () => {
    const stream = new Uint8Array([
      ...encodeRecord(RECORD_CONTROL, encodeControlMessage({ kind: 'ping', nonce: 5n, senderUs: 7n })),
      ...encodeRecord(RECORD_VIDEO, unhex(GOLDEN_TCP_VIDEO_FRAME)),
      ...encodeRecord(RECORD_CONTROL, unhex(GOLDEN_CLIPBOARD_GET)),
    ]);
    const parser = new RecordStreamParser();
    const records = [];
    // 3 字节一片的恶意切分（覆盖记录头跨片、载荷跨片）
    for (let i = 0; i < stream.length; i += 3) {
      records.push(...parser.feed(stream.slice(i, Math.min(i + 3, stream.length))));
    }
    expect(records).toHaveLength(3);
    expect(records[0].type).toBe(RECORD_CONTROL);
    expect(decodeControlMessage(records[0].payload)).toEqual({ kind: 'ping', nonce: 5n, senderUs: 7n });
    expect(records[1].type).toBe(RECORD_VIDEO);
    expect(records[2].payload).toEqual(unhex(GOLDEN_CLIPBOARD_GET));
  });

  it('视频帧头 21B golden 解析（seq 回绕边界/关键帧/分片元数据）', () => {
    const header = decodeVideoHeader(unhex(GOLDEN_VIDEO_HEADER));
    expect(header.sequence).toBe(0xffffffff);
    expect(header.ptsUs).toBe(4611686018427387903n); // Long.MAX_VALUE/2
    expect(header.keyframe).toBe(true);
    expect(header.fragmentIndex).toBe(3);
    expect(header.fragmentCount).toBe(7);
    expect(header.totalLength).toBe(123456);
  });

  it('encodeVideoPayload/decodeVideoHeader 对称往返', () => {
    const payload = encodeVideoPayload(
      {
        sequence: 0xffffffff,
        ptsUs: 4611686018427387903n,
        keyframe: true,
        fragmentIndex: 3,
        fragmentCount: 7,
        totalLength: 123456,
      },
      new Uint8Array(10),
    );
    expect(payload.slice(0, VIDEO_HEADER_BYTES)).toEqual(unhex(GOLDEN_VIDEO_HEADER));
    expect(decodeVideoHeader(payload).totalLength).toBe(123456);
  });

  it('TCP 整帧口径：fragCnt≠1 报错、长度与 totalLen 不符报错', () => {
    const good = decodeTcpVideoFrame(unhex(GOLDEN_TCP_VIDEO_FRAME));
    expect(good.header.sequence).toBe(1);
    expect(good.header.ptsUs).toBe(123456n);
    expect(good.header.keyframe).toBe(true);
    expect(Array.from(good.data)).toEqual([1, 2, 3, 4]);

    // fragCnt=2 的分片帧头（改写 fragIdx/fragCnt 字段）→ ProtocolError
    const frag = unhex(`00 00 00 02 00 00 00 00 00 01 e2 40 01 00 00 00 01 00 00 00 04 01 02 03 04`);
    frag[15] = 2; // fragCnt := 2
    expect(() => decodeTcpVideoFrame(frag)).toThrow(ProtocolError);

    // totalLength 与实际数据长度不符 → ProtocolError
    const badLen = unhex(`00 00 00 01 00 00 00 00 00 01 e2 40 01 00 00 00 01 00 00 00 09 01 02 03 04`);
    expect(() => decodeTcpVideoFrame(badLen)).toThrow(ProtocolError);
  });

  it('记录长度前缀超 8MiB 上限视为线路损坏', () => {
    const parser = new RecordStreamParser();
    const evil = unhex('01 ff ff ff ff');
    expect(() => parser.feed(evil)).toThrow(ProtocolError);
    expect(() => encodeRecord(RECORD_CONTROL, new Uint8Array(MAX_RECORD_BYTES + 1))).toThrow(ProtocolError);
  });

  it('部分记录留存待补齐（不丢字节不重复产出）', () => {
    const record = encodeRecord(RECORD_CONTROL, unhex(GOLDEN_PING));
    const parser = new RecordStreamParser();
    expect(parser.feed(record.slice(0, 4))).toHaveLength(0);
    const out = parser.feed(record.slice(4));
    expect(out).toHaveLength(1);
    expect(out[0].payload).toEqual(unhex(GOLDEN_PING));
    // 全部消费完无残留
    expect(parser.feed(new Uint8Array(0))).toHaveLength(0);
  });
});

describe('SequenceTracker：seq 单调性与丢帧计数', () => {
  it('缺口计丢帧、重复与旧帧不计缺口', () => {
    const t = new SequenceTracker();
    expect(t.observe(5)).toEqual({ dropped: 0, outOfOrder: false });
    expect(t.observe(8)).toEqual({ dropped: 2, outOfOrder: false }); // 6、7 缺号
    expect(t.observe(8)).toEqual({ dropped: 0, outOfOrder: true }); // 重复帧
    expect(t.observe(7)).toEqual({ dropped: 0, outOfOrder: true }); // 旧帧
    expect(t.droppedFrames).toBe(2);
    expect(t.receivedFrames).toBe(2);
    expect(t.outOfOrderFrames).toBe(2);
  });

  it('u32 回绕安全：0xFFFFFFFE → 1 判为前进 3', () => {
    const t = new SequenceTracker();
    t.observe(0xfffffffe);
    expect(t.observe(1)).toEqual({ dropped: 2, outOfOrder: false });
    expect(t.droppedFrames).toBe(2);
  });

  it('会话切换 reset 重置基线与计数', () => {
    const t = new SequenceTracker();
    t.observe(1);
    t.observe(10);
    expect(t.droppedFrames).toBe(8);
    t.reset();
    expect(t.droppedFrames).toBe(0);
    expect(t.observe(500)).toEqual({ dropped: 0, outOfOrder: false }); // 新会话首帧不误计缺口
    expect(t.droppedFrames).toBe(0);
  });
});
