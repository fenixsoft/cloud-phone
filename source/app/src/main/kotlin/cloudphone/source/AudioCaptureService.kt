package cloudphone.source

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 源端音频捕获服务（aux-channels-m3 任务 2.1）：
 *
 * - AudioPlaybackCapture（API 29+）捕获远端 App 音频，匹配 usage GAME/MEDIA（设计 D1）；
 *   注意捕获语义是「拷贝」：源端本地照常外放，无法静音源端（平台硬约束，手册如实说明）；
 * - AAC-LC 48kHz stereo 96kbps，20ms 帧（960 samples）；编码输出手工加 ADTS 头，
 *   客户端解码器按 ADTS 流直解（免带外 csd-0 交换）；
 * - PTS = SystemClock.elapsedRealtimeNanos()/1000（与视频编码器同一单调钟，音画同钟）；
 * - RMS 静音：连续 3s 低于阈值停发，仅每秒 1 帧 keepalive（防 NAT 老化 + 通道活性验证）；
 * - API < 29 返回不可用状态（调用方转为客户端 AuxNotice 明示降级，非静默无声）。
 */
class AudioCaptureService {

    data class StartResult(val ok: Boolean, val reason: String)

    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val BIT_RATE_BPS = 96_000
        const val FRAME_MS = 20
        /** 20ms @48kHz = 960 samples/帧 */
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000
        /** PCM16 双声道单帧字节数（960×2ch×2B） */
        const val FRAME_PCM_BYTES = FRAME_SAMPLES * CHANNELS * 2
        /** RMS 静音阈值（PCM16 满幅 32767，100 ≈ -50dBFS：低于视为静音） */
        const val RMS_SILENCE_THRESHOLD = 100.0
        const val SILENCE_STOP_MS = 3_000L
        const val KEEPALIVE_INTERVAL_MS = 1_000L

        /** AudioPlaybackCapture 平台门槛（API 29+） */
        fun supported(): Boolean = Build.VERSION.SDK_INT >= 29

        /** ADTS 头（7B 无 CRC）：AAC-LC(profile=1)/48kHz(freqIdx=3)/stereo(ch=2) */
        private fun adtsHeader(aacLength: Int): ByteArray {
            val frameLen = aacLength + 7
            val freqIdx = 3
            val chCfg = CHANNELS
            val h = ByteArray(7)
            h[0] = 0xFF.toByte()
            h[1] = 0xF1.toByte() // MPEG-4, layer 0, protection absent
            h[2] = ((1 shl 6) or (freqIdx shl 2) or (chCfg shr 2)).toByte() // profile AAC-LC
            h[3] = (((chCfg and 3) shl 6) or ((frameLen shr 11) and 0x03)).toByte()
            h[4] = ((frameLen shr 3) and 0xFF).toByte()
            h[5] = (((frameLen and 0x07) shl 5) or 0x1F).toByte()
            h[6] = 0xFC.toByte()
            return h
        }
    }

    private val running = AtomicBoolean(false)
    private var captureThread: Thread? = null

    // 可观测计数（日志/verification 采集）
    val framesSent = AtomicLong(0)
    val keepaliveSent = AtomicLong(0)
    val framesSuppressed = AtomicLong(0)

    /**
     * 启动捕获+编码泵。onChunk 在捕获线程回调（ptsUs 与视频同钟；data=ADTS 访问单元）。
     * 失败返回 ok=false 与明确原因（调用方回传客户端降级提示）。
     */
    fun start(projection: MediaProjection?, onChunk: (ptsUs: Long, seq: Int, data: ByteArray) -> Unit): StartResult {
        if (!supported()) {
            return StartResult(
                false,
                "源端系统不支持音频捕获（AudioPlaybackCapture 需 Android 10/API 29+，当前 API ${Build.VERSION.SDK_INT}）",
            )
        }
        if (projection == null) {
            return StartResult(false, "音频捕获需要 MediaProjection 授权（先在源端 App 授予）")
        }
        if (!running.compareAndSet(false, true)) {
            return StartResult(true, "音频捕获已在运行")
        }
        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            val pcmFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val recorder = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(pcmFormat)
                .setBufferSizeInBytes(maxOf(minBuf * 4, FRAME_PCM_BYTES * 8))
                .build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                running.set(false)
                return StartResult(false, "音频捕获初始化失败（镜像可能禁用 AudioPlaybackCapture）")
            }

            val encFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE_BPS)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_PCM_BYTES)
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            captureThread = Thread({ pump(recorder, encoder, onChunk) }, "audio-capture").apply {
                isDaemon = true
                start()
            }
            return StartResult(true, "")
        } catch (e: Exception) {
            running.set(false)
            return StartResult(false, "音频捕获启动失败: ${e.message}")
        }
    }

    /** 捕获泵：PCM 读 → RMS 静音判定 → AAC 编码 → ADTS 打包回调。stop 延迟 ≤1 帧（20ms） */
    private fun pump(recorder: AudioRecord, encoder: MediaCodec, onChunk: (ptsUs: Long, seq: Int, data: ByteArray) -> Unit) {
        var seq = 0
        var lastLoudAtMs = SystemClock.elapsedRealtime()
        var lastSentAtMs = 0L
        val pcm = ByteArray(FRAME_PCM_BYTES)
        val inputInfo = MediaCodec.BufferInfo()
        val outputInfo = MediaCodec.BufferInfo()
        try {
            recorder.startRecording()
            while (running.get()) {
                val n = recorder.read(pcm, 0, FRAME_PCM_BYTES)
                if (n < FRAME_PCM_BYTES) continue // 欠读（启动过渡期）：丢该 20ms 片
                val nowMs = SystemClock.elapsedRealtime()
                val ptsUs = SystemClock.elapsedRealtimeNanos() / 1000 // 与视频同钟

                // RMS 静音判定（PCM16 双声道交错的全部样本）
                var acc = 0.0
                var i = 0
                while (i < n) {
                    val lo = pcm[i].toInt() and 0xFF
                    val hi = pcm[i + 1].toInt()
                    val s = (hi shl 8) or lo
                    acc += (s * s).toDouble()
                    i += 2
                }
                val rms = kotlin.math.sqrt(acc / (n / 2))
                if (rms >= RMS_SILENCE_THRESHOLD) lastLoudAtMs = nowMs

                val silentForMs = nowMs - lastLoudAtMs
                if (silentForMs >= SILENCE_STOP_MS) {
                    // 静音 ≥3s：仅每秒 1 帧 keepalive（防 NAT 老化 + 通道活性），其余丢弃零流量
                    if (nowMs - lastSentAtMs < KEEPALIVE_INTERVAL_MS) {
                        framesSuppressed.incrementAndGet()
                        continue
                    }
                }
                if (!feedEncoder(encoder, pcm, n, ptsUs, inputInfo)) {
                    framesSuppressed.incrementAndGet()
                    continue
                }
                var sent = false
                while (true) {
                    val outIdx = encoder.dequeueOutputBuffer(outputInfo, 10_000)
                    if (outIdx < 0) break
                    if (outIdx >= 0 && outputInfo.size > 0) {
                        val outBuf = encoder.getOutputBuffer(outIdx) ?: break
                        val adts = adtsHeader(outputInfo.size)
                        val data = ByteArray(outputInfo.size + 7)
                        System.arraycopy(adts, 0, data, 0, 7)
                        // 输出 buffer position 未必为 0：按 position/limit 切片拷贝
                        outBuf.position(outputInfo.offset)
                        outBuf.limit(outputInfo.offset + outputInfo.size)
                        outBuf.get(data, 7, outputInfo.size)
                        onChunk(ptsUs, seq, data)
                        seq++
                        lastSentAtMs = nowMs
                        sent = true
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if (outputInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                if (sent) {
                    if (silentForMs >= SILENCE_STOP_MS) keepaliveSent.incrementAndGet() else framesSent.incrementAndGet()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("cloudphone-source", "audio capture pump ended: ${e.message}")
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching {
                encoder.stop()
                encoder.release()
            }
            running.set(false)
        }
    }

    /** 送一帧 PCM 进编码器（输入缓冲不可得时丢帧返 false） */
    private fun feedEncoder(encoder: MediaCodec, pcm: ByteArray, len: Int, ptsUs: Long, info: MediaCodec.BufferInfo): Boolean {
        val inIdx = encoder.dequeueInputBuffer(10_000)
        if (inIdx < 0) return false
        val buf = encoder.getInputBuffer(inIdx) ?: return false
        buf.clear()
        buf.put(pcm, 0, len)
        encoder.queueInputBuffer(inIdx, 0, len, ptsUs, 0)
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        captureThread?.join(1500)
        captureThread = null
    }

    fun isRunning(): Boolean = running.get()
}
