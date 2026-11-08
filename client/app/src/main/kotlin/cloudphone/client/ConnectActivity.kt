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
 * 连接设置 Activity（4.3）：目标/凭据/后端/观看模式表单 → 建链握手 → 进入解码渲染。
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        root.addView(TextView(this).apply { text = "连接源端" })
        root.addView(TextView(this).apply { text = "源端地址（模拟器宿主环回 10.0.2.2 / 局域网 IP）" })
        hostInput = EditText(this).apply { setText("10.0.2.2") }
        root.addView(hostInput)

        root.addView(TextView(this).apply { text = "配对 token" })
        tokenInput = EditText(this).apply { setText("cloudphone-m0") }
        root.addView(tokenInput)

        root.addView(TextView(this).apply { text = "TCP 端口" })
        tcpPortInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("22333")
        }
        root.addView(tcpPortInput)

        root.addView(TextView(this).apply { text = "UDP 端口" })
        udpPortInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("22334")
        }
        root.addView(udpPortInput)

        root.addView(TextView(this).apply { text = "传输后端" })
        backendSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ConnectActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("lan-direct", "p2p", "frp"),
            )
        }
        root.addView(backendSpinner)

        root.addView(TextView(this).apply { text = "观看模式" })
        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ConnectActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("整屏", "单应用"),
            )
        }
        root.addView(modeSpinner)

        root.addView(TextView(this).apply { text = "单应用包名" })
        packageInput = EditText(this).apply { setText("cloudphone.loadgen") }
        root.addView(packageInput)

        useUdpCheck = CheckBox(this).apply { text = "UDP 视频通道（lan 直连默认关闭）"; isChecked = false }
        root.addView(useUdpCheck)

        val connectButton = Button(this).apply {
            text = "连接并观看"
            setOnClickListener { connectAndWatch() }
        }
        root.addView(connectButton)

        val drawerButton = Button(this).apply {
            text = "会话抽屉（挂机/切换）"
            setOnClickListener { startActivity(Intent(this@ConnectActivity, SessionDrawerActivity::class.java)) }
        }
        root.addView(drawerButton)

        statusText = TextView(this)
        root.addView(statusText)
        setContentView(root)

        // 矩阵自动化入口（run_matrix.py 经 am extras 下发，直接连接并观看）
        val autoHost = intent.getStringExtra("autoHost")
        if (autoHost != null) {
            hostInput.setText(autoHost)
            intent.getStringExtra("autoBackend")?.let { b ->
                (0 until backendSpinner.adapter.count)
                    .firstOrNull { backendSpinner.adapter.getItem(it) == b }
                    ?.let { backendSpinner.setSelection(it) }
            }
            intent.getStringExtra("autoPackage")?.let { packageInput.setText(it) }
            autoWidth = intent.getIntExtra("autoWidth", 0)
            autoHeight = intent.getIntExtra("autoHeight", 0)
            autoFps = intent.getIntExtra("autoFps", 0)
            autoBitrate = intent.getIntExtra("autoBitrate", 0)
            connectAndWatch()
        }
    }

    private var autoWidth = 0
    private var autoHeight = 0
    private var autoFps = 0
    private var autoBitrate = 0

    private fun connectAndWatch() {
        statusText.text = "连接中…"
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
        val mode = if (modeSpinner.selectedItem.toString() == "单应用") WatchMode.SINGLE_APP else WatchMode.FULLSCREEN
        Thread({
            val ok = ClientSession.connect(cfg, mode, packageInput.text.toString())
            if (!ok) {
                runOnUiThread {
                    statusText.text = "失败：${ClientSession.lastError}"
                    Toast.makeText(this, ClientSession.lastError, Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            // 全屏模式需先发起会话（单应用同理）；p2p 回答已在握手中触发打洞
            // 注意：全部网络 I/O 保持在本工作线程（主线程发网络会抛 NetworkOnMainThreadException）
            val ack = ClientSession.startSession(
                mode, packageInput.text.toString(), 0,
                autoWidth, autoHeight, autoFps, autoBitrate,
            )
            runOnUiThread {
                if (ack == null || ack.status != HelloStatus.ACCEPTED) {
                    statusText.text = "会话发起失败：${ack?.reason ?: ClientSession.lastError}"
                    return@runOnUiThread
                }
                startActivity(Intent(this@ConnectActivity, DecodeRenderActivity::class.java))
            }
        }, "connect-ui").apply { start() }
    }
}
