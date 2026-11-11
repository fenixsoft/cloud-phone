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
import cloudphone.protocol.AuxChannel
import cloudphone.protocol.ControlMessage
import cloudphone.protocol.HelloStatus
import cloudphone.protocol.TransportKind
import cloudphone.protocol.WatchMode
import cloudphone.protocol.decodeControlMessage
import cloudphone.protocol.encodeControlMessage
import cloudphone.source.ime.ImeBridge
import cloudphone.transport.BackendKind
import cloudphone.transport.CryptoAlgo
import cloudphone.transport.CryptoCodec
import cloudphone.transport.FrpBackend
import cloudphone.transport.LanDirectBackend
import cloudphone.transport.P2pBackend
import cloudphone.transport.Role
import cloudphone.transport.StunClient
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
    private var bootstrap: ShellBootstrap? = null

    // ── M3 aux-channels-m3：音频下行（控制 TCP 记录通道）──

    /** 音频捕获（会话激活时建，会话结束释放——任务 2.2） */
    @Volatile private var audioCapture: AudioCaptureService? = null
    /** 有界音频下行队列（25 帧 ≈500ms；满丢最旧——积压防护第一层，保视频不阻塞） */
    private val audioQueue = java.util.concurrent.LinkedBlockingQueue<ControlMessage.AudioChunk>(25)
    private var audioSenderThread: Thread? = null
    /** 音频不可用通告（会话激活时下发 AuxNotice，客户端明示降级非静默无声） */
    @Volatile private var audioNotice: String? = null
    /** 源端侧 PING/PONG RTT 看门狗：nonce → 发送时刻 us */
    private val pendingPings = LinkedHashMap<Long, Long>()
    @Volatile private var smoothedRttMs = 0.0

    // ── M3 aux-channels-m3：文字输入 seq 去重（LRU 64）──
    private val textSeqSeen = LinkedHashMap<Int, Long>(64)

    /** 源端加密开关意图（启动 intent EXTRA_ENCRYPT_ON 下发，缺省开；negotiation 亦要求对端开启） */
    @Volatile private var encryptOn = true

    /**
     * 会话级协商结果（p2p-crossnet-traversal 3.1）：AES128GCM = 双端开启且对端支持，
     * NONE = 明文（对端老版本不支持/任一侧关闭）。enableCrypto 后与 wire 实际态一致。
     */
    @Volatile var negotiatedCryptoAlgo: CryptoAlgo = CryptoAlgo.NONE
        private set

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
        // 加密开关：启动 intent extra 优先（矩阵自动化下发），回落 prefs，缺省开
        encryptOn = intent?.getBooleanExtra(EXTRA_ENCRYPT_ON, prefs.getBoolean("encryptOn", true))
            ?: prefs.getBoolean("encryptOn", true)
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
        captureLost = true // UI 侧据此自动重授权（MediaProjection 令牌一次性+会超时，丢失后旧凭据必失效）
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
        sharedCapture?.let { existing ->
            // 异参自适应：换编码器面（零投影令牌消耗），任意客户端档位即插即连，
            // 不再依赖后台重新授权（凭据一次性：二次铸造/二次建 VD 均 SecurityException）
            if (existing.adaptTo(params) { seq, pts, kf, data -> onSessionFrame(seq, pts, kf, data) }) {
                captureParams = params
                android.util.Log.i(
                    "cloudphone-source",
                    "shared capture ready ${params.width}x${params.height}@${params.fps}/${params.bitrateKbps}kbps (adapted)",
                )
            } else {
                // 自适应失败：保留现行管线仍可出流，会话照常挂接（HelloAck 回实际尺寸，客户端 letterbox 兼容）
                captureParams = existing.currentParams
                android.util.Log.w(
                    "cloudphone-source",
                    "capture adapt failed; attach existing pipeline " +
                        "${existing.currentParams.width}x${existing.currentParams.height}@${existing.currentParams.fps}",
                )
            }
            return
        }
        val projection = acquireProjection(this)
        if (projection == null) {
            // 凭据铸造失败（一次性令牌被消费/超时）与 sc.start() 失败同因同果：
            // 必须同样置 captureLost，否则 MainActivity.onResume 不会自动弹重授权，
            // 用户会永远卡在「采集管线启动失败」（用户报告的问题 2 的另一半根因）。
            lastStartError = "需要 MediaProjection 授权（先在源端 App 点『授予 MediaProjection 授权』重新授权）"
            captureLost = true
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
            // 新建失败的唯一现实原因：投影凭据已消费/超时（一次性令牌）。
            // 清掉死凭据并给出可操作文案；captureLost 触发源端 UI 自动重授权。
            StreamService.projectionGrant = null
            captureLost = true
            lastStartError = "投屏授权已失效，请在源端 App 点『授予 MediaProjection 授权』重新授权后重连"
            return
        }
        sharedCapture = sc
        captureParams = params
        captureLost = false
        android.util.Log.i("cloudphone-source", "shared capture ready ${params.width}x${params.height}@${params.fps}/${params.bitrateKbps}kbps")
    }

    private fun startStreaming(kind: BackendKind, tcpPort: Int, udpPort: Int, useUdp: Boolean) {
        val bootstrap = ShellBootstrap()
        this.bootstrap = bootstrap
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
                    onSessionReleased() // 断开释放音频捕获
                }
                else -> Unit
            }
        }
        running = true // 必须先置位：stats 泵线程 while(running) 依赖读到 true，否则秒退
        startStatsPump()
        startAudioSender()
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
            // AUDIO_CHUNK 50 帧/s 不逐条记日志（噪声）；其余消息维持既有调试可见性
            if (msg !is ControlMessage.AudioChunk) {
                android.util.Log.d("cloudphone-source", "sent ${msg.javaClass.simpleName} (${bytes.size}B)")
            }
        } catch (e: Exception) {
            android.util.Log.w("cloudphone-source", "send ${msg.javaClass.simpleName} failed: ${e.message}")
            // 链路瞬断：STATS/应答丢失可容忍（重连后客户端重走握手）
        }
    }

    private fun handleControl(msg: ControlMessage) {
        android.util.Log.d("cloudphone-source", "recv ${msg.javaClass.simpleName}")
        when (msg) {
            is ControlMessage.Hello -> handleHello(msg)
            is ControlMessage.P2pMigrate -> {
                // 端点迁移（2.3）：对端网络切换推送新候选；会话/统计不重置
                (backend as? P2pBackend)?.onPeerCandidates(msg.candidates)
            }
            is ControlMessage.StartSession -> handleStartSession(msg)
            is ControlMessage.StopSession -> {
                val session = sessionManager?.get(msg.sessionId)
                if (session != null) {
                    sessionManager?.destroy(session)
                    if (currentSession?.id == msg.sessionId) currentSession = null
                    onSessionReleased() // 会话结束释放音频捕获（任务 2.2）
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
            is ControlMessage.Pong -> onPongSample(msg)
            is ControlMessage.StatsMessage -> Unit // 客户端统计：M0 源端仅消费 RTT（后端已自测）
            is ControlMessage.AppListRequest -> handleAppListRequest(msg)
            // ── M3 aux-channels-m3（任务 3.3）──
            is ControlMessage.ClipboardSet -> handleClipboardSet(msg)
            is ControlMessage.ClipboardGet -> handleClipboardGet()
            is ControlMessage.TextInput -> handleTextInput(msg)
            is ControlMessage.Unknown -> // 未知消息忽略且不中断会话（协议要求）；记录类型字节以诊断旧版客户端方言
                android.util.Log.w(
                    "cloudphone-source",
                    "recv Unknown type=0x${Integer.toHexString(msg.unknownType)} len=${msg.payload.size} head=${msg.payload.take(12).joinToString("") { "%02x".format(it) }}",
                )
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

    // ── M3 aux-channels-m3：辅助消息处理（任务 3.3）──

    /**
     * CLIPBOARD_SET → IME setPrimaryClip（IME 持焦点合法，全版本可用）。
     * 成功回 CLIPBOARD_TEXT 回显（客户端单飞对账）；未启用/失败回 AuxNotice 明示原因。
     */
    private fun handleClipboardSet(msg: ControlMessage.ClipboardSet) {
        if (!ImeBridge.installed) {
            send(notice(AuxChannel.CLIPBOARD, "云剪贴板输入法未启用（源端设置页启用，或 adb: ime enable/set）"))
            return
        }
        val ok = ImeBridge.setPrimaryClip(msg.text)
        if (ok) {
            android.util.Log.i("cloudphone-source", "clipboard set (${msg.text.length} chars) via IME")
            send(ControlMessage.ClipboardText(msg.text))
        } else {
            send(notice(AuxChannel.CLIPBOARD, "源端剪贴板写入失败（setPrimaryClip 被拒）"))
        }
    }

    /**
     * CLIPBOARD_GET → 读剪贴板回 CLIPBOARD_TEXT。
     * 无焦点/未启用（Android 10+ 后台剪贴板读取限制）回 AuxNotice 明示原因（非静默失败）。
     */
    private fun handleClipboardGet() {
        if (!ImeBridge.installed) {
            send(notice(AuxChannel.CLIPBOARD, "云剪贴板输入法未启用（源端设置页启用，或 adb: ime enable/set）"))
            return
        }
        val text = ImeBridge.readPrimaryClip()
        if (text == null) {
            send(notice(AuxChannel.CLIPBOARD, "源端无获焦输入框（剪贴板读取需 IME 活跃或源端 App 前台）"))
        } else {
            android.util.Log.i("cloudphone-source", "clipboard get (${text.length} chars) via IME")
            send(ControlMessage.ClipboardText(text))
        }
    }

    /**
     * TEXT_INPUT → IME commitText 主路径（完整 Unicode 含中文）；IME 未启用时纯 ASCII
     * 兜底 shellhelper `input text`（空格转义）；非 ASCII 且无 IME 明确报错（不静默丢字）。
     * 每条必有 ACK{seq} 回执；seq 去重（重复投递不重复注入，幂等补发成功回执）。
     */
    private fun handleTextInput(msg: ControlMessage.TextInput) {
        val first = synchronized(textSeqSeen) {
            if (textSeqSeen.containsKey(msg.seq)) false else {
                textSeqSeen[msg.seq] = android.os.SystemClock.elapsedRealtime()
                while (textSeqSeen.size > 64) textSeqSeen.remove(textSeqSeen.keys.first())
                true
            }
        }
        if (!first) {
            send(ControlMessage.TextInputAck(msg.seq, true, ""))
            return
        }
        android.util.Log.i("cloudphone-source", "text input seq=${msg.seq} (${msg.text.length} chars, ime=${ImeBridge.installed})")
        if (ImeBridge.installed) {
            val ok = ImeBridge.commitText(msg.text)
            send(
                if (ok) ControlMessage.TextInputAck(msg.seq, true, "")
                else ControlMessage.TextInputAck(msg.seq, false, "源端无获焦输入框（commitText 需输入法活跃）"),
            )
            return
        }
        // IME 未启用：纯 ASCII（含空格）兜底 shellhelper `input text`（空格转义在助手侧）
        if (msg.text.isNotEmpty() && msg.text.all { it.code in 0x20..0x7E }) {
            val r = bootstrap?.inputText(msg.text)
            send(
                if (r?.ok == true) ControlMessage.TextInputAck(msg.seq, true, "")
                else ControlMessage.TextInputAck(msg.seq, false, "shell 助手 input text 失败: ${r?.value ?: "助手不可用"}"),
            )
        } else {
            send(ControlMessage.TextInputAck(msg.seq, false, "需启用云剪贴板输入法（非 ASCII 文本必须经输入法注入）"))
        }
    }

    private fun notice(channel: AuxChannel, reason: String) =
        ControlMessage.AuxNotice(channel, reason)

    // ── M3 aux-channels-m3：音频会话生命周期与看门狗（任务 2.2）──

    /** 会话激活（新建/恢复）：启动音频捕获 + 下发不可用通告（如有）。幂等 */
    private fun onSessionActivated() {
        if (audioCapture?.isRunning() == true) return
        val reason = audioNotice
        if (reason != null) {
            send(notice(AuxChannel.AUDIO, reason))
            audioNotice = null
            return
        }
        val capture = AudioCaptureService()
        val result = capture.start(StreamService.acquireProjection(this)) { ptsUs, seq, data ->
            enqueueAudio(ptsUs, seq, data)
        }
        if (result.ok) {
            audioCapture = capture
            android.util.Log.i("cloudphone-source", "audio capture started (AAC-LC 48k stereo 96kbps 20ms)")
        } else {
            capture.stop()
            audioNotice = result.reason
            send(notice(AuxChannel.AUDIO, result.reason))
            audioNotice = null
        }
    }

    /** 会话释放（STOP/断开挂机/服务销毁）：释放捕获（任务 2.2 字面要求） */
    private fun onSessionReleased() {
        audioCapture?.stop()
        audioCapture = null
        audioQueue.clear()
    }

    /** 音频帧入队（编码线程回调）：有界队列满丢最旧（保视频优先，不阻塞编码线程） */
    private fun enqueueAudio(ptsUs: Long, seq: Int, data: ByteArray) {
        if (currentSession == null) return
        val chunk = ControlMessage.AudioChunk(ptsUs, seq, data)
        while (!audioQueue.offer(chunk)) {
            audioQueue.poll() // 丢最旧
            PipelineCounters.droppedFrames.incrementAndGet()
        }
    }

    /** 音频下行泵：独立线程消费队列写控制通道（弱网写阻塞不拖累控制线程） */
    private fun startAudioSender() {
        audioSenderThread = Thread({
            while (running) {
                val chunk = try {
                    audioQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                } catch (_: InterruptedException) {
                    return@Thread
                }
                send(chunk)
            }
        }, "audio-sender").apply { isDaemon = true; start() }
    }

    /** 源端侧 PONG 采样：客户端回显我方 nonce → RTT 统计（看门狗积压判据） */
    private fun onPongSample(msg: ControlMessage.Pong) {
        val sentUs = synchronized(pendingPings) { pendingPings.remove(msg.nonce) } ?: return
        val rttMs = (nowUs() - sentUs) / 1000.0
        if (rttMs in 0.0..60_000.0) {
            smoothedRttMs = if (smoothedRttMs <= 0.0) rttMs else smoothedRttMs + 0.2 * (rttMs - smoothedRttMs)
        }
    }

    /** 控制通道积压防护：RTT 超阈值（TCP 发送缓冲积压）丢全部积压音频帧保视频（jitter 侧 skip 计数） */
    private fun watchdogBacklogGuard() {
        if (smoothedRttMs > BACKLOG_RTT_THRESHOLD_MS && audioQueue.isNotEmpty()) {
            val dropped = audioQueue.size
            audioQueue.clear()
            android.util.Log.w(
                "cloudphone-source",
                "控制通道积压（rtt=${smoothedRttMs.toInt()}ms > ${BACKLOG_RTT_THRESHOLD_MS}ms）→ 丢 $dropped 帧积压音频保视频",
            )
            PipelineCounters.droppedFrames.incrementAndGet()
        }
    }

    private fun handleHello(msg: ControlMessage.Hello) {
        val manager = sessionManager ?: return
        // token 校验最前置（加密协商复用既有拒绝路径：token 不匹配 → REJECT_BAD_TOKEN，
        // 绝不进入协商/enableCrypto）
        if (!manager.validateToken(msg.token)) {
            negotiatedCryptoAlgo = CryptoAlgo.NONE
            send(ack(HelloStatus.REJECT_BAD_TOKEN, "token mismatch", msg))
            return
        }
        // p2p：offer（客户端候选文本，多候选 + symmetric 标记；旧单候选为其特例）→ 源端候选文本 answer
        var answer = ""
        val p2p = backend as? P2pBackend
        if (p2p != null && msg.p2pOffer.isNotBlank()) {
            if (msg.p2pOffer.contains(',') || msg.p2pOffer.contains(';')) {
                p2p.onPeerCandidates(msg.p2pOffer)
            } else {
                StunClient.parseSrflx(msg.p2pOffer)?.let { p2p.onPeerSrflx(it) }
            }
            answer = p2p.localCandidatesText()
        }
        // 加密协商（3.1）：客户端 encryptOn + AES128_GCM + 32B nonce，且本端开关开启 → 接受；
        // 否则明文（ack 无 crypto 块，与 v1 老端字节一致）
        var acceptedCodec: CryptoCodec? = null
        var sourceNonce = ByteArray(0)
        val algo = if (encryptOn && msg.encryptOn && msg.cryptoAlgo == CryptoAlgo.AES128_GCM &&
            msg.cryptoNonce.size == CryptoCodec.HANDSHAKE_NONCE_BYTES
        ) {
            CryptoAlgo.AES128_GCM
        } else {
            CryptoAlgo.NONE
        }
        if (algo == CryptoAlgo.AES128_GCM) {
            sourceNonce = CryptoCodec.generateHandshakeNonce()
            acceptedCodec = CryptoCodec.negotiate(Role.SOURCE, msg.token, msg.cryptoNonce, sourceNonce)
        }
        negotiatedCryptoAlgo = algo
        send(
            ack(HelloStatus.ACCEPTED, "", msg).copy(
                hungSessions = manager.hungSessions(),
                p2pAnswer = answer,
                // ack 的 crypto 块仅在协商成功（algo != NONE）时随线格式出现
                encryptOn = algo == CryptoAlgo.AES128_GCM,
                cryptoAlgo = algo,
                cryptoNonce = sourceNonce,
            ),
        )
        // 关键次序：ack 已同步 flush 出栈（writeControl flush），此后才启用加密——
        // TCP 有序保证客户端先以明文读到 ack（拿到 s2c/c2s 材料）再收加密记录。
        // handleControl 跑在通道读线程，enableCrypto 直接作用于当前 channel。
        if (acceptedCodec != null) {
            backend?.enableCrypto(acceptedCodec)
            android.util.Log.i(
                "cloudphone-source",
                "crypto negotiated: AES128-GCM (session keys derived from pairing token)",
            )
        }
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
            val resumed = if (session.mode == WatchMode.FULLSCREEN) {
                // 整屏会话恢复：共享管线（VD/编码器）断开期间持续运行，无需也不能用已消费的
                // 投影凭据重建采集（attachFullscreen 的 session.projection 本就为 null）——
                // 校验共享管线存活并按会话参数对齐即视为恢复。
                val ok = ensureSharedCapture(session.params) != null && sharedCapture != null
                if (ok) {
                    session.hung = false
                    sharedCapture?.requestIdr() // IDR 秒回，客户端亚秒出画
                }
                ok
            } else {
                manager.resume(
                    session,
                    onFrame = { seq, pts, kf, data -> onSessionFrame(seq, pts, kf, data) },
                )
            }
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
        // 诚实回执：FULLSCREEN 实际出流档位 = 共享管线现行参数（aspect-exact 后可能 ≠ 请求档位），
        // 客户端按此与真实帧尺寸做 letterbox/触摸坐标换算
        val actual = if (session.mode == WatchMode.FULLSCREEN) sharedCapture?.currentParams ?: session.params else session.params
        onSessionActivated() // 音频捕获随会话激活（新建/恢复同路径；不可用即下发 AuxNotice）
        send(
            ControlMessage.HelloAck(
                status = HelloStatus.ACCEPTED,
                reason = "",
                sessionId = session.id,
                width = actual.width,
                height = actual.height,
                fps = actual.fps,
                bitrateKbps = actual.bitrateKbps,
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
                    // 看门狗积压监测扩展（任务 2.2）：源端主动 PING 探测控制通道 RTT
                    // （后端 rttMs 无生产采样源，源端侧自测是唯一可信判据），超阈值丢积压音频保视频
                    if (session != null) {
                        val nonce = System.nanoTime()
                        val sentUs = nowUs()
                        synchronized(pendingPings) {
                            pendingPings[nonce] = sentUs
                            while (pendingPings.size > 8) pendingPings.remove(pendingPings.keys.first())
                        }
                        send(ControlMessage.Ping(nonce = nonce, senderUs = sentUs))
                        watchdogBacklogGuard()
                    }
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
        onSessionReleased() // 服务销毁释放音频捕获
        audioSenderThread?.interrupt()
        audioSenderThread = null
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
        /** 控制通道积压阈值（源端 PING/PONG RTT 看门狗，任务 2.2）：超过即丢积压音频保视频 */
        const val BACKLOG_RTT_THRESHOLD_MS = 800.0

        /** 加密开关（p2p-crossnet-traversal 3.1）：启动 intent extra，缺省 true；回落 prefs "encryptOn" */
        const val EXTRA_ENCRYPT_ON = "cloudphone.source.ENCRYPT_ON"

        /** MainActivity 授权后的投影对象交接点（进程内单会话整屏路径） */
        @Volatile var projectionRef: MediaProjection? = null

        /** 投影失效态：丢失回调/重建失败置位；UI（MainActivity.onResume）据此自动重授权 */
        @Volatile var captureLost: Boolean = false

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
