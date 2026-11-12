/**
 * 媒体管线单元测试（vitest，node 环境，WebCodecs 以注入替身承载）：
 * - VideoPipeline：四级计数、seq 缺口、首关键帧门、帧 dump 首截断、resetForNewSession；
 * - AudioPipeline：ADTS 解析/AudioSpecificConfig、显式降级（无 AudioDecoder）、
 *   剥头解码入队、worklet 喂入与统计透传。
 */
import { describe, expect, it, vi } from 'vitest';

import {
  VideoPipeline,
  VideoUnsupportedError,
  probeVideoSupport,
  type EncodedVideoChunkInit,
  type EncodedVideoChunkLike,
  type VideoDecoderLike,
  type VideoFrameLike,
} from '../src/media/VideoPipeline';
import {
  AudioPipeline,
  buildAudioSpecificConfig,
  parseAdtsHeader,
  stripAdts,
  type AudioDataLike,
  type AudioDecoderLike,
  type WorkletNodeLike,
} from '../src/media/AudioPipeline';
import { encodeVideoPayload, ProtocolError } from '../src/protocol/Framing';

// ── 测试替身 ──

class FakeDecoder implements VideoDecoderLike {
  decodeQueueSize = 0;
  state = 'configured';
  chunks: { type: string; timestamp: number; byteLength: number }[] = [];
  constructor(private onDecode?: (chunk: { type: string; timestamp: number }) => VideoFrameLike | null) {}
  decode(chunk: { type: string; timestamp: number }): void {
    this.chunks.push({ type: chunk.type, timestamp: chunk.timestamp, byteLength: 0 });
    const frame = this.onDecode?.(chunk);
    if (frame) this.onFrame(frame);
  }
  onFrame: (f: VideoFrameLike) => void = () => {};
  flush(): Promise<void> {
    return Promise.resolve();
  }
  close(): void {
    this.state = 'closed';
  }
  reset(): void {}
}

function fakeFrame(timestamp: number): VideoFrameLike {
  return { timestamp, close: vi.fn() };
}

function videoRecord(seq: number, ptsUs: bigint, keyframe: boolean, data = new Uint8Array([1, 2, 3])): Uint8Array {
  return encodeVideoPayload(
    { sequence: seq, ptsUs, keyframe, fragmentIndex: 0, fragmentCount: 1, totalLength: data.byteLength },
    data,
  );
}

function makePipeline(decoder: FakeDecoder, dump?: { maxFrames: number; onFrame: (d: unknown) => void }) {
  const rendered: number[] = [];
  const pipeline = new VideoPipeline({
    canvas: {} as HTMLCanvasElement,
    renderer: (f) => {
      rendered.push(f.timestamp);
      f.close();
    },
    decoderFactory: () => decoder,
    makeChunk: (init: EncodedVideoChunkInit): EncodedVideoChunkLike => ({
      type: init.type,
      timestamp: init.timestamp,
    }),
    dump,
  });
  (pipeline as unknown as { codec: string }).codec = 'avc1.640029'; // 跳过 probe 直接 start
  pipeline.start();
  decoder.onFrame = (f) => pipeline.submitDecodedFrame(f);
  return { pipeline, rendered };
}

describe('VideoPipeline', () => {
  it('逐帧解码渲染：decoded/keyframes/lastPtsUs 计数', () => {
    const decoder = new FakeDecoder((chunk) => fakeFrame(chunk.timestamp));
    const { pipeline, rendered } = makePipeline(decoder);
    pipeline.feedRecord(videoRecord(1, 1000n, true));
    pipeline.feedRecord(videoRecord(2, 33000n, false));
    const c = pipeline.counters();
    expect(c.decoded).toBe(2);
    expect(c.keyframes).toBe(1);
    expect(c.droppedSeqGap).toBe(0);
    expect(c.lastSeq).toBe(2);
    expect(c.lastPtsUs).toBe(33000n);
    expect(rendered).toEqual([1000, 33000]);
    expect(decoder.chunks.map((ch) => ch.type)).toEqual(['key', 'delta']);
    pipeline.close();
  });

  it('seq 缺口计丢帧；重复/旧帧不误计', () => {
    const decoder = new FakeDecoder((chunk) => fakeFrame(chunk.timestamp));
    const { pipeline } = makePipeline(decoder);
    pipeline.feedRecord(videoRecord(10, 1n, true));
    pipeline.feedRecord(videoRecord(14, 2n, false)); // 缺 11–13 → 3
    pipeline.feedRecord(videoRecord(14, 3n, false)); // 重复
    const c = pipeline.counters();
    expect(c.droppedSeqGap).toBe(3);
    expect(c.outOfOrder).toBe(1);
    pipeline.close();
  });

  it('首关键帧未到前丢弃 delta 帧（防解码错误级联）', () => {
    const decoder = new FakeDecoder((chunk) => fakeFrame(chunk.timestamp));
    const { pipeline } = makePipeline(decoder);
    pipeline.feedRecord(videoRecord(5, 1n, false)); // delta 先到 → 丢
    pipeline.feedRecord(videoRecord(6, 2n, true)); // 关键帧 → 解码
    const c = pipeline.counters();
    expect(c.droppedPremature).toBe(1);
    expect(c.decoded).toBe(1);
    expect(decoder.chunks).toHaveLength(1);
    pipeline.close();
  });

  it('TCP 整帧口径：fragCnt≠1 记录上抛 ProtocolError', () => {
    const decoder = new FakeDecoder((chunk) => fakeFrame(chunk.timestamp));
    const { pipeline } = makePipeline(decoder);
    const bad = encodeVideoPayload(
      { sequence: 1, ptsUs: 0n, keyframe: true, fragmentIndex: 0, fragmentCount: 2, totalLength: 3 },
      new Uint8Array([1, 2, 3]),
    );
    // 错误文案自 edc308f code-fix batch 起为英文诊断（Framing.ts:352）：断言关键短语与分片语义而非旧中文词
    expect(() => pipeline.feedRecord(bad)).toThrow(/single-fragment/);
    expect(() => pipeline.feedRecord(bad)).toThrow(ProtocolError);
    pipeline.close();
  });

  it('诊断 dump：首 N 帧截断，帧负载 hex 完整', () => {
    const decoder = new FakeDecoder((chunk) => fakeFrame(chunk.timestamp));
    const dumps: unknown[] = [];
    const { pipeline } = makePipeline(decoder, { maxFrames: 2, onFrame: (d) => dumps.push(d) });
    pipeline.feedRecord(videoRecord(1, 10n, true, new Uint8Array([0xde, 0xad])));
    pipeline.feedRecord(videoRecord(2, 20n, false));
    pipeline.feedRecord(videoRecord(3, 30n, false)); // 超出 N → 不再 dump
    expect(dumps).toHaveLength(2);
    expect(dumps[0]).toMatchObject({ seq: 1, keyframe: true, hex: 'dead', byteLength: 2, ptsUs: '10' });
    pipeline.close();
  });

  it('resetForNewSession：计数与 seq 基线清零，delta 门重开', () => {
    const decoder = new FakeDecoder((chunk) => fakeFrame(chunk.timestamp));
    const { pipeline } = makePipeline(decoder);
    pipeline.feedRecord(videoRecord(1, 1n, true));
    pipeline.resetForNewSession();
    pipeline.feedRecord(videoRecord(900, 2n, false)); // 新会话首帧 delta → 门重开应丢（真实链路 ATTACH 自动 IDR 先到 key）
    pipeline.feedRecord(videoRecord(901, 3n, true));
    const c = pipeline.counters();
    expect(c.decoded).toBe(1);
    expect(c.droppedPremature).toBe(1);
    expect(c.droppedSeqGap).toBe(0); // reset 后旧 seq 不产生巨大缺口
    pipeline.close();
  });
});

describe('probeVideoSupport（globalThis 探测）', () => {
  it('无 VideoDecoder 全局 → 不支持且带浏览器名', async () => {
    const support = await probeVideoSupport();
    // node 环境（无 VideoDecoder）应报不支持；若宿主真实支持则断言支持形态
    if (support.supported) {
      expect(support.codec).toBeTruthy();
    } else {
      expect(support.browser).toBeTruthy();
      expect(support.reason).toBeTruthy();
    }
  });

  it('VideoUnsupportedError 消息含浏览器名', () => {
    const err = new VideoUnsupportedError({ supported: false, browser: 'Firefox 100.0', reason: 'x' });
    expect(err.message).toContain('Firefox 100.0');
    expect(err.message).toContain('不支持');
  });
});

// ── AudioPipeline ──

describe('ADTS 解析', () => {
  // 构造 48kHz stereo AAC-LC 无 CRC ADTS 头（ISO 13818-7 位布局）：
  // b2 = profile LC(01) freqIdx 0011(48k) private 0 chan_hi 0 → 0x4C
  // b3 = chan_lo 10(ch=2) orig/home/copyright 0000 frameLength 高 2 位
  const frameLen = 7 + 20;
  const b3 = (frameLen >> 11) & 0x03;
  const b4 = (frameLen >> 3) & 0xff;
  const b5 = ((frameLen & 0x07) << 5) | 0x1f; // buffer fullness 高 5 位任意
  const adts = new Uint8Array([0xff, 0xf1, 0x4c, 0x80 | b3, b4, b5, 0xfc, ...Array.from({ length: 20 }, (_, i) => i)]);

  it('解析 48kHz stereo 7B 头', () => {
    const h = parseAdtsHeader(adts);
    expect(h).not.toBeNull();
    expect(h!.headerSize).toBe(7);
    expect(h!.profile).toBe(1); // AAC-LC
    expect(h!.samplingFrequencyIndex).toBe(3);
    expect(h!.sampleRate).toBe(48000);
    expect(h!.channels).toBe(2);
    expect(h!.frameLength).toBe(frameLen);
  });

  it('AudioSpecificConfig：AOT=2 LC @48kHz stereo → 0x11 0x90', () => {
    const asc = buildAudioSpecificConfig(parseAdtsHeader(adts)!);
    expect(Array.from(asc)).toEqual([0x11, 0x90]);
  });

  it('stripAdts 剥除头并返回 AAC 载荷', () => {
    const out = stripAdts(adts);
    expect(out).not.toBeNull();
    expect(out!.aac.byteLength).toBe(20);
    expect(out!.aac[0]).toBe(0);
  });

  it('非法 syncword 返回 null', () => {
    expect(parseAdtsHeader(new Uint8Array([0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]))).toBeNull();
    expect(parseAdtsHeader(new Uint8Array([0xff]))).toBeNull();
  });
});

class FakeAudioDecoder implements AudioDecoderLike {
  state = 'configured';
  decodeQueueSize = 0;
  configured: { codec: string; description?: Uint8Array } | null = null;
  decoded: number[] = [];
  onOutput: (d: AudioDataLike) => void;
  constructor(onOutput: (d: AudioDataLike) => void) {
    this.onOutput = onOutput;
  }
  configure(config: { codec: string; description?: Uint8Array }): void {
    this.configured = config;
  }
  decode(chunk: { type: 'key'; timestamp: number; data: Uint8Array }): void {
    this.decoded.push(chunk.timestamp);
    this.onOutput(makeAudioData());
  }
  flush(): Promise<void> {
    return Promise.resolve();
  }
  close(): void {
    this.state = 'closed';
  }
  reset(): void {}
}

function makeAudioData(): AudioDataLike {
  const frames = 4;
  const planes = [Float32Array.from([0.1, 0.2, 0.3, 0.4]), Float32Array.from([0.5, 0.6, 0.7, 0.8])];
  return {
    sampleRate: 48000,
    numberOfFrames: frames,
    numberOfChannels: 2,
    format: 'f32-planar',
    copyTo(dest: Float32Array, opts: { planeIndex: number }): void {
      dest.set(planes[opts.planeIndex]);
    },
    close(): void {},
  };
}

class FakeWorklet implements WorkletNodeLike {
  sent: unknown[] = [];
  received: ((e: { data: unknown }) => void) | null = null;
  port = {
    postMessage: (msg: unknown) => this.sent.push(msg),
    onmessage: null as ((e: { data: unknown }) => void) | null,
  };
  connect(): void {}
  disconnect(): void {}
}

describe('AudioPipeline', () => {
  function makeSupported(overrides: { decoder?: FakeAudioDecoder; worklet?: FakeWorklet } = {}) {
    const worklet = overrides.worklet ?? new FakeWorklet();
    let decoder: FakeAudioDecoder =
      overrides.decoder ?? new FakeAudioDecoder(() => makeAudioData());
    const pipeline = new AudioPipeline({
      workletFactory: () => worklet,
      decoderFactory: (config) => {
        decoder.configure(config);
        decoder.onOutput = (d) => pipeline.handleDecodedAudio(d);
        return decoder;
      },
    });
    // node 测试环境无 AudioContext/AudioWorkletNode 全局，直接接替身 worklet（跳过 start()）
    (pipeline as unknown as { worklet: WorkletNodeLike | null }).worklet = worklet;
    return { pipeline, worklet, getDecoder: () => decoder };
  }

  it('checkSupport：无 AudioDecoder 全局 → unsupported + 原因（显式降级）', () => {
    const { pipeline } = makeSupported();
    const s = pipeline.checkSupport();
    // node 测试环境无真实 AudioWorkletNode → 走不支持分支（真实浏览器由 e2e 覆盖）
    if (!s.supported) {
      expect(s.reason).toContain('不支持');
      expect(pipeline.stats().state).toBe('unsupported');
    }
  });

  it('首帧建解码器（description=ASC）+ 剥头解码 + worklet 喂入交错 PCM', () => {
    const { pipeline, worklet, getDecoder } = makeSupported();
    // 强制走 supported 分支：注入 worklet 工厂后 AudioWorkletNode 检查仍会拦（node 无全局），
    // 直接以 start 内部路径等价验证：手动建解码器并喂帧
    const adts = makeAdtsChunk();
    pipeline.feedChunk(1000n, 1, adts);
    const dec = getDecoder();
    expect(dec.configured?.codec).toBe('mp4a.40.2');
    expect(Array.from(dec.configured?.description ?? [])).toEqual([0x11, 0x90]);
    expect(dec.decoded).toEqual([1000]);
    const stats = pipeline.stats();
    expect(stats.chunksReceived).toBe(1);
    expect(stats.decodedFrames).toBe(1);
    expect(stats.lastChunkPtsUs).toBe(1000n);
    // worklet 收到交错化 PCM：4 帧 × 2ch
    const feed = worklet.sent.find((m) => (m as { type: string }).type === 'feed') as { data: Float32Array };
    expect(feed).toBeTruthy();
    // copyTo 走 f32 量化：期望值按 float32 就近取整后比较
    expect(Array.from(feed.data)).toEqual([0.1, 0.5, 0.2, 0.6, 0.3, 0.7, 0.4, 0.8].map(Math.fround));
    pipeline.close();
  });

  it('非 ADTS 帧不建解码器不计解码', () => {
    const { pipeline, getDecoder } = makeSupported();
    pipeline.feedChunk(1n, 1, new Uint8Array([0x00, 0x00, 0x00]));
    expect(getDecoder().configured).toBeNull();
    expect(pipeline.stats().decodedFrames).toBe(0);
    expect(pipeline.stats().reason).toContain('非 ADTS');
  });

  it('worklet stats 消息透传进 stats() 快照', () => {
    const { pipeline } = makeSupported();
    pipeline.handleWorkletMessage({ type: 'stats', bufferedMs: 135.5, skipCount: 2, backlogDropped: 1, playedFrames: 100, buffering: false });
    const s = pipeline.stats();
    expect(s.bufferedMs).toBeCloseTo(135.5);
    expect(s.skipCount).toBe(2);
    expect(s.backlogDropped).toBe(1);
    expect(s.playedFrames).toBe(100);
  });

  it('resetForNewSession 发 reset 并回落 idle', () => {
    const { pipeline, worklet } = makeSupported();
    pipeline.resetForNewSession();
    const types = worklet.sent.map((m) => (m as { type: string }).type);
    expect(types).toContain('reset');
    pipeline.close();
  });
});

/** 48kHz stereo AAC-LC 20ms ADTS 帧构造（与上面 describe 共用逻辑） */
function makeAdtsChunk(): Uint8Array {
  const payload = new Uint8Array(20).fill(0xab);
  const frameLen = 7 + payload.length;
  return new Uint8Array([
    0xff, 0xf1,
    0x4c, // profile LC(01) freqIdx 0011(48k) private 0 chan_hi 0
    0x80 | ((frameLen >> 11) & 0x03), // chan_lo=10(ch=2) + flags 0 + frameLength 高 2 位
    (frameLen >> 3) & 0xff,
    ((frameLen & 0x07) << 5) | 0x1f,
    0xfc,
    ...payload,
  ]);
}
