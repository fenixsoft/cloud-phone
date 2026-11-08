package cloudphone.source

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 输入窗口合成驱动（触摸延迟修复 5.3）。
 *
 * 实测（scripts/measure_latency.py --touch 基线，静屏口径 20 样本）：
 * 触摸→视觉变化 P50=651ms / P95=3104ms。分段定位（①–⑥ 插桩）：
 * 注入 0–5ms、编码 ~20ms（ptsDeltaMs）、传输+渲染 7–14ms，
 * 大头是**静屏时 SurfaceFlinger 对虚拟屏镜像按 ~1Hz idle 节奏合成**——
 * 触摸引发的 App 重绘要等下一次合成才进镜像，最长 >3s。
 *
 * 修复：InputEvent 后 5s 输入窗口内，经 SYSTEM_ALERT_WINDOW 透明 1×1 悬窗以
 * 5fps 失效自己 → 强制 display 0 逐次合成 → 镜像帧即时携带触摸画面变化。
 * 悬窗完全透明、不可触摸、不获焦点，对源端画面与触摸语义零干扰。
 * 免权限路径不存在：公共 API 无「强制合成」入口（repeat-previous-frame 只重复
 * 编码器旧帧，不驱动合成；input 注入不使目标 App 必然重绘），故经 bootstrap
 * 显式登记 `appops set cloudphone.source SYSTEM_ALERT_WINDOW allow`；
 * 未授权时降级为现状（1Hz idle 合成）并打 WARN。
 */
class InputComposer(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var choreographer: Choreographer? = null

    /** 输入窗口截止时刻（elapsedRealtime ms） */
    @Volatile private var windowUntilMs = 0L
    @Volatile private var ticking = false
    private var warnNoPermission = false

    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) return
            if (SystemClock.elapsedRealtime() < windowUntilMs) {
                // 失效悬窗 → SF 合成 display 0（镜像帧随之更新）
                view?.invalidate()
                choreographer?.postFrameCallbackDelayed(this, TICK_INTERVAL_MS)
            } else {
                ticking = false
            }
        }
    }

    /** 会话建立时挂窗（无权限则降级：仅记录） */
    fun start() {
        if (!Settings.canDrawOverlays(context)) {
            if (!warnNoPermission) {
                warnNoPermission = true
                android.util.Log.w(
                    "cloudphone-touch",
                    "input-composer 降级：SYSTEM_ALERT_WINDOW 未授权（bootstrap appops 登记）→ 触摸窗口无 5fps 合成驱动",
                )
            }
            return
        }
        if (view != null) return
        choreographer = Choreographer.getInstance() ?: return
        val v = View(context)
        v.setBackgroundColor(0x00000000) // 完全透明
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        try {
            windowManager.addView(v, lp)
            view = v
            android.util.Log.i("cloudphone-touch", "input-composer 悬窗就绪（透明 1x1）")
        } catch (e: Exception) {
            android.util.Log.w("cloudphone-touch", "input-composer 悬窗失败: ${e.message}")
        }
    }

    /** InputEvent 到达：打开/顺延 5s 输入窗口并以 5fps 驱动合成 */
    fun onInputActivity() {
        windowUntilMs = SystemClock.elapsedRealtime() + INPUT_WINDOW_MS
        if (view == null || ticking) return
        ticking = true
        choreographer?.postFrameCallbackDelayed(tick, TICK_INTERVAL_MS)
    }

    /** 会话结束：摘窗停拍 */
    fun stop() {
        ticking = false
        view?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        view = null
    }

    companion object {
        /** 输入窗口时长（5.3：触摸后 5s） */
        const val INPUT_WINDOW_MS: Long = 5000
        /** 合成驱动节拍 5fps */
        const val TICK_INTERVAL_MS: Long = 200

        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: InputComposer? = null

        fun get(context: Context): InputComposer =
            instance ?: InputComposer(context.applicationContext).also { instance = it }
    }
}
