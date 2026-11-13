package cloudphone.client

import cloudphone.protocol.ClockSyncEstimator
import cloudphone.protocol.ControlMessage
import cloudphone.protocol.InputAckResult
import cloudphone.protocol.TransportKind
import cloudphone.protocol.opSegmentsMs
import java.util.concurrent.atomic.AtomicInteger

/**
 * op-latency 客户端侧聚合（op-latency-measurement 清单 ③④）：
 * opId 生成、发送时刻登记、PONG 钟偏移采样、INPUT_ACK 分段结算、g2g（触摸→渲染）配对、
 * 滚动 200 样本 P50/P95。Android 胶水层（无 JVM 测试基建），纯逻辑已下沉
 * protocol/OpLatency.kt（ClockSyncEstimator/opSegmentsMs）做 TDD。
 *
 * 线程模型：传输读线程（onInputAck/onPong/onStatsTransport）、触控线程（nextOpId/onOpSent）、
 * 渲染线程（onFrameRendered）、UI 线程（summary）混布 → 内部单锁（不持锁做 I/O）。
 */
object OpLatencyStats {

    private val lock = Any()
    private val seq = AtomicInteger(0)

    /** 下一个 opId（0 保留=不带尾块；DOWN/UP/BACK 离散事件携带，MOVE 恒 0 防风暴） */
    fun nextOpId(): Int {
        while (true) {
            val v = seq.incrementAndGet()
            if (v != 0) return v
        }
    }

    /** 发送登记 opId → tSendUs（客户端钟；ack 结算时移除，断链残留由容量兜底淘汰） */
    private val pendingSend = LinkedHashMap<Int, Long>()

    fun onOpSent(opId: Int, tSendUs: Long) {
        if (opId == 0) return
        synchronized(lock) {
            pendingSend[opId] = tSendUs
            while (pendingSend.size > 64) pendingSend.remove(pendingSend.keys.first())
        }
    }

    // ── 钟偏移（θ = serverClock − clientClock，min-RTT 样本胜出，不确定度 ≈ rtt/2）──

    private val clockSync = ClockSyncEstimator()

    /** PONG 采样（客户端 watchdog 每 2s 发一次 Ping，样本逐步收敛） */
    fun onPong(senderUs: Long, echoUs: Long, recvUs: Long) {
        clockSync.onPong(senderUs, echoUs, recvUs)
    }

    fun offsetUs(): Long? = clockSync.offsetUs()

    fun rttUs(): Long? = clockSync.rttUs()

    @Volatile
    var transportTag: String = "?"
        private set

    /** 源端 STATS 附带的传输类型（同链路双向同质）——op 分组标签 */
    fun onStatsTransport(t: TransportKind) {
        transportTag = when (t) {
            TransportKind.LAN_DIRECT -> "lan-direct"
            TransportKind.P2P -> "p2p"
            TransportKind.P2P_FALLBACK_FRP -> "p2p→frp"
            TransportKind.FRP -> "frp"
        }
    }

    // ── 样本（cap 200 滚动）──

    class OpSample(
        val opId: Int,
        val transport: String,
        val result: InputAckResult,
        val e2eMs: Long,
        val uplinkMs: Long,
        val serverMs: Long,
        val downMs: Long,
        val injectMs: Long,
        val frameWaitMs: Long,
        val encQueueMs: Long,
        /** 触摸→上屏全程（渲染兑现后回填；-1=未配对到渲染帧） */
        @Volatile var g2gMs: Long,
    )

    private val samples = ArrayDeque<OpSample>()

    /** frameSeq → (样本, tSendUs)：等待渲染兑现 g2g */
    private val pendingG2g = HashMap<Long, Pair<OpSample, Long>>()

    /**
     * INPUT_ACK 结算（传输读线程）：E2E 用客户端单钟真值；服务端分段经 θ 桥接后
     * 残差全部归入 downMs。无发送登记（断链残留被淘汰/进程重启撞号）时用服务端
     * 总耗时反推 tSend 作退化估计（uplink/down 失真，e2e/srv 段仍可信）。
     */
    fun onInputAck(ack: ControlMessage.InputAck, tAckUs: Long) {
        val registered: Long?
        synchronized(lock) { registered = pendingSend.remove(ack.opId) }
        val tSendUs = registered ?: (tAckUs - (ack.srvNowUs - ack.arrUs))
        val offset = clockSync.offsetUs() ?: 0L
        val seg = opSegmentsMs(
            tSendUs = tSendUs, offsetUs = offset,
            arrUs = ack.arrUs, injDoneUs = ack.injDoneUs,
            encPtsUs = ack.encPtsUs, srvNowUs = ack.srvNowUs, tAckUs = tAckUs,
        )
        val sample = OpSample(
            opId = ack.opId, transport = transportTag, result = ack.result,
            e2eMs = seg.e2eAckMs, uplinkMs = seg.uplinkMs, serverMs = seg.serverMs,
            downMs = seg.downMs, injectMs = seg.injectMs,
            frameWaitMs = seg.frameWaitMs, encQueueMs = seg.encQueueMs, g2gMs = -1,
        )
        synchronized(lock) {
            samples.addLast(sample)
            while (samples.size > 200) samples.removeFirst()
            if (ack.result == InputAckResult.OK && ack.frameSeq != 0L) {
                pendingG2g[ack.frameSeq] = sample to tSendUs
                if (pendingG2g.size > 16) pendingG2g.clear() // 渲染长期未兑现（积压）→ 放弃配对
            }
        }
        android.util.Log.i(
            "cloudphone-latency",
            "op opId=${ack.opId} transport=$transportTag result=${ack.result.name} " +
                "e2eMs=${seg.e2eAckMs} uplinkMs=${seg.uplinkMs} srvMs=${seg.serverMs} downMs=${seg.downMs} " +
                "injectMs=${seg.injectMs} frameWaitMs=${seg.frameWaitMs} encQueueMs=${seg.encQueueMs} " +
                "g2gMs=${sample.g2gMs}",
        )
    }

    /** 渲染兑现（渲染线程）：frameSeq 命中 → 回填 g2g（触摸→上屏全程） */
    fun onFrameRendered(frameSeq: Long, nowUs: Long) {
        val hit = synchronized(lock) {
            val p = pendingG2g.remove(frameSeq) ?: return
            p.first.g2gMs = (nowUs - p.second) / 1000
            p.first
        }
        android.util.Log.i("cloudphone-latency", "g2g opId=${hit.opId} g2gMs=${hit.g2gMs}")
    }

    /** op E2E 滚动 P50/P95（ms；无样本 null）——StatsView HUD 喂数 */
    fun summary(): Pair<Long, Long>? {
        synchronized(lock) {
            if (samples.isEmpty()) return null
            val sorted = samples.map { it.e2eMs }.sorted()
            return pctl(sorted, 0.5) to pctl(sorted, 0.95)
        }
    }

    /** g2g 滚动 P50/P95（ms；无已兑现样本 null）——measure 脚本对照用 */
    fun g2gSummary(): Pair<Long, Long>? {
        synchronized(lock) {
            val done = samples.map { it.g2gMs }.filter { it >= 0 }.sorted()
            if (done.isEmpty()) return null
            return pctl(done, 0.5) to pctl(done, 0.95)
        }
    }

    private fun pctl(sorted: List<Long>, frac: Double): Long =
        sorted[((sorted.size - 1) * frac).toInt().coerceIn(0, sorted.size - 1)]
}
