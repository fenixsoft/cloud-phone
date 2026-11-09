package cloudphone.transport

import org.junit.Assert.assertNotNull
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * natlab 联调发现（p2p-crossnet-traversal 4.2）：打洞 socket 与 STUN 查询共用时，
 * 探针/视频帧与 Binding 应答混流 —— query 必须跳过非 STUN 报文继续收，直到本查询
 * 的应答或超时（生产环境网络切换检测 refreshLocalCandidates 依赖此路径）。
 */
class StunMixTest {

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

    @Test
    fun `query survives mixed garbage on same socket`() {
        val server = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val running = AtomicBoolean(true)
        Thread {
            val buf = ByteArray(2048)
            while (running.get()) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    server.receive(p)
                    if (p.length < 20) continue
                    if (((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF) != 0x0001) continue
                    val tid = buf.copyOfRange(8, 20)
                    server.send(DatagramPacket(bindingResponse(tid, p.address.address, p.port), 32, p.socketAddress))
                } catch (_: Exception) {
                    if (!running.get()) return@Thread
                }
            }
        }.apply { isDaemon = true; start() }

        // 被测 socket：模拟打洞 socket 混流——垃圾探针包持续涌入同一 socket
        val client = DatagramSocket()
        val garbage = DatagramSocket()
        Thread {
            val junk = ByteArray(30) { (it % 251).toByte() }
            var n = 0
            while (n < 400) {
                try {
                    garbage.send(DatagramPacket(junk, junk.size, InetSocketAddress("127.0.0.1", client.localPort)))
                } catch (_: Exception) {
                }
                Thread.sleep(2)
                n++
            }
        }.apply { isDaemon = true; start() }

        val mapped = StunClient.query(
            client, StunClient.StunServer("127.0.0.1", server.localPort), 3000, retries = 0,
        )
        running.set(false)
        server.close(); client.close(); garbage.close()
        assertNotNull("query 应穿透混流垃圾包拿到 STUN 应答", mapped)
    }
}
