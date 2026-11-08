package cloudphone.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {

    private fun statsAt(vararg times: Long): TransportStats {
        val times_ = times.toMutableList()
        return TransportStats { times_.removeAt(0) }
    }

    @Test
    fun `window rates computed per snapshot interval`() {
        val s = statsAt(0, 1000, 2000)
        // 窗口基线快照（首拍不产速率）
        s.snapshot()
        repeat(30) { s.onFrameSent(125_000) } // 30 帧 × 1Mbit @ 窗口 1s → 30fps / 30000kbps
        val snap1 = s.snapshot()
        assertEquals(30.0, snap1.fpsSent, 0.01)
        assertEquals(30000.0, snap1.bitrateKbpsSent, 0.1)
        repeat(15) { s.onFrameSent(125_000) }
        val snap2 = s.snapshot()
        assertEquals(15.0, snap2.fpsSent, 0.01)
        assertEquals(15000.0, snap2.bitrateKbpsSent, 0.1)
        assertEquals(45L, snap2.framesSent)
    }

    @Test
    fun `rtt ewma converges toward samples`() {
        val s = TransportStats { 0L }
        s.onRttSample(100.0)
        assertEquals(100.0, s.snapshot().rttMs, 0.01)
        s.onRttSample(4.0)
        assertEquals(100.0 + 0.25 * (4.0 - 100.0), s.snapshot().rttMs, 0.01)
    }

    @Test
    fun `sequence gap estimator reports loss percentage`() {
        val s = TransportStats { 0L }
        // 收到 1,2,4,5（缺 3）、8（缺 6,7）→ gap=3 received=5 → 37.5%
        listOf(1L, 2L, 4L, 5L, 8L).forEach { s.onVideoSequence(it) }
        assertEquals(37.5, s.snapshot().lossPct, 0.01)
        // 乱序/重复不产生缺口
        s.onVideoSequence(7L)
        assertEquals(37.5, s.snapshot().lossPct, 0.01)
    }

    @Test
    fun `bad events are classified and counted`() {
        val s = TransportStats { 0L }
        s.onBadEvent(TransportStats.BadEvent.DISCONNECT, "conn reset")
        s.onBadEvent(TransportStats.BadEvent.DISCONNECT, "again")
        s.onBadEvent(TransportStats.BadEvent.UDP_TO_TCP_DEGRADE, "8 fails")
        val snap = s.snapshot()
        assertEquals(2L, snap.badEvents[TransportStats.BadEvent.DISCONNECT])
        assertEquals(1L, snap.badEvents[TransportStats.BadEvent.UDP_TO_TCP_DEGRADE])
        assertEquals(TransportStats.BadEvent.UDP_TO_TCP_DEGRADE, snap.lastBadEvent?.event)
    }

    @Test
    fun `fragment drops and receive counters aggregate`() {
        val s = TransportStats { 0L }
        repeat(4) { s.onFragmentDropped() }
        s.onFrameReceived(1000)
        s.onFrameReceived(3000)
        val snap = s.snapshot()
        assertEquals(4L, snap.fragmentsDropped)
        assertEquals(2L, snap.framesReceived)
        assertEquals(4000L, snap.bytesReceived)
        assertTrue(snap.fpsReceived == 0.0) // 首拍无速率
    }
}
