package cloudphone.transport

import cloudphone.protocol.TransportKind
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * frp 公网隧道后端（真实公网回环）。
 *
 * - TCP 模式：连 [TransportConfig.tcpPort]（frp 面板隧道，M0 = 112.74.85.74:22333），
 *   控制 + 视频复用同一条可靠流（视频按 RECORD_VIDEO 流记录封装）。
 * - UDP 模式：视频通道走 [TransportConfig.udpPort]（M0 = 22334）UDP 数据报（分片+重组）；
 *   连续发送失败自动降级 TCP 复用并记录降级事件（UDP 受限时码率自适应由上层执行）。
 * - 断流自动重连：客户端角色读泵异常/写失败触发后台重连（指数退避 1s→15s），恢复后向上层发
 *   [StreamEvent.RESTORED]（上层凭此重做握手 + IDR 秒回）；源端角色常驻监听，新连接先挂
 *   pending（裸 TCP 探测不惊动会话），首条控制记录（合法 HELLO）经会话占用检查后晋升：
 *   现役客户端在位时回 REJECT_BUSY 拒绝（防双自动重连客户端互踢风暴），占用释放后
 *   新连接自动接入（RESTORED）。
 * - 降级/回落事件全程经 [TransportStats] 坏事件分类暴露。
 */
class FrpBackend(config: TransportConfig) : TransportBackendBase(config) {

    override val wireKind: TransportKind get() = TransportKind.FRP

    private val channel = AtomicReference<TcpStreamChannel?>(null)
    private var udp: UdpVideoChannel? = null
    private var listener: TcpLink.Listener? = null

    /** SOURCE 角色：已 accept 但尚未开口（无控制记录）的连接——不参与现役通道竞争 */
    private val pendingChannels = java.util.concurrent.CopyOnWriteArrayList<TcpStreamChannel>()

    @Volatile private var udpActive = false

    /** 源端：是否已接入过客户端（决定首个连接 CONNECTED 与后续连接 RESTORED 的事件分类） */
    private var sourceSawClient = false
    private val clientReconnecting = AtomicBoolean(false)

    /** 加密下发（协商 AES128GCM 后）：当前 TCP 通道与 UDP 视频通道同步挂 codec；null = 明文 */
    override fun onCryptoAttached(codec: CryptoCodec?) {
        channel.get()?.crypto = codec
        udp?.crypto = codec
    }

    override fun start() {
        checkOpen()
        when (config.role) {
            Role.CLIENT -> openClientStream(restored = false) // 阻塞直至 TCP 建立或失败
            Role.SOURCE -> bindListener()                     // 构造即绑定，accept 异步
        }
        if (config.useUdpVideo && config.udpPort > 0) openUdp()
    }

    // ── TCP 流 ──

    private fun bindListener() {
        val l = try {
            TcpLink.Listener(config.tcpPort)
        } catch (e: IOException) {
            throw TransportException("frp source 监听 ${config.tcpPort} 失败", e)
        }
        listener = l
        Thread({
            while (!closed.get()) {
                val socket = try {
                    l.accept()
                } catch (_: IOException) {
                    return@Thread // listener 已关闭
                }
                // 同 lan-accept：坏连接的 install 异常不得杀死 accept 线程（源端僵尸监听防护）
                try {
                    installChannel(socket, restored = false) // SOURCE 走 pending，晋升时再分类事件
                } catch (t: Throwable) {
                    stats.onBadEvent(TransportStats.BadEvent.STREAM_ERROR, "accept install: ${t.message}")
                    runCatching { socket.close() }
                }
            }
        }, "frp-accept").apply { isDaemon = true; start() }
    }

    private fun openClientStream(restored: Boolean) {
        val socket = try {
            TcpLink.connect(config)
        } catch (e: IOException) {
            if (restored) throw e
            throw TransportException("frp tcp ${config.host}:${config.tcpPort} 连接失败", e)
        }
        installChannel(socket, restored)
    }

    /** 装配一条已连接流（读泵 + 事件通知）；角色分流：CLIENT 立即替换，SOURCE 先挂 pending */
    private fun installChannel(socket: Socket, restored: Boolean) {
        if (config.role == Role.CLIENT) {
            val old = channel.getAndSet(null)
            old?.retire()
            old?.close()
            val ch = TcpStreamChannel(socket, stats, this, onError = { reason ->
                onStreamBroken(reason.message ?: "io")
            })
            channel.set(ch)
            ch.startReading("frp")
            if (restored) {
                stats.onBadEvent(TransportStats.BadEvent.RECONNECT, "stream restored")
            }
            onStreamEvent?.onEvent(
                if (restored) StreamEvent.RESTORED else StreamEvent.CONNECTED,
                "frp tcp ${socket.inetAddress?.hostAddress}:${socket.port}",
            )
            return
        }
        // SOURCE：新连接先挂 pending，绝不立刻替换现役通道（同 lan-direct）。
        // 背景：任何裸 TCP 连接（frpc 探活/端口扫描/重连风暴中的第二个客户端）都会在
        // accept 后立即掐死现役通道——双自动重连客户端互踢形成"连接断开，正在重连"
        // 死循环。只有真正说出第一条控制记录（合法 HELLO）的连接才经占用检查晋升。
        val plumbing = ChannelPlumbing()
        val ch = TcpStreamChannel(socket, stats, plumbing, onError = { reason ->
            plumbing.ch?.let { onChannelDead(it, reason) }
        })
        plumbing.ch = ch
        pendingChannels.add(ch)
        ch.startReading("frp")
    }

    /** pending 通道首条控制记录：会话占用检查后晋升为现役通道（旧通道延迟退役） */
    private fun onChannelControl(ch: TcpStreamChannel, payload: ByteArray) {
        if (pendingChannels.remove(ch)) {
            val busyText = sourceBusyText
            val hasIncumbent = channel.get() != null
            if (hasIncumbent && busyText.isNotEmpty()) {
                val wantsTakeover = wantsTakeover(payload)
                if (!wantsTakeover) {
                    rejectPendingChannel(ch, busyText, "frp")
                    return
                }
                // 抢占：跳过占用拒绝走正常晋升（getAndSet+retire 即踢掉现役观看端）
                stats.onBadEvent(TransportStats.BadEvent.RECONNECT, "frp takeover preempted incumbent")
            }
            val old = channel.getAndSet(ch)
            val wasActive = old != null
            if (old != null && old !== ch) retireChannel(old)
            val restored = wasActive || sourceSawClient
            sourceSawClient = true
            if (wasActive) stats.onBadEvent(TransportStats.BadEvent.RECONNECT, "frp client re-connected")
            onStreamEvent?.onEvent(
                if (restored) StreamEvent.RESTORED else StreamEvent.CONNECTED,
                "frp promote",
            )
        }
        onControl?.onControl(payload)
    }

    /** 通道断开：现役通道死亡才向全局报 DISCONNECTED；pending/退役通道静默清理 */
    private fun onChannelDead(ch: TcpStreamChannel, reason: IOException) {
        if (pendingChannels.remove(ch)) return
        if (channel.compareAndSet(ch, null) && !closed.get()) {
            stats.onBadEvent(TransportStats.BadEvent.DISCONNECT, reason.message ?: "io")
            onStreamEvent?.onEvent(StreamEvent.DISCONNECTED, reason.message ?: "io")
        }
    }

    private fun retireChannel(old: TcpStreamChannel) {
        old.retire() // 退役通道静默关闭：读泵不再把回收时的 SocketException 误报为断开
        Thread({
            try {
                Thread.sleep(3000)
            } catch (_: InterruptedException) {
            }
            try {
                old.close()
            } catch (_: IOException) {
            }
        }, "frp-old-channel-reap").apply { isDaemon = true; start() }
    }

    /** 解析 HELLO 抢占标志（非 HELLO/解析失败一律 false，保持占用保护默认语义） */
    private fun wantsTakeover(payload: ByteArray): Boolean =
        runCatching {
            (cloudphone.protocol.decodeControlMessage(payload) as? cloudphone.protocol.ControlMessage.Hello)?.takeover == true
        }.getOrDefault(false)

    /**
     * 会话占用拒绝：对 pending 通道回明文 HelloAck(REJECT_BUSY) 后延迟回收。
     * ack 经 writeControl 立即出栈（握手期未协商加密恒明文）；retireChannel 的 3s 延迟
     * 关闭保证对端先读到拒绝应答再见 FIN。
     */
    private fun rejectPendingChannel(ch: TcpStreamChannel, reason: String, tag: String) {
        pendingChannels.remove(ch)
        try {
            ch.writeControl(cloudphone.protocol.encodeControlMessage(rejectBusyAck(reason)))
        } catch (_: Exception) {
        }
        stats.onBadEvent(TransportStats.BadEvent.REJECT_BUSY, "$tag: $reason")
        retireChannel(ch)
    }

    private fun rejectBusyAck(reason: String) = cloudphone.protocol.ControlMessage.HelloAck(
        status = cloudphone.protocol.HelloStatus.REJECT_BUSY,
        reason = reason,
        sessionId = 0,
        width = 0, height = 0, fps = 0, bitrateKbps = 0,
        encoder = "",
        displayId = -1,
        p2pAnswer = "",
        hungSessions = emptyList(),
    )

    /** 每通道回调包装：把控制记录路由到晋升逻辑，视频照旧上抛 */
    private inner class ChannelPlumbing : ChannelCallbacks {
        var ch: TcpStreamChannel? = null
        override fun controlSink(): ControlSink? = ControlSink { payload ->
            ch?.let { onChannelControl(it, payload) }
        }
        override fun videoSink(): VideoSink? = this@FrpBackend.videoSink()
    }

    /** 链路断开：源端回到监听等下一个客户端；客户端进入退避重连循环 */
    private fun onStreamBroken(reason: String) {
        if (closed.get()) return
        val old = channel.getAndSet(null) ?: return
        old.close()
        stats.onBadEvent(TransportStats.BadEvent.DISCONNECT, reason)
        onStreamEvent?.onEvent(StreamEvent.DISCONNECTED, reason)
        when (config.role) {
            Role.SOURCE -> Unit // 常驻监听已就绪，等下一个客户端连接
            Role.CLIENT -> scheduleClientReconnect(reason)
        }
    }

    private fun scheduleClientReconnect(reason: String) {
        if (!clientReconnecting.compareAndSet(false, true)) return
        Thread({
            var backoffMs = INITIAL_RECONNECT_BACKOFF_MS
            while (!closed.get()) {
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_RECONNECT_BACKOFF_MS)
                try {
                    openClientStream(restored = true)
                    clientReconnecting.set(false)
                    return@Thread
                } catch (_: IOException) {
                    // 公网隧道暂不可达，退避后继续重试（会话挂机语义由上层维护）
                }
            }
        }, "frp-reconnect").apply { isDaemon = true; start() }
    }

    // ── UDP 视频 ──

    private fun openUdp() {
        val socket = try {
            when (config.role) {
                Role.CLIENT -> DatagramSocket()
                Role.SOURCE -> DatagramSocket(config.udpPort) // 从首包学习对端地址
            }
        } catch (e: IOException) {
            stats.onBadEvent(TransportStats.BadEvent.UDP_TO_TCP_DEGRADE, "udp bind fail: ${e.message}")
            udpActive = false
            return
        }
        val ch = UdpVideoChannel(
            socket = socket,
            fragmentBytes = config.fragmentBytes,
            stats = stats,
            callbacks = this,
            onError = { /* 接收侧 IO 异常：保留 TCP 通路，不致命 */ },
        )
        if (config.role == Role.CLIENT) {
            ch.setRemote(InetSocketAddress(config.host, config.udpPort))
        }
        udp = ch
        udpActive = true
        ch.startReading("frp")
    }

    override fun sendControl(payload: ByteArray) {
        checkOpen()
        val ch = channel.get() ?: throw TransportException("frp control channel down (reconnecting)")
        try {
            ch.writeControl(payload)
        } catch (e: IOException) {
            onStreamBroken("control write: ${e.message}")
            throw TransportException("frp control send failed", e)
        }
    }

    override fun sendVideo(sequence: Long, ptsUs: Long, keyframe: Boolean, frame: ByteArray) {
        checkOpen()
        if (udpActive) {
            when (udp?.send(sequence, ptsUs, keyframe, frame)) {
                UdpVideoChannel.SendResult.SENT -> return
                UdpVideoChannel.SendResult.NO_PEER -> {
                    // 对端 UDP 地址未知（如源端尚未收到客户端首包）：本帧临时走 TCP，不降级
                    val ch = channel.get() ?: throw TransportException("frp video path down (reconnecting)")
                    ch.writeVideo(sequence, ptsUs, keyframe, frame)
                    return
                }
                UdpVideoChannel.SendResult.FAILED -> {
                    // UDP 通道坏死（连续失败达阈值）：永久降级 TCP 复用并记录，会话不中断
                    udpActive = false
                    onStreamEvent?.onEvent(StreamEvent.UDP_DEGRADED, "udp video channel dead, video over tcp")
                }
                null -> {}
            }
        }
        val ch = channel.get() ?: throw TransportException("frp video path down (reconnecting)")
        try {
            ch.writeVideo(sequence, ptsUs, keyframe, frame)
        } catch (e: IOException) {
            onStreamBroken("video write: ${e.message}")
            throw TransportException("frp video send failed", e)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        channel.getAndSet(null)?.close()
        pendingChannels.forEach { it.close() }
        pendingChannels.clear()
        udp?.close()
        listener?.close()
    }

    companion object {
        /** M0 frp 面板隧道公网端点（真实公网回环） */
        const val DEFAULT_HOST = "112.74.85.74"
        const val DEFAULT_TCP_PORT = 22333
        const val DEFAULT_UDP_PORT = 22334

        const val INITIAL_RECONNECT_BACKOFF_MS = 1000L
        const val MAX_RECONNECT_BACKOFF_MS = 15_000L

        fun defaultConfig(role: Role, useUdpVideo: Boolean = true): TransportConfig = TransportConfig(
            role = role,
            kind = BackendKind.FRP,
            host = DEFAULT_HOST,
            tcpPort = DEFAULT_TCP_PORT,
            udpPort = DEFAULT_UDP_PORT,
            useUdpVideo = useUdpVideo,
        )
    }
}
