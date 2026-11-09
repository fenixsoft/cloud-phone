package cloudphone.transport

import cloudphone.protocol.ControlMessage
import cloudphone.protocol.Framing
import cloudphone.protocol.TransportKind
import cloudphone.protocol.WatchMode
import cloudphone.protocol.decodeControlMessage
import cloudphone.protocol.encodeControlMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 三后端接入 CryptoCodec 集成测试（p2p-crossnet-traversal 1.4）：
 * 真实 P2pBackend 环回对打（进程内假 STUN）+ 协商后 enableCrypto：
 * - UDP 直连视频片按方向加解密（AAD=帧头 sequence/flags）；
 * - 控制通道（TCP 信令流）按记录序加解密；
 * - 探针帧（sequence=0）恒明文（加密开启时打洞仍工作）。
 */
class CryptoTransportTest {

    /** 进程内假 STUN：Binding Request → XOR-MAPPED-ADDRESS 回显真实源地址（环回打洞） */
    private class FakeStunServer : Closeable {
        private val socket = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val port: Int = socket.localPort
        @Volatile private var running = true
        private val thread = Thread {
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    if (p.length < 20) continue
                    val type = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                    if (type != 0x0001) continue
                    val tid = buf.copyOfRange(8, 20)
                    socket.send(DatagramPacket(bindingResponse(tid, p.address.address, p.port), 32, p.socketAddress))
                } catch (e: Exception) {
                    if (running) continue else return@Thread
                }
            }
        }.apply { isDaemon = true; start() }

        private fun bindingResponse(tid: ByteArray, ip: ByteArray, port: Int): ByteArray {
            val r = ByteArray(32)
            r[0] = 0x01; r[1] = 0x01
            r[4] = 0x21; r[5] = 0x12; r[6] = 0xA4.toByte(); r[7] = 0x42
            System.arraycopy(tid, 0, r, 8, 12)
            r[20] = 0x00; r[21] = 0x20; r[22] = 0x00; r[23] = 0x08
            r[24] = 0x00; r[25] = 0x01
            val xport = port xor (StunClient.MAGIC_COOKIE ushr 16)
            r[26] = ((xport ushr 8) and 0xFF).toByte(); r[27] = (xport and 0xFF).toByte()
            for (k in 0 until 4) r[28 + k] = (ip[k].toInt() xor (StunClient.MAGIC_COOKIE ushr (24 - 8 * k))).toByte()
            return r
        }

        override fun close() {
            running = false
            socket.close()
        }
    }

    /** 双端按同一协商材料建 codec 对（client 发 c2s / source 发 s2c） */
    private fun codecPair(): Pair<CryptoCodec, CryptoCodec> {
        val clientNonce = CryptoCodec.generateHandshakeNonce()
        val sourceNonce = CryptoCodec.generateHandshakeNonce()
        val token = "pair-token"
        return CryptoCodec.negotiate(Role.CLIENT, token, clientNonce, sourceNonce) to
            CryptoCodec.negotiate(Role.SOURCE, token, clientNonce, sourceNonce)
    }

    @Test
    fun `p2p encrypted video and control over udp direct`() {
        val tcpPort = java.net.ServerSocket(0).let { s -> s.localPort.also { s.close() } }
        FakeStunServer().use { stun ->
            val source = P2pBackend(
                TransportConfig(Role.SOURCE, BackendKind.P2P, "127.0.0.1", tcpPort, 0),
                stunServers = listOf(StunClient.StunServer("127.0.0.1", stun.port)),
                punchTimeoutMs = 5000,
            )
            val client = P2pBackend(
                TransportConfig(Role.CLIENT, BackendKind.P2P, "127.0.0.1", tcpPort, 0),
                stunServers = listOf(StunClient.StunServer("127.0.0.1", stun.port)),
                punchTimeoutMs = 5000,
            )
            val srcVideo = LinkedBlockingQueue<Framing.VideoFrame>()
            val srcControl = LinkedBlockingQueue<ByteArray>()
            val srcPunch = LinkedBlockingQueue<StreamEvent>()
            val cliPunch = LinkedBlockingQueue<StreamEvent>()
            source.onVideo = VideoSink { srcVideo.put(it) }
            source.onControl = ControlSink { srcControl.put(it) }
            source.onStreamEvent = StreamEventListener { e, _ -> srcPunch.put(e) }
            client.onStreamEvent = StreamEventListener { e, _ -> cliPunch.put(e) }
            try {
                source.start()
                client.start()
                // 信令交换（offer/answer）→ 双端打洞（探针 sequence=0 恒明文，密钥未建立也能通）
                source.onPeerSrflx(client.localSrflx!!)
                client.onPeerSrflx(source.localSrflx!!)
                // 等双端直连建立（探针 200ms 间隔，环回应秒通）
                fun waitPunch(q: LinkedBlockingQueue<StreamEvent>, who: String) {
                    var e = q.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("$who punch not established")
                    var guard = 0
                    while (e != StreamEvent.PUNCH_ESTABLISHED) {
                        if (++guard > 10) throw AssertionError("$who event stream stuck: $e")
                        e = q.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("$who punch not established")
                    }
                }
                waitPunch(srcPunch, "source")
                waitPunch(cliPunch, "client")
                assertEquals(TransportKind.P2P, client.wireKind)

                // 协商成功 → 双端 enableCrypto（client 发 c2s、source 发 s2c）
                val (cliCodec, srcCodec) = codecPair()
                client.enableCrypto(cliCodec)
                source.enableCrypto(srcCodec)

                // 加密控制消息走信令 TCP：源端收到的必是解密后的原始编码字节
                val hello = ControlMessage.Hello(
                    version = 1, token = "pair-token", mode = WatchMode.FULLSCREEN,
                    packageName = "", width = 0, height = 0, fps = 0, bitrateKbps = 0,
                    sessionId = 0, p2pOffer = "",
                )
                client.sendControl(encodeControlMessage(hello))
                val ctl = srcControl.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("encrypted control not received")
                assertEquals(hello, decodeControlMessage(ctl))

                // 加密视频走 UDP 直连：2500B 帧分 3 片，每片独立 AEAD（+16B tag），重组还原
                val data = ByteArray(2500) { (it % 251).toByte() }
                client.sendVideo(sequence = 100, ptsUs = 777_777, keyframe = true, frame = data)
                val frame = srcVideo.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("encrypted video not received")
                assertEquals(100L, frame.sequence)
                assertEquals(777_777L, frame.ptsUs)
                assertTrue(frame.keyframe)
                assertArrayEquals(data, frame.data)

                // 反向控制（source → client，s2c 方向）：客户端不在此断言（无队列挂接），仅验证发送不抛
                source.sendControl(encodeControlMessage(ControlMessage.Ping(9, 123L)))
                assertEquals(TransportKind.P2P, source.wireKind)
            } finally {
                client.close()
                source.close()
            }
        }
    }

    @Test
    fun `plaintext path unchanged when crypto never enabled`() {
        // 未 enableCrypto：控制/视频路径与 v1 现状一致（回归基线：既有 TransportLoopbackTest
        // 全绿 + 此处显式断言 cipher 缺省时 sendVideo 字节路径直通）
        val src = UdpVideoChannel(
            socket = DatagramSocket(InetSocketAddress("127.0.0.1", 0)),
            fragmentBytes = 1200,
            stats = TransportStats(),
            callbacks = object : ChannelCallbacks {
                override fun controlSink(): ControlSink? = null
                override fun videoSink(): VideoSink? = null
            },
        )
        assertEquals(null, src.crypto)
        src.setRemote(InetSocketAddress("127.0.0.1", 1)) // 无人收：仅验证发送路径不抛
        assertEquals(UdpVideoChannel.SendResult.SENT, src.send(7, 1, false, ByteArray(64)))
        src.close()
    }
}
