package cloudphone.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import cloudphone.desktop.ui.DesktopOutline
import cloudphone.desktop.ui.DesktopSurface
import cloudphone.desktop.ui.DesktopText
import cloudphone.desktop.ui.DesktopTextDim
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Point
import java.awt.event.KeyEvent

/**
 * 桌面快捷键语义（proposal「桌面专属形态」）：
 * - F11：全屏切换；
 * - Ctrl+Shift+X：断开当前会话；
 * - Ctrl+Shift+K：唤起文字输入面板（ACK 对账 5s）。
 */
sealed class AppShortcut {
    /** F11 全屏切换 */
    object ToggleFullscreen : AppShortcut()

    /** Ctrl+Shift+X 断开连接 */
    object Disconnect : AppShortcut()

    /** Ctrl+Shift+K 文字输入面板 */
    object TextInputPanel : AppShortcut()
}

/**
 * 快捷键解析（纯逻辑，headless 可测）：由修饰键+键码解析桌面快捷键；
 * 非组合、Alt 参与或未注册键返回 null。
 */
object ShortcutResolver {
    /** 是否命中已注册快捷键 */
    fun resolve(ctrl: Boolean, shift: Boolean, alt: Boolean, keyCode: Int): AppShortcut? {
        if (alt) return null
        return when {
            keyCode == KeyEvent.VK_F11 && !ctrl && !shift -> AppShortcut.ToggleFullscreen
            keyCode == KeyEvent.VK_X && ctrl && shift -> AppShortcut.Disconnect
            keyCode == KeyEvent.VK_K && ctrl && shift -> AppShortcut.TextInputPanel
            else -> null
        }
    }

    /** 全屏状态切换（纯函数：取反） */
    fun toggleFullscreen(isFullscreen: Boolean): Boolean = !isFullscreen
}

/**
 * 应用窗口骨架（design D6）：无边框窗口 + 自定义标题栏（拖拽/最小化/关闭）、
 * 全屏切换、全局快捷键（AWT 事件分发器拦截，覆盖 Compose 焦点外区域）。
 * 内容层经 [content] 注入（3.2-3.4 三屏接入），快捷键经 [onShortcut] 送达调用方路由。
 */
@Composable
fun AppWindow(
    state: WindowState,
    title: String,
    onCloseRequest: () -> Unit,
    onShortcut: (AppShortcut) -> Unit,
    content: @Composable () -> Unit,
) {
    Window(
        onCloseRequest = onCloseRequest,
        title = title,
        state = state,
        undecorated = true,
    ) {
        // 全局快捷键：AWT 层拦截（含自定义标题栏/Compose 无焦点区域）
        LaunchedEffect(Unit) {
            val km = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            val dispatcher = java.awt.KeyEventDispatcher { e ->
                if (e.id == KeyEvent.KEY_PRESSED) {
                    ShortcutResolver.resolve(e.isControlDown, e.isShiftDown, e.isAltDown, e.keyCode)
                        ?.let { s -> onShortcut(s); return@KeyEventDispatcher true }
                }
                false
            }
            km.addKeyEventDispatcher(dispatcher)
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                km.removeKeyEventDispatcher(dispatcher)
            }
        }
        Column(Modifier.fillMaxSize().background(DesktopSurface)) {
            TitleBar(title = title, state = state, onClose = onCloseRequest)
            Box(Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

/** 自定义标题栏：拖拽移动 + 最小化 + 关闭（无边框窗口的桌面形态底线） */
@Composable
private fun WindowScope.TitleBar(title: String, state: WindowState, onClose: () -> Unit) {
    // 拖拽移动：AWT 全局指针坐标差值平移窗口（CMP 1.6 无 WindowDraggableArea，手写等效）
    var dragAnchor by remember { mutableStateOf<Point?>(null) }
    val dragModifier = Modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { dragAnchor = MouseInfo.getPointerInfo().location },
            onDragEnd = { dragAnchor = null },
            onDragCancel = { dragAnchor = null },
        ) { change, _ ->
            val anchor = dragAnchor ?: return@detectDragGestures
            val now = MouseInfo.getPointerInfo().location ?: return@detectDragGestures
            val dx = now.x - anchor.x
            val dy = now.y - anchor.y
            if (dx != 0 || dy != 0) {
                window.setLocation(window.x + dx, window.y + dy)
                dragAnchor = now
            }
            change.consume()
        }
    }
    Row(
        modifier = dragModifier.fillMaxWidth().height(36.dp).background(DesktopSurface),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        // 左上角品牌双色点（accent→cyan 语义）
        Box(Modifier.width(8.dp).height(8.dp).background(Color(0xFF4F8CFF)))
        Spacer(Modifier.width(4.dp))
        Box(Modifier.width(8.dp).height(8.dp).background(Color(0xFF22D3EE)))
        Spacer(Modifier.width(10.dp))
        Text(title, color = DesktopTextDim, modifier = Modifier.padding(end = 12.dp))
        Spacer(Modifier.weight(1f))
        TitleBarButton("─", "最小化") { state.isMinimized = true }
        TitleBarButton("✕", "关闭", onClick = onClose)
    }
}

/** 标题栏按钮（桌面控制台风格：无框、命中区 44x36dp） */
@Composable
private fun TitleBarButton(glyph: String, label: String, hoverColor: Color = Color(0xFFEF4444), onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(44.dp)
            .height(36.dp)
            .clickable(onClickLabel = label) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = DesktopText)
    }
}
