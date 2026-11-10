package cloudphone.desktop

import cloudphone.desktop.session.DecodedFrame
import cloudphone.desktop.session.VideoPipeline
import cloudphone.desktop.session.toAnnexB
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket

import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P


import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable
import org.bytedeco.javacpp.BytePointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files


/**
 * 2.2 验收：VideoPipeline 单测——用 JavaCV(FFmpeg) 编码器合成样例 H.264 流，
 * 断言解码输出帧与积压丢弃计数；另覆盖 AVCC→AnnexB 转换与 debug dump。
 */
class VideoPipelineTest {

    companion object {
        private const val W = 64
        private const val H = 64
        private const val FPS = 30
    }

    // ── 解码输出（AnnexB 与 AVCC 两种输入形态） ──

    @Test
    fun `合成 H264 流解码 - AnnexB 输入逐帧输出`() {
        val packets = encodeH264(40)
        assumeTrue("宿主 FFmpeg 需带 libx264（-gpl 构建）", packets.size >= 40)

        val pipeline = VideoPipeline()
        try {
            pipeline.start()
            feed(pipeline, packets, paced = true)
            val s = pipeline.stats.value
            assertEquals("接收片数", 40L, s.framesReceived)
            assertEquals("全部解码", 40L, s.framesDecoded)
            assertEquals("无积压丢弃", 0L, s.framesDroppedBacklog)
            assertTrue("应有上屏帧", s.framesPresented >= 1)
            assertEquals("解码实际分辨率 64x64", 64, s.decodedWidth)
            assertEquals(64, s.decodedHeight)
            val latest = pipeline.latestFrame.value
            assertNotNull(latest)
            assertEquals(64, latest!!.width)
            assertEquals(64, latest.height)
            assertEquals("BGRA 每像素 4B", 64 * 64 * 4, latest.bgra.size)
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun `AVCC 输入 - 长度前缀流经转换后正常解码`() {
        val packets = encodeH264(40)
        val avcc = packets.map { annexbToAvcc(it) }

        val pipeline = VideoPipeline()
        try {
            pipeline.start()
            feed(pipeline, avcc, paced = true)
            assertEquals("AVCC 输入应经 toAnnexB 还原后全部解码", 40L, pipeline.stats.value.framesDecoded)
        } finally {
            pipeline.close()
        }
    }

    // ── 积压快进（latest-wins 兜底） ──

    @Test
    fun `积压过深 - 快进至关键帧边界丢弃并计数`() {
        val packets = encodeH264(60)
        val pipeline = VideoPipeline(fastForwardThreshold = 4)
        try {
            pipeline.start()
            // 一次性灌入 60 帧（同步循环，解码消费速率 < 入队速率 → 队列深度必超阈值）
            feed(pipeline, packets, paced = false)
            awaitDrained(pipeline, timeoutMs = 20000)
            val s = pipeline.stats.value
            assertTrue("深积压应触发快进丢弃（dropped=${s.framesDroppedBacklog}）", s.framesDroppedBacklog > 0)
            assertEquals(
                "不变式：received = decoded + dropped（队列已清空）",
                s.framesReceived, s.framesDecoded + s.framesDroppedBacklog,
            )
            assertTrue("至少解码出关键帧", s.framesDecoded >= 1)
            assertTrue("上屏帧数不超解码帧数", s.framesPresented <= s.framesDecoded)
            assertEquals(
                "latest-wins 计数自洽：presented + notPresented = decoded",
                s.framesDecoded, s.framesPresented + s.framesDecodedNotPresented,
            )
        } finally {
            pipeline.close()
        }
    }

    // ── debug dump（默认关闭，开启后落盘） ──

    @Test
    fun `debug dump - 开启后原始输入片落盘`() {
        val packets = encodeH264(10)
        val dir = Files.createTempDirectory("video-dump-test")
        val dump = dir.resolve("input.h264")
        val pipeline = VideoPipeline()
        try {
            pipeline.enableDebugDump(dump)
            pipeline.start()
            feed(pipeline, packets, paced = true)
        } finally {
            pipeline.close()
        }
        assertTrue("dump 文件应存在", Files.exists(dump))
        val bytes = Files.readAllBytes(dump)
        assertTrue("dump 应有内容", bytes.size > 4)
        // 首条记录：[u32 len][payload]，len 应为正且与文件长度自洽（至少首片完整）
        val len0 = ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        assertTrue("首片长度前缀应为正（$len0）", len0 > 0)
        assertEquals("首片应完整落盘", 4 + len0, bytes.size.coerceAtLeast(4 + len0).let { if (bytes.size >= 4 + len0) 4 + len0 else bytes.size })
    }

    // ── toAnnexB 纯函数 ──

    @Test
    fun `toAnnexB - 纯函数边界`() {
        // AnnexB 直通
        val annexb = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 3)
        assertTrue(annexb contentEquals toAnnexB(annexb))
        // AVCC（4B 长度前缀）→ AnnexB
        val avcc = byteArrayOf(0, 0, 0, 3, 0x67, 1, 2, 0, 0, 0, 2, 0x68, 9)
        val out = toAnnexB(avcc)
        val expected = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 9)
        assertTrue("AVCC 应转 AnnexB：${out.toList()}", expected contentEquals out)
        // 非法长度（越界）→ 保守原样返回
        val bad = byteArrayOf(0, 0, 0, 100, 1, 2)
        assertTrue(bad contentEquals toAnnexB(bad))
    }

    // ── 工具：喂帧（keyframe 每 30 帧一标，与编码器 GOP=30 一致） ──

    /** 逐包喂入并等待消费（pace=true 时队列不积压 → 不触发快进） */
    private fun feed(pipeline: VideoPipeline, packets: List<ByteArray>, paced: Boolean) {
        packets.forEachIndexed { i, p ->
            pipeline.onVideoFrame(cloudphone.protocol.Framing.VideoFrame(0, 0, i % 30 == 0, p))
            if (paced) awaitDrained(pipeline, timeoutMs = 15000)
        }
    }

    // ── 工具：等待队列清空（received == decoded + dropped + errors） ──

    private fun awaitDrained(pipeline: VideoPipeline, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = pipeline.stats.value
            if (s.framesDecoded + s.framesDroppedBacklog >= s.framesReceived) return
            Thread.sleep(20)
        }
        if (pipeline.lastError != null) {
            println("[video-pipeline-test] lastError=")
            pipeline.lastError!!.printStackTrace()
        }
        println("[video-pipeline-test] stats=${pipeline.stats.value}")
    }

    // ── JavaCV x264 编码器（合成样例 H.264 流，AnnexB 输出） ──

    /** 编码 [count] 帧 64x64 YUV420P 渐变画面（GOP=30，无 B 帧），返回 AnnexB 码流片序列 */
    internal fun encodeH264(count: Int): List<ByteArray> {
        val codec = avcodec_find_encoder(AV_CODEC_ID_H264)
            ?: error("H.264 编码器不可用（需 -gpl 构建的 FFmpeg natives）")
        val ctx = avcodec_alloc_context3(codec) ?: error("avcodec_alloc_context3 失败")
        ctx.width(W)
        ctx.height(H)
        ctx.time_base(av_rational(1, FPS))
        ctx.pix_fmt(AV_PIX_FMT_YUV420P)
        ctx.gop_size(30)
        ctx.bit_rate(300_000)
        ctx.max_b_frames(0)
        ctx.thread_count(1)
        return encodeH264Inner(codec, ctx, count)
    }

    private fun encodeH264Inner(codec: org.bytedeco.ffmpeg.avcodec.AVCodec, ctx: AVCodecContext, count: Int): List<ByteArray> {
        if (avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) error("avcodec_open2 失败")
        val frame = av_frame_alloc() ?: error("av_frame_alloc 失败")
        frame.format(AV_PIX_FMT_YUV420P)
        frame.width(W)
        frame.height(H)
        if (av_frame_get_buffer(frame, 0) < 0) error("av_frame_get_buffer 失败")
        val out = ArrayList<ByteArray>(count)
        val pkt = AVPacket()
        try {
            for (i in 0 until count) {
                av_frame_make_writable(frame)
                fillYuv(frame, i)
                frame.pts(i.toLong())
                if (avcodec_send_frame(ctx, frame) < 0) error("avcodec_send_frame 失败 @#$i")
                drain(ctx, pkt, out)
            }
            // flush
            if (avcodec_send_frame(ctx, null) >= 0) drain(ctx, pkt, out)
        } finally {
            av_frame_free(frame)
            avcodec_free_context(ctx)
        }
        return out
    }

    private fun drain(ctx: AVCodecContext, pkt: AVPacket, out: MutableList<ByteArray>) {
        while (true) {
            val r = org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet(ctx, pkt)
            if (r < 0) break
            val data = pkt.data()
            val size = pkt.size()
            if (data != null && size > 0) {
                val bytes = ByteArray(size)
                data.get(bytes, 0, size)
                out.add(bytes)
            }
            av_packet_unref(pkt)
        }
    }

    /** 第 i 帧的 YUV420P 内容（Y 平面按帧序渐变，色度恒定灰） */
    private fun fillYuv(frame: AVFrame, i: Int) {
        val yLine = frame.linesize(0)
        val y = ByteArray(yLine * H) { 0 }
        for (y0 in 0 until H) {
            val v = ((i * 6 + y0 * 2) % 256).toByte()
            for (x0 in 0 until W) y[y0 * yLine + x0] = ((v.toInt() + x0) % 256).toByte()
        }
        frame.data(0).put(y, 0, y.size)
        val uvW = W / 2
        val uvH = H / 2
        val uLine = frame.linesize(1)
        val vLine = frame.linesize(2)
        val u = ByteArray(uLine * uvH) { 0x40.toByte() }
        val v = ByteArray(vLine * uvH) { 0x80.toByte() }
        frame.data(1).put(u, 0, u.size)
        frame.data(2).put(v, 0, v.size)
    }

    private fun av_rational(num: Int, den: Int): org.bytedeco.ffmpeg.avutil.AVRational =
        org.bytedeco.ffmpeg.avutil.AVRational().num(num).den(den)
}

// ── AnnexB ↔ AVCC 转换（测试侧工具） ──

/** AnnexB（起始码分隔 NAL 序列）→ AVCC（4B 大端长度前缀）——模拟 MediaCodec 输出形态 */
internal fun annexbToAvcc(data: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    val nals = ArrayList<Pair<Int, Int>>() // (offset, len)
    fun startCodeLen(pos: Int): Int =
        when {
            pos + 3 <= data.size && data[pos].toInt() == 0 && data[pos + 1].toInt() == 0 && data[pos + 2].toInt() == 1 -> 3
            pos + 4 <= data.size && data[pos].toInt() == 0 && data[pos + 1].toInt() == 0 && data[pos + 2].toInt() == 0 && data[pos + 3].toInt() == 1 -> 4
            else -> 0
        }
    // 收集每个 NAL 的 (起始码后 offset, 长度)
    var sc = startCodeLen(0)
    if (sc == 0) return data
    var cur = sc
    i = sc
    while (i < data.size) {
        val next = startCodeLen(i)
        if (next > 0) {
            nals.add(cur to (i - cur))
            i += next
            cur = i
        } else i++
    }
    nals.add(cur to (data.size - cur))
    for ((off, len) in nals) {
        if (len <= 0) continue
        out.write(byteArrayOf(((len ushr 24) and 0xFF).toByte(), ((len ushr 16) and 0xFF).toByte(), ((len ushr 8) and 0xFF).toByte(), (len and 0xFF).toByte()))
        out.write(data, off, len)
    }
    return out.toByteArray()
}
