package cloudphone.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import cloudphone.protocol.ControlMessage
import cloudphone.protocol.HelloStatus
import cloudphone.protocol.WatchMode

/**
 * 会话抽屉（4.4）：列出源端在跑会话与挂机会话（HELLO_ACK.hungSessions），
 * 支持：attach 恢复挂机（≤1s）、停止销毁、新开单应用会话（≥2 并存入口）、
 * 断开当前（回到源端挂机）。
 */
class SessionDrawerActivity : Activity() {

    private lateinit var listText: TextView
    private lateinit var packageInput: EditText
    private lateinit var refreshButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        root.addView(TextView(this).apply { text = "会话抽屉（源端会话与挂机列表）" })
        listText = TextView(this)
        root.addView(listText)

        refreshButton = Button(this).apply {
            text = "刷新（重新握手查询）"
            setOnClickListener { refresh() }
        }
        root.addView(refreshButton)

        root.addView(TextView(this).apply { text = "新开单应用会话（包名）" })
        packageInput = EditText(this).apply { setText("cloudphone.loadgen") }
        root.addView(packageInput)
        root.addView(Button(this).apply {
            text = "新开并观看"
            setOnClickListener { startNewSingleApp() }
        })

        root.addView(Button(this).apply {
            text = "源端应用（打开/加到桌面）"
            setOnClickListener { startActivity(Intent(this@SessionDrawerActivity, SourceAppsActivity::class.java)) }
        })

        root.addView(Button(this).apply {
            text = "断开当前观看（源端挂机）"
            setOnClickListener { detachCurrent() }
        })

        setContentView(root)
        refresh()
    }

    /** 重新握手拉取挂机列表（HELLO → HELLO_ACK.hungSessions） */
    private fun refresh() {
        Thread({
            val ok = ClientSession.handshake(WatchMode.ATTACH, "")
            val ack = ClientSession.lastHelloAck
            runOnUiThread {
                if (!ok || ack == null) {
                    listText.text = "查询失败：${ClientSession.lastError}"
                    return@runOnUiThread
                }
                val sb = StringBuilder()
                sb.append("当前观看 sessionId=${ClientSession.currentSessionId}\n\n")
                val hung = ack.hungSessions
                if (hung.isEmpty()) {
                    sb.append("（无挂机会话）\n")
                } else {
                    hung.forEach { s ->
                        sb.append("挂机 #${s.sessionId} [${s.mode}] ${s.packageName} ${s.width}x${s.height}\n")
                    }
                }
                listText.text = sb.toString()
            }
        }, "drawer-refresh").apply { start() }
    }

    /** attach 恢复挂机会话并进入渲染 */
    private fun attach(sessionId: Int) {
        Thread({
            val ack = ClientSession.startSession(
                WatchMode.ATTACH, "", sessionId, 0, 0, 0, 0,
            )
            runOnUiThread {
                if (ack == null || ack.status != HelloStatus.ACCEPTED) {
                    Toast.makeText(this, "恢复失败：${ack?.reason ?: ClientSession.lastError}", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                startActivity(Intent(this, DecodeRenderActivity::class.java))
            }
        }, "drawer-attach").apply { start() }
    }

    private fun startNewSingleApp() {
        val pkg = packageInput.text.toString()
        Thread({
            val ack = ClientSession.startSession(WatchMode.SINGLE_APP, pkg, 0, 0, 0, 0, 0)
            runOnUiThread {
                if (ack == null || ack.status != HelloStatus.ACCEPTED) {
                    Toast.makeText(this, "新开会话失败：${ack?.reason ?: ClientSession.lastError}", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                startActivity(Intent(this, DecodeRenderActivity::class.java))
            }
        }, "drawer-new").apply { start() }
    }

    /** 断开当前：STOP 语义由用户选择；此处"断开"= 停止源端编码（挂机），发送 STOP_SESSION 前先确认 → M0 直接挂机不销毁 */
    private fun detachCurrent() {
        val id = ClientSession.currentSessionId
        if (id == 0) {
            Toast.makeText(this, "当前无观看会话", Toast.LENGTH_SHORT).show()
            return
        }
        // 挂机 = 客户端停止观看（源端 onStreamEvent DISCONNECTED → hangup）；
        // 显式发 STOP_SESSION 才销毁。M0 断开按钮 = 断链（触发源端挂机）。
        ClientSession.close()
        Toast.makeText(this, "已断开（源端将挂机 #$id）", Toast.LENGTH_SHORT).show()
        refresh()
    }

    /** 停止并销毁会话（STOP_SESSION 语义） */
    private fun destroy(sessionId: Int) {
        ClientSession.stopSession(sessionId)
        Toast.makeText(this, "已请求销毁 #$sessionId", Toast.LENGTH_SHORT).show()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }
}
