package cloudphone.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FramingTest {

    private val hello = ControlMessage.Hello(
        version = PROTOCOL_VERSION, token = "pair-token-42", mode = WatchMode.SINGLE_APP,
        packageName = "cloudphone.loadgen", width = 1280, height = 720, fps = 60,
        bitrateKbps = 8000, sessionId = 0, p2pOffer = "14.155.102.104:17317",
    )

    private val helloAck = ControlMessage.HelloAck(
        status = HelloStatus.ACCEPTED, reason = "", sessionId = 7, width = 1280, height = 720,
        fps = 60, bitrateKbps = 8000, encoder = "OMX.qcom.video.encoder.avc", displayId = 2,
        p2pAnswer = "14.155.102.104:17318",
        hungSessions = listOf(
            HungSession(3, WatchMode.SINGLE_APP, "cloudphone.loadgen", 1280, 720),
            HungSession(4, WatchMode.FULLSCREEN, "", 1080, 1920),
        ),
    )

    private fun <T : ControlMessage> roundTrip(msg: T): ControlMessage =
        decodeControlMessage(encodeControlMessage(msg))

    @Test
    fun `hello round trip`() {
        val back = roundTrip(hello) as ControlMessage.Hello
        assertEquals(hello, back)
    }

    @Test
    fun `hello ack accepted round trip with hung sessions`() {
        val back = roundTrip(helloAck) as ControlMessage.HelloAck
        assertEquals(helloAck, back)
        assertEquals(2, back.hungSessions.size)
    }

    @Test
    fun `hello ack rejected carries reason`() {
        val rejected = ControlMessage.HelloAck(
            status = HelloStatus.REJECT_BAD_TOKEN, reason = "token mismatch", sessionId = 0,
            width = 0, height = 0, fps = 0, bitrateKbps = 0, encoder = "", displayId = -1,
            p2pAnswer = "", hungSessions = emptyList(),
        )
        assertEquals(rejected, roundTrip(rejected))
    }

    @Test
    fun `session lifecycle and input messages round trip`() {
        val start = ControlMessage.StartSession(0, WatchMode.SINGLE_APP, "cloudphone.loadgen", 1920, 1080, 30, 12000)
        assertEquals(start, roundTrip(start))
        assertEquals(ControlMessage.StopSession(9), roundTrip(ControlMessage.StopSession(9)))
        assertEquals(ControlMessage.IdrRequest(9), roundTrip(ControlMessage.IdrRequest(9)))
        val input = ControlMessage.InputEvent(9, InputAction.MOVE, 1, 32768, 1024)
        assertEquals(input, roundTrip(input))
        assertEquals(ControlMessage.InputEvent(9, InputAction.BACK, 0, 0, 0), roundTrip(ControlMessage.InputEvent(9, InputAction.BACK, 0, 0, 0)))
    }

    @Test
    fun `stats ping pong round trip`() {
        val stats = ControlMessage.StatsMessage(9, 15, 58.4f, 8123.5f, 3.2f, 0.41f, TransportKind.FRP)
        assertEquals(stats, roundTrip(stats))
        val ping = ControlMessage.Ping(0x1122334455667788L, 1_000_000_123L)
        assertEquals(ping, roundTrip(ping))
        val pong = ControlMessage.Pong(0x1122334455667788L, 1_000_000_123L, 1_000_000_145L)
        assertEquals(pong, roundTrip(pong))
    }

    @Test
    fun `unknown message type is ignored without breaking session`() {
        val unknown = ByteArray(1 + 4).also { buf ->
            buf[0] = 0x7F
            buf[1] = 1; buf[2] = 2; buf[3] = 3; buf[4] = 4
        }
        val decoded = decodeControlMessage(unknown)
        assertTrue("未知类型应解码为 Unknown", decoded is ControlMessage.Unknown)
        assertEquals(0x7F, (decoded as ControlMessage.Unknown).unknownType)
        // 未知消息之后既有会话继续正常收发
        assertEquals(hello, roundTrip(hello))
    }

    @Test
    fun `stream records round trip interleaved control and video`() {
        val out = ByteArrayOutputStream()
        val videoPayload = Framing.encodeVideoPayload(
            Framing.VideoFrameHeader(1, 123456L, true, 0, 1, 4), byteArrayOf(1, 2, 3, 4),
        )
        Framing.writeRecord(out, Framing.RECORD_CONTROL, encodeControlMessage(hello))
        Framing.writeRecord(out, Framing.RECORD_VIDEO, videoPayload)
        Framing.writeRecord(out, Framing.RECORD_CONTROL, encodeControlMessage(ControlMessage.Ping(5, 7)))
        val reader = Framing.StreamRecordReader(ByteArrayInputStream(out.toByteArray()))

        val r1 = reader.read()!!
        assertEquals(Framing.RECORD_CONTROL, r1.type)
        assertEquals(hello, decodeControlMessage(r1.payload))

        val r2 = reader.read()!!
        assertEquals(Framing.RECORD_VIDEO, r2.type)
        val header = Framing.decodeVideoHeader(java.nio.ByteBuffer.wrap(r2.payload))
        assertEquals(1L, header.sequence)
        assertEquals(123456L, header.ptsUs)
        assertTrue(header.keyframe)
        assertEquals(4, header.totalLength)

        val r3 = reader.read()!!
        assertEquals(ControlMessage.Ping(5, 7), decodeControlMessage(r3.payload))
        assertNull("EOF 应返回 null", reader.read())
    }

    @Test
    fun `udp fragmentation reassembles out of order`() {
        val data = ByteArray(3000) { (it % 251).toByte() }
        val datagrams = Framing.VideoFragmenter(maxDatagramPayload = 1200)
            .fragment(sequence = 42, ptsUs = 999_999L, keyframe = false, data = data)
        assertEquals("3000B 按 1179B 分片应得 3 片", 3, datagrams.size)

        val reassembler = Framing.FrameReassembler(timeoutMs = 500)
        // 乱序投递（2,0,1）
        var frame: Framing.VideoFrame? = null
        for (idx in intArrayOf(2, 0, 1)) {
            val buf = java.nio.ByteBuffer.wrap(datagrams[idx])
            val header = Framing.decodeVideoHeader(buf)
            buf.position(Framing.VIDEO_HEADER_BYTES)
            val chunk = ByteArray(buf.remaining())
            buf.get(chunk)
            frame = reassembler.offer(header, chunk) ?: frame
        }
        assertTrue("三片凑齐应出帧", frame != null)
        assertEquals(42L, frame!!.sequence)
        assertEquals(999_999L, frame.ptsUs)
        assertTrue(data.contentEquals(frame.data))
        assertEquals(1L, reassembler.completedFrames)
        assertEquals(0L, reassembler.droppedFrames)
    }

    @Test
    fun `missing fragment times out and drops frame`() {
        val data = ByteArray(3000)
        val datagrams = Framing.VideoFragmenter(maxDatagramPayload = 1200)
            .fragment(sequence = 1, ptsUs = 1L, keyframe = true, data = data)
        val reassembler = Framing.FrameReassembler(timeoutMs = 100)

        fun offerAt(idx: Int, nowMs: Long): Framing.VideoFrame? {
            val buf = java.nio.ByteBuffer.wrap(datagrams[idx])
            val header = Framing.decodeVideoHeader(buf)
            buf.position(Framing.VIDEO_HEADER_BYTES)
            val chunk = ByteArray(buf.remaining())
            buf.get(chunk)
            return reassembler.offer(header, chunk, nowMs)
        }

        assertNull(offerAt(0, 0))
        assertNull(offerAt(2, 10))
        // 超时清理后再投最后一片：组已丢弃
        assertNull(offerAt(1, 200))
        assertEquals(1L, reassembler.droppedFrames)
        assertEquals(0L, reassembler.completedFrames)
    }

    @Test
    fun `duplicate fragment is ignored`() {
        val datagrams = Framing.VideoFragmenter(maxDatagramPayload = 1200)
            .fragment(sequence = 9, ptsUs = 2L, keyframe = false, data = ByteArray(100))
        val reassembler = Framing.FrameReassembler()

        fun offerAt(idx: Int): Framing.VideoFrame? {
            val buf = java.nio.ByteBuffer.wrap(datagrams[idx])
            val header = Framing.decodeVideoHeader(buf)
            buf.position(Framing.VIDEO_HEADER_BYTES)
            val chunk = ByteArray(buf.remaining())
            buf.get(chunk)
            return reassembler.offer(header, chunk)
        }
        assertEquals("100B 数据应只有 1 片", 1, datagrams.size)
        val frame = offerAt(0)
        assertTrue("单帧单分片首次投递即完成出帧", frame != null)
        assertNull("重复分片应被忽略", offerAt(0))
        assertEquals("重复分片不得产生第二帧", 1L, reassembler.completedFrames)
    }

    @Test
    fun `frame without head fragment cannot start group`() {
        val datagrams = Framing.VideoFragmenter(maxDatagramPayload = 1200)
            .fragment(sequence = 3, ptsUs = 3L, keyframe = false, data = ByteArray(2500))
        val reassembler = Framing.FrameReassembler()
        val buf = java.nio.ByteBuffer.wrap(datagrams[1])
        val header = Framing.decodeVideoHeader(buf)
        buf.position(Framing.VIDEO_HEADER_BYTES)
        val chunk = ByteArray(buf.remaining())
        buf.get(chunk)
        assertNull("缺首片的中间分片不得建组", reassembler.offer(header, chunk))
        assertEquals(0L, reassembler.completedFrames)
    }

    @Test
    fun `video header round trip preserves all fields`() {
        val header = Framing.VideoFrameHeader(
            sequence = 0xFFFFFFFFL, ptsUs = Long.MAX_VALUE / 2, keyframe = true,
            fragmentIndex = 3, fragmentCount = 7, totalLength = 123456,
        )
        val payload = Framing.encodeVideoPayload(header, ByteArray(10))
        val back = Framing.decodeVideoHeader(java.nio.ByteBuffer.wrap(payload))
        assertEquals(header, back)
    }
}
