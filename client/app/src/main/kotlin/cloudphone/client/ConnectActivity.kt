package cloudphone.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import cloudphone.protocol.ControlMessage
import cloudphone.protocol.HelloStatus
import cloudphone.protocol.TransportKind
import cloudphone.protocol.WatchMode
import cloudphone.protocol.decodeControlMessage
import cloudphone.protocol.encodeControlMessage
import cloudphone.transport.BackendKind
import cloudphone.transport.P2pBackend
import cloudphone.transport.Role
import cloudphone.transport.StunClient
import cloudphone.transport.StreamEvent
import cloudphone.transport.TransportBackend
import cloudphone.transport.TransportConfig
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 客户端单例连接会话（进程内唯一）：
 * 传输后端生命周期（CLIENT 角色 connect）、握手（HELLO/HELLO_ACK token 配对 + p2p offer/answer）、
 * START/STOP 会话、断线自动重连（≤1s：重连→重握手→attach 恢复，源端 IDR 秒回）、
 * 视频帧分发（onVideo → [onFrame]）与控制分发（HELLO_ACK/STATS/PONG/PING）。
 */
object ClientSession {

    /** arrivalUs 为客户端本地时钟（收流时刻）；源端 ptsUs 是对端时钟，跨设备不可比，
     *  端到端绝对延迟以白闪法（measure_latency.py）为准，本字段度量收流→渲染管线延迟 */
    data class VideoFrame(
        val sequence: Long,
        val ptsUs: Long,
        val keyframe: Boolean,
        val data: ByteArray,
        val arrivalUs: Long = SystemClock.elapsedRealtimeNanos() / 1000,
    )

    data class Config(
        val host: String,
        val tcpPort: Int,
        val udpPort: Int,
        val kind: BackendKind,
        val token: String,
        val useUdpVideo: Boolean,
    )

    @Volatile var onFrame: ((VideoFrame) -> Unit)? = null
    @Volatile var onConnectionLost: (() -> Unit)? = null
    @Volatile var onStats: ((ControlMessage.StatsMessage) -> Unit)? = null
    @Volatile var onHelloAck: ((ControlMessage.HelloAck) -> Unit)? = null
    /** APP_LIST 应答分发（应用列表页消费；3.1/4.1） */
    @Volatile var onAppList: ((ControlMessage.AppListMessage) -> Unit)? = null

    @Volatile var currentSessionId = 0
    @Volatile var lastHelloAck: ControlMessage.HelloAck? = null
        private set
    @Volatile var connected = false
        private set
    @Volatile var lastError = ""
        private set

    var backend: TransportBackend? = null
        private set
    private var config: Config? = null

    /** 当前连接参数读取口（快捷方式构建、档案 upsert 复用；token 字段敏感，勿入日志） */
    fun currentConfig(): Config? = config
    private var currentMode: WatchMode = WatchMode.FULLSCREEN
    private var currentPackage = ""
    private val reconnecting = AtomicBoolean(false)
    // 单飞守卫：connect 主流程与 scheduleReconnect 竞态曾造成双 TCP 连接并存，
    // 两侧共享同一 ackQueue 互吃对方应答（HELLO ack timeout 竟发生在收到 ack 之后）
    private val connecting = AtomicBoolean(false)
    // 有界缓冲（避免 SynchronousQueue 无阻塞 offer 在 poll 未就绪时丢 ack 的竞态）
    private val ackQueue = java.util.concurrent.LinkedBlockingQueue<ControlMessage.HelloAck>(8)

    // ── 连接与握手 ──

    /** 建链 + HELLO 握手（≤500ms 目标；成功 true） */
    fun connect(cfg: Config, mode: WatchMode, packageName: String): Boolean {
        if (!connecting.compareAndSet(false, true)) {
            lastError = "连接流程进行中"
            return false
        }
        try {
            config = cfg
            currentMode = mode
            currentPackage = packageName
            return connectOnce() && handshake(mode, packageName)
        } finally {
            connecting.set(false)
        }
    }

    private fun connectOnce(): Boolean {
        val cfg = config ?: return false
        backend?.close()
        val transportCfg = TransportConfig(Role.CLIENT, cfg.kind, cfg.host, cfg.tcpPort, cfg.udpPort, useUdpVideo = cfg.useUdpVideo)
        val b = when (cfg.kind) {
            BackendKind.FRP -> cloudphone.transport.FrpBackend(transportCfg)
            BackendKind.LAN_DIRECT -> cloudphone.transport.LanDirectBackend(transportCfg)
            BackendKind.P2P -> P2pBackend(transportCfg)
        }
        backend = b
        b.onControl = cloudphone.transport.ControlSink { payload ->
            try {
                handleControl(decodeControlMessage(payload))
            } catch (_: cloudphone.protocol.ProtocolException) {
            }
        }
        b.onVideo = cloudphone.transport.VideoSink { frame ->
            onFrame?.invoke(VideoFrame(frame.sequence, frame.ptsUs, frame.keyframe, frame.data))
        }
        var connectOk = false
        val latch = SynchronousQueue<Boolean>()
        b.onStreamEvent = cloudphone.transport.StreamEventListener { event, detail ->
            when (event) {
                StreamEvent.CONNECTED, StreamEvent.RESTORED -> {
                    connected = true
                    latch.offer(true)
                }
                StreamEvent.DISCONNECTED -> {
                    connected = false
                    onConnectionLost?.invoke()
                    // connectOnce 内部换后端/旧链关闭也会触发 DISCONNECTED，
                    // connect 主流程进行中时禁止 scheduleReconnect 插进来抢 ackQueue
                    if (!connecting.get()) scheduleReconnect()
                }
                else -> Unit
            }
        }
        Thread({
            try {
                b.start()
            } catch (e: Exception) {
                lastError = "连接失败: ${e.message}"
                latch.offer(false)
            }
        }, "client-connect").apply { isDaemon = true; start() }
        // CONNECTED 事件等待（lan ≤100ms；frp 含转发建立 ≤3s；p2p 信令 ≤5s）
        connectOk = latch.poll(5, TimeUnit.SECONDS) == true
        if (!connectOk && lastError.isBlank()) lastError = "连接超时"
        return connectOk
    }

    /** HELLO 握手（token 配对 ≤500ms；p2p 附 SRFLX offer，收 answer 触发打洞） */
    fun handshake(mode: WatchMode, packageName: String): Boolean {
        val b = backend ?: return false
        val p2p = b as? P2pBackend
        val offer = if (p2p != null) {
            // 等本端 SRFLX 发现（≤2s），拿到则附 offer
            val deadline = System.currentTimeMillis() + 2000
            while (p2p.localSrflx == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
            p2p.localSrflx?.let { StunClient.formatSrflx(it) } ?: ""
        } else ""
        val hello = ControlMessage.Hello(
            version = 1,
            token = config?.token ?: "",
            mode = mode,
            packageName = packageName,
            width = 0, height = 0, fps = 0, bitrateKbps = 0,
            sessionId = ClientSession.currentSessionId,
            p2pOffer = offer,
        )
        while (ackQueue.poll() != null) Unit // 清残留
        sendControl(hello)
        android.util.Log.d("cloudphone-client", "HELLO sent (mode=$mode offer=${offer.isNotBlank()})")
        val ack = ackQueue.poll(2, TimeUnit.SECONDS) ?: run {
            android.util.Log.w("cloudphone-client", "HELLO ack timeout")
            lastError = "HELLO 无应答（握手超时）"
            return false
        }
        lastHelloAck = ack
        onHelloAck?.invoke(ack)
        if (ack.status != HelloStatus.ACCEPTED) {
            lastError = "握手被拒: ${ack.reason.ifBlank { ack.status.name }}"
            return false
        }
        if (p2p != null && ack.p2pAnswer.isNotBlank()) {
            StunClient.parseSrflx(ack.p2pAnswer)?.let { p2p.onPeerSrflx(it) }
        }
        return true
    }

    /** 发起/恢复会话；返回 HELLO_ACK（accepted 时 currentSessionId 已更新） */
    fun startSession(mode: WatchMode, packageName: String, sessionId: Int, width: Int, height: Int, fps: Int, bitrateKbps: Int): ControlMessage.HelloAck? {
        if (!connected && !connectOnce()) return null
        val hello = ControlMessage.StartSession(
            mode = mode,
            packageName = packageName,
            sessionId = sessionId,
            width = width, height = height, fps = fps, bitrateKbps = bitrateKbps,
        )
        while (ackQueue.poll() != null) Unit
        sendControl(hello)
        android.util.Log.d("cloudphone-client", "START_SESSION sent (mode=$mode pkg=$packageName sid=$sessionId)")
        val ack = ackQueue.poll(3, TimeUnit.SECONDS) ?: run {
            android.util.Log.w("cloudphone-client", "START_SESSION ack timeout")
            return null
        }
        lastHelloAck = ack
        onHelloAck?.invoke(ack)
        if (ack.status == HelloStatus.ACCEPTED && ack.sessionId != 0) {
            currentSessionId = ack.sessionId
            if (sessionId == 0) currentMode = mode
        }
        return ack
    }

    fun stopSession(sessionId: Int) {
        sendControl(ControlMessage.StopSession(sessionId))
        if (sessionId == currentSessionId) currentSessionId = 0
    }

    fun sendControl(message: ControlMessage) {
        try {
            backend?.sendControl(encodeControlMessage(message))
        } catch (_: Exception) {
        }
    }

    // ── 控制分发 ──

    private fun handleControl(msg: ControlMessage) {
        android.util.Log.d("cloudphone-client", "recv ${msg.javaClass.simpleName}")
        when (msg) {
            is ControlMessage.HelloAck -> {
                if (msg.status == HelloStatus.ACCEPTED && msg.sessionId != 0) currentSessionId = msg.sessionId
                ackQueue.offer(msg)
            }
            is ControlMessage.StatsMessage -> {
                // 矩阵采集口径（run_matrix.py 解析）
                android.util.Log.i(
                    "cloudphone-stats",
                    "fps=${msg.fps} bitrate=${msg.bitrateKbps} rtt=${msg.rttMs} loss=${msg.lossPct} seq=${msg.seq}",
                )
                onStats?.invoke(msg)
            }
            is ControlMessage.Ping -> sendControl(ControlMessage.Pong(msg.nonce, msg.senderUs, nowUs()))
            is ControlMessage.Pong -> {
                val rtt = (nowUs() - msg.senderUs) / 1000
                if (rtt in 0..10000) android.util.Log.i("cloudphone-latency", "rtt Ms=$rtt")
            }
            is ControlMessage.AppListMessage -> onAppList?.invoke(msg)
            is ControlMessage.Unknown -> Unit // 未知消息忽略且不中断会话
            else -> Unit
        }
    }

    // ── 断线重连 ≤1s ──

    private fun scheduleReconnect() {
        if (!reconnecting.compareAndSet(false, true)) return
        // 与主连接流程互斥：拿不到单飞守卫说明主流程在跑，放弃本次重连
        if (!connecting.compareAndSet(false, true)) {
            reconnecting.set(false)
            return
        }
        Thread({
            try {
                val target = currentSessionId
                var attempt = 0
                while (!connected && attempt < 5) {
                    Thread.sleep(200L)
                    if (connectOnce()) {
                        if (handshake(currentMode, currentPackage)) {
                            // attach 恢复挂机会话（源端 resume + IDR ≤1s 出画）
                            startSession(
                                currentMode, currentPackage,
                                sessionId = target, width = 0, height = 0, fps = 0, bitrateKbps = 0,
                            )
                            return@Thread
                        }
                    }
                    attempt++
                }
                lastError = "重连失败（已尝试 $attempt 次）"
            } finally {
                reconnecting.set(false)
                connecting.set(false)
            }
        }, "client-reconnect").apply { isDaemon = true; start() }
    }

    fun close() {
        backend?.close()
        backend = null
        connected = false
        currentSessionId = 0
    }

    private fun nowUs(): Long = android.os.SystemClock.elapsedRealtimeNanos() / 1000
}

/**
 * 连接设置 Activity（M0 4.3 + client-ux-and-input-latency 1.2/2.1/4.2）：
 * - 「已保存源端」档案下拉（选中回填全部字段）+ 删除入口；连接成功（START_SESSION
 *   ACCEPTED）自动 upsert 档案与 lastConnectedAt（1.2）；
 * - 串流档位：分辨率（跟随本机=真实屏幕尺寸默认档 / 720p / 1080p / 自定义宽高对话框，
 *   本地校验 width 320–2560、height 240–2560）+ 帧率（30/60 默认 60）+ 码率
 *   （4/8/16/20Mbps 默认 8），选定值随 START_SESSION width/height/fps/bitrateKbps
 *   下发（2.1）；auto* extras 自动化通道优先级高于 UI 档位（矩阵兼容不变）；
 * - cloudphone://launch deeplink：host/tcpPort/udpPort/backend/token/pkg → 自动填充 +
 *   落档案 + 静默连接 + SINGLE_APP 直达，失败带原因留在连接页便于重试（4.2）。
 */
class ConnectActivity : Activity() {

    private lateinit var hostInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var tcpPortInput: EditText
    private lateinit var udpPortInput: EditText
    private lateinit var backendSpinner: Spinner
    private lateinit var modeSpinner: Spinner
    private lateinit var packageInput: EditText
    private lateinit var useUdpCheck: CheckBox
    private lateinit var statusText: TextView
    private lateinit var profileSpinner: Spinner
    private lateinit var resolutionSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var bitrateSpinner: Spinner

    private val profiles by lazy { SourceProfiles(this) }
    /** 自定义宽高（对话框确认后生效；0 = 未设置） */
    private var customWidth = 0
    private var customHeight = 0
    /** 程序性 setSelection 期间抑制 onItemSelected 回填 */
    private var suppressProfileFill = false

    private val resolutionItems = arrayOf("跟随本机", "720p", "1080p", "自定义宽高…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val sv = android.widget.ScrollView(this).apply { root.addView(this) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; sv.addView(this) }

        content.addView(TextView(this).apply { text = "连接源端" })

        // ── 已保存源端（1.2：顶部档案下拉 + 删除）──
        content.addView(TextView(this).apply { text = "已保存源端（选中自动填充）" })
        profileSpinner = Spinner(this)
        content.addView(profileSpinner)
        content.addView(Button(this).apply {
            text = "删除选中档案"
            setOnClickListener { deleteSelectedProfile() }
        })

        content.addView(TextView(this).apply { text = "源端地址（模拟器宿主环回 10.0.2.2 / 局域网 IP）" })
        hostInput = EditText(this).apply { setText("10.0.2.2") }
        content.addView(hostInput)

        content.addView(TextView(this).apply { text = "配对 token" })
        tokenInput = EditText(this).apply { setText("cloudphone-m0") }
        content.addView(tokenInput)

        content.addView(TextView(this).apply { text = "TCP 端口" })
        tcpPortInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("22333")
        }
        content.addView(tcpPortInput)

        content.addView(TextView(this).apply { text = "UDP 端口" })
        udpPortInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("22334")
        }
        content.addView(udpPortInput)

        content.addView(TextView(this).apply { text = "传输后端" })
        backendSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ConnectActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("lan-direct", "p2p", "frp"),
            )
        }
        content.addView(backendSpinner)

        // ── 串流档位（2.1）──
        content.addView(TextView(this).apply { text = "分辨率（跟随本机 = 真实屏幕尺寸）" })
        resolutionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ConnectActivity,
                android.R.layout.simple_spinner_dropdown_item,
                resolutionItems,
            )
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (position == 3) showCustomResolutionDialog()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }
        content.addView(resolutionSpinner)

        content.addView(TextView(this).apply { text = "帧率" })
        fpsSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ConnectActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("60", "30"),
            )
        }
        content.addView(fpsSpinner)

        content.addView(TextView(this).apply { text = "码率" })
        bitrateSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ConnectActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("8 Mbps", "4 Mbps", "16 Mbps", "20 Mbps"),
            )
        }
        content.addView(bitrateSpinner)

        content.addView(TextView(this).apply { text = "观看模式" })
        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ConnectActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("整屏", "单应用"),
            )
        }
        content.addView(modeSpinner)

        content.addView(TextView(this).apply { text = "单应用包名" })
        packageInput = EditText(this).apply { setText("cloudphone.loadgen") }
        content.addView(packageInput)

        useUdpCheck = CheckBox(this).apply { text = "UDP 视频通道（lan 直连默认关闭）"; isChecked = false }
        content.addView(useUdpCheck)

        val connectButton = Button(this).apply {
            text = "连接并观看"
            setOnClickListener { connectAndWatch() }
        }
        content.addView(connectButton)

        val drawerButton = Button(this).apply {
            text = "会话抽屉（挂机/切换）"
            setOnClickListener { startActivity(Intent(this@ConnectActivity, SessionDrawerActivity::class.java)) }
        }
        content.addView(drawerButton)

        statusText = TextView(this)
        content.addView(statusText)
        setContentView(root)

        refreshProfileSpinner()

        // deeplink 优先（4.2），auto* extras 其次（矩阵自动化）
        if (!handleDeeplink(intent)) {
            handleAutoExtras(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handleDeeplink(intent)) handleAutoExtras(intent)
    }

    // ── 档案（1.2）──

    private fun refreshProfileSpinner() {
        val list = profiles.list()
        val items = ArrayList<String>(list.size + 1)
        items.add("手动输入")
        list.forEach { items.add(it.displayName) }
        suppressProfileFill = true
        profileSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, items,
        )
        profileSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (suppressProfileFill) return
                if (position <= 0) return
                val p = profiles.list().getOrNull(position - 1) ?: return
                fillFromProfile(p)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        profileSpinner.setSelection(0, false)
        suppressProfileFill = false
    }

    private fun fillFromProfile(p: SourceProfile) {
        hostInput.setText(p.host)
        tokenInput.setText(p.token)
        tcpPortInput.setText(p.tcpPort.toString())
        udpPortInput.setText(p.udpPort.toString())
        val idx = (0 until backendSpinner.adapter.count)
            .firstOrNull { backendSpinner.adapter.getItem(it) == p.backend } ?: 0
        backendSpinner.setSelection(idx)
        statusText.text = "已回填档案：$p"
    }

    private fun deleteSelectedProfile() {
        val pos = profileSpinner.selectedItemPosition
        if (pos <= 0) {
            Toast.makeText(this, "先选择要删除的档案", Toast.LENGTH_SHORT).show()
            return
        }
        val p = profiles.list().getOrNull(pos - 1) ?: return
        profiles.delete(p.host, p.tcpPort, p.backend)
        refreshProfileSpinner()
        statusText.text = "已删除档案：${p.displayName}"
    }

    /** 连接成功（START_SESSION ACCEPTED）后 upsert 档案（同键更新 lastConnectedAt） */
    private fun saveProfileFromFields() {
        val host = hostInput.text.toString().ifBlank { return }
        val tcp = tcpPortInput.text.toString().toIntOrNull() ?: return
        profiles.upsert(
            SourceProfile(
                name = "",
                host = host,
                tcpPort = tcp,
                udpPort = udpPortInput.text.toString().toIntOrNull() ?: 22334,
                backend = backendSpinner.selectedItem?.toString() ?: "lan-direct",
                token = tokenInput.text.toString(),
                lastConnectedAt = System.currentTimeMillis(),
            ),
        )
    }

    // ── 档位（2.1）──

    /** 客户端当前真实屏幕尺寸（API30+ maximumWindowMetrics，降级 realDisplayMetrics） */
    private fun localScreenSize(): Pair<Int, Int> =
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val m = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            m.widthPixels to m.heightPixels
        }

    /** 档位短边尺寸按当前朝向映射（720p/1080p 档） */
    private fun tierSize(shortSide: Int): Pair<Int, Int> {
        val longSide = when (shortSide) {
            720 -> 1280
            else -> 1920
        }
        val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        return if (portrait) shortSide to longSide else longSide to shortSide
    }

    /** 解析 UI 档位为 (width,height)；自定义档非法输入返回 null 并提示 */
    private fun resolveTierUi(): Pair<Int, Int>? {
        return when (resolutionSpinner.selectedItemPosition) {
            1 -> tierSize(720)
            2 -> tierSize(1080)
            3 -> {
                val w = customWidth
                val h = customHeight
                if (!validCustom(w, h)) {
                    Toast.makeText(this, "自定义分辨率非法：需 width 320–2560、height 240–2560", Toast.LENGTH_LONG).show()
                    null
                } else {
                    w to h
                }
            }
            else -> localScreenSize() // 默认「跟随本机」
        }
    }

    private fun validCustom(w: Int, h: Int): Boolean = w in 320..2560 && h in 240..2560

    private fun showCustomResolutionDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        val wInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "宽 320–2560"
            if (customWidth > 0) setText(customWidth.toString())
        }
        val hInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "高 240–2560"
            if (customHeight > 0) setText(customHeight.toString())
        }
        box.addView(TextView(this).apply { text = "宽×高 = " })
        box.addView(wInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(TextView(this).apply { text = " × " })
        box.addView(hInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        android.app.AlertDialog.Builder(this)
            .setTitle("自定义分辨率（范围 width 320–2560、height 240–2560）")
            .setView(box)
            .setPositiveButton("确定") { d, _ ->
                val w = wInput.text.toString().toIntOrNull() ?: 0
                val h = hInput.text.toString().toIntOrNull() ?: 0
                if (validCustom(w, h)) {
                    customWidth = w
                    customHeight = h
                    resolutionItems[3] = "自定义 ${w}×$h"
                    (resolutionSpinner.adapter as ArrayAdapter<*>).notifyDataSetChanged()
                    Toast.makeText(this, "已设置自定义 ${w}×$h", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "非法输入（width 320–2560、height 240–2560），已回退跟随本机", Toast.LENGTH_LONG).show()
                    resolutionSpinner.setSelection(0)
                }
                d.dismiss()
            }
            .setNegativeButton("取消") { d, _ ->
                resolutionSpinner.setSelection(if (customWidth > 0) 3 else 0)
                d.dismiss()
            }
            .setOnCancelListener {
                resolutionSpinner.setSelection(if (customWidth > 0) 3 else 0)
            }
            .show()
    }

    // ── 连接流程 ──

    /** 矩阵自动化入口（run_matrix.py 经 am extras 下发，直接连接并观看） */
    private fun handleAutoExtras(intent: Intent?) {
        val autoHost = intent?.getStringExtra("autoHost") ?: return
        hostInput.setText(autoHost)
        intent.getStringExtra("autoBackend")?.let { b ->
            (0 until backendSpinner.adapter.count)
                .firstOrNull { backendSpinner.adapter.getItem(it) == b }
                ?.let { backendSpinner.setSelection(it) }
        }
        intent.getStringExtra("autoPackage")?.let { packageInput.setText(it) }
        connectAndWatch(
            autoWidth = intent.getIntExtra("autoWidth", 0),
            autoHeight = intent.getIntExtra("autoHeight", 0),
            autoFps = intent.getIntExtra("autoFps", 0),
            autoBitrate = intent.getIntExtra("autoBitrate", 0),
        )
    }

    /**
     * cloudphone://launch?host=&tcpPort=&udpPort=&backend=&token=&pkg=（4.2）：
     * 自动填充 + 落档案 + 静默连接 SINGLE_APP 直达；失败带原因留在连接页。
     * 返回 true 表示本 intent 是 launch deeplink 且已接管。
     */
    private fun handleDeeplink(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "cloudphone" || uri.host != "launch") return false
        val host = uri.getQueryParameter("host")
        if (host.isNullOrBlank()) {
            statusText.text = "直达失败：deeplink 缺 host 参数"
            return true
        }
        val tcp = uri.getQueryParameter("tcpPort")?.toIntOrNull() ?: 22333
        val udp = uri.getQueryParameter("udpPort")?.toIntOrNull() ?: 22334
        val backend = uri.getQueryParameter("backend") ?: "lan-direct"
        val token = uri.getQueryParameter("token") ?: ""
        val pkg = uri.getQueryParameter("pkg") ?: ""
        // 自动填充
        hostInput.setText(host)
        tokenInput.setText(token)
        tcpPortInput.setText(tcp.toString())
        udpPortInput.setText(udp.toString())
        (0 until backendSpinner.adapter.count)
            .firstOrNull { backendSpinner.adapter.getItem(it) == backend }
            ?.let { backendSpinner.setSelection(it) }
        if (pkg.isNotBlank()) packageInput.setText(pkg)
        // 落档案（连接失败也保留字段便于重试：spec「源端不可达」Scenario）
        profiles.upsert(
            SourceProfile("", host, tcp, udp, backend, token, System.currentTimeMillis()),
        )
        refreshProfileSpinner()
        // 静默连接 + SINGLE_APP 直达
        connectAndWatch(mode = WatchMode.SINGLE_APP, packageName = pkg, silent = true)
        return true
    }

    /**
     * 连接 → START_SESSION → 进渲染页。
     * 全部网络 I/O 在工作线程（主线程发网络抛 NetworkOnMainThreadException，见 device-notes §3）。
     */
    private fun connectAndWatch(
        mode: WatchMode? = null,
        packageName: String? = null,
        silent: Boolean = false,
        autoWidth: Int = 0,
        autoHeight: Int = 0,
        autoFps: Int = 0,
        autoBitrate: Int = 0,
    ) {
        statusText.text = "连接中…"
        val watchMode = mode ?: if (modeSpinner.selectedItem.toString() == "单应用") WatchMode.SINGLE_APP else WatchMode.FULLSCREEN
        val pkg = packageName ?: packageInput.text.toString()
        // 档位解析：auto*（矩阵通道）> UI 档位
        var w = autoWidth
        var h = autoHeight
        if (w <= 0 || h <= 0) {
            val tier = resolveTierUi() ?: run {
                statusText.text = "连接已拦截：自定义分辨率非法（width 320–2560、height 240–2560）"
                return
            }
            if (w <= 0) w = tier.first
            if (h <= 0) h = tier.second
        }
        val fps = if (autoFps > 0) autoFps else fpsSpinner.selectedItem.toString().toIntOrNull() ?: 60
        val bitrate = if (autoBitrate > 0) autoBitrate else (bitrateSpinner.selectedItem.toString().split(" ")[0].toIntOrNull() ?: 8) * 1000
        val cfg = ClientSession.Config(
            host = hostInput.text.toString().ifBlank { "10.0.2.2" },
            tcpPort = tcpPortInput.text.toString().toIntOrNull() ?: 22333,
            udpPort = udpPortInput.text.toString().toIntOrNull() ?: 22334,
            kind = when (backendSpinner.selectedItem.toString()) {
                "frp" -> BackendKind.FRP
                "p2p" -> BackendKind.P2P
                else -> BackendKind.LAN_DIRECT
            },
            token = tokenInput.text.toString().ifBlank { "cloudphone-m0" },
            useUdpVideo = useUdpCheck.isChecked,
        )
        Thread({
            val ok = ClientSession.connect(cfg, watchMode, pkg)
            if (!ok) {
                runOnUiThread {
                    statusText.text = if (silent) "直达失败：${ClientSession.lastError}（目标应用 $pkg）" else "失败：${ClientSession.lastError}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            val ack = ClientSession.startSession(watchMode, pkg, 0, w, h, fps, bitrate)
            runOnUiThread {
                if (ack == null || ack.status != HelloStatus.ACCEPTED) {
                    statusText.text = if (silent) {
                        "直达失败：${ack?.reason ?: ClientSession.lastError}（目标应用 $pkg）"
                    } else {
                        "会话发起失败：${ack?.reason ?: ClientSession.lastError}"
                    }
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                // 连接成功（START_SESSION ACCEPTED）→ 档案 upsert（1.2）
                saveProfileFromFields()
                refreshProfileSpinner()
                startActivity(Intent(this@ConnectActivity, DecodeRenderActivity::class.java))
            }
        }, "connect-ui").apply { start() }
    }
}
