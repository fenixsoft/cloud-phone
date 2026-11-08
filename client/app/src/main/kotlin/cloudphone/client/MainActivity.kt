package cloudphone.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import cloudphone.protocol.ControlMessage

/**
 * 客户端入口（4.3）：连接入口 + 实时状态总览（STATS 视图常驻，连接后自动喂数）。
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var statsView: StatsView
    private val handler = Handler(Looper.getMainLooper())

    private val statsTicker = object : Runnable {
        override fun run() {
            statusText.text = when {
                ClientSession.connected ->
                    "已连接（会话 #${ClientSession.currentSessionId}）"
                ClientSession.lastError.isNotBlank() -> "未连接 — ${ClientSession.lastError}"
                else -> "未连接"
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply { text = "CloudPhone 客户端（M0）" })
        statusText = TextView(this)
        root.addView(statusText)

        // STATS 悬浮层（连接后喂数；此处作为总览）
        statsView = StatsView(this)
        root.addView(
            statsView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (120 * resources.displayMetrics.density).toInt()),
        )

        root.addView(Button(this).apply {
            text = "连接源端…"
            setOnClickListener { startActivity(Intent(this@MainActivity, ConnectActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "会话抽屉…"
            setOnClickListener { startActivity(Intent(this@MainActivity, SessionDrawerActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "PING（测 RTT）"
            setOnClickListener {
                ClientSession.sendControl(
                    ControlMessage.Ping(nonce = System.currentTimeMillis(), senderUs = System.nanoTime() / 1000),
                )
            }
        })

        setContentView(root)
        ClientSession.onStats = { stats -> handler.post { statsView.update(stats, null) } }
        handler.post(statsTicker)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(statsTicker)
    }
}
