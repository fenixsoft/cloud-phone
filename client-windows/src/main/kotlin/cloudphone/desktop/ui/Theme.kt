package cloudphone.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 深色控制台主题（spec 3.1）：深空黑底 + 发射蓝强调，mono 读出风格。
 * 桌面端固定深色（design Non-Goals：不做深浅色切换，沿用深色控制台主题）。
 */

/** 页面底色 */
val DesktopBg = Color(0xFF0B0E14)

/** 卡片/面板表面色 */
val DesktopSurface = Color(0xFF141922)

/** 表面色分层（比 surface 更亮一级，弹层/输入框用） */
val DesktopSurfaceHigh = Color(0xFF1B2230)

/** 描边/分隔色 */
val DesktopOutline = Color(0xFF232B3A)

/** 主文本色 */
val DesktopText = Color(0xFFF8FAFC)

/** 次级文本色 */
val DesktopTextDim = Color(0xFF94A3B8)

/** 强调色渐变两端（按钮/徽章/进度） */
val AccentBlue = Color(0xFF4F8CFF)
val AccentCyan = Color(0xFF22D3EE)

/** 语义色 */
val SemanticOk = Color(0xFF34D399)
val SemanticWarn = Color(0xFFF59E0B)
val SemanticDanger = Color(0xFFEF4444)

/** 深色控制台配色 */
private val ConsoleColors = darkColors(
    background = DesktopBg,
    surface = DesktopSurface,
    primary = AccentBlue,
    secondary = AccentCyan,
    onPrimary = Color(0xFF04101F),
    onBackground = DesktopText,
    onSurface = DesktopText,
)

/** 应用根主题：固定深色（参数仅为兼容 MaterialTheme 签名保留） */
@Composable
fun DesktopTheme(content: @Composable () -> Unit) {
    // 桌面端固定深色主题；isSystemInDarkTheme 仅用于避免未消费告警的显式引用
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(colors = ConsoleColors, content = content)
}
