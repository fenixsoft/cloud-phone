package cloudphone.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import cloudphone.protocol.ControlMessage
import cloudphone.protocol.TransportKind

/**
 * 实时状态视图（4.5 / client-ui-redesign 3.1）：
 * 深色 HUD 统计条样式——半透明面板 + 左缘强调色条，数据读出 mono 字体（视觉语言：
 * mono 仅用于数据读出）。FPS/码率/RTT/丢包沿用既有 STATS 上报（≥1Hz），
 * 分辨率读数取解码输出 MediaFormat 实际值（STATS 消息无分辨率字段，由宿主 Activity
 * 经 [setResolution] 喂入），端到端延迟滚动窗口（P50/P95）照旧。
 * 纯绘制组件，由宿主 Activity 喂数（onStats 回调 → [update]）。
 */
class StatsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val panelPaint = Paint().apply { color = 0xB306060A.toInt() }
    private val accentPaint = Paint().apply { color = 0xFF3B82F6.toInt() }
    private val dataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF8FAFC.toInt()
        textSize = 12f * density
        typeface = Typeface.MONOSPACE
    }
    private val ctxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A3B8.toInt()
        textSize = 10f * density
        typeface = Typeface.MONOSPACE
    }

    @Volatile private var resolutionText: String = "—"
    @Volatile private var dataLine: String = "FPS —  码率 —  RTT —  丢包 —  分辨率 —"
    @Volatile private var ctxLine: String = "等待 STATS…"

    /** 解码输出实际分辨率（INFO_OUTPUT_FORMAT_CHANGED 后宿主喂入） */
    fun setResolution(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            resolutionText = "$width×$height"
            dataLine = rebuildDataLine()
            postInvalidate()
        }
    }

    private fun rebuildDataLine(): String {
        // 保留最近一次 STATS 数值重建含分辨率的读出行
        return dataLineFor(lastStats, lastClientDelay)
    }

    private var lastStats: ControlMessage.StatsMessage? = null
    private var lastClientDelay: Pair<Long, Long>? = null

    private fun dataLineFor(stats: ControlMessage.StatsMessage?, clientDelay: Pair<Long, Long>?): String =
        buildString {
            append("FPS ")
            append(if (stats != null) "%.1f".format(stats.fps) else "—")
            append("  码率 ")
            append(if (stats != null) "%.0f".format(stats.bitrateKbps) else "—")
            append("kbps  RTT ")
            append(if (stats != null) "%.1f".format(stats.rttMs) else "—")
            append("ms  丢包 ")
            append(if (stats != null) "%.2f".format(stats.lossPct) else "—")
            append("%  ")
            append(resolutionText)
            if (clientDelay != null) {
                append("  P50/P95 ")
                append(clientDelay.first)
                append("/")
                append(clientDelay.second)
                append("ms")
            }
        }

    /** 喂最新统计（源端 STATS 消息 + 客户端延迟窗口） */
    fun update(stats: ControlMessage.StatsMessage?, clientDelay: Pair<Long, Long>?) {
        lastStats = stats
        lastClientDelay = clientDelay
        val transport = when (stats?.transport) {
            TransportKind.LAN_DIRECT -> "lan-direct"
            TransportKind.P2P -> "p2p"
            TransportKind.P2P_FALLBACK_FRP -> "p2p→frp"
            TransportKind.FRP -> "frp"
            null -> "?"
        }
        val crypto = when (ClientSession.negotiatedCryptoAlgo) {
            cloudphone.transport.CryptoAlgo.AES128_GCM -> "AES-128-GCM"
            else -> "未加密"
        }
        dataLine = dataLineFor(stats, clientDelay)
        ctxLine = buildString {
            append(transport)
            append(" · ")
            append(crypto)
            if (stats != null) append(" · 会话 #").append(stats.sessionId)
            append("  ")
            append(resolutionText)
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        // 半透明面板 + 左缘强调条
        canvas.drawRect(0f, 0f, w, h, panelPaint)
        canvas.drawRect(0f, 0f, 2f * density, h, accentPaint)
        val pad = 10f * density
        var y = dataPaint.textSize + 4f * density
        canvas.drawText(dataLine, pad, y, dataPaint)
        y += ctxPaint.textSize + 4f * density
        if (y < h) canvas.drawText(ctxLine, pad, y, ctxPaint)
    }
}
