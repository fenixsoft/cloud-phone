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
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.TextView
import android.widget.Toast
import cloudphone.protocol.ControlMessage
import cloudphone.protocol.HelloStatus
import cloudphone.protocol.WatchMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 源端应用列表页（client-ux-and-input-latency 4.1 / client-ui-redesign 4.1 磁贴化）：
 * - GridView 磁贴（占位图标 + 名称白色文字，无包名行，零新依赖平台组件）；
 * - 顶部搜索框按名称实时过滤（适配器层 filter）；
 * - 每磁贴右上角图钉 = 既有 app-shortcuts 创建桌面快捷方式（requestPinShortcut，
 *   Launcher 无钉选能力 Toast 降级，列表仍可用）；
 * - 点选磁贴 = SINGLE_APP StartSession 直进串流（源端为单应用不可用环境时展示
 *   既有明确拒绝文案）；
 * - AppListRequest 3s 无应答展示失败原因与重试入口（既有规则不变）。
 */
class SourceAppsActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var retryButton: TextView
    private lateinit var gridView: GridView
    private lateinit var searchInput: android.widget.EditText

    private var allApps: List<cloudphone.protocol.AppEntry> = emptyList()
    private var visibleApps: List<cloudphone.protocol.AppEntry> = emptyList()
    private var loadSeq = 0
    private val adapter = TileAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_source_apps)
        statusText = findViewById(R.id.apps_status)
        retryButton = findViewById(R.id.btn_apps_retry)
        gridView = findViewById(R.id.apps_grid)
        searchInput = findViewById(R.id.apps_search)

        gridView.adapter = adapter
        findViewById<TextView>(R.id.btn_apps_back).setOnClickListener { finish() }
        retryButton.setOnClickListener { loadApps() }
        // 搜索框：按应用名称实时过滤（spec「输入『原神』时列表实时过滤只剩匹配磁贴」）
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                applyFilter()
            }
        })
        loadApps()
    }

    override fun onDestroy() {
        super.onDestroy()
        ClientSession.onAppList = null
    }

    /** 按名称过滤（大小写不敏感，仅 label；包名不展示也不参与过滤——spec 磁贴无包名行） */
    private fun applyFilter() {
        val q = searchInput.text.toString().trim()
        visibleApps = if (q.isEmpty()) allApps else allApps.filter { it.label.contains(q, ignoreCase = true) }
        adapter.notifyDataSetChanged()
        if (allApps.isNotEmpty()) {
            statusText.text = "源端可启动应用 ${allApps.size} 个" +
                (if (q.isEmpty()) "" else "，匹配 ${visibleApps.size} 个")
        }
    }

    /** 发送 AppListRequest 并等待应答（3s 超时判定失败）；网络 I/O 在工作线程 */
    private fun loadApps() {
        val seq = ++loadSeq
        statusText.text = "加载中…"
        retryButton.visibility = View.GONE
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
                allApps = apps
                applyFilter()
            }
        }, "apps-load").apply { isDaemon = true; start() }
    }

    private fun showFailure(reason: String) {
        statusText.text = "列表获取失败：$reason"
        retryButton.visibility = View.VISIBLE
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
                // 单应用模式进入控制台：徽章用应用名（4.2）
                startActivity(
                    Intent(this, DecodeRenderActivity::class.java)
                        .putExtra("mode", "single")
                        .putExtra("appName", app.label)
                        .putExtra("pkg", app.packageName),
                )
            }
        }, "apps-open").apply { isDaemon = true; start() }
    }

    /**
     * 图钉 =「加到桌面」：requestPinShortcut（既有 app-shortcuts 能力，零改动复用）；
     * 快捷方式 intent = cloudphone://launch deeplink（连接参数 + 目标包名）；
     * Launcher 无钉选能力 → Toast 降级，列表仍可用。
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

    /** 磁贴适配器：占位图标 + 名称白色文字（无包名行），右上角图钉加桌面 */
    private inner class TileAdapter : BaseAdapter() {
        override fun getCount(): Int = visibleApps.size
        override fun getItem(position: Int): Any = visibleApps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_app_tile, parent, false)
            val app = visibleApps[position]
            view.findViewById<TextView>(R.id.tile_name).text = app.label
            view.findViewById<TextView>(R.id.tile_pin).setOnClickListener { pinShortcut(app) }
            view.setOnClickListener { openApp(app) }
            return view
        }
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
