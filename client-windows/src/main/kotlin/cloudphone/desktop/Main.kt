package cloudphone.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
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
        Window(
            onCloseRequest = ::exitApplication,
            title = "CloudPhone 桌面客户端",
            state = WindowState(width = 1100.dp, height = 700.dp, position = WindowPosition.Aligned(Alignment.Center)),
        ) {
            DesktopTheme {
                PlaceholderSurface(options)
            }
        }
    }
    return 0
}

/** 1.2 占位内容层（3.x 三屏接入后由 AppWindow/HomeScreen 替换） */
@Composable
private fun PlaceholderSurface(options: LaunchOptions) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0E14)), contentAlignment = Alignment.Center) {
        Text("CloudPhone 桌面客户端（${if (options.softwareRender) "软件渲染" else "默认渲染"}）", color = Color(0xFF94A3B8))
    }
}

/** 启动参数（预留：VM 冒烟与打包场景的命令行开关） */
data class LaunchOptions(
    /** 强制软件渲染提示（真实渲染后端切换由 -Dskiko.renderApi=SOFTWARE JVM 参数完成，见类注释） */
    val softwareRender: Boolean = false,
) {
    companion object {
        fun parse(args: List<String>): LaunchOptions = LaunchOptions(
            softwareRender = args.contains("--software-render"),
        )

        fun parse(args: Array<out String>): LaunchOptions = parse(args.toList())
    }
}
