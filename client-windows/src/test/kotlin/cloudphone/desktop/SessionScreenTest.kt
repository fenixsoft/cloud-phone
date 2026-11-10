package cloudphone.desktop

import cloudphone.desktop.aux.AuxUiState
import cloudphone.desktop.aux.TextInputResult
import cloudphone.desktop.session.HudState
import cloudphone.desktop.ui.DockAction
import cloudphone.desktop.ui.DockLogic
import cloudphone.desktop.ui.HudFormatter
import cloudphone.desktop.ui.KeyTextRouter
import cloudphone.desktop.ui.NavLogic
import cloudphone.desktop.ui.NoticeLogic
import cloudphone.desktop.ui.ScreenshotSaver
import cloudphone.desktop.ui.TextInputStatus
import cloudphone.desktop.ui.VolumeUi
import cloudphone.protocol.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/**
 * 3.3 验收：SessionScreen HUD 状态投影/工具坞置灰逻辑单测（headless）——
 * HUD 胶囊各字段、后端+加密徽章（含 P2P 回落）、AuxNotice 驱动置灰、
 * 虚拟导航 BACK/HOME/RECENTS 可用性、回落通告条、文字 ACK 状态、音量/截屏。
 */
class SessionScreenTest {

    // ── HUD 状态投影 ──

    @Test
    fun `HUD 投影 - 指标行各字段格式`() {
        val hud = HudState(
            fps = 12.4, bitrateKbps = 8000.0, rttMs = 35.6, lossPct = 0.25,
            decodedWidth = 2560, decodedHeight = 1440,
        )
        assertEquals("12.4 fps", HudFormatter.fpsLine(hud))
        assertEquals("8.0 Mbps", HudFormatter.bitrateLine(hud))
        assertEquals("36 ms", HudFormatter.rttLine(hud))
        assertEquals("0.3%", HudFormatter.lossLine(hud))
        assertEquals("2560×1440", HudFormatter.resolutionLine(hud))
    }

    @Test
    fun `HUD 投影 - 解码前分辨率为占位`() {
        assertEquals("—", HudFormatter.resolutionLine(HudState()))
        assertEquals("—", HudFormatter.resolutionLine(HudState(decodedWidth = 1920, decodedHeight = 0)))
    }

    @Test
    fun `HUD 投影 - 后端徽章含 P2P 回落形态`() {
        assertEquals("—", HudFormatter.wireBadge(HudState()))
        assertEquals("LAN", HudFormatter.wireBadge(HudState(wireKind = TransportKind.LAN_DIRECT)))
        assertEquals("P2P", HudFormatter.wireBadge(HudState(wireKind = TransportKind.P2P)))
        assertEquals("FRP", HudFormatter.wireBadge(HudState(wireKind = TransportKind.FRP)))
        assertEquals("P2P→FRP", HudFormatter.wireBadge(HudState(wireKind = TransportKind.P2P_FALLBACK_FRP)))
    }

    @Test
    fun `HUD 投影 - 加密徽章`() {
        assertEquals("加密", HudFormatter.cryptoBadge(HudState(encrypted = true)))
        assertEquals("明文", HudFormatter.cryptoBadge(HudState(encrypted = false)))
    }

    @Test
    fun `HUD 投影 - 胶囊两行完整`() {
        val lines = HudFormatter.capsuleLines(
            HudState(fps = 60.0, bitrateKbps = 8000.0, rttMs = 20.0, lossPct = 0.0, decodedWidth = 1080, decodedHeight = 1920, wireKind = TransportKind.LAN_DIRECT, encrypted = true),
        )
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("60.0 fps"))
        assertTrue(lines[0].contains("1080×1920"))
        assertEquals("LAN · 加密", lines[1])
    }

    // ── 工具坞置灰（AuxNotice 驱动） ──

    @Test
    fun `工具坞置灰 - AuxNotice 文字通道不可用时键盘置灰其余恒可用`() {
        val aux = AuxUiState(textInputAvailable = false, textInputUnavailableReason = "IME 未启用")
        val enabled = DockLogic.enabledActions(aux)
        assertFalse("键盘应置灰", enabled[DockAction.KEYBOARD]!!)
        // 其余动作恒可用
        assertTrue(enabled[DockAction.SCREENSHOT]!!)
        assertTrue(enabled[DockAction.PRESET_CYCLE]!!)
        assertTrue(enabled[DockAction.ROTATE]!!)
        assertTrue(enabled[DockAction.SINGLE_APP]!!)
        assertTrue(enabled[DockAction.DISCONNECT]!!)
        assertEquals("IME 未启用", DockLogic.keyboardDisabledReason(aux))
    }

    @Test
    fun `工具坞置灰 - 探测可用或未知时全部可用`() {
        assertTrue(DockLogic.enabledActions(AuxUiState())[DockAction.KEYBOARD]!!)
        assertTrue(DockLogic.enabledActions(AuxUiState(textInputAvailable = true))[DockAction.KEYBOARD]!!)
        assertNull(DockLogic.keyboardDisabledReason(AuxUiState()))
        // 置灰但无原因 → 兜底文案
        assertTrue(DockLogic.keyboardDisabledReason(AuxUiState(textInputAvailable = false))!!.isNotBlank())
    }

    @Test
    fun `工具坞 - 六个动作齐备`() {
        assertEquals(
            listOf("键盘", "截屏", "档位", "旋转", "单应用", "断开"),
            DockAction.entries.map { it.label },
        )
    }

    // ── 虚拟导航键 ──

    @Test
    fun `虚拟导航 - BACK 可用 HOME 与 RECENTS 占位禁用`() {
        val buttons = NavLogic.buttons
        assertEquals(3, buttons.size)
        assertEquals("◁ BACK", buttons[0].label)
        assertTrue(buttons[0].enabled)
        assertEquals("○ HOME", buttons[1].label)
        assertFalse(buttons[1].enabled)
        assertEquals("□ RECENTS", buttons[2].label)
        assertFalse(buttons[2].enabled)
    }

    // ── 回落通告条 ──

    @Test
    fun `回落通告 - 首条非空文案显示`() {
        assertNull(NoticeLogic.bannerText(emptyList()))
        assertNull(NoticeLogic.bannerText(listOf("", "  ")))
        assertEquals("P2P 打洞超时，已回落 FRP 中继", NoticeLogic.bannerText(listOf("P2P 打洞超时，已回落 FRP 中继")))
    }

    // ── 文字输入 ACK 状态 ──

    @Test
    fun `文字面板 - ACK 三态文案`() {
        assertTrue(TextInputStatus.text(null).contains("就绪"))
        assertEquals("注入成功", TextInputStatus.text(TextInputResult.Ok))
        assertEquals("注入失败：IME 未启用", TextInputStatus.text(TextInputResult.Failed("IME 未启用")))
        assertTrue(TextInputStatus.text(TextInputResult.Timeout).contains("超时未确认"))
    }

    // ── 音量与截屏 ──

    @Test
    fun `音量 - 百分比与越界钳制`() {
        assertEquals("0%", VolumeUi.percentText(0.0))
        assertEquals("50%", VolumeUi.percentText(0.5))
        assertEquals("80%", VolumeUi.percentText(0.8))
        assertEquals("100%", VolumeUi.percentText(1.0))
        assertEquals("0%", VolumeUi.percentText(-0.5))
        assertEquals("100%", VolumeUi.percentText(1.5))
    }

    @Test
    fun `截屏 - BGRA 帧落盘 PNG 尺寸一致`() {
        val dir = Files.createTempDirectory("screenshot-test")
        val w = 16
        val h = 8
        val bgra = ByteArray(w * h * 4) { i -> (i % 256).toByte() }
        val saved = ScreenshotSaver.saveFrame(bgra, w, h, dir)
        assertNotNull("应成功保存", saved)
        val image = javax.imageio.ImageIO.read(saved!!.toFile())
        assertEquals(w, image.width)
        assertEquals(h, image.height)
    }

    // ── 键盘直发路由（spec「鼠标与键盘输入映射」：可打印字符键 TEXT_INPUT 直发，非字符键不注入） ──

    @Test
    fun `键盘直发 - 可打印字符（ASCII、中文、emoji）产出直发文本`() {
        assertEquals("a", KeyTextRouter.typedText('a'.code))
        assertEquals("中", KeyTextRouter.typedText('中'.code))
        assertEquals(" ", KeyTextRouter.typedText(' '.code))
        assertEquals("😀", KeyTextRouter.typedText(0x1F600)) // utf16CodePoint 携带增补码位
    }

    @Test
    fun `键盘直发 - 非字符键与控制字符不注入`() {
        // 功能键/修饰键/未产生字符的按下事件：utf16CodePoint = CHAR_UNDEFINED（0xFFFF）不注入
        assertNull(KeyTextRouter.typedText(0xFFFF))
        // 控制字符不注入（协议无独立按键通道）：Enter/Escape/组合键控制字符/非法码位
        assertNull(KeyTextRouter.typedText('\n'.code)) // Enter
        assertNull(KeyTextRouter.typedText('\u001B'.code)) // Escape
        assertNull(KeyTextRouter.typedText(0x01)) // Ctrl+A 等组合键产生的控制字符
        assertNull(KeyTextRouter.typedText(-1))
    }

    @Test
    fun `键盘直发 - 门控：文字面板打开或注入降级时暂停直发`() {
        assertTrue(KeyTextRouter.directSendAllowed(textPanelOpen = false, textInputAvailable = null))
        assertTrue(KeyTextRouter.directSendAllowed(textPanelOpen = false, textInputAvailable = true))
        assertFalse(KeyTextRouter.directSendAllowed(textPanelOpen = true, textInputAvailable = null))
        assertFalse(KeyTextRouter.directSendAllowed(textPanelOpen = false, textInputAvailable = false))
    }
}
