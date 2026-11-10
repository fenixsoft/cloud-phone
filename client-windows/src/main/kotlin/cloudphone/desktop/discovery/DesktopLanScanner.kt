package cloudphone.desktop.discovery

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 局域网源端自动发现（Android LanScanner 桌面移植，client-ui-redesign 1.2 语义）：
 * 从本机 NetworkInterface 的非回环 IPv4 推导所在 /24 网段（取不到时回退 192.168/10. 常见网段），
 * 并发 TCP connect 探测候选地址的源端默认端口（连接握手即判服务存在，不完成 A2A
 * 握手，避免误触发源端会话状态）。
 *
 * 约束（spec「局域网源端自动发现」）：
 * - 后台线程执行，回调式 API，不阻塞 UI；
 * - 单地址探测超时 300ms、并发 64、总时长上限 10s（超时未探完即收尾）；
 * - 结果按 host+port 去重，按 IP 数值序返回；
 * - 探测结果不写档案（避免误报污染档案库），仅供首页列表呈现（入库是 DesktopSourceProfiles 的职责）。
 */
class DesktopLanScanner(
    /** 探测端口（首页扫描偏好；缺省 22333） */
    private val port: Int = DEFAULT_PORT,
    /** 测试注入：网段前缀覆盖（null=按本机网卡推导） */
    private val subnetPrefixesOverride: List<String>? = null,
    /** 测试注入：进度节流间隔（默认 100ms = ≤10Hz） */
    private val progressIntervalMs: Long = 100,
) {

    /** 单个发现的源端（host+port 唯一键） */
    data class DiscoveredHost(val host: String, val port: Int) {
        val key: String get() = "$host:$port"
    }

    private val executor: ExecutorService = Executors.newFixedThreadPool(CONCURRENCY) { r ->
        Thread(r, "lan-scan").apply { isDaemon = true }
    }

    /** 扫描代次：新一轮扫描 +1，老代次的探测任务直接作废跳过 */
    private val generation = AtomicInteger(0)

    /**
     * 发起一次扫描（同一实例可串行复用：旧代次探测任务按代次作废）。
     * - [onProgress]：探测进度（已探测数/总数），节流回调（默认 ≤10Hz）；
     * - [onHostFound]：每发现一个源端回调一次（顺序不保证）；
     * - [onComplete]：本轮扫描收尾（探完/总时长超 10s），去重排序后的发现列表 + 耗时 ms。
     */
    fun scan(
        onProgress: (probed: Int, total: Int) -> Unit,
        onHostFound: (DiscoveredHost) -> Unit,
        onComplete: (hosts: List<DiscoveredHost>, elapsedMs: Long) -> Unit,
    ) {
        val gen = generation.incrementAndGet()
        val addresses = candidateAddresses()
        val startedAtMs = System.currentTimeMillis()
        if (addresses.isEmpty()) {
            onComplete(emptyList(), 0)
            return
        }
        val found = Collections.synchronizedList(ArrayList<DiscoveredHost>())
        val seen = Collections.synchronizedSet(HashSet<String>())
        val probed = AtomicInteger(0)
        val total = addresses.size
        val deadlineMs = startedAtMs + TOTAL_TIMEOUT_MS
        val lastProgressAtMs = AtomicLong(0)
        for (addr in addresses) {
            executor.submit {
                if (gen != generation.get()) return@submit // 已被新一轮扫描作废
                val now = System.currentTimeMillis()
                if (now <= deadlineMs && probeTcp(addr, port, CONNECT_TIMEOUT_MS)) {
                    val hit = DiscoveredHost(addr, port)
                    if (seen.add(hit.key)) {
                        found.add(hit)
                        onHostFound(hit)
                    }
                }
                val done = probed.incrementAndGet()
                if (gen != generation.get()) return@submit
                if (done >= total) {
                    val deduped = found.distinctBy { it.key }.sortedBy { ipSortKey(it.host) }
                    onComplete(deduped, System.currentTimeMillis() - startedAtMs)
                } else {
                    val last = lastProgressAtMs.get()
                    if (now - last >= progressIntervalMs && lastProgressAtMs.compareAndSet(last, now)) {
                        onProgress(done, total)
                    }
                }
            }
        }
    }

    /** 逻辑作废当前扫描（新一轮 scan 会自动作废旧代次；离开首页时调用防泄漏回调） */
    fun cancel() {
        generation.incrementAndGet()
    }

    /** TCP connect 探测：连接成功即判源端服务存在（不读写 payload） */
    private fun probeTcp(host: String, port: Int, timeoutMs: Int): Boolean =
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }

    /**
     * 候选地址集：
     * ① 测试注入网段（subnetPrefixesOverride）；
     * ② NetworkInterface 非回环站点内 IPv4 → 所在 /24；
     * ③ 再降级常见网段 192.168.1/192.168.0/10.0.0 的 /24。
     * 输出形如 "192.168.1.23"。
     */
    fun candidateAddresses(): List<String> {
        val base = subnetPrefixesOverride ?: deriveSubnets().ifEmpty { listOf("192.168.1", "192.168.0", "10.0.0") }
        val out = ArrayList<String>(base.size * 254)
        for (prefix in base.distinct()) {
            for (i in 1..254) out.add("$prefix.$i")
        }
        return out
    }

    /** 展示用网段标签（如 "192.168.1.0/24"，供首页网络状态徽章） */
    fun subnetLabels(): List<String> =
        (subnetPrefixesOverride ?: deriveSubnets().ifEmpty { listOf("192.168.1", "192.168.0", "10.0.0") })
            .map { "$it.0/24" }

    /** 推导本机所在 /24 的前三段（如 "192.168.1"），可能多网卡多段 */
    private fun deriveSubnets(): List<String> {
        val out = LinkedHashSet<String>()
        try {
            val e = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (e.hasMoreElements()) {
                val nif = e.nextElement()
                nif.inetAddresses.toList().forEach { a ->
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                        subnetPrefix(a.hostAddress ?: "")?.let { out.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.toList()
    }

    companion object {
        const val DEFAULT_PORT = 22333
        const val CONNECT_TIMEOUT_MS = 300
        const val CONCURRENCY = 64
        const val TOTAL_TIMEOUT_MS = 10_000L

        /** "192.168.1.23" → "192.168.1"（取前三段；非四段格式返回 null） */
        internal fun subnetPrefix(ip: String): String? {
            val parts = ip.split(".")
            if (parts.size != 4) return null
            return "${parts[0]}.${parts[1]}.${parts[2]}"
        }

        /** IP 数值排序键（字符串序会把 .10 排在 .2 前） */
        internal fun ipSortKey(host: String): Long =
            host.split(".").fold(0L) { acc, p -> (acc shl 8) or (p.toLongOrNull() ?: 0L) }
    }
}
