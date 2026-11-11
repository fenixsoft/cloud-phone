/**
 * cloudphone 线协议 —— 控制消息定义与编解码（TS 版，逐字节复刻 docs/protocol.md §3）。
 *
 * 线格式真相源：docs/protocol.md；实现参照 protocol/src/main/kotlin/cloudphone/protocol/Messages.kt。
 * 控制通道记录 = [u8 recordType=0x01][u32 payloadLen][u8 msgType][字段...]，本文件负责
 * msgType 与字段编解码（协议常量唯一真相源的浏览器侧复刻）。
 *
 * 解码器纪律（设计 D1）：对**未知尾部字节一律容忍跳过**——线格式先例：HELLO 存在
 * docs/protocol.md 未记载的可选 `takeover` 尾字节（Kotlin Messages.kt 编码仅置位时出现、
 * 解码容忍缺失）与可选 34B 加密协商块，后续扩展同理；未知 msgType 整段保留为 Unknown
 * 消息，不中断会话。
 */

import {
  ByteReader,
  ByteWriter,
  ProtocolError,
  RECORD_CONTROL,
  encodeRecord,
  type Record,
} from './Framing';

/** 协议版本（M0 = 1） */
export const PROTOCOL_VERSION = 1;

/** 控制消息类型常量（0x01–0x11 连续编号；数值冻结不得改动，docs/protocol.md §6.4） */
export const MsgType = {
  HELLO: 0x01,
  HELLO_ACK: 0x02,
  START_SESSION: 0x03,
  STOP_SESSION: 0x04,
  IDR_REQUEST: 0x05,
  INPUT_EVENT: 0x06,
  STATS: 0x07,
  PING: 0x08,
  PONG: 0x09,
  APP_LIST_REQUEST: 0x0a,
  APP_LIST: 0x0b,
  P2P_MIGRATE: 0x0c,
  AUDIO_CHUNK: 0x0d,
  CLIPBOARD_SET: 0x0e,
  CLIPBOARD_GET: 0x0f,
  CLIPBOARD_TEXT: 0x10,
  TEXT_INPUT: 0x11,
} as const;

export type MsgTypeValue = (typeof MsgType)[keyof typeof MsgType];

/** 观看模式：整屏 / 单应用（指定包名）/ 附着到已挂机会话 */
export const WatchMode = { FULLSCREEN: 1, SINGLE_APP: 2, ATTACH: 3 } as const;
export type WatchModeValue = 1 | 2 | 3;

/** HELLO_ACK 状态与错误码 */
export const HelloStatus = {
  ACCEPTED: 0,
  REJECT_BAD_TOKEN: 1,
  REJECT_BUSY: 2,
  REJECT_VERSION: 3,
  REJECT_UNSUPPORTED_MODE: 4,
} as const;

/** 输入动作：触摸按下/抬起/移动，以及远端 back 键 */
export const InputAction = { DOWN: 0, UP: 1, MOVE: 2, BACK: 3 } as const;

/** 传输路径类型（STATS.transport 字段） */
export const TransportKind = { LAN_DIRECT: 0, P2P: 1, FRP: 2, P2P_FALLBACK_FRP: 3 } as const;

/** M3 辅助通道能力标识（CLIPBOARD_TEXT kind=1 的 channel 字段） */
export const AuxChannel = { AUDIO: 1, CLIPBOARD: 2, TEXT_INPUT: 3 } as const;

/** 可启动应用条目（APP_LIST 载荷） */
export interface AppEntry {
  packageName: string;
  label: string;
}

/** 已挂机（断开未销毁）会话摘要，随 HELLO_ACK 下发供「恢复上次观看」入口展示 */
export interface HungSession {
  sessionId: number;
  mode: WatchModeValue;
  packageName: string;
  width: number;
  height: number;
}

/** 加密协商结果（浏览器 v1 不发起加密：无块 = none 路径；解码侧如实呈现协商结果） */
export interface CryptoNegotiation {
  encryptOn: boolean;
  cryptoAlgo: number;
  cryptoNonce: Uint8Array;
}

/** 34B 加密协商块：[u8 cryptoAlgo][u8 encryptOn][32B nonce] */
export const CRYPTO_BLOCK_BYTES = 1 + 1 + 32;

function enumName(table: { readonly [key: string]: number }, value: number): string {
  for (const [name, v] of Object.entries(table)) {
    if (v === value) return name;
  }
  throw new ProtocolError(`unknown enum value ${value} in ${Object.keys(table).join('/')}`);
}

// ── 控制消息判别联合（字段名与 Kotlin data class 对齐）──

export type ControlMessage =
  | { kind: 'hello'; version: number; token: string; mode: WatchModeValue; packageName: string; width: number; height: number; fps: number; bitrateKbps: number; sessionId: number; p2pOffer: string; crypto: CryptoNegotiation | null }
  | { kind: 'helloAck'; status: number; statusName: string; reason: string; sessionId: number; width: number; height: number; fps: number; bitrateKbps: number; encoder: string; displayId: number; p2pAnswer: string; hungSessions: HungSession[]; crypto: CryptoNegotiation | null }
  | { kind: 'startSession'; sessionId: number; mode: WatchModeValue; packageName: string; width: number; height: number; fps: number; bitrateKbps: number }
  | { kind: 'stopSession'; sessionId: number }
  | { kind: 'idrRequest'; sessionId: number }
  | { kind: 'inputEvent'; sessionId: number; action: number; pointerId: number; x: number; y: number }
  | { kind: 'stats'; sessionId: number; seq: number; fps: number; bitrateKbps: number; rttMs: number; lossPct: number; transport: number }
  | { kind: 'ping'; nonce: bigint; senderUs: bigint }
  | { kind: 'pong'; nonce: bigint; senderUs: bigint; echoUs: bigint }
  | { kind: 'appListRequest'; requestId: number }
  | { kind: 'appList'; requestId: number; apps: AppEntry[] }
  | { kind: 'p2pMigrate'; candidates: string }
  | { kind: 'audioChunk'; ptsUs: bigint; seq: number; payload: Uint8Array }
  | { kind: 'clipboardSet'; text: string }
  | { kind: 'clipboardGet' }
  | { kind: 'clipboardText'; text: string }
  | { kind: 'auxNotice'; channel: number; reason: string }
  | { kind: 'textInput'; seq: number; text: string }
  | { kind: 'textInputAck'; seq: number; ok: boolean; reason: string }
  /** 未知类型消息：接收方 MUST 忽略且不中断会话（原始载荷保留供诊断） */
  | { kind: 'unknown'; unknownType: number; payload: Uint8Array };

/** 可编码消息（Unknown 不允许编码，与 Kotlin encodeControlMessage 对齐） */
export type EncodableControlMessage = Exclude<ControlMessage, { kind: 'unknown' }>;

// ── 编码：控制消息 → 线载荷（不含 record 头，首字节为 msgType）──

export function encodeControlMessage(msg: EncodableControlMessage): Uint8Array {
  const w = new ByteWriter(64);
  switch (msg.kind) {
    case 'hello': {
      w.u8(MsgType.HELLO)
        .u16(msg.version)
        .u16String(msg.token)
        .u8(msg.mode)
        .u16String(msg.packageName)
        .u16(msg.width)
        .u16(msg.height)
        .u8(msg.fps)
        .u32(msg.bitrateKbps)
        .i32(msg.sessionId)
        .u16String(msg.p2pOffer);
      // 浏览器 v1 不发加密块（= none 明文路径，docs/protocol.md §7.6 兼容矩阵首行）
      if (msg.crypto) {
        w.u8(msg.crypto.cryptoAlgo).u8(msg.crypto.encryptOn ? 1 : 0).bytes(msg.crypto.cryptoNonce);
      }
      break;
    }
    case 'helloAck': {
      w.u8(MsgType.HELLO_ACK)
        .u8(msg.status)
        .u16String(msg.reason)
        .i32(msg.sessionId)
        .u16(msg.width)
        .u16(msg.height)
        .u8(msg.fps)
        .u32(msg.bitrateKbps)
        .u16String(msg.encoder)
        .i32(msg.displayId)
        .u16String(msg.p2pAnswer)
        .u16(msg.hungSessions.length);
      for (const h of msg.hungSessions) {
        w.i32(h.sessionId).u8(h.mode).u16String(h.packageName).u16(h.width).u16(h.height);
      }
      if (msg.crypto) {
        w.u8(msg.crypto.cryptoAlgo).u8(msg.crypto.encryptOn ? 1 : 0).bytes(msg.crypto.cryptoNonce);
      }
      break;
    }
    case 'startSession':
      w.u8(MsgType.START_SESSION)
        .i32(msg.sessionId)
        .u8(msg.mode)
        .u16String(msg.packageName)
        .u16(msg.width)
        .u16(msg.height)
        .u8(msg.fps)
        .u32(msg.bitrateKbps);
      break;
    case 'stopSession':
      w.u8(MsgType.STOP_SESSION).i32(msg.sessionId);
      break;
    case 'idrRequest':
      w.u8(MsgType.IDR_REQUEST).i32(msg.sessionId);
      break;
    case 'inputEvent':
      // 归一化坐标按无符号 u16 写出（x/y ∈ [0,65535]）
      w.u8(MsgType.INPUT_EVENT).i32(msg.sessionId).u8(msg.action).u8(msg.pointerId).u16(msg.x).u16(msg.y);
      break;
    case 'stats':
      w.u8(MsgType.STATS)
        .i32(msg.sessionId)
        .i32(msg.seq)
        .f32(msg.fps)
        .f32(msg.bitrateKbps)
        .f32(msg.rttMs)
        .f32(msg.lossPct)
        .u8(msg.transport);
      break;
    case 'ping':
      w.u8(MsgType.PING).i64(msg.nonce).i64(msg.senderUs);
      break;
    case 'pong':
      w.u8(MsgType.PONG).i64(msg.nonce).i64(msg.senderUs).i64(msg.echoUs);
      break;
    case 'appListRequest':
      w.u8(MsgType.APP_LIST_REQUEST).i32(msg.requestId);
      break;
    case 'appList': {
      w.u8(MsgType.APP_LIST).i32(msg.requestId).u16(msg.apps.length);
      for (const a of msg.apps) {
        w.u16String(a.packageName).u16String(a.label);
      }
      break;
    }
    case 'p2pMigrate':
      w.u8(MsgType.P2P_MIGRATE).u16String(msg.candidates);
      break;
    case 'audioChunk':
      w.u8(MsgType.AUDIO_CHUNK).i64(msg.ptsUs).i32(msg.seq).u16(msg.payload.byteLength).bytes(msg.payload);
      break;
    case 'clipboardSet':
      w.u8(MsgType.CLIPBOARD_SET).u16String(msg.text);
      break;
    case 'clipboardGet':
      w.u8(MsgType.CLIPBOARD_GET); // 空载荷（仅 msgType 字节）
      break;
    case 'clipboardText':
      w.u8(MsgType.CLIPBOARD_TEXT).u8(0).u16String(msg.text);
      break;
    case 'auxNotice':
      w.u8(MsgType.CLIPBOARD_TEXT).u8(1).u8(msg.channel).u16String(msg.reason);
      break;
    case 'textInput':
      w.u8(MsgType.TEXT_INPUT).u8(0).i32(msg.seq).u16String(msg.text);
      break;
    case 'textInputAck':
      w.u8(MsgType.TEXT_INPUT).u8(1).i32(msg.seq).u8(msg.ok ? 1 : 0).u16String(msg.reason);
      break;
    default: {
      const never: never = msg;
      throw new ProtocolError(`cannot encode message ${(never as { kind: string }).kind}`);
    }
  }
  return w.toUint8Array();
}

// ── 解码：线载荷 → 控制消息 ──

/** 读可选加密协商块；缓冲不足（老端消息）返回 null → 协商回落 none */
function readCryptoBlockOpt(r: ByteReader): CryptoNegotiation | null {
  if (r.remaining() < CRYPTO_BLOCK_BYTES) return null;
  const algo = r.u8();
  const on = r.u8() !== 0;
  const nonce = r.bytes(32);
  return { encryptOn: on, cryptoAlgo: algo, cryptoNonce: nonce };
}

export function decodeControlMessage(payload: Uint8Array): ControlMessage {
  if (payload.byteLength === 0) throw new ProtocolError('empty control payload');
  const r = new ByteReader(payload);
  const type = r.u8();
  switch (type) {
    case MsgType.HELLO: {
      const base = {
        kind: 'hello' as const,
        version: r.u16(),
        token: r.u16String(),
        mode: readWatchMode(r),
        packageName: r.u16String(),
        width: r.u16(),
        height: r.u16(),
        fps: r.u8(),
        bitrateKbps: r.u32(),
        sessionId: r.i32(),
        p2pOffer: r.u16String(),
      };
      // 尾部字节一律容忍：34B 加密协商块（可选）与 takeover 等未知尾字节按剩余长度判定跳过
      const crypto = readCryptoBlockOpt(r);
      return { ...base, crypto };
    }
    case MsgType.HELLO_ACK: {
      const statusByte = r.u8();
      if (statusByte > HelloStatus.REJECT_UNSUPPORTED_MODE) {
        throw new ProtocolError(`bad hello status: ${statusByte}`);
      }
      const reason = r.u16String();
      const sessionId = r.i32();
      const width = r.u16();
      const height = r.u16();
      const fps = r.u8();
      const bitrateKbps = r.u32();
      const encoder = r.u16String();
      const displayId = r.i32();
      const p2pAnswer = r.u16String();
      const hungCount = r.u16();
      const hungSessions: HungSession[] = [];
      for (let i = 0; i < hungCount; i++) {
        hungSessions.push({
          sessionId: r.i32(),
          mode: readWatchMode(r),
          packageName: r.u16String(),
          width: r.u16(),
          height: r.u16(),
        });
      }
      const crypto = readCryptoBlockOpt(r); // 尾部容忍：无块 = 老端，none
      return {
        kind: 'helloAck',
        status: statusByte,
        statusName: enumName(HelloStatus, statusByte),
        reason,
        sessionId,
        width,
        height,
        fps,
        bitrateKbps,
        encoder,
        displayId,
        p2pAnswer,
        hungSessions,
        crypto,
      };
    }
    case MsgType.START_SESSION:
      return {
        kind: 'startSession',
        sessionId: r.i32(),
        mode: readWatchMode(r),
        packageName: r.u16String(),
        width: r.u16(),
        height: r.u16(),
        fps: r.u8(),
        bitrateKbps: r.u32(),
      };
    case MsgType.STOP_SESSION:
      return { kind: 'stopSession', sessionId: r.i32() };
    case MsgType.IDR_REQUEST:
      return { kind: 'idrRequest', sessionId: r.i32() };
    case MsgType.INPUT_EVENT: {
      const sessionId = r.i32();
      const actionByte = r.u8();
      if (actionByte > InputAction.BACK) throw new ProtocolError(`bad input action: ${actionByte}`);
      return { kind: 'inputEvent', sessionId, action: actionByte, pointerId: r.u8(), x: r.u16(), y: r.u16() };
    }
    case MsgType.STATS: {
      const sessionId = r.i32();
      const seq = r.i32();
      const fps = r.f32();
      const bitrateKbps = r.f32();
      const rttMs = r.f32();
      const lossPct = r.f32();
      const transportByte = r.u8();
      if (transportByte > TransportKind.P2P_FALLBACK_FRP) {
        throw new ProtocolError(`bad transport kind: ${transportByte}`);
      }
      return { kind: 'stats', sessionId, seq, fps, bitrateKbps, rttMs, lossPct, transport: transportByte };
    }
    case MsgType.PING:
      return { kind: 'ping', nonce: r.i64(), senderUs: r.i64() };
    case MsgType.PONG: {
      const nonce = r.i64();
      const senderUs = r.i64();
      return { kind: 'pong', nonce, senderUs, echoUs: r.i64() };
    }
    case MsgType.APP_LIST_REQUEST:
      return { kind: 'appListRequest', requestId: r.i32() };
    case MsgType.APP_LIST: {
      const requestId = r.i32();
      const count = r.u16();
      const apps: AppEntry[] = [];
      for (let i = 0; i < count; i++) {
        apps.push({ packageName: r.u16String(), label: r.u16String() });
      }
      return { kind: 'appList', requestId, apps }; // 尾部多余字节容忍跳过
    }
    case MsgType.P2P_MIGRATE:
      return { kind: 'p2pMigrate', candidates: r.u16String() };
    case MsgType.AUDIO_CHUNK: {
      const ptsUs = r.i64();
      const seq = r.i32();
      const len = r.u16();
      return { kind: 'audioChunk', ptsUs, seq, payload: r.bytes(len) };
    }
    case MsgType.CLIPBOARD_SET:
      return { kind: 'clipboardSet', text: r.u16String() };
    case MsgType.CLIPBOARD_GET:
      return { kind: 'clipboardGet' }; // 空载荷；尾部字节容忍跳过
    case MsgType.CLIPBOARD_TEXT: {
      const kind = r.u8();
      if (kind === 0) return { kind: 'clipboardText', text: r.u16String() };
      if (kind === 1) {
        const channelByte = r.u8();
        if (channelByte < AuxChannel.AUDIO || channelByte > AuxChannel.TEXT_INPUT) {
          throw new ProtocolError(`bad aux channel: ${channelByte}`);
        }
        return { kind: 'auxNotice', channel: channelByte, reason: r.u16String() };
      }
      throw new ProtocolError(`bad clipboard-text kind: ${kind}`);
    }
    case MsgType.TEXT_INPUT: {
      const kind = r.u8();
      const seq = r.i32();
      if (kind === 0) return { kind: 'textInput', seq, text: r.u16String() };
      if (kind === 1) {
        const ok = r.u8() !== 0;
        return { kind: 'textInputAck', seq, ok, reason: r.u16String() };
      }
      throw new ProtocolError(`bad text-input kind: ${kind}`);
    }
    default:
      // 未定义 msgType：整段保留为 Unknown，调用方忽略即可（不中断会话）
      return { kind: 'unknown', unknownType: type, payload: r.rest() };
  }
}

function readWatchMode(r: ByteReader): WatchModeValue {
  const v = r.u8();
  if (v < WatchMode.FULLSCREEN || v > WatchMode.ATTACH) throw new ProtocolError(`bad watch mode: ${v}`);
  return v as WatchModeValue;
}

// ── 记录级便利封装：控制记录 = [0x01][u32 len][控制载荷] ──

/** 控制消息 → 完整控制记录字节（[0x01][len][payload]） */
export function encodeControlRecord(msg: EncodableControlMessage): Uint8Array {
  return encodeRecord(RECORD_CONTROL, encodeControlMessage(msg));
}

/** 从一条记录 payload 解出控制消息（含 Unknown 容忍语义） */
export function decodeControlRecord(record: Record): ControlMessage {
  return decodeControlMessage(record.payload);
}
