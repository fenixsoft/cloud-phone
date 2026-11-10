package cloudphone.desktop

import cloudphone.desktop.session.InputMapper
import cloudphone.desktop.session.KeyboardText
import cloudphone.protocol.ControlMessage
import cloudphone.protocol.InputAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.4 验收：InputMapper 纯函数单测——letterbox 映射边界、黑边防护、
 * 左键 DOWN/MOVE/UP、滚轮小步、右键 BACK。
 */
class InputMapperTest {

    /** 横屏视频 1920x1080 在 1280x960 视口：scale=0.667 → 内容 1280x720，上下黑边各 120 */
    private fun landscape() = InputMapper.Viewport(1920, 1080, 1280, 960)

    /** 竖屏视频 1080x1920 在 1920x1080 视口：scale=0.5625 → 内容 608x1080，左右黑边各 656 */
    private fun portrait() = InputMapper.Viewport(1080, 1920, 1920, 1080)

    // ── letterbox 几何 ──

    @Test
    fun `letterbox - 横屏上下黑边居中`() {
        val c = landscape().content
        assertEquals(0, c.x)
        assertEquals(120, c.y)
        assertEquals(1280, c.width)
        assertEquals(720, c.height)
    }

    @Test
    fun `letterbox - 竖屏左右黑边居中`() {
        val c = portrait().content
        assertEquals(656, c.x)
        assertEquals(0, c.y)
        assertEquals(608, c.width)
        assertEquals(1080, c.height)
    }

    @Test
    fun `letterbox - 视口与视频等比时铺满`() {
        val c = InputMapper.Viewport(1280, 720, 1280, 720).content
        assertEquals(0, c.x)
        assertEquals(0, c.y)
        assertEquals(1280, c.width)
        assertEquals(720, c.height)
    }

    // ── 归一化映射边界 ──

    @Test
    fun `映射 - 内容区四角到满量程边界`() {
        val vp = landscape()
        val c = vp.content
        // 左上角 → (0, 0)
        assertEquals(InputMapper.NormPoint(0, 0), InputMapper.mapToVideo(vp, c.x.toDouble(), c.y.toDouble()))
        // 右下角（内容区内最后像素）→ (65535, 65535)
        assertEquals(
            InputMapper.NormPoint(65535, 65535),
            InputMapper.mapToVideo(vp, (c.x + c.width - 1.0), (c.y + c.height - 1.0)),
        )
        // 中心 → 满量程一半
        val center = InputMapper.mapToVideo(vp, c.x + c.width / 2.0, c.y + c.height / 2.0)!!
        assertTrue("中心 x 应接近 32768（实际 ${center.x}）", center.x in 32700..32835)
        assertTrue("中心 y 应接近 32768（实际 ${center.y}）", center.y in 32700..32835)
    }

    @Test
    fun `黑边防护 - 内容区外返回 null`() {
        val vp = landscape()
        val c = vp.content
        // 上黑边（y=60 < contentY=120）
        assertNull(InputMapper.mapToVideo(vp, 640.0, 60.0))
        // 下黑边
        assertNull(InputMapper.mapToVideo(vp, 640.0, (c.y + c.height + 10).toDouble()))
        // 内容区内不越界
        assertTrue(InputMapper.mapToVideo(vp, 640.0, 400.0) != null)
        // 竖屏左右黑边
        val pc = portrait().content
        assertNull(InputMapper.mapToVideo(portrait(), 10.0, 540.0))
        assertNull(InputMapper.mapToVideo(portrait(), (pc.x + pc.width + 5).toDouble(), 540.0))
        assertTrue(InputMapper.mapToVideo(portrait(), (pc.x + 10).toDouble(), 540.0) != null)
    }

    // ── 左键 DOWN/MOVE/UP（拖动=滑动） ──

    @Test
    fun `左键三段 - 动作与归一化坐标正确`() {
        val vp = landscape()
        val down = InputMapper.pointerDown(vp, 640.0, 480.0, sessionId = 7)!!
        assertEquals(InputAction.DOWN, down.action)
        assertEquals(7, down.sessionId)
        assertEquals(InputMapper.POINTER_MOUSE, down.pointerId)
        val move = InputMapper.pointerMove(vp, 700.0, 500.0, 7)!!
        assertEquals(InputAction.MOVE, move.action)
        assertTrue("拖动 MOVE 坐标应随位置变化", move.x != down.x || move.y != down.y)
        val up = InputMapper.pointerUp(vp, 700.0, 500.0, 7)!!
        assertEquals(InputAction.UP, up.action)
        assertEquals(move.x, up.x)
        assertEquals(move.y, up.y)
        // 拖出黑边：MOVE 事件被忽略
        assertNull(InputMapper.pointerMove(vp, 640.0, 60.0, 7))
    }

    // ── 滚轮小步 ──

    @Test
    fun `滚轮 - 单格三元组与方向语义`() {
        val vp = landscape()
        // 滚轮向下 → 手指上滑（y 递减）
        val dn = InputMapper.wheelStep(vp, 640.0, 480.0, notches = 1, sessionId = 3)
        assertEquals(3, dn.size)
        assertEquals(InputAction.DOWN, dn[0].action)
        assertEquals(InputAction.MOVE, dn[1].action)
        assertEquals(InputAction.UP, dn[2].action)
        assertEquals(dn[0].x, dn[1].x)
        assertTrue("滚下应上滑（y 递减）", dn[1].y < dn[0].y)
        assertEquals(3, dn[0].sessionId)
        // 滚轮向上 → 手指下滑（y 递增）
        val up2 = InputMapper.wheelStep(vp, 640.0, 480.0, notches = -1, sessionId = 3)
        assertTrue("滚上应下滑（y 递增）", up2[1].y > up2[0].y)
        // 起点或终点落黑边 → 不注入半截手势
        assertTrue(InputMapper.wheelStep(vp, 640.0, 480.0, notches = 1, sessionId = 3).isNotEmpty())
        val nearEdge = InputMapper.wheelStep(vp, 640.0, 130.0, notches = 1, sessionId = 3) // 终点会越过上边缘
        assertTrue("终点越黑边应整体放弃", nearEdge.isEmpty())
    }

    // ── 右键 = BACK ──

    @Test
    fun `右键 - BACK 注入`() {
        val e = InputMapper.back(sessionId = 9)
        assertEquals(InputAction.BACK, e.action)
        assertEquals(9, e.sessionId)
        assertEquals(0, e.x)
        assertEquals(0, e.y)
    }

    // ── 键盘字符过滤（spec「鼠标与键盘输入映射」：可打印字符键 TEXT_INPUT 直发，非字符键不注入） ──

    @Test
    fun `键盘过滤 - 可打印 ASCII 字母数字标点空格直发`() {
        assertEquals("a", KeyboardText.printableText('a'))
        assertEquals("Z", KeyboardText.printableText('Z'))
        assertEquals("7", KeyboardText.printableText('7'))
        assertEquals(",", KeyboardText.printableText(','))
        assertEquals("@", KeyboardText.printableText('@'))
        assertEquals(" ", KeyboardText.printableText(' '))
    }

    @Test
    fun `键盘过滤 - 全 Unicode 字母与增补码位（中文、emoji）直发`() {
        assertEquals("中", KeyboardText.printableText('中'))
        assertEquals("é", KeyboardText.printableText('é'))
        assertEquals("😀", KeyboardText.printableText(0x1F600)) // 增补平面码位 emoji
    }

    @Test
    fun `键盘过滤 - 控制字符与未定义键码不注入`() {
        assertNull(KeyboardText.printableText('\n')) // Enter
        assertNull(KeyboardText.printableText('\t')) // Tab
        assertNull(KeyboardText.printableText('\r'))
        assertNull(KeyboardText.printableText('\u001B')) // Escape
        assertNull(KeyboardText.printableText('\u007F')) // Delete/Backspace（C1 控制字符）
        assertNull(KeyboardText.printableText(KeyboardText.CHAR_UNDEFINED)) // 功能键/修饰键
        assertNull(KeyboardText.printableText(-1)) // 非法码位
    }
}
