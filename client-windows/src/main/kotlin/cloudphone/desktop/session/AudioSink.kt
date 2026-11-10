package cloudphone.desktop.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.ffmpeg.global.avcodec.av_new_packet
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AV_CH_LAYOUT_STEREO
import org.bytedeco.ffmpeg.global.avutil.AV_CHANNEL_ORDER_NATIVE
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLTP
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** 播放设备抽象：javax.sound 实现（桌面）与 null-sink（测试/无设备回退） */
interface AudioOutput {
    /** 准备设备（打开 16bit LE PCM 线路） */
    fun open(sampleRate: Int, channels: Int)

    /** 起播（jitter 水位达标后调用；之前写入仅缓冲不出声） */
    fun start()

    /** 阻塞式写入一段交错 s16le PCM */
    fun write(pcm: ByteArray)

    /** 丢弃设备内未播缓冲 */
    fun flush() {}

    fun close()
}

/** javax.sound.sampled.SourceDataLine 实现（桌面真实出声路径） */
class JavaSoundAudioOutput(private val lineBufferBytes: Int = 128 * 1024) : AudioOutput {
    private var line: javax.sound.sampled.SourceDataLine? = null

    override fun open(sampleRate: Int, channels: Int) {
        val format = javax.sound.sampled.AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val l = javax.sound.sampled.AudioSystem.getSourceDataLine(format)
        l.open(format, lineBufferBytes)
        line = l
    }

    override fun start() {
        line?.start()
    }

    override fun write(pcm: ByteArray) {
        line?.write(pcm, 0, pcm.size)
    }

    override fun flush() {
        line?.flush()
    }

    override fun close() {
        runCatching { line?.drain() }
        runCatching { line?.close() }
        line = null
    }
}

/** null-sink：吞掉 PCM 只计数（测试/无声卡环境回退） */
class NullAudioOutput : AudioOutput {
    @Volatile var writtenFrames: Int = 0
        private set
    @Volatile var writtenBytes: Long = 0
        private set
    @Volatile var startedAtFrame: Int = -1
        private set
    @Volatile var lastPcm: ByteArray? = null
        private set

    override fun open(sampleRate: Int, channels: Int) {}

    override fun start() {
        if (startedAtFrame < 0) startedAtFrame = writtenFrames
    }

    override fun write(pcm: ByteArray) {
        lastPcm = pcm
        writtenBytes += pcm.size
        // 每帧 1024 样本 × ch×2B（写侧按整帧调用；部分写入按字节折算不在此处理）
        writtenFrames++
    }

    override fun close() {}
}

/** 逐级计数（StateFlow 供 HUD/诊断） */
data class AudioSinkStats(
    val chunksReceived: Long = 0,
    val framesDecoded: Long = 0,
    val framesWritten: Long = 0,
    /** 积压 >800ms 丢最旧到水位的已解码 PCM 帧数 */
    val framesDroppedBacklog: Long = 0,
    /** 输入 chunk 队列溢出（>32）丢最旧的 ADTS 片数 */
    val chunksDroppedOverflow: Long = 0,
    val queuedFrames: Int = 0,
    /** 是否已达起播水位（output.start() 已调用） */
    val playingStarted: Boolean = false,
)

/**
 * 音频下行（design D3，对齐 Android AudioPlayer 语义）：
 * AUDIO_CHUNK(ADTS AAC-LC) → FFmpeg AAC 解码 → 48kHz/stereo/s16le PCM →
 * jitter 缓冲（120ms 起播水位）→ [AudioOutput]。
 *
 * - 输入 chunk 队列上限 32，满则丢最旧（计数 chunksDroppedOverflow）；
 * - 已解码 PCM 队列时长 >800ms → 丢最旧至水位（计数 framesDroppedBacklog）；
 * - 音量/静音在 PCM 侧缩放（s16le 逐样本）。
 */
class AudioSink(
    private val output: AudioOutput,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val channels: Int = DEFAULT_CHANNELS,
) {
    companion object {
        const val DEFAULT_SAMPLE_RATE = 48000
        const val DEFAULT_CHANNELS = 2
        /** AAC-LC 每帧样本数（解码输出恒定） */
        const val AAC_SAMPLES_PER_FRAME = 1024
        /** 起播水位：120ms（Android AudioPlayer=6×20ms；AAC 21.3ms/帧 → 6 帧≈128ms） */
        const val START_WATERMARK_MS = 120L
        /** 积压丢弃阈值：>800ms 丢最旧至水位 */
        const val BACKLOG_DROP_MS = 800L
        /** 输入 ADTS chunk 队列上限（帧） */
        const val INPUT_QUEUE_MAX = 32
        /** 每帧时长 ms（AAC：1024/48000≈21.33ms） */
        val frameMs: Double = AAC_SAMPLES_PER_FRAME * 1000.0 / DEFAULT_SAMPLE_RATE
    }

    private val _stats = MutableStateFlow(AudioSinkStats())
    val stats: StateFlow<AudioSinkStats> = _stats

    // ── 输入 chunk 队列（dispatch 线程生产，音频线程解码消费） ──

    private val lock = Object()
    private val inputQueue = ArrayDeque<ByteArray>()
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private var decodeWorker: Thread? = null
    private var playWorker: Thread? = null

    // ── 解码后 PCM jitter 队列 ──

    private val pcmQueue = ArrayDeque<ByteArray>()

    // ── FFmpeg AAC 解码器（音频线程独占） ──

    private var codecCtx: org.bytedeco.ffmpeg.avcodec.AVCodecContext? = null
    private val avFrame = try {
        av_frame_alloc()
    } catch (t: Throwable) {
        null
    }
    private val packet = AVPacket()
    private var outputOpened = false

    // ── 计数与音量 ──

    private var chunksReceived = 0L
    private var framesDecoded = 0L
    private var framesWritten = 0L
    private var framesDroppedBacklog = 0L
    private var chunksDroppedOverflow = 0L

    @Volatile private var volume = 1.0f
    @Volatile private var muted = false
    @Volatile private var playingStarted = false

    /**
     * 挂起解码消费（挂起会话语义：停止消费音频，输入队列按 32 上限丢最旧兜底）；
     * 恢复后从队列继续。
     */
    @Volatile var decodingSuspended: Boolean = false

    /** 解码线程最近一次未捕获异常（诊断出口） */
    @Volatile var lastError: Throwable? = null
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        initDecoder()
        // 解码线程：ADTS chunk → PCM 帧 → jitter 入队（水位判定/积压丢弃在此）
        decodeWorker = thread(isDaemon = true, name = "audio-decode") {
            try {
                decodeLoop()
            } catch (t: Throwable) {
                lastError = t
                publish()
            }
        }
        // 播放线程：jitter 出队 → 音量缩放 → 设备写（write 阻塞天然节流，不阻塞解码）
        playWorker = thread(isDaemon = true, name = "audio-play") {
            try {
                playLoop()
            } catch (t: Throwable) {
                lastError = t
                publish()
            }
        }
    }

    /** AUDIO_CHUNK 入口（ADTS AAC 片；dispatch 线程，永不阻塞） */
    fun onAudioChunk(chunk: ByteArray) {
        if (!running.get() || chunk.isEmpty()) return
        synchronized(lock) {
            if (inputQueue.size >= INPUT_QUEUE_MAX) {
                inputQueue.removeFirst()
                chunksDroppedOverflow++
            }
            inputQueue.addLast(chunk)
            chunksReceived++
            publish()
            lock.notifyAll()
        }
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
    }

    fun setMuted(m: Boolean) {
        muted = m
    }

    /** 静音时立即丢弃设备内与 jitter 队列的积压（点击取消静音后按水位重新起播） */
    fun flushQueue() {
        synchronized(lock) {
            pcmQueue.clear()
            publish()
        }
        runCatching { output.flush() }
    }

    fun close() {
        if (!running.compareAndSet(true, false)) return
        synchronized(lock) {
            inputQueue.clear()
            pcmQueue.clear()
            lock.notifyAll()
        }
        decodeWorker?.join(2000)
        playWorker?.join(2000)
        decodeWorker = null
        playWorker = null
        releaseDecoder()
        output.close()
        playingStarted = false
    }

    // ── 解码线程：chunk → PCM jitter 入队 ──

    private fun decodeLoop() {
        val startFrames = watermarkFrames(START_WATERMARK_MS)
        val backlogDropFrames = watermarkFrames(BACKLOG_DROP_MS)
        while (running.get()) {
            val chunk = takeChunk() ?: continue
            val pcmFrames = decodeAdts(chunk)
            synchronized(lock) {
                for (pcm in pcmFrames) {
                    // 积压 >800ms：丢最旧至水位（音频帧无参考链，直接丢）
                    if (pcmQueue.size >= backlogDropFrames) {
                        val excess = pcmQueue.size - startFrames + 1
                        repeat(excess) {
                            if (pcmQueue.isNotEmpty()) {
                                pcmQueue.removeFirst()
                                framesDroppedBacklog++
                            }
                        }
                    }
                    pcmQueue.addLast(pcm)
                    framesDecoded++
                }
                // 起播：水位达标才 start（此前仅缓冲）
                if (!playingStarted && pcmQueue.size >= startFrames) {
                    playingStarted = true
                    if (!outputOpened) {
                        output.open(sampleRate, channels)
                        outputOpened = true
                    }
                    output.start()
                }
                publish()
            }
        }
    }

    // ── 播放线程：jitter 出队 → 音量 → 设备写 ──

    private fun playLoop() {
        while (running.get()) {
            val pcm = synchronized(lock) {
                while (pcmQueue.isEmpty() || !playingStarted) {
                    if (!running.get()) return
                    lock.wait(100)
                }
                pcmQueue.removeFirst()
            }
            output.write(applyVolume(pcm, if (muted) 0f else volume))
            synchronized(lock) {
                framesWritten++
                publish()
            }
        }
    }

    /** 阈值 ms → 帧数（向上取整） */
    private fun watermarkFrames(ms: Long): Int =
        kotlin.math.ceil(ms / frameMs).toInt().coerceAtLeast(1)

    private fun takeChunk(): ByteArray? {
        synchronized(lock) {
            while (inputQueue.isEmpty() || decodingSuspended) {
                if (!running.get()) return null
                lock.wait(100)
            }
            return inputQueue.removeFirst()
        }
    }

    // ── AAC 解码（ADTS 输入 → s16le 交错 PCM 帧） ──

    private fun decodeAdts(adts: ByteArray): List<ByteArray> {
        val ctx = codecCtx ?: return emptyList()
        val ret = av_new_packet(packet, adts.size)
        if (ret < 0) return emptyList()
        try {
            packet.data().put(adts, 0, adts.size)
            val send = avcodec_send_packet(ctx, packet)
            if (send < 0 && send != -11) return emptyList()
            val out = ArrayList<ByteArray>(1)
            while (true) {
                val r = avcodec_receive_frame(ctx, avFrame)
                if (r == -11 || r == AVERROR_EOF) break
                if (r < 0) break
                convertToS16le(avFrame)?.let {
                    out.add(it)
                }
            }
            return out
        } finally {
            av_packet_unref(packet)
        }
    }

    /** 解码帧（FLTP/S16）→ s16le 交错 ByteArray */
    private fun convertToS16le(f: AVFrame?): ByteArray? {
        if (f == null) return null
        val n = f.nb_samples()
        if (n <= 0) return null
        val ch = if (f.channels() > 0) f.channels() else channels
        val fmt = f.format()
        val out = ByteArray(n * ch * 2)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        if (fmt == AV_SAMPLE_FMT_FLTP) {
            // 平面 float → 交错 s16
            val planeBytes = ByteArray(n * 4)
            val planes = ArrayList<java.nio.FloatBuffer>(ch)
            for (c in 0 until ch) {
                val p = f.data(c)
                p.get(planeBytes, 0, n * 4)
                planes.add(ByteBuffer.wrap(planeBytes.copyOf()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer())
            }
            for (i in 0 until n) {
                for (c in 0 until ch) {
                    bb.putShort(floatToS16(planes[c].get(i)))
                }
            }
        } else if (fmt == AV_SAMPLE_FMT_S16) {
            // 已交错 s16 直拷
            val bytes = ByteArray(n * ch * 2)
            f.data(0).get(bytes, 0, bytes.size)
            return bytes
        } else {
            return null // 不支持的采样格式（源端恒为 AAC-LC → FLTP/S16）
        }
        return out
    }

    private fun floatToS16(v: Float): Short =
        (v.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()

    // ── 解码器生命周期 ──

    private fun initDecoder() {
        if (codecCtx != null) return
        val codec = avcodec_find_decoder(AV_CODEC_ID_AAC)
        if (codec == null) {
            System.err.println("[audio-sink] AAC 解码器不可用（FFmpeg 原生库缺失？）")
            return
        }
        val ctx = avcodec_alloc_context3(codec)
        ctx.thread_count(1)
        if (avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) {
            avcodec_free_context(ctx)
            System.err.println("[audio-sink] avcodec_open2 失败")
            return
        }
        codecCtx = ctx
    }

    private fun releaseDecoder() {
        codecCtx?.let { avcodec_free_context(it) }
        codecCtx = null
        avFrame?.let { av_frame_free(it) }
    }

    private fun publish() {
        _stats.value = AudioSinkStats(
            chunksReceived = chunksReceived,
            framesDecoded = framesDecoded,
            framesWritten = framesWritten,
            framesDroppedBacklog = framesDroppedBacklog,
            chunksDroppedOverflow = chunksDroppedOverflow,
            queuedFrames = synchronized(lock) { pcmQueue.size },
            playingStarted = playingStarted,
        )
    }
}

/** PCM 侧音量缩放：s16le 交错逐样本乘系数（静音=0 系数归零）；返回缩放后的新数组 */
internal fun applyVolume(pcm: ByteArray, volume: Float): ByteArray {
    if (volume >= 0.999f) return pcm
    val out = ByteArray(pcm.size)
    val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
    val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    var i = 0
    val n = pcm.size / 2
    while (i < n) {
        val s = bb.short
        val scaled = (s * volume).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        ob.putShort(scaled.toShort())
        i++
    }
    return out
}

/** 编码侧 ch_layout 辅助（测试与源共享） */
internal fun applyStereoLayout(l: org.bytedeco.ffmpeg.avutil.AVChannelLayout) {
    l.order(AV_CHANNEL_ORDER_NATIVE)
    l.nb_channels(2)
    l.u_mask(AV_CH_LAYOUT_STEREO)
}
