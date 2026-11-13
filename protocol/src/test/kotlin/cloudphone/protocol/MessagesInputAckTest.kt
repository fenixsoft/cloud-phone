package cloudphone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * op-latency 协议扩展单测（op-latency-measurement 清单 ①）：
 * - InputEvent 可选尾块 opId（opId=0 时线格式与 v1 基线逐字节一致，老端忽略尾块）；
 * - INPUT_ACK(0x12) 编解码往返与手工字节流解码（客户端侧对账）；
 * - 未知类型邻域回归（0x13 仍 Unknown，连接语义不变）。
 */
class MessagesInputAckTest {

    private fun roundtrip(msg: ControlMessage): ControlMessage =
        decodeControlMessage(encodeControlMessage(msg))

    // ── InputEvent opId 尾块 ──

    @Test
    fun `input event opId zero encodes legacy 11 bytes`() {
        // opId=0：线格式必须与扩展前逐字节一致（老端兼容回归保障）
        val bytes = encodeControlMessage(
            ControlMessage.InputEvent(
                sessionId = 0x01020304, action = InputAction.DOWN, pointerId = 5,
                x = 0x0102, y = 0x0304, opId = 0,
            ),
        )
        assertEquals(0x06, bytes[0].toInt() and 0xFF)
        assertEquals(11, bytes.size)
        val expected = byteArrayOf(
            0x06,
            0x01, 0x02, 0x03, 0x04, // sessionId
            0x00,                   // DOWN
            0x05,                   // pointerId
            0x01, 0x02,             // x
            0x03, 0x04,             // y
        )
        assertTrue(expected.contentEquals(bytes))
    }

    @Test
    fun `input event opId nonzero appends 4 byte tail`() {
        val bytes = encodeControlMessage(
            ControlMessage.InputEvent(
                sessionId = 1, action = InputAction.UP, pointerId = 0,
                x = 100, y = 200, opId = 0x11223344,
            ),
        )
        assertEquals(15, bytes.size)
        assertEquals(0x11, bytes[11].toInt() and 0xFF)
        assertEquals(0x22, bytes[12].toInt() and 0xFF)
        assertEquals(0x33, bytes[13].toInt() and 0xFF)
        assertEquals(0x44, bytes[14].toInt() and 0xFF)
        val out = decodeControlMessage(bytes) as ControlMessage.InputEvent
        assertEquals(0x11223344, out.opId)
        assertEquals(InputAction.UP, out.action)
    }

    @Test
    fun `legacy 10 byte input event decodes opId zero`() {
        // 老端字节流（无尾块）→ 新端解码：opId 缺省 0，既有字段不受影响
        val legacy = byteArrayOf(
            0x06,
            0x00, 0x00, 0x00, 0x02, // sessionId=2
            0x01,                   // UP
            0x03,                   // pointerId=3
            0x27, 0x10,             // x=10000
            0x4E, 0x20,             // y=20000
        )
        val out = decodeControlMessage(legacy) as ControlMessage.InputEvent
        assertEquals(2, out.sessionId)
        assertEquals(InputAction.UP, out.action)
        assertEquals(3, out.pointerId)
        assertEquals(10000, out.x)
        assertEquals(20000, out.y)
        assertEquals(0, out.opId)
    }

    @Test
    fun `input event opId roundtrip`() {
        val out = roundtrip(
            ControlMessage.InputEvent(9, InputAction.MOVE, 1, 300, 400, opId = 7),
        ) as ControlMessage.InputEvent
        assertEquals(7, out.opId)
        assertEquals(9, out.sessionId)
        assertEquals(InputAction.MOVE, out.action)
        assertEquals(300, out.x)
        assertEquals(400, out.y)
    }

    // ── INPUT_ACK (0x12) ──

    @Test
    fun `input ack wire layout is 46 bytes with type 0x12`() {
        // 线格式：[0x12][u32 opId][u8 result][i64 arrUs][i64 injDoneUs][u64 frameSeq][i64 encPtsUs][i64 srvNowUs]
        val bytes = encodeControlMessage(
            ControlMessage.InputAck(
                opId = 1, result = InputAckResult.OK,
                arrUs = 2, injDoneUs = 3, frameSeq = 4, encPtsUs = 5, srvNowUs = 6,
            ),
        )
        assertEquals(0x12, bytes[0].toInt() and 0xFF)
        assertEquals(46, bytes.size)
    }

    @Test
    fun `input ack roundtrip preserves all fields`() {
        val msg = ControlMessage.InputAck(
            opId = 0x7F654321, result = InputAckResult.OK,
            arrUs = 1_234_567_890L, injDoneUs = 1_234_569_000L,
            frameSeq = 0x1FFFFFFFFFL, encPtsUs = 1_234_570_500L, srvNowUs = 1_234_571_000L,
        )
        val out = roundtrip(msg) as ControlMessage.InputAck
        assertEquals(msg.opId, out.opId)
        assertEquals(InputAckResult.OK, out.result)
        assertEquals(msg.arrUs, out.arrUs)
        assertEquals(msg.injDoneUs, out.injDoneUs)
        assertEquals(msg.frameSeq, out.frameSeq)
        assertEquals(msg.encPtsUs, out.encPtsUs)
        assertEquals(msg.srvNowUs, out.srvNowUs)
    }

    @Test
    fun `input ack all result codes roundtrip`() {
        for (r in listOf(
            InputAckResult.OK, InputAckResult.UNKNOWN_SESSION,
            InputAckResult.INJECT_FAILED, InputAckResult.FRAME_TIMEOUT,
        )) {
            val out = roundtrip(
                ControlMessage.InputAck(42, r, 100, 200, 0, 0, 300),
            ) as ControlMessage.InputAck
            assertEquals(r, out.result)
            assertEquals(42, out.opId)
            assertEquals(100, out.arrUs)
            assertEquals(200, out.injDoneUs)
            assertEquals(0L, out.frameSeq)
            assertEquals(0L, out.encPtsUs)
            assertEquals(300, out.srvNowUs)
        }
    }

    @Test
    fun `input ack timeout variant frame fields zero`() {
        // 超时回执约定：frameSeq=0、encPtsUs=0（客户端据此判定无帧段）
        val out = roundtrip(
            ControlMessage.InputAck(
                opId = 5, result = InputAckResult.FRAME_TIMEOUT,
                arrUs = 10_000, injDoneUs = 11_000, frameSeq = 0, encPtsUs = 0, srvNowUs = 26_000,
            ),
        ) as ControlMessage.InputAck
        assertEquals(InputAckResult.FRAME_TIMEOUT, out.result)
        assertEquals(0L, out.frameSeq)
        assertEquals(0L, out.encPtsUs)
    }

    @Test
    fun `hand built input ack bytes decode`() {
        // 手工字节流（大端）校验线序：opId=0x00000007, result=OK, arr=1000, inj=1500,
        // frameSeq=42, encPts=1800, srvNow=2000
        val b = java.io.ByteArrayOutputStream()
        b.write(0x12)
        b.write(byteArrayOf(0, 0, 0, 7))
        b.write(0)
        fun i64(v: Long) {
            for (sh in 56 downTo 0 step 8) b.write(((v ushr sh) and 0xFF).toInt())
        }
        i64(1000); i64(1500)
        for (sh in 56 downTo 0 step 8) b.write((((42L ushr sh) and 0xFF).toInt()))
        i64(1800); i64(2000)
        val out = decodeControlMessage(b.toByteArray()) as ControlMessage.InputAck
        assertEquals(7, out.opId)
        assertEquals(InputAckResult.OK, out.result)
        assertEquals(1000L, out.arrUs)
        assertEquals(1500L, out.injDoneUs)
        assertEquals(42L, out.frameSeq)
        assertEquals(1800L, out.encPtsUs)
        assertEquals(2000L, out.srvNowUs)
    }

    @Test
    fun `truncated input ack throws protocol exception`() {
        // 结构性损坏（长度不足）：与其他消息同语义——ProtocolException，记录级丢弃不拖垮会话
        val bytes = encodeControlMessage(
            ControlMessage.InputAck(1, InputAckResult.OK, 2, 3, 4, 5, 6),
        )
        try {
            decodeControlMessage(bytes.copyOfRange(0, bytes.size - 8))
            fail("truncated INPUT_ACK must throw ProtocolException")
        } catch (_: cloudphone.protocol.ProtocolException) {
            // expected
        }
    }

    @Test
    fun `neighbor undefined type 0x13 stays unknown`() {
        // INPUT_ACK 占用 0x12 后，邻位 0x13 仍属未定义段：Unknown 忽略不断会话
        val payload = byteArrayOf(0x13, 0x01, 0x02, 0x03)
        val msg = decodeControlMessage(payload)
        assertTrue(msg is ControlMessage.Unknown)
        assertEquals(0x13, (msg as ControlMessage.Unknown).unknownType)
    }
}
