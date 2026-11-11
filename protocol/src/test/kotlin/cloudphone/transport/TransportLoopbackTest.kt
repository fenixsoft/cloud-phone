package cloudphone.transport

import cloudphone.protocol.Framing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 环回集成测试：用本机环回模拟"源端 accept + 客户端 connect"，
 * 走真实 socket 验证三通道机制（控制 / TCP 复用视频 / UDP 视频与降级）。
 * 不依赖模拟器与公网。
 */
class TransportLoopbackTest {

    private fun freeUdpPort(): Int = DatagramSocket().let { s -> val p = s.localPort; s.close(); p }

    private fun freeTcpPort(): Int = ServerSocket(0).let { s -> val p = s.localPort; s.close(); p }

    private class Received<T> {
        private val queue = LinkedBlockingQueue<T>()
        fun push(v: T) = queue.put(v)
        fun take(timeoutMs: Long = 5000): T = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw AssertionError("等待数据超时")
    }

    private fun pair(useUdp: Boolean): Pair<FrpBackend, FrpBackend> {
        val tcpPort = freeTcpPort()
        val udpPort = if (useUdp) freeUdpPort() else 0
        val source = FrpBackend(
            TransportConfig(Role.SOURCE, BackendKind.FRP, "127.0.0.1", tcpPort, udpPort, useUdpVideo = useUdp),
        )
        val client = FrpBackend(
            TransportConfig(Role.CLIENT, BackendKind.FRP, "127.0.0.1", tcpPort, udpPort, useUdpVideo = useUdp),
        )
        return source to client
    }

    @Test
    fun `control messages round trip over loopback`() {
        val (source, client) = pair(useUdp = false)
        val srcControl = Received<ByteArray>()
        val cliControl = Received<ByteArray>()
        source.onControl = ControlSink { srcControl.push(it) }
        client.onControl = ControlSink { cliControl.push(it) }
        source.start()
        client.start()
        try {
            client.sendControl(byteArrayOf(0x01, 1, 2, 3)) // HELLO 形态字节
            val hello = srcControl.take()
            assertTrue(hello.contentEquals(byteArrayOf(0x01, 1, 2, 3)))
            source.sendControl(byteArrayOf(0x02, 9))
            val ack = cliControl.take()
            assertTrue(ack.contentEquals(byteArrayOf(0x02, 9)))
        } finally {
            client.close(); source.close()
        }
    }

    @Test
    fun `video over tcp mux arrives intact`() {
        val (source, client) = pair(useUdp = false)
        val srcVideo = Received<Framing.VideoFrame>()
        source.onVideo = VideoSink { srcVideo.push(it) }
        source.start()
        client.start()
        try {
            val data = ByteArray(40 * 1024) { (it % 241).toByte() }
            client.sendVideo(sequence = 7, ptsUs = 123_456L, keyframe = true, frame = data)
            val frame = srcVideo.take()
            assertEquals(7L, frame.sequence)
            assertEquals(123_456L, frame.ptsUs)
            assertTrue(frame.keyframe)
            assertTrue(data.contentEquals(frame.data))
            val snap = client.snapshot()
            assertEquals(1L, snap.framesSent)
            assertEquals(data.size.toLong(), snap.bytesSent)
        } finally {
            client.close(); source.close()
        }
    }

    @Test
    fun `video over udp fragments reassembles on receiver`() {
        val (source, client) = pair(useUdp = true)
        val srcVideo = Received<Framing.VideoFrame>()
        val cliVideo = Received<Framing.VideoFrame>()
        source.onVideo = VideoSink { srcVideo.push(it) }
        client.onVideo = VideoSink { cliVideo.push(it) }
        source.start()
        client.start()
        try {
            // >1200B 帧触发分片；连发 3 帧验证序号与重组
            for (seq in 1L..3L) {
                val data = ByteArray(3000) { (it * seq).toByte() }
                client.sendVideo(sequence = seq, ptsUs = seq * 1000, keyframe = seq == 1L, frame = data)
            }
            val f1 = srcVideo.take()
            assertEquals(1L, f1.sequence)
            assertTrue(f1.keyframe)
            val f2 = srcVideo.take()
            assertEquals(2L, f2.sequence)
            val f3 = srcVideo.take()
            assertEquals(3L, f3.sequence)
            assertEquals(3000, f3.data.size)
            assertEquals(3L, source.snapshot().framesReceived)
            // 源端已从首包学到客户端 UDP 地址 → 反向 UDP 亦通
            source.sendVideo(sequence = 10, ptsUs = 99, keyframe = false, frame = ByteArray(500))
            val back = cliVideo.take()
            assertEquals(10L, back.sequence)
            assertEquals(500, back.data.size)
        } finally {
            client.close(); source.close()
        }
    }

    @Test
    fun `source udp frames without peer temporarily use tcp without degrade`() {
        val tcpPort = freeTcpPort()
        val udpPort = freeUdpPort()
        // 源端 UDP 已启用但客户端从不发 UDP 包（无对端地址）：源端视频帧应临时走 TCP，
        // 且 MUST NOT 记录 UDP→TCP 永久降级事件（等首包学习后仍可用 UDP）
        val source = FrpBackend(
            TransportConfig(Role.SOURCE, BackendKind.FRP, "127.0.0.1", tcpPort, udpPort, useUdpVideo = true),
        )
        val client = FrpBackend(
            TransportConfig(Role.CLIENT, BackendKind.FRP, "127.0.0.1", tcpPort, udpPort, useUdpVideo = false),
        )
        val cliVideo = Received<Framing.VideoFrame>()
        client.onVideo = VideoSink { cliVideo.push(it) }
        source.start()
        client.start()
        try {
            // 占用保护后源端晋升以首条控制记录为门：先说 HELLO 并等源端转发回执，
            // 确认晋升完成（channel 在位）后再发源端→客户端视频，避免竞态丢首帧
            val srcControl = Received<ByteArray>()
            source.onControl = ControlSink { srcControl.push(it) }
            client.sendControl(byteArrayOf(0x01, 1))
            assertNotNull(srcControl.take())
            val data = ByteArray(1500) { (it % 13).toByte() }
            source.sendVideo(sequence = 1, ptsUs = 11, keyframe = true, frame = data)
            val frame = cliVideo.take()
            assertTrue(data.contentEquals(frame.data))
            assertFalse(
                "无对端不等于通道坏死：不得记永久降级事件",
                source.snapshot().badEvents.containsKey(TransportStats.BadEvent.UDP_TO_TCP_DEGRADE),
            )
        } finally {
            client.close(); source.close()
        }
    }

    @Test
    fun `udp channel hard-fails after threshold sends and records degrade event`() {
        val stats = TransportStats()
        val socket = DatagramSocket()
        val channel = UdpVideoChannel(
            socket = socket,
            fragmentBytes = 1200,
            stats = stats,
            callbacks = object : ChannelCallbacks {
                override fun controlSink(): ControlSink? = null
                override fun videoSink(): VideoSink? = null
            },
        )
        channel.setRemote(InetSocketAddress("127.0.0.1", freeUdpPort()))
        socket.close() // 人为坏死通道：后续 send 全部抛异常
        var result: UdpVideoChannel.SendResult = UdpVideoChannel.SendResult.SENT
        repeat(UdpVideoChannel.SEND_FAILURE_DEGRADE_THRESHOLD) {
            result = channel.send(sequence = 1, ptsUs = 1, keyframe = false, frame = ByteArray(10))
        }
        assertEquals(UdpVideoChannel.SendResult.FAILED, result)
        assertNotNull(stats.snapshot().lastBadEvent)
        assertEquals(
            TransportStats.BadEvent.UDP_TO_TCP_DEGRADE,
            stats.snapshot().lastBadEvent?.event,
        )
    }

    @Test
    fun `lan-direct backend connects over loopback with low rtt`() {
        val tcpPort = freeTcpPort()
        val source = LanDirectBackend(
            TransportConfig(Role.SOURCE, BackendKind.LAN_DIRECT, "127.0.0.1", tcpPort, 0, useUdpVideo = false),
        )
        val client = LanDirectBackend(
            TransportConfig(Role.CLIENT, BackendKind.LAN_DIRECT, "127.0.0.1", tcpPort, 0, useUdpVideo = false),
        )
        val srcControl = Received<ByteArray>()
        source.onControl = ControlSink { srcControl.push(it) }
        source.start()
        client.start()
        try {
            val pingPayload = byteArrayOf(0x08)
            val t0 = System.nanoTime()
            repeat(100) { client.sendControl(pingPayload); srcControl.take() }
            val perOpUs = (System.nanoTime() - t0) / 1000 / 100
            assertTrue("环回单次往返应 ≤5ms（实测 ${perOpUs}µs）", perOpUs < 5_000)
        } finally {
            client.close(); source.close()
        }
    }

    @Test
    fun `transport stats classify disconnect event on abrupt close`() {
        val tcpPort = freeTcpPort()
        val source = FrpBackend(
            TransportConfig(Role.SOURCE, BackendKind.FRP, "127.0.0.1", tcpPort, 0, useUdpVideo = false),
        )
        val client = FrpBackend(
            TransportConfig(Role.CLIENT, BackendKind.FRP, "127.0.0.1", tcpPort, 0, useUdpVideo = false),
        )
        val events = Received<StreamEvent>()
        source.onStreamEvent = StreamEventListener { e, _ -> events.push(e) }
        source.start()
        client.start()
        try {
            client.sendControl(byteArrayOf(0x01, 1)) // HELLO 形态字节 → 晋升为现役（pending 探针死亡静默吸收）
            client.close() // 客户端 abrupt close → 源端读泵报错
            var event = events.take(10_000)
            while (event != StreamEvent.DISCONNECTED) {
                event = events.take(10_000) // 先 CONNECTED 后 DISCONNECTED，取到目标事件为止
            }
            assertEquals(StreamEvent.DISCONNECTED, event)
            assertTrue(source.snapshot().badEvents.containsKey(TransportStats.BadEvent.DISCONNECT))
        } finally {
            source.close()
        }
    }
}
