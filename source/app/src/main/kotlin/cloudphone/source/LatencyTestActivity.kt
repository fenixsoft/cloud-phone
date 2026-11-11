package cloudphone.source

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.random.Random

/**
 * 延迟测试（移动靶）——串流链路端到端延迟的体感量尺。
 *
 * 玩法：源端屏幕上 3 个圆点横向匀速弹跳移动；观看端（Android/Windows 客户端）
 * 点中圆点得分，点空断连击。圆点直径固定，速度越快「可命中时间窗」越窄
 * （窗宽 = 直径/速度），要点中就必须把 操作→注入→采集→编码→传输→渲染→再操作
 * 的整条延迟压进窗口内——速度档即延迟量尺。
 *
 * 速度档位（px/s，1080 宽屏，圆点直径 120px 的命中时间窗）：
 *   1 慢 300（400ms）｜2 中 700（170ms）｜3 快 1400（86ms）｜4 很快 2400（50ms）｜5 极限 4000（30ms）
 *
 * 屏幕底部左右角固定「－速度／＋速度」热区（单点触控，注入点击即可用），
 * 顶部 HUD 显示 得分/连击/命中率/当前档位与命中窗。命中瞬间圆点闪白——
 * 闪光回到你屏幕的耗时就是本次操作的真实往返延迟（体感直读）。
 */
class LatencyTestActivity : Activity() {

    private lateinit var testView: LatencyTestView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        testView = LatencyTestView(this)
        setContentView(testView)
    }

    override fun onResume() {
        super.onResume()
        testView.resume()
    }

    override fun onPause() {
        super.onPause()
        testView.pause()
    }
}

class LatencyTestView(activity: Activity) : View(activity) {

    /** 单个移动靶（纵向泳道由索引决定，见 laneCenter） */
    private class Target(val color: Int) {
        var x = 0f
        var dir = 1f
        var flashUntilMs = 0L // 命中闪光截止时刻
    }

    private val targets: List<Target> = listOf(
        Target(Color.rgb(239, 83, 80)),   // 红
        Target(Color.rgb(67, 160, 71)),   // 绿
        Target(Color.rgb(41, 121, 255)),  // 蓝
    )

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
    }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val laneBandPaint = Paint().apply { style = Paint.Style.FILL }
    private val laneLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }

    private var speedLevel = 2 // 1..5
    private var score = 0
    private var misses = 0
    private var streak = 0
    private var bestStreak = 0
    private var lastFeedback = ""
    private var lastFeedbackUntilMs = 0L
    private var lastFrameMs = 0L
    private var running = false
    private var inited = false

    private val frameCallback = object : Choreographer.FrameCallback {
        private var logs = 0
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val dtMs = if (lastFrameMs == 0L) 16L else (now - lastFrameMs).coerceIn(1L, 100L)
            lastFrameMs = now
            step(dtMs)
            invalidate()
            logs++
            if (logs <= 5 || logs % 120 == 0) {
                android.util.Log.d("cloudphone-touch", "latency-test doFrame#$logs w=$width x=${targets[0].x} v=${SPEEDS[speedLevel - 1]}")
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun resume() {
        if (running) return
        running = true
        lastFrameMs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun pause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun step(dtMs: Long) {
        val w = width.toFloat()
        if (!inited && w > 0) {
            // 错开起点，避免三靶同相
            for ((i, t) in targets.withIndex()) {
                t.x = w * (0.15f + 0.25f * i)
                t.dir = if (i % 2 == 0) 1f else -1f
            }
            inited = true
        }
        val v = SPEEDS[speedLevel - 1]
        for (t in targets) {
            t.x += t.dir * v * dtMs / 1000f
            if (t.x < radius) { t.x = radius; t.dir = 1f }
            if (t.x > w - radius) { t.x = w - radius; t.dir = -1f }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        android.util.Log.d("cloudphone-touch", "latency-test touch @(${event.x.toInt()},${event.y.toInt()})")
        val x = event.x
        val y = event.y
        val now = SystemClock.elapsedRealtime()
        val h = height.toFloat()
        val w = width.toFloat()

        // 底部角热区：速度调节（热区做大些，注入点击好命中）
        if (y > h - zoneSize) {
            if (x < zoneSize) { changeSpeed(-1, now); return true }
            if (x > w - zoneSize) { changeSpeed(+1, now); return true }
        }
        // HUD 速度行热区：点「速度档 x/5（…）」标签本身循环换挡（客户端工具坞会遮住
        // 源端屏幕底部，底部角热区经串流客户端点不到——标签在顶部，任何端都够得着）
        if (y in 85f..185f && x in w / 2 - 350f..w / 2 + 350f) {
            val nv = if (speedLevel >= SPEEDS.size) 1 else speedLevel + 1
            speedLevel = nv
            lastFeedback = "速度档 $nv（命中窗 ${windowMs(nv)}ms）"
            lastFeedbackUntilMs = now + 1200
            return true
        }

        // 靶命中判定：圆心距离 ≤ r + 容差（注入点击为精确落点，容差给视觉瞄准误差）
        for (t in targets) {
            val cy = laneCenter(targets.indexOf(t), h)
            if (hypot(x - t.x, y - cy) <= radius + 18f) {
                score++
                streak++
                if (streak > bestStreak) bestStreak = streak
                t.flashUntilMs = now + 350
                lastFeedback = "命中 +1  连击 $streak"
                lastFeedbackUntilMs = now + 900
                android.util.Log.i("cloudphone-touch", "latency-test HIT score=$score streak=$streak @(${x.toInt()},${y.toInt()})")
                respawn(t, w)
                return true
            }
        }
        // 点空
        misses++
        streak = 0
        lastFeedback = "未中（连击断）"
        lastFeedbackUntilMs = now + 900
        return true
    }

    private fun changeSpeed(delta: Int, now: Long) {
        val nv = (speedLevel + delta).coerceIn(1, SPEEDS.size)
        if (nv != speedLevel) {
            speedLevel = nv
            lastFeedback = "速度档 $nv（命中窗 ${windowMs(nv)}ms）"
            lastFeedbackUntilMs = now + 1200
        }
    }

    private fun respawn(t: Target, w: Float) {
        t.dir = if (Random.nextBoolean()) 1f else -1f
        t.x = if (t.dir > 0) radius else w - radius
    }

    private val radius get() = 60f
    private val zoneSize get() = 180f

    private fun laneCenter(index: Int, h: Float): Float = when (index) {
        0 -> h * 0.30f
        1 -> h * 0.52f
        else -> h * 0.74f
    }

    private fun windowMs(level: Int): Int = (2 * radius / (SPEEDS[level - 1] / 1000f)).toInt()

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val h = height.toFloat()
        val w = width.toFloat()
        canvas.drawColor(Color.rgb(12, 14, 20))

        // 顶部 HUD
        textPaint.textSize = 52f
        canvas.drawText("得分 $score   连击 $streak（最佳 $bestStreak）   未中 $misses", 32f, 90f, textPaint)
        textPaint.textSize = 44f
        val total = score + misses
        val rate = if (total == 0) 100 else score * 100 / total
        canvas.drawText(
            "速度档 $speedLevel/${SPEEDS.size}（${SPEEDS[speedLevel - 1]}px/s，命中窗 ${windowMs(speedLevel)}ms）   命中率 $rate%",
            32f, 160f, textPaint,
        )
        textPaint.color = Color.rgb(160, 168, 184)
        textPaint.textSize = 38f
        canvas.drawText("点中移动圆点得分；速度越快，要求链路延迟越小", 32f, 224f, textPaint)
        textPaint.color = Color.WHITE

        // 泳道导轨：纵向对准基准（命中要求 y 落在泳道中心 ±radius 内）
        for (i in targets.indices) {
            val cy = laneCenter(i, h)
            laneBandPaint.color = Color.argb(26, 255, 255, 255)
            canvas.drawRect(0f, cy - radius, w, cy + radius, laneBandPaint)
            laneLinePaint.color = Color.argb(70, 255, 255, 255)
            canvas.drawLine(0f, cy, w, cy, laneLinePaint)
        }

        // 靶
        for ((i, t) in targets.withIndex()) {
            val cy = laneCenter(i, h)
            circlePaint.color = t.color
            canvas.drawCircle(t.x, cy, radius, circlePaint)
            if (now < t.flashUntilMs) {
                ringPaint.color = Color.WHITE
                ringPaint.strokeWidth = 14f
                canvas.drawCircle(t.x, cy, radius + 22f, ringPaint)
            }
            ringPaint.color = Color.argb(140, 255, 255, 255)
            ringPaint.strokeWidth = 3f
            canvas.drawLine(t.x - radius, cy, t.x + radius, cy, ringPaint) // 中线准星
        }

        // 速度热区
        zonePaint.color = Color.argb(70, 255, 255, 255)
        canvas.drawRect(0f, h - zoneSize, zoneSize, h, zonePaint)
        canvas.drawRect(w - zoneSize, h - zoneSize, w, h, zonePaint)
        textPaint.textSize = 60f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("－", zoneSize / 2, h - zoneSize / 2 + 20f, textPaint)
        canvas.drawText("＋", w - zoneSize / 2, h - zoneSize / 2 + 20f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT

        // 反馈
        if (now < lastFeedbackUntilMs) {
            textPaint.textSize = 64f
            textPaint.color = if (lastFeedback.startsWith("命中")) Color.rgb(120, 230, 140) else Color.rgb(240, 120, 120)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(lastFeedback, w / 2, h * 0.44f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = Color.WHITE
        }
    }

    companion object {
        /** 速度档 px/s：档位越高，命中时间窗（直径/速度）越窄，对链路延迟要求越高 */
        val SPEEDS = intArrayOf(300, 700, 1400, 2400, 4000)
    }
}
