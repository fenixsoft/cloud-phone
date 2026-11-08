package cloudphone.transport

import java.util.EnumMap

/**
 * 传输统计聚合 —— 逐级计数器（发送/接收/分片丢弃）、坏事件分类统计与 ≥1Hz 快照。
 *
 * 三后端共用同一实现（两端引用 protocol 依赖中的同一份）；上层管线与 UI 以
 * [snapshot] 按秒取数：fps/码率取窗口差值，RTT 取 EWMA，丢包率取序号缺口估计。
 * 线程安全：单锁保护（调用频率 ≤ 每帧一次，无竞争热点）。
 */
class TransportStats(private val now: () -> Long = { System.currentTimeMillis() }) {

    /** 传输层坏事件分类（断流/重连/打洞超时回落/UDP→TCP 降级/流错误） */
    enum class BadEvent {
        DISCONNECT,            // 已建立链路意外断开
        RECONNECT,             // 自动重连成功
        PUNCH_TIMEOUT,         // UDP 打洞 5s 未成
        PUNCH_FALLBACK_FRP,    // 打洞失败回落 frp（会话不中断）
        UDP_TO_TCP_DEGRADE,    // UDP 视频通道连续失败降级 TCP 复用
        STREAM_ERROR,          // 协议流损坏（长度前缀非法等）
    }

    data class BadEventRecord(val event: BadEvent, val atMs: Long, val detail: String)

    /** 一次 ≥1Hz 统计快照（STATS 消息与验证矩阵的取数口径） */
    data class Snapshot(
        val epochMs: Long,
        val intervalMs: Long,
        val fpsSent: Double,
        val fpsReceived: Double,
        val bitrateKbpsSent: Double,
        val bitrateKbpsReceived: Double,
        val rttMs: Double,
        val lossPct: Double,
        val framesSent: Long,
        val framesReceived: Long,
        val bytesSent: Long,
        val bytesReceived: Long,
        val fragmentsDropped: Long,
        val badEvents: Map<BadEvent, Long>,
        val lastBadEvent: BadEventRecord?,
    )

    // ── 累计计数 ──
    private var framesSent = 0L
    private var framesReceived = 0L
    private var bytesSent = 0L
    private var bytesReceived = 0L
    private var fragmentsDropped = 0L
    private val badEventCounts = EnumMap<BadEvent, Long>(BadEvent::class.java)
    private var lastBadEvent: BadEventRecord? = null

    // ── RTT（EWMA α=0.25）──
    private var rttMs = Double.NaN

    // ── 丢包（发送侧序号缺口估计）──
    private var seqMax = -1L
    private var seqReceived = 0L
    private var seqGap = 0L

    // ── 快照窗口 ──
    private var lastSnapshotAt = -1L
    private var lastFramesSent = 0L
    private var lastFramesReceived = 0L
    private var lastBytesSent = 0L
    private var lastBytesReceived = 0L

    fun onFrameSent(bytes: Int) {
        synchronized(this) {
            framesSent++
            bytesSent += bytes
        }
    }

    fun onFrameReceived(bytes: Int) {
        synchronized(this) {
            framesReceived++
            bytesReceived += bytes
        }
    }

    fun onFragmentDropped(count: Int = 1) {
        synchronized(this) { fragmentsDropped += count }
    }

    /** 发送侧视频帧序号到达（仅按序推进计数唯一帧；乱序重复不计，缺口累计为丢包） */
    fun onVideoSequence(sequence: Long) {
        synchronized(this) {
            if (seqMax < 0) {
                seqMax = sequence
                seqReceived = 1
                return
            }
            if (sequence > seqMax) {
                seqGap += sequence - seqMax - 1
                seqMax = sequence
                seqReceived++
            }
        }
    }

    fun onRttSample(ms: Double) {
        synchronized(this) {
            rttMs = if (rttMs.isNaN()) ms else rttMs + RTT_SMOOTHING * (ms - rttMs)
        }
    }

    fun onBadEvent(event: BadEvent, detail: String = "") {
        synchronized(this) {
            badEventCounts[event] = (badEventCounts[event] ?: 0L) + 1
            lastBadEvent = BadEventRecord(event, now(), detail)
        }
    }

    /** 取快照并推进速率窗口；调用方 ≥1Hz 调用即满足统计频率要求 */
    fun snapshot(): Snapshot {
        val t = now()
        var fpsSent = 0.0
        var fpsRecv = 0.0
        var kbpsSent = 0.0
        var kbpsRecv = 0.0
        var interval = 0L
        synchronized(this) {
            if (lastSnapshotAt >= 0) {
                interval = (t - lastSnapshotAt).coerceAtLeast(1)
                val sec = interval / 1000.0
                fpsSent = (framesSent - lastFramesSent) / sec
                fpsRecv = (framesReceived - lastFramesReceived) / sec
                kbpsSent = (bytesSent - lastBytesSent) * 8.0 / sec / 1000.0
                kbpsRecv = (bytesReceived - lastBytesReceived) * 8.0 / sec / 1000.0
            }
            lastSnapshotAt = t
            lastFramesSent = framesSent
            lastFramesReceived = framesReceived
            lastBytesSent = bytesSent
            lastBytesReceived = bytesReceived
            val denom = seqGap + seqReceived
            val loss = if (denom <= 0) 0.0 else 100.0 * seqGap / denom
            return Snapshot(
                epochMs = t,
                intervalMs = interval,
                fpsSent = fpsSent,
                fpsReceived = fpsRecv,
                bitrateKbpsSent = kbpsSent,
                bitrateKbpsReceived = kbpsRecv,
                rttMs = if (rttMs.isNaN()) 0.0 else rttMs,
                lossPct = loss,
                framesSent = framesSent,
                framesReceived = framesReceived,
                bytesSent = bytesSent,
                bytesReceived = bytesReceived,
                fragmentsDropped = fragmentsDropped,
                badEvents = badEventCounts.toMap(),
                lastBadEvent = lastBadEvent,
            )
        }
    }

    companion object {
        const val RTT_SMOOTHING = 0.25
    }
}
