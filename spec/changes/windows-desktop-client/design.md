# Design: Windows 桌面串流客户端

## Context

CloudPhone 现有四个 Gradle 工程：`protocol/`（零 Android 依赖纯 Kotlin JVM 库：Messages/Framing + LanDirect/P2p/Frp 三后端 + CryptoCodec/Stats/Diagnostics/StunClient）、`source/`（Android 源端服务）、`client/`（Android 客户端，经 `includeBuild("../protocol")` 复合构建 + `implementation("cloudphone:protocol:0.1.0")` 依赖替换引用协议）、`source/loadgen`。用户需要 Windows 桌面形态客户端，功能与 Android 客户端全对齐。验收环境：用户自有 dockur/windows VM（宿主已有 `~/win-vm/` 操作指引，RDP 127.0.0.1:8889）。UI 真相源为本 change 原型（`specs/windows-desktop-client/prototype/`，plain 仿样层、视觉参照无约束力）。

知识库依据（起草时已核对）：protocol/stream-protocol-core（大端 C 兼容布局、HELLO/HELLO_ACK 500ms、REJECT_BAD_TOKEN、加密协商不 bump 版本、未知 MsgType 整段丢弃）、transport 三页（lan-direct RTT≤5ms / p2p 打洞增强链 / frp 112.74.85.74:22333 TCP+22334 UDP；AES-128-GCM 片级+HKDF 方向子钥+重放 512 窗口，探针 seq=0 恒明文）、aux-channels-m3（AAC-LC 48kHz stereo 96kbps 20ms 帧、jitter 120ms 起播/队列 32 帧、静音 3s 降 1fps keepalive、积压>800ms 清音频、TEXT_INPUT ACK 5s/CLIPBOARD 10s）、client 五能力页（latest-wins、触摸归一化 65535、档案去重、档位矩阵 320–2560 校验、AppListRequest 源端 1s 回传目标 + 客户端 3s 失败判定）。

## Goals / Non-Goals

**Goals**
- Windows 桌面客户端与 Android 客户端功能全对齐：三传输+加密、解码渲染、触摸等效输入、音频/剪贴板/文字注入、LAN 扫描、档案、档位、挂起恢复、单应用、HUD。
- 协议与传输零重写：`protocol/` 是唯一事实源，桌面端只消费不 fork。
- 一条可重复的 Windows 实机验证链路（构建产物 → VM 运行 → 截图取证），冒烟脚本 agent 可独立执行。
- jpackage 打包配置就绪，产出可分发的 Windows 安装包（在 Windows 机器上构建）。

**Non-Goals**
- 不改动 `protocol/`、`source/`、`client/` 任何现有代码与行为（Android 客户端不感知桌面端存在）。
- 不做 macOS/Linux 官方支持（Compose Desktop 天然跨平台但不承诺验收；Linux 上仅作为开发调试环境）。
- 不做硬件编码采集（桌面端只消费）；不做多点触控手势合成（右键=BACK 已覆盖桌面主场景）。
- 不做自动更新、多语言 i18n、深浅色主题切换（沿用深色控制台主题）。

## Decisions

### D1 工程形态：新顶层 Gradle 工程 `client-windows/`，Compose Desktop + 复合构建复用 protocol
`client-windows/` 独立 Gradle 工程（Kotlin JVM 17，settings 里 `includeBuild("../protocol")`，依赖 `implementation("cloudphone:protocol:0.1.0")` 经复合构建替换），完全复制 client/ 的引用模式。UI 层用 Compose Desktop（org.jetbrains.compose），声明式 UI 与原型布局一一对应。
- *备选*：JavaFX/Swing 手写布局（生态弱、与原型差距大）；Electron/其它语言（放弃 Kotlin 协议复用，违背单一事实源）。*理由*：Compose Desktop 是 Kotlin 官方桌面栈，与 protocol 同语言同工具链，协议消费零成本。

### D2 解码渲染：JavaCV(FFmpeg) H.264 软解 → Compose ImageBitmap，latest-wins 单消费者
视频片经 TransportBackend 到达后送 FFmpeg 解码（`javacv-platform` 按 classifier 只带 windows-x86_64 与 linux-x86_64，避免全平台数百 MB）；解码循环为单消费者协程：解码按序进行（保 P 帧参考链完整，对齐 client-decode-render 的 latest-wins 语义——Android 端是「解码照常、仅上屏最新帧」），上屏仅取最新帧、积压陈旧帧解码后丢弃并计数，积压过深时解码输入快进至下一关键帧边界（不在参考链中间丢帧，避免花屏），解出 RGBA 后转 `org.jetbrains.skia.Image`→`ImageBitmap` 交 Compose Canvas 绘制；视口按源分辨率 letterbox 居中（沿用 client-decode-render 语义），HUD 分辨率取解码实际值。
- *备选*：vlcj（拖整个 VLC 运行时）；ffmpeg CLI 子进程管道（进程管理/崩溃恢复脆弱）。*理由*：JavaCV 与 JVM 进程内线程模型契合，帧指针→位图拷贝一次即可，吞吐足够 1080P60。

### D3 音频下行：JavaCV AAC 解码 + javax.sound.sampled 播放，jitter 语义逐条对齐
AUDIO_CHUNK(ADTS) → FFmpeg AAC 解码为 PCM 16bit/48kHz/stereo → `SourceDataLine` 播放。jitter buffer 沿用 aux-channels-m3 参数：120ms 起播水位、队列 32 帧上限、控制通道积压>800ms 丢最旧音频帧、静音 3s 降 1fps keepalive（源端行为不变，桌面端只做对等消费）；音量控制在播放端做 PCM 缩放（popover 滑杆）。
- *备选*：Java Sound 直接喂 ADTS（JRE 不带 AAC 解码器，必死）；单独引 fobs4jmf 之类古董库（不维护）。*理由*：与 D2 共用 JavaCV，不增新依赖家族。

### D4 输入映射：鼠标/键盘 → INPUT_EVENT 桌面语义表
左键 press/move/release → InputAction DOWN/MOVE/UP（坐标归一化 65535，letterbox 视口内换算）；按住拖动即滑动；滚轮 → 连续小步 DOWN/UP 模拟滑动；**右键 = BACK**（Android 端边缘侧滑手势的桌面等效，复用 Android 客户端 sendRemoteBack 的注入路径）；键盘焦点时可打印字符键经 TEXT_INPUT 直发（协议 MsgType 无独立按键通道，非字符键不注入；scancode 全键盘映射列为后续增强）；Ctrl+Shift+K 唤起文字输入面板（ACK 对账 5s）。HOME/RECENTS 维持占位禁用（对齐 Android 端现状）。
- *备选*：Robot 本地注入（方向反了，桌面端是远端注入）；引入 scancode 全键盘映射 v1 就做（协议按键覆盖待定，列为 Non-Goal 的后续增强）。*理由*：先对齐 Android 已有能力，语义表直接映射现有 InputAction 枚举，协议零改动。

### D5 档案持久化：`~/.cloudphone/profiles.json`（用户目录 JSON 文件）
SourceProfiles 语义移植：host+tcpPort+backend 去重、连接成功自动入库、名称/档位偏好随档；存储从 SharedPreferences 换为用户主目录 JSON 文件（kotlinx-serialization 或 org.json，与 protocol 现有 JSON 习惯一致）；token 仅存本机文件、不出日志（对齐 source-profiles 隐私约束）。
- *备选*：java.util.prefs（Windows 落注册表，不可审查/难迁移）；工程目录内文件（多用户污染）。*理由*：一个可审查、可备份的单文件，行为与 Android 档案一一对应。

### D6 LAN 发现：LanScanner 逻辑移植，去掉 Context 依赖
`/24` 候选推导改用 `java.net.NetworkInterface`（Android 版仅用 Context 取连通性信息）；探测参数不变（单址 300ms、并发 64、总 10s、host+port 去重、结果不写档案）；扫描面板 UI 对齐原型（进度节流 ≤10Hz）。
- *理由*：纯 JVM 逻辑，唯一 Android 触点是 Context，桌面替换是纯删除。

### D7 会话编排：`SessionController` 状态机 + coroutines 桥接
Android 端 DecodeRenderActivity 内联的会话编排（连接→HELLO→START_SESSION→视频消费→输入回传→挂起/恢复/断开→档位循环）抽为桌面端独立 `SessionController`（挂起函数 + StateFlow 暴露 HUD 状态/会话状态/AuxNotice）；protocol 后端回调线程经 Channel 桥进协程。挂起/恢复/断开挂机、档位循环重发 START_SESSION 不断控制链、HUD 取解码实际值——语义逐条对齐 Android 端。
- *备选*：直接在 Composable 里管传输线程（状态撕裂、难以单测）。*理由*：编排逻辑可脱离 UI 单测（JVM 单测直接跑），UI 只做状态投影。

### D8 Windows 实机验证链路：构建产物入 VM + RDP/monitor 截图取证
`scripts/win_vm_smoke.sh`：宿主 `./gradlew :jar` → 产物经 docker cp 进 dockur/windows 容器共享目录 → VM 内安装/复用 JRE 17 → 启动客户端连宿主模拟器源端（slirp 网关 10.0.2.2 映射宿主环回，adb forward tcp:22333 已由 scripts/bootstrap_adb.sh 建立）→ QEMU monitor/xfreerdp 截图取证（首页渲染、连接建立、视频上屏、断开回首页）。脚本显式探测 10.0.2.2:22333 可达性，不可达时输出诊断而非静默失败。
- *备选*：仅 headless JVM 单测（验不了 Compose 渲染与真实传输）；手工 RDP 验收（不可重复、agent 无法独立执行）。*理由*：端到端链路复用既有 bootstrap 脚本，截图是用户验收环境的直接证据。

## Risks / Trade-offs

- [JavaCV 依赖体积大（FFmpeg 原生库数十 MB/平台）] → 按 classifier 只引入 windows-x86_64 + linux-x86_64；打包安装包体积写进文档预期。
- [dockur/windows slirp 网络对宿主环回的可达性未实测] → 冒烟脚本第一步显式探测 10.0.2.2:22333；不可达时预案：dockur 端口映射直连或宿主 socat 中转，文档记录两种路径。
- [VM 内无 GPU，Compose Desktop Direct3D 初始化失败] → 启动参数提供软件渲染回退（`-Dskiko.renderApi=SOFTWARE`），冒烟脚本带该参数启动。
- [FFmpeg AAC 解码器可用性随 bytedeco 构建而异] → D3 实现首个任务即验证 AAC 端到端；不可用则换用 decoder 明确指定（ffmpeg 内建 aac，预期可用）。
- [Windows 防火墙/权限拦截（出站 UDP 打洞、jpackage 需 WiX）] → docs/windows-client.md 列出出站规则与构建前置；lan-direct 纯出站 TCP 不受影响，是主验收路径。
- [协议对齐回归（桌面端消费姿势与 Android 不一致）] → protocol 既有 JVM 单测全量保留；新增桌面端 loopback 会话单测（本机起源桩跑 HELLO/START_SESSION/INPUT/AUX 往返断言）。
- [Compose Desktop 版本与 Kotlin 版本兼容矩阵] → 实现期第一任务在 libs.versions.toml 锁定实测组合，文档记录。

## Migration Plan

纯新增：新顶层目录 `client-windows/`、新脚本与文档；不触碰既有工程构建图（根 settings 不加模块，`client-windows` 自成一体）。回滚 = 删除 `client-windows/`、`scripts/win_vm_smoke.sh`、`docs/windows-client.md` 与本 change 的 spec 文件，零涟漪。用户数据（档案 JSON）落用户目录，不进仓库。

## Open Questions

- Compose Desktop / Kotlin 具体版本组合（实现期锁定并回填 docs）。
- JavaCV AAC 解码在目标 bytedeco 版本的可用性（实现期第一任务验证，备选路径已列）。
- VM 内 JRE 17 获取方式（Adoptium 在线下载 vs 宿主 docker cp 注入离线包）——按 VM 网络实测选择，冒烟脚本兼容两种。
