package cloudphone.desktop.session

import cloudphone.protocol.ControlMessage
import cloudphone.protocol.Framing
import cloudphone.protocol.HelloStatus
import cloudphone.protocol.TransportKind
import cloudphone.protocol.WatchMode
import cloudphone.transport.BackendKind
import cloudphone.transport.CryptoAlgo
import cloudphone.transport.CryptoCodec
import cloudphone.transport.FrpBackend
import cloudphone.transport.LanDirectBackend
import cloudphone.transport.P2pBackend
import cloudphone.transport.Role
import cloudphone.transport.StreamEvent
import cloudphone.transport.TransportBackend
import cloudphone.transport.TransportConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 会话阶段（SessionScreen 状态投影源头） */
enum class SessionPhase {
    /** 空闲（未连接） */
    IDLE,

    /** 建链中（TCP/打洞） */
    CONNECTING,

    /** HELLO 握手/START_SESSION 进行中 */
    HANDSHAKING,

    /** 会话中（视频消费中） */
    STREAMING,

    /** 挂起（视频消费停止，控制链保持——spec「挂起/恢复」语义） */
    SUSPENDED,

    /** 已断开（用户主动断开或重连放弃） */
    DISCONNECTED,

    /** 失败（握手被拒/连接失败等明确原因） */
    FAILED,
}

/**
 * 会话发起参数（首页表单/档案卡投影；档位由 UI 解析为具体值后填入——
 * 「跟随本机」语义在 UI 层取本机屏幕尺寸，控制器只收具体四元组）。
 */
data class SessionParams(
    val host: String,
    val tcpPort: Int,
    val udpPort: Int = 22334,
    val kind: BackendKind = BackendKind.LAN_DIRECT,
    val token: String,
    val useUdpVideo: Boolean = true,
    /** 端到端加密意图（缺省开；对端老版本自动回落明文，对齐 Android ClientSession） */
    val encryptOn: Boolean = true,
    val mode: WatchMode = WatchMode.FULLSCREEN,
    val packageName: String = "",
    /** 附着/重连目标的挂机会话 id（0=新会话） */
    val sessionId: Int = 0,
    /** 档位四元组（width/height 0 表示由 HELLO 携带的占位——START_SESSION 必须携带具体档位） */
    val width: Int = 1080,
    val height: Int = 1920,
    val fps: Int = 60,
    val bitrateKbps: Int = 8000,
    /** 抢占请求：源端正被其他设备观看时踢掉对方接管（用户显式意图，BUSY 自动重试即置位） */
    val takeover: Boolean = false,
)

/** HUD 状态投影（spec：fps/码率/RTT/丢包/解码实际分辨率/后端+加密徽章） */
data class HudState(
    val fps: Double = 0.0,
    val bitrateKbps: Double = 0.0,
    val rttMs: Double = 0.0,
    val lossPct: Double = 0.0,
    /** 解码实际分辨率（取 VideoPipeline 解码输出值，非 START_SESSION 请求值） */
    val decodedWidth: Int = 0,
    val decodedHeight: Int = 0,
    /** 线上路径徽章（lan-direct/p2p/frp，p2p 回落后为 P2P_FALLBACK_FRP） */
    val wireKind: TransportKind? = null,
    /** 加密徽章：AES-128-GCM 协商成功 */
    val encrypted: Boolean = false,
)

/** 串流档位（工具坞档位循环与自定义档位的公共载体） */
data class StreamPreset(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val label: String,
) {
    val isCustom: Boolean get() = label == CUSTOM_LABEL

    companion object {
        const val CUSTOM_LABEL = "自定义"

        val P1080P_60 = StreamPreset(1080, 1920, 60, 8000, "1080P·60")
        val P720P_60 = StreamPreset(720, 1280, 60, 8000, "720P·60")
        val P1080P_30 = StreamPreset(1080, 1920, 30, 8000, "1080P·30")
    }
}

/**
 * 档位循环序列（spec/客户端界面壳既有语义）：1080P·60 → 720P·60 → 1080P·30 → 自定义 → 回 1080P·60。
 * 「自定义」取用户表单的自定义档位（未设置过则回 1080P·60）。
 */
object PresetCycle {
    val SEQUENCE = listOf("1080P·60", "720P·60", "1080P·30", StreamPreset.CUSTOM_LABEL)

    /** 档位循环切换的下一档（[currentLabel] 为当前档标签，[custom] 为用户自定义档） */
    fun next(currentLabel: String?, custom: StreamPreset?): StreamPreset {
        val idx = currentLabel?.let { SEQUENCE.indexOf(it) } ?: -1
        return when (idx + 1) {
            1 -> StreamPreset.P720P_60
            2 -> StreamPreset.P1080P_30
            3 -> custom ?: StreamPreset.P1080P_60
            else -> StreamPreset.P1080P_60
        }
    }
}

/**
 * 桌面端会话编排（design D7）：连接 → HELLO → START_SESSION → 视频/音频/输入全双工会话。
 *
 * - protocol 后端回调线程经 Channel 桥进协程（回调线程只做入队，不执行任何阻塞逻辑）；
 * - 挂起 = 停止视频消费保持控制链（spec「挂起/恢复」；恢复重发 IDR 重新拉流）——
 *   与 Android 端「断链挂机」不同：桌面端挂起不断链，断开才终止源端会话；
 * - 档位循环 = 以新档位重发整屏 START_SESSION（sessionId=0 新档位会话，控制链不断开）；
 * - 断开 = STOP_SESSION + 关闭传输（spec「断开 SHALL 回首页且会话在源端正确终止」）；
 * - 断线自动重连（对齐 Android ClientSession 断线 ≤1s 重连）：重连后先 attach 原会话，
 *   被拒则带原档位开全新会话兜底。
 */
class SessionController(
    /** 会话作用域（调用方可注入测试作用域） */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** 传输后端工厂（测试注入 loopback 后端；默认按 BackendKind 构造 protocol 三后端） */
    private val backendFactory: (TransportConfig) -> TransportBackend = ::defaultBackendFactory,
    /** HELLO 应答等待（源端目标 ≤500ms 回复；客户端等待窗口取 2s，对齐 Android） */
    private val helloTimeoutMs: Long = 2_000,
    /** START_SESSION 应答等待（对齐 Android 3s） */
    private val startAckTimeoutMs: Long = 3_000,
    /** 建链等待（lan ≤100ms；frp 含转发建立 ≤3s；p2p 信令 ≤5s——取 5s 上限） */
    private val connectTimeoutMs: Long = 5_000,
    /** 断线自动重连（单测注入 false 保持确定性；生产默认开） */
    private val autoReconnect: Boolean = true,
) {
    /** 传输后端工厂（protocol 三后端单一定义，本类不重实现任何传输逻辑） */
    companion object {
        fun defaultBackendFactory(config: TransportConfig): TransportBackend = when (config.kind) {
            BackendKind.LAN_DIRECT -> LanDirectBackend(config)
            BackendKind.P2P -> P2pBackend(config)
            BackendKind.FRP -> FrpBackend(config)
        }
    }

    private val _phase = MutableStateFlow(SessionPhase.IDLE)
    val phase: StateFlow<SessionPhase> = _phase.asStateFlow()

    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud.asStateFlow()

    /** 回落/状态通告条（p2p 打洞失败回落 frp、断线重连中等；SessionScreen 顶部呈现） */
    private val _notices = MutableStateFlow<List<String>>(emptyList())
    val notices: StateFlow<List<String>> = _notices.asStateFlow()

    /** 会话失败原因（FAILED 态呈现） */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 加密协商结果（HUD 加密徽章与设置呈现） */
    @Volatile
    var negotiatedCryptoAlgo: CryptoAlgo = CryptoAlgo.NONE
        private set

    /** 当前会话 id（0=无会话；START_SESSION accepted 后更新） */
    @Volatile
    var currentSessionId: Int = 0
        private set

    /** 当前生效档位标签（档位循环游标） */
    @Volatile
    var currentPresetLabel: String? = null
        private set

    /** 最近一次被接受的档位四元组（重连开新会话兜底用，对齐 Android lastAccepted*） */
    @Volatile
    var lastAccepted: StreamPreset? = null
        private set

    /** 视频帧出口（transport 回调线程直呼；SessionController 挂起态下拦截不下发） */
    @Volatile
    var onVideo: ((Framing.VideoFrame) -> Unit)? = null

    /** 音频块出口（AuxBridge 消费：ADTS → AAC 解码 → jitter 播放） */
    @Volatile
    var onAudioChunk: ((ControlMessage.AudioChunk) -> Unit)? = null

    /** 应用列表应答出口（AppsScreen 3s 超时判定消费 [requestAppList] 亦可） */
    @Volatile
    var onAppList: ((ControlMessage.AppListMessage) -> Unit)? = null

    /** 辅助控制消息出口（AuxBridge.onControlMessage 接线点；dispatch 循环路由剪贴板/能力通知/文字 ACK，未接线时忽略） */
    @Volatile var onAuxControl: ((ControlMessage) -> Unit)? = null

    /** 解码实际分辨率回填（VideoPipeline 解码输出变化时调用，HUD 分辨率取实际值） */
    fun reportDecodedSize(width: Int, height: Int) {
        _hud.value = _hud.value.copy(decodedWidth = width, decodedHeight = height)
    }

    // ── 内部状态 ──

    private val closed = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val userDisconnected = AtomicBoolean(false)
    /** 每个 start() 只允许自动抢占一次：断线重连不再自动抢占，避免双桌面端互相踢的死循环 */
    @Volatile private var preemptUsed = false
    private val suspended = AtomicBoolean(false)
    private val nextRequestId = AtomicInteger(0)
    private val nextTextSeq = AtomicInteger(0)

    @Volatile private var backend: TransportBackend? = null
    @Volatile private var params: SessionParams? = null
    @Volatile private var lastPongAtMs = 0L
    @Volatile private var lastPingSentAtMs = 0L
    @Volatile private var connected = false

    /** 挂起/恢复语义：挂起时视频帧不下发消费（控制链保持） */
    val isSuspended: Boolean get() = suspended.get()

    /** 会话主协程（断开/失败时取消，重连时重建） */
    @Volatile private var sessionJob: Job? = null

    // 控制消息分发通道：回调线程入队，单消费协程分发（design D7 桥接）
    private val controlChannel = Channel<ControlMessage>(Channel.UNLIMITED)
    private val ackChannel = Channel<ControlMessage.HelloAck>(8)
    private val appListChannel = Channel<ControlMessage.AppListMessage>(4)
    private val connectEvents = Channel<StreamEvent>(8)

    /** 分发循环是否已启动（每会话重启一次） */
    private var dispatchJob: Job? = null

    // ── 连接 ──

    /** 发起会话（首页卡片「连接」/档案一键连接/快捷方式直达）。返回后异步推进，状态经 [phase] 观测。 */
    fun start(newParams: SessionParams) {
        if (!connecting.compareAndSet(false, true)) return
        userDisconnected.set(false)
        closed.set(false)
        preemptUsed = false
        params = newParams
        launchSession(newParams)
    }

    private fun launchSession(runParams: SessionParams) {
        sessionJob?.cancel()
        prepareSessionState()
        val job = scope.launch { runSession(runParams) }
        sessionJob = job
    }

    /** 会话（重）启动前的公共复位：清残留信箱、复位挂起态、重启分发循环 */
    private fun prepareSessionState() {
        ackChannel.clear()
        appListChannel.clear()
        connectEvents.clear()
        suspended.set(false)
        _phase.value = SessionPhase.CONNECTING
        _error.value = null
        startDispatchLoop()
    }

    private suspend fun runSession(runParams: SessionParams) {
        val cfg = TransportConfig(
            role = Role.CLIENT,
            kind = runParams.kind,
            host = runParams.host,
            tcpPort = runParams.tcpPort,
            udpPort = runParams.udpPort,
            useUdpVideo = runParams.useUdpVideo,
        )
        val b = backendFactory(cfg)
        backend = b
        wireBackend(b)
        // 建链（阻塞 connect 在 IO 线程；CONNECTED 事件经 connectEvents 等待）
        var startError: String? = null
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { b.start() }.onFailure { startError = it.message }
            }
        }
        val connectedEvent = withTimeoutOrNull(connectTimeoutMs) {
            connectEvents.receive()
        }
        if (connectedEvent == null) {
            fail(startError?.let { "连接失败: $it" } ?: "连接超时（${runParams.kind} ${runParams.host}:${runParams.tcpPort}）")
            runCatching { b.close() }
            return
        }
        connected = true
        lastPongAtMs = System.currentTimeMillis()

        // HELLO 握手（含加密协商请求，对齐 Android ClientSession.handshake）
        _phase.value = SessionPhase.HANDSHAKING
        val p2pOffer = (b as? P2pBackend)?.let { p2p ->
            // 等本端候选发现 ≤2s，拿到则附多候选 offer（对齐 Android）
            val deadline = System.currentTimeMillis() + 2_000
            while (p2p.localCandidates.isEmpty() && System.currentTimeMillis() < deadline) delay(50)
            p2p.localCandidatesText()
        } ?: ""
        val clientNonce = if (runParams.encryptOn) CryptoCodec.generateHandshakeNonce() else ByteArray(0)
        sendControl(
            ControlMessage.Hello(
                version = cloudphone.protocol.PROTOCOL_VERSION,
                token = runParams.token,
                mode = runParams.mode,
                packageName = runParams.packageName,
                width = 0, height = 0, fps = 0, bitrateKbps = 0,
                sessionId = currentSessionId,
                p2pOffer = p2pOffer,
                encryptOn = runParams.encryptOn,
                cryptoAlgo = if (runParams.encryptOn) CryptoAlgo.AES128_GCM else CryptoAlgo.NONE,
                cryptoNonce = clientNonce,
                takeover = runParams.takeover,
            ),
        )
        val ack = awaitHelloAck(helloTimeoutMs)
        if (ack == null) {
            fail("HELLO 无应答（握手超时）")
            runCatching { b.close() }
            return
        }
        if (ack.status != HelloStatus.ACCEPTED) {
            // 占用拒绝（REJECT_BUSY）：用户点击连接即表达观看意图 → 自动抢占一次
            // （新协议 HELLO.takeover，源端踢现任通道）；抢占重试仍 BUSY 才显式失败。
            if (ack.status == HelloStatus.REJECT_BUSY && !runParams.takeover && !preemptUsed) {
                preemptUsed = true // start() 生命周期内一次：重连路径不再自动抢占（防双端互踢）
                _notices.value = listOf("源端正被其他设备观看，正在抢占…")
                runCatching { b.close() }
                return runSession(runParams.copy(takeover = true))
            }
            // token 错配等显式拒绝：明确失败不重试（spec REJECT_BAD_TOKEN 场景，文案含显式拒绝码）
            fail("握手被拒: ${ack.reason.ifBlank { "-" }} [${ack.status.name}]")
            runCatching { b.close() }
            return
        }
        applyCryptoNegotiation(b, runParams, ack, clientNonce)

        // p2p answer 回填（打洞目标，对齐 Android）
        (b as? P2pBackend)?.let { p2p ->
            if (ack.p2pAnswer.isNotBlank()) {
                if (ack.p2pAnswer.contains(',') || ack.p2pAnswer.contains(';')) {
                    p2p.onPeerCandidates(ack.p2pAnswer)
                } else {
                    cloudphone.transport.StunClient.parseSrflx(ack.p2pAnswer)?.let { p2p.onPeerSrflx(it) }
                }
            }
        }

        // 发起/附着会话
        val startAck = sendStartSession(runParams.mode, runParams.packageName, runParams.sessionId,
            runParams.width, runParams.height, runParams.fps, runParams.bitrateKbps)
        if (startAck == null) {
            fail("START_SESSION 无应答")
            runCatching { b.close() }
            return
        }
        if (startAck.status != HelloStatus.ACCEPTED) {
            // attach 被拒（会话已被源端回收）：带原档位开全新会话兜底（既有重连兜底语义）
            if (runParams.mode == WatchMode.ATTACH && runParams.sessionId != 0) {
                val fallback = lastAccepted
                val retry = sendStartSession(
                    WatchMode.FULLSCREEN, "", 0,
                    fallback?.width ?: runParams.width, fallback?.height ?: runParams.height,
                    fallback?.fps ?: runParams.fps, fallback?.bitrateKbps ?: runParams.bitrateKbps,
                )
                if (retry == null || retry.status != HelloStatus.ACCEPTED) {
                    fail("会话恢复被拒: ${retry?.reason ?: startAck.reason.ifBlank { startAck.status.name }}")
                    runCatching { b.close() }
                    return
                }
                notice("原会话已失效，已按原档位开启全新会话")
            } else {
                fail("START_SESSION 被拒: ${startAck.reason.ifBlank { startAck.status.name }}")
                runCatching { b.close() }
                return
            }
        }
        _phase.value = SessionPhase.STREAMING
        notice("")
        currentPresetLabel = presetLabelFor(runParams)
        // 保活看门狗（PING/PONG 静默死亡检测，对齐 Android >6s 掐死重连）
        startWatchdog()
    }

    /** 连接参数 → 档位循环游标标签（已知预设精确匹配，其余归「自定义」） */
    private fun presetLabelFor(p: SessionParams): String = when {
        p.width == StreamPreset.P1080P_60.width && p.height == StreamPreset.P1080P_60.height && p.fps == 60 -> "1080P·60"
        p.width == StreamPreset.P720P_60.width && p.height == StreamPreset.P720P_60.height && p.fps == 60 -> "720P·60"
        p.width == StreamPreset.P1080P_30.width && p.height == StreamPreset.P1080P_30.height && p.fps == 30 -> "1080P·30"
        else -> StreamPreset.CUSTOM_LABEL
    }

    /** wire 后端回调：回调线程只入队（design D7 桥接纪律） */
    private fun wireBackend(b: TransportBackend) {
        b.onControl = cloudphone.transport.ControlSink { payload ->
            val msg = runCatching { cloudphone.protocol.decodeControlMessage(payload) }.getOrNull() ?: return@ControlSink
            scope.launch { controlChannel.send(msg) }
        }
        b.onVideo = cloudphone.transport.VideoSink { frame ->
            if (!suspended.get()) onVideo?.invoke(frame)
        }
        b.onStreamEvent = cloudphone.transport.StreamEventListener { event, _ ->
            scope.launch { connectEvents.send(event) }
            if (event == StreamEvent.DISCONNECTED) {
                connected = false
                scope.launch { onDisconnected() }
            }
            if (event == StreamEvent.PUNCH_FALLBACK_FRP) {
                notice("p2p 打洞失败，已回落 frp 中转")
            }
            if (event == StreamEvent.PUNCH_ESTABLISHED) {
                notice("")
            }
        }
    }

    /** 控制消息分发循环（每会话一条，单消费者） */
    private fun startDispatchLoop() {
        dispatchJob?.cancel()
        dispatchJob = scope.launch {
            while (isActive) {
                val msg = controlChannel.receive()
                when (msg) {
                    is ControlMessage.HelloAck -> {
                        if (msg.status == HelloStatus.ACCEPTED && msg.sessionId != 0) currentSessionId = msg.sessionId
                        ackChannel.trySend(msg)
                    }
                    is ControlMessage.StatsMessage -> {
                        _hud.value = _hud.value.copy(
                            fps = msg.fps.toDouble(),
                            bitrateKbps = msg.bitrateKbps.toDouble(),
                            rttMs = msg.rttMs.toDouble(),
                            lossPct = msg.lossPct.toDouble(),
                            wireKind = backend?.wireKind,
                            encrypted = negotiatedCryptoAlgo == CryptoAlgo.AES128_GCM,
                        )
                    }
                    is ControlMessage.Ping -> sendControl(
                        ControlMessage.Pong(msg.nonce, msg.senderUs, System.nanoTime() / 1000),
                    )
                    is ControlMessage.Pong -> {
                        lastPongAtMs = System.currentTimeMillis()
                        val rtt = (System.nanoTime() / 1000 - msg.senderUs) / 1000.0
                        if (rtt in 0.0..10_000.0) {
                            _hud.value = _hud.value.copy(rttMs = rtt)
                        }
                    }
                    is ControlMessage.AppListMessage -> {
                        onAppList?.invoke(msg)
                        appListChannel.trySend(msg)
                    }
                    is ControlMessage.AudioChunk -> onAudioChunk?.invoke(msg)
                    is ControlMessage.P2pMigrate -> (backend as? P2pBackend)?.onPeerCandidates(msg.candidates)
                    is ControlMessage.ClipboardText -> onAuxControl?.invoke(msg)
                    is ControlMessage.AuxNotice -> onAuxControl?.invoke(msg)
                    is ControlMessage.TextInputAck -> onAuxControl?.invoke(msg)
                    is ControlMessage.Unknown -> Unit // 未知消息忽略且不中断会话（协议容错规则）
                    else -> Unit
                }
            }
        }
    }

    private suspend fun awaitHelloAck(timeoutMs: Long): ControlMessage.HelloAck? =
        withTimeoutOrNull(timeoutMs) { ackChannel.receive() }

    /** 加密协商落态（HelloAck 处理读线程语义：enableCrypto 竞态窗口最小化——这里在 ack 消费后即刻执行） */
    private fun applyCryptoNegotiation(b: TransportBackend, runParams: SessionParams, ack: ControlMessage.HelloAck, clientNonce: ByteArray) {
        negotiatedCryptoAlgo = if (runParams.encryptOn) ack.cryptoAlgo else CryptoAlgo.NONE
        if (negotiatedCryptoAlgo == CryptoAlgo.AES128_GCM && ack.cryptoNonce.size == CryptoCodec.HANDSHAKE_NONCE_BYTES) {
            val codec = CryptoCodec.negotiate(Role.CLIENT, runParams.token, clientNonce, ack.cryptoNonce)
            b.enableCrypto(codec)
        }
        _hud.value = _hud.value.copy(encrypted = negotiatedCryptoAlgo == CryptoAlgo.AES128_GCM)
    }

    /** 发起/恢复会话；accepted 时 currentSessionId 已更新（对齐 Android ClientSession.startSession） */
    private suspend fun sendStartSession(
        mode: WatchMode, packageName: String, sessionId: Int,
        width: Int, height: Int, fps: Int, bitrateKbps: Int,
    ): ControlMessage.HelloAck? {
        sendControl(
            ControlMessage.StartSession(
                sessionId = sessionId, mode = mode, packageName = packageName,
                width = width, height = height, fps = fps, bitrateKbps = bitrateKbps,
            ),
        )
        val ack = withTimeoutOrNull(startAckTimeoutMs) { ackChannel.receive() } ?: return null
        if (ack.status == HelloStatus.ACCEPTED && ack.sessionId != 0) {
            if (sessionId == 0 && width > 0) {
                lastAccepted = StreamPreset(width, height, fps, bitrateKbps, currentPresetLabel ?: StreamPreset.P1080P_60.label)
            }
        }
        return ack
    }

    // ── 会话动作（SessionScreen 工具坞/抽屉） ──

    /**
     * 档位循环切换（spec：1080P·60 → 720P·60 → 1080P·30 → 自定义 循环）：
     * 以新档位重发整屏 START_SESSION（sessionId=0，控制链不断开）。
     * 返回新档位标签；无会话/发送失败返回 null。
     */
    suspend fun cyclePreset(custom: StreamPreset?): StreamPreset? {
        if (currentSessionId == 0 || _phase.value != SessionPhase.STREAMING) return null
        val next = PresetCycle.next(currentPresetLabel, custom)
        val ack = sendStartSession(WatchMode.FULLSCREEN, "", 0, next.width, next.height, next.fps, next.bitrateKbps)
        if (ack == null || ack.status != HelloStatus.ACCEPTED) return null
        currentPresetLabel = next.label
        lastAccepted = next
        return next
    }

    /** 挂起：停止视频消费，控制链保持（spec「挂起/恢复」语义，可恢复） */
    fun suspendSession() {
        if (currentSessionId == 0) return
        suspended.set(true)
        _phase.value = SessionPhase.SUSPENDED
    }

    /** 恢复：重新拉流（IDR 秒回），控制链全程未断 */
    fun resumeSession() {
        if (!suspended.get()) return
        suspended.set(false)
        _phase.value = SessionPhase.STREAMING
        sendControl(ControlMessage.IdrRequest(sessionId = currentSessionId))
    }

    /** 断开：STOP_SESSION + 关传输（源端会话正确终止），回 IDLE/DISCONNECTED */
    fun disconnect() {
        userDisconnected.set(true)
        val sid = currentSessionId
        if (sid != 0) sendControl(ControlMessage.StopSession(sid))
        currentSessionId = 0
        closed.set(true)
        runCatching { backend?.close() }
        connected = false
        sessionJob?.cancel()
        dispatchJob?.cancel()
        watchdogJob?.cancel()
        _phase.value = SessionPhase.DISCONNECTED
        _hud.value = HudState()
        onVideo = null
        onAudioChunk = null
    }

    /** AppListRequest（AppsScreen 消费；3s 无应答返回 null → UI 显示重试入口） */
    suspend fun requestAppList(timeoutMs: Long = 3_000): ControlMessage.AppListMessage? {
        if (currentSessionId == 0) return null
        val requestId = nextRequestId.incrementAndGet()
        sendControl(ControlMessage.AppListRequest(requestId))
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                val msg = appListChannel.receive()
                if (msg.requestId == requestId) return@withTimeoutOrNull msg
                // 其它 requestId 的迟到应答：忽略继续等
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }

    /** 输入事件回传（InputMapper 归一化坐标，pointerId 0=单指） */
    fun sendInput(action: cloudphone.protocol.InputAction, x: Int, y: Int) {
        if (currentSessionId == 0) return
        sendControl(ControlMessage.InputEvent(sessionId = currentSessionId, action = action, pointerId = 0, x = x, y = y))
    }

    /** 通用控制发送（AuxBridge/截屏等消费） */
    fun sendControl(message: ControlMessage) {
        runCatching { backend?.sendControl(cloudphone.protocol.encodeControlMessage(message)) }
    }

    /** 文字注入 seq 分配（AuxBridge TEXT_INPUT 对账用，全局单调） */
    fun nextTextSequence(): Int = nextTextSeq.incrementAndGet()

    // ── 断线重连 ──

    private suspend fun onDisconnected() {
        if (userDisconnected.get() || closed.get()) return
        notice("连接断开，重连中…")
        _phase.value = SessionPhase.CONNECTING
        var backoffMs = 1_000L
        while (!userDisconnected.get() && !closed.get()) {
            delay(backoffMs) // 指数退避 1s→30s：断线风暴下 1s 固定节拍会与占用保护互相放大
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            val p = params ?: return
            val attach = currentSessionId
            val runParams = if (attach != 0) {
                p.copy(sessionId = attach, mode = WatchMode.ATTACH)
            } else p
            prepareSessionState()
            runSession(runParams)
            if (_phase.value == SessionPhase.STREAMING) return
            if (_phase.value == SessionPhase.FAILED) return // 显式拒绝（如 token 错配/被占用拒绝）不重试
        }
    }

    // ── 保活看门狗（对齐 Android：>6s 无 Pong 掐死重连） ──

    @Volatile private var watchdogJob: Job? = null

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && connected && !closed.get() && !userDisconnected.get()) {
                delay(2_000)
                if (!connected) break
                val now = System.currentTimeMillis()
                if (now - lastPingSentAtMs >= 2_000) {
                    lastPingSentAtMs = now
                    sendControl(ControlMessage.Ping(nonce = now, senderUs = System.nanoTime() / 1000))
                }
                if (lastPongAtMs > 0 && now - lastPongAtMs > 6_000) {
                    lastPongAtMs = now // 防抖：同一死通道只触发一次
                    runCatching { backend?.close() } // DISCONNECTED 事件 → onDisconnected 重连
                    connected = false
                }
            }
        }
    }

    // ── 小工具 ──

    private fun notice(text: String) {
        _notices.value = if (text.isBlank()) emptyList() else listOf(text)
    }

    private fun fail(reason: String) {
        _error.value = reason
        _phase.value = SessionPhase.FAILED
        connecting.set(false)
    }
}

private fun <T> Channel<T>.clear() {
    while (tryReceive().isSuccess) Unit
}
