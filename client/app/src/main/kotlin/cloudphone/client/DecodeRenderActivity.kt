package cloudphone.client

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import cloudphone.protocol.ControlMessage
import cloudphone.protocol.HelloStatus
import cloudphone.protocol.WatchMode
import cloudphone.protocol.encodeControlMessage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 全幅画面控制台（client-ui-redesign 3.1/3.2/3.3/4.2，design D4）：
 * - 画面全幅（既有解码渲染管线：硬解 + letterbox + latest-wins，逻辑不变）；
 * - 顶部 HUD 统计条（StatsView 深色 HUD 样式；FPS/码率/RTT/丢包沿用 STATS 上报，
 *   分辨率读数取解码输出 MediaFormat 实际值——STATS 无分辨率字段）；
 * - 底部工具坞（键盘/截屏/档位循环/旋转/单应用入口/断开，可收起留把手）；
 * - Android 虚拟导航键（BACK 经既有 INPUT_EVENT InputAction.BACK 注入；
 *   HOME/RECENTS 协议无对应消息——预留占位提示，不扩展协议）；
 * - 会话抽屉（SessionDrawerActivity 语义迁移为本 Activity 内自绘滑出面板：
 *   FrameLayout 顶层容器 + 平移手势，零新依赖；挂起/恢复/断开，会话生命周期
 *   调用保持既有接口不变）；
 * - 单应用模式徽章（「单应用 · <应用名>」+ 一键退出返回系统画面、连接保持）。
 */
class DecodeRenderActivity : Activity() {

    private lateinit var surfaceView: AspectFitSurfaceView
    private lateinit var statsView: StatsView
    private lateinit var rootLayout: FrameLayout
    private lateinit var dockContent: LinearLayout
    private lateinit var hudBarFrame: LinearLayout
    private lateinit var dockRootFrame: LinearLayout
    private lateinit var fullscreenFab: TextView
    private lateinit var fullscreenToggleBtn: TextView
    private var fullscreenMode = false
    private lateinit var tierButton: TextView
    private lateinit var suspendOverlay: LinearLayout
    private lateinit var suspendLabel: TextView
    private lateinit var singleAppBadge: LinearLayout
    private lateinit var singleAppBadgeText: TextView
    private lateinit var drawerScrim: View
    private lateinit var drawerPanel: LinearLayout
    private lateinit var hungListHost: LinearLayout
    private lateinit var suspendDrawerButton: TextView
    private lateinit var keyboardAnchor: EditText

    private var decoder: MediaCodec? = null
    private val running = AtomicBoolean(false)
    // 深度 8（≈133ms@60fps）：解码慢于收流时丢旧帧保延迟上界（spec P95≤200ms），保关键帧
    private val frameQueue = LinkedBlockingQueue<ClientSession.VideoFrame>(8)
    private val lastOutputAtMs = AtomicLong(0)
    private val connectedAtMs = AtomicLong(0)
    private val firstFrameRenderedMs = AtomicLong(-1)
    private var lastLostToastAtMs = 0L
    private val idrInFlight = AtomicBoolean(false)
    private var renderThread: Thread? = null
    private var watchThread: Thread? = null

    // ── M3 aux-channels-m3（任务 4.3）：会话控制条 ──
    private lateinit var aux: AuxChannelClient
    private lateinit var statusLine: android.widget.TextView
    private lateinit var btnKeyboard: android.widget.Button
    private lateinit var btnPaste: android.widget.Button
    private lateinit var btnCopy: android.widget.Button
    private lateinit var btnMute: android.widget.Button
    /** 降级原因（AuxNotice 驱动）：输入法未启用→键盘/粘贴/复制灰；无获焦输入框→键盘/复制灰 */
    private var imeReason: String? = null
    private var focusReason: String? = null
    private var audioReason: String? = null

    /** 延迟采样（PTS 差值，ms）——最近 100 帧滚动窗口，供 StatsView/日志导出 */
    val delaySamplesMs = ArrayDeque<Long>()

    private val handler = Handler(Looper.getMainLooper())

    // ── 控制台状态 ──
    private var suspended = false
    private var suspendedSessionId = 0
    /** 单应用模式（intent extra mode=single 时置位，4.2） */
    private var isSingleApp = false
    private var singleAppName = ""

    /** 档位循环（3.3）：1080P·60 → 720P·60 → 1080P·30 → 自定义 → 循环 */
    private var tierIdx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        isSingleApp = intent.getStringExtra("mode") == "single"
        singleAppName = intent.getStringExtra("appName") ?: intent.getStringExtra("pkg") ?: ""
        buildConsoleUi()
        // 触摸挂接（真机修复：此前 TouchChannel 从未接线，全屏触摸不可用）
        surfaceView.setOnTouchListener(
            cloudphone.client.input.TouchChannel(
                sessionIdProvider = { ClientSession.currentSessionId },
                send = { ClientSession.sendControl(it) },
            )
        )
        // 白闪底：首帧渲染前全白（测量基准）
        rootLayout.setBackgroundColor(0xFFFFFFFF.toInt())
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = startPipeline(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = stopPipeline()
        })
        ClientSession.onFrame = { frame -> onTransportFrame(frame) }
        ClientSession.onConnectionLost = {
            runOnUiThread {
                rootLayout.setBackgroundColor(0xFFFFFFFF.toInt())
                firstFrameRenderedMs.set(-1)
                // 重连退避期间 DISCONNECTED 会多次触发，toast 节流 ≥3s 防止刷屏
                val now = SystemClock.elapsedRealtime()
                if (now - lastLostToastAtMs > 3000) {
                    lastLostToastAtMs = now
                    Toast.makeText(this, "连接断开，正在重连…", Toast.LENGTH_SHORT).show()
                }
            }
        }
        // 重连终止（被占用拒绝/次数耗尽）：明确告知后退出渲染页，回连接页展示原因
        ClientSession.onReconnectStopped = { reason ->
            runOnUiThread {
                Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
                finish()
            }
        }
        ClientSession.onStats = { stats ->
            handler.post { statsView.update(stats, delayStats()) }
        }
        // M3：辅助通道接线（音频默认开启；静音按钮独立控制）
        aux = AuxChannelClient()
        aux.onUiState = { st -> runOnUiThread { onAuxUiState(st) } }
        ClientSession.onAux = { msg -> aux.handleAux(msg) }
        aux.startAudio()
        // 音频通道活性观测：10s 无任何音频帧且无源端通告 → 提示（源端静音/通道未建立）
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isFinishing && aux.player.framesReceived.get() == 0L && audioReason == null) {
                audioReason = "音频未收到数据（源端静音、音频通道未建立或源端版本过旧）"
                refreshStatus()
            }
        }, 10_000L)
        connectedAtMs.set(SystemClock.elapsedRealtime())
        // 单应用模式徽章（4.2）
        if (isSingleApp) showSingleAppBadge()
    }

    // ── 控制台 UI 构建（零依赖 View 体系） ──

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildConsoleUi() {
        rootLayout = FrameLayout(this)
        // 画面全幅（AspectFit：视频宽高比 letterbox，触控归一化基于该视口）
        surfaceView = AspectFitSurfaceView(this)
        surfaceView.id = R.id.console_surface
        rootLayout.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )

        // 顶部 HUD 统计条（横排：stats 占满 + 右侧「断开」——原在底部工具坞与系统手势区重叠难按）
        val hudBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x66000000.toInt())
        }
        statsView = StatsView(this)
        hudBar.addView(
            statsView,
            LinearLayout.LayoutParams(0, dp(44), 1f),
        )
        hudBar.addView(TextView(this).apply {
            text = "断开"
            setDockStyle(R.drawable.bg_btn_danger, 0xFFEF4444.toInt())
            setOnClickListener { disconnectToHome() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(10)
        })
        hudBarFrame = hudBar
        rootLayout.addView(
            hudBar,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP),
        )

        // 单应用模式徽章（4.2：圆角背景 TextView + 一键退出）
        singleAppBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_badge_accent)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            singleAppBadgeText = TextView(this@DecodeRenderActivity).apply {
                setTextColor(0xFFF8FAFC.toInt())
                textSize = 12f
            }
            addView(singleAppBadgeText)
            addView(TextView(this@DecodeRenderActivity).apply {
                text = "  退出"
                setTextColor(0xFF3B82F6.toInt())
                textSize = 12f
                setOnClickListener { exitSingleApp() }
            })
        }
        rootLayout.addView(
            singleAppBadge,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(52)
            },
        )
        singleAppBadge.visibility = View.GONE

        // 挂起态遮罩（会话抽屉「挂起」后画面停更 + 状态标注挂起）
        suspendOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC0B0B10.toInt())
            suspendLabel = TextView(this@DecodeRenderActivity).apply {
                setTextColor(0xFFF8FAFC.toInt())
                textSize = 16f
            }
            addView(suspendLabel)
            addView(TextView(this@DecodeRenderActivity).apply {
                text = "恢复观看"
                setBackgroundResource(R.drawable.bg_btn_accent)
                setTextColor(0xFF04101F.toInt())
                textSize = 14f
                setPadding(dp(20), dp(10), dp(20), dp(10))
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(16)
                setOnClickListener { resumeSession() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            })
        }
        rootLayout.addView(
            suspendOverlay,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        suspendOverlay.visibility = View.GONE

        // 底部工具坞（把手 + 内容两段；可收起避免遮挡触控区；全屏模式下作为悬浮菜单叠加在画面上）
        val dockRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_dock)
        }
        // 收起态把手
        val handleRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
            addView(View(this@DecodeRenderActivity).apply {
                setBackgroundResource(R.drawable.bg_handle)
            }, LinearLayout.LayoutParams(dp(36), dp(4)))
            addView(TextView(this@DecodeRenderActivity).apply {
                text = "工具坞"
                setTextColor(0xFF94A3B8.toInt())
                textSize = 10f
                setPadding(0, dp(2), 0, 0)
            })
        }
        handleRow.setOnClickListener { toggleDock() }
        dockRoot.addView(handleRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        dockContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(8)) }

        // 虚拟导航键行：BACK 注入；HOME/RECENTS 协议无对应消息——预留占位提示
        val navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        navRow.addView(TextView(this).apply {
            text = "◀ BACK"
            setNavStyle()
            setOnClickListener { sendRemoteBack() }
        }, rowParams())
        navRow.addView(TextView(this).apply {
            text = "● HOME · 预留"
            setNavStyle()
            alpha = 0.5f
            setOnClickListener { Toast.makeText(context, "协议暂无 HOME 消息（预留）", Toast.LENGTH_SHORT).show() }
        }, rowParams())
        navRow.addView(TextView(this).apply {
            text = "▣ RECENTS · 预留"
            setNavStyle()
            alpha = 0.5f
            setOnClickListener { Toast.makeText(context, "协议暂无 RECENTS 消息（预留）", Toast.LENGTH_SHORT).show() }
        }, rowParams())
        dockContent.addView(navRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 工具坞行 1：键盘 / 截屏 / 档位 / 旋转
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(dockButton("键盘") { toggleKeyboard() }, rowParams())
        row1.addView(dockButton("截屏") { takeScreenshot() }, rowParams())
        tierButton = dockButton(currentTierLabel()) { cycleTier() }
        row1.addView(tierButton, rowParams())
        row1.addView(dockButton("旋转") { toggleRotation() }, rowParams())
        fullscreenToggleBtn = dockButton("全屏") { toggleFullscreen() }
        row1.addView(fullscreenToggleBtn, rowParams())
        dockContent.addView(row1, dockRowParams())

        // 工具坞行 2：单应用 / 抽屉 / 断开
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(dockButton("单应用") {
            startActivity(Intent(this@DecodeRenderActivity, SourceAppsActivity::class.java))
        }, rowParams())
        row2.addView(dockButton("抽屉") { openDrawer() }, rowParams())
        dockContent.addView(row2, dockRowParams())

        // M3 辅助通道控制行（键盘=TEXT_INPUT 注入面板/粘贴/复制/静音 + 状态行）：
        // master 侧 buildControlBar 原样并入工具坞（dockContent 即竖排 LinearLayout 宿主），
        // 工具坞自身「键盘」（toggleKeyboard 本地 IME 焦点）与该行「键盘」（协议注入）并存。
        buildControlBar(dockContent)

        dockRoot.addView(dockContent, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        rootLayout.addView(
            dockRoot,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
        dockRootFrame = dockRoot

        // 全屏模式的悬浮呼出键（右缘中部半透明 ☰，点按呼出工具坞菜单）
        fullscreenFab = TextView(this).apply {
            text = "☰"
            textSize = 22f
            setTextColor(0xFFE2E8F0.toInt())
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x77202A3A.toInt())
            }
            alpha = 0.45f
            setOnClickListener {
                dockRootFrame.visibility = View.VISIBLE
                it.visibility = View.GONE
            }
        }
        rootLayout.addView(
            fullscreenFab,
            FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(6) },
        )
        fullscreenFab.visibility = View.GONE

        // 会话抽屉：右缘唤出条（24dp，横向拖拽唤出）+ 遮罩 + 自绘滑出面板
        val edgeStrip = View(this)
        edgeStrip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    edgeDownX = e.rawX
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.rawX - edgeDownX < -dp(40)) {
                        openDrawer()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
        rootLayout.addView(
            edgeStrip,
            FrameLayout.LayoutParams(dp(24), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END),
        )

        drawerScrim = View(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setOnClickListener { closeDrawer() }
        }
        drawerScrim.visibility = View.GONE
        rootLayout.addView(
            drawerScrim,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )

        drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_drawer_panel)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(TextView(this@DecodeRenderActivity).apply {
                text = "会话抽屉"
                setTextColor(0xFFF8FAFC.toInt())
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@DecodeRenderActivity).apply {
                text = "挂起 = 源端保留会话；恢复 = 秒回继续观看"
                setTextColor(0xFF94A3B8.toInt())
                textSize = 11f
                setPadding(0, dp(4), 0, dp(8))
            })
            hungListHost = LinearLayout(this@DecodeRenderActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(hungListHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            suspendDrawerButton = TextView(this@DecodeRenderActivity).apply {
                text = "挂起当前会话"
                setDockStyle(R.drawable.bg_btn_ghost, 0xFFF8FAFC.toInt())
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener {
                    if (suspended) {
                        Toast.makeText(context, "会话已挂起", Toast.LENGTH_SHORT).show()
                    } else {
                        suspendSession()
                        closeDrawer()
                    }
                }
            }
            addView(suspendDrawerButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            addView(TextView(this@DecodeRenderActivity).apply {
                text = "断开（回到连接首页）"
                setDockStyle(R.drawable.bg_btn_danger, 0xFFEF4444.toInt())
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { disconnectToHome() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            // 面板右滑关闭手势（平移）
            var downX = 0f
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        v.translationX = (e.rawX - downX).coerceAtLeast(0f)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (e.rawX - downX > dp(60)) {
                            closeDrawer()
                        } else {
                            v.animate().translationX(0f).setDuration(150).start()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        drawerPanel.visibility = View.GONE
        rootLayout.addView(
            drawerPanel,
            FrameLayout.LayoutParams(dp(300), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END),
        )

        // 软键盘焦点锚点（键盘按钮 = 唤起/收起 IME；1px 透明不遮挡画面）
        keyboardAnchor = EditText(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            alpha = 0f
        }
        rootLayout.addView(keyboardAnchor, FrameLayout.LayoutParams(1, 1))

        setContentView(rootLayout)
    }

    private var edgeDownX = 0f

    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) }

    private fun dockRowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }

    private fun TextView.setNavStyle() {
        setBackgroundResource(R.drawable.bg_btn_ghost)
        setTextColor(0xFFF8FAFC.toInt())
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }

    private fun TextView.setDockStyle(bgRes: Int, color: Int) {
        setBackgroundResource(bgRes)
        setTextColor(color)
        textSize = 11f
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    private fun dockButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setDockStyle(R.drawable.bg_btn_ghost, 0xFFF8FAFC.toInt())
            setOnClickListener { onClick() }
        }

    /**
     * 全屏切换：隐藏顶部 HUD 与工具坞，画面裁切铺满整屏（短边撑满），系统栏沉浸；
     * 悬浮 ☰ 键呼出工具坞（叠加在画面上，此时无系统栏遮挡）。「断开」常驻顶部 HUD 条。
     */
    private fun toggleFullscreen() {
        fullscreenMode = !fullscreenMode
        surfaceView.fillMode = fullscreenMode
        surfaceView.requestLayout()
        if (fullscreenMode) {
            hudBarFrame.visibility = View.GONE
            dockRootFrame.visibility = View.GONE
            fullscreenFab.visibility = View.VISIBLE
            hideSystemBars()
        } else {
            hudBarFrame.visibility = View.VISIBLE
            dockRootFrame.visibility = View.VISIBLE
            fullscreenFab.visibility = View.GONE
            showSystemBars()
        }
        fullscreenToggleBtn.text = if (fullscreenMode) "退出全屏" else "全屏"
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：deprecated systemUiVisibility 会被新平台忽略，必须走 WindowInsetsController
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { c ->
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun toggleDock() {
        dockContent.visibility = if (dockContent.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    // ── 虚拟导航键（3.1） ──

    /** BACK 经既有 INPUT_EVENT InputAction.BACK 注入源端（与边缘侧滑同一通道） */
    private fun sendRemoteBack() {
        ClientSession.sendControl(
            ControlMessage.InputEvent(
                sessionId = ClientSession.currentSessionId,
                action = cloudphone.protocol.InputAction.BACK,
                pointerId = 0,
                x = 0,
                y = 0,
            ),
        )
    }

    // ── 工具坞动作（3.1/3.3） ──

    /** 键盘 = 唤起/收起软键盘（spec 工具坞「键盘唤起」；注入文字无协议消息，见 run-result findings） */
    private fun toggleKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        if (keyboardAnchor.hasFocus()) {
            imm.hideSoftInputFromWindow(keyboardAnchor.windowToken, 0)
            Toast.makeText(this, "键盘已收起", Toast.LENGTH_SHORT).show()
        } else {
            keyboardAnchor.requestFocus()
            imm.showSoftInput(keyboardAnchor, InputMethodManager.SHOW_IMPLICIT)
            Toast.makeText(this, "键盘已唤起", Toast.LENGTH_SHORT).show()
        }
    }

    /** 截屏：PixelCopy 抓 Surface 当前帧 → 保存应用私有外部存储 → toast 确认（会话不中断） */
    private fun takeScreenshot() {
        val w = surfaceView.width.coerceAtLeast(1)
        val h = surfaceView.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        PixelCopy.request(surfaceView, bitmap, { result ->
            if (result != PixelCopy.SUCCESS) {
                Toast.makeText(this, "截屏失败（err=$result）", Toast.LENGTH_SHORT).show()
                return@request
            }
            runCatching {
                val dir = File(getExternalFilesDir(null), "screens").apply { mkdirs() }
                val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
                val file = File(dir, "screenshot-$ts.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Toast.makeText(this, "已保存：${file.absolutePath}", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(this, "截屏保存失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }, handler)
    }

    /** 旋转 = 本地切换客户端横竖屏呈现（MUST NOT 改源端） */
    private fun toggleRotation() {
        // 显式横/竖切换（非 SENSOR_*：模拟器镜像无有效加速度计时 SENSOR_* 不生效，真机行为一致）
        requestedOrientation =
            if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
    }

    // ── 档位循环（3.3，resolution-passthrough 参数语义） ──

    private val tierShortSides = intArrayOf(1080, 720, 1080, 0) // 0 = 自定义
    private val tierFps = intArrayOf(60, 60, 30, 0)
    private val tierLabels = arrayOf("1080P·60", "720P·60", "1080P·30", "自定义")

    private fun currentTierLabel(): String = tierLabels[tierIdx]

    /** 工具坞档位点击：按顺序循环；到「自定义」弹输入；每次以新档位重发整屏 START_SESSION */
    private fun cycleTier() {
        tierIdx = (tierIdx + 1) % tierLabels.size
        tierButton.text = tierLabels[tierIdx]
        if (tierIdx == 3) {
            showCustomTierDialog()
            return
        }
        val (w, h) = tierSize(tierShortSides[tierIdx])
        applyTier(w, h, tierFps[tierIdx], SettingsPrefs.bitrateMbps(this) * 1000)
    }

    /** 以新档位重发整屏 START_SESSION（sessionId=0 新会话；控制链路不断开，源端按新参重建管线后 IDR 恢复） */
    private fun applyTier(w: Int, h: Int, fps: Int, kbps: Int) {
        if (!ClientSession.connected) {
            Toast.makeText(this, "无在线连接，档位未切换", Toast.LENGTH_SHORT).show()
            return
        }
        // 客户端本地范围校验（既有 resolution-passthrough 规则：width 320–2560、height 240–2560）
        if (w !in 320..2560 || h !in 240..2560) {
            Toast.makeText(this, "档位非法（width 320–2560、height 240–2560）", Toast.LENGTH_LONG).show()
            return
        }
        Thread({
            val ack = ClientSession.startSession(WatchMode.FULLSCREEN, "", 0, w, h, fps, kbps)
            runOnUiThread {
                if (ack == null || ack.status != HelloStatus.ACCEPTED) {
                    Toast.makeText(this, "档位切换失败：${ack?.reason ?: ClientSession.lastError}", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                Toast.makeText(this, "档位已切换 ${w}×$h@${fps}", Toast.LENGTH_SHORT).show()
            }
        }, "tier-switch").apply { isDaemon = true; start() }
    }

    /** 自定义档输入（默认值取最近一次被接受档位） */
    private fun showCustomTierDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        fun numField(label: String, value: Int): EditText = EditText(this).apply {
            hint = label
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
        }
        val wDef = ClientSession.lastAcceptedWidth.takeIf { it > 0 } ?: 1920
        val hDef = ClientSession.lastAcceptedHeight.takeIf { it > 0 } ?: 1080
        val fpsDef = ClientSession.lastAcceptedFps.takeIf { it > 0 } ?: 60
        val kbpsDef = ClientSession.lastAcceptedKbps.takeIf { it > 0 } ?: 8000
        val wInput = numField("宽（320–2560）", wDef)
        val hInput = numField("高（240–2560）", hDef)
        val fpsInput = numField("帧率（30/60）", fpsDef)
        val kbpsInput = numField("码率（kbps）", kbpsDef)
        listOf("宽 × 高" to listOf(wInput, hInput), "帧率" to listOf(fpsInput), "码率 kbps" to listOf(kbpsInput)).forEach { (title, fields) ->
            box.addView(TextView(this).apply { text = title; setTextColor(0xFF94A3B8.toInt()); textSize = 12f })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fields.forEach { f ->
                row.addView(f, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            }
            box.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("自定义档位")
            .setView(box)
            .setPositiveButton("应用") { d, _ ->
                val w = wInput.text.toString().toIntOrNull() ?: 0
                val h = hInput.text.toString().toIntOrNull() ?: 0
                val fps = fpsInput.text.toString().toIntOrNull() ?: 60
                val kbps = kbpsInput.text.toString().toIntOrNull() ?: 8000
                if (w in 320..2560 && h in 240..2560) {
                    applyTier(w, h, fps, kbps)
                } else {
                    Toast.makeText(this, "非法输入（width 320–2560、height 240–2560），档位未切换", Toast.LENGTH_LONG).show()
                    tierIdx = 2 // 回退到上一档
                    tierButton.text = tierLabels[tierIdx]
                }
                d.dismiss()
            }
            .setNegativeButton("取消") { d, _ ->
                tierIdx = 2 // 回退到上一档
                tierButton.text = tierLabels[tierIdx]
                d.dismiss()
            }
            .show()
    }

    /** 档位短边尺寸按当前朝向映射（与首页档位同一映射规则） */
    private fun tierSize(shortSide: Int): Pair<Int, Int> {
        val longSide = when (shortSide) {
            720 -> 1280
            else -> 1920
        }
        val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        return if (portrait) shortSide to longSide else longSide to shortSide
    }

    // ── 会话抽屉（3.2：挂起/恢复/断开；挂机列表查询沿用 HELLO_ACK.hungSessions） ──

    private fun openDrawer() {
        drawerScrim.visibility = View.VISIBLE
        drawerPanel.visibility = View.VISIBLE
        drawerPanel.post {
            drawerPanel.translationX = drawerPanel.width.toFloat()
            drawerPanel.animate().translationX(0f).setDuration(200).start()
        }
        suspendDrawerButton.text = if (suspended) "已挂起（点恢复观看继续）" else "挂起当前会话"
        refreshHungList()
    }

    private fun closeDrawer() {
        drawerPanel.animate().translationX(drawerPanel.width.toFloat()).setDuration(180).start()
        drawerScrim.visibility = View.GONE
        drawerPanel.postDelayed({ drawerPanel.visibility = View.GONE }, 180)
    }

    /** 挂机列表（既有语义：HELLO(ATTACH) → HELLO_ACK.hungSessions；仅在线时可查） */
    private fun refreshHungList() {
        hungListHost.removeAllViews()
        if (!ClientSession.connected) {
            hungListHost.addView(TextView(this).apply {
                text = "（无在线连接——会话已挂起，用「恢复观看」重连）"
                setTextColor(0xFF94A3B8.toInt())
                textSize = 12f
                setPadding(0, dp(4), 0, dp(8))
            })
            return
        }
        Thread({
            val ok = ClientSession.handshake(WatchMode.ATTACH, "")
            val ack = ClientSession.lastHelloAck
            runOnUiThread {
                hungListHost.removeAllViews()
                val hung = ack?.hungSessions ?: emptyList()
                if (!ok || hung.isEmpty()) {
                    hungListHost.addView(TextView(this).apply {
                        text = if (ok) "（无挂机会话）" else "查询失败：${ClientSession.lastError}"
                        setTextColor(0xFF94A3B8.toInt())
                        textSize = 12f
                        setPadding(0, dp(4), 0, dp(8))
                    })
                    return@runOnUiThread
                }
                hung.forEach { s ->
                    val row = LinearLayout(this@DecodeRenderActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(6), 0, dp(6))
                        addView(TextView(this@DecodeRenderActivity).apply {
                            text = "#${s.sessionId} · ${s.mode} · ${s.packageName.ifBlank { "整屏" }}\n${s.width}×${s.height}"
                            setTextColor(0xFFF8FAFC.toInt())
                            textSize = 12f
                        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        addView(TextView(this@DecodeRenderActivity).apply {
                            text = "恢复"
                            setBackgroundResource(R.drawable.bg_btn_accent)
                            setTextColor(0xFF04101F.toInt())
                            textSize = 12f
                            setPadding(dp(14), dp(6), dp(14), dp(6))
                            setOnClickListener { attachHung(s.sessionId) }
                        })
                    }
                    hungListHost.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                }
            }
        }, "drawer-refresh").apply { isDaemon = true; start() }
    }

    /** attach 恢复挂机会话（≤1s 源端 IDR 出画——既有语义） */
    private fun attachHung(sessionId: Int) {
        Thread({
            val ack = ClientSession.startSession(WatchMode.ATTACH, "", sessionId, 0, 0, 0, 0)
            runOnUiThread {
                if (ack == null || ack.status != HelloStatus.ACCEPTED) {
                    Toast.makeText(this, "恢复失败：${ack?.reason ?: ClientSession.lastError}", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                suspended = false
                suspendOverlay.visibility = View.GONE
                closeDrawer()
                Toast.makeText(this, "已恢复 #$sessionId", Toast.LENGTH_SHORT).show()
            }
        }, "drawer-attach").apply { isDaemon = true; start() }
    }

    /** 挂起：客户端停止观看（断链，源端 hangup 保留会话），画面停更并标注挂起 */
    private fun suspendSession() {
        val id = ClientSession.currentSessionId
        if (id == 0) {
            Toast.makeText(this, "当前无观看会话", Toast.LENGTH_SHORT).show()
            return
        }
        suspendedSessionId = id
        suspended = true
        ClientSession.close() // 既有挂机语义：源端 onStreamEvent DISCONNECTED → hangup（保留会话）
        suspendLabel.text = "已挂起 · 会话 #$id（源端保留）"
        suspendOverlay.visibility = View.VISIBLE
        Toast.makeText(this, "已挂起 #$id", Toast.LENGTH_SHORT).show()
    }

    /** 恢复：重连 + attach 原会话（源端 IDR 秒回）；失败回退开全新整屏会话（既有重连兜底语义） */
    private fun resumeSession() {
        val cfg = ClientSession.currentConfig()
        if (cfg == null) {
            Toast.makeText(this, "无历史连接参数，请断开重连", Toast.LENGTH_LONG).show()
            return
        }
        val target = suspendedSessionId
        suspendLabel.text = "恢复中…"
        Thread({
            val ok = ClientSession.connect(cfg, WatchMode.ATTACH, "")
            if (!ok) {
                runOnUiThread {
                    suspendLabel.text = "已挂起 · 会话 #$target（恢复失败：${ClientSession.lastError}）"
                }
                return@Thread
            }
            var ack = if (target != 0) {
                ClientSession.startSession(WatchMode.ATTACH, "", target, 0, 0, 0, 0)
            } else {
                null
            }
            if (ack?.status != HelloStatus.ACCEPTED) {
                // resume 被拒（会话已被源端回收等）→ 带原参数开全新会话兜底（既有语义）
                ack = ClientSession.startSession(
                    WatchMode.FULLSCREEN, "", 0,
                    ClientSession.lastAcceptedWidth, ClientSession.lastAcceptedHeight,
                    ClientSession.lastAcceptedFps, ClientSession.lastAcceptedKbps,
                )
            }
            runOnUiThread {
                if (ack?.status != HelloStatus.ACCEPTED) {
                    suspendLabel.text = "已挂起 · 会话 #$target（恢复失败：${ack?.reason ?: ClientSession.lastError}）"
                    return@runOnUiThread
                }
                suspended = false
                suspendOverlay.visibility = View.GONE
                Toast.makeText(this, "会话已恢复", Toast.LENGTH_SHORT).show()
            }
        }, "drawer-resume").apply { isDaemon = true; start() }
    }

    /** 断开：关链并回到连接首页（spec「断开则回到连接首页」） */
    private fun disconnectToHome() {
        ClientSession.close()
        startActivity(
            Intent(this, ConnectActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

    // ── 单应用徽章（4.2） ──

    private fun showSingleAppBadge() {
        val label = singleAppName.ifBlank { "未知应用" }
        singleAppBadgeText.text = "单应用 · $label"
        singleAppBadge.visibility = View.VISIBLE
    }

    /** 一键退出：结束单应用会话 → 以最近档位重开整屏会话（返回系统画面、连接保持） */
    private fun exitSingleApp() {
        val id = ClientSession.currentSessionId
        val w = ClientSession.lastAcceptedWidth.takeIf { it > 0 } ?: 1920
        val h = ClientSession.lastAcceptedHeight.takeIf { it > 0 } ?: 1080
        val fps = ClientSession.lastAcceptedFps.takeIf { it > 0 } ?: 60
        val kbps = ClientSession.lastAcceptedKbps.takeIf { it > 0 } ?: 8000
        Thread({
            if (id != 0) ClientSession.stopSession(id) // 结束单应用会话
            val ack = ClientSession.startSession(WatchMode.FULLSCREEN, "", 0, w, h, fps, kbps)
            runOnUiThread {
                if (ack == null || ack.status != HelloStatus.ACCEPTED) {
                    Toast.makeText(this, "返回系统画面失败：${ack?.reason ?: ClientSession.lastError}", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                singleAppBadge.visibility = View.GONE
                Toast.makeText(this, "已返回系统画面", Toast.LENGTH_SHORT).show()
            }
        }, "single-app-exit").apply { isDaemon = true; start() }
    }

    // ── 控制条（任务 4.3：键盘/粘贴/复制 + 音频静音；不可用态置灰与原因文案）──

    private fun buildControlBar(root: LinearLayout) {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnKeyboard = android.widget.Button(this).apply {
            text = "键盘"
            setOnClickListener { showTextInputPanel() }
        }
        btnPaste = android.widget.Button(this).apply {
            text = "粘贴"
            setOnClickListener { onPasteClicked() }
        }
        btnCopy = android.widget.Button(this).apply {
            text = "复制"
            setOnClickListener { onCopyClicked() }
        }
        btnMute = android.widget.Button(this).apply {
            text = "🔊 开"
            setOnClickListener { toggleMute() }
            setOnLongClickListener { showVolumeDialog(); true }
        }
        for (b in arrayOf(btnKeyboard, btnPaste, btnCopy, btnMute)) {
            bar.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        statusLine = android.widget.TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        root.addView(statusLine, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** AuxNotice 驱动的可用性更新（原因文案→置灰，禁止静默失败） */
    private fun onAuxUiState(st: AuxChannelClient.UiState) {
        (st.audioUnavailableReason ?: st.clipboardUnavailableReason ?: st.keyboardUnavailableReason)?.let { r ->
            when {
                r.contains("未启用") && st.audioUnavailableReason == null -> imeReason = r
                r.contains("无获焦输入框") -> focusReason = r
                st.audioUnavailableReason != null -> audioReason = r
                else -> if (st.clipboardUnavailableReason != null) focusReason = r
            }
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        statusLine.text = listOfNotNull(
            audioReason?.let { "音频: $it" },
            imeReason?.let { "输入法: $it" },
            focusReason?.let { "焦点: $it" },
        ).joinToString("；").ifBlank { "辅助通道就绪" }
        btnKeyboard.isEnabled = imeReason == null && focusReason == null
        btnPaste.isEnabled = imeReason == null
        btnCopy.isEnabled = imeReason == null && focusReason == null
        btnMute.isEnabled = audioReason == null || aux.player.framesReceived.get() > 0
        btnMute.text = if (aux.player.muted) "🔇 关" else "🔊 开"
    }

    /** 键盘按钮：多行文本面板（TEXT_INPUT 发送，完整 Unicode 走 IME commitText） */
    private fun showTextInputPanel() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = android.view.Gravity.TOP
            hint = "输入要注入到源端的文本（支持中文）"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("文字输入（IME 注入主路径）")
            .setView(input)
            .setPositiveButton("发送") { d, _ ->
                val text = input.text.toString()
                d.dismiss()
                if (text.isNotEmpty()) sendTextInput(text)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sendTextInput(text: String) {
        statusLine.text = "注入中…"
        Thread({
            val (ok, why) = aux.sendText(text)
            runOnUiThread {
                statusLine.text = if (ok) "已注入（${text.length} 字符）✓" else "注入失败：$why"
                if (!ok && why.contains("无获焦输入框")) {
                    focusReason = why
                    refreshStatus()
                }
            }
        }, "aux-text-input").apply { isDaemon = true; start() }
    }

    /** 粘贴按钮：客户端本地剪贴板 → CLIPBOARD_SET → 源端 IME setPrimaryClip */
    private fun onPasteClicked() {
        val text = aux.localClipboardText(this)
        if (text == null) {
            statusLine.text = "本地剪贴板无文本（先在本机复制内容）"
            return
        }
        Thread({
            val (ok, v) = aux.sendClipboardSet(text)
            runOnUiThread {
                statusLine.text = if (ok) "已粘贴到源端（${text.length} 字符）✓" else "粘贴失败：$v"
                if (!ok && v.contains("未启用")) {
                    imeReason = v
                    refreshStatus()
                }
            }
        }, "aux-clipboard-set").apply { isDaemon = true; start() }
    }

    /** 复制按钮：CLIPBOARD_GET → 等待 CLIPBOARD_TEXT 回执（10s 超时降级）→ 写本地剪贴板 */
    private fun onCopyClicked() {
        statusLine.text = "正在读取源端剪贴板…"
        Thread({
            val (ok, v) = aux.requestClipboardGet()
            runOnUiThread {
                if (ok) {
                    aux.setLocalClipboard(this, v)
                    statusLine.text = "已复制到本机（${v.length} 字符）✓"
                } else {
                    statusLine.text = "复制失败：$v"
                    if (v.contains("未启用")) imeReason = v
                    if (v.contains("无获焦输入框")) focusReason = v
                    refreshStatus()
                }
            }
        }, "aux-clipboard-get").apply { isDaemon = true; start() }
    }

    /** 音频静音开关（会话级独立，不联动视频） */
    private fun toggleMute() {
        aux.player.muted = !aux.player.muted
        refreshStatus()
    }

    /** 长按音量按钮：独立音量滑条（不联动视频） */
    private fun showVolumeDialog() {
        val seek = android.widget.SeekBar(this).apply {
            max = 100
            progress = (aux.player.volume * 100).toInt()
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("音频音量（独立于视频）")
            .setView(seek)
            .setPositiveButton("确定", null)
            .show()
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                aux.player.volume = progress / 100f
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) = Unit
        })
    }

    // ── 传输帧入口 ──

    private fun onTransportFrame(frame: ClientSession.VideoFrame) {
        // 丢帧保护：队列满（解码背压）时丢最旧的普通帧，保留关键帧
        while (!frameQueue.offer(frame)) {
            val dropped = frameQueue.poll()
            if (dropped?.keyframe == true) frameQueue.offer(dropped)
        }
    }

    // ── 解码管线（既有逻辑不变） ──

    private fun startPipeline(surface: Surface) {
        if (running.get()) return
        running.set(true)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720).apply {
            // 实际分辨率随首帧 SPS 自适应（解码器按流内容解析）
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
        }
        val name = pickDecoder() ?: run {
            Toast.makeText(this, "无可用 AVC 解码器", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val codec = MediaCodec.createByCodecName(name)
        try {
            codec.configure(format, surface, null, 0)
        } catch (e: Exception) {
            // 分辨率不匹配等：不带尺寸重配（流内 SPS 优先）
            val retry = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
            retry.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            codec.configure(retry, surface, null, 0)
        }
        codec.start()
        decoder = codec
        renderThread = Thread({ decodeLoop(codec) }, "client-decode").apply { isDaemon = true; start() }
        watchThread = Thread({ blackScreenWatch() }, "client-watch").apply { isDaemon = true; start() }
    }

    private fun pickDecoder(): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder.not() }
        val avc = list.filter { it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        // goldfish 解码器是模拟器虚拟硬件：吃帧不出帧（stale input buffer 后静默），实测必须避开；
        // 真机无 goldfish，硬解优先不受影响
        val usable = avc.filter { !it.name.contains("goldfish", true) }
        return (usable.firstOrNull {
            val n = it.name
            !n.startsWith("OMX.google") && !n.startsWith("c2.android")
        } ?: usable.firstOrNull() ?: avc.firstOrNull())?.name
    }

    private fun decodeLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val frame = frameQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue
            var index = codec.dequeueInputBuffer(20_000)
            if (index >= 0) {
                val buf = codec.getInputBuffer(index) ?: continue
                buf.clear()
                buf.put(frame.data)
                codec.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, 0)
            } else {
                continue
            }
            index = codec.dequeueOutputBuffer(info, 20_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // 真实视频尺寸到达：更新 letterbox 视口（触控归一化基于该视口）+ HUD 分辨率读数
                val f = codec.outputFormat
                val vw = runCatching { f.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
                val vh = runCatching { f.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
                if (vw > 0 && vh > 0) {
                    (surfaceView as AspectFitSurfaceView).setVideoSize(vw, vh)
                    statsView.setResolution(vw, vh) // HUD 分辨率随源端实际输出更新（3.3）
                    android.util.Log.i("cloudphone-client", "video size $vw x $vh")
                }
                continue
            }
            if (index >= 0) {
                // latest-wins 渲染（触摸延迟修复 5.3）：解码照常（保 P 帧参考链完整），
                // 但仅当队列为空（本帧已是最新）或本帧为关键帧时才上屏；
                // 积压的陈旧帧 releaseOutputBuffer(false) 丢弃，避免显示滞后 = 积压深度 × 帧间隔
                val isLatest = frameQueue.isEmpty() || frame.keyframe
                codec.releaseOutputBuffer(index, isLatest) // true=渲染到 Surface
                onFrameRendered(frame, info)
            }
        }
    }

    private fun onFrameRendered(frame: ClientSession.VideoFrame, info: MediaCodec.BufferInfo) {
        lastOutputAtMs.set(SystemClock.elapsedRealtime())
        idrInFlight.set(false)
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        // 收流→渲染管线延迟（客户端本地时钟，跨设备时钟域不可直接相减）
        val delayMs = (nowNanos / 1000 - frame.arrivalUs) / 1000
        if (delayMs in 0..10_000) {
            synchronized(delaySamplesMs) {
                delaySamplesMs.addLast(delayMs)
                while (delaySamplesMs.size > 100) delaySamplesMs.removeFirst()
            }
            android.util.Log.i(
                "cloudphone-latency",
                "frame seq=${frame.sequence} delayMs=$delayMs keyframe=${frame.keyframe}",
            )
        }
        // ⑥ 渲染完成打点（触摸延迟插桩 5.1）：sinceTouchMs = 本帧渲染时刻 − 最近触摸发出时刻
        val sinceTouchMs = SystemClock.elapsedRealtime() - ClientSession.lastInputSentAtMs
        if (ClientSession.lastInputSentAtMs > 0 && sinceTouchMs in 0..5000) {
            android.util.Log.i(
                "cloudphone-touch",
                "t6 render seq=${frame.sequence} sinceTouchMs=$sinceTouchMs keyframe=${frame.keyframe}",
            )
        }
        if (firstFrameRenderedMs.compareAndSet(-1, SystemClock.elapsedRealtime())) {
            val flashMs = firstFrameRenderedMs.get() - connectedAtMs.get()
            android.util.Log.i("cloudphone-latency", "white-flash Ms=$flashMs")
            runOnUiThread {
                // 首帧已渲染：撤白闪（重设计布局：白底在 rootLayout，沿用分支既有撤白通路）
                (findViewById<FrameLayout>(android.R.id.content)).setBackgroundColor(0x00000000)
            }
        }
    }

    /** 黑屏看守：>1s 无输出帧 → 请求 IDR（≤200ms 恢复；静屏无输入时每 2s 重试） */
    private fun blackScreenWatch() {
        var lastIdrRequestAtMs = 0L
        while (running.get()) {
            Thread.sleep(300)
            val idleMs = SystemClock.elapsedRealtime() - lastOutputAtMs.get()
            // 源端编码器为 surface 输入：静屏时无新帧进编码器，sync-frame 请求要等下次
            // 重绘才兑现——单次 CAS 只发一次会让白屏等到天荒地老，改为 2s 持续重试
            val now = SystemClock.elapsedRealtime()
            if (idleMs > 1000 && now - lastIdrRequestAtMs > 2000) {
                lastIdrRequestAtMs = now
                ClientSession.sendControl(
                    ControlMessage.IdrRequest(sessionId = ClientSession.currentSessionId),
                )
            }
        }
    }

    /** 请求立即关键帧（UI 手动触发入口） */
    fun requestKeyframe() {
        idrInFlight.set(false)
        ClientSession.sendControl(ControlMessage.IdrRequest(sessionId = ClientSession.currentSessionId))
    }

    private fun stopPipeline() {
        running.set(false)
        renderThread?.join(1000)
        watchThread?.join(300)
        runCatching {
            decoder?.stop()
            decoder?.release()
        }
        decoder = null
        frameQueue.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPipeline()
        ClientSession.onFrame = null
        ClientSession.onConnectionLost = null
        ClientSession.onReconnectStopped = null
        ClientSession.onStats = null
        // M3：辅助通道释放
        aux.stopAudio()
        ClientSession.onAux = null
    }

    /** 最近延迟统计（P50/P95，供 StatsView） */
    fun delayStats(): Pair<Long, Long>? {
        synchronized(delaySamplesMs) {
            if (delaySamplesMs.isEmpty()) return null
            val sorted = delaySamplesMs.sorted()
            return sorted[sorted.size / 2] to sorted[((sorted.size - 1) * 95) / 100]
        }
    }

    /**
     * 按视频宽高比 letterbox 的 SurfaceView：onMeasure 使自身 laid-out 尺寸 = 视频显示区
     * （居中于父容器，黑边由父容器白底/黑底承载）；TouchChannel 归一化基于该 View 尺寸，
     * 故视口=触控区，坐标语义与源端真实屏幕一一对应。
     */
    inner class AspectFitSurfaceView(context: android.content.Context) : SurfaceView(context) {
        private var videoW = 0
        private var videoH = 0
        /** false=等宽适配（信箱留白）；true=裁切铺满（全屏模式，短边撑满、长边溢出裁切） */
        var fillMode = false

        fun setVideoSize(w: Int, h: Int) {
            if (w == videoW && h == videoH) return
            videoW = w
            videoH = h
            post { requestLayout() }
        }

        override fun onMeasure(wSpec: Int, hSpec: Int) {
            if (videoW <= 0 || videoH <= 0) {
                super.onMeasure(wSpec, hSpec)
                return
            }
            val pw = View.MeasureSpec.getSize(wSpec)
            val ph = View.MeasureSpec.getSize(hSpec)
            val videoAspect = videoW.toDouble() / videoH
            val parentAspect = pw.toDouble() / ph
            val (w, h) = if (fillMode) {
                // 铺满：取更大的缩放，溢出边由父容器裁切（Gravity.CENTER 对称裁）
                if (videoAspect > parentAspect) {
                    (ph * videoAspect).toInt() to ph
                } else {
                    pw to (pw / videoAspect).toInt()
                }
            } else if (videoAspect > parentAspect) {
                pw to (pw / videoAspect).toInt()
            } else {
                (ph * videoAspect).toInt() to ph
            }
            setMeasuredDimension(w.coerceAtLeast(1), h.coerceAtLeast(1))
        }
    }
}
