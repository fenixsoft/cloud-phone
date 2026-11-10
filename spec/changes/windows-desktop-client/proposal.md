# Proposal: Windows 桌面串流客户端

## Why

现有客户端只有 Android APK 形态：观看与操作「云手机」必须依赖第二台安卓设备。桌面用户（开发调试、PC 游戏、办公挂机）需要在自己的 Windows 机器上直接观看与操作源端。协议层 `protocol/` 已是零 Android 依赖的纯 Kotlin JVM 库（三传输后端 / 帧协议 / 加密 / 统计），Windows 端经复合构建直接复用即可获得与 Android 客户端同等的网络与协议能力，当前是补齐桌面端的最佳时机。

## What Changes

- 新增 Windows 桌面客户端工程 `client-windows/`（Kotlin + Compose Desktop，JVM 17，`includeBuild("../protocol")` 复用协议模块，协议零重写、单一事实源）。
- 新增桌面端解码渲染管线：FFmpeg(JavaCV) H.264 解码 → Compose/Skia 图像上屏，实现与 Android 端同语义的 latest-wins 渲染兜底与 letterbox 视口。
- 新增桌面端输入回传：鼠标（左键=触摸 down/move/up、拖拽=滑动、滚轮=滚动）与键盘按键经 INPUT_EVENT 回传；右键=BACK（对应 Android 端边缘侧滑手势语义）；文字输入走 TEXT_INPUT。
- 三辅助通道全量对齐：音频下行（JavaCV AAC 解码 + javax.sound PCM 播放、120ms 水位、静音零流量语义不变）、剪贴板双向同步、AuxNotice 降级呈现。
- 连接体验对齐：源端档案持久化（桌面 JSON 文件）、局域网 /24 扫描发现、三档分辨率/帧率/码率选择、连接成功自动入库与一键重连。
- 会话能力对齐：挂起/恢复/断开挂机、会话中档位循环切换（重发 START_SESSION 不断控制链）、单应用列表与单应用串流、实时 HUD 统计（fps/码率/RTT/丢包/分辨率/加密态）。
- 桌面专属形态：自定义窗口标题栏、全屏模式、快捷键（F11 全屏 / Ctrl+Shift+X 断开 / Ctrl+Shift+K 键盘面板）、jpackage 打包配置。
- 新增 Windows 实机验证链路：dockur/windows VM（RDP 8889 / QEMU monitor / 截图取证）上跑冒烟验证脚本，与宿主模拟器源端构成端到端。
- 界面以本 change 原型为 UI 真相源（`specs/windows-desktop-client/prototype/`）。

## Capabilities

### New Capabilities
- `windows-desktop-client`: Windows 桌面客户端全量能力——协议复用与复合构建、解码渲染与 latest-wins、鼠标/键盘输入映射、三传输后端与加密接入、三辅助通道、连接首页/控制台/单应用三界面、档案持久化与 LAN 发现、挂起恢复、档位切换、HUD 统计、jpackage 打包与 Windows 实机冒烟验证链路。

### Modified Capabilities

（无——本 change 不改动任何既有能力的需求；源端/协议/Android 客户端行为保持不变，Android 客户端与本客户端成为同协议的两种客户端形态。）

## Impact

- 新增 `client-windows/` Gradle 工程（唯一新顶层目录）：compose-desktop 依赖 + javacv-platform（FFmpeg）+ 复合构建引用 `../protocol`；不改动 `protocol/`、`source/`、`client/` 任何现有代码。
- 新增 `scripts/win_vm_smoke.sh`（Windows VM 冒烟：构建产物分发 + RDP 启动 + 截图取证）与 `docs/windows-client.md`（用户手册：JRE/打包/防火墙/VM 说明）。
- 新增桌面端持久化文件 `~/.cloudphone/profiles.json`（档案 token 仅存本机用户目录，语义对齐 source-profiles 的私有存储约束）。
- 新依赖仅落在 `client-windows/`：org.jetbrains.compose、javacv-platform（含 FFmpeg 各平台预编译）、kotlinx-coroutines。
- 验证环境：宿主 Linux 跑 JVM 单测 + natlab 式协议级 loopback；Windows VM（dockur/windows，RDP 127.0.0.1:8889）跑实机冒烟；源端为宿主 Android 模拟器（既有 bootstrap 链路，slirp 网关 10.0.2.2 可达宿主 adb forward 端口）。
