package cloudphone.transport

import cloudphone.protocol.Framing
import cloudphone.protocol.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 打洞增强链测试（p2p-crossnet-traversal 2.1/2.2/2.3）：
 * - 多候选文本格式往返 + symmetric 标记（2.1）
 * - 端口预测差分外推（2.2，单元）
 * - symmetric 对端预测窗口装配 + 打洞仍建立（2.2，集成：端口自增假 STUN）
 * - 多候选含诱饵地址仍经可达候选打通（2.1，集成）
 * - 端点迁移：候选更新 → PUNCH_MIGRATED → 重建直连，会话与统计不重置（2.3，集成）
 */
class PunchEnhanceTest {

    /** 假 STUN：Binding 应答映射端口自增（模拟 symmetric 端口分配）或取固定值；base 错开多节点 */
    private class FakeStunServer(private val incrementPorts: Boolean, base: Int = 30000) : Closeable {
        private val socket = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val port: Int = socket.localPort
        private val counter = java.util.concurrent.atomic.AtomicInteger(base)
        @Volatile private var running = true
        private val thread = Thread {
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    if (p.length < 20) continue
                    if (((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF) != 0x0001) continue
                    val tid = buf.copyOfRange(8, 20)
                    val mappedPort = if (incrementPorts) counter.incrementAndGet() else 40000
                    socket.send(
                        DatagramPacket(
                            bindingResponse(tid, p.address.address, mappedPort), 32, p.socketAddress,
                        ),
                    )
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


    // ── 2.1 多候选文本 ──

    @Test
    fun `candidate text format parse roundtrip`() {
        val addrs = listOf(
            InetSocketAddress("2001:db8::1", 40000),
            InetSocketAddress("1.2.3.4", 40001),
            InetSocketAddress("192.168.1.5", 41000),
        )
        val text = StunClient.formatCandidates(addrs, symmetric = true)
        assertTrue(text.contains(";nat=sym"))
        val parsed = StunClient.parseCandidates(text)!!
        assertEquals(addrs.map { it.port }, parsed.addresses.map { it.port })
        assertTrue(parsed.symmetric)
        // 无 symmetric 标记
        val plain = StunClient.parseCandidates(StunClient.formatCandidates(addrs, false))!!
        assertFalse(plain.symmetric)
        // 旧单候选字符串 = n=1 特例
        val legacy = StunClient.parseCandidates("9.9.9.9:1234")!!
        assertEquals(1, legacy.addresses.size)
        assertEquals(1234, legacy.addresses[0].port)
        assertFalse(legacy.symmetric)
        // 容错：空串/全坏项
        assertNull(StunClient.parseCandidates(""))
        assertNull(StunClient.parseCandidates("garbage,xxx"))
    }

    // ── 2.2 端口预测 ──

    @Test
    fun `port predictor extrapolates window from samples`() {
        // 均匀步长 2：外推基 = last+step，窗口按步长线性展开
        // 双向外推：前向（顺序分配型）+ 后向（池化型——映射落在样本附近池内），合计 ≤window
        val ports = PortPredictor.predict(listOf(40000, 40004, 40002), window = 5)
        assertEquals(listOf(40006, 40008, 40010, 40002, 40000), ports)
        // 默认窗口 ≤64（前向 32 + 后向 32）
        val big = PortPredictor.predict(listOf(40000, 40001))
        assertTrue(big.size <= PortPredictor.MAX_WINDOW)
        assertEquals(64, big.size)
        // 后向覆盖池下沿：小池采样（40001,40002）→ 后向探到 40000（池内可命中）
        assertTrue(PortPredictor.predict(listOf(40001, 40002), window = 4).contains(40000))
        // 步长 0（端口保持型误判为 sym）：步长钳制 ≥1（前向 2 + 后向 1）
        val flat = PortPredictor.predict(listOf(40000, 40000, 40000), window = 3)
        assertEquals(listOf(40001, 40002, 39999), flat)
        // 采样不足：不启用
        assertTrue(PortPredictor.predict(listOf(40000)).isEmpty())
        assertTrue(PortPredictor.predict(emptyList()).isEmpty())
    }

    @Test
    fun `symmetric mapping detection across servers`() {
        // 映射 (ip, port) 随目标变化 = symmetric（打洞困难）：
        // 同 ip 不同 port → port-dependent
        val portDependent = listOf(
            StunClient.SrflxResult(StunClient.StunServer("1.1.1.1", 3478), "1.1.1.1", 40001, 1.0),
            StunClient.SrflxResult(StunClient.StunServer("1.1.1.1", 3478), "1.1.1.1", 40009, 2.0),
        )
        assertTrue(StunClient.isSymmetricMapping(portDependent))
        // 不同 ip（无论端口同异）→ address-dependent（洞按目标隔离，同样打洞困难）
        val addrDependent = listOf(
            StunClient.SrflxResult(StunClient.StunServer("1.1.1.1", 3478), "1.1.1.1", 40001, 1.0),
            StunClient.SrflxResult(StunClient.StunServer("2.2.2.2", 3478), "2.2.2.2", 40009, 2.0),
        )
        assertTrue(StunClient.isSymmetricMapping(addrDependent))
        val addrDependentSamePort = listOf(
            StunClient.SrflxResult(StunClient.StunServer("1.1.1.1", 3478), "1.1.1.1", 40001, 1.0),
            StunClient.SrflxResult(StunClient.StunServer("2.2.2.2", 3478), "2.2.2.2", 40001, 2.0),
        )
        assertTrue(StunClient.isSymmetricMapping(addrDependentSamePort))
        // 全部一致 = endpoint-independent（false）
        val ei = listOf(
            StunClient.SrflxResult(StunClient.StunServer("1.1.1.1", 3478), "9.9.9.9", 40001, 1.0),
            StunClient.SrflxResult(StunClient.StunServer("2.2.2.2", 3478), "9.9.9.9", 40001, 2.0),
        )
        assertFalse(StunClient.isSymmetricMapping(ei))
    }

    // ── 集成：symmetric 对端预测装配 + 多候选诱饵 + 迁移 ──

    private class PunchPair(incrementPorts: Boolean) : Closeable {
        val stuns = listOf(FakeStunServer(incrementPorts, base = 30000), FakeStunServer(incrementPorts, base = 31000))
        val tcpPort = freePort()
        val source = P2pBackend(
            TransportConfig(Role.SOURCE, BackendKind.P2P, "127.0.0.1", tcpPort, 0),
            stunServers = stuns.map { StunClient.StunServer("127.0.0.1", it.port) },
            punchTimeoutMs = 5000,
        )
        val client = P2pBackend(
            TransportConfig(Role.CLIENT, BackendKind.P2P, "127.0.0.1", tcpPort, 0),
            stunServers = stuns.map { StunClient.StunServer("127.0.0.1", it.port) },
            punchTimeoutMs = 5000,
        )
        val srcEvents = LinkedBlockingQueue<StreamEvent>()
        val cliEvents = LinkedBlockingQueue<StreamEvent>()

        init {
            source.onStreamEvent = StreamEventListener { e, _ -> srcEvents.put(e) }
            client.onStreamEvent = StreamEventListener { e, _ -> cliEvents.put(e) }
            source.start()
            client.start()
            // 信令交换：候选文本（多候选 + symmetric 标记随实际判定产生）
            source.onPeerCandidates(client.localCandidatesText())
            client.onPeerCandidates(source.localCandidatesText())
        }

        fun awaitEvent(q: LinkedBlockingQueue<StreamEvent>, want: StreamEvent, who: String) {
            var guard = 0
            var e = q.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("$who: no event for $want")
            while (e != want) {
                if (++guard > 20) throw AssertionError("$who: event $want never arrived (stuck at $e)")
                e = q.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("$who: no event for $want")
            }
        }

        override fun close() {
            client.close()
            source.close()
            stuns.forEach { it.close() }
        }
    }

    @Test
    fun `punch establishes with prediction armed and decoy candidates`() {
        PunchPair(incrementPorts = true).use { pair ->
            // 端口自增假 STUN：同 socket 多节点映射端口互异 → 本端判 symmetric → offer 带 nat=sym
            assertTrue(pair.client.localNatSymmetric)
            assertTrue(pair.client.localCandidatesText().contains(";nat=sym"))
            // 诱饵候选混入可达候选：out-probe 全目标轮询仍打通
            pair.awaitEvent(pair.srcEvents, StreamEvent.PUNCH_ESTABLISHED, "source")
            pair.awaitEvent(pair.cliEvents, StreamEvent.PUNCH_ESTABLISHED, "client")
            assertEquals(TransportKind.P2P, pair.client.wireKind)
            // 预测窗口已装配（symmetric 对端 + 开关默认开）
            val stages = pair.client.punchTrace.snapshot().map { it.stage }
            assertTrue("prediction stage missing: $stages", Diagnostics.PunchStages.PORT_PREDICTION in stages)
            // 视频经 UDP 直连
            val srcVideo = LinkedBlockingQueue<Framing.VideoFrame>()
            pair.source.onVideo = VideoSink { srcVideo.put(it) }
            val data = ByteArray(1500) { it.toByte() }
            pair.client.sendVideo(50, 5, false, data)
            val f = srcVideo.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("video not received")
            assertEquals(50L, f.sequence)
        }
    }

    @Test
    fun `endpoint migration updates targets and re-establishes without reset`() {
        PunchPair(incrementPorts = false).use { pair ->
            pair.awaitEvent(pair.srcEvents, StreamEvent.PUNCH_ESTABLISHED, "source")
            pair.awaitEvent(pair.cliEvents, StreamEvent.PUNCH_ESTABLISHED, "client")
            val statsBefore = pair.client.stats.snapshot()

            // 双端各发一次迁移（对端各自 onPeerCandidates 二次进入 → migrateTo）
            pair.client.onPeerCandidates(pair.source.localCandidatesText())
            pair.source.onPeerCandidates(pair.client.localCandidatesText())
            pair.awaitEvent(pair.cliEvents, StreamEvent.PUNCH_MIGRATED, "client")
            pair.awaitEvent(pair.srcEvents, StreamEvent.PUNCH_MIGRATED, "source")
            // 直连重建（探针循环恢复）
            pair.awaitEvent(pair.cliEvents, StreamEvent.PUNCH_ESTABLISHED, "client")
            pair.awaitEvent(pair.srcEvents, StreamEvent.PUNCH_ESTABLISHED, "source")

            // 迁移轨迹 + 统计未重置（断流/重连计数不因迁移产生）
            val stages = pair.client.punchTrace.snapshot().map { it.stage }
            assertTrue(Diagnostics.PunchStages.MIGRATION in stages)
            val statsAfter = pair.client.stats.snapshot()
            assertEquals(statsBefore.framesSent, statsAfter.framesSent)

            // 迁移后视频仍通
            val srcVideo = LinkedBlockingQueue<Framing.VideoFrame>()
            pair.source.onVideo = VideoSink { srcVideo.put(it) }
            pair.client.sendVideo(60, 6, true, ByteArray(800))
            assertNotNull(srcVideo.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `refresh local candidates returns null when mapping unchanged`() {
        PunchPair(incrementPorts = false).use { pair ->
            // 环回无 NAT：重发现映射不变 → null（不产生迁移消息）
            assertNull(pair.client.refreshLocalCandidates())
        }
    }
}

private fun freePort(): Int = java.net.ServerSocket(0).let { s -> s.localPort.also { s.close() } }
