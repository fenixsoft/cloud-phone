package cloudphone.desktop

import cloudphone.desktop.profiles.DesktopSourceProfiles
import cloudphone.desktop.profiles.SourceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 2.7 验收：SourceProfilesTest——临时目录覆盖 host+tcpPort+backend 去重、
 * 连接成功自动入库、档位偏好持久化、token 不落日志。
 */
class SourceProfilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newProfiles(): DesktopSourceProfiles =
        DesktopSourceProfiles(file = tmp.root.resolve("profiles.json").toPath())

    private fun sample(
        host: String = "192.168.1.10",
        tcpPort: Int = 22333,
        backend: String = "lan-direct",
        token: String = "secret-token-123",
        atMs: Long = 1000,
        name: String = "",
    ) = SourceProfile(
        name = name,
        host = host,
        tcpPort = tcpPort,
        backend = backend,
        token = token,
        lastConnectedAt = atMs,
    )

    // ── upsert 去重（host+tcpPort+backend 键） ──

    @Test
    fun `upsert - 同键更新不产生重复`() {
        val p = newProfiles()
        p.save(sample(atMs = 1000))
        p.save(sample(atMs = 2000, name = "客厅手机"))
        assertEquals("同键应去重为 1 条", 1, p.count())
        val only = p.list().single()
        assertEquals("应取最新整条更新", 2000L, only.lastConnectedAt)
        assertEquals("客厅手机", only.name)
    }

    @Test
    fun `upsert - 不同键各自保留`() {
        val p = newProfiles()
        p.save(sample())
        p.save(sample(backend = "frp"))
        p.save(sample(tcpPort = 22334))
        assertEquals(3, p.count())
    }

    // ── 排序与查询 ──

    @Test
    fun `list - 按最近使用倒序`() {
        val p = newProfiles()
        p.save(sample(host = "10.0.0.2", atMs = 100))
        p.save(sample(host = "10.0.0.3", atMs = 300))
        p.save(sample(host = "10.0.0.4", atMs = 200))
        assertEquals(listOf("10.0.0.3", "10.0.0.4", "10.0.0.2"), p.list().map { it.host })
    }

    @Test
    fun `findByHostPort - 不限后端取最近使用`() {
        val p = newProfiles()
        p.save(sample(backend = "frp", atMs = 100))
        p.save(sample(backend = "lan-direct", atMs = 500))
        assertEquals("lan-direct", p.findByHostPort("192.168.1.10", 22333)?.backend)
        assertNull(p.findByHostPort("192.168.1.99", 22333))
    }

    // ── 删除 ──

    @Test
    fun `delete - 命中返回真且落盘，未命中返回假`() {
        val p = newProfiles()
        p.save(sample())
        assertTrue(p.delete("192.168.1.10", 22333, "lan-direct"))
        assertFalse(p.delete("192.168.1.10", 22333, "lan-direct"))
        assertEquals(0, p.count())
        // 新实例读取确认已删除（落盘生效）
        assertEquals(0, newProfiles().count())
    }

    // ── 连接成功自动入库 ──

    @Test
    fun `recordConnected - 自动入库带 auto 来源且保留偏好`() {
        val p = newProfiles()
        p.save(sample(name = "客厅手机", atMs = 100)) // 手输建档
        p.savePresetPreference("192.168.1.10", 22333, "lan-direct", "自定义")
        p.recordConnected(host = "192.168.1.10", tcpPort = 22333, udpPort = 22334, backend = "lan-direct", token = "secret-token-123")
        val prof = p.findByKey("192.168.1.10", 22333, "lan-direct")!!
        assertEquals("连接入库应标记 auto", SourceProfile.ORIGIN_AUTO, prof.origin)
        assertEquals("保留原名称", "客厅手机", prof.name)
        assertEquals("保留档位偏好", "自定义", prof.presetLabel)
        assertEquals("不产生重复档案", 1, p.count())
    }

    // ── 档位偏好持久化 ──

    @Test
    fun `档位偏好 - 跨实例持久化`() {
        val file = tmp.root.resolve("profiles.json").toPath()
        val p1 = DesktopSourceProfiles(file = file)
        p1.save(sample())
        assertTrue(p1.savePresetPreference("192.168.1.10", 22333, "lan-direct", "1080P·60"))
        val p2 = DesktopSourceProfiles(file = file)
        assertEquals("1080P·60", p2.findByKey("192.168.1.10", 22333, "lan-direct")?.presetLabel)
        assertFalse("不存在的档案无法更新偏好", p1.savePresetPreference("x", 1, "y", "p"))
    }

    // ── token 不落日志 ──

    @Test
    fun `token - toString 与 displayName 均不泄露`() {
        val p = sample(token = "secret-token-123", name = "客厅手机")
        assertFalse(p.toString().contains("secret-token-123"))
        assertFalse(p.displayName.contains("secret-token-123"))
        assertTrue(p.toString().contains("192.168.1.10"))
        // 存储文件本身含 token（持久化需要），但展示路径恒不含
        val prof = newProfiles()
        prof.save(p)
        val raw = tmp.root.resolve("profiles.json").toPath().toFile().readText()
        assertTrue(raw.contains("secret-token-123"))
        assertFalse(prof.list().single().toString().contains("secret-token-123"))
    }

    // ── 兼容与容错 ──

    @Test
    fun `兼容 - 老档案缺省键自动兜底`() {
        val file = tmp.root.resolve("profiles.json").toPath()
        // 老 JSON：无 udpPort/encryptOn/origin/presetLabel 键
        file.toFile().writeText(
            """[{"name":"","host":"192.168.1.10","tcpPort":22333,"backend":"lan-direct","token":"t","lastConnectedAt":42}]""",
        )
        val p = DesktopSourceProfiles(file = file)
        val only = p.list().single()
        assertEquals(22334, only.udpPort)
        assertEquals(true, only.encryptOn)
        assertEquals(SourceProfile.ORIGIN_MANUAL, only.origin)
        assertEquals("", only.presetLabel)
    }

    @Test
    fun `容错 - 损坏文件视为空且不崩溃`() {
        val file = tmp.root.resolve("profiles.json").toPath()
        file.toFile().writeText("{这不是 JSON 数组")
        val p = DesktopSourceProfiles(file = file)
        assertEquals(0, p.list().size)
        assertEquals(0, p.count())
        // 后续 upsert 正常重写修复
        p.save(sample())
        assertEquals(1, newProfiles().count())
    }
}
