package cloudphone.protocol

/**
 * op-latency 纯逻辑（op-latency-measurement 清单 ④ 核心，JVM 可测，无 Android 依赖）：
 * - [ClockSyncEstimator]：NTP 风格 PONG 采样估计客户端↔服务端单调钟偏移（min-RTT 样本胜出，不确定度 ≈ rtt/2）；
 * - [opSegmentsMs]：一次 op 的分段耗时。客户端单钟测 E2E（真值），服务端单钟测分段，
 *   两钟经 θ 桥接，桥接残差（含 θ 不确定度）全部归入下行段。
 */
class ClockSyncEstimator {

    /** θ = serverClock − clientClock（正 = 服务端钟快） */
    data class Sample(val offsetUs: Long, val rttUs: Long)

    @Volatile
    private var best: Sample? = null

    /**
     * PONG 采样：客户端在 t=senderUs（客户端钟）发 Ping；服务端 t=echoUs（服务端钟）回 Pong；
     * 客户端 t=recvUs（客户端钟）收到。
     * rtt = recvUs − senderUs（客户端钟内自洽）；θ = echoUs − (senderUs + recvUs) / 2。
     *
     * @return 采纳后的当前最优样本；畸形样本（rtt<0）忽略并返回 null
     */
    fun onPong(senderUs: Long, echoUs: Long, recvUs: Long): Sample? {
        if (recvUs < senderUs) return null
        val rtt = recvUs - senderUs
        val offset = echoUs - (senderUs + recvUs) / 2
        val cur = best
        if (cur == null || rtt < cur.rttUs) {
            val s = Sample(offset, rtt)
            best = s
            return s
        }
        return cur
    }

    /** 当前钟偏移估计（us）；尚无样本为 null */
    fun offsetUs(): Long? = best?.offsetUs

    /** 当前最优 RTT（us）；尚无样本为 null */
    fun rttUs(): Long? = best?.rttUs
}

/** 一次 op 的分段耗时（毫秒，四舍五入；负值=钟不确定度噪声，钳 0 不入统计；-1=该段不存在） */
data class OpSegmentsMs(
    /** 客户端钟真值：ack 到达 − op 发出 */
    val e2eAckMs: Long,
    /** arrUs − (tSend+θ)：上行传输 + 客户端发送栈（±rtt/2 不确定度） */
    val uplinkMs: Long,
    /** srvNowUs − arrUs：服务端总耗时 */
    val serverMs: Long,
    /** 残差 = e2eAck − uplink − server：下行传输 + 客户端接收/分发栈（吸收钟桥不确定度） */
    val downMs: Long,
    /** injDoneUs − arrUs：注入系统调用耗时 */
    val injectMs: Long,
    /** encPtsUs − injDoneUs：注入 → 注入后首个合成帧编码出队（-1=超时回执无帧） */
    val frameWaitMs: Long,
    /** srvNowUs − encPtsUs：编码出队 → ack 构造（含发送排队前时光）（-1=同上） */
    val encQueueMs: Long,
)

/**
 * 分段换算（全部微秒入参，客户端 tSendUs/tAckUs 为客户端单调钟，其余为服务端单调钟）。
 * 恒等式：e2eAckMs = uplinkMs + serverMs + downMs（钳零前残差可为负，钳零后仅 ≥0 方向成立）。
 */
fun opSegmentsMs(
    tSendUs: Long,
    offsetUs: Long,
    arrUs: Long,
    injDoneUs: Long,
    encPtsUs: Long,
    srvNowUs: Long,
    tAckUs: Long,
): OpSegmentsMs {
    fun ms(us: Long): Long = (us + 500) / 1000
    fun clampMs(us: Long): Long = if (us < 0) 0L else ms(us)
    val e2eAckMs = ms(tAckUs - tSendUs)
    val uplinkRaw = arrUs - (tSendUs + offsetUs)
    val serverRaw = srvNowUs - arrUs
    val injectRaw = injDoneUs - arrUs
    val hasFrame = encPtsUs > 0
    val frameWaitMs = if (hasFrame) clampMs(encPtsUs - injDoneUs) else -1L
    val encQueueMs = if (hasFrame) clampMs(srvNowUs - encPtsUs) else -1L
    val downRaw = (tAckUs - tSendUs) - uplinkRaw - serverRaw
    return OpSegmentsMs(
        e2eAckMs = e2eAckMs,
        uplinkMs = clampMs(uplinkRaw),
        serverMs = clampMs(serverRaw),
        downMs = clampMs(downRaw),
        injectMs = clampMs(injectRaw),
        frameWaitMs = frameWaitMs,
        encQueueMs = encQueueMs,
    )
}
