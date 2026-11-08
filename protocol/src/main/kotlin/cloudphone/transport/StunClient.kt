package cloudphone.transport

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * 最小 STUN（RFC 5389）客户端：Binding Request → XOR-MAPPED-ADDRESS/MAPPED-ADDRESS 解析。
 * 移植 scripts/nat_check.py 同款探测法：多节点**同一本地 socket** 查询（映射一致性判定 NAT 类型）。
 * 仅实现 M0 所需子集（无认证/无 OTHER-ADDRESS 行为探测）。
 */
object StunClient {

    data class StunServer(val host: String, val port: Int)

    data class SrflxResult(
        val server: StunServer,
        val mappedIp: String,
        val mappedPort: Int,
        val rttMs: Double,
    )

    const val MAGIC_COOKIE: Int = 0x2112A442
    private const val BINDING_REQUEST = 0x0001
    private const val BINDING_RESPONSE = 0x0101
    private const val ATTR_MAPPED_ADDRESS = 0x0001
    private const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
    private const val HEADER_BYTES = 20

    /** M0 内置公网 STUN 节点列表（国内 qq/miwifi 被滤，用海外三节点；可经配置覆盖） */
    val DEFAULT_SERVERS: List<StunServer> = listOf(
        StunServer("stun.cloudflare.com", 3478),
        StunServer("stun.twilio.com", 3478),
        StunServer("stun.nextcloud.com", 443),
    )

    /** 生成 Binding Request（无属性）：[u16 type][u16 len=0][u32 magic][12B tid] */
    fun bindingRequest(random: Random = Random.Default): ByteArray {
        val msg = ByteArray(HEADER_BYTES)
        msg[0] = (BINDING_REQUEST ushr 8).toByte()
        msg[1] = BINDING_REQUEST.toByte()
        // len = 0（msg[2..3] 保持 0）
        msg[4] = (MAGIC_COOKIE ushr 24).toByte()
        msg[5] = (MAGIC_COOKIE ushr 16).toByte()
        msg[6] = (MAGIC_COOKIE ushr 8).toByte()
        msg[7] = MAGIC_COOKIE.toByte()
        random.nextBytes(msg, 8, 20)
        return msg
    }

    /** 解析 Binding Response 中的 XOR-MAPPED-ADDRESS（优先）或 MAPPED-ADDRESS */
    fun parseMappedAddress(resp: ByteArray, tid: ByteArray): InetSocketAddress? {
        if (resp.size < HEADER_BYTES) return null
        val type = ((resp[0].toInt() and 0xFF) shl 8) or (resp[1].toInt() and 0xFF)
        if (type != BINDING_RESPONSE) return null
        // 校验 magic 与 transaction id
        val magic = ((resp[4].toInt() and 0xFF) shl 24) or ((resp[5].toInt() and 0xFF) shl 16) or
            ((resp[6].toInt() and 0xFF) shl 8) or (resp[7].toInt() and 0xFF)
        if (magic != MAGIC_COOKIE) return null
        for (i in 0 until 12) {
            if (resp[8 + i] != tid[i]) return null
        }
        var off = HEADER_BYTES
        while (off + 4 <= resp.size) {
            val atype = ((resp[off].toInt() and 0xFF) shl 8) or (resp[off + 1].toInt() and 0xFF)
            val alen = ((resp[off + 2].toInt() and 0xFF) shl 8) or (resp[off + 3].toInt() and 0xFF)
            val aval = off + 4
            if (aval + alen > resp.size) return null
            if (atype == ATTR_XOR_MAPPED_ADDRESS && alen == 8) {
                val port = ((resp[aval + 2].toInt() and 0xFF) shl 8) or (resp[aval + 3].toInt() and 0xFF)
                val xport = port xor (MAGIC_COOKIE ushr 16)
                val ip = ByteArray(4)
                for (k in 0 until 4) ip[k] = (resp[aval + 4 + k].toInt() xor (MAGIC_COOKIE ushr (24 - 8 * k))).toByte()
                return InetSocketAddress(InetAddress.getByAddress(ip), xport)
            }
            if (atype == ATTR_MAPPED_ADDRESS && alen >= 8 && resp[aval].toInt() == 0x01) {
                val port = ((resp[aval + 2].toInt() and 0xFF) shl 8) or (resp[aval + 3].toInt() and 0xFF)
                val ip = resp.copyOfRange(aval + 4, aval + 8)
                return InetSocketAddress(InetAddress.getByAddress(ip), port)
            }
            off += 4 + alen + ((4 - alen % 4) % 4) // 属性按 4 字节对齐
        }
        return null
    }

    /**
     * 在**给定 socket** 上查询一个 STUN 节点（复用同一 socket 供 NAT 类型判定）。
     * 超时/不可达返回 null（调用方可换下一节点）。
     */
    fun query(socket: DatagramSocket, server: StunServer, timeoutMs: Int = 3000, retries: Int = 1): SrflxResult? {
        val address = runCatching { InetAddress.getByName(server.host) }.getOrNull() ?: return null
        val target = InetSocketAddress(address, server.port)
        val tid = ByteArray(12)
        val request = bindingRequest()
        System.arraycopy(request, 8, tid, 0, 12)
        val buf = ByteArray(2048)
        val oldTimeout = socket.soTimeout
        try {
            socket.soTimeout = timeoutMs
            for (attempt in 0..retries) {
                val t0 = System.nanoTime()
                socket.send(DatagramPacket(request, request.size, target))
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val rtt = (System.nanoTime() - t0) / 1_000_000.0
                    val mapped = parseMappedAddress(buf.copyOf(packet.length), tid) ?: continue
                    return SrflxResult(server, mapped.address.hostAddress ?: continue, mapped.port, rtt)
                } catch (_: SocketTimeoutException) {
                    // 重试
                }
            }
            return null
        } catch (_: Exception) {
            // 网络不可达/DNS 失败等
            return null
        } finally {
            try {
                socket.soTimeout = oldTimeout
            } catch (_: Exception) {
            }
        }
    }

    /** 依次尝试节点列表，返回首个可达结果（SRFLX 发现） */
    fun discoverSrflx(socket: DatagramSocket, servers: List<StunServer> = DEFAULT_SERVERS): SrflxResult? {
        for (server in servers) {
            query(socket, server)?.let { return it }
        }
        return null
    }

    /** SRFLX 地址 ↔ 协议字符串（HELLO.p2pOffer / HELLO_ACK.p2pAnswer 的 "ip:port" 形态） */
    fun formatSrflx(address: InetSocketAddress): String = "${address.address.hostAddress}:${address.port}"

    fun parseSrflx(value: String): InetSocketAddress? {
        if (value.isBlank()) return null
        val idx = value.lastIndexOf(':')
        if (idx <= 0 || idx == value.length - 1) return null
        val port = value.substring(idx + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return runCatching { InetSocketAddress(value.substring(0, idx), port) }.getOrNull()
    }
}
