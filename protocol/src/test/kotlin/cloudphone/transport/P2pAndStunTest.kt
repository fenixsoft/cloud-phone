package cloudphone.transport

import cloudphone.protocol.Framing
import cloudphone.protocol.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * StunClient / Diagnostics / P2pBackend 测试。
 * 用进程内"假 STUN 服务器"（ faithfully 实现 Binding Request → XOR-MAPPED-ADDRESS 应答）
 * 做确定性验证：不依赖公网节点；p2p 打洞走本机环回双 socket 对打。
 */
class P2pAndStunTest {

    // ── 假 STUN 服务器 ──

    /** cannedIp/cannedPort 为 null 时回显请求包的真实源地址（环回打洞用） */
    private class FakeStunServer(
        private val cannedIp: String? = null,
        private val cannedPort: Int? = null,
    ) : Closeable {
        val socket = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val port: Int = socket.localPort
        @Volatile private var running = true
        val seenSources = LinkedBlockingQueue<InetSocketAddress>()

        private val thread = Thread {
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    if (packet.length < 20) continue
                    val type = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                    if (type != 0x0001) continue
                    val tid = buf.copyOfRange(8, 20)
                    val ip: ByteArray
                    val p: Int
                    if (cannedIp == null) {
                        ip = packet.address.address
                        p = packet.port
                    } else {
                        ip = InetAddress.getByName(cannedIp).address
                        p = cannedPort!!
                    }
                    seenSources.put(InetSocketAddress(packet.address, packet.port))
                    socket.send(DatagramPacket(bindingResponse(tid, ip, p), 32, packet.socketAddress))
                } catch (e: Exception) {
                    if (running) continue else return@Thread
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            running = false
            socket.close()
        }

        companion object {
            fun bindingResponse(tid: ByteArray, ip: ByteArray, port: Int): ByteArray {
                val resp = ByteArray(32)
                resp[0] = 0x01; resp[1] = 0x01 // Binding Response
                resp[2] = 0; resp[3] = 12 // length
                val magic = StunClient.MAGIC_COOKIE
                resp[4] = (magic ushr 24).toByte(); resp[5] = (magic ushr 16).toByte()
                resp[6] = (magic ushr 8).toByte(); resp[7] = magic.toByte()
                System.arraycopy(tid, 0, resp, 8, 12)
                resp[20] = 0x00; resp[21] = 0x20 // XOR-MAPPED-ADDRESS
                resp[22] = 0; resp[23] = 8
                resp[24] = 0x00 // reserved
                resp[25] = 0x01 // family IPv4
                val xport = port xor (magic ushr 16)
                resp[26] = (xport ushr 8).toByte(); resp[27] = xport.toByte() // X-Port
                for (k in 0 until 4) resp[28 + k] = (ip[k].toInt() xor (magic ushr (24 - 8 * k))).toByte()
                return resp
            }
        }
    }

    private fun server(port: Int) = StunClient.StunServer("127.0.0.1", port)

    // ── StunClient ──

    @Test
    fun `binding request round trip parses xor mapped address`() {
        FakeStunServer().use { fake ->
            DatagramSocket().use { sock ->
                val result = StunClient.query(sock, server(fake.port), timeoutMs = 2000)
                assertNotNull("假 STUN 应答可解析出 SRFLX", result)
                assertEquals("127.0.0.1", result!!.mappedIp)
                assertEquals(sock.localPort, result.mappedPort)
                assertTrue(result.rttMs >= 0)
            }
        }
    }

    @Test
    fun `unreachable stun returns null not exception`() {
        DatagramSocket().use { sock ->
            // 不可达端口（绑定后立即关闭的端口）
            val dead = DatagramSocket().let { s -> val p = s.localPort; s.close(); p }
            val result = StunClient.query(sock, server(dead), timeoutMs = 200, retries = 0)
            assertEquals(null, result)
        }
    }

    @Test
    fun `srflx string format round trip`() {
        val addr = InetSocketAddress("14.155.102.104", 17317)
        assertEquals("14.155.102.104:17317", StunClient.formatSrflx(addr))
        assertEquals(addr, StunClient.parseSrflx(StunClient.formatSrflx(addr)))
        assertEquals(null, StunClient.parseSrflx(""))
        assertEquals(null, StunClient.parseSrflx("bad"))
        assertEquals(null, StunClient.parseSrflx("host:99999"))
    }

    // ── Diagnostics ──

    @Test
    fun `same mapping across servers judges cone nat`() {
        FakeStunServer("9.9.9.9", 40000).use { a ->
            FakeStunServer("9.9.9.9", 40000).use { b ->
                val report = Diagnostics.probe(listOf(server(a.port), server(b.port)), timeoutMs = 2000)
                assertEquals(Diagnostics.NAT_ENDPOINT_INDEPENDENT, report.natType)
                assertTrue(report.holePunchFriendly)
                assertEquals("9.9.9.9", report.srflxIp)
                assertEquals(40000, report.srflxPort)
                assertEquals(2, report.probes.size)
                // JSON 结构化输出可解析
                val json = Diagnostics.toJson(report)
                assertTrue(json.contains("\"nat_type\":\"endpoint-independent\""))
                assertTrue(json.contains("\"hole_punch_friendly\":true"))
            }
        }
    }

    @Test
    fun `port varying mapping judges port dependent nat`() {
        FakeStunServer("9.9.9.9", 40000).use { a ->
            FakeStunServer("9.9.9.9", 51201).use { b ->
                val report = Diagnostics.probe(listOf(server(a.port), server(b.port)), timeoutMs = 2000)
                assertEquals(Diagnostics.NAT_PORT_DEPENDENT, report.natType)
                assertFalse(report.holePunchFriendly)
            }
        }
    }

    @Test
    fun `all stun unreachable reports unknown`() {
        val dead1 = DatagramSocket().let { s -> val p = s.localPort; s.close(); p }
        val dead2 = DatagramSocket().let { s -> val p = s.localPort; s.close(); p }
        val report = Diagnostics.probe(listOf(server(dead1), server(dead2)), timeoutMs = 200)
        assertEquals(Diagnostics.NAT_UNKNOWN, report.natType)
        assertFalse(report.holePunchFriendly)
    }

    // ── P2pBackend（环回打洞）──

    @Test
    fun `p2p punch establishes and carries video over udp direct`() {
        val tcpPort = java.net.ServerSocket(0).let { s -> val p = s.localPort; s.close(); p }
        FakeStunServer().use { fakeStun -> // echo 模式：SRFLX = 真实环回地址
            val source = P2pBackend(
                TransportConfig(Role.SOURCE, BackendKind.P2P, "127.0.0.1", tcpPort, 0),
                stunServers = listOf(server(fakeStun.port)),
                punchTimeoutMs = 5000,
            )
            val client = P2pBackend(
                TransportConfig(Role.CLIENT, BackendKind.P2P, "127.0.0.1", tcpPort, 0),
                stunServers = listOf(server(fakeStun.port)),
                punchTimeoutMs = 5000,
            )
            val srcEvents = LinkedBlockingQueue<StreamEvent>()
            val cliEvents = LinkedBlockingQueue<StreamEvent>()
            val srcVideo = LinkedBlockingQueue<Framing.VideoFrame>()
            source.onStreamEvent = StreamEventListener { e, _ -> srcEvents.put(e) }
            client.onStreamEvent = StreamEventListener { e, _ -> cliEvents.put(e) }
            source.onVideo = VideoSink { srcVideo.put(it) }

            source.start() // 信令监听绑定 + SRFLX 发现（echo）
            client.start() // 信令连接 + SRFLX 发现
            try {
                assertNotNull(source.localSrflx)
                assertNotNull(client.localSrflx)
                // 信令交换（offer/answer → 双端 onPeerSrflx 启动打洞）
                source.onPeerSrflx(client.localSrflx!!)
                client.onPeerSrflx(source.localSrflx!!)
                // 打洞建立事件（探针 200ms 间隔，环回应秒通）
                // 打洞建立事件（探针 200ms 间隔，环回应秒通；先到的 CONNECTED 跳过）
                fun drainPunch(q: LinkedBlockingQueue<StreamEvent>, who: String) {
                    var e = q.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("$who 打洞未建立")
                    var guard = 0
                    while (e != StreamEvent.PUNCH_ESTABLISHED) {
                        if (++guard > 10) throw AssertionError("$who 事件流异常: $e")
                        e = q.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("$who 打洞未建立")
                    }
                }
                drainPunch(srcEvents, "源端")
                drainPunch(cliEvents, "客户端")
                assertEquals(TransportKind.P2P, client.wireKind)
                // 直连通道承载视频
                val data = ByteArray(2500) { (it % 31).toByte() }
                client.sendVideo(sequence = 100, ptsUs = 777_777, keyframe = true, frame = data)
                val frame = srcVideo.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("直连视频未到达")
                assertEquals(100L, frame.sequence)
                assertEquals(777_777L, frame.ptsUs)
                assertTrue(data.contentEquals(frame.data))
                assertEquals(TransportKind.P2P, source.wireKind)
            } finally {
                client.close(); source.close()
            }
        }
    }

    @Test
    fun `punch timeout falls back to frp tcp and session keeps working`() {
        val tcpPort = java.net.ServerSocket(0).let { s -> val p = s.localPort; s.close(); p }
        // 无 STUN 可达（模拟国内被滤/断网场景）→ 直接具备回落形态
        val deadStun = DatagramSocket().let { s -> val p = s.localPort; s.close(); p }
        val source = P2pBackend(
            TransportConfig(Role.SOURCE, BackendKind.P2P, "127.0.0.1", tcpPort, 0),
            stunServers = listOf(server(deadStun)),
            punchTimeoutMs = 500,
        )
        val client = P2pBackend(
            TransportConfig(Role.CLIENT, BackendKind.P2P, "127.0.0.1", tcpPort, 0),
            stunServers = listOf(server(deadStun)),
            punchTimeoutMs = 500,
        )
        val cliVideo = LinkedBlockingQueue<Framing.VideoFrame>()
        client.onVideo = VideoSink { cliVideo.put(it) }
        source.start()
        client.start()
        try {
            assertEquals(null, source.localSrflx) // STUN 不可达，无 SRFLX
            assertEquals(TransportKind.P2P_FALLBACK_FRP, client.wireKind)
            // 回落形态下会话不中断：视频走信令 TCP 复用完整可达
            val data = ByteArray(1800) { (it % 17).toByte() }
            source.sendVideo(sequence = 3, ptsUs = 42, keyframe = true, frame = data)
            val frame = cliVideo.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("回落路径视频未到达")
            assertEquals(3L, frame.sequence)
            assertTrue(data.contentEquals(frame.data))
            assertTrue(
                "回落事件应被记录",
                client.snapshot().badEvents.containsKey(TransportStats.BadEvent.PUNCH_FALLBACK_FRP),
            )
        } finally {
            client.close(); source.close()
        }
    }
}
