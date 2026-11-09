package cloudphone.natlab

import cloudphone.protocol.ControlMessage
import cloudphone.protocol.Framing
import cloudphone.protocol.HelloStatus
import cloudphone.protocol.WatchMode
import cloudphone.protocol.decodeControlMessage
import cloudphone.protocol.encodeControlMessage
import cloudphone.transport.BackendKind
import cloudphone.transport.ControlSink
import cloudphone.transport.CryptoAlgo
import cloudphone.transport.CryptoCodec
import cloudphone.transport.Diagnostics
import cloudphone.transport.P2pBackend
import cloudphone.transport.Role
import cloudphone.transport.StunClient
import cloudphone.transport.StreamEvent
import cloudphone.transport.StreamEventListener
import cloudphone.transport.TransportConfig
import cloudphone.transport.VideoSink
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * natlab 对端 JVM 进程（p2p-crossnet-traversal 4.2）：protocol 库直跑真实 transport 栈
 * （StunClient/P2pBackend/CryptoCodec 原样复用，非简化实现），供 natlab_run.py 在
 * netns 拓扑中拉起两端做打洞矩阵验证。
 *
 * 模式：
 *   stun      STUN 应答器（root ns，绑定 0.0.0.0:3478，Binding → XOR-MAPPED-ADDRESS）
 *   client    客户端：连源端 FRP 端口 → HELLO（加密协商）→ START_SESSION → 收视频帧
 *             可选 --migrate-watch：后台周期 refreshLocalCandidates，出口映射变化即发 P2P_MIGRATE
 *   source    源端：监听 FRP 端口 → 应答 HELLO/START_SESSION → 30fps 合成帧泵 + PING 应答
 *
 * 对 runner 的 JSON-line 结果协议（stdout，每事件一行）：
 *   {"event":"connected"|"srflx"|"punch_established"|"punch_fallback"|"migrated"
 *             |"crypto"|"frames"|"migrate_sent"|"session_started"|"result", ...}
 *
 * 退出码 0 = 客户端在 --timeout 内完成既定验收（收到视频帧/迁移后恢复）。
 */
object NatlabPeer {

    @Volatile private var running = true

    // ── 结果事件输出 ──

    private fun emit(json: String) {
        synchronized(System.out) {
            println(json)
            System.out.flush()
        }
    }

    private fun emitEvent(name: String, extra: Map<String, Any?> = emptyMap()) {
        val sb = StringBuilder(96).append("{\"event\":\"").append(name).append('"')
        val t0 = startedAt
        for ((k, v) in extra) {
            sb.append(',')
            sb.append('"').append(k).append("\":")
            when (v) {
                is String -> sb.append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
                else -> sb.append(v.toString())
            }
        }
        if (t0 > 0) sb.append(",\"t_ms\":").append(System.currentTimeMillis() - t0)
        sb.append('}')
        emit(sb.toString())
    }

    @Volatile private var startedAt = 0L

    // ── 参数 ──

    private var token = "cloudphone-m0"
    private var frpHost = "10.200.2.1" // srv 网关 WAN（源端 FRP 静态转发入口）
    private var tcpPort = 22333
    private var udpPort = 22334
    // 多 STUN 节点：同 IP 双端口映射 → 对称映射检测（distinct 端口对）→ 端口预测装配
    private val stunServers = mutableListOf(StunClient.StunServer("10.200.100.1", 3478))
    private var stunExplicit = false
    private var encryptOn = true
    private var portPrediction = true
    private var migrateWatch = false
    private var migrateIntervalMs = 1000L
    private var timeoutMs = 20_000L
    private var expectMigration = false
    private var videoFramesTarget = 5
    private var videoPumpFps = 30
    private var videoPayloadBytes = 1200
    private var perfStats = false
    private val pendingPings = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    /** 性能线程代际：会话重连/关闭时旧线程自然退出 */
    private val perfGen = java.util.concurrent.atomic.AtomicLong(0)

    /** 与协议 UDP 分片上限一致（docs/protocol.md §2.2，Framing.VideoFragmenter.DEFAULT_MAX_DATAGRAM） */
    private const val DEFAULT_MAX_DATAGRAM = 1200

    fun main(args: Array<String>) {
        startedAt = System.currentTimeMillis()
        var mode = ""
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--mode" -> mode = args[++i]
                "--token" -> token = args[++i]
                "--frp-host" -> frpHost = args[++i]
                "--tcp-port" -> tcpPort = args[++i].toInt()
                "--udp-port" -> udpPort = args[++i].toInt()
                "--stun" -> {
                    val h = args[++i].split(":")
                    val host = h[0]
                    val port = h.getOrElse(1) { "3478" }.toInt()
                    if (!stunExplicit) {
                        stunServers.clear() // 首个显式节点替换默认值，其后追加（多节点材料不互吞）
                        stunExplicit = true
                    }
                    stunServers.add(StunClient.StunServer(host, port))
                }
                "--no-encrypt" -> encryptOn = false
                "--no-prediction" -> portPrediction = false
                "--migrate-watch" -> migrateWatch = true
                "--expect-migration" -> expectMigration = true
                "--migrate-interval-ms" -> migrateIntervalMs = args[++i].toLong()
                "--timeout" -> timeoutMs = args[++i].toLong()
                "--frames" -> videoFramesTarget = args[++i].toInt()
                "--fps" -> videoPumpFps = args[++i].toInt()
                "--payload-bytes" -> videoPayloadBytes = args[++i].toInt()
                "--perf" -> perfStats = true
            }
            i++
        }
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            emitEvent("error", mapOf("detail" to (e.message ?: e.toString())))
            running = false
        }
        when (mode) {
            "stun" -> runStun()
            "client" -> runClient()
            "source" -> runSource()
            else -> {
                emitEvent("error", mapOf("detail" to "unknown mode $mode (stun|client|source)"))
                kotlin.system.exitProcess(2)
            }
        }
    }

    // ── STUN 应答器模式 ──

    private fun runStun() {
        val socket = DatagramSocket(InetSocketAddress("0.0.0.0", stunServers.first().port))
        socket.broadcast = false
        emitEvent("listening", mapOf("addr" to "0.0.0.0:${stunServers.first().port}"))
        val buf = ByteArray(2048)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try {
                socket.receive(p)
            } catch (_: Exception) {
                return
            }
            if (p.length < 20) continue
            val type = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
            if (type != 0x0001) continue // 仅 Binding Request
            val tid = buf.copyOfRange(8, 20)
            socket.send(DatagramPacket(bindingResponse(tid, p.address.address, p.port), 32, p.socketAddress))
        }
    }

    private fun bindingResponse(tid: ByteArray, ip: ByteArray, port: Int): ByteArray {
        val r = ByteArray(32)
        r[0] = 0x01; r[1] = 0x01
        r[4] = 0x21; r[5] = 0x12; r[6] = 0xA4.toByte(); r[7] = 0x42
        System.arraycopy(tid, 0, r, 8, 12)
        r[20] = 0x00; r[21] = 0x20; r[22] = 0x00; r[23] = 0x08
        r[24] = 0x00; r[25] = 0x01
        val xport = port xor (StunClient.MAGIC_COOKIE ushr 16)
        r[26] = ((xport ushr 8) and 0xFF).toByte(); r[27] = (xport and 0xFF).toByte()
        for (k in 0 until 4) r[28 + k] = (ip[k].toInt() xor (StunClient.MAGIC_COOKIE ushr (24 - 8 * k))).toByte()
        return r
    }

    // ── 公共：P2pBackend 装配 + 控制分发 ──

    private fun buildBackend(role: Role): P2pBackend {
        val cfg = TransportConfig(
            role = role,
            kind = BackendKind.P2P,
            host = frpHost,
            tcpPort = tcpPort,
            udpPort = udpPort,
            useUdpVideo = true,
        )
        return P2pBackend(
            cfg,
            stunServers = stunServers.toList(),
            punchTimeoutMs = 5000,
            portPredictionEnabled = portPrediction,
        )
    }

    private fun wireEvents(b: P2pBackend, role: Role) {
        b.onStreamEvent = StreamEventListener { e, detail ->
            when (e) {
                StreamEvent.CONNECTED, StreamEvent.RESTORED -> emitEvent("connected", mapOf("role" to role.name))
                StreamEvent.PUNCH_ESTABLISHED -> emitEvent("punch_established", mapOf("detail" to detail))
                StreamEvent.PUNCH_FALLBACK_FRP -> emitEvent("punch_fallback", mapOf("detail" to detail))
                StreamEvent.PUNCH_MIGRATED -> emitEvent("migrated", mapOf("detail" to detail))
                StreamEvent.UDP_DEGRADED -> emitEvent("udp_degraded", mapOf("detail" to detail))
                else -> Unit
            }
        }
    }

    // ── 客户端模式 ──

    private val clientAcks = LinkedBlockingQueue<ControlMessage.HelloAck>(8)
    private val framesReceived = AtomicInteger(0)

    /** 断流恢复重连上限（natlab 断流恢复场景：网络切换杀掉 TCP+UDP，重建控制面后迁移重打洞） */
    private const val MAX_RECONNECTS = 2
    private const val FRAME_STALL_MS = 3500L

    private fun runClient() {
        val deadline = System.currentTimeMillis() + timeoutMs
        var punchMs = -1L
        var punchCount = 0
        var reestablishedAfterMigration = false
        var reconnects = 0
        var ack: ControlMessage.HelloAck? = null
        var framesAtLastBreak = 0
        var cryptoAlgoName = "NONE"

        while (System.currentTimeMillis() < deadline) {
            if (ack != null && reconnects > MAX_RECONNECTS) break
            if (reconnects > 0) emitEvent("reconnect", mapOf("attempt" to reconnects, "frames_before" to framesAtLastBreak))
            framesAtLastBreak = framesReceived.get()
            val b = buildBackend(Role.CLIENT)
            var sessionClosed = false
            var stall = false
            var sessionPunchMs = -1L
            b.onControl = ControlSink { payload ->
                val msg = runCatching { decodeControlMessage(payload) }.getOrNull() ?: return@ControlSink
                handleClientControl(b, msg)
            }
            b.onStreamEvent = StreamEventListener { e, detail ->
                when (e) {
                    StreamEvent.CONNECTED, StreamEvent.RESTORED -> emitEvent("connected", mapOf("role" to "CLIENT"))
                    StreamEvent.PUNCH_ESTABLISHED -> {
                        punchCount++
                        sessionPunchMs = System.currentTimeMillis() - startedAt
                        if (punchMs < 0) punchMs = sessionPunchMs
                        if (punchCount >= 2) reestablishedAfterMigration = true
                        emitEvent("punch_established", mapOf("count" to punchCount, "detail" to detail))
                    }
                    StreamEvent.PUNCH_FALLBACK_FRP -> emitEvent("punch_fallback", mapOf("detail" to detail))
                    StreamEvent.PUNCH_MIGRATED -> emitEvent("migrated", mapOf("detail" to detail))
                    else -> Unit
                }
            }
            b.onVideo = VideoSink { frame ->
                val frames = framesReceived.incrementAndGet()
                if (frames == videoFramesTarget) {
                    emitEvent(
                        "frames", mapOf(
                            "count" to frames, "sequence" to frame.sequence,
                            "bytes" to frame.data.size, "punch_ms" to punchMs,
                        ),
                    )
                }
            }
            b.start()
            emitEvent("srflx", mapOf("candidates" to b.localCandidatesText()))

            // HELLO（加密协商材料：encryptOn + AES128_GCM + 32B nonce；重连场景=RESTORE 语义）
            val clientNonce = if (encryptOn) CryptoCodec.generateHandshakeNonce() else ByteArray(0)
            val hello = ControlMessage.Hello(
                version = 1, token = token, mode = WatchMode.FULLSCREEN, packageName = "",
                width = 0, height = 0, fps = 0, bitrateKbps = 0, sessionId = 0,
                p2pOffer = b.localCandidatesText(),
                encryptOn = encryptOn,
                cryptoAlgo = if (encryptOn) CryptoAlgo.AES128_GCM else CryptoAlgo.NONE,
                cryptoNonce = clientNonce,
            )
            helloClientNonce = clientNonce
            b.sendControl(encodeControlMessage(hello))
            val sessionAck = clientAcks.poll(timeoutMs, TimeUnit.MILLISECONDS)
            if (sessionAck == null || sessionAck.status != HelloStatus.ACCEPTED) {
                if (ack == null) {
                    emitEvent("result", mapOf("ok" to false, "reason" to "hello ack timeout/rejected"))
                }
                sessionClosed = true
                b.close()
                break
            }
            ack = sessionAck
            cryptoAlgoName = ack.cryptoAlgo.name
            emitEvent("crypto", mapOf("algo" to ack.cryptoAlgo.name))
            b.sendControl(
                encodeControlMessage(
                    ControlMessage.StartSession(0, WatchMode.FULLSCREEN, "", 1280, 720, 30, 4000),
                ),
            )
            emitEvent("session_started", mapOf("sessionId" to ack.sessionId))

            // 性能统计（--perf）：1Hz PING（PONG 回包算 RTT）+ 窗口 fps/码率事件
            if (perfStats) {
                val myPerfGen = perfGen.incrementAndGet()
                Thread {
                    var lastFrames = framesReceived.get()
                    var lastAt = System.currentTimeMillis()
                    while (running && !sessionClosed && perfGen.get() == myPerfGen) {
                        try {
                            Thread.sleep(1000)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                        val nonce = System.nanoTime()
                        pendingPings[nonce] = System.nanoTime() / 1000
                        runCatching {
                            b.sendControl(
                                encodeControlMessage(
                                    ControlMessage.Ping(nonce, System.nanoTime() / 1000),
                                ),
                            )
                        }
                        val now = framesReceived.get()
                        val nowAt = System.currentTimeMillis()
                        val winSec = (nowAt - lastAt).coerceAtLeast(1) / 1000.0
                        val fps = ((now - lastFrames) / winSec)
                        val cryptoTag = if (encryptOn && ack.cryptoAlgo == CryptoAlgo.AES128_GCM) {
                            16L * ((videoPayloadBytes + DEFAULT_MAX_DATAGRAM - 1) / DEFAULT_MAX_DATAGRAM)
                        } else {
                            0L
                        }
                        emitEvent(
                            "stats", mapOf(
                                "fps" to Math.round(fps * 10) / 10.0,
                                "bitrate_kbps" to Math.round(fps * videoPayloadBytes * 8 / 1000.0 * 10) / 10.0,
                                "wire_bitrate_kbps" to Math.round(fps * (videoPayloadBytes + cryptoTag) * 8 / 1000.0 * 10) / 10.0,
                                "payload_bytes" to videoPayloadBytes,
                                "crypto" to ack.cryptoAlgo.name,
                            ),
                        )
                        lastFrames = now
                        lastAt = nowAt
                    }
                }.apply { isDaemon = true; start() }
            }

            // 迁移监测（控制面存活时有效）：出口映射变化 → P2P_MIGRATE
            val watch = if (migrateWatch) Thread {
                while (running && !sessionClosed) {
                    try {
                        Thread.sleep(migrateIntervalMs)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    val newCands = try {
                        b.refreshLocalCandidates()
                    } catch (_: Exception) {
                        continue
                    } ?: continue
                    try {
                        b.sendControl(encodeControlMessage(ControlMessage.P2pMigrate(newCands)))
                        emitEvent("migrate_sent", mapOf("candidates" to newCands))
                    } catch (_: Exception) {
                        return@Thread
                    }
                }
            } else null
            watch?.apply { isDaemon = true; start() }

            // 帧看门狗：已收帧后停滞 FRAME_STALL_MS → 判定断流，走断流恢复重连；
            // 验收达标（普通场景帧数达标 / 迁移场景恢复后继续收帧）即提前退出，不等满超时
            var lastFrames = framesReceived.get()
            var hadFramesThisRun = lastFrames > 0 || framesAtLastBreak > 0
            var lastProgress = System.currentTimeMillis()
            var successEarly = false
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(150)
                val now = framesReceived.get()
                if (now != lastFrames) {
                    lastFrames = now
                    hadFramesThisRun = true
                    lastProgress = System.currentTimeMillis()
                } else if (hadFramesThisRun && now - framesAtLastBreak > 0 &&
                    System.currentTimeMillis() - lastProgress > FRAME_STALL_MS &&
                    reconnects < MAX_RECONNECTS
                ) {
                    stall = true
                    break
                }
                if (!expectMigration && now >= videoFramesTarget) {
                    successEarly = true
                    break
                }
                if (expectMigration && reconnects >= 1 && reestablishedAfterMigration &&
                    now > framesAtLastBreak
                ) {
                    Thread.sleep(400) // 恢复后短暂安定再收尾（多积累恢复帧）
                    successEarly = true
                    break
                }
            }
            sessionClosed = true
            val framesNow = framesReceived.get()
            if (!stall) {
                val ok = if (expectMigration) {
                    successEarly && reconnects >= 1 && reestablishedAfterMigration && framesNow > framesAtLastBreak
                } else {
                    successEarly && framesNow >= videoFramesTarget
                }
                emitEvent(
                    "result",
                    mapOf(
                        "ok" to ok,
                        "frames" to framesNow,
                        "punch_ms" to punchMs,
                        "punch_ok" to (punchCount >= 1),
                        "reconnects" to reconnects,
                        "migrated" to (reconnects >= 1),
                        "resumed_frames" to (framesNow - framesAtLastBreak),
                        "nat_symmetric" to b.localNatSymmetric,
                        "prediction" to predictionOutcome(b),
                        "punch_trace" to b.punchTrace.toJson(),
                        "crypto" to cryptoAlgoName,
                    ),
                )
                running = false
                b.close()
                return
            }
            // 断流恢复：拆掉死链路（TCP 控制面已随网络切换失效），重建后重新 HELLO/START
            reconnects++
            b.close()
        }
        emitEvent(
            "result",
            mapOf(
                "ok" to false, "frames" to framesReceived.get(), "punch_ms" to punchMs,
                "punch_ok" to (punchCount >= 1), "reconnects" to reconnects,
                "migrated" to (reconnects >= 1), "crypto" to cryptoAlgoName,
            ),
        )
        running = false
    }

    private fun predictionOutcome(b: P2pBackend): String {
        val hit = b.punchTrace.snapshot().firstOrNull { it.stage == Diagnostics.PunchStages.PORT_PREDICTION }
        return hit?.detail ?: "off"
    }

    private fun handleClientControl(b: P2pBackend, msg: ControlMessage) {
        when (msg) {
            is ControlMessage.HelloAck -> {
                // p2p：answer（多候选文本）分发（与 ConnectActivity 同规则）
                val answer = msg.p2pAnswer
                if (!answer.isNullOrBlank()) {
                    if (answer.contains(',') || answer.contains(';')) {
                        b.onPeerCandidates(answer)
                    } else {
                        StunClient.parseSrflx(answer)?.let { b.onPeerSrflx(it) }
                    }
                }
                // 加密协商接受：读线程即时 enableCrypto（先于 ackQueue/上层消费）
                if (msg.status == HelloStatus.ACCEPTED && encryptOn &&
                    msg.cryptoAlgo == CryptoAlgo.AES128_GCM &&
                    msg.cryptoNonce.size == CryptoCodec.HANDSHAKE_NONCE_BYTES
                ) {
                    val nonce = helloClientNonce
                    if (nonce != null) {
                        b.enableCrypto(CryptoCodec.negotiate(Role.CLIENT, token, nonce, msg.cryptoNonce))
                    }
                }
                clientAcks.offer(msg)
            }
            is ControlMessage.P2pMigrate -> b.onPeerCandidates(msg.candidates)
            is ControlMessage.Pong -> {
                // 性能口径（--perf）：RTT = 本端时钟往返（同 JVM 进程单调钟，跨进程不可比由驱动保证）
                val sentUs = pendingPings.remove(msg.nonce)
                if (sentUs != null) {
                    emitEvent("pong", mapOf("rtt_ms" to (System.nanoTime() / 1000 - sentUs) / 1000.0))
                }
            }
            else -> Unit
        }
    }

    @Volatile private var helloClientNonce: ByteArray? = null

    // ── 源端模式 ──

    private val videoSeq = AtomicLong(1)
    private val pingCount = AtomicInteger(0)

    private fun runSource() {
        val b = buildBackend(Role.SOURCE)
        wireEvents(b, Role.SOURCE)
        b.onControl = ControlSink { payload ->
            val msg = runCatching { decodeControlMessage(payload) }.getOrNull()
            if (msg == null) {
                emitEvent("control_decode_fail", mapOf("bytes" to payload.size))
                return@ControlSink
            }
            if (msg is ControlMessage.P2pMigrate) emitEvent("migrate_recv", mapOf("candidates" to msg.candidates))
            handleSourceControl(b, msg)
        }
        b.onVideo = VideoSink { framesReceived.incrementAndGet() } // 客户端不发视频，防御计数
        b.start()
        emitEvent("listening", mapOf("addr" to "0.0.0.0:$tcpPort"))
        // 运行到超时/被 runner 终止；期间 stats/视频泵由会话驱动
        val deadline = System.currentTimeMillis() + timeoutMs + 10_000
        while (running && System.currentTimeMillis() < deadline) Thread.sleep(200)
        running = false
        b.close()
    }

    private fun handleSourceControl(b: P2pBackend, msg: ControlMessage) {
        when (msg) {
            is ControlMessage.Hello -> {
                // token 校验最前置（复用 REJECT_BAD_TOKEN 拒绝路径）
                if (msg.token != token) {
                    b.sendControl(
                        encodeControlMessage(
                            ControlMessage.HelloAck(
                                HelloStatus.REJECT_BAD_TOKEN, "token mismatch", 0, 0, 0, 0, 0, "", -1, "",
                                emptyList(),
                            ),
                        ),
                    )
                    return
                }
                // 加密协商（与 StreamService 同规则：双端开启 + AES128_GCM + 32B nonce）
                var codec: CryptoCodec? = null
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
                    codec = CryptoCodec.negotiate(Role.SOURCE, msg.token, msg.cryptoNonce, sourceNonce)
                }
                emitEvent("crypto", mapOf("algo" to algo.name))
                emitEvent("answer", mapOf("text" to b.localCandidatesText()))
                b.sendControl(
                    encodeControlMessage(
                        ControlMessage.HelloAck(
                            status = HelloStatus.ACCEPTED, reason = "", sessionId = 7,
                            width = 1280, height = 720, fps = 30, bitrateKbps = 4000,
                            encoder = "natlab", displayId = 0,
                            p2pAnswer = b.localCandidatesText(), hungSessions = emptyList(),
                            encryptOn = algo == CryptoAlgo.AES128_GCM,
                            cryptoAlgo = algo, cryptoNonce = sourceNonce,
                        ),
                    ),
                )
                // ack 同步 flush 后才启用（客户端先明文收 ack 再收密文）
                if (codec != null) b.enableCrypto(codec)
                // p2p：offer（多候选文本）分发
                if (msg.p2pOffer.isNotBlank()) {
                    if (msg.p2pOffer.contains(',') || msg.p2pOffer.contains(';')) {
                        b.onPeerCandidates(msg.p2pOffer)
                    } else {
                        StunClient.parseSrflx(msg.p2pOffer)?.let { b.onPeerSrflx(it) }
                    }
                }
            }
            is ControlMessage.StartSession -> {
                emitEvent("session_started", mapOf("sessionId" to msg.sessionId))
                startVideoPump(b)
            }
            is ControlMessage.Ping -> {
                b.sendControl(
                    encodeControlMessage(
                        ControlMessage.Pong(msg.nonce, msg.senderUs, System.nanoTime() / 1000),
                    ),
                )
                pingCount.incrementAndGet()
            }
            is ControlMessage.IdrRequest -> Unit // 合成帧泵恒发关键帧
            is ControlMessage.P2pMigrate -> b.onPeerCandidates(msg.candidates)
            else -> Unit
        }
    }

    private fun startVideoPump(b: P2pBackend) {
        Thread({
            val interval = 1000L / videoPumpFps
            val frame = ByteArray(videoPayloadBytes) { (it % 251).toByte() } // 合成载荷（含关键帧标记）
            while (running) {
                val seq = videoSeq.getAndIncrement()
                try {
                    b.sendVideo(seq, seq * 33_333, true, frame)
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(interval)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "natlab-video-pump").apply { isDaemon = true; start() }
    }
}

fun main(args: Array<String>) {
    NatlabPeer.main(args)
}
