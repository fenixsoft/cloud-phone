package cloudphone.source

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Surface
import cloudphone.source.obs.PipelineCounters

/**
 * 会话画面源统一接口：MediaProjection 整屏 / 虚拟屏单应用双路径共用。
 * 输出统一为 H.264 帧回调（sequence/ptsUs/keyframe/data），供 SessionManager 送入传输层。
 */
interface CaptureSource {
    /** 启动并开始出帧；成功 true */
    fun start(onFrame: (sequence: Long, ptsUs: Long, keyframe: Boolean, data: ByteArray) -> Unit): Boolean
    fun stop()
    fun requestIdr()
    val describe: String
}

/**
 * 整屏采集（MediaProjection 路径）：
 * 投影授权 → 虚拟屏镜像主屏（AUTO_MIRROR）→ EncoderPipeline（Surface 输入）→ H.264 帧。
 * 旋转/分辨率变化：DisplayListener 触发管线重建并立即请求 IDR（spec：变化 MUST 重建+IDR）。
 */
class ScreenCapture(
    private val context: Context,
    private val projection: MediaProjection,
    /** aspect-exact 后的实际编码参数；adaptTo 成功后更新 */
    private var params: EncoderPipeline.SessionParams,
    /** 授权丢失（用户从状态栏停止投影）时回调 */
    private val onProjectionLost: () -> Unit,
) : CaptureSource {

    override val describe: String get() = "fullscreen ${params.width}x${params.height}@${params.fps}"

    private var encoder: EncoderPipeline? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayListener: DisplayManager.DisplayListener? = null
    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 现行编码参数（aspect-exact 后的实际值；adaptTo 成功后随参数更新） */
    val currentParams: EncoderPipeline.SessionParams get() = params

    private fun frameSink(
        onFrame: (sequence: Long, ptsUs: Long, keyframe: Boolean, data: ByteArray) -> Unit,
    ): (sequence: Long, ptsUs: Long, keyframe: Boolean, data: ByteArray) -> Unit =
        { seq, pts, kf, data ->
            PipelineCounters.sentFrames.incrementAndGet()
            onFrame(seq, pts, kf, data)
        }

    override fun start(onFrame: (sequence: Long, ptsUs: Long, keyframe: Boolean, data: ByteArray) -> Unit): Boolean {
        return try {
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    PipelineCounters.onBadEvent(
                        PipelineCounters.BadEvent.PROJECTION_LOST, "user stopped projection",
                    )
                    onProjectionLost()
                }
            }, mainHandler)
            buildPipeline(params, onFrame)
            registerDisplayListener(onFrame)
            PipelineCounters.capturedFrames.set(0)
            true
        } catch (e: Exception) {
            android.util.Log.w("cloudphone-source", "capture start failed: ${e.javaClass.simpleName}: ${e.message}")
            PipelineCounters.onBadEvent(
                PipelineCounters.BadEvent.CAPTURE_FAILURE, "start: ${e.message}",
            )
            false
        }
    }

    /** 宽高比精确化：按主屏真实分辨率等比缩放（短边=请求档位短边），16 对齐后作为编码与 VD 尺寸 */
    private fun aspectExact(requested: EncoderPipeline.SessionParams): EncoderPipeline.SessionParams {
        // VD 固定尺寸镜像会非等比拉伸主屏内容（20:9 屏被压进 16:9 → 画面扭曲）
        val d = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val metrics = android.util.DisplayMetrics()
        if (d == null) return requested
        d.getRealMetrics(metrics)
        val dw = metrics.widthPixels
        val dh = metrics.heightPixels
        if (dw <= 0 || dh <= 0) return requested
        val shortTarget = minOf(requested.width, requested.height)
        val scale = shortTarget.toDouble() / minOf(dw, dh)
        fun align16(v: Double) = ((v / 16).toInt() * 16).coerceAtLeast(16)
        val w = align16(dw * scale)
        val h = align16(dh * scale)
        if (w == requested.width && h == requested.height) return requested
        android.util.Log.i(
            "cloudphone-source",
            "capture: aspect-exact ${dw}x${dh} -> ${w}x${h} (tier short=$shortTarget)",
        )
        return EncoderPipeline.SessionParams.normalized(w, h, requested.fps, requested.bitrateKbps)
    }

    private fun buildPipeline(
        requested: EncoderPipeline.SessionParams,
        onFrame: (sequence: Long, ptsUs: Long, keyframe: Boolean, data: ByteArray) -> Unit,
    ) {
        val p = aspectExact(requested)
        val enc = EncoderPipeline(p.width, p.height, p.fps, p.bitrateKbps, frameSink(onFrame), { gapMs ->
            // ④ 采集出帧 idle 段打点（触摸延迟插桩 5.1：帧间隔>200ms 的静屏段）
            android.util.Log.i("cloudphone-touch", "t4 capture idle gapMs=$gapMs")
        })
        enc.start()
        encoder = enc
        val surface: Surface = checkNotNull(enc.surface) { "encoder surface unavailable" }
        // 必须经 MediaProjection.createVirtualDisplay（携带投影授权令牌）；
        // 直接 DisplayManager.createVirtualDisplay(AUTO_MIRROR) 会因 CAPTURE_VIDEO_OUTPUT 权限静默拒投
        virtualDisplay = projection.createVirtualDisplay(
            "cloudphone-fullscreen",
            p.width, p.height,
            context.resources.displayMetrics.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        )
        val vd = virtualDisplay
        android.util.Log.i(
            "cloudphone-source",
            "capture: vd=${if (vd != null) "id=${vd.display?.displayId} valid=${vd.display?.isValid}" else "NULL"} " +
                "enc=${enc.encoderName} ${p.width}x${p.height}@${p.fps}",
        )
        if (vd == null || vd.display?.isValid != true) {
            PipelineCounters.onBadEvent(PipelineCounters.BadEvent.CAPTURE_FAILURE, "virtual display invalid")
            throw IllegalStateException("MediaProjection 虚拟屏创建失败（授权态失效？）")
        }
        PipelineCounters.capturedFrames.incrementAndGet()
    }

    /**
     * 异参自适应：请求档位与现行管线不同时，换编码器面而不重建投影。
     * MediaProjection 凭据一次性（Android 14：同 resultData 二次 getMediaProjection /
     * 同实例二次 createVirtualDisplay 均抛 SecurityException），因此档位切换 MUST 走
     * 零令牌路径：新建编码器 → VirtualDisplay.setSurface 切换合成目标 → 停旧编码器。
     * 任意客户端分辨率/帧率/码率即插即连，不再依赖后台重新授权。
     */
    fun adaptTo(
        requested: EncoderPipeline.SessionParams,
        onFrame: (sequence: Long, ptsUs: Long, keyframe: Boolean, data: ByteArray) -> Unit,
    ): Boolean = try {
        synchronized(this) {
            if (stopped.get()) throw IllegalStateException("capture already stopped")
            val vd = checkNotNull(virtualDisplay) { "virtual display gone" }
            val p = aspectExact(requested)
            val oldEnc = encoder
            val enc = EncoderPipeline(p.width, p.height, p.fps, p.bitrateKbps, frameSink(onFrame), { gapMs ->
                android.util.Log.i("cloudphone-touch", "t4 capture idle gapMs=$gapMs")
            })
            enc.start()
            val surface = checkNotNull(enc.surface) { "encoder surface unavailable" }
            try {
                vd.setSurface(surface) // 零令牌切换合成目标（MediaProjection 不受影响）
                try {
                    vd.resize(p.width, p.height, context.resources.displayMetrics.densityDpi)
                } catch (e: Exception) {
                    // 部分镜像不允许运行中 resize：合成器按 surface 几何等比缩放兜底（同宽高比无损）
                    android.util.Log.i("cloudphone-source", "vd resize unsupported (surface 缩放兜底): ${e.message}")
                }
            } catch (e: Exception) {
                enc.stop() // 新管线上面失败：回收新编码器，旧管线保持可用
                throw e
            }
            oldEnc?.stop()
            encoder = enc
            params = p
            enc.requestIdr() // 切档立即出关键帧（客户端秒出画面）
            PipelineCounters.capturedFrames.incrementAndGet()
            android.util.Log.i(
                "cloudphone-source",
                "capture adapt -> ${enc.encoderName} ${p.width}x${p.height}@${p.fps}/${p.bitrateKbps}kbps",
            )
        }
        true
    } catch (e: Exception) {
        android.util.Log.w("cloudphone-source", "capture adapt failed: ${e.javaClass.simpleName}: ${e.message}")
        PipelineCounters.onBadEvent(PipelineCounters.BadEvent.CAPTURE_FAILURE, "adapt: ${e.message}")
        false
    }

    private fun registerDisplayListener(
        onFrame: (sequence: Long, ptsUs: Long, keyframe: Boolean, data: ByteArray) -> Unit,
    ) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != android.view.Display.DEFAULT_DISPLAY) return
                val metrics = context.resources.displayMetrics
                val rotatedW = metrics.widthPixels
                val rotatedH = metrics.heightPixels
                val currentW = minOf(rotatedW, rotatedH) == minOf(params.width, params.height)
                if (!currentW || metrics.densityDpi != virtualDisplay?.display?.let { 0 }) {
                    // 旋转或分辨率变化：重建管线并立即 IDR
                    rebuild(rotatedW, rotatedH, metrics.densityDpi, onFrame)
                }
            }

            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        dm.registerDisplayListener(listener, mainHandler)
        displayListener = listener
    }

    private fun rebuild(w: Int, h: Int, dpi: Int, onFrame: (sequence: Long, ptsUs: Long, keyframe: Boolean, data: ByteArray) -> Unit) {
        PipelineCounters.onBadEvent(PipelineCounters.BadEvent.DISPLAY_REBUILD, "${w}x$h@$dpi")
        // 旋转/分辨率变化：同样走零令牌换面（不再 release + 二次 createVirtualDisplay，
        // 规避同投影实例二次采集的镜像级限制），换面后 adaptTo 内已即时请求 IDR
        adaptTo(EncoderPipeline.SessionParams.normalized(w, h, params.fps, params.bitrateKbps), onFrame)
    }

    override fun requestIdr() {
        encoder?.requestIdr()
    }

    /** InputEvent 到达：打开 2s 插桩/提速窗口（5.1 打点 + 5.3 修复挂点，StreamService 调用） */
    fun onInputActivity() {
        encoder?.inputTraceUntilMs = android.os.SystemClock.elapsedRealtime() + 2000
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        displayListener?.let {
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .unregisterDisplayListener(it)
        }
        synchronized(this) {
            virtualDisplay?.release()
            virtualDisplay = null
            encoder?.stop()
            encoder = null
        }
    }
}
