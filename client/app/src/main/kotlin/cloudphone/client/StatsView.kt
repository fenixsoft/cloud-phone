package cloudphone.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import cloudphone.protocol.ControlMessage
import cloudphone.protocol.TransportKind

/**
 * 实时状态视图（4.5）：STATS（≥1Hz）+ 客户端解码延迟滚动窗口（P50/P95）。
 * 纯绘制组件，由宿主 Activity 喂数（onStats 回调 → [update]）。
 */
class StatsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14 * resources.displayMetrics.density
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 0, 0)
        textSize = 14 * resources.displayMetrics.density
    }

    @Volatile private var lines: List<String> = listOf("STATS 等待中…")

    /** 喂最新统计（源端 STATS 消息 + 客户端延迟窗口） */
    fun update(stats: ControlMessage.StatsMessage?, clientDelay: Pair<Long, Long>?) {
        val transport = when (stats?.transport) {
            TransportKind.LAN_DIRECT -> "lan-direct"
            TransportKind.P2P -> "p2p(直连)"
            TransportKind.P2P_FALLBACK_FRP -> "p2p→frp(回落)"
            TransportKind.FRP -> "frp"
            null -> "?"
        }
        val newLines = buildList {
            add("传输: $transport")
            // 加密协商结果（p2p-crossnet-traversal 3.2）：读 ClientSession 会话态
            add(
                when (ClientSession.negotiatedCryptoAlgo) {
                    cloudphone.transport.CryptoAlgo.AES128_GCM -> "加密: AES-128-GCM"
                    else -> "未加密（对端不支持）"
                },
            )
            if (stats != null) {
                add("帧率: ${"%.1f".format(stats.fps)} fps")
                add("码率: ${"%.0f".format(stats.bitrateKbps)} kbps")
                add("RTT: ${"%.1f".format(stats.rttMs)} ms")
                add("丢包: ${"%.2f".format(stats.lossPct)} %")
                add("会话: #${stats.sessionId} (seq=${stats.seq})")
            }
            if (clientDelay != null) {
                add("端到端延迟 P50/P95: ${clientDelay.first}/${clientDelay.second} ms")
            }
        }
        lines = newLines
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        var y = paint.textSize
        for (line in lines) {
            canvas.drawText(line, 6f, y + 2f, shadow)
            canvas.drawText(line, 5f, y + 1f, paint)
            y += paint.textSize * 1.4f
        }
    }
}
