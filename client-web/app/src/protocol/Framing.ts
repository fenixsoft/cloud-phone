/**
 * cloudphone 线协议 —— 封帧层（TS 版，逐字节复刻 docs/protocol.md §1–§2）。
 *
 * 线格式真相源：docs/protocol.md；实现参照 protocol/src/main/kotlin/cloudphone/protocol/Framing.kt。
 *
 * 流记录（TCP/frp-TCP 控制与视频复用）：[u8 recordType][u32 payloadLen][payload]
 *   recordType 0x01 = 控制消息（payload 见 Messages.ts）
 *   recordType 0x02 = 视频帧记录（payload = 21B 帧头 + 帧数据）
 * 字节序统一大端；字段紧凑排列无填充；单记录上限 8 MiB。
 *
 * 网关是协议无关字节中继：WS 二进制帧边界 = TCP 段边界，与记录边界无关——
 * 因此浏览器侧必须按字节流累积解析（RecordStreamParser），不得按 WS 消息逐条解析。
 */

/** 控制记录类型 */
export const RECORD_CONTROL = 0x01;
/** 视频记录类型 */
export const RECORD_VIDEO = 0x02;

/** 流记录头字节数（u8 recordType + u32 payloadLen） */
export const RECORD_HEADER_BYTES = 5;
/** 视频帧头字节数：u32 sequence + u64 ptsUs + u8 flags + u16 fragIdx + u16 fragCnt + u32 totalLen = 21 */
export const VIDEO_HEADER_BYTES = 4 + 8 + 1 + 2 + 2 + 4;
/** 视频帧 flags：bit0 = 关键帧（IDR） */
export const FLAG_KEYFRAME = 0x01;
/** 单条流记录上限（防损坏长度前缀拖垮内存；docs/protocol.md §2.1） */
export const MAX_RECORD_BYTES = 8 * 1024 * 1024;

/** 协议解析失败（结构性损坏；未知消息类型不算失败，见 Messages.ts 的 Unknown） */
export class ProtocolError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ProtocolError';
  }
}

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder('utf-8');

/**
 * 大端字节写出器（C 兼容紧凑布局，docs/protocol.md §6）。
 * 容量不足自动倍增，无需预计算精确载荷长度。i64 一律以 bigint 承载：
 * PING.nonce 等 i64 字段取值可超 2^53，不得用 number。
 */
export class ByteWriter {
  private buf: Uint8Array;
  private view: DataView;
  private pos = 0;

  constructor(capacity = 64) {
    this.buf = new Uint8Array(Math.max(1, capacity));
    this.view = new DataView(this.buf.buffer);
  }

  private ensure(n: number): void {
    if (this.pos + n <= this.buf.byteLength) return;
    let cap = this.buf.byteLength;
    while (cap < this.pos + n) cap *= 2;
    const grown = new Uint8Array(cap);
    grown.set(this.buf.subarray(0, this.pos), 0);
    this.buf = grown;
    this.view = new DataView(grown.buffer);
  }

  u8(v: number): this {
    this.ensure(1);
    this.view.setUint8(this.pos, v & 0xff);
    this.pos += 1;
    return this;
  }

  u16(v: number): this {
    if (v < 0 || v > 0xffff) throw new ProtocolError(`u16 out of range: ${v}`);
    this.ensure(2);
    this.view.setUint16(this.pos, v, false);
    this.pos += 2;
    return this;
  }

  u32(v: number): this {
    if (v < 0 || v > 0xffffffff) throw new ProtocolError(`u32 out of range: ${v}`);
    this.ensure(4);
    this.view.setUint32(this.pos, v, false);
    this.pos += 4;
    return this;
  }

  i32(v: number): this {
    if (!Number.isInteger(v) || v < -0x80000000 || v > 0x7fffffff) throw new ProtocolError(`i32 out of range: ${v}`);
    this.ensure(4);
    this.view.setInt32(this.pos, v, false);
    this.pos += 4;
    return this;
  }

  /** i64 写出；v 为 bigint（安全承载全值域），也容忍 |v| < 2^53 的整数 number */
  i64(v: bigint | number): this {
    const big = typeof v === 'bigint' ? v : BigInt(Math.trunc(v));
    if (big < -(2n ** 63n) || big >= 2n ** 63n) throw new ProtocolError(`i64 out of range: ${big}`);
    this.ensure(8);
    this.view.setBigInt64(this.pos, big, false);
    this.pos += 8;
    return this;
  }

  /** IEEE-754 单精度（STATS 的 fps/码率/rtt/loss 字段） */
  f32(v: number): this {
    this.ensure(4);
    this.view.setFloat32(this.pos, v, false);
    this.pos += 4;
    return this;
  }

  bytes(b: Uint8Array): this {
    this.ensure(b.byteLength);
    this.buf.set(b, this.pos);
    this.pos += b.byteLength;
    return this;
  }

  /** 协议字符串：u16 字节长度 + UTF-8 字节（长度不含自身 2 字节） */
  u16String(s: string): this {
    const utf8 = textEncoder.encode(s);
    if (utf8.byteLength > 0xffff) throw new ProtocolError(`string too long: ${utf8.byteLength} bytes`);
    return this.u16(utf8.byteLength).bytes(utf8);
  }

  /** 已写出区间快照（不含未使用容量） */
  toUint8Array(): Uint8Array {
    return this.buf.slice(0, this.pos);
  }
}

/** 大端字节读取器（越界读取抛 ProtocolError 而非静默回 0） */
export class ByteReader {
  private view: DataView;
  private pos = 0;

  constructor(private readonly buf: Uint8Array) {
    // 注意：以 buf 实际区间建视图（subarray 视图的 byteOffset 已被 DataView 构造吸收）
    this.view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  }

  remaining(): number {
    return this.buf.byteLength - this.pos;
  }

  private need(n: number): void {
    if (this.remaining() < n) throw new ProtocolError(`unexpected end of payload: need ${n}, have ${this.remaining()}`);
  }

  u8(): number {
    this.need(1);
    return this.view.getUint8(this.pos++);
  }

  u16(): number {
    this.need(2);
    const v = this.view.getUint16(this.pos, false);
    this.pos += 2;
    return v;
  }

  u32(): number {
    this.need(4);
    const v = this.view.getUint32(this.pos, false);
    this.pos += 4;
    return v;
  }

  i32(): number {
    this.need(4);
    const v = this.view.getInt32(this.pos, false);
    this.pos += 4;
    return v;
  }

  i64(): bigint {
    this.need(8);
    const v = this.view.getBigInt64(this.pos, false);
    this.pos += 8;
    return v;
  }

  f32(): number {
    this.need(4);
    const v = this.view.getFloat32(this.pos, false);
    this.pos += 4;
    return v;
  }

  bytes(n: number): Uint8Array {
    this.need(n);
    const out = this.buf.slice(this.pos, this.pos + n);
    this.pos += n;
    return out;
  }

  /** 跳过 n 字节（未知尾部字节容忍语义：解码器一律跳过不报错） */
  skip(n: number): void {
    this.need(n);
    this.pos += n;
  }

  /** 读剩余全部字节 */
  rest(): Uint8Array {
    return this.bytes(this.remaining());
  }

  u16String(): string {
    const len = this.u16();
    return textDecoder.decode(this.bytes(len));
  }
}

/** 流记录 */
export interface Record {
  type: number;
  payload: Uint8Array;
}

/** 写一条流记录：[u8 recordType][u32 payloadLen][payload]（长度前缀大端） */
export function encodeRecord(recordType: number, payload: Uint8Array): Uint8Array {
  if (payload.byteLength > MAX_RECORD_BYTES) {
    throw new ProtocolError(`record too large: ${payload.byteLength}`);
  }
  const out = new Uint8Array(RECORD_HEADER_BYTES + payload.byteLength);
  const view = new DataView(out.buffer);
  view.setUint8(0, recordType & 0xff);
  view.setUint32(1, payload.byteLength, false);
  out.set(payload, RECORD_HEADER_BYTES);
  return out;
}

/**
 * 字节流记录解析器（增量喂入，与 WS 消息边界无关）。
 * 每次 feed() 返回本次完整解析出的记录（0..N 条）；部分记录字节留存内部待后续补齐。
 * 声明长度超上限视为线路损坏（ProtocolError），由调用方关闭连接。
 */
export class RecordStreamParser {
  private pending: Uint8Array = new Uint8Array(0);
  private view: DataView = new DataView(this.pending.buffer);

  /** 喂入一段任意切分的字节；返回其中完整记录 */
  feed(chunk: Uint8Array): Record[] {
    // 拼接存量 + 新到字节
    const merged = new Uint8Array(this.pending.byteLength + chunk.byteLength);
    if (this.pending.byteLength > 0) merged.set(this.pending, 0);
    merged.set(chunk, this.pending.byteLength);
    this.pending = merged;
    this.view = new DataView(merged.buffer, merged.byteOffset, merged.byteLength);

    const records: Record[] = [];
    let offset = 0;
    for (;;) {
      if (merged.byteLength - offset < RECORD_HEADER_BYTES) break;
      const type = this.view.getUint8(offset);
      const len = this.view.getUint32(offset + 1, false);
      if (len > MAX_RECORD_BYTES) throw new ProtocolError(`bad record length: ${len}`);
      if (merged.byteLength - offset - RECORD_HEADER_BYTES < len) break;
      const payload = merged.slice(offset + RECORD_HEADER_BYTES, offset + RECORD_HEADER_BYTES + len);
      records.push({ type, payload });
      offset += RECORD_HEADER_BYTES + len;
    }
    if (offset > 0) {
      this.pending = merged.slice(offset);
      this.view = new DataView(this.pending.buffer, this.pending.byteOffset, this.pending.byteLength);
    }
    return records;
  }
}

/** 21B 视频帧头（docs/protocol.md §2.2） */
export interface VideoFrameHeader {
  /** u32 帧序号（发送侧单调递增，回绕自然溢出） */
  sequence: number;
  /** u64 源端单调钟 µs 时间戳（bit-exact 用 bigint 承载） */
  ptsUs: bigint;
  /** bit0 = 关键帧（IDR） */
  keyframe: boolean;
  /** 本分片下标（0 起）；TCP 流模式恒为 0 */
  fragmentIndex: number;
  /** 本帧总分片数；TCP 流模式恒为 1 */
  fragmentCount: number;
  /** 整帧（未分片）字节长度 */
  totalLength: number;
}

/** 从视频载荷头部解析 21B 帧头；载荷不足 21B 抛 ProtocolError */
export function decodeVideoHeader(payload: Uint8Array): VideoFrameHeader {
  if (payload.byteLength < VIDEO_HEADER_BYTES) {
    throw new ProtocolError(`video payload shorter than header: ${payload.byteLength}`);
  }
  const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  const flags = view.getUint8(12);
  return {
    sequence: view.getUint32(0, false),
    ptsUs: view.getBigUint64(4, false),
    keyframe: (flags & FLAG_KEYFRAME) !== 0,
    fragmentIndex: view.getUint16(13, false),
    fragmentCount: view.getUint16(15, false),
    totalLength: view.getUint32(17, false),
  };
}

/** 帧头 + 分片字节 → 完整视频载荷（测试对称性与未来工具复用；浏览器正常只收不发） */
export function encodeVideoPayload(header: VideoFrameHeader, fragmentBytes: Uint8Array): Uint8Array {
  const out = new Uint8Array(VIDEO_HEADER_BYTES + fragmentBytes.byteLength);
  const view = new DataView(out.buffer);
  view.setUint32(0, header.sequence >>> 0, false);
  view.setBigUint64(4, BigInt.asUintN(64, header.ptsUs), false);
  view.setUint8(12, header.keyframe ? FLAG_KEYFRAME : 0);
  view.setUint16(13, header.fragmentIndex & 0xffff, false);
  view.setUint16(15, header.fragmentCount & 0xffff, false);
  view.setUint32(17, header.totalLength >>> 0, false);
  out.set(fragmentBytes, VIDEO_HEADER_BYTES);
  return out;
}

export interface TcpVideoFrame {
  header: VideoFrameHeader;
  data: Uint8Array;
}

/**
 * TCP 流模式视频记录 → 整帧（docs/protocol.md §2：frp/TCP 降级承载按流记录整帧传输）。
 * 整帧口径：fragCnt ≠ 1 即协议错误（浏览器端不做 UDP 分片重组）；数据长度与 totalLength
 * 不符同视为线路损坏。
 */
export function decodeTcpVideoFrame(payload: Uint8Array): TcpVideoFrame {
  const header = decodeVideoHeader(payload);
  if (header.fragmentCount !== 1 || header.fragmentIndex !== 0) {
    throw new ProtocolError(
      `TCP 流模式仅承载整帧单分片记录，收到 fragIdx=${header.fragmentIndex} fragCnt=${header.fragmentCount}`,
    );
  }
  const data = payload.slice(VIDEO_HEADER_BYTES);
  if (data.byteLength !== header.totalLength) {
    throw new ProtocolError(`帧数据长度与帧头不符: data=${data.byteLength} totalLen=${header.totalLength}`);
  }
  return { header, data };
}

/** seq 观测结果 */
export interface SeqObservation {
  /** 本次观测新计入的丢帧数（seq 缺口，0 = 无缺口） */
  dropped: number;
  /** 本次是否为重复/乱序旧帧（不计缺口） */
  outOfOrder: boolean;
}

/**
 * 视频帧 sequence 单调性跟踪与丢帧计数（u32 回绕安全）。
 *
 * 浏览器走 TCP 流模式，帧本身不丢（TCP 可靠有序），缺号只会来自源端主动丢帧
 * （编码器背压丢帧），按缺口计入 droppedFrames 供 HUD 展示（设计 D4）。
 * 会话切换/ATTACH 恢复时调用 reset() 重置基线，避免跨会话误计巨大缺口。
 */
export class SequenceTracker {
  private lastSeq: number | null = null;

  /** seq 缺口累计丢帧数 */
  droppedFrames = 0;
  /** 重复/回绕旧帧计数（不计缺口） */
  outOfOrderFrames = 0;
  /** 已按序接收帧数 */
  receivedFrames = 0;

  reset(): void {
    this.lastSeq = null;
    this.droppedFrames = 0;
    this.outOfOrderFrames = 0;
    this.receivedFrames = 0;
  }

  /** 观测一帧的 sequence；返回本次计数增量 */
  observe(seq: number): SeqObservation {
    if (this.lastSeq === null) {
      this.lastSeq = seq >>> 0;
      this.receivedFrames += 1;
      return { dropped: 0, outOfOrder: false };
    }
    // u32 模差：(seq - last) mod 2^32，全部值 < 2^53，双精度精确
    const diff = (((seq >>> 0) - this.lastSeq) + 0x100000000) % 0x100000000;
    if (diff === 0) {
      this.outOfOrderFrames += 1;
      return { dropped: 0, outOfOrder: true };
    }
    if (diff > 0x7fffffff) {
      // 旧 seq（回绕前的迟到帧/重放）：不计缺口
      this.outOfOrderFrames += 1;
      return { dropped: 0, outOfOrder: true };
    }
    this.lastSeq = seq >>> 0;
    this.receivedFrames += 1;
    const gap = diff - 1;
    if (gap > 0) {
      this.droppedFrames += gap;
      return { dropped: gap, outOfOrder: false };
    }
    return { dropped: 0, outOfOrder: false };
  }
}
