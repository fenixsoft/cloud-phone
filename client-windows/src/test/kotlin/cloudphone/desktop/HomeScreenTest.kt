package cloudphone.desktop

import cloudphone.desktop.ui.DiscoveredCard
import cloudphone.desktop.ui.DiscoveredCardAction
import cloudphone.desktop.ui.FormErrors
import cloudphone.desktop.ui.HomeBackend
import cloudphone.desktop.ui.HomeFormLogic
import cloudphone.desktop.ui.HomePreset
import cloudphone.desktop.ui.NewConnectionForm
import cloudphone.desktop.ui.ProfileCard
import cloudphone.desktop.ui.ScanPanelState
import cloudphone.protocol.WatchMode
import cloudphone.transport.BackendKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.2 验收：HomeScreen 表单校验与状态投影单测（headless）——
 * 端口/后端/token 默认值、自定义档位范围与 aspect-exact 内联报错、
 * 参数投影（跟随本机/720P/1080P/自定义）、发现/档案卡片置灰、扫描进度。
 */
class HomeScreenTest {

    /** 本机屏幕参照（跟随本机档 aspect-exact 引用） */
    private val lw = 1920
    private val lh = 1080

    // ── 默认表单 ──

    @Test
    fun `默认表单 - 端口并排 22333 22334 后端 lan-direct 高级折叠跟随本机 60 8M`() {
        val f = NewConnectionForm()
        assertEquals("22333", f.tcpPortText)
        assertEquals("22334", f.udpPortText)
        assertEquals(HomeBackend.LAN_DIRECT, f.backend)
        assertFalse("高级应默认折叠", f.advancedExpanded)
        assertEquals(HomePreset.FOLLOW_LOCAL, f.preset)
        assertEquals(60, f.fps)
        assertEquals(8, f.bitrateMbps)
        assertTrue(f.encryptOn)
    }

    // ── 基础字段校验 ──

    @Test
    fun `host 校验 - 空报错 trim 生效`() {
        assertNotNull(HomeFormLogic.validateHost(""))
        assertNotNull(HomeFormLogic.validateHost("   "))
        assertNull(HomeFormLogic.validateHost("192.168.1.10"))
        assertNull(HomeFormLogic.validateHost(" my-host.example.com "))
    }

    @Test
    fun `端口校验 - 整数 1 到 65535`() {
        assertNotNull(HomeFormLogic.validatePort(""))
        assertNotNull(HomeFormLogic.validatePort("abc"))
        assertNotNull(HomeFormLogic.validatePort("0"))
        assertNotNull(HomeFormLogic.validatePort("65536"))
        assertNull(HomeFormLogic.validatePort("1"))
        assertNull(HomeFormLogic.validatePort("65535"))
        assertNull(HomeFormLogic.validatePort(" 22333 "))
    }

    @Test
    fun `token 校验 - 必填`() {
        assertNotNull(HomeFormLogic.validateToken(""))
        assertNull(HomeFormLogic.validateToken("t1"))
    }

    @Test
    fun `整表校验 - 默认值合法 完整表单通过`() {
        val ok = NewConnectionForm(host = "192.168.1.10", token = "t")
        assertNull(HomeFormLogic.validate(ok, lw, lh))
        // 缺 host/token → 内联报错
        val errs = HomeFormLogic.validate(NewConnectionForm(), lw, lh)
        assertNotNull(errs)
        assertTrue(errs!!.host != null)
        assertTrue(errs.token != null)
        // 自定义档缺宽高 → 内联报错且不发 START_SESSION（resolve 前必须 validate）
        val custom = NewConnectionForm(host = "h", token = "t", preset = HomePreset.CUSTOM)
        val cerrs = HomeFormLogic.validate(custom, lw, lh)
        assertNotNull(cerrs)
        assertNotNull(cerrs!!.customWidth)
        assertNotNull(cerrs.customHeight)
    }

    // ── 自定义档位范围校验 ──

    @Test
    fun `自定义档 - 宽高范围边界`() {
        // width 320–2560
        val (wLo, _, _) = HomeFormLogic.validateCustomPreset("320", "1080", "60", lw, lh)
        // aspect 不匹配（320x1080 非 16:9）→ 高度字段带 aspect 报错，宽度无范围错
        assertNull(wLo)
        val (wUnder, _, _) = HomeFormLogic.validateCustomPreset("319", "1080", "60", lw, lh)
        assertNotNull(wUnder)
        val (wOver, _, _) = HomeFormLogic.validateCustomPreset("2561", "1080", "60", lw, lh)
        assertNotNull(wOver)
        val (_, wOverOk, _) = HomeFormLogic.validateCustomPreset("2560", "1440", "60", lw, lh)
        // 2560x1440 是 16:9 → 无错
        assertNull(wOverOk)
        // height 240–2560
        val (_, hUnder, _) = HomeFormLogic.validateCustomPreset("1280", "239", "60", lw, lh)
        assertNotNull(hUnder)
        val (_, hOver, _) = HomeFormLogic.validateCustomPreset("1280", "2561", "60", lw, lh)
        assertNotNull(hOver)
    }

    @Test
    fun `自定义档 - aspect-exact 与本机屏幕宽高比一致`() {
        // 1920x1080 本机：1600x900 OK；1600x901 报错（比例不一致）
        val (w1, h1, _) = HomeFormLogic.validateCustomPreset("1600", "900", "60", lw, lh)
        assertNull(w1); assertNull(h1)
        val (w2, h2, _) = HomeFormLogic.validateCustomPreset("1600", "901", "60", lw, lh)
        assertNull(w2); assertTrue("比例不一致应报在高度字段", h2!!.contains("宽高比"))
        // 竖屏本机 1080x1920：900x1600 OK
        val (w3, h3, _) = HomeFormLogic.validateCustomPreset("900", "1600", "60", 1080, 1920)
        assertNull(w3); assertNull(h3)
        // 交叉相乘防溢出：极端尺寸也稳定判定
        val (w4, h4, _) = HomeFormLogic.validateCustomPreset("2560", "1440", "60", 3840, 2160)
        assertNull(w4); assertNull(h4)
    }

    @Test
    fun `自定义档 - 非整数与帧率范围`() {
        val (w, _, _) = HomeFormLogic.validateCustomPreset("abc", "1080", "60", lw, lh)
        assertTrue(w!!.contains("整数"))
        val (_, _, fLow) = HomeFormLogic.validateCustomPreset("1600", "900", "10", lw, lh)
        assertNotNull(fLow)
        val (_, _, fHigh) = HomeFormLogic.validateCustomPreset("1600", "900", "200", lw, lh)
        assertNotNull(fHigh)
        val (_, _, fOk) = HomeFormLogic.validateCustomPreset("1600", "900", "120", lw, lh)
        assertNull(fOk)
    }

    // ── 参数投影 ──

    @Test
    fun `参数投影 - 跟随本机取本机分辨率`() {
        val f = NewConnectionForm(host = "192.168.1.10", token = "t")
        val p = HomeFormLogic.resolveParams(f, lw, lh)
        assertEquals(lw, p.width)
        assertEquals(lh, p.height)
        assertEquals(60, p.fps)
        assertEquals(8000, p.bitrateKbps)
        assertEquals(22333, p.tcpPort)
        assertEquals(22334, p.udpPort)
        assertEquals(BackendKind.LAN_DIRECT, p.kind)
        assertEquals(WatchMode.FULLSCREEN, p.mode)
        assertEquals("t", p.token)
    }

    @Test
    fun `参数投影 - 720P 1080P 与自定义档`() {
        val p720 = HomeFormLogic.resolveParams(NewConnectionForm(host = "h", token = "t", preset = HomePreset.P720, fps = 30), lw, lh)
        assertEquals(1280, p720.width)
        assertEquals(720, p720.height)
        assertEquals(30, p720.fps)
        val p1080 = HomeFormLogic.resolveParams(NewConnectionForm(host = "h", token = "t", preset = HomePreset.P1080), lw, lh)
        assertEquals(1920, p1080.width)
        assertEquals(1080, p1080.height)
        val custom = HomeFormLogic.resolveParams(
            NewConnectionForm(host = "h", token = "t", preset = HomePreset.CUSTOM, customWidthText = "2560", customHeightText = "1440", customFpsText = "60", bitrateMbps = 20),
            lw, lh,
        )
        assertEquals(2560, custom.width)
        assertEquals(1440, custom.height)
        assertEquals(20000, custom.bitrateKbps)
    }

    @Test
    fun `参数投影 - 后端映射与单应用模式`() {
        val f = NewConnectionForm(host = "h", token = "t", backend = HomeBackend.FRP)
        assertEquals(BackendKind.FRP, HomeFormLogic.resolveParams(f, lw, lh).kind)
        assertEquals(BackendKind.P2P, HomeFormLogic.resolveParams(f.copy(backend = HomeBackend.P2P), lw, lh).kind)
    }

    // ── 发现/档案卡片投影 ──

    @Test
    fun `发现卡片 - 已保存标记与动作`() {
        val unsaved = DiscoveredCard(host = "192.168.1.10", port = 22333)
        assertEquals("未保存", unsaved.savedLabel)
        val saved = DiscoveredCard(host = "192.168.1.10", port = 22333, savedProfileName = "客厅手机")
        assertEquals("已保存·客厅手机", saved.savedLabel)
        assertEquals("192.168.1.10:22333", saved.title)
        assertEquals(2, DiscoveredCardAction.entries.size)
    }

    @Test
    fun `档案卡片 - 置灰不可连但删除恒可用`() {
        val good = ProfileCard.from("客厅手机", "192.168.1.10", 22333, 22334, "lan-direct", "t", "自动", "1080P·60")
        assertTrue(good.connectable)
        // host 空 → 置灰
        val noHost = ProfileCard.from("", "", 22333, 22334, "lan-direct", "t", "手输", "")
        assertFalse(noHost.connectable)
        // port 非法 → 置灰
        val badPort = ProfileCard.from("x", "h", 0, 22334, "frp", "t", "手输", "")
        assertFalse(badPort.connectable)
    }

    // ── 扫描面板投影 ──

    @Test
    fun `扫描面板 - 进度分数与文案`() {
        val idle = ScanPanelState()
        assertEquals(0f, idle.progressFraction)
        val running = ScanPanelState(scanning = true, probed = 64, total = 254, subnetLabel = "192.168.1.0/24")
        assertEquals(64.0 / 254, running.progressFraction.toDouble(), 1e-9)
        assertEquals("64 / 254", running.progressText)
        assertEquals("192.168.1.0/24", running.subnetLabel)
        val noTotal = ScanPanelState(scanning = true, probed = 0, total = 0)
        assertEquals(0f, noTotal.progressFraction)
    }
}
