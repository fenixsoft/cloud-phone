package cloudphone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * op-latency 纯逻辑单测（op-latency-measurement 清单 ④ 的可测核心）：
 * - ClockSyncEstimator：NTP 风格 PONG 采样 → 客户端↔服务端单调钟偏移 θ（min-RTT 胜出）；
 * - opSegmentsMs：一次 op 的分段耗时换算（客户端单钟 E2E + 服务端单钟分段，钟桥残差归下行段）。
 */
class OpLatencyTest {

    // ── ClockSyncEstimator ──

    @Test
    fun `symmetric pong yields zero offset`() {
        // 客户端 t=1_000_000 发 Ping(senderUs)，服务端 t=1_000_050 回 Pong(echoUs)，
        // 客户端 t=1_000_100 收到：rtt=100us，钟无偏 → θ=0
        val est = ClockSyncEstimator()
        est.onPong(senderUs = 1_000_000L, echoUs = 1_000_050L, recvUs = 1_000_100L)
        assertEquals(0L, est.offsetUs())
        assertEquals(100L, est.rttUs())
    }

    @Test
    fun `server ahead offset is positive`() {
        // 钟桥定义 θ = serverClock − clientClock：echo 落点超前中点 250us → 服快 250us
        val est = ClockSyncEstimator()
        est.onPong(senderUs = 1_000_000L, echoUs = 1_000_300L, recvUs = 1_000_100L)
        assertEquals(250L, est.offsetUs())
    }

    @Test
    fun `min rtt sample wins`() {
        // 第一份 RTT 5000us、第二份 800us：取小 RTT（更可信）样本的偏移
        val est = ClockSyncEstimator()
        est.onPong(senderUs = 1_000_000L, echoUs = 1_002_500L, recvUs = 1_005_000L)
        assertEquals(5000L, est.rttUs())
        est.onPong(senderUs = 2_000_000L, echoUs = 2_000_400L, recvUs = 2_000_800L)
        assertEquals(800L, est.rttUs())
        assertEquals(0L, est.offsetUs()) // 2000400 − (2000000+2000800)/2 = 0
    }

    @Test
    fun `larger rtt sample does not replace best`() {
        val est = ClockSyncEstimator()
        est.onPong(senderUs = 1_000_000L, echoUs = 1_000_400L, recvUs = 1_000_800L) // rtt 800, θ=0
        est.onPong(senderUs = 2_000_000L, echoUs = 2_002_500L, recvUs = 2_005_000L) // rtt 5000, θ=2500
        assertEquals(800L, est.rttUs())
        assertEquals(0L, est.offsetUs())
    }

    @Test
    fun `negative rtt sample is ignored`() {
        val est = ClockSyncEstimator()
        assertNull(est.onPong(senderUs = 1_000_100L, echoUs = 1_000_050L, recvUs = 1_000_000L))
        assertNull(est.offsetUs())
    }

    @Test
    fun `no sample offset is null`() {
        assertNull(ClockSyncEstimator().offsetUs())
    }

    // ── opSegmentsMs ──

    @Test
    fun `segments happy path`() {
        // 场景：θ=+250us（服快）；tSend=0 → arr=30_000（上行 29_750us）；注入 2ms；
        // 首帧 encPts=80_000（等帧 48ms）；ack 构造于 srvNow=95_000；客户端 tAck=100_000（E2E 100ms）
        val s = opSegmentsMs(
            tSendUs = 0L, offsetUs = 250L,
            arrUs = 30_000L, injDoneUs = 32_000L,
            encPtsUs = 80_000L, srvNowUs = 95_000L, tAckUs = 100_000L,
        )
        assertEquals(100L, s.e2eAckMs)
        assertEquals(30L, s.uplinkMs)     // (30000−250)/1000 = 29.75 → 30
        assertEquals(2L, s.injectMs)
        assertEquals(48L, s.frameWaitMs)  // 80000−32000
        assertEquals(15L, s.encQueueMs)   // 95000−80000
        assertEquals(65L, s.serverMs)     // 95000−30000
        assertEquals(5L, s.downMs)        // 100−30−65
    }

    @Test
    fun `segments timeout variant frame fields absent`() {
        // 超时回执（encPtsUs=0、frameSeq=0）：帧段以 -1 标记缺失，其余分段照算
        val s = opSegmentsMs(
            tSendUs = 0L, offsetUs = 0L,
            arrUs = 30_000L, injDoneUs = 32_000L,
            encPtsUs = 0L, srvNowUs = 1_500_000L, tAckUs = 1_500_100L,
        )
        assertEquals(-1L, s.frameWaitMs)
        assertEquals(-1L, s.encQueueMs)
        assertEquals(1470L, s.serverMs)
        assertEquals(1500L, s.e2eAckMs)
        assertEquals(30L, s.uplinkMs)
        assertEquals(0L, s.downMs) // 1500−30−1470 = 0
    }

    @Test
    fun `segments clamp negatives to zero`() {
        // 场景 A：θ 虚高（估计偏 > 真实上行）→ uplink 原始值为负 → 钳 0（不确定度 ≈ rtt/2，噪声不入统计）
        val a = opSegmentsMs(
            tSendUs = 1_000_000L, offsetUs = 2_000_000L,
            arrUs = 1_500_000L, injDoneUs = 1_500_500L,
            encPtsUs = 1_600_000L, srvNowUs = 1_600_500L, tAckUs = 1_600_000L,
        )
        assertEquals(0L, a.uplinkMs)
        // 场景 B：θ 低估 100ms（服务端钟实际快 100ms 未被采样）→ uplink 虚高吃掉全部残差 → down 为负 → 钳 0
        val b = opSegmentsMs(
            tSendUs = 0L, offsetUs = 0L,
            arrUs = 110_000L, injDoneUs = 111_000L,
            encPtsUs = 114_000L, srvNowUs = 115_000L, tAckUs = 25_000L,
        )
        assertEquals(25L, b.e2eAckMs)
        assertEquals(110L, b.uplinkMs)
        assertEquals(5L, b.serverMs)
        assertEquals(0L, b.downMs) // 25 − 110 − 5 = −90 → 0
    }

    @Test
    fun `ms rounding is half up`() {
        // 1499us → 1ms；1500us → 2ms（四舍五入，非截断）
        val s1 = opSegmentsMs(0, 0, arrUs = 1_499L, injDoneUs = 1_499L, encPtsUs = 0, srvNowUs = 2_998L, tAckUs = 1_499L)
        assertEquals(1L, s1.e2eAckMs) // 1499us
        assertEquals(1L, s1.serverMs) // 2998−1499 = 1499us
        val s2 = opSegmentsMs(0, 0, arrUs = 1_500L, injDoneUs = 1_500L, encPtsUs = 0, srvNowUs = 3_000L, tAckUs = 1_500L)
        assertEquals(2L, s2.e2eAckMs) // 1500us
        assertEquals(2L, s2.serverMs) // 1500us
    }
}
