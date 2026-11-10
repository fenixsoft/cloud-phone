package cloudphone.desktop

import cloudphone.desktop.session.PresetCycle
import cloudphone.desktop.session.SessionController
import cloudphone.desktop.session.SessionParams
import cloudphone.desktop.session.SessionPhase
import cloudphone.desktop.session.StreamPreset
import cloudphone.protocol.ControlMessage
import cloudphone.protocol.Framing
import cloudphone.protocol.HelloStatus
import cloudphone.protocol.WatchMode
import cloudphone.protocol.decodeControlMessage
import cloudphone.protocol.encodeControlMessage
import cloudphone.transport.CryptoAlgo
import cloudphone.transport.CryptoCodec
import cloudphone.transport.Role
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 2.1 验收：SessionController 经本机源桩 loopback 断言
 * HELLO / START_SESSION / 挂起恢复 / 档位切换 / REJECT_BAD_TOKEN / 加密协商路径。
 * 源桩是真实线格式对端（Framing/Messages 编解码 + 可选 CryptoCodec），非 mock。
 */
class SessionControllerTest {

    private val controllers = CopyOnWriteArrayList<SessionController>()
    private val sources = CopyOnWriteArrayList<LoopbackSource>()

    private fun newSource(
        validToken: String = "good-token",
        negotiateCrypto: Boolean = false,
        hungSessions: List<cloudphone.protocol.HungSession> = emptyList(),
    ): LoopbackSource {
        val s = LoopbackSource(validToken = validToken, negotiateCrypto = negotiateCrypto, hungSessions = hungSessions)
        s.start()
        sources.add(s)
        return s
    }

    private fun newController(autoReconnect: Boolean = true): SessionController {
        val c = SessionController(autoReconnect = autoReconnect)
        controllers.add(c)
        return c
    }

    private fun awaitPhase(c: SessionController, target: SessionPhase, timeoutMs: Long = 8000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (c.phase.value == target) return true
            Thread.sleep(10)
        }
        return false
    }

    @After
    fun tearDown() {
        controllers.forEach { it.disconnect() }
        sources.forEach { it.close() }
    }

    // ── HELLO / START_SESSION 建立会话 ──

    @Test
    fun `握手与会话建立 - HELLO 携带 token 与加密请求 START_SESSION 携带档位`() {
        val src = newSource()
        val c = newController()
        val videos = CopyOnWriteArrayList<Framing.VideoFrame>()
        c.onVideo = { videos.add(it) }

        c.start(SessionParams(host = "127.0.0.1", tcpPort = src.boundPort, udpPort = 0, useUdpVideo = false, token = "good-token"))
        assertTrue("应进入 STREAMING（实际 ${c.phase.value} / err=${c.error.value}）", awaitPhase(c, SessionPhase.STREAMING))

        assertEquals("sessionId 取自 HELLO_ACK", 42, c.currentSessionId)
        assertEquals("源端应收到一次 START_SESSION", 1, src.startSessions.size)
        val ss = src.startSessions[0]
        assertEquals(1080, ss.width)
        assertEquals(1920, ss.height)
        assertEquals(60, ss.fps)
        assertEquals(8000, ss.bitrateKbps)
        assertEquals(WatchMode.FULLSCREEN, ss.mode)
        val hello = src.hellos[0]
        assertEquals("good-token", hello.token)
        assertEquals(1, hello.version)
        assertTrue("加密意图应随 HELLO 请求", hello.encryptOn)
        assertTrue("初始档位游标应落在已知预设", c.currentPresetLabel == "1080P·60")
    }

    // ── REJECT_BAD_TOKEN ──

    @Test
    fun `token 错配 - 显式 REJECT_BAD_TOKEN 失败不进入会话`() {
        val src = newSource(validToken = "good-token")
        val c = newController()
        c.start(SessionParams(host = "127.0.0.1", tcpPort = src.boundPort, udpPort = 0, useUdpVideo = false, token = "wrong-token"))
        assertTrue(awaitPhase(c, SessionPhase.FAILED))
        assertTrue("失败原因应含显式拒绝码：${c.error.value}", c.error.value!!.contains("REJECT_BAD_TOKEN"))
        assertEquals("不得进入会话", 0, c.currentSessionId)
    }

    // ── 输入事件回传（明文） ──

    @Test
    fun `输入事件回传 - 归一化坐标经控制通道到源端`() {
        val src = newSource()
        val c = newController()
        c.start(SessionParams(host = "127.0.0.1", tcpPort = src.boundPort, udpPort = 0, useUdpVideo = false, token = "good-token"))
        assertTrue(awaitPhase(c, SessionPhase.STREAMING))

        c.sendInput(cloudphone.protocol.InputAction.DOWN, 100, 200)
        c.sendInput(cloudphone.protocol.InputAction.MOVE, 300, 400)
        c.sendInput(cloudphone.protocol.InputAction.UP, 300, 400)

        val deadline = System.currentTimeMillis() + 5000
        while (src.receivedInputs.size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(3, src.receivedInputs.size)
        assertEquals(42, src.receivedInputs[0].sessionId)
        assertEquals(100, src.receivedInputs[0].x)
        assertEquals(200, src.receivedInputs[0].y)
    }

    // ── 挂起 / 恢复（控制链保持） ──

    @Test
    fun `挂起恢复 - 视频消费停止控制链不断 恢复即拉 IDR`() {
        val src = newSource()
        val c = newController()
        val videos = CopyOnWriteArrayList<Framing.VideoFrame>()
        c.onVideo = { videos.add(it) }
        c.start(SessionParams(host = "127.0.0.1", tcpPort = src.boundPort, udpPort = 0, useUdpVideo = false, token = "good-token"))
        assertTrue(awaitPhase(c, SessionPhase.STREAMING))

        // 源端推一帧：挂起前应到达消费端
        src.pushVideoFrame(sequence = 1, keyframe = true, bytes = ByteArray(64) { 1 })
        var deadline = System.currentTimeMillis() + 5000
        while (videos.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("挂起前视频帧应送达消费端", 1, videos.size)

        c.suspendSession()
        assertEquals(SessionPhase.SUSPENDED, c.phase.value)
        assertTrue(c.isSuspended)
        src.pushVideoFrame(sequence = 2, keyframe = true, bytes = ByteArray(64) { 2 })
        Thread.sleep(300)
        assertEquals("挂起后视频消费停止（帧不下发）", 1, videos.size)

        c.resumeSession()
        assertEquals("恢复即回 STREAMING（控制链未断、无需重连）", SessionPhase.STREAMING, c.phase.value)
        deadline = System.currentTimeMillis() + 5000
        while (src.idrRequests.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("恢复应重发 IDR 请求重新拉流", src.idrRequests.isNotEmpty())
        assertEquals("同一 TCP 连接（挂起恢复不断链）", 1, src.connectionCount.get())

        // 恢复后帧恢复下发
        src.pushVideoFrame(sequence = 3, keyframe = true, bytes = ByteArray(64) { 3 })
        deadline = System.currentTimeMillis() + 5000
        while (videos.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(2, videos.size)
    }

    // ── 档位循环切换（重发 START_SESSION 不断链） ──

    @Test
    fun `档位循环 - 1080P60 到 720P60 到 1080P30 到自定义 控制链不断`() = runBlocking {
        val src = newSource()
        val c = newController()
        c.start(SessionParams(host = "127.0.0.1", tcpPort = src.boundPort, udpPort = 0, useUdpVideo = false, token = "good-token"))
        assertTrue(awaitPhase(c, SessionPhase.STREAMING))

        val custom = StreamPreset(900, 1600, 45, 6000, StreamPreset.CUSTOM_LABEL)
        val p1 = c.cyclePreset(custom)
        assertEquals("下一档 720P·60", StreamPreset.P720P_60, p1)
        val p2 = c.cyclePreset(custom)
        assertEquals("再下一档 1080P·30", StreamPreset.P1080P_30, p2)
        val p3 = c.cyclePreset(custom)
        assertEquals("再下一档 自定义", custom, p3)
        val p4 = c.cyclePreset(custom)
        assertEquals("循环回 1080P·60", StreamPreset.P1080P_60, p4)

        val deadline = System.currentTimeMillis() + 5000
        while (src.startSessions.size < 5 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("初始会话 + 每次档位循环各一次 START_SESSION", 5, src.startSessions.size)
        assertEquals(720, src.startSessions[1].width)
        assertEquals(1280, src.startSessions[1].height)
        assertEquals(30, src.startSessions[2].fps)
        assertEquals(900, src.startSessions[3].width)
        assertEquals(6000, src.startSessions[3].bitrateKbps)
        assertEquals(1080, src.startSessions[4].width)
        assertEquals(60, src.startSessions[4].fps)
        assertEquals("档位循环不断链：仍只有一条 TCP 连接", 1, src.connectionCount.get())
        assertEquals("会话保持 STREAMING", SessionPhase.STREAMING, c.phase.value)
    }

    // ── 加密协商（双方开启 → AES-128-GCM） ──

    @Test
    fun `加密协商 - 双端开启后控制通道密文往返`() {
        val src = newSource(negotiateCrypto = true)
        val c = newController()
        c.start(SessionParams(host = "127.0.0.1", tcpPort = src.boundPort, udpPort = 0, useUdpVideo = false, token = "good-token", encryptOn = true))
        assertTrue(awaitPhase(c, SessionPhase.STREAMING))

        assertEquals("协商结果应为 AES-128-GCM", CryptoAlgo.AES128_GCM, c.negotiatedCryptoAlgo)
        assertTrue("HUD 加密徽章应点亮", c.hud.value.encrypted)
        // 密文往返自证：crypto 启用后客户端 INPUT_EVENT 加密发出，源桩解密后字段完好
        c.sendInput(cloudphone.protocol.InputAction.DOWN, 123, 456)
        val deadline = System.currentTimeMillis() + 5000
        while (src.receivedInputs.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, src.receivedInputs.size)
        assertEquals(123, src.receivedInputs[0].x)
        assertEquals(456, src.receivedInputs[0].y)
    }

    // ── 断开（源端会话终止） ──

    @Test
    fun `断开 - STOP_SESSION 下发并回 DISCONNECTED`() {
        val src = newSource()
        val c = newController()
        c.start(SessionParams(host = "127.0.0.1", tcpPort = src.boundPort, udpPort = 0, useUdpVideo = false, token = "good-token"))
        assertTrue(awaitPhase(c, SessionPhase.STREAMING))

        c.disconnect()
        assertEquals(SessionPhase.DISCONNECTED, c.phase.value)
        assertEquals(0, c.currentSessionId)
        val deadline = System.currentTimeMillis() + 5000
        while (src.stopSessions.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("源端应收到 STOP_SESSION（会话正确终止）", 1, src.stopSessions.size)
        assertEquals(42, src.stopSessions[0].sessionId)
    }

    // ── AppListRequest 请求-应答 ──

    @Test
    fun `应用列表 - 请求应答与超时路径`() {
        val src = newSource()
        val c = newController()
        c.start(SessionParams(host = "127.0.0.1", tcpPort = src.boundPort, udpPort = 0, useUdpVideo = false, token = "good-token"))
        assertTrue(awaitPhase(c, SessionPhase.STREAMING))

        // 应答路径：源桩回两枚应用
        src.replyAppList = true
        val msg = runBlocking { c.requestAppList(timeoutMs = 3000) }
        assertNotNull(msg)
        assertEquals(2, msg!!.apps.size)
        assertEquals("com.example.game", msg.apps[0].packageName)

        // 超时路径：关闭应答 → 3s 无应答返回 null（UI 呈现重试入口）
        src.replyAppList = false
        val none = runBlocking { c.requestAppList(timeoutMs = 300) }
        assertNull(none)
    }

    // ── 档位循环序列纯逻辑 ──

    @Test
    fun `PresetCycle 序列 - 循环语义`() {
        val custom = StreamPreset(960, 2160, 45, 12000, StreamPreset.CUSTOM_LABEL)
        assertEquals(StreamPreset.P720P_60, PresetCycle.next("1080P·60", custom))
        assertEquals(StreamPreset.P1080P_30, PresetCycle.next("720P·60", custom))
        assertEquals(custom, PresetCycle.next("1080P·30", custom))
        assertEquals(StreamPreset.P1080P_60, PresetCycle.next(StreamPreset.CUSTOM_LABEL, custom))
        assertEquals("自定义未设置时回落 1080P·60", StreamPreset.P1080P_60, PresetCycle.next("1080P·30", null))
        assertEquals(StreamPreset.P1080P_60, PresetCycle.next(null, custom))
    }
}

/**
 * 本机源桩（真实线格式对端）：Framing 逐记录收发 + Messages 编解码 + 可选 AES-128-GCM 加密，
 * 供 SessionController loopback 单测。握手/会话/输入/辅助应答行为可配置。
 */
class LoopbackSource(
    private val validToken: String = "good-token",
    private val negotiateCrypto: Boolean = false,
    private val hungSessions: List<cloudphone.protocol.HungSession> = emptyList(),
) : Closeable {

    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val boundPort: Int get() = server.localPort

    val hellos = CopyOnWriteArrayList<ControlMessage.Hello>()
    val startSessions = CopyOnWriteArrayList<ControlMessage.StartSession>()
    val stopSessions = CopyOnWriteArrayList<ControlMessage.StopSession>()
    val idrRequests = CopyOnWriteArrayList<ControlMessage.IdrRequest>()
    val receivedInputs = CopyOnWriteArrayList<ControlMessage.InputEvent>()
    val connectionCount = AtomicInteger(0)

    /** START_SESSION 应答后是否主动回 AppList（requestAppList 应答路径用） */
    @Volatile var replyAppList: Boolean = false

    @Volatile private var crypto: CryptoCodec? = null
    private val running = AtomicBoolean(false)
    private val nextSessionId = AtomicInteger(42)
    private var acceptThread: Thread? = null
    @Volatile private var activeSocket: Socket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptThread = thread(isDaemon = true, name = "loopback-source-accept") {
            while (running.get()) {
                val s = try {
                    server.accept()
                } catch (_: Exception) {
                    return@thread
                }
                s.tcpNoDelay = true
                activeSocket = s
                connectionCount.incrementAndGet()
                thread(isDaemon = true, name = "loopback-source-io") { serve(s) }
            }
        }
    }

    private fun serve(s: Socket) {
        val reader = Framing.StreamRecordReader(BufferedInputStream(s.getInputStream(), 64 * 1024))
        val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
        try {
            while (running.get()) {
                val record = reader.read() ?: return
                if (record.type != Framing.RECORD_CONTROL) continue
                val c = crypto
                val payload = if (c != null) {
                    c.decryptControl(record.payload) ?: continue // 认证失败丢弃该记录
                } else record.payload
                when (val msg = decodeControlMessage(payload)) {
                    is ControlMessage.Hello -> handleHello(msg, out)
                    is ControlMessage.StartSession -> {
                        startSessions.add(msg)
                        val sid = if (msg.sessionId != 0) msg.sessionId else nextSessionId.get()
                        reply(
                            ControlMessage.HelloAck(
                                status = HelloStatus.ACCEPTED, reason = "", sessionId = sid,
                                width = msg.width, height = msg.height, fps = msg.fps,
                                bitrateKbps = msg.bitrateKbps, encoder = "stub-encoder", displayId = 0,
                                p2pAnswer = "", hungSessions = emptyList(),
                            ),
                            out,
                        )
                    }
                    is ControlMessage.AppListRequest -> {
                        // 请求-应答制：按请求 requestId 回送（replyAppList=false 时静默 → 客户端超时路径）
                        if (replyAppList) {
                            reply(
                                ControlMessage.AppListMessage(
                                    requestId = msg.requestId,
                                    apps = listOf(
                                        cloudphone.protocol.AppEntry("com.example.game", "示例游戏"),
                                        cloudphone.protocol.AppEntry("com.example.tool", "示例工具"),
                                    ),
                                ),
                                out,
                            )
                        }
                    }
                    is ControlMessage.InputEvent -> receivedInputs.add(msg)
                    is ControlMessage.StopSession -> stopSessions.add(msg)
                    is ControlMessage.IdrRequest -> idrRequests.add(msg)
                    is ControlMessage.Ping -> reply(ControlMessage.Pong(msg.nonce, msg.senderUs, System.nanoTime() / 1000), out)
                    is ControlMessage.AppListRequest -> Unit
                    is ControlMessage.Unknown -> Unit
                    else -> Unit
                }
            }
        } catch (_: Exception) {
            // 连接断开：running 为 false 或对端关闭，静默退出
        }
    }

    private fun handleHello(hello: ControlMessage.Hello, out: BufferedOutputStream) {
        hellos.add(hello)
        if (hello.token != validToken) {
            // token 错配必须显式拒绝码（协议规则，不走超时/未知错误路径）
            reply(
                ControlMessage.HelloAck(
                    status = HelloStatus.REJECT_BAD_TOKEN, reason = "token mismatch", sessionId = 0,
                    width = 0, height = 0, fps = 0, bitrateKbps = 0, encoder = "", displayId = 0,
                    p2pAnswer = "", hungSessions = emptyList(),
                ),
                out,
            )
            return
        }
        if (negotiateCrypto && hello.encryptOn && hello.cryptoAlgo == CryptoAlgo.AES128_GCM) {
            val sourceNonce = CryptoCodec.generateHandshakeNonce()
            // ack 先明文发出（客户端读到 ack 才 enableCrypto），随后本端启用加密
            reply(
                ControlMessage.HelloAck(
                    status = HelloStatus.ACCEPTED, reason = "", sessionId = nextSessionId.get(),
                    width = 1080, height = 1920, fps = 60, bitrateKbps = 8000,
                    encoder = "stub-encoder", displayId = 0, p2pAnswer = "", hungSessions = hungSessions,
                    encryptOn = true, cryptoAlgo = CryptoAlgo.AES128_GCM, cryptoNonce = sourceNonce,
                ),
                out,
                forcePlain = true,
            )
            crypto = CryptoCodec.negotiate(Role.SOURCE, hello.token, hello.cryptoNonce, sourceNonce)
            return
        }
        reply(
            ControlMessage.HelloAck(
                status = HelloStatus.ACCEPTED, reason = "", sessionId = nextSessionId.get(),
                width = 1080, height = 1920, fps = 60, bitrateKbps = 8000,
                encoder = "stub-encoder", displayId = 0, p2pAnswer = "", hungSessions = hungSessions,
            ),
            out,
        )
    }

    private fun reply(msg: ControlMessage, out: BufferedOutputStream, forcePlain: Boolean = false) {
        val payload = encodeControlMessage(msg)
        val c = crypto
        // 握手 ack 先明文发出（客户端读到 ack 才 enableCrypto）；其余消息在加密态全部密文
        val body = if (c != null && !forcePlain) c.encryptControl(payload) else payload
        synchronized(out) {
            Framing.writeRecord(out, Framing.RECORD_CONTROL, body)
            out.flush()
        }
    }

    /** 推一帧视频（RECORD_VIDEO 记录；明文/密文随当前加密态），供挂起/恢复断言消费端 gating */
    fun pushVideoFrame(sequence: Long, keyframe: Boolean, bytes: ByteArray) {
        val s = activeSocket ?: return
        val header = Framing.VideoFrameHeader(sequence, System.nanoTime() / 1000, keyframe, 0, 1, bytes.size)
        val payload = Framing.encodeVideoPayload(header, bytes)
        val c = crypto
        val body = if (c != null) {
            val sealed = c.encryptVideo(sequence, keyframe, 0, bytes)
            Framing.encodeVideoPayload(
                Framing.VideoFrameHeader(sequence, header.ptsUs, keyframe, 0, 1, sealed.size), sealed,
            )
        } else payload
        val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
        synchronized(out) {
            Framing.writeRecord(out, Framing.RECORD_VIDEO, body)
            out.flush()
        }
    }

    override fun close() {
        running.set(false)
        runCatching { activeSocket?.close() }
        runCatching { server.close() }
    }
}
