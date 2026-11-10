package cloudphone.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloudphone.desktop.session.HudState
import cloudphone.desktop.session.KeyboardText
import cloudphone.desktop.session.SessionController
import cloudphone.desktop.session.SessionPhase
import cloudphone.desktop.aux.AuxUiState
import cloudphone.desktop.aux.TextInputResult
import cloudphone.protocol.TransportKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * 会话控制台（design D8）：letterbox 视频画布、HUD 胶囊（fps/码率/RTT/丢包/解码实际
 * 分辨率/后端+加密徽章）、回落通告条、底部工具坞（AuxNotice 驱动置灰）、虚拟导航键
 * （BACK 可用，HOME/RECENTS 占位禁用）、会话抽屉（挂起/恢复/断开）、文字输入面板
 * （ACK 状态）、音量 popover、截屏保存当前帧。
 * HUD 文案/工具坞置灰/导航可用性均为纯函数（headless 单测覆盖）。
 */

// ═══════════════ 纯逻辑（headless 可测） ═══════════════

/** HUD 胶囊文案投影（spec：fps/码率/RTT/丢包/解码实际分辨率/后端+加密徽章） */
object HudFormatter {
    /** 帧行（12.4 fps） */
    fun fpsLine(hud: HudState): String = String.format("%.1f fps", hud.fps)

    /** 码率（8.0 Mbps） */
    fun bitrateLine(hud: HudState): String = String.format("%.1f Mbps", hud.bitrateKbps / 1000.0)

    /** RTT（35 ms） */
    fun rttLine(hud: HudState): String = String.format("%.0f ms", hud.rttMs)

    /** 丢包（0.2%） */
    fun lossLine(hud: HudState): String = String.format("%.1f%%", hud.lossPct)

    /** 解码实际分辨率（未解码=—；取 VideoPipeline 解码输出值，非请求值） */
    fun resolutionLine(hud: HudState): String =
        if (hud.decodedWidth > 0 && hud.decodedHeight > 0) "${hud.decodedWidth}×${hud.decodedHeight}" else "—"

    /** 线上路径徽章（lan-direct/p2p/frp，p2p 回落后为 P2P→FRP） */
    fun wireBadge(hud: HudState): String = when (hud.wireKind) {
        TransportKind.LAN_DIRECT -> "LAN"
        TransportKind.P2P -> "P2P"
        TransportKind.FRP -> "FRP"
        TransportKind.P2P_FALLBACK_FRP -> "P2P→FRP"
        null -> "—"
    }

    /** 加密徽章（AES-128-GCM 协商成功=「加密」，否则「明文」） */
    fun cryptoBadge(hud: HudState): String = if (hud.encrypted) "加密" else "明文"

    /** 胶囊整行文案（第一行指标，第二行徽章） */
    fun capsuleLines(hud: HudState): List<String> = listOf(
        "${fpsLine(hud)} · ${bitrateLine(hud)} · ${rttLine(hud)} · 丢${lossLine(hud)} · ${resolutionLine(hud)}",
        "${wireBadge(hud)} · ${cryptoBadge(hud)}",
    )
}

/** 底部工具坞动作 */
enum class DockAction(val label: String) {
    KEYBOARD("键盘"),
    SCREENSHOT("截屏"),
    PRESET_CYCLE("档位"),
    ROTATE("旋转"),
    SINGLE_APP("单应用"),
    DISCONNECT("断开"),
}

/** 工具坞置灰逻辑：键盘按钮受 AuxNotice 驱动置灰，其余恒可用（AuxNotice 非静默失败 → UI 置灰+原因） */
object DockLogic {
    /** 各动作可用性（[aux] 为辅助通道 UI 状态） */
    fun enabledActions(aux: AuxUiState): Map<DockAction, Boolean> = mapOf(
        DockAction.KEYBOARD to (aux.textInputAvailable != false),
        DockAction.SCREENSHOT to true,
        DockAction.PRESET_CYCLE to true,
        DockAction.ROTATE to true,
        DockAction.SINGLE_APP to true,
        DockAction.DISCONNECT to true,
    )

    /** 键盘按钮置灰原因（AuxNotice 携带，非空即置灰并提示） */
    fun keyboardDisabledReason(aux: AuxUiState): String? =
        if (aux.textInputAvailable == false) {
            aux.textInputUnavailableReason.ifBlank { "文字注入不可用" }
        } else null
}

/** 虚拟导航键：BACK 可用（右键/边缘手势桌面等效），HOME/RECENTS 占位禁用（对齐 Android 端现状） */
object NavLogic {
    data class NavButton(val label: String, val enabled: Boolean)

    val buttons: List<NavButton> = listOf(
        NavButton("◁ BACK", enabled = true),
        NavButton("○ HOME", enabled = false),
        NavButton("□ RECENTS", enabled = false),
    )
}

/** 回落通告条投影（notices 非空即显示第一条；spec：回落等降级通告显式呈现，非静默） */
object NoticeLogic {
    fun bannerText(notices: List<String>): String? = notices.firstOrNull { it.isNotBlank() }
}

/** 文字输入面板 ACK 状态文案（spec：ACK 对账 5s，状态非静默） */
object TextInputStatus {
    fun text(result: TextInputResult?): String = when (result) {
        null -> "就绪（Ctrl+Shift+K 唤起）"
        is TextInputResult.Ok -> "注入成功"
        is TextInputResult.Failed -> "注入失败：${result.reason}"
        TextInputResult.Timeout -> "超时未确认（源端未回 ACK）"
    }
}

/**
 * 键盘直发路由（spec「鼠标与键盘输入映射」：会话窗口获得键盘焦点时，可打印字符键
 * SHALL 经 TEXT_INPUT 直发——等效文字面板语义；协议无独立按键通道，非字符键
 * （功能键/修饰键）SHALL NOT 产生注入；HOME/RECENTS 保持占位禁用不变）。
 */
object KeyTextRouter {
    /**
     * 键盘事件 utf16CodePoint → 直发文本；不可打印（功能键/修饰键按下事件的
     * CHAR_UNDEFINED、Enter/Escape/Ctrl 组合等控制字符、私有区码位）返回 null（不注入）。
     * 判别依据：Compose Desktop（CMP 1.6.x）把 AWT KEY_TYPED 映射为 type=Unknown 且
     * utf16CodePoint=keyChar 的按键事件，可打印性过滤即字符键与非字符键的判别器。
     */
    fun typedText(codePoint: Int): String? = KeyboardText.printableText(codePoint)

    /**
     * 直发门控：文字面板打开（面板自身承载输入，防同击键双注入）或文字注入已降级
     * （AuxNotice 置灰，textInputAvailable == false）时暂停直发。
     */
    fun directSendAllowed(textPanelOpen: Boolean, textInputAvailable: Boolean?): Boolean =
        !textPanelOpen && textInputAvailable != false
}

/** 音量 popover 文案（0..1 → 0..100%） */
object VolumeUi {
    fun percentText(volume: Double): String = "${(volume.coerceIn(0.0, 1.0) * 100).roundToInt()}%"
}

/** 截屏保存（当前帧 PNG 落盘；返回保存路径或 null） */
object ScreenshotSaver {
    val defaultDir: Path by lazy {
        val home = System.getProperty("user.home") ?: "."
        Paths.get(home, "Pictures", "cloudphone")
    }

    /** 保存 BGRA 帧到 PNG（[dir] 可注入便于测试）；返回文件路径 */
    fun saveFrame(bgra: ByteArray, width: Int, height: Int, dir: Path = defaultDir): Path? {
        return try {
            Files.createDirectories(dir)
            val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            // FFmpeg BGRA 内存序 → ARGB int（逐像素 setRGB，PNG 落盘与显示路径解耦）
            var off = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (off + 4 > bgra.size) break
                    val b = bgra[off].toInt() and 0xFF
                    val g = bgra[off + 1].toInt() and 0xFF
                    val r = bgra[off + 2].toInt() and 0xFF
                    val a = bgra[off + 3].toInt() and 0xFF
                    image.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
                    off += 4
                }
            }
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
            val file = dir.resolve("screenshot-$ts.png")
            if (!ImageIO.write(image, "png", file.toFile())) return null
            file
        } catch (_: Exception) {
            null
        }
    }
}

// ═══════════════ Compose UI ═══════════════

/** 会话控制台根组件 */
@Composable
fun SessionScreen(
    controller: SessionController,
    aux: AuxUiState,
    notices: List<String>,
    bitmap: androidx.compose.ui.graphics.ImageBitmap?,
    videoWidth: Int,
    videoHeight: Int,
    volume: Double,
    onVolumeChange: (Double) -> Unit,
    textResult: TextInputResult?,
    onSendText: (String) -> Unit,
    onScreenshot: () -> Unit,
    onCyclePreset: () -> Unit,
    onRotate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var drawerOpen by remember { mutableStateOf(false) }
    var textPanelOpen by remember { mutableStateOf(false) }
    var volumeOpen by remember { mutableStateOf(false) }
    var textDraft by remember { mutableStateOf("") }
    var rotated by remember { mutableStateOf(false) }

    // 视口键盘焦点（spec「鼠标与键盘输入映射」：会话窗口获得键盘焦点时，可打印字符键
    // 经 TEXT_INPUT 直发——等效文字面板语义；非字符键不注入）。点击画面夺取焦点；
    // 文字面板关闭后焦点回到画面（直发恢复），面板打开期间由面板承载输入、不直发。
    val canvasFocus = remember { FocusRequester() }
    LaunchedEffect(textPanelOpen) { if (!textPanelOpen) canvasFocus.requestFocus() }

    Box(
        modifier.fillMaxSize().background(DesktopBg)
            .focusRequester(canvasFocus)
            .clickable(onClickLabel = "画面聚焦键盘") { canvasFocus.requestFocus() }
            .onPreviewKeyEvent { e ->
                val text = KeyTextRouter.typedText(e.utf16CodePoint)
                if (text != null && KeyTextRouter.directSendAllowed(textPanelOpen, aux.textInputAvailable)) {
                    onSendText(text)
                    true
                } else {
                    false
                }
            },
    ) {
        // letterbox 视频画布
        VideoCanvas(bitmap = bitmap, videoWidth = videoWidth, videoHeight = videoHeight, rotated = rotated)

        // HUD 胶囊（顶部居中）
        val hud by controller.hud.collectAsState()
        Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) {
            HudCapsule(hud)
        }

        // 回落通告条
        NoticeLogic.bannerText(notices)?.let { text ->
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 52.dp)
                    .background(SemanticWarn.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(text, color = SemanticWarn, fontSize = 12.sp)
            }
        }

        // 视口内鼠标输入（左键三段 + 右键 BACK + 滚轮小步）接线：
        // 由 AppWindow 层 AWT 监听注入（SessionController.sendInput），此处保持画布纯渲染。

        // 底部工具坞
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                .background(DesktopSurfaceHigh, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val enabledMap = DockLogic.enabledActions(aux)
            DockAction.entries.forEach { action ->
                val enabled = enabledMap[action] ?: true
                DockButton(
                    label = action.label,
                    enabled = enabled,
                ) {
                    when (action) {
                        DockAction.KEYBOARD -> textPanelOpen = !textPanelOpen
                        DockAction.SCREENSHOT -> onScreenshot()
                        DockAction.PRESET_CYCLE -> onCyclePreset()
                        DockAction.ROTATE -> { rotated = !rotated; onRotate() }
                        DockAction.SINGLE_APP -> Unit // 单应用入口经会话抽屉（ AppsScreen 3.4 承接）
                        DockAction.DISCONNECT -> controller.disconnect()
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            // 音量（popover）
            Box {
                DockButton(label = VolumeUi.percentText(volume), enabled = true) { volumeOpen = !volumeOpen }
                if (volumeOpen) {
                    VolumePopover(
                        volume = volume,
                        onChange = onVolumeChange,
                        onDismiss = { volumeOpen = false },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            DockButton(label = "菜单", enabled = true) { drawerOpen = !drawerOpen }
        }

        // 虚拟导航键（右下：BACK 可用，HOME/RECENTS 禁用占位）
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            NavLogic.buttons.forEach { b ->
                Box(
                    Modifier.background(if (b.enabled) DesktopSurface else DesktopBg, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(b.label, color = if (b.enabled) DesktopText else DesktopTextDim, fontSize = 11.sp,
                        modifier = if (b.enabled) Modifier.clickable { onBack() } else Modifier)
                }
            }
        }

        // 文字输入面板（ACK 状态行）
        if (textPanelOpen) {
            Column(
                Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp)
                    .background(DesktopSurfaceHigh, RoundedCornerShape(8.dp)).padding(10.dp).width(320.dp),
            ) {
                Text("文字输入（组合文本注入）", color = DesktopText, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).background(DesktopBg, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp)) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = textDraft,
                            onValueChange = { textDraft = it },
                            textStyle = androidx.compose.ui.text.TextStyle(color = DesktopText, fontSize = 13.sp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.background(AccentBlue, RoundedCornerShape(6.dp))
                            .clickable(onClickLabel = "发送") { if (textDraft.isNotBlank()) { onSendText(textDraft); textDraft = "" } }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    ) { Text("发送", color = DesktopText, fontSize = 12.sp) }
                }
                Spacer(Modifier.height(4.dp))
                Text(TextInputStatus.text(textResult), color = DesktopTextDim, fontSize = 11.sp)
                DockLogic.keyboardDisabledReason(aux)?.let { reason ->
                    Text("已置灰：$reason", color = SemanticWarn, fontSize = 11.sp)
                }
            }
        }

        // 会话抽屉（挂起/恢复/断开）
        if (drawerOpen) {
            SessionDrawer(
                controller = controller,
                onDismiss = { drawerOpen = false },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/** HUD 胶囊（两行：指标行 + 徽章行） */
@Composable
private fun HudCapsule(hud: HudState) {
    val lines = HudFormatter.capsuleLines(hud)
    Column(
        Modifier.background(Color(0xB0141922), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(lines[0], color = DesktopText, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val badges = lines[1].split(" · ")
            badges.forEach { badge ->
                val color = when {
                    badge.contains("加密") -> SemanticOk
                    badge.contains("明文") -> SemanticWarn
                    badge.contains("FRP") && badge.contains("→") -> SemanticWarn
                    else -> AccentCyan
                }
                Text(badge, color = color, fontSize = 11.sp)
            }
        }
    }
}

/** letterbox Canvas：视频帧按比例居中绘制（黑边由窗口背景呈现） */
@Composable
private fun VideoCanvas(bitmap: androidx.compose.ui.graphics.ImageBitmap?, videoWidth: Int, videoHeight: Int, rotated: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val bmp = bitmap ?: return@Canvas
        if (videoWidth <= 0 || videoHeight <= 0) return@Canvas
        // 本地旋转：交换视频宽高做 letterbox，内容绕中心转 90°
        val (vw, vh) = if (rotated) videoHeight to videoWidth else videoWidth to videoHeight
        val scale = minOf(size.width / vw, size.height / vh)
        val w = (vw * scale).roundToInt()
        val h = (vh * scale).roundToInt()
        val left = ((size.width - vw * scale) / 2).roundToInt()
        val top = ((size.height - vh * scale) / 2).roundToInt()
        if (!rotated) {
            drawImage(
                image = bmp,
                srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                srcSize = androidx.compose.ui.unit.IntSize(bmp.width, bmp.height),
                dstOffset = androidx.compose.ui.unit.IntOffset(left, top),
                dstSize = androidx.compose.ui.unit.IntSize(w, h),
                filterQuality = FilterQuality.Medium,
            )
        } else {
            val pivot = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            rotate(degrees = 90f, pivot = pivot) {
                drawImage(
                    image = bmp,
                    srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                    srcSize = androidx.compose.ui.unit.IntSize(bmp.width, bmp.height),
                    dstOffset = androidx.compose.ui.unit.IntOffset(left, top),
                    dstSize = androidx.compose.ui.unit.IntSize(w, h),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}

/** 音量 popover（纵向滑条 + 百分比） */
@Composable
private fun VolumePopover(volume: Double, onChange: (Double) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.background(DesktopSurfaceHigh, RoundedCornerShape(8.dp)).padding(10.dp).width(120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(VolumeUi.percentText(volume), color = DesktopText, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.0, 0.5, 1.0).forEach { v ->
                Box(
                    Modifier.background(if (volume == v) AccentBlue else DesktopOutline, RoundedCornerShape(4.dp))
                        .clickable { onChange(v) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(if (v == 0.0) "静音" else if (v == 1.0) "最大" else "50%", color = DesktopText, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("关闭", color = DesktopTextDim, fontSize = 11.sp, modifier = Modifier.clickable { onDismiss() })
    }
}

/** 会话抽屉（挂起/恢复/断开 + 阶段显示） */
@Composable
private fun SessionDrawer(controller: SessionController, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val phase by controller.phase.collectAsState()
    Column(
        modifier.width(220.dp).background(DesktopSurfaceHigh).padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("会话", color = DesktopText, fontSize = 14.sp)
            Text("关闭", color = DesktopTextDim, fontSize = 12.sp, modifier = Modifier.clickable { onDismiss() })
        }
        Spacer(Modifier.height(4.dp))
        Text("阶段：${phaseLabel(phase)}", color = DesktopTextDim, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        if (phase == SessionPhase.SUSPENDED) {
            DrawerAction("恢复") { controller.resumeSession() }
        } else {
            DrawerAction("挂起", enabled = phase == SessionPhase.STREAMING) { controller.suspendSession() }
        }
        Spacer(Modifier.height(6.dp))
        DrawerAction("断开连接") { controller.disconnect(); onDismiss() }
    }
}

/** 阶段展示名 */
private fun phaseLabel(phase: SessionPhase): String = when (phase) {
    SessionPhase.IDLE -> "未连接"
    SessionPhase.CONNECTING -> "连接中"
    SessionPhase.HANDSHAKING -> "握手中"
    SessionPhase.STREAMING -> "串流中"
    SessionPhase.SUSPENDED -> "已挂起（控制链路保持）"
    SessionPhase.DISCONNECTED -> "已断开"
    SessionPhase.FAILED -> "失败"
}

@Composable
private fun DrawerAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .background(if (enabled) DesktopOutline else DesktopBg, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClickLabel = label) { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) DesktopText else DesktopTextDim, fontSize = 13.sp)
    }
}

@Composable
private fun DockButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (enabled) DesktopOutline else DesktopBg, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClickLabel = label) { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, color = if (enabled) DesktopText else DesktopTextDim, fontSize = 12.sp)
    }
}

