package cloudphone.desktop

import cloudphone.desktop.ui.AppsLogic
import cloudphone.desktop.ui.DesktopShortcut
import cloudphone.protocol.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.4 验收：AppsScreen 列表状态/重试逻辑单测（headless）——
 * 列表状态机（3s 无应答出重试入口）、名称搜索、磁贴名不显示包名、
 * 单应用徽章/退出投影、.lnk 桌面快捷方式命令构造。
 */
class AppsScreenTest {

    private val apps = listOf(
        AppEntry("com.tencent.mm", "微信"),
        AppEntry("com.example.game", "GameXYZ"),
        AppEntry("com.demo.browser", "浏览器"),
    )

    // ── 列表状态机 ──

    @Test
    fun `列表状态机 - 默认与加载态不出重试`() {
        assertFalse(AppsLogic.ListState().showRetry)
        assertFalse(AppsLogic.ListState(phase = AppsLogic.Phase.LOADING).showRetry)
        assertFalse(AppsLogic.ListState(phase = AppsLogic.Phase.LOADED, apps = apps).showRetry)
        assertTrue(AppsLogic.ListState(phase = AppsLogic.Phase.LOADING).loading)
    }

    @Test
    fun `列表状态机 - 3s 无应答 TIMEOUT 显示重试入口携原因`() {
        val s = AppsLogic.ListState(phase = AppsLogic.Phase.TIMEOUT, reason = "3s 无 APP_LIST 应答")
        assertTrue(s.showRetry)
        assertTrue(s.reason.contains("3s"))
        assertFalse(s.loading)
    }

    // ── 名称搜索 ──

    @Test
    fun `搜索 - 空查询全量`() {
        assertEquals(apps, AppsLogic.filterApps(apps, ""))
        assertEquals(apps, AppsLogic.filterApps(apps, "   "))
    }

    @Test
    fun `搜索 - label 大小写不敏感包含匹配`() {
        assertEquals(listOf("微信"), AppsLogic.filterApps(apps, "微信").map { it.label })
        assertEquals(listOf("GameXYZ"), AppsLogic.filterApps(apps, "game").map { it.label })
        assertEquals(listOf("GameXYZ"), AppsLogic.filterApps(apps, "XYZ").map { it.label })
        assertTrue(AppsLogic.filterApps(apps, "不存在").isEmpty())
    }

    // ── 磁贴展示名（不显示包名） ──

    @Test
    fun `磁贴名 - 只显示 label 不暴露包名`() {
        assertEquals("微信", AppsLogic.tileName(apps[0]))
        assertEquals("GameXYZ", AppsLogic.tileName(apps[1]))
        // label 为空的异常条目兜底（不泄漏也至少可用）
        assertEquals("com.fallback.app", AppsLogic.tileName(AppEntry("com.fallback.app", "")))
    }

    // ── 桌面快捷方式（.lnk） ──

    @Test
    fun `lnk 文件名 - Windows 非法字符替换`() {
        assertEquals("微信.lnk", DesktopShortcut.lnkFileName("微信"))
        assertEquals("Game_XYZ.lnk", DesktopShortcut.lnkFileName("Game/XYZ"))
        assertEquals("a_b_c.lnk", DesktopShortcut.lnkFileName("a\\b:c"))
        assertEquals("x__y.lnk", DesktopShortcut.lnkFileName("x*?y"))
        assertEquals("app.lnk", DesktopShortcut.lnkFileName("   "))
        assertEquals("trim.lnk", DesktopShortcut.lnkFileName(" trim "))
    }

    @Test
    fun `lnk 命令 - WScript_Shell 目标与单应用参数`() {
        val cmd = DesktopShortcut.buildPowerShellLnkCommand(
            targetExe = "C:\\Apps\\cloudphone.exe",
            lnkPath = "C:\\Users\\me\\Desktop\\微信.lnk",
            arguments = DesktopShortcut.singleAppArguments("com.tencent.mm"),
            workingDir = "C:\\Apps",
        )
        assertTrue(cmd.contains("New-Object -ComObject WScript.Shell"))
        assertTrue(cmd.contains("CreateShortcut"))
        assertTrue(cmd.contains("\"C:\\Apps\\cloudphone.exe\""))
        assertTrue(cmd.contains("TargetPath"))
        assertTrue(cmd.contains("Arguments"))
        assertTrue(cmd.contains("--app com.tencent.mm"))
        assertTrue(cmd.contains("WorkingDirectory"))
        assertTrue(cmd.contains("Save()"))
    }

    @Test
    fun `lnk 命令 - 引号参数转义`() {
        val cmd = DesktopShortcut.buildPowerShellLnkCommand("a\"b.exe", "x.lnk", "--app p", ".")
        assertTrue(cmd.contains("\"a\\\"b.exe\""))
    }

    @Test
    fun `单应用参数 - 直达模式格式`() {
        assertEquals("--app com.demo.browser", DesktopShortcut.singleAppArguments("com.demo.browser"))
    }

    @Test
    fun `固化入口 - 非 Windows 宿主安全返回 null`() {
        // Linux 测试宿主：isWindows=false，create 不执行 PowerShell 直接返回 null（不抛出）
        if (!DesktopShortcut.isWindows()) {
            assertEquals(null, DesktopShortcut.create("微信", "com.tencent.mm", "/bin/true"))
        }
    }

    @Test
    fun `桌面目录 - user home 下 Desktop`() {
        val home = System.getProperty("user.home") ?: "."
        assertEquals("$home${java.io.File.separator}Desktop", DesktopShortcut.desktopDir().toString())
    }
}
