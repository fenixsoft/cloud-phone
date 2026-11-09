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
import cloudphone.client.discovery.LanScanner
import cloudphone.protocol.HelloStatus
import cloudphone.protocol.TransportKind
import cloudphone.protocol.WatchMode
import cloudphone.protocol.decodeControlMessage
import cloudphone.protocol.encodeControlMessage
import cloudphone.transport.BackendKind
import cloudphone.transport.CryptoAlgo
import cloudphone.transport.CryptoCodec
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
        /** 端到端加密意图（p2p-crossnet-traversal 3.2，缺省开；对端不支持自动回落明文） */
        val encryptOn: Boolean = true,
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

    /** 最近一次 InputEvent 发出时刻（elapsedRealtime ms；触摸延迟插桩 ⑥ sinceTouchMs 依据） */
    @Volatile var lastInputSentAtMs: Long = 0
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
    // 最近一次被接受的会话档位（重连 resume 失败时按原参数重开）
    @Volatile var lastAcceptedWidth = 0
    @Volatile var lastAcceptedHeight = 0
    @Volatile var lastAcceptedFps = 0
    @Volatile var lastAcceptedKbps = 0
    // 有界缓冲（避免 SynchronousQueue 无阻塞 offer 在 poll 未就绪时丢 ack 的竞态）
    private val ackQueue = java.util.concurrent.LinkedBlockingQueue<ControlMessage.HelloAck>(8)

    /** 本端加密开关意图（cfg.encryptOn 快照；negotiation 前置材料） */
    @Volatile private var encryptOn = true

    /**
     * 加密协商结果（p2p-crossnet-traversal 3.2）：AES128GCM = 双端开启且对端支持；
     * NONE = 明文（对端老版本/任一侧关闭）。UI 以此展示「加密：AES-128-GCM / 未加密」。
     */
    @Volatile var negotiatedCryptoAlgo: CryptoAlgo = CryptoAlgo.NONE
        private set

    /** 本次握手的客户端 nonce（handleControl 读线程据 ack.cryptoNonce 派生密钥用） */
    @Volatile private var pendingCryptoNonce: ByteArray? = null

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
            encryptOn = cfg.encryptOn
            // 每次连接重新协商：清上一连接的密钥态（新握手新 nonce 新子钥）
            negotiatedCryptoAlgo = CryptoAlgo.NONE
            pendingCryptoNonce = null
            val ok = connectOnce() && handshake(mode, packageName)
            if (ok) {
                lastPongAtMs = SystemClock.elapsedRealtime()
                startControlWatchdog()
            }
            return ok
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

    /** HELLO 握手（token 配对 ≤500ms；p2p 附多候选 offer，收 answer 触发打洞；附加密协商请求） */
    fun handshake(mode: WatchMode, packageName: String): Boolean {
        val b = backend ?: return false
        val p2p = b as? P2pBackend
        val offer = if (p2p != null) {
            // 等本端候选发现（SRFLX 多节点 + 本地多网卡，≤2s），拿到则附多候选 offer 文本
            val deadline = System.currentTimeMillis() + 2000
            while (p2p.localCandidates.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            p2p.localCandidatesText()
        } else ""
        // 加密协商请求：encryptOn 时携 AES128_GCM + 32B 客户端 nonce（每次握手重新生成）
        val clientNonce = if (encryptOn) CryptoCodec.generateHandshakeNonce() else ByteArray(0)
        pendingCryptoNonce = if (encryptOn) clientNonce else null
        val hello = ControlMessage.Hello(
            version = 1,
            token = config?.token ?: "",
            mode = mode,
            packageName = packageName,
            width = 0, height = 0, fps = 0, bitrateKbps = 0,
            sessionId = ClientSession.currentSessionId,
            p2pOffer = offer,
            encryptOn = encryptOn,
            cryptoAlgo = if (encryptOn) CryptoAlgo.AES128_GCM else CryptoAlgo.NONE,
            cryptoNonce = clientNonce,
        )
        while (ackQueue.poll() != null) Unit // 清残留
        sendControl(hello)
        android.util.Log.d(
            "cloudphone-client",
            "HELLO sent (mode=$mode offer=${offer.isNotBlank()} encryptOn=$encryptOn)",
        )
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
            if (ack.p2pAnswer.contains(',') || ack.p2pAnswer.contains(';')) {
                p2p.onPeerCandidates(ack.p2pAnswer)
            } else {
                StunClient.parseSrflx(ack.p2pAnswer)?.let { p2p.onPeerSrflx(it) }
            }
        }
        // 协商结果落态（enableCrypto 已在读线程完成，见 handleControl）
        negotiatedCryptoAlgo = if (encryptOn) ack.cryptoAlgo else CryptoAlgo.NONE
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
            if (sessionId == 0) {
                currentMode = mode
                // 记录最近一次被接受的真实档位：resume 被拒时用原参数重开全新会话
                if (width > 0) {
                    lastAcceptedWidth = width
                    lastAcceptedHeight = height
                    lastAcceptedFps = fps
                    lastAcceptedKbps = bitrateKbps
                }
            }
        }
        return ack
    }

    fun stopSession(sessionId: Int) {
        sendControl(ControlMessage.StopSession(sessionId))
        if (sessionId == currentSessionId) currentSessionId = 0
    }

    fun sendControl(message: ControlMessage) {
        if (message is ControlMessage.InputEvent) {
            // 触摸链插桩：记录最近触摸发出时刻（⑥ sinceTouchMs 依据）
            lastInputSentAtMs = SystemClock.elapsedRealtime()
        }
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
                // 加密协商接受（3.2）：ack 同步读线程处理——握手上层线程尚未从 ackQueue
                // 取走 ack，源端 enableCrypto 后发来的首批加密记录即刻可解（竞态窗口最小化）
                if (msg.status == HelloStatus.ACCEPTED && msg.cryptoAlgo == CryptoAlgo.AES128_GCM &&
                    encryptOn && msg.cryptoNonce.size == CryptoCodec.HANDSHAKE_NONCE_BYTES
                ) {
                    val nonce = pendingCryptoNonce
                    val cfg = config
                    if (nonce != null && cfg != null) {
                        val codec = CryptoCodec.negotiate(Role.CLIENT, cfg.token, nonce, msg.cryptoNonce)
                        backend?.enableCrypto(codec)
                    }
                }
                ackQueue.offer(msg)
            }
            is ControlMessage.P2pMigrate -> {
                // 端点迁移（2.3）：对端网络切换推送新候选；会话/统计不重置
                (backend as? P2pBackend)?.onPeerCandidates(msg.candidates)
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
                lastPongAtMs = SystemClock.elapsedRealtime()
                val rtt = (nowUs() - msg.senderUs) / 1000
                if (rtt in 0..10000) android.util.Log.i("cloudphone-latency", "rtt Ms=$rtt")
            }
            is ControlMessage.AppListMessage -> onAppList?.invoke(msg)
            is ControlMessage.Unknown -> Unit // 未知消息忽略且不中断会话
            else -> Unit
        }
    }

    // ── 断线重连 ≤1s ──

    // 控制通道保活看门狗：静默 TCP 死亡检测。实测 adb forward 半开连接下客户端写入
    // 0ms 完成但对端永不到达（无 FIN/RST 可感知，InputEvent 全部黑洞化），
    // 复用 PING/PONG：>6s 无应答即掐死重连（resume 语义恢复挂机）
    @Volatile private var lastPongAtMs = 0L
    @Volatile private var lastPingSentAtMs = 0L
    private val watchdogStarted = AtomicBoolean(false)

    private fun startControlWatchdog() {
        if (!watchdogStarted.compareAndSet(false, true)) return
        Thread({
            while (true) {
                Thread.sleep(2000)
                if (!connected) continue
                val now = SystemClock.elapsedRealtime()
                if (now - lastPingSentAtMs >= 2000) {
                    lastPingSentAtMs = now
                    sendControl(ControlMessage.Ping(nonce = now, senderUs = nowUs()))
                }
                if (lastPongAtMs > 0 && now - lastPongAtMs > 6000) {
                    android.util.Log.w(
                        "cloudphone-client",
                        "控制通道 >6s 无 Pong（静默失活）→ 掐死重连",
                    )
                    lastPongAtMs = now // 防抖：同一死通道只触发一次重连
                    backend?.close()
                    connected = false
                    // 退避重连已在跑时不重复触发（重连成功会刷新 lastPongAtMs）
                    if (!reconnecting.get()) scheduleReconnect()
                }
            }
        }, "client-keepalive").apply { isDaemon = true; start() }
    }

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
                while (!connected && attempt < 12) {
                    // 指数退避（1s→2s→4s→6s cap，±20% 抖动）：0.2s 连发曾自我放大成
                    // 连接风暴，把 frp 隧道/模拟器 accept 打僵后重连全部落空
                    val base = longArrayOf(1000, 2000, 4000, 6000)[attempt.coerceAtMost(3)]
                    val jitter = (base * (8 + (0..4).random())) / 10
                    Thread.sleep(jitter)
                    if (connectOnce()) {
                        if (handshake(currentMode, currentPackage)) {
                            // attach 恢复挂机会话（源端 resume + IDR ≤1s 出画）
                            val ack = startSession(
                                currentMode, currentPackage,
                                sessionId = target, width = 0, height = 0, fps = 0, bitrateKbps = 0,
                            )
                            if (ack?.status == HelloStatus.ACCEPTED) {
                                lastPongAtMs = SystemClock.elapsedRealtime() // 防止 watchdog 对新通道误判再重连一轮
                                return@Thread
                            }
                            // resume 被拒（会话已被源端回收等）→ 带原参数开全新会话兜底
                            val fresh = startSession(
                                currentMode, currentPackage,
                                sessionId = 0,
                                width = lastAcceptedWidth, height = lastAcceptedHeight,
                                fps = lastAcceptedFps, bitrateKbps = lastAcceptedKbps,
                            )
                            if (fresh?.status == HelloStatus.ACCEPTED) {
                                lastPongAtMs = SystemClock.elapsedRealtime() // 同上
                                return@Thread
                            }
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
 * 连接首页（client-ui-redesign 2.1/2.2，design D2）：
 * 单 Activity 多区块滚动（ScrollView + 动态挂载 item_device_card.xml，零新依赖）——
 * 顶栏（设置/会话抽屉入口）+ 网络状态徽章 + 扫描面板（三态：扫描中/有发现/未发现）+
 * 发现列表 + 档案列表 + 常驻「新建连接」入口。
 *
 * 保留既有行为通路：
 * - cloudphone://launch deeplink：自动落档案 + 静默连接直达（app-shortcuts 既有语义）；
 * - auto* extras 矩阵自动化通道（run_matrix.py）优先级不变；
 * - 连接成功（START_SESSION ACCEPTED）自动 upsert 档案（source-profiles 既有规则）。
 * LAUNCHER 入口（MainActivity 退役，状态总览职责并入首页/控制台）。
 */
class ConnectActivity : Activity() {

    private lateinit var netBadge: TextView
    private lateinit var scanProgress: android.widget.ProgressBar
    private lateinit var scanStatus: TextView
    private lateinit var scanCount: TextView
    private lateinit var emptyState: android.view.View
    private lateinit var discoveredSection: TextView
    private lateinit var discoveredList: LinearLayout
    private lateinit var profilesList: LinearLayout
    private lateinit var noProfiles: TextView
    private lateinit var connStatus: TextView

    private val profiles by lazy { SourceProfiles(this) }
    private var scanner: LanScanner? = null
    private var scanGeneration = 0
    private var scannedOnce = false
    private var lastDiscovered: List<LanScanner.DiscoveredHost> = emptyList()
    private var lastScanSubnets: List<String> = emptyList()
    private val connectInFlight = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)
        bindViews()
        wireActions()
        renderProfiles()
        // 进入首页自动扫描一次（仅一次，其余手动重试——design Risks）
        startScan()
        // deeplink 优先（4.2），auto* extras 其次（矩阵自动化）——与既有行为一致
        if (!handleDeeplink(intent)) {
            handleAutoExtras(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handleDeeplink(intent)) handleAutoExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        renderProfiles()
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner?.cancel()
    }

    private fun bindViews() {
        netBadge = findViewById(R.id.net_badge)
        scanProgress = findViewById(R.id.scan_progress)
        scanStatus = findViewById(R.id.scan_status)
        scanCount = findViewById(R.id.scan_count)
        emptyState = findViewById(R.id.empty_state)
        discoveredSection = findViewById(R.id.section_discovered)
        discoveredList = findViewById(R.id.discovered_list)
        profilesList = findViewById(R.id.profiles_list)
        noProfiles = findViewById(R.id.no_profiles)
        connStatus = findViewById(R.id.conn_status)
    }

    private fun wireActions() {
        findViewById<TextView>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.btn_sessions).setOnClickListener {
            startActivity(Intent(this, SessionDrawerActivity::class.java))
        }
        findViewById<TextView>(R.id.btn_rescan).setOnClickListener { startScan() }
        findViewById<TextView>(R.id.btn_new_connection).setOnClickListener { openManualSheet() }
        findViewById<TextView>(R.id.btn_empty_new).setOnClickListener { openManualSheet() }
    }

    private fun openManualSheet() {
        val sheet = cloudphone.client.ui.ManualConnectSheet()
        sheet.onSaved = { renderProfiles() }
        sheet.show(fragmentManager, "manual_connect")
    }

    // ── 扫描三态（2.2） ──

    private fun startScan() {
        scanner?.cancel()
        val gen = ++scanGeneration
        val port = SettingsPrefs.defaultTcpPort(this)
        val s = LanScanner(this, port)
        scanner = s
        lastScanSubnets = s.subnetLabels()
        scannedOnce = false
        lastDiscovered = emptyList()
        // 扫描中态
        badge(netBadge, "扫描中 · ${lastScanSubnets.firstOrNull() ?: "?"}", R.drawable.bg_badge_warn, R.color.hud_warn)
        scanProgress.progress = 0
        scanStatus.text = "正在扫描（TCP :$port）…"
        scanCount.text = "已探测 0"
        emptyState.visibility = android.view.View.GONE
        discoveredList.visibility = android.view.View.GONE
        discoveredSection.visibility = android.view.View.GONE
        discoveredList.removeAllViews()
        s.scan(
            onProgress = { done, total ->
                runOnUiThread {
                    if (gen != scanGeneration) return@runOnUiThread
                    scanProgress.max = total
                    scanProgress.progress = done
                    scanCount.text = "已探测 $done/$total"
                }
            },
            onHostFound = {
                runOnUiThread {
                    if (gen != scanGeneration) return@runOnUiThread
                    scanStatus.text = "发现源端 ${it.host}:${it.port}"
                }
            },
            onComplete = { hosts, elapsedMs ->
                runOnUiThread {
                    if (gen != scanGeneration) return@runOnUiThread
                    onScanComplete(hosts, elapsedMs)
                }
            },
        )
    }

    private fun onScanComplete(hosts: List<LanScanner.DiscoveredHost>, elapsedMs: Long) {
        scannedOnce = true
        lastDiscovered = hosts
        scanProgress.max = 100
        scanProgress.progress = 100
        scanCount.text = "发现 ${hosts.size} 台"
        scanStatus.text = if (hosts.isEmpty()) {
            "扫描完成（${elapsedMs}ms）— 未发现监听 :${SettingsPrefs.defaultTcpPort(this)} 的源端"
        } else {
            "扫描完成（${elapsedMs}ms）"
        }
        if (hosts.isEmpty()) {
            badge(netBadge, "未发现源端", R.drawable.bg_badge_danger, R.color.hud_danger)
            emptyState.visibility = android.view.View.VISIBLE
        } else {
            badge(netBadge, "局域网 · ${lastScanSubnets.firstOrNull() ?: "?"}", R.drawable.bg_badge_ok, R.color.hud_ok)
            emptyState.visibility = android.view.View.GONE
        }
        renderDiscovered()
        renderProfiles()
    }

    /** 发现列表：发现卡含名称/在线徽章/地址/后端标识/上次连接信息；按 host+port 匹配档案标「已保存」 */
    private fun renderDiscovered() {
        discoveredList.removeAllViews()
        if (lastDiscovered.isEmpty()) {
            discoveredSection.visibility = android.view.View.GONE
            discoveredList.visibility = android.view.View.GONE
            return
        }
        discoveredSection.text = "发现的源端（${lastDiscovered.size} 台）"
        discoveredSection.visibility = android.view.View.VISIBLE
        discoveredList.visibility = android.view.View.VISIBLE
        for (d in lastDiscovered) {
            val p = profiles.findByHostPort(d.host, d.port)
            val card = layoutInflater.inflate(R.layout.item_device_card, discoveredList, false)
            card.findViewById<TextView>(R.id.card_name).text =
                p?.name?.takeIf { it.isNotBlank() } ?: "源端 ${d.host}"
            card.findViewById<TextView>(R.id.card_addr).text = "${d.host}:${d.port}"
            badge(
                card.findViewById(R.id.card_badge), "在线",
                R.drawable.bg_badge_ok, R.color.hud_ok,
            )
            // 后端标识：匹配档案显示档案后端，否则默认 lan-direct（探测不握手，无法探测真实后端）
            val backendLabel = card.findViewById<TextView>(R.id.card_backend)
            if (p != null) {
                backendLabel.text = "已保存 · ${p.backend}"
                badge(backendLabel, "已保存 · ${p.backend}", R.drawable.bg_badge_accent, R.color.hud_accent)
            } else {
                backendLabel.text = "lan-direct"
                badge(backendLabel, "lan-direct", R.drawable.bg_badge_accent, R.color.hud_accent)
            }
            card.findViewById<TextView>(R.id.card_last).text =
                p?.let { "上次连接 ${fmtTime(it.lastConnectedAt)}" } ?: "未连接过"
            // 发现卡动作集：连接 + 单应用（不自动写档案）
            val cfg = cfgOf(d.host, d.port, p)
            card.findViewById<TextView>(R.id.btn_card_connect).setOnClickListener {
                connectAndEnter(cfg)
            }
            card.findViewById<TextView>(R.id.btn_card_apps).setOnClickListener {
                connectAndEnter(cfg, thenIntent = Intent(this, SourceAppsActivity::class.java))
            }
            card.findViewById<TextView>(R.id.btn_card_delete).visibility = android.view.View.GONE
            discoveredList.addView(card)
        }
    }

    // ── 档案列表（2.1/2.2） ──

    private fun renderProfiles() {
        val list = profiles.list()
        profilesList.removeAllViews()
        noProfiles.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<TextView>(R.id.section_profiles).text = "手动保存的连接（${list.size} 个）"
        for (p in list) {
            val card = layoutInflater.inflate(R.layout.item_device_card, profilesList, false)
            card.findViewById<TextView>(R.id.card_name).text =
                p.name.takeIf { it.isNotBlank() } ?: p.host
            card.findViewById<TextView>(R.id.card_addr).text = "${p.host}:${p.tcpPort}"
            // 档案卡状态：本次扫描无响应 → 「无响应」弱化 + 连接禁用；其余 → 「已保存」
            val reachable = reachableByLastScan(p.host, p.tcpPort)
            val connectBtn = card.findViewById<TextView>(R.id.btn_card_connect)
            val badgeView = card.findViewById<TextView>(R.id.card_badge)
            if (scannedOnce && !reachable) {
                badge(badgeView, "无响应", R.drawable.bg_badge_danger, R.color.hud_danger)
                card.alpha = 0.55f // 视觉弱化
                connectBtn.isEnabled = false // 不可达禁用态
                connectBtn.alpha = 0.5f
                connectBtn.setOnClickListener(null)
                connectBtn.isClickable = false
            } else {
                badge(badgeView, "已保存", R.drawable.bg_badge_accent, R.color.hud_accent)
                connectBtn.setOnClickListener {
                    connectAndEnter(cfgOf(p.host, p.tcpPort, p))
                }
            }
            card.findViewById<TextView>(R.id.card_backend).text = p.backend
            badge(
                card.findViewById(R.id.card_backend), p.backend,
                R.drawable.bg_badge_accent, R.color.hud_accent,
            )
            card.findViewById<TextView>(R.id.card_last).text =
                "token ${if (p.token.isBlank()) "默认" else "已设置"} · ${p.originLabel} · ${fmtTime(p.lastConnectedAt)}"
            // 档案卡动作集：连接 + 删除（删除始终可用），无「单应用」
            card.findViewById<TextView>(R.id.btn_card_apps).visibility = android.view.View.GONE
            card.findViewById<TextView>(R.id.btn_card_delete).setOnClickListener {
                profiles.delete(p.host, p.tcpPort, p.backend)
                Toast.makeText(this, "已删除：${p.name.ifBlank { p.host }}", Toast.LENGTH_SHORT).show()
                renderProfiles()
            }
            profilesList.addView(card)
        }
    }

    /** 本次扫描口径下档案是否可达：未扫描/非本网段档案（frp 公网等）不参与无响应判定 */
    private fun reachableByLastScan(host: String, port: Int): Boolean {
        if (!scannedOnce) return true
        val prefix = host.substringBeforeLast('.', "")
        if (prefix.isEmpty() || lastScanSubnets.none { it.startsWith(prefix) }) return true
        return lastDiscovered.any { it.host == host && it.port == port }
    }

    // ── 连接流程（保留既有语义） ──

    /** 组装连接配置：档案优先（token/udp/encryptOn），新发现源端走既有默认 token 规则 */
    private fun cfgOf(host: String, tcpPort: Int, p: SourceProfile?): ClientSession.Config =
        ClientSession.Config(
            host = host,
            tcpPort = tcpPort,
            udpPort = p?.udpPort ?: SettingsPrefs.defaultUdpPort(this),
            kind = kindOf(p?.backend ?: "lan-direct"),
            token = p?.token ?: "",
            useUdpVideo = false,
            encryptOn = p?.encryptOn ?: true,
        )

    private fun kindOf(backend: String): BackendKind = when (backend) {
        "frp" -> BackendKind.FRP
        "p2p" -> BackendKind.P2P
        else -> BackendKind.LAN_DIRECT
    }

    private fun backendString(kind: BackendKind): String =
        kind.name.lowercase().replace('_', '-')

    /**
     * 连接 → START_SESSION → 进入目标页（控制台 / 应用列表 / 单应用直达）。
     * 网络 I/O 全在工作线程（主线程发网络抛 NetworkOnMainThreadException）。
     */
    private fun connectAndEnter(
        cfg: ClientSession.Config,
        mode: WatchMode = WatchMode.FULLSCREEN,
        pkg: String = "",
        thenIntent: Intent? = null,
        silent: Boolean = false,
        autoW: Int = 0,
        autoH: Int = 0,
        autoFps: Int = 0,
        autoKbps: Int = 0,
    ) {
        if (!connectInFlight.compareAndSet(false, true)) {
            Toast.makeText(this, "连接流程进行中", Toast.LENGTH_SHORT).show()
            return
        }
        connStatus.visibility = android.view.View.VISIBLE
        connStatus.text = "连接中…"
        Thread({
            val ok = ClientSession.connect(cfg, mode, pkg)
            if (!ok) {
                runOnUiThread {
                    connectInFlight.set(false)
                    connStatus.text = "连接失败：${ClientSession.lastError}"
                    Toast.makeText(this, "连接失败：${ClientSession.lastError}", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            val tier = defaultTier()
            val w = if (autoW > 0) autoW else tier.first
            val h = if (autoH > 0) autoH else tier.second
            val fps = if (autoFps > 0) autoFps else SettingsPrefs.fps(this)
            val kbps = if (autoKbps > 0) autoKbps else SettingsPrefs.bitrateMbps(this) * 1000
            val ack = ClientSession.startSession(mode, pkg, 0, w, h, fps, kbps)
            runOnUiThread {
                connectInFlight.set(false)
                if (ack == null || ack.status != HelloStatus.ACCEPTED) {
                    val reason = ack?.reason?.takeIf { it.isNotBlank() } ?: ClientSession.lastError
                    connStatus.text = if (silent) "直达失败：$reason" else "会话发起失败：$reason"
                    Toast.makeText(this, connStatus.text, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                // 连接成功（START_SESSION ACCEPTED）→ 档案 upsert（source-profiles 既有规则）
                saveProfileAfterConnect(cfg)
                renderProfiles()
                connStatus.visibility = android.view.View.GONE
                startActivity(thenIntent ?: Intent(this, DecodeRenderActivity::class.java))
            }
        }, "connect-ui").apply { isDaemon = true; start() }
    }

    /** 连接成功自动入库：同键更新 lastConnectedAt，保留档案名与原来源标记；新档案 origin=auto */
    private fun saveProfileAfterConnect(cfg: ClientSession.Config) {
        val backend = backendString(cfg.kind)
        val existing = profiles.findByKey(cfg.host, cfg.tcpPort, backend)
        profiles.upsert(
            SourceProfile(
                name = existing?.name ?: "",
                host = cfg.host,
                tcpPort = cfg.tcpPort,
                udpPort = cfg.udpPort,
                backend = backend,
                token = cfg.token,
                lastConnectedAt = System.currentTimeMillis(),
                encryptOn = cfg.encryptOn,
                origin = existing?.origin ?: SourceProfile.ORIGIN_AUTO,
            ),
        )
    }

    // ── 档位默认值（resolution-passthrough 既有语义：档位随 START_SESSION 下发） ──

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

    /** 设置页「串流档位默认值」→ (width,height)（4.3：下次连接生效） */
    private fun defaultTier(): Pair<Int, Int> = when (SettingsPrefs.resolutionTier(this)) {
        1 -> tierSize(720)
        2 -> tierSize(1080)
        else -> localScreenSize() // 默认「跟随本机」
    }

    // ── deeplink 与矩阵自动化（既有通路，行为不变） ──

    /**
     * cloudphone://launch?host=&tcpPort=&udpPort=&backend=&token=&pkg=（既有 4.2）：
     * 自动落档案 + 静默连接直达；pkg 非空走 SINGLE_APP 单应用直达，失败带原因留在首页。
     * 返回 true 表示本 intent 是 launch deeplink 且已接管。
     */
    private fun handleDeeplink(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "cloudphone" || uri.host != "launch") return false
        val host = uri.getQueryParameter("host")
        if (host.isNullOrBlank()) {
            connStatus.visibility = android.view.View.VISIBLE
            connStatus.text = "直达失败：deeplink 缺 host 参数"
            return true
        }
        val tcp = uri.getQueryParameter("tcpPort")?.toIntOrNull() ?: 22333
        val udp = uri.getQueryParameter("udpPort")?.toIntOrNull() ?: SourceProfile.DEFAULT_UDP_PORT
        val backend = uri.getQueryParameter("backend") ?: "lan-direct"
        val token = uri.getQueryParameter("token") ?: ""
        val pkg = uri.getQueryParameter("pkg") ?: ""
        // 落档案（连接失败也保留字段便于重试：spec「源端不可达」Scenario）
        profiles.upsert(
            SourceProfile("", host, tcp, udp, backend, token, System.currentTimeMillis(),
                encryptOn = true, origin = SourceProfile.ORIGIN_AUTO),
        )
        renderProfiles()
        val cfg = ClientSession.Config(host, tcp, udp, kindOf(backend), token, false, true)
        val mode = if (pkg.isNotBlank()) WatchMode.SINGLE_APP else WatchMode.FULLSCREEN
        val then = if (pkg.isNotBlank()) {
            Intent(this, DecodeRenderActivity::class.java)
                .putExtra("mode", "single")
                .putExtra("appName", pkg)
                .putExtra("pkg", pkg)
        } else {
            null
        }
        connectAndEnter(cfg, mode, pkg, thenIntent = then, silent = true)
        return true
    }

    /** 矩阵自动化入口（run_matrix.py 经 am extras 下发，直接连接并观看——通道保持不变） */
    private fun handleAutoExtras(intent: Intent?) {
        val autoHost = intent?.getStringExtra("autoHost") ?: return
        val backend = intent.getStringExtra("autoBackend") ?: "lan-direct"
        val pkg = intent.getStringExtra("autoPackage") ?: "cloudphone.loadgen"
        val cfg = ClientSession.Config(
            host = autoHost,
            tcpPort = SettingsPrefs.defaultTcpPort(this),
            udpPort = SettingsPrefs.defaultUdpPort(this),
            kind = kindOf(backend),
            token = "cloudphone-m0",
            useUdpVideo = false,
            encryptOn = true,
        )
        connectAndEnter(
            cfg,
            WatchMode.FULLSCREEN,
            pkg,
            silent = true,
            autoW = intent.getIntExtra("autoWidth", 0),
            autoH = intent.getIntExtra("autoHeight", 0),
            autoFps = intent.getIntExtra("autoFps", 0),
            autoKbps = intent.getIntExtra("autoBitrate", 0),
        )
    }

    // ── 小工具 ──

    private fun badge(view: TextView, text: String, bgRes: Int, colorRes: Int) {
        view.text = text
        view.setBackgroundResource(bgRes)
        view.setTextColor(resources.getColor(colorRes, theme))
    }

    private fun fmtTime(epochMs: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochMs))
}
