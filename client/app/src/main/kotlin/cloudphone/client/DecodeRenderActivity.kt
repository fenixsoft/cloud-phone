package cloudphone.client

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import cloudphone.protocol.ControlMessage
import cloudphone.protocol.encodeControlMessage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 硬解码渲染 Activity（4.1）：
 * - 客户端硬解 H.264 → SurfaceView 渲染（MediaCodec 硬件解码器优选）；
 * - 首帧白闪测量：连接后表面先铺白，首帧渲染时刻 - 连接建立时刻 = 白闪时长（spec：白闪 MUST 可测量）；
 * - 帧级延迟：本地渲染时刻 - 源端 PTS（elapsedRealtimeNanos 同源假设，见 docs/validation-report）；
 * - 丢帧/黑屏恢复：>1s 无输出帧 → 发 IDR_REQUEST（≤200ms 关键帧恢复）；
 * - 首帧等待：白闪界面（白底 + "等待画面…" 提示）。
 */
class DecodeRenderActivity : android.app.Activity() {

    private lateinit var surfaceView: SurfaceView
    private var decoder: MediaCodec? = null
    private val running = AtomicBoolean(false)
    // 深度 8（≈133ms@60fps）：解码慢于收流时丢旧帧保延迟上界（spec P95≤200ms），保关键帧
    private val frameQueue = LinkedBlockingQueue<ClientSession.VideoFrame>(8)
    private val lastOutputAtMs = AtomicLong(0)
    private val connectedAtMs = AtomicLong(0)
    private val firstFrameRenderedMs = AtomicLong(-1)
    private val idrInFlight = AtomicBoolean(false)
    private var renderThread: Thread? = null
    private var watchThread: Thread? = null

    /** 延迟采样（PTS 差值，ms）——最近 100 帧滚动窗口，供 StatsView/日志导出 */
    val delaySamplesMs = ArrayDeque<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        surfaceView = SurfaceView(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // 白闪底：首帧渲染前全白（测量基准）
        root.setBackgroundColor(0xFFFFFFFF.toInt())
        setContentView(root)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = startPipeline(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = stopPipeline()
        })
        ClientSession.onFrame = { frame -> onTransportFrame(frame) }
        ClientSession.onConnectionLost = {
            runOnUiThread {
                root.setBackgroundColor(0xFFFFFFFF.toInt())
                firstFrameRenderedMs.set(-1)
                android.widget.Toast.makeText(
                    this, "连接断开，正在重连（≤1s）…", android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        connectedAtMs.set(SystemClock.elapsedRealtime())
    }

    // ── 传输帧入口 ──

    private fun onTransportFrame(frame: ClientSession.VideoFrame) {
        // 丢帧保护：队列满（解码背压）时丢最旧的普通帧，保留关键帧
        while (!frameQueue.offer(frame)) {
            val dropped = frameQueue.poll()
            if (dropped?.keyframe == true) frameQueue.offer(dropped)
        }
    }

    // ── 解码管线 ──

    private fun startPipeline(surface: Surface) {
        if (running.get()) return
        running.set(true)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720).apply {
            // 实际分辨率随首帧 SPS 自适应（解码器按流内容解析）
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
        }
        val name = pickDecoder() ?: run {
            android.widget.Toast.makeText(this, "无可用 AVC 解码器", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val codec = MediaCodec.createByCodecName(name)
        try {
            codec.configure(format, surface, null, 0)
        } catch (e: Exception) {
            // 分辨率不匹配等：不带尺寸重配（流内 SPS 优先）
            val retry = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
            retry.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            codec.configure(retry, surface, null, 0)
        }
        codec.start()
        decoder = codec
        renderThread = Thread({ decodeLoop(codec) }, "client-decode").apply { isDaemon = true; start() }
        watchThread = Thread({ blackScreenWatch() }, "client-watch").apply { isDaemon = true; start() }
    }

    private fun pickDecoder(): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder.not() }
        val avc = list.filter { it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        // goldfish 解码器是模拟器虚拟硬件：吃帧不出帧（stale input buffer 后静默），实测必须避开；
        // 真机无 goldfish，硬解优先不受影响
        val usable = avc.filter { !it.name.contains("goldfish", true) }
        return (usable.firstOrNull {
            val n = it.name
            !n.startsWith("OMX.google") && !n.startsWith("c2.android")
        } ?: usable.firstOrNull() ?: avc.firstOrNull())?.name
    }

    private fun decodeLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val frame = frameQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue
            var index = codec.dequeueInputBuffer(20_000)
            if (index >= 0) {
                val buf = codec.getInputBuffer(index) ?: continue
                buf.clear()
                buf.put(frame.data)
                codec.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, 0)
            } else {
                continue
            }
            index = codec.dequeueOutputBuffer(info, 20_000)
            if (index >= 0) {
                codec.releaseOutputBuffer(index, true) // 渲染到 Surface
                onFrameRendered(frame, info)
            }
        }
    }

    private fun onFrameRendered(frame: ClientSession.VideoFrame, info: MediaCodec.BufferInfo) {
        lastOutputAtMs.set(SystemClock.elapsedRealtime())
        idrInFlight.set(false)
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        // 收流→渲染管线延迟（客户端本地时钟，跨设备时钟域不可直接相减）
        val delayMs = (nowNanos / 1000 - frame.arrivalUs) / 1000
        if (delayMs in 0..10_000) {
            synchronized(delaySamplesMs) {
                delaySamplesMs.addLast(delayMs)
                while (delaySamplesMs.size > 100) delaySamplesMs.removeFirst()
            }
            android.util.Log.i(
                "cloudphone-latency",
                "frame seq=${frame.sequence} delayMs=$delayMs keyframe=${frame.keyframe}",
            )
        }
        if (firstFrameRenderedMs.compareAndSet(-1, SystemClock.elapsedRealtime())) {
            val flashMs = firstFrameRenderedMs.get() - connectedAtMs.get()
            android.util.Log.i("cloudphone-latency", "white-flash Ms=$flashMs")
            runOnUiThread {
                // 首帧已渲染：撤白闪
                (findViewById<FrameLayout>(android.R.id.content)).setBackgroundColor(0x00000000)
            }
        }
    }

    /** 黑屏看守：>1s 无输出帧 → 请求 IDR（≤200ms 恢复） */
    private fun blackScreenWatch() {
        while (running.get()) {
            Thread.sleep(300)
            val idleMs = SystemClock.elapsedRealtime() - lastOutputAtMs.get()
            if (idleMs > 1000 && idrInFlight.compareAndSet(false, true)) {
                ClientSession.sendControl(
                    ControlMessage.IdrRequest(sessionId = ClientSession.currentSessionId),
                )
            }
        }
    }

    /** 请求立即关键帧（UI 手动触发入口） */
    fun requestKeyframe() {
        idrInFlight.set(false)
        ClientSession.sendControl(ControlMessage.IdrRequest(sessionId = ClientSession.currentSessionId))
    }

    private fun stopPipeline() {
        running.set(false)
        renderThread?.join(1000)
        watchThread?.join(300)
        runCatching {
            decoder?.stop()
            decoder?.release()
        }
        decoder = null
        frameQueue.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPipeline()
        ClientSession.onFrame = null
        ClientSession.onConnectionLost = null
    }

    /** 最近延迟统计（P50/P95，供 StatsView） */
    fun delayStats(): Pair<Long, Long>? {
        synchronized(delaySamplesMs) {
            if (delaySamplesMs.isEmpty()) return null
            val sorted = delaySamplesMs.sorted()
            return sorted[sorted.size / 2] to sorted[((sorted.size - 1) * 95) / 100]
        }
    }
}
