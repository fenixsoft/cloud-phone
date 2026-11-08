package cloudphone.loadgen

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 恒定负载页（Choreographer 驱动 60fps 动画 + 帧计数 overlay + 可点击按钮）：
 * - 动画帧回调逐帧重绘（帧率压测基准，源端整屏/虚拟屏采集对象）；
 * - 帧计数 overlay 实时显示累计帧数与瞬时 fps（客户端画面可见 → 画面流畅性判据）；
 * - 可点击按钮：点击白闪 + 计数（触摸注入通路验证 + 白闪延迟测量标记）。
 */
class GameLoadActivity : Activity() {

    private lateinit var counterText: TextView
    private lateinit var fpsText: TextView
    private lateinit var tapFlash: View
    private lateinit var root: FrameLayout

    private val frames = java.util.concurrent.atomic.AtomicLong()
    private val taps = java.util.concurrent.atomic.AtomicLong()
    private var lastFpsAtMs = 0L
    private var lastFpsFrames = 0L
    private var animRunning = false

    // 60fps 动画：方块往返移动
    private var boxX = 0f
    private var boxDir = 1f
    private lateinit var box: View

    private val frameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animRunning) return
            frames.incrementAndGet()
            val w = root.width.coerceAtLeast(1)
            boxX += boxDir * (w / 120f) // ~2s 单程
            if (boxX > w - 120) boxDir = -1f
            if (boxX < 0) boxDir = 1f
            box.translationX = boxX
            val now = SystemClock.elapsedRealtime()
            if (now - lastFpsAtMs >= 500) {
                val fps = (frames.get() - lastFpsFrames) * 1000 / (now - lastFpsAtMs).coerceAtLeast(1)
                fpsText.text = "fps=$fps frames=${frames.get()}"
                lastFpsAtMs = now
                lastFpsFrames = frames.get()
            }
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        root = FrameLayout(this).apply { setBackgroundColor(0xFF20306A.toInt()) }

        box = View(this).apply { setBackgroundColor(0xFFFF5533.toInt()) }
        root.addView(
            box,
            FrameLayout.LayoutParams((120 * density).toInt(), (120 * density).toInt(), Gravity.TOP or Gravity.START),
        )

        counterText = TextView(this).apply {
            text = "GameLoad 60fps 压测页"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
        }
        root.addView(
            counterText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ),
        )
        fpsText = TextView(this).apply {
            setTextColor(0xFF88FF88.toInt())
            textSize = 14f
        }
        root.addView(
            fpsText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = (28 * density).toInt() },
        )

        // 可点击按钮：点击 → 白闪 120ms + 计数（触摸通路验证 + 延迟测量标记）
        val tapButton = Button(this).apply {
            text = "点我（白闪标记）"
            setOnClickListener {
                taps.incrementAndGet()
                counterText.text = "taps=${taps.get()}"
                tapFlash.visibility = View.VISIBLE
                window.decorView.postDelayed({ tapFlash.visibility = View.GONE }, 120)
                Toast.makeText(this@GameLoadActivity, "tap #${taps.get()}", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(
            tapButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = (48 * density).toInt() },
        )

        // 白闪覆盖层（延迟测量：注入点击 → 白帧出现 → 客户端解码可见）
        tapFlash = View(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            visibility = View.GONE
        }
        root.addView(
            tapFlash,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        animRunning = true
        lastFpsAtMs = SystemClock.elapsedRealtime()
        android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onPause() {
        animRunning = false
        super.onPause()
    }
}
