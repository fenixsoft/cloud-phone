package cloudphone.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloudphone.desktop.session.SessionParams
import cloudphone.protocol.WatchMode
import cloudphone.transport.BackendKind

/**
 * 连接首页（design D7）：扫描面板、发现卡片（整屏/单应用）、档案卡片（可置灰/删除）、
 * 新建连接抽屉表单。表单校验与状态投影均为纯函数（[HomeFormLogic]/[ProfileCard] 投影），
 * headless 单测覆盖；Compose 层只做渲染与回调。
 */

// ═══════════════ 纯逻辑（headless 可测） ═══════════════

/** 传输后端三选（默认 lan-direct） */
enum class HomeBackend(val id: String, val label: String) {
    LAN_DIRECT("lan-direct", "局域网直连"),
    P2P("p2p", "P2P 打洞"),
    FRP("frp", "FRP 中继");

    fun toKind(): BackendKind = when (this) {
        LAN_DIRECT -> BackendKind.LAN_DIRECT
        P2P -> BackendKind.P2P
        FRP -> BackendKind.FRP
    }

    companion object {
        fun fromId(id: String): HomeBackend = entries.firstOrNull { it.id == id } ?: LAN_DIRECT
    }
}

/** 档位选择（默认 跟随本机） */
enum class HomePreset(val label: String) {
    FOLLOW_LOCAL("跟随本机"),
    P720("720P"),
    P1080("1080P"),
    CUSTOM("自定义")
}

/** 新建连接表单状态（默认值 = spec：22333/22334、lan-direct、跟随本机/60fps/8Mbps、高级折叠） */
data class NewConnectionForm(
    val host: String = "",
    val tcpPortText: String = "22333",
    val udpPortText: String = "22334",
    val backend: HomeBackend = HomeBackend.LAN_DIRECT,
    val token: String = "",
    val advancedExpanded: Boolean = false,
    val preset: HomePreset = HomePreset.FOLLOW_LOCAL,
    val fps: Int = 60,
    val bitrateMbps: Int = 8,
    val encryptOn: Boolean = true,
    val customWidthText: String = "",
    val customHeightText: String = "",
    val customFpsText: String = "60",
)

/** 表单内联报错（字段级；null=无错。spec：校验失败表单内联报错，不发 START_SESSION） */
data class FormErrors(
    val host: String? = null,
    val tcpPort: String? = null,
    val udpPort: String? = null,
    val token: String? = null,
    val customWidth: String? = null,
    val customHeight: String? = null,
    val customFps: String? = null,
) {
    val hasErrors: Boolean
        get() = host != null || tcpPort != null || udpPort != null || token != null ||
            customWidth != null || customHeight != null || customFps != null
}

/** 表单校验与参数投影（纯函数） */
object HomeFormLogic {
    const val WIDTH_MIN = 320
    const val WIDTH_MAX = 2560
    const val HEIGHT_MIN = 240
    const val HEIGHT_MAX = 2560
    const val FPS_DEFAULT = 60

    /** 整数文本解析（空白/非数字 = null） */
    fun parseInt(text: String): Int? = text.trim().toIntOrNull()

    /** host 必填（IP 或主机名）；不含端口语义 */
    fun validateHost(host: String): String? =
        if (host.trim().isEmpty()) "主机地址必填" else null

    /** 端口校验（1–65535 整数） */
    fun validatePort(text: String): String? {
        val v = parseInt(text) ?: return "端口须为整数"
        if (v !in 1..65535) return "端口须在 1–65535"
        return null
    }

    /** token 必填（A2A 握手凭证） */
    fun validateToken(token: String): String? =
        if (token.isEmpty()) "token 必填" else null

    /**
     * 自定义档位本地校验（spec：width 320–2560 / height 240–2560 且 aspect-exact
     * —— 宽高比与「跟随本机」目标一致；[refWidth]/[refHeight] 为本机屏幕分辨率）。
     * 校验失败表单内联报错，不发 START_SESSION。
     */
    fun validateCustomPreset(
        widthText: String,
        heightText: String,
        fpsText: String,
        refWidth: Int,
        refHeight: Int,
    ): Triple<String?, String?, String?> {
        val w = parseInt(widthText)
        val h = parseInt(heightText)
        val fps = parseInt(fpsText)
        val wErr = when {
            w == null -> "宽度须为整数"
            w !in WIDTH_MIN..WIDTH_MAX -> "宽度须在 $WIDTH_MIN–$WIDTH_MAX"
            else -> null
        }
        val hErr = when {
            h == null -> "高度须为整数"
            h !in HEIGHT_MIN..HEIGHT_MAX -> "高度须在 $HEIGHT_MIN–$HEIGHT_MAX"
            else -> null
        }
        val fpsErr = when {
            fps == null -> "帧率须为整数"
            fps !in 20..120 -> "帧率须在 20–120"
            else -> null
        }
        // aspect-exact：宽高比与跟随本机目标一致（交叉相乘避免浮点误差）
        if (wErr == null && hErr == null && refWidth > 0 && refHeight > 0) {
            if (w!!.toLong() * refHeight != h!!.toLong() * refWidth) {
                return Triple(wErr, "宽高比须与本机屏幕一致（${refWidth}×$refHeight）", fpsErr)
            }
        }
        return Triple(wErr, hErr, fpsErr)
    }

    /** 整表校验（[localWidth]/[localHeight] = 本机屏幕尺寸，跟随本机档引用） */
    fun validate(form: NewConnectionForm, localWidth: Int, localHeight: Int): FormErrors? {
        val custom = if (form.preset == HomePreset.CUSTOM) {
            val (w, h, f) = validateCustomPreset(form.customWidthText, form.customHeightText, form.customFpsText, localWidth, localHeight)
            FormErrors(customWidth = w, customHeight = h, customFps = f)
        } else {
            FormErrors()
        }
        val errors = custom.copy(
            host = validateHost(form.host),
            tcpPort = validatePort(form.tcpPortText),
            udpPort = validatePort(form.udpPortText),
            token = validateToken(form.token),
        )
        return if (errors.hasErrors) errors else null
    }

    /**
     * 表单 → 会话参数（校验通过返回 null 否则返回错误；跟随本机=本机分辨率，
     * 720P=1280×720，1080P=1920×1080，自定义=表单值）。
     */
    fun resolveParams(form: NewConnectionForm, localWidth: Int, localHeight: Int, sessionId: Int = 0): SessionParams {
        val (width, height, fps) = when (form.preset) {
            HomePreset.FOLLOW_LOCAL -> Triple(localWidth.coerceAtLeast(1), localHeight.coerceAtLeast(1), form.fps)
            HomePreset.P720 -> Triple(1280, 720, form.fps)
            HomePreset.P1080 -> Triple(1920, 1080, form.fps)
            HomePreset.CUSTOM -> Triple(
                parseInt(form.customWidthText) ?: localWidth,
                parseInt(form.customHeightText) ?: localHeight,
                parseInt(form.customFpsText) ?: FPS_DEFAULT,
            )
        }
        return SessionParams(
            host = form.host.trim(),
            tcpPort = parseInt(form.tcpPortText) ?: 22333,
            udpPort = parseInt(form.udpPortText) ?: 22334,
            kind = form.backend.toKind(),
            token = form.token,
            encryptOn = form.encryptOn,
            mode = WatchMode.FULLSCREEN,
            packageName = "",
            sessionId = sessionId,
            width = width,
            height = height,
            fps = fps,
            bitrateKbps = form.bitrateMbps * 1000,
        )
    }
}

/** 发现卡片动作 */
enum class DiscoveredCardAction { CONNECT, CONNECT_SINGLE_APP }

/** 发现卡片投影（host+port + 已保存档案名 → 展示与动作可用性） */
data class DiscoveredCard(
    val host: String,
    val port: Int,
    /** 匹配到的已保存档案名（首页发现卡与档案匹配标「已保存」，DesktopSourceProfiles.findByHostPort） */
    val savedProfileName: String = "",
) {
    val title: String get() = "$host:$port"
    val savedLabel: String get() = if (savedProfileName.isBlank()) "未保存" else "已保存·$savedProfileName"
}

/** 档案卡片投影（置灰 = host/port 不完整不可连接；删除恒可用） */
data class ProfileCard(
    val name: String,
    val host: String,
    val tcpPort: Int,
    val udpPort: Int,
    val backend: String,
    val token: String,
    val originLabel: String,
    val presetLabel: String,
) {
    val connectable: Boolean
        get() = host.isNotBlank() && tcpPort in 1..65535 && backend.isNotBlank()

    companion object {
        /** 从档案投影（含 token——卡片动作直接发起连接；展示名不含 token） */
        fun from(name: String, host: String, tcpPort: Int, udpPort: Int, backend: String, token: String, originLabel: String, presetLabel: String): ProfileCard =
            ProfileCard(name, host, tcpPort, udpPort, backend, token, originLabel, presetLabel)
    }
}

/** 扫描面板状态投影 */
data class ScanPanelState(
    val scanning: Boolean = false,
    val probed: Int = 0,
    val total: Int = 0,
    val subnetLabel: String = "",
    val elapsedMs: Long = 0,
    val discovered: List<DiscoveredCard> = emptyList(),
) {
    /** 进度 0..1（无候选=0） */
    val progressFraction: Float get() = if (total <= 0) 0f else probed.toFloat() / total
    val progressText: String get() = "$probed / $total"
}

// ═══════════════ Compose UI ═══════════════

/** 首页根组件：扫描面板 + 发现/档案列表 + 新建抽屉 */
@Composable
fun HomeScreen(
    scan: ScanPanelState,
    profiles: List<ProfileCard>,
    form: NewConnectionForm,
    onFormChange: (NewConnectionForm) -> Unit,
    onToggleDrawer: () -> Unit,
    drawerVisible: Boolean,
    onScanToggle: () -> Unit,
    onDiscoveredAction: (DiscoveredCard, DiscoveredCardAction) -> Unit,
    onProfileConnect: (ProfileCard) -> Unit,
    onProfileDelete: (ProfileCard) -> Unit,
    onFormConnect: (() -> Unit)? = null,
    localWidth: Int,
    localHeight: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(DesktopBg)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            ScanPanel(scan = scan, onScanToggle = onScanToggle)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("已保存档案", color = DesktopTextDim, fontSize = 13.sp)
                Text("＋ 新建连接", color = AccentBlue, fontSize = 13.sp,
                    modifier = Modifier.clickable(onClickLabel = "新建连接") { onToggleDrawer() })
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                scan.discovered.forEach { card ->
                    DiscoveredCardView(card = card, onAction = { onDiscoveredAction(card, it) })
                    Spacer(Modifier.height(8.dp))
                }
                profiles.forEach { p ->
                    ProfileCardView(p = p, onConnect = { onProfileConnect(p) }, onDelete = { onProfileDelete(p) })
                    Spacer(Modifier.height(8.dp))
                }
                if (scan.discovered.isEmpty() && profiles.isEmpty()) {
                    Text("暂无发现与档案——点击右上角「＋ 新建连接」开始", color = DesktopTextDim, fontSize = 13.sp)
                }
            }
        }
        NewConnectionDrawer(
            visible = drawerVisible,
            form = form,
            onFormChange = onFormChange,
            onConnect = onFormConnect,
            localWidth = localWidth,
            localHeight = localHeight,
            onClose = onToggleDrawer,
        )
    }
}

/** 扫描面板：网段徽章 + 进度 + 开始/停止 */
@Composable
private fun ScanPanel(scan: ScanPanelState, onScanToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(DesktopSurface, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("局域网发现", color = DesktopText, fontSize = 14.sp)
                if (scan.subnetLabel.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(scan.subnetLabel, color = AccentCyan, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            if (scan.scanning) {
                Text("扫描中 ${scan.progressText}", color = DesktopTextDim, fontSize = 12.sp)
            } else if (scan.elapsedMs > 0) {
                Text("上次扫描 ${scan.discovered.size} 台 · ${scan.elapsedMs}ms", color = DesktopTextDim, fontSize = 12.sp)
            } else {
                Text("自动探测源端（/24 网段 TCP 探测，结果不写档案）", color = DesktopTextDim, fontSize = 12.sp)
            }
        }
        Box(
            Modifier.background(if (scan.scanning) DesktopOutline else AccentBlue, RoundedCornerShape(6.dp))
                .clickable(onClickLabel = if (scan.scanning) "停止扫描" else "开始扫描") { onScanToggle() }
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(if (scan.scanning) "停止" else "扫描", color = DesktopText, fontSize = 13.sp)
        }
    }
}

/** 发现卡片：整屏连接 + 单应用连接 */
@Composable
private fun DiscoveredCardView(card: DiscoveredCard, onAction: (DiscoveredCardAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(DesktopSurface, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(card.title, color = DesktopText, fontSize = 14.sp)
            Text(card.savedLabel, color = DesktopTextDim, fontSize = 12.sp)
        }
        CardButton("连接") { onAction(DiscoveredCardAction.CONNECT) }
        Spacer(Modifier.width(8.dp))
        CardButton("单应用") { onAction(DiscoveredCardAction.CONNECT_SINGLE_APP) }
    }
}

/** 档案卡片：置灰不可连（host/port 不完整）、删除恒可用 */
@Composable
private fun ProfileCardView(p: ProfileCard, onConnect: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(DesktopSurface, RoundedCornerShape(8.dp))
            .border(1.dp, DesktopOutline, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.name.ifBlank { "${p.host}:${p.tcpPort}" }, color = DesktopText, fontSize = 14.sp)
            Text(
                "${p.host}:${p.tcpPort} · ${p.backend} · ${p.originLabel}" +
                    if (p.presetLabel.isNotBlank()) " · ${p.presetLabel}" else "",
                color = DesktopTextDim,
                fontSize = 12.sp,
            )
        }
        if (p.connectable) {
            CardButton("连接") { onConnect() }
            Spacer(Modifier.width(8.dp))
        } else {
            Text("不可用", color = SemanticWarn, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
        }
        CardButton("删除") { onDelete() }
    }
}

/** 新建连接抽屉（右侧滑出）：基础项 + 高级折叠 + 自定义档位内联报错 */
@Composable
private fun NewConnectionDrawer(
    visible: Boolean,
    form: NewConnectionForm,
    onFormChange: (NewConnectionForm) -> Unit,
    onConnect: (() -> Unit)? = null,
    localWidth: Int,
    localHeight: Int,
    onClose: () -> Unit,
) {
    AnimatedVisibility(visible = visible) {
        val errors = HomeFormLogic.validate(form, localWidth, localHeight)
        Column(
            Modifier.fillMaxHeight().width(380.dp).background(DesktopSurfaceHigh)
                .verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("新建连接", color = DesktopText, fontSize = 16.sp)
                Text("关闭", color = DesktopTextDim, fontSize = 13.sp, modifier = Modifier.clickable { onClose() })
            }
            Spacer(Modifier.height(12.dp))
            FieldLabel("主机地址")
            FieldText(value = form.host, onChange = { onFormChange(form.copy(host = it)) })
            ErrorText(errors?.host)
            Spacer(Modifier.height(8.dp))
            // 端口并排（TCP/UDP）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("TCP 端口")
                    FieldText(value = form.tcpPortText, onChange = { onFormChange(form.copy(tcpPortText = it)) })
                    ErrorText(errors?.tcpPort)
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("UDP 端口")
                    FieldText(value = form.udpPortText, onChange = { onFormChange(form.copy(udpPortText = it)) })
                    ErrorText(errors?.udpPort)
                }
            }
            Spacer(Modifier.height(8.dp))
            FieldLabel("传输后端")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeBackend.entries.forEach { b ->
                    Box(
                        Modifier.background(if (form.backend == b) AccentBlue else DesktopOutline, RoundedCornerShape(6.dp))
                            .clickable { onFormChange(form.copy(backend = b)) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) { Text(b.label, color = DesktopText, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            FieldLabel("token")
            // 密码态：显示为掩码
            FieldText(value = form.token, onChange = { onFormChange(form.copy(token = it)) }, masked = true)
            ErrorText(errors?.token)
            Spacer(Modifier.height(12.dp))
            // 高级折叠（默认收起）
            Text(
                if (form.advancedExpanded) "▾ 高级选项" else "▸ 高级选项",
                color = AccentCyan, fontSize = 13.sp,
                modifier = Modifier.clickable { onFormChange(form.copy(advancedExpanded = !form.advancedExpanded)) },
            )
            AnimatedVisibility(visible = form.advancedExpanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    FieldLabel("档位")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomePreset.entries.forEach { preset ->
                            Box(
                                Modifier.background(if (form.preset == preset) AccentBlue else DesktopOutline, RoundedCornerShape(6.dp))
                                    .clickable { onFormChange(form.copy(preset = preset)) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) { Text(preset.label, color = DesktopText, fontSize = 12.sp) }
                        }
                    }
                    if (form.preset == HomePreset.CUSTOM) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                FieldLabel("宽")
                                FieldText(value = form.customWidthText, onChange = { onFormChange(form.copy(customWidthText = it)) })
                                ErrorText(errors?.customWidth)
                            }
                            Column(Modifier.weight(1f)) {
                                FieldLabel("高")
                                FieldText(value = form.customHeightText, onChange = { onFormChange(form.copy(customHeightText = it)) })
                                ErrorText(errors?.customHeight)
                            }
                            Column(Modifier.weight(1f)) {
                                FieldLabel("帧率")
                                FieldText(value = form.customFpsText, onChange = { onFormChange(form.copy(customFpsText = it)) })
                                ErrorText(errors?.customFps)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FieldLabel("帧率（跟随本机/720P/1080P）")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 60).forEach { fps ->
                            Box(
                                Modifier.background(if (form.fps == fps) AccentBlue else DesktopOutline, RoundedCornerShape(6.dp))
                                    .clickable { onFormChange(form.copy(fps = fps)) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) { Text("${fps}fps", color = DesktopText, fontSize = 12.sp) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FieldLabel("码率")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(4, 8, 16, 20).forEach { mbps ->
                            Box(
                                Modifier.background(if (form.bitrateMbps == mbps) AccentBlue else DesktopOutline, RoundedCornerShape(6.dp))
                                    .clickable { onFormChange(form.copy(bitrateMbps = mbps)) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) { Text("${mbps}M", color = DesktopText, fontSize = 12.sp) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "端到端加密：${if (form.encryptOn) "开" else "关"}",
                        color = if (form.encryptOn) SemanticOk else SemanticWarn, fontSize = 13.sp,
                        modifier = Modifier.clickable { onFormChange(form.copy(encryptOn = !form.encryptOn)) },
                    )
                }
            }
            // 连接按钮（校验失败置灰——内联报错已在字段旁呈现；null 回调 = 上层未接线时隐藏）
            if (onConnect != null) {
                Spacer(Modifier.height(16.dp))
                val errors = HomeFormLogic.validate(form, localWidth, localHeight)
                Box(
                    Modifier.fillMaxWidth()
                        .background(if (errors == null) AccentBlue else DesktopOutline, RoundedCornerShape(8.dp))
                        .clickable(enabled = errors == null, onClickLabel = "连接") { onConnect() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("连接", color = if (errors == null) DesktopText else DesktopTextDim, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = DesktopTextDim, fontSize = 12.sp)
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun FieldText(value: String, onChange: (String) -> Unit, masked: Boolean = false) {
    // 轻量输入态（卡片风格）；编辑器接入在 ViewModel 侧（form.copy）完成
    Box(Modifier.fillMaxWidth().background(DesktopBg, RoundedCornerShape(6.dp)).padding(8.dp)) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = androidx.compose.ui.text.TextStyle(color = DesktopText, fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) {
        Spacer(Modifier.height(2.dp))
        Text(error, color = SemanticDanger, fontSize = 12.sp)
    }
}

@Composable
private fun CardButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.background(DesktopOutline, RoundedCornerShape(6.dp))
            .clickable(onClickLabel = label) { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(label, color = DesktopText, fontSize = 12.sp)
    }
}
