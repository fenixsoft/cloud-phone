package cloudphone.source

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import cloudphone.protocol.ControlMessage
import cloudphone.protocol.HelloStatus
import cloudphone.protocol.TransportKind
import cloudphone.protocol.WatchMode
import cloudphone.protocol.decodeControlMessage
import cloudphone.protocol.encodeControlMessage
import cloudphone.transport.BackendKind
import cloudphone.transport.FrpBackend
import cloudphone.transport.LanDirectBackend
import cloudphone.transport.P2pBackend
import cloudphone.transport.Role
import cloudphone.transport.StreamEvent
import cloudphone.transport.TransportBackend
import cloudphone.transport.TransportConfig
import cloudphone.source.input.InputInjector
import cloudphone.source.obs.PipelineCounters
import cloudphone.source.shell.ShellBootstrap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 源端串流前台服务：传输后端（SOURCE 角色 accept）+ 协议控制面 + 会话管线接线。
 *
 * - 断开挂机：onStreamEvent DISCONNECTED → 当前会话 hangup（停编码留虚拟屏）；
 * - 同 token 重连：客户端重发 HELLO/START_SESSION(attach) → resume ≤1s（IDR 秒回）；
 * - ≥2 单应用会话并存：SessionManager 注册表；
 * - STATS ≥1Hz 双向、PING/PONG RTT、IDR_REQUEST ≤200ms。
 */
class StreamService : Service() {

    private var backend: TransportBackend? = null
    private var sessionManager: SessionManager? = null
    private var injector: InputInjector? = null
    /** 触摸延迟修复 5.3：输入窗口合成驱动 */
    private var inputComposer: InputComposer? = null
    @Volatile private var currentSession: SessionManager.Session? = null
    /** 授权时创建的共享采集管线（VD+编码器常驻；一授权一实例） */
    @Volatile private var sharedCapture: ScreenCapture? = null
    @Volatile private var captureParams: EncoderPipeline.SessionParams? = null
    private val statsSeq = AtomicInteger(0)
    private var statsThread: Thread? = null
    @Volatile private var running = false
    private var token = DEFAULT_TOKEN

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithType()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        token = prefs.getString("token", DEFAULT_TOKEN) ?: DEFAULT_TOKEN
        val backendKind = when (prefs.getString("backend", "lan-direct")) {
            "frp" -> BackendKind.FRP
            "p2p" -> BackendKind.P2P
            else -> BackendKind.LAN_DIRECT
        }
        val tcpPort = prefs.getInt("tcpPort", 22333)
        val udpPort = prefs.getInt("udpPort", 22334)
        val useUdp = prefs.getBoolean("useUdpVideo", false)
        if (!running) {
            startStreaming(backendKind, tcpPort, udpPort, useUdp)
        }
        if (intent?.action == ACTION_START_CAPTURE) {
            // 授权即建管线：MediaProjection 令牌只够建一次 VD（闲置还会过期），
            // 采集/编码常驻，会话仅挂接帧流。参数由启动方 extras 决定（矩阵自动化下发）。
            ensureSharedCapture(
                EncoderPipeline.SessionParams(
                    width = intent.getIntExtra("width", 1280),
                    height = intent.getIntExtra("height", 720),
                    fps = intent.getIntExtra("fps", 60),
                    bitrateKbps = intent.getIntExtra("bitrateKbps", 8000),
                ),
            )
        }
        return START_STICKY
    }

    /** 投影丢失：仅当仍是当前管线时清理（防旧管线迟到回调误伤新管线） */
    private fun onPipelineLost(pipeline: ScreenCapture) {
        if (sharedCapture !== pipeline) return
        sharedCapture = null
        captureParams = null
        currentSession = null
        android.util.Log.w("cloudphone-source", "shared capture lost (projection stopped)")
    }

    /** 授权后立即创建共享采集管线（一授权一 VD；会话 attach 不再触碰投影令牌） */
    private fun ensureSharedCapture(params: EncoderPipeline.SessionParams) {
        if (sharedCapture != null && captureParams == params) {
            // 同参幂等：也打 ready 日志（自动化以该行判定管线就绪）
            android.util.Log.i(
                "cloudphone-source",
                "shared capture ready ${params.width}x${params.height}@${params.fps}/${params.bitrateKbps}kbps (unchanged)",
            )
            return
        }
        sharedCapture?.stop()
        sharedCapture = null
        val projection = acquireProjection(this)
        if (projection == null) {
            lastStartError = "需要 MediaProjection 授权（先在源端 App 授予）"
            return
        }
        // 丢失回调带实例身份校验：旧管线的 onStop 异步迟到时不得误清新管线
        // （实测：重建管线后旧投影 stop 回调 1~4s 后到达，曾把新 1080p 管线清空导致会话拒绝）
        val holder = arrayOfNulls<ScreenCapture>(1)
        val sc = ScreenCapture(this, projection, params, onProjectionLost = {
            holder[0]?.let { onPipelineLost(it) }
        })
        holder[0] = sc
        val ok = sc.start { seq, pts, kf, data -> onSessionFrame(seq, pts, kf, data) }
        if (!ok) {
            sc.stop()
            lastStartError = "采集管线启动失败（见 capture start failed 日志）"
            return
        }
        sharedCapture = sc
        captureParams = params
        android.util.Log.i("cloudphone-source", "shared capture ready ${params.width}x${params.height}@${params.fps}/${params.bitrateKbps}kbps")
    }

    private fun startStreaming(kind: BackendKind, tcpPort: Int, udpPort: Int, useUdp: Boolean) {
        val bootstrap = ShellBootstrap()
        sessionManager = SessionManager(this, token, bootstrap)
        injector = InputInjector(bootstrap, sessionManager!!)
        // 触摸延迟修复 5.3：输入窗口合成驱动（透明悬窗 5fps；未授权时降级并 WARN）
        inputComposer = InputComposer.get(this)
        inputComposer?.start()
        val config = TransportConfig(Role.SOURCE, kind, "", tcpPort, udpPort, useUdpVideo = useUdp)
        val b = when (kind) {
            BackendKind.FRP -> FrpBackend(config)
            BackendKind.LAN_DIRECT -> LanDirectBackend(config)
            BackendKind.P2P -> P2pBackend(config)
        }
        backend = b
        b.onControl = cloudphone.transport.ControlSink { payload ->
            try {
                handleControl(decodeControlMessage(payload))
            } catch (e: cloudphone.protocol.ProtocolException) {
                // 结构性损坏：忽略该记录（记录级解析失败不拖垮服务）
            }
        }
        b.onStreamEvent = cloudphone.transport.StreamEventListener { event, detail ->
            when (event) {
                StreamEvent.DISCONNECTED -> {
                    // 断开挂机：只停编码/帧流，虚拟屏与 App 保留
                    currentSession?.let { sessionManager?.hangup(it) }
                    currentSession = null
                }
                else -> Unit
            }
        }
        running = true // 必须先置位：stats 泵线程 while(running) 依赖读到 true，否则秒退
        startStatsPump()
        Thread({
            try {
                b.start()
            } catch (e: Exception) {
                running = false
                PipelineCounters.onBadEvent(
                    PipelineCounters.BadEvent.CAPTURE_FAILURE, "transport start: ${e.message}",
                )
            }
        }, "transport-start").apply { isDaemon = true; start() }
    }

    // ── 控制面 ──

    private fun send(msg: ControlMessage) {
        try {
            val bytes = encodeControlMessage(msg)
            backend?.sendControl(bytes)
            android.util.Log.d("cloudphone-source", "sent ${msg.javaClass.simpleName} (${bytes.size}B)")
        } catch (e: Exception) {
            android.util.Log.w("cloudphone-source", "send ${msg.javaClass.simpleName} failed: ${e.message}")
            // 链路瞬断：STATS/应答丢失可容忍（重连后客户端重走握手）
        }
    }

    private fun handleControl(msg: ControlMessage) {
        android.util.Log.d("cloudphone-source", "recv ${msg.javaClass.simpleName}")
        when (msg) {
            is ControlMessage.Hello -> handleHello(msg)
            is ControlMessage.StartSession -> handleStartSession(msg)
            is ControlMessage.StopSession -> {
                val session = sessionManager?.get(msg.sessionId)
                if (session != null) {
                    sessionManager?.destroy(session)
                    if (currentSession?.id == msg.sessionId) currentSession = null
                }
            }
            is ControlMessage.IdrRequest -> sharedCapture?.requestIdr()
            is ControlMessage.InputEvent -> {
                // ② InputEvent 到达打点（触摸延迟插桩 5.1）+ 输入窗口标记（5.3 修复挂点）
                android.util.Log.i("cloudphone-touch", "t2 recv action=${msg.action.id} sid=${msg.sessionId}")
                sharedCapture?.onInputActivity()
                inputComposer?.onInputActivity()
                injector?.inject(msg.sessionId, msg.action, msg.pointerId, msg.x, msg.y)
            }
            is ControlMessage.Ping -> send(ControlMessage.Pong(msg.nonce, msg.senderUs, nowUs()))
            is ControlMessage.StatsMessage -> Unit // 客户端统计：M0 源端仅消费 RTT（后端已自测）
            is ControlMessage.AppListRequest -> handleAppListRequest(msg)
            is ControlMessage.Unknown -> Unit // 未知消息忽略且不中断会话（协议要求）
            else -> Unit
        }
    }

    /**
     * APP_LIST 请求-应答（client-ux-and-input-latency 3.2）：
     * - 会话在线才应答（请求-应答制：老客户端不发该消息即零影响，无 BREAKING）；
     * - PackageManager 枚举 MAIN+LAUNCHER 可启动应用，排除源端 App 自身，其余不过滤
     *   （含负载/第三方/系统应用，spec app-shortcuts Requirement 1）；
     * - 枚举 + label 解析在控制线程内完成（实测 <100ms，1s 应答 SLA 足够）。
     */
    private fun handleAppListRequest(msg: ControlMessage.AppListRequest) {
        if (currentSession == null) {
            android.util.Log.i("cloudphone-source", "app list request req=${msg.requestId} ignored (no active session)")
            return
        }
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val self = packageName
        val byPackage = LinkedHashMap<String, String>()
        for (info in pm.queryIntentActivities(launcher, 0)) {
            val pkg = info.activityInfo.packageName
            if (pkg == self) continue
            if (byPackage.containsKey(pkg)) continue
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
            byPackage[pkg] = label
        }
        val apps = byPackage.entries.map { cloudphone.protocol.AppEntry(it.key, it.value) }
        android.util.Log.i(
            "cloudphone-source",
            "app list response req=${msg.requestId} count=${apps.size} first=${apps.firstOrNull()?.packageName ?: "-"}",
        )
        send(ControlMessage.AppListMessage(msg.requestId, apps))
    }

    private fun handleHello(msg: ControlMessage.Hello) {
        val manager = sessionManager ?: return
        if (!manager.validateToken(msg.token)) {
            send(
                ack(HelloStatus.REJECT_BAD_TOKEN, "token mismatch", msg),
            )
            return
        }
        // p2p：offer（客户端 SRFLX）→ 源端 answer
        var answer = ""
        val p2p = backend as? P2pBackend
        if (p2p != null && msg.p2pOffer.isNotBlank()) {
            cloudphone.transport.StunClient.parseSrflx(msg.p2pOffer)?.let { offer ->
                p2p.onPeerSrflx(offer)
                answer = p2p.localSrflx?.let { cloudphone.transport.StunClient.formatSrflx(it) } ?: ""
            }
        }
        send(
            ack(HelloStatus.ACCEPTED, "", msg).copy(
                hungSessions = manager.hungSessions(),
                p2pAnswer = answer,
            ),
        )
    }

    private fun ack(status: HelloStatus, reason: String, hello: ControlMessage.Hello) =
        ControlMessage.HelloAck(
            status = status,
            reason = reason,
            sessionId = 0,
            width = 0, height = 0, fps = 0, bitrateKbps = 0,
            encoder = "",
            displayId = -1,
            p2pAnswer = "",
            hungSessions = emptyList(),
        )

    private fun handleStartSession(msg: ControlMessage.StartSession) {
        val manager = sessionManager ?: return
        // 挂起当前展示会话（切换观看目标）
        currentSession?.let { if (it.id != msg.sessionId) manager.hangup(it) }

        val session = if (msg.sessionId != 0) {
            manager.get(msg.sessionId)
        } else {
            when (msg.mode) {
                WatchMode.FULLSCREEN -> {
                    // 整屏会话挂接授权时建好的共享采集管线（VD/编码器常驻，见 ensureSharedCapture）；
                    // 请求档位与现行管线不同时按请求重建（2.2：档位必须生效，旧实现 attachFullscreen
                    // 静默沿用旧参，分辨率/码率变更需重启源端进程）
                    ensureSharedCapture(params(msg))
                    if (sharedCapture != null) {
                        manager.attachFullscreen(params(msg))
                    } else {
                        lastStartError = if (lastStartError.isBlank()) {
                            "需要 MediaProjection 授权（先在源端 App 授予）"
                        } else lastStartError
                        null
                    }
                }
                WatchMode.SINGLE_APP -> manager.startSingleApp(
                    msg.packageName.ifBlank { DEFAULT_SINGLE_APP },
                    params(msg),
                    onFrame = { seq, pts, kf, data -> onSessionFrame(seq, pts, kf, data) },
                )
                else -> null
            }
        }
        if (session == null) {
            val reason = manager.lastError.ifBlank { lastStartError.ifBlank { "会话创建失败" } }
            lastStartError = ""
            send(
                ControlMessage.HelloAck(
                    status = HelloStatus.REJECT_UNSUPPORTED_MODE,
                    reason = reason,
                    sessionId = 0, width = 0, height = 0, fps = 0, bitrateKbps = 0,
                    encoder = "", displayId = -1, p2pAnswer = "", hungSessions = manager.hungSessions(),
                ),
            )
            return
        }
        // 已挂机或新建会话 → 恢复/续帧 + IDR 秒回
        if (msg.sessionId != 0 && session.hung) {
            val resumed = manager.resume(
                session,
                onFrame = { seq, pts, kf, data -> onSessionFrame(seq, pts, kf, data) },
            )
            if (!resumed) {
                send(
                    ControlMessage.HelloAck(
                        status = HelloStatus.REJECT_BUSY,
                        reason = manager.lastError,
                        sessionId = 0, width = 0, height = 0, fps = 0, bitrateKbps = 0,
                        encoder = "", displayId = -1, p2pAnswer = "", hungSessions = manager.hungSessions(),
                    ),
                )
                return
            }
        } else if (session.hung) {
            manager.resume(session, onFrame = { seq, pts, kf, data -> onSessionFrame(seq, pts, kf, data) })
        }
        currentSession = session
        send(
            ControlMessage.HelloAck(
                status = HelloStatus.ACCEPTED,
                reason = "",
                sessionId = session.id,
                width = session.params.width,
                height = session.params.height,
                fps = session.params.fps,
                bitrateKbps = session.params.bitrateKbps,
                encoder = "MediaCodec AVC",
                displayId = session.vdSession?.displayId ?: 0,
                p2pAnswer = "",
                hungSessions = manager.hungSessions(),
            ),
        )
    }

    @Volatile private var lastStartError = ""

    /** 会话帧 → 传输层（编码线程回调；后端发送线程安全）。无会话时直接丢弃省带宽 */
    private fun onSessionFrame(sequence: Long, ptsUs: Long, keyframe: Boolean, data: ByteArray) {
        if (currentSession == null) return
        try {
            backend?.sendVideo(sequence, ptsUs, keyframe, data)
        } catch (_: Exception) {
            PipelineCounters.droppedFrames.incrementAndGet()
        }
    }

    // ── STATS ≥1Hz ──

    private fun startStatsPump() {
        statsThread = Thread({
            while (running) {
                val snap = backend?.snapshot()
                if (snap != null) {
                    val session = currentSession
                    send(
                        ControlMessage.StatsMessage(
                            sessionId = session?.id ?: 0,
                            seq = statsSeq.incrementAndGet(),
                            fps = snap.fpsSent.toFloat(),
                            bitrateKbps = snap.bitrateKbpsSent.toFloat(),
                            rttMs = snap.rttMs.toFloat(),
                            lossPct = snap.lossPct.toFloat(),
                            transport = when (backend) {
                                is P2pBackend -> if (snap.badEvents.containsKey(TransportStatsBadEvent.PUNCH_FALLBACK_FRP)) TransportKind.P2P_FALLBACK_FRP else TransportKind.P2P
                                is FrpBackend -> TransportKind.FRP
                                else -> TransportKind.LAN_DIRECT
                            },
                        ),
                    )
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "stats-pump").apply { isDaemon = true; start() }
    }

    // ── MediaProjection 授权桥（MainActivity 授权后传入） ──

    override fun onDestroy() {
        running = false
        statsThread?.interrupt()
        inputComposer?.stop()
        inputComposer = null
        currentSession?.let { sessionManager?.hangup(it) }
        currentSession = null
        sharedCapture?.stop()
        sharedCapture = null
        captureParams = null
        backend?.close()
        super.onDestroy()
    }

    companion object {
        const val PREFS = "cloudphone_source_prefs"
        const val DEFAULT_TOKEN = "cloudphone-m0"
        const val DEFAULT_SINGLE_APP = "cloudphone.loadgen"
        const val CHANNEL_ID = "cloudphone-stream"
        /** 授权成功后由 MainActivity 发起：立即用凭据建共享采集管线 */
        const val ACTION_START_CAPTURE = "cloudphone.source.START_CAPTURE"

        /** MainActivity 授权后的投影对象交接点（进程内单会话整屏路径） */
        @Volatile var projectionRef: MediaProjection? = null

        /**
         * 授权凭据（resultCode + resultData）。部分镜像的 MediaProjection 令牌闲置约 60s 即失效，
         * 会话开始时用凭据重新铸造实例；铸造失败再回退旧实例（可能仍有效）。
         */
        @Volatile var projectionGrant: Pair<Int, Intent>? = null

        /** 会话开始前取可用投影：优先用凭据新铸，失败回退缓存实例 */
        fun acquireProjection(context: Context): MediaProjection? {
            projectionGrant?.let { (code, data) ->
                try {
                    val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val fresh = mgr.getMediaProjection(code, data)
                    projectionRef = fresh
                    return fresh
                } catch (e: Exception) {
                    android.util.Log.i("cloudphone-source", "fresh projection mint failed: ${e.message}")
                }
            }
            return projectionRef
        }

        private fun nowUs(): Long = android.os.SystemClock.elapsedRealtimeNanos() / 1000

        private fun params(msg: ControlMessage.StartSession): EncoderPipeline.SessionParams =
            EncoderPipeline.SessionParams.normalized(
                if (msg.width > 0) msg.width else 1280,
                if (msg.height > 0) msg.height else 720,
                if (msg.fps > 0) msg.fps else 60,
                if (msg.bitrateKbps > 0) msg.bitrateKbps else 8000,
            )
    }

    private fun startForegroundWithType() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "串流会话", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = if (android.os.Build.VERSION.SDK_INT >= 29) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("CloudPhone 源端")
                .setContentText("串流会话运行中")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("CloudPhone 源端").build()
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            startForeground(
                42, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(42, notification)
        }
    }
}

private typealias TransportStatsBadEvent = cloudphone.transport.TransportStats.BadEvent
