package cloudphone.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.KeyEvent

/**
 * 3.1 验收：AppWindow 快捷键/全屏状态逻辑单测（headless）——
 * F11 全屏切换、Ctrl+Shift+X 断开、Ctrl+Shift+K 文字输入面板、修饰键边界。
 */
class AppWindowTest {

    @Test
    fun `F11 - 无修饰键触发全屏切换`() {
        assertEquals(AppShortcut.ToggleFullscreen, ShortcutResolver.resolve(ctrl = false, shift = false, alt = false, keyCode = KeyEvent.VK_F11))
    }

    @Test
    fun `F11 - 带修饰键不触发`() {
        assertNull(ShortcutResolver.resolve(ctrl = true, shift = false, alt = false, keyCode = KeyEvent.VK_F11))
        assertNull(ShortcutResolver.resolve(ctrl = false, shift = true, alt = false, keyCode = KeyEvent.VK_F11))
        assertNull(ShortcutResolver.resolve(ctrl = false, shift = false, alt = true, keyCode = KeyEvent.VK_F11))
    }

    @Test
    fun `Ctrl+Shift+X - 断开连接`() {
        assertEquals(AppShortcut.Disconnect, ShortcutResolver.resolve(ctrl = true, shift = true, alt = false, keyCode = KeyEvent.VK_X))
    }

    @Test
    fun `Ctrl+Shift+X - 修饰键不全不触发`() {
        assertNull(ShortcutResolver.resolve(ctrl = true, shift = false, alt = false, keyCode = KeyEvent.VK_X))
        assertNull(ShortcutResolver.resolve(ctrl = false, shift = true, alt = false, keyCode = KeyEvent.VK_X))
        assertNull(ShortcutResolver.resolve(ctrl = false, shift = false, alt = false, keyCode = KeyEvent.VK_X))
    }

    @Test
    fun `Ctrl+Shift+K - 文字输入面板`() {
        assertEquals(AppShortcut.TextInputPanel, ShortcutResolver.resolve(ctrl = true, shift = true, alt = false, keyCode = KeyEvent.VK_K))
        assertNull(ShortcutResolver.resolve(ctrl = true, shift = false, alt = false, keyCode = KeyEvent.VK_K))
    }

    @Test
    fun `未注册键 - 返回 null`() {
        assertNull(ShortcutResolver.resolve(ctrl = true, shift = true, alt = false, keyCode = KeyEvent.VK_A))
        assertNull(ShortcutResolver.resolve(ctrl = false, shift = false, alt = false, keyCode = KeyEvent.VK_ESCAPE))
    }

    @Test
    fun `全屏状态 - 切换取反`() {
        assertTrue("非全屏 → 切换后全屏", ShortcutResolver.toggleFullscreen(false))
        assertFalse("全屏 → 切换后退出全屏", ShortcutResolver.toggleFullscreen(true))
    }
}
