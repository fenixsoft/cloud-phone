package cloudphone.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.ImageBitmap
import cloudphone.desktop.aux.AuxBridge
import cloudphone.desktop.aux.AuxTransport
import cloudphone.desktop.aux.AuxUiState
import cloudphone.desktop.aux.TextInputResult
import cloudphone.desktop.discovery.DesktopLanScanner
import cloudphone.desktop.profiles.DesktopSourceProfiles
import cloudphone.desktop.profiles.SourceProfile
import cloudphone.desktop.session.AudioSink
import cloudphone.desktop.session.DecodedFrame
import cloudphone.desktop.session.FrameBitmapFactory
import cloudphone.desktop.session.JavaSoundAudioOutput
import cloudphone.desktop.session.SessionController
import cloudphone.desktop.session.SessionParams
import cloudphone.desktop.session.SessionPhase
import cloudphone.desktop.session.VideoPipeline
import cloudphone.desktop.ui.AppsLogic
import cloudphone.desktop.ui.AppsScreen
import cloudphone.desktop.ui.DiscoveredCard
import cloudphone.desktop.ui.DiscoveredCardAction
import cloudphone.desktop.ui.HomeBackend
import cloudphone.desktop.ui.HomeFormLogic
import cloudphone.desktop.ui.HomePreset
import cloudphone.desktop.ui.HomeScreen
import cloudphone.desktop.ui.NewConnectionForm
import cloudphone.desktop.ui.ProfileCard
import cloudphone.desktop.ui.ScanPanelState
import cloudphone.desktop.ui.ScreenshotSaver
import cloudphone.desktop.ui.SessionScreen
import cloudphone.protocol.AppEntry
import cloudphone.protocol.WatchMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.awt.Toolkit

/**
 * 应用根接线（3.x 三屏接入）：路由 HOME（连接首页）/ SESSION（会话控制台）/ APPS（单应用列表）。
 *
 * - HomeRoute：扫描面板 + 档案 + 新建连接表单 → resolveParams → SessionRoute；
 * - SessionRoute：SessionController + VideoPipeline + AudioSink + AuxBridge 全接线
 *   （视频帧上屏、音频播放、辅助消息出口、输入注入由 SessionScreen 链上完成）；
 * - 连接成功自动入库档案（origin=auto）；断开/失败回 HOME。
 */

/** 全局快捷键总线（AppWindow.onShortcut → AppRoot 消费；extraBufferCapacity=1 不阻塞键盘派发） */
class UiBus {
    /** Ctrl+Shift+X 断开连接 */
    val disconnect = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Ctrl+Shift+K 文字输入面板开关（计数触发：每次自增翻转一次面板） */
    val textPanel = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

/** 应用路由 */
enum class AppRoute { HOME, SESSION, APPS }

@Composable
fun AppRoot(
    bus: UiBus,
    softwareRender: Boolean = false,
    autotest: AutotestParams? = null,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    // --autotest：E2E 冒烟直连（等效表单手输后点连接，复用同一 SessionParams 路径；
    // VM 内 adb 转发无 UDP，强制 TCP 视频）
    val autoPending = autotest?.let {
        SessionParams(
            host = it.host,
            tcpPort = it.tcpPort,
            udpPort = 0,
            token = it.token,
            useUdpVideo = false,
        )
    }
    var pending by remember { mutableStateOf(autoPending) }
    var route by remember { mutableStateOf(if (autoPending != null) AppRoute.SESSION else AppRoute.HOME) }
    val p = pending
    if (p == null) {
        HomeRoute(onConnect = { params, singleApp ->
            pending = params
            route = if (singleApp) AppRoute.APPS else AppRoute.SESSION
        })
    } else {
        SessionRoute(
            params = p,
            bus = bus,
            route = route,
            onRoute = { route = it },
            onClosed = {
                pending = null
                route = AppRoute.HOME
            },
        )
    }
}

// ═══════════════ HOME（连接首页） ═══════════════

@Composable
private fun HomeRoute(onConnect: (SessionParams, Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val store = remember { DesktopSourceProfiles() }
    val scanner = remember { DesktopLanScanner() }
    var form by remember { mutableStateOf(NewConnectionForm()) }
    var drawerVisible by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(store.list().map { it.toCard() }) }
    var scan by remember { mutableStateOf(ScanPanelState()) }
    // 发现卡「单应用连接」意图：抽屉补 token 后连接即进 APPS
    var pendingSingleApp by remember { mutableStateOf(false) }
    // 本机分辨率（FOLLOW_LOCAL/自定义兜底；headless 探测失败兜底 1080P）
    val local = remember { runCatching { Toolkit.getDefaultToolkit().screenSize }.getOrNull() }
    val localWidth = local?.width ?: 1920
    val localHeight = local?.height ?: 1080

    fun refreshProfiles() {
        profiles = store.list().map { it.toCard() }
    }

    fun paramsOf(host: String, tcpPort: Int, udpPort: Int, backendId: String, token: String, encryptOn: Boolean, presetLabel: String): SessionParams {
        val (w, h) = when (presetLabel) {
            "720P" -> 1280 to 720
            "1080P" -> 1920 to 1080
            else -> localWidth to localHeight
        }
        return SessionParams(
            host = host,
            tcpPort = tcpPort,
            udpPort = udpPort,
            kind = HomeBackend.fromId(backendId).toKind(),
            token = token,
            encryptOn = encryptOn,
            width = w,
            height = h,
            fps = form.fps,
            bitrateKbps = form.bitrateMbps * 1000,
        )
    }

    HomeScreen(
        scan = scan,
        profiles = profiles,
        form = form,
        onFormChange = { form = it },
        onToggleDrawer = { drawerVisible = !drawerVisible },
        drawerVisible = drawerVisible,
        onScanToggle = {
            if (scan.scanning) {
                scanner.cancel()
            } else {
                scope.launch(Dispatchers.IO) {
                    val subnet = scanner.subnetLabels().firstOrNull().orEmpty()
                    scope.launch { scan = ScanPanelState(scanning = true, subnetLabel = subnet) }
                    scanner.scan(
                        onProgress = { probed, total ->
                            scope.launch { scan = scan.copy(probed = probed, total = total) }
                        },
                        onHostFound = { hit ->
                            scope.launch { scan = scan.copy(discovered = scan.discovered + DiscoveredCard(hit.host, hit.port)) }
                        },
                        onComplete = { hosts, elapsedMs ->
                            scope.launch {
                                scan = ScanPanelState(
                                    scanning = false,
                                    elapsedMs = elapsedMs,
                                    subnetLabel = subnet,
                                    discovered = hosts.map { DiscoveredCard(it.host, it.port) },
                                )
                            }
                        },
                    )
                }
            }
        },
        onDiscoveredAction = { card, action ->
            val single = action == DiscoveredCardAction.CONNECT_SINGLE_APP
            val saved = store.findByHostPort(card.host, card.port)
            if (saved != null) {
                onConnect(
                    paramsOf(saved.host, saved.tcpPort, saved.udpPort, saved.backend, saved.token, saved.encryptOn, saved.presetLabel),
                    single,
                )
            } else {
                // 无既有档案：预填 host/port 开抽屉补 token
                form = form.copy(host = card.host, tcpPortText = card.port.toString())
                pendingSingleApp = single
                drawerVisible = true
            }
        },
        onProfileConnect = { card ->
            onConnect(
                paramsOf(card.host, card.tcpPort, card.udpPort, card.backend, card.token, form.encryptOn, card.presetLabel),
                false,
            )
        },
        onProfileDelete = { card ->
            store.delete(card.host, card.tcpPort, card.backend)
            refreshProfiles()
        },
        onFormConnect = onFormConnect@{
            if (HomeFormLogic.validate(form, localWidth, localHeight) != null) return@onFormConnect
            val params = HomeFormLogic.resolveParams(form, localWidth, localHeight)
            store.recordConnected(
                host = form.host.trim(),
                tcpPort = params.tcpPort,
                udpPort = params.udpPort,
                backend = form.backend.id,
                token = form.token,
                encryptOn = form.encryptOn,
            )
            refreshProfiles()
            drawerVisible = false
            onConnect(params, pendingSingleApp)
            pendingSingleApp = false
        },
        localWidth = localWidth,
        localHeight = localHeight,
    )
}

private fun SourceProfile.toCard(): ProfileCard = ProfileCard.from(
    name = name,
    host = host,
    tcpPort = tcpPort,
    udpPort = udpPort,
    backend = backend,
    token = token,
    originLabel = originLabel,
    presetLabel = presetLabel,
)

// ═══════════════ SESSION + APPS（会话全接线） ═══════════════

@Composable
private fun SessionRoute(
    params: SessionParams,
    bus: UiBus,
    route: AppRoute,
    onRoute: (AppRoute) -> Unit,
    onClosed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember { SessionController() }
    val pipeline = remember { VideoPipeline() }
    val audio = remember { AudioSink(JavaSoundAudioOutput()) }
    val aux = remember { AuxBridge(AuxTransport { controller.sendControl(it) }) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var textResult by remember { mutableStateOf<TextInputResult?>(null) }
    var volume by remember { mutableStateOf(0.5) }
    var auxState by remember { mutableStateOf(AuxUiState()) }
    var notices by remember { mutableStateOf(controller.notices.value) }
    var panelToggleCount by remember { mutableStateOf(0) }
    var appsState by remember { mutableStateOf(AppsLogic.ListState()) }
    var appQuery by remember { mutableStateOf("") }
    // 会话生效参数：UDP 视频自动回退时切换为 TCP 并同步给单应用等后续 start 使用
    var activeParams by remember(params) { mutableStateOf(params) }
    var extraNotice by remember { mutableStateOf<String?>(null) }

    // 会话生命周期与全接线（一次性；controller.start 异步推进 CONNECTING→STREAMING）
    LaunchedEffect(controller, pipeline, audio, aux) {
        controller.onVideo = { pipeline.onVideoFrame(it) }
        controller.onAudioChunk = { audio.onAudioChunk(it.payload) }
        controller.onAuxControl = { aux.onControlMessage(it) }
        pipeline.start()
        audio.start()
        controller.start(activeParams)
        // UDP 视频自动回退：进入 STREAMING 后 8s 仍无解码帧（NAT/FRP 中继等环境 UDP 不可达），
        // 以 TCP 视频重启会话（仅一次；FRP/adb 转发场景 UDP 天然不通，音频/控制走 TCP 不受影响）
        if (activeParams.useUdpVideo) {
            controller.phase.filter { it == SessionPhase.STREAMING }.first()
            delay(8_000)
            if (pipeline.latestFrame.value == null) {
                extraNotice = "UDP 视频通道不可达（NAT/中继环境常见），已自动切换为 TCP 视频"
                activeParams = activeParams.copy(useUdpVideo = false)
                controller.start(activeParams)
            }
        }
        launch { pipeline.latestFrame.collect { f -> if (f != null) bitmap = FrameBitmapFactory.toImageBitmap(f) } }
        launch {
            pipeline.stats.collect { s ->
                if (s.decodedWidth > 0 && s.decodedHeight > 0) controller.reportDecodedSize(s.decodedWidth, s.decodedHeight)
            }
        }
        launch { aux.uiState.collect { auxState = it } }
        launch { controller.notices.collect { notices = it } }
        launch {
            controller.phase.collect { p ->
                if (p == SessionPhase.DISCONNECTED) {
                    pipeline.close()
                    audio.close()
                    onClosed()
                }
            }
        }
        launch { bus.disconnect.collect { controller.disconnect() } }
        launch { bus.textPanel.collect { panelToggleCount += 1 } }
    }
    DisposableEffect(Unit) {
        onDispose {
            controller.disconnect()
            pipeline.close()
            audio.close()
        }
    }

    // 单应用列表加载（spec：源端 1s 内回传；客户端 3s 无应答判定失败显示重试入口）
    fun loadApps() {
        appsState = AppsLogic.ListState(AppsLogic.Phase.LOADING)
        scope.launch {
            val msg = controller.requestAppList()
            appsState = if (msg != null) {
                AppsLogic.ListState(AppsLogic.Phase.LOADED, apps = msg.apps)
            } else {
                AppsLogic.ListState(AppsLogic.Phase.TIMEOUT, reason = "3s 内源端未回应用列表")
            }
        }
    }
    LaunchedEffect(route) {
        if (route == AppRoute.APPS && appsState.phase == AppsLogic.Phase.IDLE) loadApps()
    }

    when (route) {
        AppRoute.APPS -> AppsScreen(
            state = appsState.copy(apps = AppsLogic.filterApps(appsState.apps, appQuery)),
            query = appQuery,
            onQueryChange = { appQuery = it },
            onRetry = { loadApps() },
            onLaunch = { entry ->
                controller.start(
                    activeParams.copy(
                        mode = WatchMode.SINGLE_APP,
                        packageName = entry.packageName,
                        sessionId = 0,
                    ),
                )
                onRoute(AppRoute.SESSION)
            },
            onPin = { entry ->
                val exe = targetExePath()
                scope.launch(Dispatchers.IO) {
                    cloudphone.desktop.ui.DesktopShortcut.create(entry.label, entry.packageName, exe)
                }
            },
            onExitSingleApp = {
                controller.start(activeParams.copy(mode = WatchMode.FULLSCREEN, packageName = "", sessionId = 0))
                onRoute(AppRoute.SESSION)
            },
            singleAppLabel = singleAppLabelOf(activeParams),
        )
        else -> SessionScreen(
            controller = controller,
            aux = auxState,
            notices = notices + listOfNotNull(extraNotice),
            bitmap = bitmap,
            videoWidth = controller.hud.collectAsState().value.decodedWidth,
            videoHeight = controller.hud.collectAsState().value.decodedHeight,
            volume = volume,
            onVolumeChange = {
                volume = it
                audio.setVolume(it.toFloat())
            },
            textResult = textResult,
            onSendText = { text ->
                scope.launch { textResult = aux.sendTextInput(text) }
            },
            onScreenshot = {
                scope.launch { screenshotLatest(pipeline) }
            },
            onCyclePreset = {
                scope.launch { controller.cyclePreset(null) }
            },
            onRotate = { /* 本地旋转由 SessionScreen 内部状态处理（映射同步在其输入接线内） */ },
            onBack = { controller.disconnect() },
            textPanelToggleCount = panelToggleCount,
        )
    }
}

/** 当前参数的单应用徽章（单应用态显示目标应用名；整屏态空串） */
private fun singleAppLabelOf(params: SessionParams): String =
    if (params.mode == WatchMode.SINGLE_APP) params.packageName.substringAfterLast('.') else ""

/** 截屏：最新解码帧 BGRA→PNG 落盘（~/Pictures/cloudphone；无帧静默忽略） */
private suspend fun screenshotLatest(pipeline: VideoPipeline) {
    val f = pipeline.latestFrame.value ?: return
    kotlinx.coroutines.withContext(Dispatchers.IO) {
        ScreenshotSaver.saveFrame(f.bgra, f.width, f.height)
    }
}

/** 桌面快捷方式目标 exe（launch4j/jpackage 启动路径优先，退化为工作目录旁同名 exe） */
private fun targetExePath(): String {
    System.getProperty("jpackage.app-path")?.let { return it }
    val dir = System.getProperty("user.dir") ?: "."
    return java.nio.file.Paths.get(dir, "CloudPhoneDesktop.exe").toString()
}
