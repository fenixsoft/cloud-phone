package cloudphone.client

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import cloudphone.protocol.ControlMessage
import cloudphone.protocol.HelloStatus
import cloudphone.protocol.WatchMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 源端应用列表页（client-ux-and-input-latency 4.1）：
 * - 会话在线时发送 AppListRequest，3s 无应答展示失败原因与重试入口；
 * - 每项两个操作：「打开」= SINGLE_APP StartSession 直进串流（源端为单应用不可用
 *   环境时展示既有明确拒绝文案）；「加到桌面」= ShortcutManager.requestPinShortcut
 *   （id=launch-<host>-<port>-<pkg哈希>），钉选被拒/不支持 Toast 降级，列表仍可用。
 */
class SourceAppsActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var listHost: LinearLayout
    private lateinit var retryButton: Button
    private var loadSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply { text = "源端应用（打开 / 加到桌面）" })
        statusText = TextView(this).apply { text = "加载中…" }
        root.addView(statusText)
        retryButton = Button(this).apply {
            text = "重试"
            setOnClickListener { loadApps() }
            visibility = android.view.View.GONE
        }
        root.addView(retryButton)
        val sv = android.widget.ScrollView(this)
        listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(listHost)
        root.addView(
            sv,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT, 1f,
            ),
        )
        setContentView(root)
        loadApps()
    }

    override fun onDestroy() {
        super.onDestroy()
        ClientSession.onAppList = null
    }

    /** 发送 AppListRequest 并等待应答（3s 超时判定失败）；网络 I/O 在工作线程 */
    private fun loadApps() {
        val seq = ++loadSeq
        statusText.text = "加载中…"
        retryButton.visibility = android.view.View.GONE
        listHost.removeAllViews()
        Thread({
            if (!ClientSession.connected) {
                runOnUiThread { showFailure("无在线会话（先连接源端）") }
                return@Thread
            }
            val latch = CountDownLatch(1)
            var received: ControlMessage.AppListMessage? = null
            ClientSession.onAppList = { msg ->
                received = msg
                latch.countDown()
            }
            val requestId = (SystemClock.elapsedRealtime() and 0x7FFFFFFF).toInt()
            ClientSession.sendControl(ControlMessage.AppListRequest(requestId))
            val got = latch.await(3, TimeUnit.SECONDS)
            val apps = received?.apps
            ClientSession.onAppList = null
            runOnUiThread {
                if (seq != loadSeq) return@runOnUiThread // 过期应答（用户已重试）
                if (!got || apps == null) {
                    showFailure("3s 无应答（会话可能已断开）")
                    return@runOnUiThread
                }
                statusText.text = "源端可启动应用 ${apps.size} 个"
                renderApps(apps)
            }
        }, "apps-load").apply { isDaemon = true; start() }
    }

    private fun showFailure(reason: String) {
        statusText.text = "列表获取失败：$reason"
        retryButton.visibility = android.view.View.VISIBLE
    }

    private fun renderApps(apps: List<cloudphone.protocol.AppEntry>) {
        listHost.removeAllViews()
        val pad = (8 * resources.displayMetrics.density).toInt()
        for (app in apps) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                TextView(this).apply {
                    text = "${app.label}\n${app.packageName}"
                    setPadding(pad, pad, pad, pad)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(Button(this).apply {
                text = "打开"
                setOnClickListener { openApp(app) }
            })
            row.addView(Button(this).apply {
                text = "加到桌面"
                setOnClickListener { pinShortcut(app) }
            })
            listHost.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    /** 「打开」：SINGLE_APP StartSession 直进串流；被拒展示源端既有拒绝文案 */
    private fun openApp(app: cloudphone.protocol.AppEntry) {
        Toast.makeText(this, "正在打开 ${app.label}（单应用）…", Toast.LENGTH_SHORT).show()
        Thread({
            val ack = ClientSession.startSession(
                WatchMode.SINGLE_APP, app.packageName, 0, 0, 0, 0, 0,
            )
            runOnUiThread {
                if (ack == null || ack.status != HelloStatus.ACCEPTED) {
                    Toast.makeText(
                        this,
                        "打开失败：${ack?.reason ?: ClientSession.lastError}",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@runOnUiThread
                }
                startActivity(Intent(this, DecodeRenderActivity::class.java))
            }
        }, "apps-open").apply { isDaemon = true; start() }
    }

    /**
     * 「加到桌面」：requestPinShortcut；Launcher 无钉选能力 → Toast「当前桌面不支持创建快捷方式」。
     * 快捷方式 intent = cloudphone://launch deeplink（连接参数 + 目标包名，见 4.2）。
     */
    private fun pinShortcut(app: cloudphone.protocol.AppEntry) {
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            Toast.makeText(this, "当前桌面不支持创建快捷方式", Toast.LENGTH_LONG).show()
            return
        }
        val cfg = ClientSession.currentConfig()
        if (cfg == null) {
            Toast.makeText(this, "无当前连接参数（先连接源端）", Toast.LENGTH_LONG).show()
            return
        }
        val uri = Uri.Builder()
            .scheme("cloudphone").authority("launch")
            .appendQueryParameter("host", cfg.host)
            .appendQueryParameter("tcpPort", cfg.tcpPort.toString())
            .appendQueryParameter("udpPort", cfg.udpPort.toString())
            .appendQueryParameter("backend", cfg.kind.name.lowercase().replace('_', '-'))
            .appendQueryParameter("token", cfg.token)
            .appendQueryParameter("pkg", app.packageName)
            .build()
        val intent = Intent(Intent.ACTION_VIEW, uri, this, ConnectActivity::class.java)
        val id = "launch-${cfg.host}-${cfg.tcpPort}-${pkgHash(app.packageName)}"
        val shortcut = android.content.pm.ShortcutInfo.Builder(this, id)
            .setShortLabel(app.label.take(10))
            .setLongLabel(app.label)
            .setIcon(Icon.createWithResource(this, android.R.mipmap.sym_def_app_icon))
            .setIntent(intent)
            .build()
        val callback = PendingIntent.getBroadcast(
            this, id.hashCode(),
            Intent(this, PinShortcutResultReceiver::class.java).putExtra(PIN_EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val ok = runCatching { sm.requestPinShortcut(shortcut, callback.intentSender) }
        if (ok.isSuccess) {
            Toast.makeText(this, "已请求钉选「${app.label}」，请在桌面确认", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "当前桌面不支持创建快捷方式", Toast.LENGTH_LONG).show()
        }
    }

    private fun pkgHash(pkg: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(pkg.toByteArray())
        return bytes.take(4).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PIN_EXTRA_ID = "cloudphone.client.PIN_SHORTCUT_ID"
    }
}

/**
 * 钉选结果广播接收器：Launcher 拒绝钉选（无 EXTRA_PINNED_SHORTCUT 回传）时 Toast 降级提示，
 * 应用列表不受影响（spec app-shortcuts「Launcher 不支持钉选的降级」）。
 */
class PinShortcutResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 平台 jar 未暴露该常量；字面值与 androidx ShortcutManagerCompat.EXTRA_PINNED_SHORTCUT 一致
        val pinned = intent.getStringExtra(EXTRA_PINNED_SHORTCUT)
        val msg = if (pinned != null) "快捷方式已创建" else "当前桌面不支持创建快捷方式（钉选被拒）"
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_PINNED_SHORTCUT = "android.intent.extra.shortcut.PINNED_SHORTCUT"
    }
}
