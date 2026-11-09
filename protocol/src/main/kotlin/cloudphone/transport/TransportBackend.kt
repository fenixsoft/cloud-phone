package cloudphone.transport

import cloudphone.protocol.Framing
import cloudphone.protocol.ProtocolException
import cloudphone.protocol.TransportKind
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 传输后端类别（配置切换键） */
enum class BackendKind { LAN_DIRECT, P2P, FRP }

/** 端角色：源端 accept / 客户端 connect */
enum class Role { SOURCE, CLIENT }

/**
 * 传输配置 —— 三后端（lan-direct / p2p / frp）凭 [kind] + 本配置切换，
 * 对上层管线完全透明（切换后端不改采集/编码/解码管线代码）。
 */
data class TransportConfig(
    val role: Role,
    val kind: BackendKind,
    /** 客户端连接目标主机（源端忽略）；模拟器内经 10.0.2.2 达主机环回/公网 */
    val host: String,
    /** 控制通道 TCP 端口（frp=22333 / lan=forward 后的环回端口） */
    val tcpPort: Int,
    /** 视频通道 UDP 端口（frp=22334 / lan=udp_relay 端口；0 = 不走 UDP） */
    val udpPort: Int,
    /** 是否启用 UDP 视频通道（失败自动降级 TCP 复用） */
    val useUdpVideo: Boolean = true,
    /** UDP 单数据报载荷上限（含 21B 帧头） */
    val fragmentBytes: Int = Framing.VideoFragmenter.DEFAULT_MAX_DATAGRAM,
)

/** 传输层异常（连接失败/链路损坏） */
class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 收到对端控制消息（已按长度前缀切片） */
fun interface ControlSink {
    fun onControl(payload: ByteArray)
}

/** 收到完整视频帧（UDP 重组或 TCP 复用解出） */
fun interface VideoSink {
    fun onVideo(frame: Framing.VideoFrame)
}

/** 通道分发时实时取回调（允许上层在 start() 后再挂回调） */
interface ChannelCallbacks {
    fun controlSink(): ControlSink?
    fun videoSink(): VideoSink?
}

/** 链路事件（供上层在重连后重做握手/重新请求 IDR） */
enum class StreamEvent {
    CONNECTED,           // 控制链路建立（可开始握手）
    DISCONNECTED,        // 控制链路断开
    RESTORED,            // 链路恢复（重连成功/客户端重入）
    PUNCH_ESTABLISHED,   // p2p UDP 直连打通（视频切直连）
    PUNCH_FALLBACK_FRP,  // p2p 打洞超时回落 frp（视频走 TCP 复用，会话不中断）
    UDP_DEGRADED,        // UDP 视频通道坏死降级 TCP 复用
    PUNCH_MIGRATED,      // 端点迁移：对端候选地址更新（会话/统计不重置，直连重建后重新报 ESTABLISHED）
}

fun interface StreamEventListener {
    fun onEvent(event: StreamEvent, detail: String)
}

/**
 * 传输后端统一抽象：connect/accept（按 [TransportConfig.role]）/send/recv（回调）/close/stats。
 * 三后端对上层暴露同一接口；STATS ≥1Hz 经 [stats.snapshot] 取数。
 */
interface TransportBackend : Closeable {
    val config: TransportConfig
    val stats: TransportStats

    /** 线上路径类型（STATS.transport 字段取值） */
    val wireKind: TransportKind

    /** 阻塞建立链路；成功返回后即可收发。失败抛 [TransportException]。 */
    fun start()

    /** 可靠控制通道发送（控制消息编码字节，不含记录头） */
    fun sendControl(payload: ByteArray)

    /** 视频整帧发送（后端负责 UDP 分片或 TCP 复用封装与降级） */
    fun sendVideo(sequence: Long, ptsUs: Long, keyframe: Boolean, frame: ByteArray)

    var onControl: ControlSink?
    var onVideo: VideoSink?
    var onStreamEvent: StreamEventListener?

    /** 当前统计快照（≥1Hz 由调用方驱动） */
    fun snapshot(): TransportStats.Snapshot = stats.snapshot()

    /**
     * 启用/更换会话加密（p2p-crossnet-traversal 1.4）：握手协商结果 = AES128GCM 时
     * 上层传入按协商材料构造的 [CryptoCodec]（方向子钥内建），此后控制与视频通道按
     * direction 加解密；协商 none 或重连未协商时传 null —— 通道走明文，路径与 v1
     * 现状逐字节一致。重连后必须重新握手协商（新 codec 实例，counter 全新）。
     */
    fun enableCrypto(codec: CryptoCodec?)
}

/** 后端公共基座：回调持有 + 发送序号默认自增（上层亦可显式指定 sequence） */
abstract class TransportBackendBase(final override val config: TransportConfig) : TransportBackend, ChannelCallbacks {
    final override val stats: TransportStats = TransportStats()
    override var onControl: ControlSink? = null
    override var onVideo: VideoSink? = null
    override var onStreamEvent: StreamEventListener? = null

    /** 会话加密编解码器（null = 明文）；enableCrypto 下发并同步到当前通道 */
    var sessionCrypto: CryptoCodec? = null
        private set

    final override fun enableCrypto(codec: CryptoCodec?) {
        sessionCrypto = codec
        onCryptoAttached(codec)
    }

    /** 子类把 codec 下发到当前活跃通道（TCP/UDP channel 的 volatile crypto 字段） */
    protected open fun onCryptoAttached(codec: CryptoCodec?) {}

    final override fun controlSink(): ControlSink? = onControl
    final override fun videoSink(): VideoSink? = onVideo

    protected val closed = AtomicBoolean(false)
    private val nextSequence = AtomicLong(1)

    protected fun nextSeq(): Long = nextSequence.getAndIncrement()

    protected fun checkOpen() {
        if (closed.get()) throw TransportException("transport closed")
    }
}

/**
 * TCP 流半桥：负责一条可靠流上的记录收发（控制 + 可选视频复用）。
 * 源端 accept / 客户端 connect 均收敛到持有已连接 [Socket] 的实例。
 */
class TcpStreamChannel(
    val socket: Socket,
    private val stats: TransportStats,
    private val callbacks: ChannelCallbacks,
    private val onError: (IOException) -> Unit,
) : Closeable {

    private val output: OutputStream = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
    @Volatile private var reading = false
    @Volatile private var retired = false
    private var readerThread: Thread? = null

    /** 会话加密（协商成功后由后端 onCryptoAttached 下发；null = 明文，与 v1 逐字节一致） */
    @Volatile var crypto: CryptoCodec? = null

    /**
     * 退役：被更新通道替换后，读泵静默退出——既有 socket 上后续的读异常
     * （含延迟/立即 close 触发的 SocketException）不再触发 onError，
     * 否则会向全局误报 DISCONNECTED，令客户端把新连接也拆掉重连（3s 重连风暴根因）。
     */
    fun retire() {
        retired = true
        reading = false
    }

    /** 启动接收泵（单线程阻塞读，逐记录分发） */
    fun startReading(name: String) {
        check(!reading)
        reading = true
        readerThread = Thread({
            val reader = Framing.StreamRecordReader(BufferedInputStream(socket.getInputStream(), 64 * 1024))
            try {
                while (reading) {
                    // EOF（对端正常关闭）按断流处理：触发 onError → DISCONNECT 事件/重连
                    val record = reader.read() ?: throw java.io.EOFException("peer closed stream")
                    when (record.type) {
                        Framing.RECORD_CONTROL -> {
                            val c = crypto
                            var payload = record.payload
                            if (c != null) {
                                // 加密态：按记录到达顺序重建 control counter；认证失败丢弃该记录
                                // （长度前缀分帧不受影响，继续读后续记录；明文/密钥错配的瞬态竞态
                                // 只损失单条消息，不升级为断流重连风暴）
                                val plain = c.decryptControl(record.payload)
                                if (plain == null) {
                                    stats.onBadEvent(TransportStats.BadEvent.STREAM_ERROR, "control decrypt")
                                    continue
                                }
                                payload = plain
                            }
                            callbacks.controlSink()?.onControl(payload)
                        }
                        Framing.RECORD_VIDEO -> {
                            val f = decodeVideoRecord(record.payload, crypto)
                            if (f == null) {
                                stats.onBadEvent(TransportStats.BadEvent.STREAM_ERROR, "video record decode")
                            } else {
                                stats.onFrameReceived(f.data.size)
                                stats.onVideoSequence(f.sequence)
                                callbacks.videoSink()?.onVideo(f)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (reading && !retired) onError(e)
            } catch (e: ProtocolException) {
                stats.onBadEvent(TransportStats.BadEvent.STREAM_ERROR, e.message ?: "protocol")
                if (reading && !retired) onError(IOException(e.message, e))
            } finally {
                reading = false
            }
        }, "tcp-stream-$name").apply { isDaemon = true; start() }
    }

    /** TCP 复用模式发视频：单分片视频载荷套 RECORD_VIDEO 记录（加密态下分片载荷按 AAD=sequence/flags 加密） */
    fun writeVideo(sequence: Long, ptsUs: Long, keyframe: Boolean, frame: ByteArray) {
        val c = crypto
        // 探针帧（sequence=0）恒明文（打洞期对端尚无密钥）
        val data = if (c != null && sequence != UdpVideoChannel.PUNCH_PROBE_SEQUENCE) {
            c.encryptVideo(sequence, keyframe, 0, frame)
        } else frame
        val payload = Framing.encodeVideoPayload(
            Framing.VideoFrameHeader(sequence, ptsUs, keyframe, 0, 1, frame.size), data,
        )
        synchronized(output) {
            Framing.writeRecord(output, Framing.RECORD_VIDEO, payload)
            output.flush() // 实时视频：逐帧出栈（ Nagle 已关，缓冲滞留会直接放大端到端延迟）
        }
        stats.onFrameSent(frame.size) // 统计口径恒为明文字节数
    }

    fun writeControl(payload: ByteArray) {
        val c = crypto
        val sealed = if (c != null) c.encryptControl(payload) else payload
        synchronized(output) {
            Framing.writeRecord(output, Framing.RECORD_CONTROL, sealed)
            output.flush() // 控制消息必须即时出栈（BufferedOutputStream 不 flush 会滞留 64KB 缓冲）
        }
    }

    override fun close() {
        reading = false
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        /** TCP 视频记录 → 单帧（TCP 复用模式不分片，fragCnt 恒 1）；加密态下先解密分片载荷 */
        fun decodeVideoRecord(payload: ByteArray, crypto: CryptoCodec? = null): Framing.VideoFrame? {
            val buf = java.nio.ByteBuffer.wrap(payload)
            val header = Framing.decodeVideoHeader(buf)
            if (header.fragmentCount != 1) return null
            var chunk = ByteArray(buf.remaining())
            buf.get(chunk)
            if (crypto != null && header.sequence != UdpVideoChannel.PUNCH_PROBE_SEQUENCE) {
                chunk = crypto.decryptVideo(header.sequence, header.keyframe, header.fragmentIndex, chunk)
                    ?: return null // 认证失败/重放：按流错误丢弃（读泵计 STREAM_ERROR）
            }
            return Framing.VideoFrame(header.sequence, header.ptsUs, header.keyframe, chunk)
        }
    }
}

/**
 * UDP 视频半桥：分片发送 + 重组接收 + 健康度跟踪（连续失败触发降级回调）。
 * 发送目标可延迟设定（源端从首包学习对端地址；打洞后端穿透成功后设定 SRFLX）。
 */
class UdpVideoChannel(
    val socket: DatagramSocket,
    private val fragmentBytes: Int,
    private val stats: TransportStats,
    private val callbacks: ChannelCallbacks,
    private val onFirstPeer: ((InetSocketAddress) -> Unit)? = null,
    private val onError: (IOException) -> Unit = {},
    /** 首包学习远端地址：仅 p2p 打洞场景开启；lan/frp 共享端口会被宿主侧无关流量污染 */
    private val learnFromFirstPacket: Boolean = true,
) : Closeable {

    private val fragmenter = Framing.VideoFragmenter(fragmentBytes)
    private val reassembler = Framing.FrameReassembler()
    @Volatile private var remote: InetSocketAddress? = null
    @Volatile private var running = false
    private val sendFailures = java.util.concurrent.atomic.AtomicInteger(0)
    private var recvThread: Thread? = null

    /** 会话加密（协商成功后由后端下发；null = 明文分片，与 v1 逐字节一致） */
    @Volatile var crypto: CryptoCodec? = null

    private val firstPacketFired = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 首包回调（无论 remote 是否已知；p2p 打洞以"对端任一数据报到达"判定直连建立） */
    @Volatile var onFirstPacket: ((InetSocketAddress) -> Unit)? = null

    val localPort: Int get() = socket.localPort

    fun setRemote(address: InetSocketAddress) {
        remote = address
        // 目标更新（初始 setRemote / 端点迁移 setRemote）重挂首包回调：
        // 迁移后下一数据报重新触发 onFirstPacket → P2pBackend 重建 established 标记
        firstPacketFired.set(false)
    }

    fun remoteAddress(): InetSocketAddress? = remote

    fun startReading(name: String) {
        check(!running)
        running = true
        recvThread = Thread({
            val buf = ByteArray(fragmentBytes + 64)
            try {
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val from = InetSocketAddress(packet.address, packet.port)
                    onFirstPacket?.let { cb ->
                        if (firstPacketFired.compareAndSet(false, true)) cb(from)
                    }
                    if (learnFromFirstPacket && remote == null) {
                        // 首包学习对端地址（仅 p2p 打洞场景；lan/frp 显式 setRemote，杜绝污染）
                        remote = from
                        onFirstPeer?.invoke(from)
                    }
                    handleDatagram(buf, packet.length)
                }
            } catch (e: IOException) {
                if (running) onError(e)
            } finally {
                running = false
            }
        }, "udp-video-$name").apply { isDaemon = true; start() }
    }

    private fun handleDatagram(buf: ByteArray, len: Int) {
        // UDP 数据报 = [u8 recordType=0x02][视频载荷]；截掉记录类型字节
        if (len < 1 + Framing.VIDEO_HEADER_BYTES) return // 过短（含打洞探针前的噪声）：静默
        val recordType = buf[0].toInt() and 0xFF
        if (recordType != Framing.RECORD_VIDEO) return // 控制类 UDP 数据 M0 不定义，丢弃
        val payload = java.nio.ByteBuffer.wrap(buf, 1, len - 1)
        val header = Framing.decodeVideoHeader(payload)
        if (header.sequence == PUNCH_PROBE_SEQUENCE) return // 打洞探针：不计统计、不下发解码
        var chunk = ByteArray(payload.remaining())
        payload.get(chunk)
        // 加密态：按帧头 (sequence, keyframe, fragIdx) 解密分片（AAD 绑定帧头）；失败即丢片
        val c = crypto
        if (c != null) {
            val plain = c.decryptVideo(header.sequence, header.keyframe, header.fragmentIndex, chunk)
            if (plain == null) {
                stats.onFragmentDropped()
                return
            }
            chunk = plain
        }
        val frame = reassembler.offer(header, chunk)
        if (frame != null) {
            stats.onFrameReceived(frame.data.size)
            stats.onVideoSequence(frame.sequence)
            callbacks.videoSink()?.onVideo(frame)
        }
    }

    /** UDP 发送结果：SENT=已走 UDP；NO_PEER=对端地址未知（本帧临时走 TCP，不降级）；FAILED=连续失败达阈值（永久降级 TCP） */
    enum class SendResult { SENT, NO_PEER, FAILED }

    /**
     * 发送整帧（自动分片）；对端未知返回 NO_PEER（不降级），
     * 连续失败达阈值返回 FAILED（触发降级判定）。
     */
    fun send(sequence: Long, ptsUs: Long, keyframe: Boolean, frame: ByteArray): SendResult {
        val target = remote ?: return SendResult.NO_PEER
        try {
            val fragments = fragmenter.fragment(sequence, ptsUs, keyframe, frame)
            val c = crypto
            val encrypt = c != null && sequence != PUNCH_PROBE_SEQUENCE // 探针帧恒明文
            val datagrams = ArrayList<DatagramPacket>(fragments.size)
            for (f in fragments) {
                var body = f
                if (encrypt) {
                    // 分片载荷 = [帧头 21B][分片字节]；按帧头字段逐片 AEAD（tag +16B/片）
                    val bb = java.nio.ByteBuffer.wrap(f)
                    val fh = Framing.decodeVideoHeader(bb)
                    val chunk = ByteArray(bb.remaining())
                    bb.get(chunk)
                    val sealed = c!!.encryptVideo(fh.sequence, fh.keyframe, fh.fragmentIndex, chunk)
                    val replaced = ByteArray(Framing.VIDEO_HEADER_BYTES + sealed.size)
                    System.arraycopy(f, 0, replaced, 0, Framing.VIDEO_HEADER_BYTES)
                    System.arraycopy(sealed, 0, replaced, Framing.VIDEO_HEADER_BYTES, sealed.size)
                    body = replaced
                }
                val wire = ByteArray(body.size + 1)
                wire[0] = Framing.RECORD_VIDEO.toByte()
                System.arraycopy(body, 0, wire, 1, body.size)
                datagrams.add(DatagramPacket(wire, wire.size, target))
            }
            for (p in datagrams) socket.send(p)
            sendFailures.set(0)
            if (sequence != PUNCH_PROBE_SEQUENCE) stats.onFrameSent(frame.size) // 探针不计统计
            return SendResult.SENT
        } catch (e: IOException) {
            val fails = sendFailures.incrementAndGet()
            if (fails >= SEND_FAILURE_DEGRADE_THRESHOLD) {
                stats.onBadEvent(TransportStats.BadEvent.UDP_TO_TCP_DEGRADE, e.message ?: "udp send fail")
                return SendResult.FAILED
            }
            return SendResult.SENT
        }
    }

    override fun close() {
        running = false
        socket.close()
    }

    companion object {
        /** 连续发送失败阈值：达到即判定 UDP 通道坏死 → 降级 TCP */
        const val SEND_FAILURE_DEGRADE_THRESHOLD = 8

        /** 打洞探针保留序号：sequence=0 的 UDP 视频载荷为探针，不计统计不下发解码 */
        const val PUNCH_PROBE_SEQUENCE: Long = 0L
    }
}

/**
 * 建立已连接 TCP 通道的统一入口：源端 listen+accept / 客户端 connect。
 * lan-direct 与 frp 共用；p2p 信令期亦复用。
 */
object TcpLink {

    /** 客户端：直连 [config.host]:[config.tcpPort] */
    fun connect(config: TransportConfig, connectTimeoutMs: Int = 5000): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(config.host, config.tcpPort), connectTimeoutMs)
        return socket
    }

    /** 源端：监听器。构造即绑定端口（start() 返回时端口已就绪，可被连接）。 */
    class Listener(port: Int, backlog: Int = 4) : Closeable {
        private val server = ServerSocket(port, backlog, InetAddress.getByName("0.0.0.0"))

        val boundPort: Int get() = server.localPort

        /** 阻塞 accept 一条控制连接（多客户端场景由调用方循环调用） */
        fun accept(): Socket {
            val socket = server.accept()
            socket.tcpNoDelay = true
            return socket
        }

        override fun close() {
            try {
                server.close()
            } catch (_: IOException) {
            }
        }
    }
}
