package cloudphone.source

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import cloudphone.source.ime.ImeBridge
import cloudphone.source.obs.PipelineCounters
import cloudphone.source.shell.ShellBootstrap

/**
 * 源端入口（运维 UI，M0 最小形态）：
 * - shell bootstrap 状态检测与降级原因展示（3.1）；
 * - MediaProjection 授权发起 + 前台服务启停（会话配置：后端/token/端口/分辨率）；
 * - 管线计数与坏事件实时展示（3.4 可观测性出口）。
 *
 * 交互契约：以下控件均为真实可交互，行为在本类实现（无纯展示假按钮）。
 */
class MainActivity : Activity() {

    private lateinit var backendSpinner: Spinner
    private lateinit var tokenInput: EditText
    private lateinit var tcpPortInput: EditText
    private lateinit var udpPortInput: EditText
    private lateinit var useUdpCheck: android.widget.CheckBox
    private lateinit var statusText: TextView
    private lateinit var countersText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var projectionButton: Button
    private lateinit var imeButton: Button
    private lateinit var overlayButton: Button

    /** 投影失效自动重授权路径标记：授权确认后直接重建采集管线（手动授权不置位） */
    private var pendingRebuildAfterGrant = false

    private val bootstrap = ShellBootstrap()
    private val handler = Handler(Looper.getMainLooper())

    private val countersTicker = object : Runnable {
        override fun run() {
            countersText.text =
                "管线计数 ${PipelineCounters.summaryLine()}\n" +
                "坏事件 ${PipelineCounters.snapshot().filterKeys { it.startsWith("bad_") }}"
            if (isFinishing.not()) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        absorbAutoRecover(intent)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        root.addView(TextView(this).apply { text = "CloudPhone 源端（M0）" })

        root.addView(TextView(this).apply { text = "传输后端" })
        backendSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("lan-direct", "p2p", "frp"),
            )
        }
        root.addView(backendSpinner)

        root.addView(TextView(this).apply { text = "配对 token" })
        tokenInput = EditText(this)
        root.addView(tokenInput)

        root.addView(TextView(this).apply { text = "TCP 端口（lan/frp/p2p 信令）" })
        tcpPortInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("22333")
        }
        root.addView(tcpPortInput)

        root.addView(TextView(this).apply { text = "UDP 端口（视频通道）" })
        udpPortInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("22334")
        }
        root.addView(udpPortInput)

        useUdpCheck = android.widget.CheckBox(this).apply {
            text = "启用 UDP 视频通道（失败自动降级 TCP；lan 直连默认关闭：共享端口易被宿主侧流量污染远端学习）"
            isChecked = false
        }
        root.addView(useUdpCheck)

        projectionButton = Button(this).apply {
            text = "授予 MediaProjection 授权"
            setOnClickListener { requestProjection() }
        }
        root.addView(projectionButton)

        startButton = Button(this).apply {
            text = "启动串流服务"
            setOnClickListener { startService() }
        }
        root.addView(startButton)

        stopButton = Button(this).apply {
            text = "停止串流服务"
            setOnClickListener { stopService() }
        }
        root.addView(stopButton)

        // M3 aux-channels-m3 任务 3.4：云剪贴板输入法启用引导（无 adb 环境的兜底入口）。
        // 检测 IME 未启用时显示；跳系统输入法设置（ACTION_INPUT_METHOD_SETTINGS）
        imeButton = Button(this)
        imeButton.setOnClickListener {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开输入法设置: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        root.addView(imeButton)

        // 悬浮窗权限引导（触摸延迟修复 5.3）：InputComposer 悬窗合成驱动需
        // SYSTEM_ALERT_WINDOW；未授权时降级为系统 idle 合成（触摸→画面 ~1s）。
        overlayButton = Button(this).apply {
            setOnClickListener {
                try {
                    val intent = Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName"),
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "无法打开悬浮窗设置: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(overlayButton)

        // 延迟测试（移动靶）：全屏移动靶 + 速度 5 档，经串流点击命中即体感延迟量尺
        root.addView(Button(this).apply {
            text = "延迟测试（移动靶）→"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, LatencyTestActivity::class.java))
            }
        })

        statusText = TextView(this)
        root.addView(statusText)
        countersText = TextView(this)
        root.addView(countersText)

        setContentView(root)
        refreshShellStatus()
        handler.post(countersTicker)
    }

    /** shell bootstrap 检测：不可用时展示明确降级原因（单应用模式不可用，整屏模式可用） */
    private fun refreshShellStatus() {
        Thread {
            val result = bootstrap.detect()
            handler.post {
                statusText.text = when (result.status) {
                    ShellBootstrap.Status.AVAILABLE ->
                        "shell 助手: 就绪 v${result.version}（单应用虚拟屏/触摸注入可用）"
                    ShellBootstrap.Status.UNAVAILABLE ->
                        "shell 助手: 不可用 — ${result.reason}\n（降级：仅整屏 MediaProjection 模式，无输入注入）"
                }
            }
        }.start()
    }

    /**
     * 云剪贴板输入法状态检测（M3 任务 3.4）：未启用时显示启用入口按钮，
     * 已启用隐藏（剪贴板/中文输入通道可用）。onResume 复检（从系统设置返回即刷新）。
     */
    private fun refreshImeStatus() {
        Thread {
            val enabled = runCatching { ImeBridge.isEnabled(this) }.getOrDefault(false)
            handler.post {
                if (isFinishing) return@post
                imeButton.visibility = if (enabled) View.GONE else View.VISIBLE
                imeButton.text = "启用云剪贴板输入法（剪贴板/中文输入必需）→"
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        refreshImeStatus()
        refreshOverlayStatus()
        maybeAutoReproject()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // SINGLE_TOP 复用栈顶实例（自愈看门狗重复拉起时不再叠实例）：吸收 extra 后同样走自动重授权
        absorbAutoRecover(intent)
        maybeAutoReproject()
    }

    /** 自愈/开机自启路径：携带 AUTO_RECOVER extra → 置 captureLost，onResume/onNewIntent 消费即弹授权框 */
    private fun absorbAutoRecover(intent: Intent?) {
        if (intent?.getBooleanExtra(StreamService.EXTRA_AUTO_RECOVER, false) == true) {
            StreamService.captureLost = true
        }
    }

    /** captureLost 消费口：弹系统授权框（autoRebuild=true → 授权后立即重建管线） */
    private fun maybeAutoReproject() {
        if (StreamService.captureLost) {
            StreamService.captureLost = false
            Toast.makeText(this, "投屏授权已失效，请重新授权以恢复串流", Toast.LENGTH_LONG).show()
            requestProjection(autoRebuild = true)
        }
    }

    /** 悬浮窗权限检测（触摸延迟修复 5.3/5.5：InputComposer 悬窗需 SYSTEM_ALERT_WINDOW，
     *  未授权降级为系统 idle 合成节奏 → 触摸→画面变化约 1s。给出系统设置引导入口。） */
    private fun refreshOverlayStatus() {
        val granted = android.provider.Settings.canDrawOverlays(this)
        overlayButton.visibility = if (granted) View.GONE else View.VISIBLE
        overlayButton.text = "开启悬浮窗权限（触摸延迟 1s → 0.02s）→"
    }

    private fun requestProjection(autoRebuild: Boolean = false) {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
            // dump 开关用到 /sdcard（M0 诊断用；非必需权限，拒绝可继续）
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
        }
        // 顺序修订（2026-11-12 reboot 实测）：授权框必须先行。API 34 上先起 mediaProjection 型
        // FGS 时，Service.onCreate→startForeground(MEDIA_PROJECTION) 需要 project_media appop
        // 处于 running（仅授权后成立）——全新进程/新开机时必崩（SecurityException，crash 缓冲
        // 实证 10:00:18），授权框成僵尸无人收 result。正确时序：onActivityResult（用户已授权）
        // → startForegroundService(ACTION_START_CAPTURE) → onCreate startForeground 此刻合法
        // → getMediaProjection，FGS 先于令牌获取的铁律仍由该链路天然满足。
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_PROJECTION)
        // 自动重授权标记：仅投影失效恢复路径（autoRebuild）在 onActivityResult 后直接带参
        // 重建管线；手动授权走原流程（只存凭据，由『启动串流服务』消费，避免抢消费凭据）
        pendingRebuildAfterGrant = autoRebuild
    }

    private fun startService() {
        val prefs = getSharedPreferences(StreamService.PREFS, MODE_PRIVATE)
        prefs.edit()
            .putString("backend", backendSpinner.selectedItem.toString())
            .putString("token", tokenInput.text.toString().ifBlank { StreamService.DEFAULT_TOKEN })
            .putInt("tcpPort", tcpPortInput.text.toString().toIntOrNull() ?: 22333)
            .putInt("udpPort", udpPortInput.text.toString().toIntOrNull() ?: 22334)
            .putBoolean("useUdpVideo", useUdpCheck.isChecked)
            .apply()
        val intent = Intent(this, StreamService::class.java)
            .setAction(StreamService.ACTION_START_CAPTURE) // 服务已起则顺带建默认参数采集管线
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "服务已启动（${backendSpinner.selectedItem}）", Toast.LENGTH_SHORT).show()
        refreshShellStatus()
    }

    private fun stopService() {
        stopService(Intent(this, StreamService::class.java))
        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PROJECTION && resultCode == RESULT_OK && data != null) {
            // 本镜像约束：一授权只能铸一个 MediaProjection 实例且闲置约 60s 失效。
            // 凭据落地即消费（capture-lifecycle）：两条路径都立即建默认参管线——
            // ①消灭凭据闲置失效窗口；②杜绝新鲜令牌滞留 projectionGrant：滞留令牌若被
            // 音频路径 acquireProjection 消费会铸出第二投影，系统按一投影一应用收回
            // 活跃视频投影 → 级联清闸（freezer 事故同款机制）。参数差异由 adaptTo
            // 零令牌切档消化（换编码器 surface / vd.setSurface+resize），无需新凭据。
            StreamService.projectionGrant = resultCode to data
            pendingRebuildAfterGrant = false
            StreamService.captureLost = false
            val intent = Intent(this, StreamService::class.java).setAction(StreamService.ACTION_START_CAPTURE)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            statusText.text = "授权已就绪，采集管线建立中…"
        } else if (requestCode == REQUEST_PROJECTION) {
            // 授权被取消（人为拒绝/弹窗异常消失）：自动恢复路径不能哑火——
            // 回灌 captureLost 触发自愈看门狗重试（无人值守源端：串流必须随时可用）
            if (pendingRebuildAfterGrant) {
                pendingRebuildAfterGrant = false
                StreamService.captureLost = true
                StreamService.instanceRef?.requestRecovery()
                statusText.text = "授权被取消，自愈看门狗将自动重试"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(countersTicker)
    }

    companion object {
        private const val REQUEST_PROJECTION = 1
    }
}
