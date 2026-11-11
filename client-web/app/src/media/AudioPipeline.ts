/**
 * 音频管线（AUDIO_CHUNK → ADTS 剥头 → AudioDecoder → AudioWorklet 环形缓冲）。
 *
 * 设计依据：design.md D5、任务 4.1/4.2：
 * - 首帧解析 ADTS 头取 profile/采样率/声道，构造 AudioSpecificConfig（2 字节）作
 *   AudioDecoder description，之后剥除每帧 7/9B ADTS 头喂 AudioDecoder('mp4a.40.2')；
 * - 播放：AudioWorklet 环形缓冲，起播水位 120ms、不足即 skip 并计数（对齐协议 §3.13
 *   客户端口径，与 Android AudioPlayer/Windows AudioSink 一致）；
 * - 源端静音抑制（3s 停发 + 1 帧/s keepalive）天然兼容；
 * - `window.AudioDecoder` 缺失 → 显式降级：状态置 disabled + 原因提示，视频触控不受影响。
 */

// ── WebCodecs 音频最小结构类型（同 VideoPipeline：走 globalThis 结构探测）──

export interface AudioDecoderConfig {
  codec: string;
  description?: Uint8Array;
  sampleRate?: number;
  numberOfChannels?: number;
}

export interface AudioDecoderLike {
  configure(config: AudioDecoderConfig): void;
  decode(chunk: { type: 'key'; timestamp: number; data: Uint8Array }): void;
  flush(): Promise<void>;
  close(): void;
  reset(): void;
  readonly decodeQueueSize: number;
  readonly state: string;
}

export interface AudioDataLike {
  readonly sampleRate: number;
  readonly numberOfFrames: number;
  readonly numberOfChannels: number;
  readonly format: string | null;
  /** f32-planar 通道数据拷贝目标 */
  copyTo(destination: Float32Array, options: { planeIndex: number }): void;
  close(): void;
}

interface WebCodecsAudioGlobal {
  AudioDecoder?: new (init: {
    output: (data: AudioDataLike) => void;
    error: (err: Error) => void;
  }) => AudioDecoderLike;
  EncodedAudioChunk?: new (init: { type: 'key'; timestamp: number; data: Uint8Array | ArrayBuffer }) => unknown;
  AudioWorkletNode?: new (context: BaseAudioContext, name: string, options?: { numberOfOutputs?: number; outputChannelCount?: number[] }) => {
    port: {
      postMessage(msg: unknown): void;
      onmessage: ((e: { data: unknown }) => void) | null;
    };
    connect(node: AudioNode): void;
    disconnect(): void;
  };
}

function audioGlobals(): WebCodecsAudioGlobal {
  return globalThis as unknown as WebCodecsAudioGlobal;
}

// ── ADTS 解析（AAC ADTS: ISO 13818-7）──

/** AAC 采样率表（sampling_frequency_index） */
const AAC_SAMPLE_RATES = [
  96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
] as const;

export interface AdtsHeader {
  /** ADTS 头长度（protection_absent=1 → 7B，=0 → 9B 含 CRC） */
  headerSize: number;
  /** AAC profile（audio_object_type - 1；1 = AAC-LC） */
  profile: number;
  samplingFrequencyIndex: number;
  sampleRate: number;
  channels: number;
  /** 整帧长度（含头） */
  frameLength: number;
}

/** 解析 ADTS 头；非法（syncword 错/越界）返回 null */
export function parseAdtsHeader(buf: Uint8Array): AdtsHeader | null {
  if (buf.byteLength < 7) return null;
  if (buf[0] !== 0xff || (buf[1] & 0xf6) !== 0xf0) return null; // syncword 0xFFF + ID=0(MPEG-4) + layer=0
  const protectionAbsent = (buf[1] & 0x01) !== 0;
  const profile = (buf[2] >> 6) & 0x03;
  const samplingFrequencyIndex = (buf[2] >> 2) & 0x0f;
  const channels = ((buf[2] & 0x01) << 2) | ((buf[3] >> 6) & 0x03);
  const frameLength = ((buf[3] & 0x03) << 11) | (buf[4] << 3) | ((buf[5] >> 5) & 0x07);
  if (samplingFrequencyIndex >= AAC_SAMPLE_RATES.length) return null;
  if (channels === 0 || channels > 2) return null; // 本系统恒 48kHz stereo，防御性拒绝其它
  return {
    headerSize: protectionAbsent ? 7 : 9,
    profile,
    samplingFrequencyIndex,
    sampleRate: AAC_SAMPLE_RATES[samplingFrequencyIndex],
    channels,
    frameLength,
  };
}

/** AudioSpecificConfig（2B）：AOT-1(5b) + freqIndex(4b) + channelConfig(4b) */
export function buildAudioSpecificConfig(h: AdtsHeader): Uint8Array {
  const aot = h.profile + 1; // ADTS profile 字段 = AOT - 1
  const b0 = (aot << 3) | (h.samplingFrequencyIndex >> 1);
  const b1 = ((h.samplingFrequencyIndex & 1) << 7) | (h.channels << 3);
  return new Uint8Array([b0, b1]);
}

/** 从 AUDIO_CHUNK 载荷剥除 ADTS 头；返回 raw AAC 访问单元（非法帧返回 null） */
export function stripAdts(payload: Uint8Array): { header: AdtsHeader; aac: Uint8Array } | null {
  const header = parseAdtsHeader(payload);
  if (!header) return null;
  if (payload.byteLength < header.headerSize) return null;
  const aac = payload.slice(header.headerSize);
  if (header.frameLength > 0 && aac.byteLength + header.headerSize > header.frameLength) {
    // 帧长字段与实际不符：按实际载荷长度解码（记录在 warn 由调用方计数）
    return { header, aac };
  }
  return { header, aac };
}

// ── 音频状态与管线 ──

export type AudioState = 'unsupported' | 'idle' | 'starting' | 'playing' | 'error';

export interface AudioStats {
  state: AudioState;
  reason: string;
  bufferedMs: number;
  skipCount: number;
  backlogDropped: number;
  playedFrames: number;
  decodedFrames: number;
  chunksReceived: number;
  /** 源端时钟秒（工作窃听用）；null = 无数据 */
  lastChunkPtsUs: bigint | null;
}

export interface AudioPipelineOptions {
  /** AudioWorklet 模块 URL（缺省同目录 audio-worklet.js，vite 资产解析） */
  workletUrl?: string;
  /** 测试注入：解码器工厂 */
  decoderFactory?: (config: AudioDecoderConfig) => AudioDecoderLike;
  /** 测试注入：worklet 节点工厂（无 AudioContext 的 node 环境单测） */
  workletFactory?: () => WorkletNodeLike;
  onError?: (err: Error) => void;
}

export interface WorkletNodeLike {
  port: {
    postMessage(msg: unknown): void;
    onmessage: ((e: { data: unknown }) => void) | null;
  };
  connect(node: unknown): void;
  disconnect(): void;
}

const CODEC_AAC_LC = 'mp4a.40.2';

export class AudioPipeline {
  private state: AudioState = 'idle';
  private reason = '';
  private decoder: AudioDecoderLike | null = null;
  private worklet: WorkletNodeLike | null = null;
  private ctx: { resume(): Promise<void>; close(): Promise<void>; destination: unknown } | null = null;
  private statsExternal: Partial<AudioStats> = {};
  private configSeen = false;
  private chunksReceived = 0;
  private decodedFrames = 0;
  private lastChunkPtsUs: bigint | null = null;
  private readonly options: Required<Pick<AudioPipelineOptions, 'workletUrl'>> & AudioPipelineOptions;

  constructor(options: AudioPipelineOptions = {}) {
    this.options = { workletUrl: options.workletUrl ?? new URL('./audio-worklet.js', import.meta.url).href, ...options };
  }

  /**
   * 能力探测（任务 4.2）：window.AudioDecoder 缺失 → disabled + 原因。
   * 视频与触控不受影响——本方法失败不抛出，状态如实上报供 UI 置灰。
   */
  checkSupport(): { supported: boolean; reason: string } {
    const g = audioGlobals();
    if (!g.AudioDecoder) {
      this.state = 'unsupported';
      this.reason = '音频不可用（浏览器不支持 WebCodecs AudioDecoder）';
      return { supported: false, reason: this.reason };
    }
    if (!g.AudioWorkletNode) {
      this.state = 'unsupported';
      this.reason = '音频不可用（浏览器不支持 AudioWorklet）';
      return { supported: false, reason: this.reason };
    }
    return { supported: true, reason: '' };
  }

  /**
   * 建立播放链路（AudioContext + worklet + decoder）。
   * unsupported 时直接返回 false（显式降级路径）；ctx.resume 由用户手势侧触发。
   */
  async start(): Promise<boolean> {
    const support = this.checkSupport();
    if (!support.supported) return false;
    this.state = 'starting';
    const g = audioGlobals();
    try {
      if (this.options.workletFactory) {
        this.worklet = this.options.workletFactory();
      } else {
        const CtxCtor = (globalThis as { AudioContext?: new () => { audioWorklet: { addModule(url: string): Promise<void> }; createGain(): { connect(dest: unknown): void; gain: { value: number } }; destination: unknown; resume(): Promise<void>; close(): Promise<void> } }).AudioContext;
        if (!CtxCtor) throw new Error('AudioContext 不可用');
        const ctx = new CtxCtor();
        await ctx.audioWorklet.addModule(this.options.workletUrl);
        const gain = ctx.createGain();
        gain.connect(ctx.destination);
        const node = new g.AudioWorkletNode!(ctx as unknown as BaseAudioContext, 'ring-buffer-player', {
          numberOfOutputs: 1,
          outputChannelCount: [2],
        });
        node.connect(gain as unknown as AudioNode);
        this.ctx = ctx;
        this.worklet = node as unknown as WorkletNodeLike;
        this.gainNode = gain;
      }
      this.worklet!.port.onmessage = (e) => this.handleWorkletMessage(e.data);
      this.state = 'idle';
      return true;
    } catch (err) {
      this.state = 'error';
      this.reason = `音频初始化失败：${(err as Error).message}`;
      this.options.onError?.(err as Error);
      return false;
    }
  }

  private gainNode: { connect(dest: unknown): void; gain: { value: number } } | null = null;

  /** worklet 统计消息入口（start() 内部接线；测试替身直接调用） */
  handleWorkletMessage(data: unknown): void {
    if (!data || typeof data !== 'object') return;
    const msg = data as { type: string; bufferedMs?: number; skipCount?: number; backlogDropped?: number; playedFrames?: number; buffering?: boolean };
    if (msg.type === 'stats') {
      this.statsExternal = {
        bufferedMs: msg.bufferedMs ?? 0,
        skipCount: msg.skipCount ?? 0,
        backlogDropped: msg.backlogDropped ?? 0,
        playedFrames: msg.playedFrames ?? 0,
      };
      if (this.state === 'idle' && (msg.bufferedMs ?? 0) >= 120) this.state = 'playing';
      if (this.state === 'playing' && msg.buffering) this.state = 'starting';
    }
  }

  /** 会话中音量 [0,1]（独立于视频；静音=0） */
  setVolume(v: number): void {
    if (this.gainNode) this.gainNode.gain.value = Math.min(1, Math.max(0, v));
  }

  /**
   * 喂入一条 AUDIO_CHUNK 控制消息载荷（ptsUs/seq/payload 已由会话层解出）。
   * 首帧解析 ADTS → 建立 AudioDecoder(description)；后续帧剥头解码入队。
   * 解码失败计数不中断（音频丢帧比延迟更伤的语义相反：坏帧跳过即可）。
   */
  feedChunk(ptsUs: bigint, seq: number, payload: Uint8Array): void {
    this.chunksReceived += 1;
    this.lastChunkPtsUs = ptsUs;
    if (this.state === 'unsupported' || this.state === 'error') return;
    const parsed = stripAdts(payload);
    if (!parsed) {
      this.reason = `非 ADTS 音频帧 seq=${seq}`;
      return;
    }
    if (!this.configSeen) {
      this.configSeen = true;
      this.createDecoder(parsed.header);
    }
    if (!this.decoder) return;
    const init = { type: 'key' as const, timestamp: Number(ptsUs), data: parsed.aac };
    // 真实浏览器须传 EncodedAudioChunk 实例；无全局（测试替身路径）直接传结构化 init
    const Ctor = audioGlobals().EncodedAudioChunk;
    const chunk = Ctor ? (new Ctor(init) as unknown as typeof init) : init;
    try {
      this.decoder.decode(chunk);
      this.decodedFrames += 1;
    } catch (err) {
      this.reason = `AAC 解码入队失败 seq=${seq}: ${(err as Error).message}`;
    }
  }

  private createDecoder(header: AdtsHeader): void {
    const g = audioGlobals();
    const description = buildAudioSpecificConfig(header);
    const config = {
      codec: CODEC_AAC_LC,
      description,
      sampleRate: header.sampleRate,
      numberOfChannels: header.channels,
    };
    const factory =
      this.options.decoderFactory ??
      ((cfg: AudioDecoderConfig) => {
        const dec = new g.AudioDecoder!({
          output: (data) => this.handleDecodedAudio(data),
          error: (err) => {
            this.state = 'error';
            this.reason = `AudioDecoder 错误：${err.message}`;
            this.options.onError?.(err);
          },
        });
        dec.configure(cfg as Parameters<AudioDecoderLike['configure']>[0]);
        return dec as unknown as AudioDecoderLike;
      });
    this.decoder = factory(config);
  }

  /** 解码输出帧入口（真实 WebCodecs 回调与测试替身共用） */
  handleDecodedAudio(data: AudioDataLike): void {
    // AudioDecoder 输出 f32-planar → 交错化 → worklet 环形缓冲
    const frames = data.numberOfFrames;
    const channels = Math.min(2, data.numberOfChannels);
    const interleaved = new Float32Array(frames * channels);
    const plane = new Float32Array(frames);
    for (let ch = 0; ch < channels; ch++) {
      data.copyTo(plane, { planeIndex: ch });
      for (let i = 0; i < frames; i++) interleaved[i * channels + ch] = plane[i];
    }
    data.close();
    this.worklet?.port.postMessage({ type: 'feed', data: interleaved });
  }

  /** 用户手势后恢复 AudioContext（浏览器自动播放策略） */
  async resumeContext(): Promise<void> {
    await this.ctx?.resume();
  }

  /** 快照（HUD 轮询） */
  stats(): AudioStats {
    return {
      state: this.state,
      reason: this.reason,
      bufferedMs: this.statsExternal.bufferedMs ?? 0,
      skipCount: this.statsExternal.skipCount ?? 0,
      backlogDropped: this.statsExternal.backlogDropped ?? 0,
      playedFrames: this.statsExternal.playedFrames ?? 0,
      decodedFrames: this.decodedFrames,
      chunksReceived: this.chunksReceived,
      lastChunkPtsUs: this.lastChunkPtsUs,
    };
  }

  /** 会话切换：清缓冲重置状态 */
  resetForNewSession(): void {
    this.worklet?.port.postMessage({ type: 'reset' });
    this.decoder?.reset();
    this.state = this.state === 'unsupported' ? 'unsupported' : 'idle';
    this.reason = '';
    this.statsExternal = {};
  }

  close(): void {
    try {
      this.decoder?.close();
    } catch {
      /* 已关闭 */
    }
    this.decoder = null;
    try {
      this.worklet?.disconnect();
      void this.ctx?.close();
    } catch {
      /* 已关闭 */
    }
    this.worklet = null;
    this.ctx = null;
  }
}
