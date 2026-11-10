package cloudphone.desktop.session

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import cloudphone.protocol.Framing
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY
import org.bytedeco.ffmpeg.global.avcodec.av_new_packet
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.global.swscale.sws_scale_frame
import org.bytedeco.javacpp.BytePointer
import java.awt.image.BufferedImage
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.ArrayDeque
import kotlin.concurrent.thread

/** 解码输出帧：BGRA 像素（4B/px，B/G/R/A 字节序，alpha 恒 255——源 YUV 无 alpha） */
class DecodedFrame(
    val bgra: ByteArray,
    val width: Int,
    val height: Int,
    val sequence: Long,
    val ptsUs: Long,
)

/**
 * 逐级计数（spec：接收片数/解码帧数/积压丢弃帧数/上屏帧数，经 StateFlow 供 HUD 与诊断）。
 * 不变式：framesReceived = framesDecoded + framesDroppedBacklog + 队列残留。
 */
data class VideoPipelineStats(
    val framesReceived: Long = 0,
    val framesDecoded: Long = 0,
    /** 积压过深快进至关键帧边界时丢弃的待解码帧（保参考链完整：只丢关键帧之前的 P 帧） */
    val framesDroppedBacklog: Long = 0,
    /** 上屏帧数（latest-wins：仅队列空时的本帧即最新帧上屏） */
    val framesPresented: Long = 0,
    /** 解码完成但队列已有更新帧而未上屏的帧数（latest-wins 丢弃计数） */
    val framesDecodedNotPresented: Long = 0,
    /** 解码实际分辨率（HUD 分辨率取此值，非 START_SESSION 请求值） */
    val decodedWidth: Int = 0,
    val decodedHeight: Int = 0,
    /** 送包/收帧错误次数（诊断） */
    val decodeErrors: Long = 0,
)

/**
 * 桌面端解码渲染管线（design D2）：TransportBackend 视频片 → FFmpeg H.264 软解 →
 * BGRA（swscale）→ [latestFrame]（Compose Canvas 经 [FrameBitmapFactory.toImageBitmap] 上屏）。
 *
 * latest-wins 单消费者语义（对齐 client-decode-render）：
 * - 解码按序进行（保 P 帧参考链完整），上屏仅取最新帧；
 * - 积压过深时解码输入快进至下一关键帧边界（丢弃帧计数），不在参考链中间直接丢帧；
 * - 每级计数经 [stats] StateFlow 供 HUD 与诊断。
 *
 * debug dump（默认关闭）：[enableDebugDump] 后将原始 H.264 输入片按
 * `[u32 len][payload]` 前缀落盘，供 VM 冒烟黑屏/花屏取证。
 */
class VideoPipeline(
    /** 积压快进阈值（帧数）：队列深度超过即丢弃至下一关键帧 */
    private val fastForwardThreshold: Int = DEFAULT_FAST_FORWARD_THRESHOLD,
) {
    companion object {
        /** 默认快进阈值：≈200ms @60fps，超过即判定积压过深 */
        const val DEFAULT_FAST_FORWARD_THRESHOLD = 12
    }

    private val _stats = kotlinx.coroutines.flow.MutableStateFlow(VideoPipelineStats())
    val stats: kotlinx.coroutines.flow.StateFlow<VideoPipelineStats> = _stats

    /** 最新解码帧（latest-wins 上屏出口；Compose 侧 collect 后经 FrameBitmapFactory 转 ImageBitmap） */
    private val _latestFrame = kotlinx.coroutines.flow.MutableStateFlow<DecodedFrame?>(null)
    val latestFrame: kotlinx.coroutines.flow.StateFlow<DecodedFrame?> = _latestFrame

    // ── 输入队列（transport 回调线程入队，解码单消费者线程出队） ──

    private val queueLock = Object()
    private val queue = ArrayDeque<Framing.VideoFrame>()
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private var worker: Thread? = null

    // ── FFmpeg 解码器（单线程独占） ──

    private var codecCtx: org.bytedeco.ffmpeg.avcodec.AVCodecContext? = null
    private var swsCtx: org.bytedeco.ffmpeg.swscale.SwsContext? = null
    private var dstFrame: AVFrame? = null
    private var dstAllocW = 0
    private var dstAllocH = 0
    private val avFrame = avFrameAllocSafe()
    private val packet = AVPacket()
    private var swsSrcW = 0
    private var swsSrcH = 0
    private var swsSrcFmt = -1

    private fun avFrameAllocSafe(): AVFrame? = try {
        av_frame_alloc()
    } catch (t: Throwable) {
        null
    }

    // ── 计数器（工作线程内更新，快照发 StateFlow） ──

    private var received = 0L
    private var decoded = 0L
    private var decodeErrors = 0L
    private var droppedBacklog = 0L
    private var presented = 0L
    private var decodedNotPresented = 0L
    private var decodedW = 0
    private var decodedH = 0

    // ── debug dump ──

    @Volatile private var dumpStream: BufferedOutputStream? = null

    /** 解码线程最近一次未捕获异常（诊断出口；正常为 null） */
    @Volatile var lastError: Throwable? = null
        private set

    /** 启用原始 H.264 输入片 dump（黑屏/花屏取证用；默认关闭） */
    fun enableDebugDump(path: Path) {
        dumpStream?.close()
        dumpStream = BufferedOutputStream(FileOutputStream(path.toFile()))
    }

    fun disableDebugDump() {
        runCatching { dumpStream?.flush() }
        runCatching { dumpStream?.close() }
        dumpStream = null
    }

    /** 启动解码消费者（幂等；重复调用忽略） */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        initDecoder()
        worker = thread(isDaemon = true, name = "video-decode") {
            try {
                decodeLoop()
            } catch (t: Throwable) {
                lastError = t
                decodeErrors++
                publishStats()
            }
        }
    }

    /** 视频帧入口（transport 回调线程；永不阻塞——积压防护由消费者快进完成） */
    fun onVideoFrame(frame: Framing.VideoFrame) {
        if (!running.get()) return
        if (frame.data.isEmpty()) return
        synchronized(queueLock) {
            queue.addLast(frame)
            received++
            publishStats()
            queueLock.notifyAll()
        }
    }

    /** 关闭：停消费者、释放解码器与 dump 流 */
    fun close() {
        if (!running.compareAndSet(true, false)) return
        synchronized(queueLock) { queueLock.notifyAll() }
        worker?.join(2000)
        worker = null
        synchronized(queueLock) { queue.clear() }
        releaseDecoder()
        disableDebugDump()
    }

    // ── 解码主循环（单消费者：按序解码保参考链；上屏仅最新） ──

    private fun decodeLoop() {
        while (running.get()) {
            val frame = takeNext() ?: continue
            val annexb = toAnnexB(frame.data)
            dumpStream?.let { s ->
                synchronized(s) {
                    val len = annexb.size
                    s.write((len ushr 24) and 0xFF)
                    s.write((len ushr 16) and 0xFF)
                    s.write((len ushr 8) and 0xFF)
                    s.write(len and 0xFF)
                    s.write(annexb)
                }
            }
            val outFrames = decodeOne(annexb, frame.keyframe)
            for (f in outFrames) {
                decoded++
                decodedW = f.width
                decodedH = f.height
                val isLatest = synchronized(queueLock) { queue.isEmpty() }
                if (isLatest) {
                    _latestFrame.value = f
                    presented++
                } else {
                    decodedNotPresented++
                }
                publishStats()
            }
        }
    }

    /** 取下一帧；队列过深时快进到关键帧边界（丢弃关键帧之前的 P 帧，计数） */
    private fun takeNext(): Framing.VideoFrame? {
        synchronized(queueLock) {
            if (queue.isEmpty()) {
                queueLock.wait(100)
                if (queue.isEmpty()) return null
            }
            if (queue.size <= fastForwardThreshold) {
                return queue.removeFirst()
            }
            // 快进：丢弃至下一关键帧（关键帧自含参考；其后的 P 帧参考链完整不被打断）
            var dropped = 0
            while (queue.isNotEmpty()) {
                val f = queue.removeFirst()
                if (f.keyframe) {
                    if (dropped > 0) {
                        droppedBacklog += dropped
                        publishStats()
                    }
                    return f
                }
                dropped++
            }
            // 整队列无关键帧：全部丢弃（参考链已断裂，逐帧解码只会花屏）
            droppedBacklog += dropped
            publishStats()
            return null
        }
    }

    /** 单片解码：送包并抽取全部输出帧（按序，保 P 帧参考）。不注入时间戳（交给解码器按到达序处理） */
    private fun decodeOne(annexb: ByteArray, keyframe: Boolean): List<DecodedFrame> {
        val ctx = codecCtx ?: return emptyList()
        // 规范 refcounted 喂入：av_new_packet 分配缓冲 + 拷贝 + send + unref（避免裸指针归属歧义）
        val ret = av_new_packet(packet, annexb.size)
        if (ret < 0) return emptyList()
        try {
            packet.data().put(annexb, 0, annexb.size)
            packet.flags(if (keyframe) packet.flags() or AV_PKT_FLAG_KEY else packet.flags())
            val send = avcodec_send_packet(ctx, packet)
            if (send < 0 && send != -11 /* AVERROR(EAGAIN) */) {
                decodeErrors++
                publishStats()
                System.err.println("[video-pipeline] send_packet 失败 ret=$send size=${annexb.size} key=$keyframe")
                return emptyList()
            }
            val out = ArrayList<DecodedFrame>(1)
            while (true) {
                val r = avcodec_receive_frame(ctx, avFrame)
                if (r == -11 || r == AVERROR_EOF) break
                if (r < 0) break
                convertFrame()?.let { out.add(it) }
            }
            return out
        } finally {
            av_packet_unref(packet)
        }
    }

    /** 解码帧 → BGRA（sws_scale_frame 直传 AVFrame；分辨率变化时重建 sws 上下文与目标帧） */
    private fun convertFrame(): DecodedFrame? {
        val f = avFrame ?: return null
        val w = f.width()
        val h = f.height()
        if (w <= 0 || h <= 0) return null
        val fmt = f.format()
        if (swsCtx == null || swsSrcW != w || swsSrcH != h || swsSrcFmt != fmt) {
            sws_freeContext(swsCtx)
            swsCtx = sws_getContext(w, h, fmt, w, h, AV_PIX_FMT_BGRA, SWS_BILINEAR, null, null, null as org.bytedeco.javacpp.DoublePointer?)
            swsSrcW = w
            swsSrcH = h
            swsSrcFmt = fmt
        }
        val sws = swsCtx ?: return null
        val dst = ensureDstFrame(w, h) ?: return null
        val ret = sws_scale_frame(sws, dst, f)
        if (ret < 0) return null
        // 读出 BGRA（剥离栅格行对齐填充）
        val stride = dst.linesize(0)
        val rowBytes = w * 4
        val out = ByteArray(rowBytes * h)
        val src = dst.data(0)
        if (stride == rowBytes) {
            src.get(out, 0, out.size)
        } else {
            for (y in 0 until h) {
                src.position((y * stride).toLong()).get(out, y * rowBytes, rowBytes)
            }
            src.position(0)
        }
        decodedW = w
        decodedH = h
        return DecodedFrame(bgra = out, width = w, height = h, sequence = 0, ptsUs = f.pts())
    }

    /** 复用的 BGRA 目标帧（分辨率变化时重建） */
    private fun ensureDstFrame(w: Int, h: Int): AVFrame? {
        val existing = dstFrame
        if (existing != null && dstAllocW == w && dstAllocH == h) return existing
        existing?.let { av_frame_free(it) }
        val d = av_frame_alloc() ?: return null
        d.format(AV_PIX_FMT_BGRA)
        d.width(w)
        d.height(h)
        if (av_frame_get_buffer(d, 32) < 0) {
            av_frame_free(d)
            dstFrame = null
            return null
        }
        dstFrame = d
        dstAllocW = w
        dstAllocH = h
        return d
    }

    // ── 解码器生命周期 ──

    private fun initDecoder() {
        if (codecCtx != null) return
        val codec = avcodec_find_decoder(AV_CODEC_ID_H264)
        if (codec == null) {
            System.err.println("[video-pipeline] H.264 解码器不可用（FFmpeg 原生库缺失？）")
            return
        }
        val ctx = avcodec_alloc_context3(codec)
        // 单线程解码：输出确定性（无帧级线程缓冲尾帧延迟），1080P60 软解单线程可达实时
        ctx.thread_count(1)
        if (avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) {
            avcodec_free_context(ctx)
            System.err.println("[video-pipeline] avcodec_open2 失败")
            return
        }
        codecCtx = ctx
    }

    private fun releaseDecoder() {
        codecCtx?.let { avcodec_free_context(it) }
        codecCtx = null
        avFrame?.let { av_frame_free(it) }
        dstFrame?.let { av_frame_free(it) }
        dstFrame = null
        dstAllocW = 0
        dstAllocH = 0
        sws_freeContext(swsCtx)
        swsCtx = null
        swsSrcW = 0
        swsSrcH = 0
        swsSrcFmt = -1
    }

    private fun publishStats() {
        _stats.value = VideoPipelineStats(
            framesReceived = received,
            framesDecoded = decoded,
            framesDroppedBacklog = droppedBacklog,
            framesPresented = presented,
            framesDecodedNotPresented = decodedNotPresented,
            decodedWidth = decodedW,
            decodedHeight = decodedH,
            decodeErrors = decodeErrors,
        )
    }
}

/**
 * 解码帧 → Compose ImageBitmap（BufferedImage 4BYTE_ABGR 与 FFmpeg BGRA 字节序一致，
 * 零逐通道换算；视频帧 alpha 恒 255，premul/unpremul 等价）。
 */
object FrameBitmapFactory {
    /** BGRA 帧 → BufferedImage（截屏保存与 ImageBitmap 转换共用） */
    fun toBufferedImage(frame: DecodedFrame): BufferedImage {
        val img = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_4BYTE_ABGR)
        val raster = img.raster
        val dataBuffer = raster.dataBuffer as java.awt.image.DataBufferByte
        val target = dataBuffer.data
        // BufferedImage 栅格行无填充（PixelInterleavedSampleModel 紧凑布局），整块拷贝
        System.arraycopy(frame.bgra, 0, target, 0, frame.bgra.size.coerceAtMost(target.size))
        return img
    }

    /** BGRA 帧 → ImageBitmap（Compose Canvas 上屏） */
    fun toImageBitmap(frame: DecodedFrame): ImageBitmap = toBufferedImage(frame).toComposeImageBitmap()
}

// ── AVCC → AnnexB 转换（源端 MediaCodec 输出为 4B 长度前缀；FFmpeg 吃 AnnexB） ──

/** 判断是否已是 AnnexB（00 00 00 01 或 00 00 01 起始码） */
internal fun isAnnexB(data: ByteArray): Boolean =
    (data.size >= 4 && data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 0 && data[3].toInt() == 1) ||
        (data.size >= 3 && data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 1)

/** 尝试按 AVCC（4B 大端长度前缀 NAL 序列）完整解析：全部 NAL 恰好铺满即成功 */
internal fun parsesAsAvcc(data: ByteArray): Boolean {
    if (data.size < 4) return false
    var i = 0
    var nals = 0
    while (i + 4 <= data.size) {
        val len = ((data[i].toInt() and 0xFF) shl 24) or ((data[i + 1].toInt() and 0xFF) shl 16) or
            ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
        if (len <= 0 || i + 4 + len > data.size) return false
        i += 4 + len
        nals++
    }
    return nals > 0 && i == data.size
}

/**
 * AVCC（4B 长度前缀 NAL 序列，MediaCodec 默认输出）→ AnnexB（00 00 00 01 起始码）。
 * 源端在每个关键帧前前置 csd（SPS/PPS，同为 AVCC 形态），逐 NAL 转换即可整帧还原。
 * 分类规则：能按 AVCC 完整解析（NAL 恰好铺满）才转换；否则视为 AnnexB 直通。
 * （注意：不能用 3B 起始码嗅探判 AnnexB——AVCC 长度前缀 00 00 01 xx 会被误判成起始码）
 */
internal fun toAnnexB(data: ByteArray): ByteArray {
    if (!parsesAsAvcc(data)) return data
    val out = ByteArray(data.size + 64)
    var o = 0
    var i = 0
    while (i + 4 <= data.size) {
        val len = ((data[i].toInt() and 0xFF) shl 24) or ((data[i + 1].toInt() and 0xFF) shl 16) or
            ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
        if (len <= 0 || i + 4 + len > data.size) return data
        // 4B 起始码（00 00 00 01）容量恒足够（替换 4B 长度前缀）
        out[o] = 0; out[o + 1] = 0; out[o + 2] = 0; out[o + 3] = 1
        o += 4
        System.arraycopy(data, i + 4, out, o, len)
        o += len
        i += 4 + len
    }
    return out.copyOf(o)
}
