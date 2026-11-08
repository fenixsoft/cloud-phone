package cloudphone.transport

import cloudphone.protocol.Framing
import cloudphone.protocol.TransportKind
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * p2p 后端：STUN 地址发现 + frp 承载信令 + 双端同时 UDP 发包打洞。
 *
 * 流程：
 * 1. 控制通道经 frp-TCP（[TransportConfig.tcpPort]）建立——信令（offer/answer）即
 *    HELLO.p2pOffer / HELLO_ACK.p2pAnswer（上层会话握手时携带，格式 "ip:port"）。
 * 2. [start] 时经 STUN 多节点发现本端 SRFLX（[localSrflx]），上层放入 offer。
 * 3. 对端 answer 到达后上层调 [onPeerSrflx]：双端同时向对方 SRFLX 每 200ms 发打洞探针
 *    （合法 1 字节视频载荷帧，接收侧自然并入重组器），任一探针从对端到达即直连建立，
 *    视频通道切 UDP 直连（STATS.transport=p2p）。
 * 4. [punchTimeoutMs]（M0 = 5s）未成：上报失败并自动回落 frp（视频走控制 TCP 复用），
 *    记录 PUNCH_TIMEOUT + PUNCH_FALLBACK_FRP 事件，会话不中断；后台继续打洞，
 *    后续打通仍可切直连（PUNCH_ESTABLISHED 再通知）。
 *
 * NAT 对称型对端必然回落——本后端保证逻辑正确性与优雅降级；打洞成功率由真机复测验证
 * （同主机双模拟器拓扑可能"假成功"，见 design.md 开放问题）。
 */
class P2pBackend(
    config: TransportConfig,
    private val stunServers: List<StunClient.StunServer> = StunClient.DEFAULT_SERVERS,
    private val punchTimeoutMs: Long = PUNCH_TIMEOUT_DEFAULT_MS,
) : TransportBackendBase(config) {

    override val wireKind: TransportKind
        get() = when {
            directEstablished.get() -> TransportKind.P2P
            fellBack.get() -> TransportKind.P2P_FALLBACK_FRP
            else -> TransportKind.P2P
        }

    private val channel = java.util.concurrent.atomic.AtomicReference<TcpStreamChannel?>(null)
    private var listener: TcpLink.Listener? = null
    private var punchSocket: DatagramSocket? = null
    private var videoChannel: UdpVideoChannel? = null

    /** 本端 SRFLX 地址（上层放入 HELLO.p2pOffer） */
    @Volatile var localSrflx: InetSocketAddress? = null
        private set

    private val directEstablished = AtomicBoolean(false)
    private val fellBack = AtomicBoolean(false)
    private val punchStarted = AtomicBoolean(false)

    private var sourceSawClient = false

    override fun start() {
        checkOpen()
        when (config.role) {
            Role.CLIENT -> openControlStream(restored = false)
            Role.SOURCE -> bindListener()
        }
        openPunchSocket()
        // SRFLX 发现（打洞 socket 上多节点探测）
        val socket = punchSocket ?: return
        val srflx = StunClient.discoverSrflx(socket, stunServers)
        if (srflx == null) {
            stats.onBadEvent(TransportStats.BadEvent.PUNCH_TIMEOUT, "STUN unreachable, p2p unavailable")
            stats.onBadEvent(TransportStats.BadEvent.PUNCH_FALLBACK_FRP, "stun discovery failed, video over frp tcp")
            fellBack.set(true) // 直接具备回落形态：视频走 TCP 复用
            onStreamEvent?.onEvent(StreamEvent.PUNCH_FALLBACK_FRP, "stun discovery failed")
            return
        }
        localSrflx = InetSocketAddress(srflx.mappedIp, srflx.mappedPort)
    }

    /** 上层收到对端 answer（HELLO_ACK.p2pAnswer 或源端收到 HELLO.p2pOffer）后调用 */
    fun onPeerSrflx(peer: InetSocketAddress) {
        checkOpen()
        if (!punchStarted.compareAndSet(false, true)) return
        val socket = punchSocket ?: return
        val vc = UdpVideoChannel(
            socket = socket,
            fragmentBytes = config.fragmentBytes,
            stats = stats,
            callbacks = this,
        )
        vc.onFirstPacket = { from ->
            // 对端任一数据报到达 = 洞已打通（探针或视频分片皆可）
            if (directEstablished.compareAndSet(false, true)) {
                onStreamEvent?.onEvent(StreamEvent.PUNCH_ESTABLISHED, "p2p udp direct from $from")
            }
        }
        vc.setRemote(peer)
        videoChannel = vc
        vc.startReading("p2p")
        // 双端同时向对端 SRFLX 发探针（sequence=0 保留探针帧，接收侧不计统计不下发解码）
        Thread({
            val probe = ByteArray(1) { 0x50 }
            while (!closed.get() && !directEstablished.get()) {
                vc.send(sequence = UdpVideoChannel.PUNCH_PROBE_SEQUENCE, ptsUs = 0, keyframe = false, frame = probe)
                try {
                    Thread.sleep(PUNCH_PROBE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "p2p-punch").apply { isDaemon = true; start() }
        // 5s 打洞定时器
        Thread({
            try {
                Thread.sleep(punchTimeoutMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!closed.get() && !directEstablished.get() && fellBack.compareAndSet(false, true)) {
                stats.onBadEvent(TransportStats.BadEvent.PUNCH_TIMEOUT, "no direct udp in ${punchTimeoutMs}ms")
                stats.onBadEvent(TransportStats.BadEvent.PUNCH_FALLBACK_FRP, "video over frp tcp")
                onStreamEvent?.onEvent(StreamEvent.PUNCH_FALLBACK_FRP, "video over frp tcp, punch continues")
            }
        }, "p2p-punch-timer").apply { isDaemon = true; start() }
    }

    // ── 控制通道（frp 承载信令；回落期亦承载视频）──

    private fun bindListener() {
        val l = try {
            TcpLink.Listener(config.tcpPort)
        } catch (e: IOException) {
            throw TransportException("p2p source 信令监听 ${config.tcpPort} 失败", e)
        }
        listener = l
        Thread({
            while (!closed.get()) {
                val socket = try {
                    l.accept()
                } catch (_: IOException) {
                    return@Thread
                }
                installChannel(socket, restored = sourceSawClient)
                sourceSawClient = true
            }
        }, "p2p-accept").apply { isDaemon = true; start() }
    }

    private fun openControlStream(restored: Boolean) {
        val socket = try {
            TcpLink.connect(config)
        } catch (e: IOException) {
            if (restored) throw e
            throw TransportException("p2p 信令通道 ${config.host}:${config.tcpPort} 连接失败", e)
        }
        installChannel(socket, restored)
    }

    private fun installChannel(socket: Socket, restored: Boolean) {
        channel.getAndSet(null)?.close()
        val ch = TcpStreamChannel(socket, stats, this, onError = { reason ->
            onControlBroken(reason.message ?: "io")
        })
        channel.set(ch)
        ch.startReading("p2p-ctl")
        if (restored) {
            stats.onBadEvent(TransportStats.BadEvent.RECONNECT, "signaling restored")
        }
        onStreamEvent?.onEvent(
            if (restored) StreamEvent.RESTORED else StreamEvent.CONNECTED,
            "p2p signaling ${socket.inetAddress?.hostAddress}:${socket.port}",
        )
    }

    private fun onControlBroken(reason: String) {
        if (closed.get()) return
        val old = channel.getAndSet(null) ?: return
        old.close()
        stats.onBadEvent(TransportStats.BadEvent.DISCONNECT, reason)
        onStreamEvent?.onEvent(StreamEvent.DISCONNECTED, reason)
    }

    private fun openPunchSocket() {
        punchSocket = try {
            when (config.role) {
                Role.CLIENT -> DatagramSocket()
                Role.SOURCE -> DatagramSocket(config.udpPort)
            }
        } catch (e: IOException) {
            stats.onBadEvent(TransportStats.BadEvent.PUNCH_TIMEOUT, "punch socket bind fail: ${e.message}")
            fellBack.set(true)
            null
        }
    }

    override fun sendControl(payload: ByteArray) {
        checkOpen()
        val ch = channel.get() ?: throw TransportException("p2p signaling channel down")
        try {
            ch.writeControl(payload)
        } catch (e: IOException) {
            onControlBroken("control write: ${e.message}")
            throw TransportException("p2p signaling send failed", e)
        }
    }

    override fun sendVideo(sequence: Long, ptsUs: Long, keyframe: Boolean, frame: ByteArray) {
        checkOpen()
        val vc = videoChannel
        if (vc != null && (directEstablished.get() || !fellBack.get())) {
            // 未回落前持续尝试 UDP（探针在途也可能即刻打通）；回落后仅直连已成才走 UDP
            when (vc.send(sequence, ptsUs, keyframe, frame)) {
                UdpVideoChannel.SendResult.SENT -> return
                UdpVideoChannel.SendResult.NO_PEER -> Unit // 对端地址未知，走 TCP
                UdpVideoChannel.SendResult.FAILED -> {
                    fellBack.set(true)
                    onStreamEvent?.onEvent(StreamEvent.UDP_DEGRADED, "p2p udp dead, video over frp tcp")
                }
            }
        }
        val ch = channel.get() ?: throw TransportException("p2p video path down")
        try {
            ch.writeVideo(sequence, ptsUs, keyframe, frame)
        } catch (e: IOException) {
            onControlBroken("video write: ${e.message}")
            throw TransportException("p2p video send failed", e)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        channel.getAndSet(null)?.close()
        videoChannel?.close()
        punchSocket?.close()
        listener?.close()
    }

    companion object {
        /** 打洞探针间隔 */
        const val PUNCH_PROBE_INTERVAL_MS = 200L

        /** 打洞超时（spec：5s 未成回落 frp） */
        const val PUNCH_TIMEOUT_DEFAULT_MS = 5000L

        fun defaultConfig(role: Role, frpHost: String = FrpBackend.DEFAULT_HOST): TransportConfig =
            TransportConfig(
                role = role,
                kind = BackendKind.P2P,
                host = frpHost,
                tcpPort = FrpBackend.DEFAULT_TCP_PORT,
                udpPort = FrpBackend.DEFAULT_UDP_PORT,
                useUdpVideo = true,
            )
    }
}
