package cloudphone.desktop

import cloudphone.desktop.session.AudioSink
import cloudphone.desktop.session.NullAudioOutput
import cloudphone.desktop.session.applyStereoLayout
import cloudphone.desktop.session.applyVolume
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLTP
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import kotlin.math.PI
import kotlin.math.sin

/**
 * 2.3 验收：AudioSink 单测——JavaCV AAC 编码器合成 ADTS 流，断言解码 PCM 帧数
 * 与 jitter 丢弃/起播逻辑（120ms 水位起播、>800ms 丢最旧至水位、输入队列 32 上限）。
 */
class AudioSinkTest {

    companion object {
        /** AAC 编码帧样本数（LC 恒 1024） */
        private const val SAMPLES = 1024
        private const val RATE = 48000
    }

    // ── 起播水位（120ms ≈ 6 帧） ──

    @Test
    fun `起播水位 - 不足 120ms 不起播 达标后起播并全量写出`() {
        val adts = encodeAdts(6)
        val out = NullAudioOutput()
        val sink = AudioSink(out)
        try {
            sink.start()
            sink.onAudioChunk(adts[0])
            sink.onAudioChunk(adts[1])
            sink.onAudioChunk(adts[2])
            sink.onAudioChunk(adts[3])
            sink.onAudioChunk(adts[4])
            // 5 帧 ≈ 107ms < 120ms 水位：不起播、不写设备
            waitUntil("水位前保持缓冲", sink) { it.chunksReceived == 5L && !it.playingStarted && it.queuedFrames == 5 }
            assertEquals("水位前不得写设备", 0, out.writtenFrames)

            // 第 6 帧 ≈ 128ms ≥ 120ms：起播
            sink.onAudioChunk(adts[5])
            waitUntil("起播后写完全部 6 帧", sink) { it.framesWritten == 6L }
            assertTrue("playingStarted 应为 true", sink.stats.value.playingStarted)
            assertEquals("解码帧数", 6L, sink.stats.value.framesDecoded)
            assertEquals("全部写出", 6, out.writtenFrames)
            assertEquals("起播前零写入", 0, out.startedAtFrame)
        } finally {
            sink.close()
        }
    }

    // ── 积压 >800ms 丢最旧至水位 ──

    @Test
    fun `积压超 800ms - 丢最旧至起播水位`() {
        val adts = encodeAdts(50)
        val out = GateAudioOutput()
        val sink = AudioSink(out)
        try {
            sink.start()
            // 设备写阻塞 → jitter 队列持续积压；按解码进度逐块喂入（输入队列不先溢出）
            out.blockWrites()
            adts.forEachIndexed { i, chunk ->
                sink.onAudioChunk(chunk)
                waitUntil("第 ${i + 1} 块解码", sink) { it.framesDecoded == (i + 1).toLong() }
            }
            // 50 帧×21.3ms≈1067ms > 800ms：首次穿越（入队第 39 帧、size=38）一次性丢最旧至
            // 水位 6（dropped=33），随后队列再增长至 17（<38 不再触发）——与"积压>800ms 丢最旧"语义一致
            waitUntil("积压丢弃收敛", sink) { it.framesDecoded == 50L }
            val s = sink.stats.value
            assertEquals("穿越丢弃帧数", 33L, s.framesDroppedBacklog)
            // 终态队列 17（可能有 1 帧已被播放线程取走、阻塞在设备写里 in-flight）
            assertTrue(
                "终态队列 = 50-33±1（实际 ${s.queuedFrames}）",
                s.queuedFrames in 16..17,
            )
            assertEquals("阻塞期间零写出", 0, out.writtenFrames)
            assertTrue(
                "计数守恒：written+dropped+queued ≈ decoded",
                s.framesWritten + s.framesDroppedBacklog + s.queuedFrames >= 49L,
            )
            // 放行后：残余 17 帧全部写出
            out.unblockWrites()
            waitUntil("放行后写出残余帧", sink) { it.framesWritten == 17L }
            assertEquals(17, out.writtenFrames)
        } finally {
            sink.close()
        }
    }

    // ── 输入 chunk 队列 32 上限（挂起解码消费时溢出丢最旧） ──

    @Test
    fun `输入队列 32 上限 - 挂起期间溢出丢最旧`() {
        val adts = encodeAdts(40)
        val out = NullAudioOutput()
        val sink = AudioSink(out)
        try {
            sink.start()
            sink.decodingSuspended = true
            adts.forEach { sink.onAudioChunk(it) }
            waitUntil("输入计数稳定", sink) { it.chunksReceived == 40L }
            val s = sink.stats.value
            assertEquals("40 块全收", 40L, s.chunksReceived)
            assertEquals("超 32 上限丢最旧 8 块", 8L, s.chunksDroppedOverflow)
            assertEquals("挂起期间零解码", 0L, s.framesDecoded)
            // 恢复消费：剩余 32 块全部解码
            sink.decodingSuspended = false
            waitUntil("恢复后解码 32 帧", sink) { it.framesDecoded == 32L }
            assertEquals("溢出计数不变", 8L, sink.stats.value.chunksDroppedOverflow)
        } finally {
            sink.close()
        }
    }

    // ── 音量/静音（PCM 侧缩放，纯函数） ──

    @Test
    fun `音量缩放 - 半量取整与静音归零`() {
        // s16le: [1000, -2000, 32767, -32768]
        val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(1000); bb.putShort(-2000); bb.putShort(32767); bb.putShort(-32768)
        val src = bb.array()

        // 1.0 直通（同一引用，零拷贝）
        assertArrayEquals(src, applyVolume(src, 1.0f))
        // 0.5 缩放：1000→500, -2000→-1000, 32767→16384(四舍五入), -32768→-16384
        val half = applyVolume(src, 0.5f)
        val hb = ByteBuffer.wrap(half).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(500, hb.short.toInt())
        assertEquals(-1000, hb.short.toInt())
        assertEquals(16384, hb.short.toInt())
        assertEquals(-16384, hb.short.toInt())
        // 静音归零
        val mute = applyVolume(src, 0.0f)
        assertTrue(mute.all { it.toInt() == 0 })
        // 音量钳制 >1 视为 1
        assertArrayEquals(src, applyVolume(src, 5.0f))
    }

    @Test
    fun `静音标志 - 写出 PCM 归零`() {
        val adts = encodeAdts(6)
        val out = NullAudioOutput()
        val sink = AudioSink(out)
        try {
            sink.start()
            sink.setMuted(true)
            adts.forEach { sink.onAudioChunk(it) }
            waitUntil("静音下写完全部帧", sink) { it.framesWritten == 6L }
            val pcm = out.lastPcm!!
            assertEquals(1024 * 2 * 2, pcm.size)
            assertTrue("静音 PCM 应全零", pcm.all { it.toInt() == 0 })
        } finally {
            sink.close()
        }
    }

    // ── 工具：等待统计满足条件 ──

    private fun waitUntil(what: String, sink: AudioSink, cond: (cloudphone.desktop.session.AudioSinkStats) -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            if (cond(sink.stats.value)) return
            if (sink.lastError != null) throw IllegalStateException("sink error", sink.lastError)
            Thread.sleep(10)
        }
        throw AssertionError("等待超时: $what, stats=${sink.stats.value}, lastError=${sink.lastError}")
    }

    // ── JavaCV AAC 编码器 → ADTS 合成 ──

    /** 编码 [count] 帧 48kHz 立体声正弦（FLTP），包 ADTS 头输出 */
    private fun encodeAdts(count: Int): List<ByteArray> {
        val codec = avcodec_find_encoder(AV_CODEC_ID_AAC) ?: error("AAC 编码器不可用")
        val ctx = avcodec_alloc_context3(codec)!!
        ctx.sample_rate(RATE)
        applyStereoLayout(ctx.ch_layout())
        ctx.sample_fmt(AV_SAMPLE_FMT_FLTP)
        ctx.bit_rate(96_000)
        ctx.time_base(av_rational(1, RATE))
        if (avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) error("avcodec_open2 失败")
        val frameSize = ctx.frame_size().let { if (it > 0) it else SAMPLES }
        val frame = av_frame_alloc()!!
        frame.format(AV_SAMPLE_FMT_FLTP)
        frame.sample_rate(RATE)
        frame.nb_samples(frameSize)
        applyStereoLayout(frame.ch_layout())
        if (av_frame_get_buffer(frame, 0) < 0) error("av_frame_get_buffer 失败")
        val out = ArrayList<ByteArray>(count)
        val pkt = AVPacket()
        try {
            for (i in 0 until count) {
                fillSine(frame, frameSize, i)
                frame.pts(i.toLong() * frameSize)
                if (avcodec_send_frame(ctx, frame) < 0) error("send_frame 失败 @$i")
                while (true) {
                    val r = avcodec_receive_packet(ctx, pkt)
                    if (r < 0) break
                    val payload = ByteArray(pkt.size())
                    pkt.data().get(payload, 0, pkt.size())
                    out.add(adtsWrap(payload, RATE, 2))
                    av_packet_unref(pkt)
                }
            }
            // flush
            if (avcodec_send_frame(ctx, null) >= 0) {
                while (true) {
                    val r = avcodec_receive_packet(ctx, pkt)
                    if (r < 0) break
                    val payload = ByteArray(pkt.size())
                    pkt.data().get(payload, 0, pkt.size())
                    out.add(adtsWrap(payload, RATE, 2))
                    av_packet_unref(pkt)
                }
            }
        } finally {
            av_frame_free(frame)
            avcodec_free_context(ctx)
        }
        // AAC 编码器 flush 会补出最后一帧：裁剪到精确 count
        return out.take(count)
    }

    /** 双声道正弦（440Hz 左 / 880Hz 右），幅度 0.5 */
    private fun fillSine(frame: AVFrame, n: Int, frameIndex: Int) {
        for (ch in 0 until 2) {
            val bytes = ByteArray(n * 4)
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val freq = if (ch == 0) 440.0 else 880.0
            val phase = frameIndex * n * 2.0 * PI * freq / RATE
            for (i in 0 until n) {
                fb.put((0.5 * sin(phase + i * 2.0 * PI * freq / RATE)).toFloat())
            }
            frame.data(ch).put(bytes, 0, bytes.size)
        }
    }

    /** 7 字节 ADTS 头（MPEG-4 / 无 CRC / LC / 48kHz / stereo） */
    private fun adtsWrap(aac: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val sfIndex = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5; else -> 3
        }
        val len = aac.size + 7
        val h = ByteArray(7)
        h[0] = 0xFF.toByte()
        h[1] = 0xF1.toByte() // MPEG-4, layer 0, protection_absent=1
        h[2] = (((1 and 0x3) shl 6) or ((sfIndex and 0xF) shl 2) or (((channels shr 2) and 0x1) shl 1)).toByte() // profile=LC
        h[3] = (((channels and 0x3) shl 6) or (((len shr 11) and 0x3))).toByte()
        h[4] = ((len shr 3) and 0xFF).toByte()
        h[5] = (((len and 0x7) shl 5) or 0x1F).toByte() // buffer_fullness=0x7FF 高 5 位
        h[6] = 0xFC.toByte() // fullness 低 6 位 (0x3F)<<2 | blocks-1=0
        return h + aac
    }

    private fun av_rational(num: Int, den: Int): org.bytedeco.ffmpeg.avutil.AVRational =
        org.bytedeco.ffmpeg.avutil.AVRational().num(num).den(den)
}

/** 可控阻塞的测试输出：模拟设备缓冲满（写阻塞） */
class GateAudioOutput : cloudphone.desktop.session.AudioOutput {
    private val gate = CountDownLatch(1)
    @Volatile private var blocked = false

    fun blockWrites() {
        blocked = true
    }

    fun unblockWrites() {
        blocked = false
        gate.countDown()
    }

    @Volatile var writtenFrames: Int = 0
        private set

    override fun open(sampleRate: Int, channels: Int) {}
    override fun start() {}
    override fun write(pcm: ByteArray) {
        if (blocked) {
            // 门未开则阻塞（模拟 SourceDataLine 缓冲满）
            while (blocked) Thread.sleep(5)
        }
        writtenFrames++
    }

    override fun close() {}
}
