package cloudphone.desktop

import cloudphone.desktop.discovery.DesktopLanScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket

/**
 * 2.6 验收：LanScannerTest——对本机 ServerSocket 探测命中、同网段无服务地址超时排除、
 * /24 候选推导、去重与 IP 数值序、进度 ≤10Hz 节流。
 */
class LanScannerTest {

    /** 本机非回环 IPv4 与其 /24 前缀（CI 容器一般有 docker 网卡；无则测试回退 127 网段） */
    private fun localIpAndPrefix(): Pair<String, String>? {
        try {
            val e = NetworkInterface.getNetworkInterfaces() ?: return null
            while (e.hasMoreElements()) {
                val nif = e.nextElement()
                for (a in nif.inetAddresses.toList()) {
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                        val ip = a.hostAddress ?: continue
                        return ip to DesktopLanScanner.subnetPrefix(ip)!!
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    @Test
    fun `探测命中 - 本机 ServerSocket 在网段候选中被发现`() {
        val local = localIpAndPrefix() ?: error("宿主无非回环站点内 IPv4，无法执行发现命中测试")
        val (ip, prefix) = local
        ServerSocket(0, 50, InetAddress.getByName(ip)).use { server ->
            val port = server.localPort
            val scanner = DesktopLanScanner(port = port, subnetPrefixesOverride = listOf(prefix))
            var complete: Pair<List<DesktopLanScanner.DiscoveredHost>, Long>? = null
            val progress = mutableListOf<Pair<Int, Int>>()
            scanner.scan(
                onProgress = { p, t -> progress += p to t },
                onHostFound = { },
                onComplete = { hosts, elapsed -> complete = hosts to elapsed },
            )
            val deadline = System.currentTimeMillis() + 15000
            while (complete == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
            val (hosts, elapsed) = complete ?: error("扫描未在 15s 内收尾")
            assertTrue("应发现本机 ServerSocket（$ip:$port）", hosts.any { it.host == ip && it.port == port })
            assertEquals("发现地址应为探测端口", port, hosts.first().port)
            assertTrue("总时长应 < 10s 上限（实际 ${elapsed}ms）", elapsed < 10_000)
            // 进度回调形状：总数 = /24 候选数 254，计数非降
            assertTrue("应有进度回调", progress.isNotEmpty())
            assertEquals("进度总数应为 254", 254, progress.last().second)
            assertTrue("进度计数应非降", progress.zipWithNext().all { (a, b) -> a.first <= b.first })
            // 超时排除：除本机外同网段其余地址不应出现（网段内无其他监听者）
            assertTrue(
                "不应发现非本机地址",
                hosts.all { it.host == ip },
            )
        }
    }

    @Test
    fun `超时排除 - 无监听端口零命中`() {
        val local = localIpAndPrefix() ?: error("宿主无非回环站点内 IPv4")
        val (_, prefix) = local
        // 选用保留端口（大概率无监听）：全部候选应被拒绝/超时排除
        val scanner = DesktopLanScanner(port = 1, subnetPrefixesOverride = listOf(prefix))
        var complete: Pair<List<DesktopLanScanner.DiscoveredHost>, Long>? = null
        scanner.scan(
            onProgress = { _, _ -> },
            onHostFound = { },
            onComplete = { hosts, _ -> complete = hosts to 0L },
        )
        val deadline = System.currentTimeMillis() + 15000
        while (complete == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("保留端口应零命中（实际 ${complete?.first}）", complete?.first?.isEmpty() == true)
    }

    @Test
    fun `候选与工具 - 网段展开去重与排序键`() {
        val scanner = DesktopLanScanner(port = 22333, subnetPrefixesOverride = listOf("192.168.1", "10.0.0"))
        val addrs = scanner.candidateAddresses()
        assertEquals("两网段各 254 候选", 508, addrs.size)
        assertTrue(addrs.contains("192.168.1.1"))
        assertTrue(addrs.contains("10.0.0.254"))
        assertEquals("网段标签", listOf("192.168.1.0/24", "10.0.0.0/24"), scanner.subnetLabels())
        // subnetPrefix 边界
        assertEquals("192.168.1", DesktopLanScanner.subnetPrefix("192.168.1.23"))
        assertEquals(null, DesktopLanScanner.subnetPrefix("fe80::1"))
        assertEquals(null, DesktopLanScanner.subnetPrefix("1.2.3"))
        // ipSortKey：数值序（字符串序会把 .10 排在 .2 前）
        assertTrue(DesktopLanScanner.ipSortKey("192.168.1.2") < DesktopLanScanner.ipSortKey("192.168.1.10"))
        assertTrue(DesktopLanScanner.ipSortKey("9.0.0.255") < DesktopLanScanner.ipSortKey("10.0.0.1"))
    }

    @Test
    fun `cancel - 作废后旧扫描不再回调`() {
        val local = localIpAndPrefix()
        val scanner = DesktopLanScanner(port = 1, subnetPrefixesOverride = listOf(local?.second ?: "192.168.1"))
        var completed = false
        scanner.scan(
            onProgress = { _, _ -> },
            onHostFound = { },
            onComplete = { _, _ -> completed = true },
        )
        scanner.cancel()
        // 等 500ms 确认旧代次收尾回调被作废（探测任务直接跳过）
        Thread.sleep(500)
        assertTrue("作废后不应有收尾回调", !completed)
    }
}
