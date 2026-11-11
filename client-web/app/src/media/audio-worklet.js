/**
 * AudioWorklet 环形缓冲播放器（audio-thread 侧）。
 *
 * 语义逐字对齐协议 §3.13 / Android AudioPlayer.kt：
 * - jitter 环形缓冲攒够起播水位才起播（吸收网络抖动），不足即保持缓冲态（不出声不消费）；
 * - 播放中欠载（无样本可出）→ skip 计数 + 回缓冲态（防断续爆音）；
 * - 积压 >800ms → feed 侧丢最旧到水位（防时钟漂移无限增长），计 backlogDropped；
 * - 兼容源端静音抑制（3s 停发 + 1 帧/s keepalive）：keepalive 帧照常入队，欠载走缓冲态。
 *
 * 本文件经 AudioPipeline 的 new URL + ctx.audioWorklet.addModule 由浏览器按纯 JS
 * 原生加载（vite 对 new URL 资产引用不做转译），MUST 保持纯 JS：禁止 TS 专属语法
 * （语法守卫回归见 tests/audio-worklet-syntax.test.ts）。
 *
 * 主线程 ↔ worklet 消息协议：
 *   → { type:'feed', data: Float32Array（交错 stereo PCM） }
 *   → { type:'reset' }
 *   → { type:'query-stats' }
 *   ← { type:'stats', bufferedMs, skipCount, playedFrames, backlogDropped, overflowDropped, buffering }
 */

/** 起播水位协议缺省回退 ms（spec/协议 §3.13：120ms）。单一真相源为 AudioPipeline 导出的
 *  START_WATERMARK_MS，经 AudioWorkletNode processorOptions.startWatermarkMs 注入覆盖此回退 */
const DEFAULT_START_WATERMARK_MS = 120;
/** 积压上限 ms（Android AudioPlayer MAX_DROP_TO_WATERMARK_MS） */
const MAX_BACKLOG_MS = 800;
const SAMPLE_RATE = 48000;
const CHANNELS = 2;

class RingBufferPlayer extends AudioWorkletProcessor {
  // 交错环形缓冲（stereo），容量 1s
  capacity = SAMPLE_RATE * CHANNELS;
  ring = new Float32Array(this.capacity);
  readPos = 0;
  writePos = 0;
  buffered = 0; // 交错样本数

  buffering = true;
  skipCount = 0; // 欠载次数（回缓冲态次数，对齐 Android skipCount 增长语义）
  backlogDropped = 0; // >800ms 丢最旧的样本帧数
  playedFrames = 0; // 已输出样本帧数
  overflowDropped = 0; // 容量守卫截断丢弃的交错样本数
  lastStatsAt = 0;

  // 起播水位 ms（缺省回退；构造时被注入的 processorOptions.startWatermarkMs 覆盖）
  startWatermarkMs = DEFAULT_START_WATERMARK_MS;

  constructor(options) {
    super();
    // AudioWorkletNode 的 processorOptions 字典即本构造器的 options 入参
    const injected = options?.startWatermarkMs;
    if (typeof injected === 'number' && injected > 0) this.startWatermarkMs = injected;
    this.port.onmessage = (e) => this.onMessage(e.data);
  }

  onMessage(msg) {
    if (msg.type === 'feed') {
      this.feed(msg.data ?? new Float32Array(0));
    } else if (msg.type === 'reset') {
      this.readPos = this.writePos = this.buffered = 0;
      this.buffering = true;
      this.skipCount = this.backlogDropped = this.playedFrames = this.overflowDropped = 0;
    } else if (msg.type === 'query-stats') {
      this.sendStats(true);
    }
  }

  /** 入队一段交错 PCM；积压超上限丢最旧到起播水位 */
  feed(interleaved) {
    const frames = Math.floor(interleaved.length / CHANNELS);
    if (frames <= 0) return;
    const backlogLimitFrames = (MAX_BACKLOG_MS * SAMPLE_RATE) / 1000;
    const bufferedFrames = this.buffered / CHANNELS;
    if (bufferedFrames + frames > backlogLimitFrames) {
      const keepFrames = Math.floor((this.startWatermarkMs * SAMPLE_RATE) / 1000);
      const dropFrames = Math.max(0, bufferedFrames + frames - keepFrames);
      const dropSamples = Math.min(this.buffered, Math.floor(dropFrames) * CHANNELS);
      this.readPos = (this.readPos + dropSamples) % this.capacity;
      this.buffered -= dropSamples;
      this.backlogDropped += Math.floor(dropFrames);
    }
    // 容量守卫：只写剩余空间，绝不覆写未读样本；超出部分显式计数，
    // 保证 buffered <= capacity 恒成立（水位/backlog 丢弃/播放统计不失真）
    const writable = Math.min(interleaved.length, this.capacity - this.buffered);
    this.overflowDropped += interleaved.length - writable;
    for (let i = 0; i < writable; i++) {
      this.ring[this.writePos] = interleaved[i];
      this.writePos = (this.writePos + 1) % this.capacity;
      this.buffered++;
    }
  }

  sendStats(force) {
    const now = currentTime;
    if (!force && now - this.lastStatsAt < 1) return; // ≈1Hz
    this.lastStatsAt = now;
    this.port.postMessage({
      type: 'stats',
      bufferedMs: ((this.buffered / CHANNELS) * 1000) / SAMPLE_RATE,
      skipCount: this.skipCount,
      playedFrames: this.playedFrames,
      backlogDropped: this.backlogDropped,
      overflowDropped: this.overflowDropped,
      buffering: this.buffering,
    });
  }

  process(_inputs, outputs) {
    const out = outputs[0];
    const outFrames = out[0]?.length ?? 0;
    const outChannels = out.length;
    const watermarkSamples = ((this.startWatermarkMs * SAMPLE_RATE) / 1000) * CHANNELS;

    if (this.buffering) {
      if (this.buffered < watermarkSamples) {
        // 缓冲不足：不出声、不消费（继续攒），输出静音
        for (const ch of out) ch.fill(0);
        this.sendStats(false);
        return true;
      }
      this.buffering = false; // 达起播水位（协议 §3.13，缺省 120ms）起播
    }

    let consumedFrames = 0;
    for (let i = 0; i < outFrames; i++) {
      let frameConsumed = false;
      for (let ch = 0; ch < outChannels; ch++) {
        if (this.buffered > 0) {
          out[ch][i] = this.ring[this.readPos];
          this.readPos = (this.readPos + 1) % this.capacity;
          this.buffered--;
          frameConsumed = true;
        } else {
          out[ch][i] = 0;
        }
      }
      // 仅按真实消费统计：本输出帧至少出样 1 个样本才算播放
      if (frameConsumed) consumedFrames++;
    }
    this.playedFrames += consumedFrames;

    if (this.buffered === 0) {
      // 欠载：skip 计数并回到缓冲态（防断续爆音；对齐 AudioPlayer.playLoop poll 超时分支）
      this.skipCount += 1;
      this.buffering = true;
    }
    this.sendStats(false);
    return true;
  }
}

registerProcessor('ring-buffer-player', RingBufferPlayer);
