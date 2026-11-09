package cloudphone.transport

import java.net.DatagramSocket

/**
 * 直连诊断：NAT 类型判定（移植 scripts/nat_check.py 同款多节点同 socket 探测）
 * + 端口分配特征 + 结构化 JSON 输出。
 *
 * 判定法（与 nat_check.py 一致）：
 * - 同一本地 socket 向 ≥2 个 STUN 节点发 Binding Request：
 *   - 全部返回相同 (ip, port) 映射 → endpoint-independent（cone，打洞友好）
 *   - 相同 ip 不同 port → port-dependent（对称端口型，打洞困难）
 *   - 不同 ip → address-dependent（对称地址型，打洞困难）
 * - 第二个本地 socket 向首个可达节点再探测：端口差 delta 判定分配可预测性
 *   （delta=0 端口保持 / ≤4 可预测 / 其余随机），供对端预判打洞目标端口。
 * - 节点全部不可达 → unknown（保守不判定）。
 */
object Diagnostics {

    const val NAT_ENDPOINT_INDEPENDENT = "endpoint-independent"
    const val NAT_ADDRESS_DEPENDENT = "address-dependent"
    const val NAT_PORT_DEPENDENT = "port-dependent"
    const val NAT_UNKNOWN = "unknown"

    data class Probe(
        val server: String,
        val mappedIp: String,
        val mappedPort: Int,
        val rttMs: Double,
    )

    data class NatReport(
        val natType: String,
        val srflxIp: String?,
        val srflxPort: Int?,
        val portAllocation: String,
        val holePunchFriendly: Boolean,
        val probes: List<Probe>,
        val probedAtMs: Long,
    )

    /** 执行完整探测并产出判定 */
    fun probe(
        servers: List<StunClient.StunServer> = StunClient.DEFAULT_SERVERS,
        timeoutMs: Int = 3000,
    ): NatReport {
        val now = System.currentTimeMillis()
        val socketA = DatagramSocket()
        val probes = ArrayList<Probe>()
        try {
            for (server in servers) {
                StunClient.query(socketA, server, timeoutMs)?.let {
                    probes.add(Probe("${server.host}:${server.port}", it.mappedIp, it.mappedPort, it.rttMs))
                }
            }
            if (probes.isEmpty()) {
                return NatReport(NAT_UNKNOWN, null, null, "unknown", false, emptyList(), now)
            }
            val distinctMappings = probes.map { it.mappedIp to it.mappedPort }.toSet()
            val sameIp = probes.map { it.mappedIp }.toSet().size == 1
            val (natType, friendly) = when {
                distinctMappings.size == 1 -> NAT_ENDPOINT_INDEPENDENT to true
                sameIp -> NAT_PORT_DEPENDENT to false
                else -> NAT_ADDRESS_DEPENDENT to false
            }
            val srflx = probes.first()
            // 第二 socket 端口分配特征（端口差 = 分配可预测性）
            val allocation = portAllocationProbe(servers, socketA.localPort, timeoutMs, srflx.mappedPort)
            return NatReport(
                natType = natType,
                srflxIp = srflx.mappedIp,
                srflxPort = srflx.mappedPort,
                portAllocation = allocation,
                holePunchFriendly = friendly,
                probes = probes,
                probedAtMs = now,
            )
        } finally {
            socketA.close()
        }
    }

    private fun portAllocationProbe(
        servers: List<StunClient.StunServer>,
        firstLocalPort: Int,
        timeoutMs: Int,
        firstMappedPort: Int,
    ): String {
        val socketB = try {
            DatagramSocket()
        } catch (_: Exception) {
            return "unknown"
        }
        return try {
            val target = servers.firstOrNull { s -> StunClient.query(socketB, s, timeoutMs) != null }
                ?: return "unknown"
            val result = StunClient.query(socketB, target, timeoutMs) ?: return "unknown"
            val delta = Math.abs(result.mappedPort - firstMappedPort)
            when {
                delta == 0 -> "port-preserving"
                delta <= 4 -> "predictable"
                else -> "unpredictable"
            }
        } finally {
            socketB.close()
        }
    }

    /** 判定结构 → JSON（无第三方依赖，手写转义） */
    fun toJson(report: NatReport): String {
        val sb = StringBuilder(256)
        sb.append('{')
        sb.append("\"nat_type\":\"").append(escape(report.natType)).append("\",")
        sb.append("\"srflx_ip\":").append(report.srflxIp?.let { "\"${escape(it)}\"" } ?: "null").append(',')
        sb.append("\"srflx_port\":").append(report.srflxPort ?: "null").append(',')
        sb.append("\"port_allocation\":\"").append(escape(report.portAllocation)).append("\",")
        sb.append("\"hole_punch_friendly\":").append(report.holePunchFriendly).append(',')
        sb.append("\"probes\":[")
        report.probes.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("{\"server\":\"").append(escape(p.server)).append("\",")
            sb.append("\"mapped_ip\":\"").append(escape(p.mappedIp)).append("\",")
            sb.append("\"mapped_port\":").append(p.mappedPort).append(',')
            sb.append("\"rtt_ms\":").append(p.rttMs).append('}')
        }
        sb.append("],")
        sb.append("\"probed_at_ms\":").append(report.probedAtMs)
        sb.append('}')
        return sb.toString()
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    // ── 打洞分级耗时（p2p-crossnet-traversal 2.1：每级独立耗时统计进 Diagnostics）──

    /** 打洞链阶段常量（D2：v6 直连 → 多候选 → 端口预测 → FRP 兜底 → 端点迁移） */
    object PunchStages {
        const val SRFLX_DISCOVERY = "srflx_discovery"   // STUN 多节点 SRFLX 发现
        const val LOCAL_CANDIDATES = "local_candidates" // 本地多网卡候选枚举
        const val PEER_ANALYSIS = "peer_analysis"       // 对端候选解析 + symmetric 判定
        const val V6_DIRECT = "v6_direct"               // IPv6 直连优先尝试
        const val SRFLX_PUNCH = "srflx_punch"           // 多候选并行 out-probe 打洞
        const val PORT_PREDICTION = "port_prediction"   // symmetric 端口预测窗口批量探测
        const val MIGRATION = "endpoint_migration"      // 端点迁移地址更新
        const val FALLBACK = "fallback_frp"             // 全部失败回落 FRP（degraded）
    }

    /** 打洞分级计时：阶段 → 距链路启动的毫秒数 + 详注（线程安全，阶段后写覆盖前值） */
    class PunchTrace {
        data class StageRecord(val stage: String, val elapsedMs: Long, val detail: String)

        private val records = LinkedHashMap<String, StageRecord>()
        private val t0 = System.nanoTime()

        @Synchronized
        fun record(stage: String, detail: String = "") {
            records[stage] = StageRecord(stage, (System.nanoTime() - t0) / 1_000_000, detail)
        }

        @Synchronized
        fun snapshot(): List<StageRecord> = records.values.toList()

        @Synchronized
        fun toJson(): String {
            val sb = StringBuilder(128)
            sb.append("{\"stages\":[")
            records.values.forEachIndexed { i, r ->
                if (i > 0) sb.append(',')
                sb.append("{\"stage\":\"").append(escape(r.stage)).append("\",")
                sb.append("\"elapsed_ms\":").append(r.elapsedMs).append(',')
                sb.append("\"detail\":\"").append(escape(r.detail)).append("\"}")
            }
            sb.append("]}")
            return sb.toString()
        }
    }
}
