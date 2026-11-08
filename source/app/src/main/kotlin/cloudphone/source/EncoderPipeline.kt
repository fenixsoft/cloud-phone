package cloudphone.source

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import cloudphone.protocol.Framing
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * MediaCodec H.264 编码管线（M0 全屏采集路径）：
 * - 分辨率/帧率/码率按会话参数配置（720p/1080p × 30/60fps × 可配码率）；
 * - 输入面 Surface 交由采集端渲染；输出 H.264 NAL 按 [Framing] 视频载荷封装；
 * - 帧内嵌源端单调时钟 PTS（elapsedRealtimeNanos µs，端到端延迟测量依据）；
 * - 支持动态 [requestIdr]（IDR_REQUEST ≤200ms 产关键帧：KEY_I_FRAME_INTERVAL=∞ 时用
 *   PARAMETER_KEY_REQUEST_SYNC_FRAME 即时触发）。
 *
 * 输出回调式（每帧 [onEncodedFrame]）：sequence 单调递增、keyframe 标记、编码字节完整 NAL 帧本体。
 */
class EncoderPipeline(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateKbps: Int,
    private val onEncodedFrame: (sequence: Long, ptsUs: Long, keyframe: Boolean, data: ByteArray) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val sequenceGen = AtomicLong(1)
    private val idrRequested = AtomicBoolean(false)

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainThread: Thread? = null

    /** csd（SPS/PPS）：首个关键帧前置发送，供解码端 configure */
    @Volatile private var csd: ByteArray? = null
    private val csdSent = AtomicBoolean(false)

    @Volatile var encoderName: String = ""
        private set

    /** 编码器输入 Surface（采集端把画面渲染到这个面上） */
    val surface: Surface? get() = inputSurface

    /** 标准配置：720p=1280×720 / 1080p=1920×1080，30/60fps */
    data class SessionParams(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateKbps: Int,
    ) {
        companion object {
            fun normalized(width: Int, height: Int, fps: Int, bitrateKbps: Int): SessionParams {
                // 分辨率对齐到偶数（编码器要求）；帧率限 15..60；码率限 1..20Mbps。
                // 上限 2560：竖屏 aspect-exact（如 1080x2400 手机屏）需要长边 >1440，
                // 旧 1440 封顶会把竖屏真实宽高比压扁（画面扭曲根因之一）
                val w = width.coerceIn(320, 2560).let { if (it % 2 != 0) it + 1 else it }
                val h = height.coerceIn(240, 2560).let { if (it % 2 != 0) it + 1 else it }
                return SessionParams(w, h, fps.coerceIn(15, 60), bitrateKbps.coerceIn(1000, 20000))
            }

            val P720_30 = SessionParams(1280, 720, 30, 6000)
            val P720_60 = SessionParams(1280, 720, 60, 8000)
            val P1080_30 = SessionParams(1920, 1080, 30, 12000)
            val P1080_60 = SessionParams(1920, 1080, 60, 16000)
        }
    }

    fun start() {
        check(running.compareAndSet(false, true)) { "encoder already running" }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // 2s 自动 IDR 兜底 + 显式 requestIdr（≤200ms）双轨；∞ 间隔会在无显式请求时永久静默
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // 静态画面重复上一帧持续出流（屏幕镜像标准做法；供 fps/码率观测与断流判定）
            // 注：KEY_REPEAT_PREVIOUS_FRAME_IF_POSSIBLE 在部分 SDK 版本为 @hide，用字面量
            setInteger("repeat-previous-frame-if-possible", 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        val encoderName = pickEncoder() ?: run {
            running.set(false)
            throw IllegalStateException("未找到可用的 AVC 编码器")
        }
        this.encoderName = encoderName
        val codec = MediaCodec.createByCodecName(encoderName)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        this.codec = codec
        this.inputSurface = surface
        drainLoop(codec)
    }

    /** 优先硬件编码器（OMX.qcom.* / c2.qti.*），软编兜底（c2.android.avc / OMX.google） */
    private fun pickEncoder(): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val avc = list.filter {
            !it.isEncoder.not() && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }
        val hw = avc.firstOrNull {
            val name = it.name
            !name.startsWith("OMX.google") && !name.startsWith("c2.android")
        }
        return (hw ?: avc.firstOrNull())?.name
    }

    private fun drainLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        drainThread = Thread({
            try {
                while (running.get()) {
                    val index = codec.dequeueOutputBuffer(info, 20_000)
                    if (index < 0) continue
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> {
                            // csd（SPS/PPS）配置帧：暂存，首个关键帧前置（解码端需其 configure）
                            if (info.size > 0) {
                                val buffer = codec.getOutputBuffer(index) ?: continue
                                val out = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.get(out)
                                csd = out
                            }
                            codec.releaseOutputBuffer(index, false)
                        }
                        else -> {
                            val buffer = codec.getOutputBuffer(index) ?: continue
                            val out = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(out)
                            codec.releaseOutputBuffer(index, false)
                            if (info.size > 0) {
                                val ptsUs = SystemClock.elapsedRealtimeNanos() / 1000
                                val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                val seq = sequenceGen.getAndIncrement()
                                if (keyframe) idrRequested.set(false)
                                // 每个关键帧都前置 csd（SPS/PPS）：共享采集管线先于会话运行，
                                // 新接入的解码端只能从"下一个关键帧"切入，若关键帧无参数集则永不出口
                                val payload = if (keyframe) (csd ?: ByteArray(0)) + out else out
                                cloudphone.source.obs.PipelineCounters.encodedFrames.incrementAndGet()
                                cloudphone.source.obs.PipelineCounters.maybeDumpFrame(seq, keyframe, payload)
                                onEncodedFrame(seq, ptsUs, keyframe, payload)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    cloudphone.source.obs.PipelineCounters.onBadEvent(
                        cloudphone.source.obs.PipelineCounters.BadEvent.ENCODE_FAILURE,
                        e.message ?: "codec drain error",
                    )
                }
            } finally {
                releaseCodec()
            }
        }, "encoder-drain").apply {
            isDaemon = true
            start()
        }
    }

    /** 动态 IDR：PARAMETER_KEY_REQUEST_SYNC_FRAME（≤200ms 内产关键帧） */
    fun requestIdr() {
        idrRequested.set(true)
        val codec = codec ?: return
        try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(params)
        } catch (e: IllegalStateException) {
            cloudphone.source.obs.PipelineCounters.onBadEvent(
                cloudphone.source.obs.PipelineCounters.BadEvent.ENCODE_FAILURE,
                "request sync frame: ${e.message}",
            )
        }
    }

    fun isRunning(): Boolean = running.get()

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        drainThread?.join(1000)
        releaseCodec()
    }

    private fun releaseCodec() {
        inputSurface?.release()
        inputSurface = null
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
    }
}
