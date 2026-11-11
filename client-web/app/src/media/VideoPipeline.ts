/**
 * 视频管线（WebCodecs VideoDecoder + canvas 渲染）与可观测性。
 *
 * 设计依据：design.md D4（Annex B + optimizeForLatency + prefer-hardware、逐帧
 * decode-then-render 不加缓冲）、任务 3.1/3.2（isConfigSupported 探测不支持→含浏览器名
 * 的错误页；decoded/dropped/decodeQueueSize/关键帧四级计数 + 诊断帧 dump）。
 *
 * 输入：网关 WS 下行的 0x02 视频记录（TCP 流模式整帧口径，Framing.decodeTcpVideoFrame）。
 * 输出：逐帧绘制到 canvas（渲染后 close 不持引用）。
 *
 * WebCodecs 类型不直接引用全局 DOM 声明（不同 TS 版本 lib.dom 覆盖不一），统一走
 * 结构化最小接口 + globalThis 探测，保证 tsc 严格模式确定性通过。
 */

import { ProtocolError, SequenceTracker, decodeTcpVideoFrame } from '../protocol/Framing';

// ── WebCodecs 最小结构类型（与 W3C WebCodecs Level 1 对齐的子集）──

export interface EncodedVideoChunkInit {
  type: 'key' | 'delta';
  timestamp: number;
  data: Uint8Array | ArrayBuffer;
}

export interface EncodedVideoChunkLike {
  readonly type: 'key' | 'delta';
  readonly timestamp: number;
}

export interface VideoFrameLike {
  readonly timestamp: number;
  close(): void;
}

export interface VideoDecoderConfigLike {
  codec: string;
  avc?: { format: 'annexb' | 'avc' };
  optimizeForLatency?: boolean;
  hardwareAcceleration?: 'no-preference' | 'prefer-hardware' | 'prefer-software';
}

export interface VideoDecoderLike {
  decode(chunk: EncodedVideoChunkLike): void;
  flush(): Promise<void>;
  close(): void;
  reset(): void;
  readonly state: string;
  readonly decodeQueueSize: number;
}

/** WebCodecs 全局探测（结构性访问，避免依赖 lib.dom 版本） */
interface WebCodecsGlobal {
  VideoDecoder?: new (
    init: { output: (frame: VideoFrameLike) => void; error: (err: Error) => void },
  ) => VideoDecoderLike;
  EncodedVideoChunk?: new (init: EncodedVideoChunkInit) => EncodedVideoChunkLike;
}

function webcodecs(): WebCodecsGlobal {
  return globalThis as unknown as WebCodecsGlobal;
}

/** 探测/解码用的 H.264 codec 串（High Profile 优先，按 SPS 实测档位可修正——design D4） */
const CANDIDATE_CODECS = ['avc1.640029', 'avc1.64002a', 'avc1.640033', 'avc1.42e01e'] as const;

/** 浏览器名与版本（错误页展示用） */
export function browserName(): string {
  const ua = globalThis.navigator?.userAgent ?? '';
  const table: Array<[RegExp, string]> = [
    [/Edg\/([\d.]+)/, 'Microsoft Edge'],
    [/OPR\/([\d.]+)/, 'Opera'],
    [/Chrome\/([\d.]+)/, 'Chrome'],
    [/Firefox\/([\d.]+)/, 'Firefox'],
    [/Version\/([\d.]+).*Safari/, 'Safari'],
  ];
  for (const [re, name] of table) {
    const m = ua.match(re);
    if (m) return `${name} ${m[1]}`;
  }
  return ua ? `未知浏览器（UA: ${ua.slice(0, 80)}）` : '未知浏览器';
}

export interface VideoSupport {
  supported: boolean;
  /** 支持时选中的 codec 串 */
  codec?: string;
  /** 支持时被 isConfigSupported 接受的完整配置（start() 须用同一配置，防运行时拒绝） */
  config?: VideoDecoderConfigLike;
  browser: string;
  reason?: string;
}

/**
 * isConfigSupported 探测：VideoDecoder 存在性 + 候选 codec × 硬解偏好矩阵。
 * prefer-hardware 在无 GPU/无头环境常被整档拒绝（实测 Chromium headless：hardware
 * 档 false 而 no-preference / prefer-software 档 true），故逐级回退并把被接受的
 * 完整配置带回——start() 用同一配置构造解码器，防探测通过、运行时 configure 拒绝。
 * spec Scenario「不支持时显式报错」：结果由调用方呈现为含浏览器名的错误页。
 */
export async function probeVideoSupport(): Promise<VideoSupport> {
  const browser = browserName();
  const { VideoDecoder } = webcodecs();
  if (!VideoDecoder) {
    return { supported: false, browser, reason: '浏览器不支持 WebCodecs VideoDecoder' };
  }
  const probe = VideoDecoder as unknown as {
    isConfigSupported?: (config: VideoDecoderConfigLike) => Promise<{ supported?: boolean }>;
  };
  if (typeof probe.isConfigSupported !== 'function') {
    return { supported: false, browser, reason: 'VideoDecoder.isConfigSupported 不可用' };
  }
  const hwPrefs = ['prefer-hardware', 'no-preference', 'prefer-software'] as const;
  let lastReason = '无可用 H.264 解码配置';
  for (const codec of CANDIDATE_CODECS) {
    for (const hardwareAcceleration of hwPrefs) {
      const config: VideoDecoderConfigLike = {
        codec,
        avc: { format: 'annexb' },
        optimizeForLatency: true,
        hardwareAcceleration,
      };
      try {
        const result = await probe.isConfigSupported(config);
        if (result?.supported) return { supported: true, codec, config, browser };
        lastReason = `isConfigSupported(${codec}, ${hardwareAcceleration}) = false`;
      } catch (err) {
        lastReason = `isConfigSupported(${codec}, ${hardwareAcceleration}) 抛错: ${(err as Error).message}`;
      }
    }
  }
  return { supported: false, browser, reason: lastReason };
}

/** 不支持错误（main.ts 据此渲染含浏览器名的错误页，而非黑屏等待） */
export class VideoUnsupportedError extends Error {
  constructor(public readonly support: VideoSupport) {
    super(`当前浏览器不支持硬解（${support.browser}）：${support.reason ?? '未知原因'}`);
    this.name = 'VideoUnsupportedError';
  }
}

// ── 可观测性 ──

export interface VideoPipelineCounters {
  /** 已解码并提交渲染的帧数 */
  decoded: number;
  /** seq 缺口计丢帧数（源端主动丢帧以缺口形式出现） */
  droppedSeqGap: number;
  /** 结构性丢弃（首关键帧未到时到达的 delta 帧等） */
  droppedPremature: number;
  /** 渲染丢弃（canvas 2D 上下文不可用等渲染路径失败，帧未上屏） */
  renderDropped: number;
  /** 关键帧（IDR）帧数 */
  keyframes: number;
  /** 解码器排队深度（decodeQueueSize 快照） */
  decodeQueueSize: number;
  /** 最近帧 seq（null = 尚未收到） */
  lastSeq: number | null;
  /** 重复/回绕旧帧计数 */
  outOfOrder: number;
  /** 最近解码时间戳（µs，源端钟） */
  lastPtsUs: bigint | null;
}

/** 诊断帧 dump（首 N 帧原始负载，供延迟专项比对；JSON 序列化友好） */
export interface FrameDump {
  seq: number;
  ptsUs: string;
  keyframe: boolean;
  byteLength: number;
  /** hex 编码的帧负载（仅 dump 开启时逐帧上报） */
  hex: string;
  capturedAtMs: number;
}

export interface DumpSink {
  maxFrames: number;
  onFrame(dump: FrameDump): void;
}

export interface VideoPipelineOptions {
  canvas: HTMLCanvasElement;
  /** 渲染器（缺省 canvas 2D drawImage 后 close；测试注入替身） */
  renderer?: (frame: VideoFrameLike) => void;
  /** EncodedVideoChunk 构造器（缺省真实全局；测试注入替身） */
  makeChunk?: (init: EncodedVideoChunkInit) => EncodedVideoChunkLike;
  /** 已配置解码器工厂（双参：codec + 探测通过的完整 config，注入工厂同样复用探测结果；缺省构造真实 VideoDecoder；测试注入替身） */
  decoderFactory?: (codec: string, config?: VideoDecoderConfigLike) => VideoDecoderLike;
  /** 诊断帧 dump 开关（首 N 帧原始负载） */
  dump?: DumpSink;
  /** 每帧解码输出回调（HUD 延迟采样接入点；渲染前调用） */
  onFrameDecoded?: (ptsUs: bigint) => void;
  /** 解码器错误回调 */
  onError?: (err: Error) => void;
}

export class VideoPipeline {
  private codec: string | null = null;
  private acceptedConfig: VideoDecoderConfigLike | null = null;
  private decoder: VideoDecoderLike | null = null;
  private readonly seqTracker = new SequenceTracker();
  private countersInternal: VideoPipelineCounters = this.blankCounters();
  private firstKeyframeSeen = false;
  private dumpedFrames = 0;
  /** start() 是否已成功建立过解码器（区分 dump-only 诊断模式与致命错误恢复等待期） */
  private started = false;
  /** canvas 2D 上下文缓存（undefined = 未探测；null = 探测失败，如被 webgl 等其它上下文类型占用） */
  private ctx2d: CanvasRenderingContext2D | null | undefined = undefined;

  constructor(private readonly opts: VideoPipelineOptions) {}

  private blankCounters(): VideoPipelineCounters {
    return {
      decoded: 0,
      droppedSeqGap: 0,
      droppedPremature: 0,
      renderDropped: 0,
      keyframes: 0,
      decodeQueueSize: 0,
      lastSeq: null,
      outOfOrder: 0,
      lastPtsUs: null,
    };
  }

  /** 探测并选定 codec（可注入已完成的探测结果复用）；不支持抛 VideoUnsupportedError（含浏览器名） */
  async probe(support?: VideoSupport): Promise<VideoSupport> {
    const result = support ?? (await probeVideoSupport());
    if (!result.supported) throw new VideoUnsupportedError(result);
    this.codec = result.codec ?? CANDIDATE_CODECS[0];
    this.acceptedConfig = result.config ?? null;
    return result;
  }

  /** 构造并配置 VideoDecoder（Annex B + optimizeForLatency + prefer-hardware） */
  start(): void {
    if (!this.codec) throw new ProtocolError('VideoPipeline.probe() 未完成');
    if (this.decoder) return;
    this.buildDecoder();
    this.started = true;
    this.countersInternal = this.blankCounters();
    this.firstKeyframeSeen = false;
  }

  /** 构造并配置解码器（start 与致命错误恢复共用：复用探测通过的 codec/config 重走 configure） */
  private buildDecoder(): void {
    const codec = this.codec;
    if (!codec) throw new ProtocolError('VideoPipeline.probe() 未完成');
    const factory = this.opts.decoderFactory ?? this.defaultDecoderFactory();
    this.decoder = factory(codec, this.acceptedConfig ?? undefined);
  }

  /** 解码器失效处理：close 容错、置空并重开关键帧门（下一关键帧到达 feedRecord 时重建恢复） */
  private invalidateDecoder(): void {
    try {
      this.decoder?.close();
    } catch {
      // 已 closed 状态重复 close 容忍
    }
    this.decoder = null;
    // 重开关键帧门：重建后的解码器须等新关键帧，期间 delta 帧走既有 droppedPremature 丢弃路径
    this.firstKeyframeSeen = false;
  }

  private defaultDecoderFactory() {
    return (_codec: string, config?: VideoDecoderConfigLike): VideoDecoderLike => {
      const { VideoDecoder } = webcodecs();
      if (!VideoDecoder) throw new VideoUnsupportedError(missingSupport());
      const dec = new VideoDecoder({
        output: (frame) => this.renderFrame(frame),
        error: (err) => {
          // WebCodecs 致命错误后 decoder 进入 closed：标记失效（重开关键帧门，下一关键帧重建恢复），再透传错误
          this.invalidateDecoder();
          this.opts.onError?.(err);
        },
      }) as unknown as VideoDecoderLike & { configure(cfg: VideoDecoderConfigLike): void };
      // 探测接受的完整配置原样下发（Annex B + optimizeForLatency + 探测通过的硬解档位）
      dec.configure(
        config ?? {
          codec: this.codec ?? CANDIDATE_CODECS[0],
          avc: { format: 'annexb' },
          optimizeForLatency: true,
          hardwareAcceleration: 'no-preference',
        },
      );
      return dec;
    };
  }

  private renderFrame(frame: VideoFrameLike): void {
    // 逐帧 decode-then-render：绘制后立即 close，不持帧引用（设计 D4 时序纪律）。
    // 全函数体纳入 try-finally：BigInt 转换或 onFrameDecoded 回调抛错时也保证 frame.close()，
    // 防解码输出 VideoFrame 底层资源泄漏。
    try {
      this.countersInternal.decoded += 1;
      this.countersInternal.lastPtsUs = BigInt(Math.round(frame.timestamp));
      this.opts.onFrameDecoded?.(this.countersInternal.lastPtsUs);
      if (this.opts.renderer) this.opts.renderer(frame);
      else this.drawToCanvas(frame);
    } finally {
      frame.close();
    }
  }

  /** 解码器输出帧入口（真实 WebCodecs 回调与测试替身共用） */
  submitDecodedFrame(frame: VideoFrameLike): void {
    this.renderFrame(frame);
  }

  /**
   * 喂入一条 0x02 视频记录载荷（TCP 流模式整帧口径）。
   * 解码失败结构性错误（fragCnt≠1 等）原样上抛 ProtocolError 由会话层处置；
   * 首关键帧未到前丢弃 delta 帧（解码器不可解 delta，防错误级联）。
   */
  feedRecord(payload: Uint8Array): void {
    const { header, data } = decodeTcpVideoFrame(payload);
    const obs = this.seqTracker.observe(header.sequence);
    if (obs.dropped > 0) this.countersInternal.droppedSeqGap += obs.dropped;
    if (obs.outOfOrder) this.countersInternal.outOfOrder += 1;
    if (!obs.outOfOrder) this.countersInternal.lastSeq = header.sequence;

    if (header.keyframe) this.countersInternal.keyframes += 1;
    if (!this.firstKeyframeSeen) {
      if (!header.keyframe) {
        this.countersInternal.droppedPremature += 1;
        return;
      }
      this.firstKeyframeSeen = true;
    }

    if (this.opts.dump && this.dumpedFrames < this.opts.dump.maxFrames) {
      this.dumpedFrames += 1;
      this.opts.dump.onFrame({
        seq: header.sequence,
        ptsUs: header.ptsUs.toString(),
        keyframe: header.keyframe,
        byteLength: data.byteLength,
        hex: BufferLike_hex(data),
        capturedAtMs: Date.now(),
      });
    }

    // decode 前守卫：致命错误后 decoder 置 closed（error 回调未及触发的兜底检测），
    // 走失效重建路径，防每次 decode 同步抛 TypeError 且帧静默蒸发
    if (this.decoder && this.decoder.state === 'closed') {
      this.invalidateDecoder();
      this.opts.onError?.(new Error('VideoDecoder 已 closed（致命错误兜底检测），等待下一关键帧重建'));
    }

    if (!this.decoder) {
      if (!this.started) {
        // dump-only 模式（start 前诊断）：显式计数，防帧静默蒸发不可定位
        this.countersInternal.droppedPremature += 1;
        return;
      }
      if (!header.keyframe) {
        // 解码器失效恢复等待期：delta 帧走既有丢弃路径并计数（关键帧门已重开，此为重建曾失败时的可达分支）
        this.countersInternal.droppedPremature += 1;
        return;
      }
      // 下一关键帧到达：重走 configure 重建解码器（复用探测通过的 codec/config）
      this.buildDecoder();
      if (!this.decoder) return; // buildDecoder 抛错时不可达；防御返回
    }

    const makeChunk =
      this.opts.makeChunk ??
      ((init: EncodedVideoChunkInit): EncodedVideoChunkLike => {
        const Ctor = webcodecs().EncodedVideoChunk;
        if (!Ctor) throw new ProtocolError('EncodedVideoChunk 不可用');
        return new Ctor(init);
      });
    this.decoder.decode(
      makeChunk({
        type: header.keyframe ? 'key' : 'delta',
        timestamp: Number(header.ptsUs), // 源端单调钟 µs，值域远小于 2^53，number 无损
        data: data,
      }),
    );
    this.countersInternal.decodeQueueSize = this.decoder?.decodeQueueSize ?? 0;
  }

  /** 计数快照（HUD 轮询用） */
  counters(): VideoPipelineCounters {
    return {
      ...this.countersInternal,
      decodeQueueSize: this.decoder?.decodeQueueSize ?? 0,
    };
  }

  /** 会话切换/ATTACH：重置 seq 基线与关键帧门（不重建解码器，attach 自动 IDR 喂新关键帧） */
  resetForNewSession(): void {
    this.seqTracker.reset();
    this.firstKeyframeSeen = false;
    this.countersInternal = this.blankCounters();
    this.dumpedFrames = 0;
    this.decoder?.reset();
  }

  close(): void {
    try {
      this.decoder?.close();
    } catch {
      // 已关闭状态重复 close 容忍
    }
    this.decoder = null;
    this.started = false; // 关闭后不得经恢复路径重建出新解码器
  }

  /** canvas 2D 逐帧绘制（VideoFrame 可被 drawImage 接受；尺寸由会话参数设定）。
   *  2D 上下文缓存为字段（省逐帧 getContext 开销）；上下文不可用（如被 webgl 占用）时
   *  计入 renderDropped，仅首次失败经 onError 上报，防静默吞帧与逐帧刷屏。 */
  private drawToCanvas(frame: VideoFrameLike): void {
    if (this.ctx2d === undefined) {
      this.ctx2d = this.opts.canvas.getContext('2d');
      if (!this.ctx2d) {
        this.opts.onError?.(new Error('canvas 2D 上下文不可用（可能被其它上下文类型占用），渲染帧将丢弃'));
      }
    }
    if (!this.ctx2d) {
      this.countersInternal.renderDropped += 1;
      return;
    }
    this.ctx2d.drawImage(frame as unknown as CanvasImageSource, 0, 0);
  }
}

// Uint8Array → hex（诊断 dump；不引 Buffer 保持浏览器/双端一致）
function BufferLike_hex(bytes: Uint8Array): string {
  let out = '';
  for (let i = 0; i < bytes.byteLength; i++) out += bytes[i].toString(16).padStart(2, '0');
  return out;
}

function missingSupport(): VideoSupport {
  return { supported: false, browser: browserName(), reason: 'VideoDecoder 全局缺失' };
}
