package cloudphone.transport

import cloudphone.protocol.TransportKind
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

/**
 * lan-direct 局域网直连后端（双模拟器拓扑下"局域网" = 同主机一跳）。
 *
 * - 控制通道：源端监听 [TransportConfig.tcpPort]（构造即绑定），主机侧经 `adb forward`
 *   把源端模拟器端口暴露到主机环回（bootstrap_adb.sh 负责），客户端连 10.0.2.2:forwardPort
 *   一跳直连。
 * - 视频通道：主机侧 udp_relay.py（adb 不支持 UDP 转发）中继环回 [TransportConfig.udpPort]
 *   进源端模拟器；客户端 UDP 同样发往 10.0.2.2。UDP 失败自动降级 TCP 复用并记录。
 * - 同主机环回 RTT ≤5ms 量级（PING/PONG 实测经 STATS.rttMs 上报）。
 *
 * 与 frp 后端共用 [TcpStreamChannel]/[UdpVideoChannel] 机制，仅端点与重连策略不同：
 * 环回链路无自动重连循环——断开记 DISCONNECT 事件并回到监听，由上层决定会话重建。
 */
class LanDirectBackend(config: TransportConfig) : TransportBackendBase(config) {

    override val wireKind: TransportKind get() = TransportKind.LAN_DIRECT

    private var channel: TcpStreamChannel? = null
    private var udp: UdpVideoChannel? = null
    private var listener: TcpLink.Listener? = null

    @Volatile private var udpActive = false
    private var sourceSawClient = false

    override fun start() {
        checkOpen()
        when (config.role) {
            Role.CLIENT -> connectStream()
            Role.SOURCE -> bindListener()
        }
        if (config.useUdpVideo && config.udpPort > 0) openUdp()
    }

    private fun bindListener() {
        val l = try {
            TcpLink.Listener(config.tcpPort)
        } catch (e: IOException) {
            throw TransportException("lan-direct source 监听 ${config.tcpPort} 失败", e)
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
        }, "lan-accept").apply { isDaemon = true; start() }
    }

    private fun connectStream() {
        val socket = try {
            TcpLink.connect(config)
        } catch (e: IOException) {
            throw TransportException(
                "lan-direct tcp ${config.host}:${config.tcpPort} 连接失败（检查 adb forward 是否就绪）", e,
            )
        }
        installChannel(socket, restored = false)
    }

    private fun installChannel(socket: Socket, restored: Boolean) {
        // 旧通道延迟关闭：新连接立即掐死旧通道曾吞掉在途控制应答
        // （两端重连竞态时 START_SESSION ack 写出后旧 socket 即关，客户端永远收不到）
        val old = channel
        channel = TcpStreamChannel(socket, stats, this, onError = { reason ->
            if (!closed.get()) {
                stats.onBadEvent(TransportStats.BadEvent.DISCONNECT, reason.message ?: "io")
                onStreamEvent?.onEvent(StreamEvent.DISCONNECTED, reason.message ?: "io")
            }
        }).also { ch ->
            ch.startReading("lan")
        }
        if (old != null) {
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
            }, "lan-old-channel-reap").apply { isDaemon = true; start() }
        }
        if (restored) {
            stats.onBadEvent(TransportStats.BadEvent.RECONNECT, "lan client re-connected")
        }
        onStreamEvent?.onEvent(
            if (restored) StreamEvent.RESTORED else StreamEvent.CONNECTED,
            "lan-direct ${socket.inetAddress?.hostAddress}:${socket.port}",
        )
    }

    private fun openUdp() {
        val socket = try {
            when (config.role) {
                Role.CLIENT -> DatagramSocket()
                Role.SOURCE -> DatagramSocket(config.udpPort)
            }
        } catch (e: IOException) {
            stats.onBadEvent(TransportStats.BadEvent.UDP_TO_TCP_DEGRADE, "udp bind fail: ${e.message}")
            null
        }
        socket?.let {
            // lan 直连不首包学习（learnFromFirstPacket=false）：共享宿主端口上的无关 UDP 流量
            // （frpc/adb 重定向/残留代理）曾把视频远端污染到黑洞；lan 的对端地址只来自显式 setRemote
            val u = UdpVideoChannel(it, config.fragmentBytes, stats, this, learnFromFirstPacket = false)
            if (config.role == Role.CLIENT) {
                u.setRemote(InetSocketAddress(config.host, config.udpPort))
            }
            udp = u
            udpActive = true
            u.startReading("lan")
        }
    }

    override fun sendControl(payload: ByteArray) {
        checkOpen()
        val ch = channel ?: throw TransportException("lan-direct control channel down")
        try {
            ch.writeControl(payload)
        } catch (e: IOException) {
            stats.onBadEvent(TransportStats.BadEvent.DISCONNECT, "control write: ${e.message}")
            throw TransportException("lan-direct control send failed", e)
        }
    }

    override fun sendVideo(sequence: Long, ptsUs: Long, keyframe: Boolean, frame: ByteArray) {
        checkOpen()
        if (udpActive) {
            when (udp?.send(sequence, ptsUs, keyframe, frame)) {
                UdpVideoChannel.SendResult.SENT -> return
                UdpVideoChannel.SendResult.NO_PEER -> {
                    val ch = channel ?: throw TransportException("lan-direct video path down")
                    try {
                        ch.writeVideo(sequence, ptsUs, keyframe, frame)
                    } catch (e: IOException) {
                        throw TransportException("lan-direct video send failed", e)
                    }
                    return
                }
                UdpVideoChannel.SendResult.FAILED -> {
                    udpActive = false
                    onStreamEvent?.onEvent(StreamEvent.UDP_DEGRADED, "lan udp video channel dead, video over tcp")
                }
                null -> {}
            }
        }
        val ch = channel ?: throw TransportException("lan-direct video path down")
        try {
            ch.writeVideo(sequence, ptsUs, keyframe, frame)
        } catch (e: IOException) {
            throw TransportException("lan-direct video send failed", e)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        channel?.close()
        udp?.close()
        listener?.close()
    }

    companion object {
        /** 模拟器内访问主机环回的固定地址（qemu 用户态 NAT 网关） */
        const val EMULATOR_HOST_LOOPBACK = "10.0.2.2"

        /** 默认端点：主机环回 22333(TCP)/22334(UDP)，与 frp 隧道端口对齐便于脚本编排 */
        const val DEFAULT_TCP_PORT = 22333
        const val DEFAULT_UDP_PORT = 22334

        fun defaultConfig(role: Role, useUdpVideo: Boolean = false): TransportConfig = TransportConfig(
            role = role,
            kind = BackendKind.LAN_DIRECT,
            host = EMULATOR_HOST_LOOPBACK,
            tcpPort = DEFAULT_TCP_PORT,
            udpPort = DEFAULT_UDP_PORT,
            useUdpVideo = useUdpVideo,
        )
    }
}
