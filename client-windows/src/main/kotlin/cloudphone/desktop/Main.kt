package cloudphone.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cloudphone.desktop.ui.DesktopTheme
import java.awt.GraphicsEnvironment
import kotlin.system.exitProcess

/**
 * 云手机 Windows 桌面客户端入口。
 *
 * 软件渲染回退（design Risks：dockur/windows VM 无 GPU，Compose Desktop 的
 * Direct3D/Skia 渲染后端初始化可能失败）：启动时加 JVM 参数
 *
 * ```
 * java -Dskiko.renderApi=SOFTWARE -jar cloudphone-desktop.jar
 * ```
 *
 * 正常有 GPU 的桌面环境无需该参数（skiko 默认按平台自动选择渲染后端）；
 * Windows VM 冒烟链路（scripts/win_vm_smoke.sh）与 docs/windows-client.md 均以
 * 软件渲染参数启动，属默认推荐路径而非调试开关。
 */
fun main(args: Array<String>) {
    val code = runDesktopApp(args)
    if (code != 0) exitProcess(code)
}

/**
 * 应用启动入口（可被单测冒烟：headless 环境——构建/CI 服务器无显示环境——跳过
 * 实际窗口创建，直接返回 0；有显示环境则进入 Compose 窗口事件循环直到窗口关闭）。
 */
fun runDesktopApp(args: Array<String>): Int {
    if (GraphicsEnvironment.isHeadless()) {
        println("[cloudphone-desktop] headless 环境（无显示）跳过窗口渲染，启动入口冒烟通过")
        return 0
    }
    val options = LaunchOptions.parse(args)
    application {
        var fullscreen by remember { mutableStateOf(false) }
        val windowState = rememberWindowState(
            width = 1100.dp,
            height = 700.dp,
            position = WindowPosition.Aligned(Alignment.Center),
        )
        val bus = remember { UiBus() }
        AppWindow(
            state = windowState,
            title = "CloudPhone 桌面客户端",
            onCloseRequest = ::exitApplication,
            onShortcut = { shortcut ->
                when (shortcut) {
                    AppShortcut.ToggleFullscreen -> fullscreen = !fullscreen
                    AppShortcut.Disconnect -> bus.disconnect.tryEmit(Unit)
                    AppShortcut.TextInputPanel -> bus.textPanel.tryEmit(Unit)
                }
            },
        ) {
            // F11 全屏切换（headless 环境不执行到此处）
            LaunchedEffect(fullscreen) {
                windowState.placement =
                    if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
            }
            DesktopTheme {
                AppRoot(bus = bus, softwareRender = options.softwareRender, autotest = options.autotest)
            }
        }
    }
    return 0
}

/** 自动化测试连接参数（--autotest host port [token]；VM 冒烟跳过手输表单直连） */
data class AutotestParams(
    val host: String,
    val tcpPort: Int,
    val token: String,
)

/** 启动参数（预留：VM 冒烟与打包场景的命令行开关） */
data class LaunchOptions(
    /** 强制软件渲染提示（真实渲染后端切换由 -Dskiko.renderApi=SOFTWARE JVM 参数完成，见类注释） */
    val softwareRender: Boolean = false,

    /** 自动化连接（E2E 冒烟：启动即以给定参数建立会话，等效表单手输后的连接） */
    val autotest: AutotestParams? = null,
) {
    companion object {
        fun parse(args: List<String>): LaunchOptions {
            val idx = args.indexOf("--autotest")
            val autotest = if (idx >= 0 && idx + 2 < args.size) {
                AutotestParams(
                    host = args[idx + 1],
                    tcpPort = args[idx + 2].toIntOrNull() ?: 22333,
                    token = args.getOrNull(idx + 3) ?: "cloudphone-m0",
                )
            } else {
                null
            }
            return LaunchOptions(
                softwareRender = args.contains("--software-render"),
                autotest = autotest,
            )
        }

        fun parse(args: Array<out String>): LaunchOptions = parse(args.toList())
    }
}
