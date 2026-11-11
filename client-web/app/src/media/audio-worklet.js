/**
 * AudioWorklet 环形缓冲播放器（audio-thread 侧）。
 *
 * 语义逐字对齐协议 §3.13 / Android AudioPlayer.kt：
 * - jitter 环形缓冲攒够 120ms 才起播（吸收网络抖动），不足即保持缓冲态（不出声不消费）；
 * - 播放中欠载（无样本可出）→ skip 计数 + 回缓冲态（防断续爆音）；
 * - 积压 >800ms → feed 侧丢最旧到水位（防时钟漂移无限增长），计 backlogDropped；
 * - 兼容源端静音抑制（3s 停发 + 1 帧/s keepalive）：keepalive 帧照常入队，欠载走缓冲态。
 *
 * 主线程 ↔ worklet 消息协议：
 *   → { type:'feed', data: Float32Array（交错 stereo PCM） }
 *   → { type:'reset' }
 *   → { type:'query-stats' }
 *   ← { type:'stats', bufferedMs, skipCount, playedFrames, backlogDropped, buffering }
 */

/** 起播水位 ms（spec/协议 §3.13：120ms） */
const START_WATERMARK_MS = 120;
/** 积压上限 ms（Android AudioPlayer MAX_DROP_TO_WATERMARK_MS） */
const MAX_BACKLOG_MS = 800;
const SAMPLE_RATE = 48000;
const CHANNELS = 2;

class RingBufferPlayer extends AudioWorkletProcessor {
  // 交错环形缓冲（stereo），容量 1s
  private readonly capacity = SAMPLE_RATE * CHANNELS;
  private readonly ring = new Float32Array(this.capacity);
  private readPos = 0;
  private writePos = 0;
  private buffered = 0; // 交错样本数

  private buffering = true;
  private skipCount = 0; // 欠载次数（回缓冲态次数，对齐 Android skipCount 增长语义）
  private backlogDropped = 0; // >800ms 丢最旧的样本帧数
  private playedFrames = 0; // 已输出样本帧数
  private lastStatsAt = 0;

  constructor() {
    super();
    this.port.onmessage = (e) => this.onMessage(e.data);
  }

  private onMessage(msg) {
    if (msg.type === 'feed') {
      this.feed(msg.data ?? new Float32Array(0));
    } else if (msg.type === 'reset') {
      this.readPos = this.writePos = this.buffered = 0;
      this.buffering = true;
      this.skipCount = this.backlogDropped = this.playedFrames = 0;
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
      const keepFrames = Math.floor((START_WATERMARK_MS * SAMPLE_RATE) / 1000);
      const dropFrames = Math.max(0, bufferedFrames + frames - keepFrames);
      const dropSamples = Math.min(this.buffered, Math.floor(dropFrames) * CHANNELS);
      this.readPos = (this.readPos + dropSamples) % this.capacity;
      this.buffered -= dropSamples;
      this.backlogDropped += Math.floor(dropFrames);
    }
    for (let i = 0; i < interleaved.length; i++) {
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
      buffering: this.buffering,
    });
  }

  process(_inputs, outputs) {
    const out = outputs[0];
    const outFrames = out[0]?.length ?? 0;
    const outChannels = out.length;
    const watermarkSamples = ((START_WATERMARK_MS * SAMPLE_RATE) / 1000) * CHANNELS;

    if (this.buffering) {
      if (this.buffered < watermarkSamples) {
        // 缓冲不足：不出声、不消费（继续攒），输出静音
        for (const ch of out) ch.fill(0);
        this.sendStats(false);
        return true;
      }
      this.buffering = false; // 达 120ms 水位起播
    }

    let consumedFrames = 0;
    for (let i = 0; i < outFrames; i++) {
      for (let ch = 0; ch < outChannels; ch++) {
        if (this.buffered > 0) {
          out[ch][i] = this.ring[this.readPos];
          this.readPos = (this.readPos + 1) % this.capacity;
          this.buffered--;
        } else {
          out[ch][i] = 0;
        }
      }
      if (this.buffered > 0 || i === 0) consumedFrames++;
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
