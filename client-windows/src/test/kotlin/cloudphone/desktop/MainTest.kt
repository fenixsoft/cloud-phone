package cloudphone.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.GraphicsEnvironment

/** 1.2 验收：headless 环境启动入口冒烟（无显示环境跳过实际渲染）+ 启动参数解析 */
class MainTest {

    @Test
    fun `headless 环境启动入口冒烟通过`() {
        // 仅无显示环境执行（有显示器的开发机跳过：真实窗口创建会阻塞测试进程）
        assumeTrue("本测试仅在 headless（无显示）环境执行", GraphicsEnvironment.isHeadless())
        val code = runDesktopApp(emptyArray())
        assertEquals(0, code)
    }

    @Test
    fun `启动参数解析 - 软件渲染开关`() {
        assertFalse(LaunchOptions.parse(emptyArray()).softwareRender)
        assertTrue(LaunchOptions.parse(arrayOf("--software-render")).softwareRender)
        assertFalse(LaunchOptions.parse(arrayOf("--other")).softwareRender)
    }
}
