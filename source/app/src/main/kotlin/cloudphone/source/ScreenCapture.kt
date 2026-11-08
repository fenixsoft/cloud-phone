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
    private val params: EncoderPipeline.SessionParams,
    /** 授权丢失（用户从状态栏停止投影）时回调 */
    private val onProjectionLost: () -> Unit,
) : CaptureSource {

    override val describe: String get() = "fullscreen ${params.width}x${params.height}@${params.fps}"

    private var encoder: EncoderPipeline? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayListener: DisplayManager.DisplayListener? = null
    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

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

    private fun buildPipeline(
        p: EncoderPipeline.SessionParams,
        onFrame: (sequence: Long, ptsUs: Long, keyframe: Boolean, data: ByteArray) -> Unit,
    ) {
        val enc = EncoderPipeline(p.width, p.height, p.fps, p.bitrateKbps) { seq, pts, kf, data ->
            PipelineCounters.sentFrames.incrementAndGet()
            onFrame(seq, pts, kf, data)
        }
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
        synchronized(this) {
            virtualDisplay?.release()
            virtualDisplay = null
            encoder?.stop()
            encoder = null
            buildPipeline(EncoderPipeline.SessionParams.normalized(w, h, params.fps, params.bitrateKbps), onFrame)
            encoder?.requestIdr()
        }
    }

    override fun requestIdr() {
        encoder?.requestIdr()
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
