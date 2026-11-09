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
 * 流程（p2p-crossnet-traversal 2.1/2.2/2.3 增强）：
 * 1. 控制通道经 frp-TCP（[TransportConfig.tcpPort]）建立——信令（offer/answer）即
 *    HELLO.p2pOffer / HELLO_ACK.p2pAnswer（上层会话握手时携带；格式为多候选文本，见
 *    [StunClient.formatCandidates]，旧单候选 "ip:port" 为其 n=1 特例）。
 * 2. [start] 时经 STUN 多节点汇总发现本端 SRFLX（[localSrflx]），并据多节点映射差异
 *    判定本端是否 symmetric（[localNatSymmetric]），连同本地多网卡候选（含 IPv6）一并
 *    汇入候选文本（上层放入 offer）。
 * 3. 对端 answer 到达后上层调 [onPeerCandidates]（或旧入口 [onPeerSrflx]）：
 *    - 候选按 IPv6 优先排序，多候选并行 out-probe（每 [PUNCH_PROBE_INTERVAL_MS] 轮询全部目标）；
 *    - 对端 symmetric 时启用端口预测窗口（[PortPredictor]，≤64 端口批量探测，2.2），
 *      预测命中记入 [punchTrace]；未命中不阻塞，超时照常回落 frp（degraded 兜底）；
 *    - 任一探针从对端到达即直连建立，视频通道切 UDP 直连（STATS.transport=p2p）。
 * 4. [punchTimeoutMs]（M0 = 5s）未成：上报失败并自动回落 frp（视频走控制 TCP 复用），
 *    记录 PUNCH_TIMEOUT + PUNCH_FALLBACK_FRP 事件，会话不中断；后台继续打洞，
 *    后续打通仍可切直连（PUNCH_ESTABLISHED 再通知）。
 * 5. 端点迁移（2.3）：本端网络切换后上层经 [refreshLocalCandidates] 检出出口地址变化，
 *    以 P2P_MIGRATE（MsgType 0x0C）推送新候选；对端 [onPeerCandidates] 二次进入即迁移
 *    路径——setRemote 更新目标、探针循环恢复、sessionId 与统计均不重置，
 *    直连重建后再报 PUNCH_ESTABLISHED。
 *
 * 每级耗时（SRFLX 发现/候选枚举/对端分析/v6 尝试/打洞/预测/迁移/回落）记录进
 * [punchTrace]（[Diagnostics.PunchTrace]，可 JSON 输出）。
 */
class P2pBackend(
    config: TransportConfig,
    private val stunServers: List<StunClient.StunServer> = StunClient.DEFAULT_SERVERS,
    private val punchTimeoutMs: Long = PUNCH_TIMEOUT_DEFAULT_MS,
    /** 端口预测窗口开关（仅 symmetric 对端生效；natlab 矩阵对照实验可关闭） */
    private val portPredictionEnabled: Boolean = true,
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

    /** 打洞分级耗时（诊断/验证床数据出口） */
    val punchTrace = Diagnostics.PunchTrace()

    /** 本端 SRFLX 主地址（上层放入 HELLO.p2pOffer 的首选候选） */
    @Volatile var localSrflx: InetSocketAddress? = null
        private set

    /** 本端候选全集（SRFLX + 本地多网卡，IPv6 优先；上层放入 offer 文本） */
    @Volatile var localCandidates: List<InetSocketAddress> = emptyList()
        private set

    /** 本端 symmetric NAT 判定（多 STUN 节点同 socket 映射端口不一致） */
    @Volatile var localNatSymmetric = false
        private set

    private val directEstablished = AtomicBoolean(false)
    private val fellBack = AtomicBoolean(false)
    private val punchStarted = AtomicBoolean(false)

    /** 探针目标集（迁移时原子替换；探针线程每轮读最新值） */
    private val punchTargets = java.util.concurrent.atomic.AtomicReference<List<InetSocketAddress>>(emptyList())

    @Volatile private var peerNatSymmetric = false
    @Volatile private var predictedPorts: Set<Int> = emptySet()
    @Volatile private var predictionHit = false
    @Volatile private var probeLoopStarted = false

    private var sourceSawClient = false

    /** 加密下发（协商 AES128GCM 后）：信令 TCP 通道与已建立的打洞视频通道同步挂 codec */
    override fun onCryptoAttached(codec: CryptoCodec?) {
        channel.get()?.crypto = codec
        videoChannel?.crypto = codec
    }

    override fun start() {
        checkOpen()
        when (config.role) {
            Role.CLIENT -> openControlStream(restored = false)
            Role.SOURCE -> bindListener()
        }
        openPunchSocket()
        // SRFLX 多节点汇总（同一打洞 socket 串行短超时：并发查询会互吃对方应答）
        val socket = punchSocket ?: return
        val t0 = System.nanoTime()
        val results = StunClient.discoverSrflxAll(socket, stunServers, STUN_AGGREGATE_TIMEOUT_MS)
        punchTrace.record(
            Diagnostics.PunchStages.SRFLX_DISCOVERY,
            "${results.size}/${stunServers.size} nodes in ${(System.nanoTime() - t0) / 1_000_000}ms",
        )
        if (results.isEmpty()) {
            stats.onBadEvent(TransportStats.BadEvent.PUNCH_TIMEOUT, "STUN unreachable, p2p unavailable")
            stats.onBadEvent(TransportStats.BadEvent.PUNCH_FALLBACK_FRP, "stun discovery failed, video over frp tcp")
            fellBack.set(true) // 直接具备回落形态：视频走 TCP 复用
            punchTrace.record(Diagnostics.PunchStages.FALLBACK, "stun discovery failed")
            onStreamEvent?.onEvent(StreamEvent.PUNCH_FALLBACK_FRP, "stun discovery failed")
            return
        }
        localSrflx = InetSocketAddress(results[0].mappedIp, results[0].mappedPort)
        localNatSymmetric = StunClient.isSymmetricMapping(results)
        localCandidates = buildLocalCandidates(socket, results)
        punchTrace.record(
            Diagnostics.PunchStages.LOCAL_CANDIDATES,
            "${localCandidates.size} candidates sym=$localNatSymmetric",
        )
    }

    /** SRFLX 结果 + 本地多网卡候选（含 IPv6，v6 优先排序；host 端口 = 打洞 socket 端口） */
    private fun buildLocalCandidates(socket: DatagramSocket, results: List<StunClient.SrflxResult>): List<InetSocketAddress> {
        val cands = ArrayList<InetSocketAddress>()
        for (r in results) cands.add(InetSocketAddress(r.mappedIp, r.mappedPort))
        for (addr in StunClient.localInterfaces()) cands.add(InetSocketAddress(addr, socket.localPort))
        return orderedCandidates(cands.distinctBy { "${it.address}/${it.port}" })
    }

    /** 候选排序：IPv6 直连优先（同 v6 网可达即免打洞），稳定排序保持 SRFLX 先于 host */
    private fun orderedCandidates(cands: List<InetSocketAddress>): List<InetSocketAddress> =
        cands.sortedByDescending { it.address is java.net.Inet6Address }

    /** 本端候选文本（offer/answer/migrate 载荷共用格式） */
    fun localCandidatesText(): String = StunClient.formatCandidates(localCandidates, localNatSymmetric)

    /**
     * 网络切换检测（迁移发起侧，2.3）：重新 SRFLX 发现，出口映射变化则更新本端候选并返回
     * 新候选文本（调用方以 P2P_MIGRATE 发给对端）；未变化返回 null（不产生消息）。
     */
    fun refreshLocalCandidates(): String? {
        val socket = punchSocket ?: return null
        if (closed.get()) return null
        val results = StunClient.discoverSrflxAll(socket, stunServers, STUN_AGGREGATE_TIMEOUT_MS)
        if (results.isEmpty()) {
            punchTrace.record(Diagnostics.PunchStages.MIGRATION, "refresh discovery 0/${stunServers.size}")
            return null
        }
        val primary = results.firstOrNull() ?: return null
        val newSrflx = InetSocketAddress(primary.mappedIp, primary.mappedPort)
        val newSym = StunClient.isSymmetricMapping(results)
        if (newSrflx == localSrflx && newSym == localNatSymmetric) {
            punchTrace.record(Diagnostics.PunchStages.MIGRATION, "refresh unchanged $newSrflx")
            return null
        }
        localSrflx = newSrflx
        localNatSymmetric = newSym
        localCandidates = buildLocalCandidates(socket, results)
        punchTrace.record(Diagnostics.PunchStages.MIGRATION, "local endpoint changed → $newSrflx")
        return localCandidatesText()
    }

    /** 上层收到对端 answer（HELLO_ACK.p2pAnswer 或源端收到 HELLO.p2pOffer）后调用（单候选兼容入口） */
    fun onPeerSrflx(peer: InetSocketAddress) {
        onPeerCandidates(StunClient.formatSrflx(peer))
    }

    /**
     * 对端候选文本入口：首次进入 = 初始打洞；打洞已启动后再次进入 = 端点迁移更新（2.3）。
     * 迁移路径只换目标地址（setRemote + 探针循环恢复），会话与统计不重置。
     */
    fun onPeerCandidates(text: String) {
        checkOpen()
        val parsed = StunClient.parseCandidates(text) ?: return
        peerNatSymmetric = parsed.symmetric
        punchTrace.record(
            Diagnostics.PunchStages.PEER_ANALYSIS,
            "cands=${parsed.addresses.size} sym=$peerNatSymmetric",
        )
        if (!punchStarted.compareAndSet(false, true)) {
            migrateTo(parsed.addresses)
            return
        }
        beginPunch(parsed)
    }

    private fun beginPunch(parsed: StunClient.CandidateSet) {
        val socket = punchSocket ?: return
        val ordered = orderedCandidates(parsed.addresses)
        // IPv6 直连优先：v6 候选排前，探针循环先行尝试（v4-only socket 发 v6 失败静默跳过并记录）
        val v6 = ordered.count { it.address is java.net.Inet6Address }
        punchTrace.record(
            Diagnostics.PunchStages.V6_DIRECT,
            if (v6 > 0) "v6-first, $v6 v6 candidate(s)" else "no v6 candidate, skipped",
        )
        // 端口预测（2.2）：仅 symmetric 对端启用；差分外推 ≤64 端口窗口并入探测集
        val targets = ArrayList(ordered)
        if (portPredictionEnabled && peerNatSymmetric) {
            val predicted = ArrayList<InetSocketAddress>()
            for ((ip, addrs) in parsed.addresses.groupBy { it.address }) {
                if (addrs.size < 2) continue
                for (port in PortPredictor.predict(addrs.map { it.port })) {
                    predicted.add(InetSocketAddress(ip, port))
                }
            }
            if (predicted.isNotEmpty()) {
                predictedPorts = predicted.map { it.port }.toSet()
                targets.addAll(predicted)
                punchTrace.record(
                    Diagnostics.PunchStages.PORT_PREDICTION,
                    "armed window=${predicted.size} ports (peer sym)",
                )
            }
        }
        val distinct = targets.distinctBy { "${it.address}/${it.port}" }
        val vc = UdpVideoChannel(
            socket = socket,
            fragmentBytes = config.fragmentBytes,
            stats = stats,
            callbacks = this,
        )
        vc.onFirstPacket = { from -> onPeerReachable(from) }
        // 打洞通道可能晚于握手协商建立（客户端收到 ack 后才 onPeerSrflx）：创建即继承会话加密
        vc.crypto = sessionCrypto
        vc.setRemote(distinct.first())
        videoChannel = vc
        punchTargets.set(distinct)
        vc.startReading("p2p")
        punchTrace.record(Diagnostics.PunchStages.SRFLX_PUNCH, "probing ${distinct.size} target(s)")
        startProbeLoop()
        // 5s 打洞定时器（失败 → frp 兜底，degraded 标记；后台继续打洞）
        Thread({
            try {
                Thread.sleep(punchTimeoutMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!closed.get() && !directEstablished.get() && fellBack.compareAndSet(false, true)) {
                if (predictedPorts.isNotEmpty() && !predictionHit) {
                    punchTrace.record(Diagnostics.PunchStages.PORT_PREDICTION, "miss after ${punchTimeoutMs}ms")
                }
                stats.onBadEvent(TransportStats.BadEvent.PUNCH_TIMEOUT, "no direct udp in ${punchTimeoutMs}ms")
                stats.onBadEvent(TransportStats.BadEvent.PUNCH_FALLBACK_FRP, "video over frp tcp")
                punchTrace.record(Diagnostics.PunchStages.FALLBACK, "video over frp tcp")
                onStreamEvent?.onEvent(StreamEvent.PUNCH_FALLBACK_FRP, "video over frp tcp, punch continues")
            }
        }, "p2p-punch-timer").apply { isDaemon = true; start() }
    }

    /** 对端任一数据报到达（探针或视频分片）：直连建立 + peer-reflexive 地址校正 + 分级记录 */
    private fun onPeerReachable(from: InetSocketAddress) {
        if (predictedPorts.isNotEmpty() && from.port in predictedPorts && !predictionHit) {
            predictionHit = true
            punchTrace.record(Diagnostics.PunchStages.PORT_PREDICTION, "hit predicted port ${from.port}")
        }
        if (from.address is java.net.Inet6Address) {
            punchTrace.record(Diagnostics.PunchStages.V6_DIRECT, "v6 direct established via $from")
        }
        if (directEstablished.compareAndSet(false, true)) {
            // peer-reflexive 地址校正：实际收到数据报的对端地址接管视频发送目标——
            // symmetric NAT 下 SRFLX 可能已过期，"收到即可达"的地址才是可靠直连地址
            videoChannel?.setRemote(from)
            punchTrace.record(Diagnostics.PunchStages.SRFLX_PUNCH, "established via $from")
            onStreamEvent?.onEvent(StreamEvent.PUNCH_ESTABLISHED, "p2p udp direct from $from")
        }
    }

    /** 端点迁移：换目标、断 established 标记（探针循环自动恢复发包重建直连），会话不动 */
    private fun migrateTo(peerCandidates: List<InetSocketAddress>) {
        val vc = videoChannel ?: return
        val ordered = orderedCandidates(peerCandidates)
        if (ordered.isEmpty()) return
        punchTargets.set(ordered)
        vc.setRemote(ordered.first())
        directEstablished.set(false)
        punchTrace.record(Diagnostics.PunchStages.MIGRATION, "peer endpoint → ${ordered.first()}")
        onStreamEvent?.onEvent(
            StreamEvent.PUNCH_MIGRATED,
            "peer endpoint updated to ${ordered.first()}, session kept",
        )
    }

    /**
     * 常驻探针循环（迁移复用同一循环）：未 established（或迁移重建中）每轮向全部目标发探针；
     * established 后保底再发 MIN_PROBE_BURST 轮（先收后发竞态防御：不发包即不开本端出向
     * 映射，对端入探针恒被过滤）。迁移把 established 置 false，循环自动恢复。
     */
    private fun startProbeLoop() {
        if (!probeLoopStarted) {
            probeLoopStarted = true
            Thread({
                // 探针 wire：[0x02][sequence=0 帧头][1B 载荷]，与 vc.send(sequence=0) 字节一致（恒明文）
                val probeWire = run {
                    val payload = Framing.encodeVideoPayload(
                        Framing.VideoFrameHeader(0, 0, false, 0, 1, 1), byteArrayOf(0x50),
                    )
                    ByteArray(payload.size + 1).also {
                        it[0] = Framing.RECORD_VIDEO.toByte()
                        System.arraycopy(payload, 0, it, 1, payload.size)
                    }
                }
                var burst = 0
                while (!closed.get()) {
                    val established = directEstablished.get()
                    if (!established) {
                        burst = 0
                        for (target in punchTargets.get()) {
                            try {
                                punchSocket?.send(java.net.DatagramPacket(probeWire, probeWire.size, target))
                            } catch (_: Exception) {
                                // v4 socket 发 v6 目标等：静默跳过（候选容错，不中断其余目标）
                            }
                        }
                    } else if (burst < MIN_PROBE_BURST) {
                        burst++
                        for (target in punchTargets.get()) {
                            try {
                                punchSocket?.send(java.net.DatagramPacket(probeWire, probeWire.size, target))
                            } catch (_: Exception) {
                            }
                        }
                    }
                    try {
                        Thread.sleep(PUNCH_PROBE_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }, "p2p-punch").apply { isDaemon = true; start() }
        }
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
        channel.getAndSet(null)?.let { it.retire(); it.close() }
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
        channel.getAndSet(null)?.let { it.retire(); it.close() }
        videoChannel?.close()
        punchSocket?.close()
        listener?.close()
    }

    companion object {
        /** 打洞探针间隔 */
        const val PUNCH_PROBE_INTERVAL_MS = 200L

        /** 打洞超时（spec：5s 未成回落 frp） */
        const val PUNCH_TIMEOUT_DEFAULT_MS = 5000L

        /** established 后探针保底突发发数：保证本端出向映射已开（先收后发竞态防御） */
        const val MIN_PROBE_BURST = 5

        /** SRFLX 汇总单节点超时（多节点串行，总耗时可控在打洞预算内） */
        const val STUN_AGGREGATE_TIMEOUT_MS = 1200

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

/**
 * symmetric NAT 端口预测（p2p-crossnet-traversal 2.2）：
 * 由对端多 STUN 节点的 SRFLX 端口采样差分外推其对"本端地址"的下一个映射端口窗口
 * （≤64 个），并入探针目标集批量探测。仅当对端判定 symmetric（同 ip 多采样端口互异）
 * 且上层开启 [P2pBackend] portPredictionEnabled 时启用；预测未命中不阻塞——5s 超时
 * 照常回落 frp（视频切中转，degraded 标记）。
 */
object PortPredictor {
    /** 预测窗口上限（spec 2.2：≤64 端口/批） */
    const val MAX_WINDOW = 64

    /** 端口上限（预测窗口越界截断，防止 InetSocketAddress port out of range） */
    const val MAX_PORT = 65535

    /**
     * 差分外推（双向）：采样端口排序去重 → 相邻差均值（步长，向下取整且 ≥1）→
     * 前向外推（last+step·k，顺序分配型对端）+ 后向外推（last-step·k，池化型对端——
     * 映射落在样本附近池内，仅前向会立即越池），两向合计 ≤[window] 个端口（去重截断）。
     * 采样不足 2 个（无法差分）返回空表（不启用预测）。
     */
    fun predict(ports: List<Int>, window: Int = MAX_WINDOW): List<Int> {
        if (ports.size < 2 || window <= 0) return emptyList()
        val sorted = ports.sorted() // 保留重复采样（全同端口 → 差分 0 → 步长钳制 1）
        if (sorted.size < 2) return emptyList()
        val deltas = sorted.zipWithNext { a, b -> b - a }
        val avgStep = deltas.sum().toDouble() / deltas.size
        val step = if (avgStep >= 1.0) Math.round(avgStep).toInt().coerceAtLeast(1) else 1
        val last = sorted.last()
        val nForward = (window + 1) / 2
        val nBackward = window - nForward
        val out = LinkedHashSet<Int>()
        var p = last + step
        var n = 0
        while (n < nForward && p <= MAX_PORT) {
            out.add(p); p += step; n++
        }
        p = last - step
        n = 0
        while (n < nBackward && p >= 1) {
            out.add(p); p -= step; n++
        }
        return out.toList()
    }
}
