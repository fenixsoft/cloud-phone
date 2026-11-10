# Tasks: Windows 桌面串流客户端

## 1. 工程脚手架与依赖锁定

- [x] 1.1 创建 `client-windows/` Gradle 工程：settings.gradle.kts（`includeBuild("../protocol")`）、build.gradle.kts（Kotlin JVM 17 + org.jetbrains.compose + javacv-platform 仅 windows-x86_64/linux-x86_64 classifier + kotlinx-coroutines + kotlinx-serialization-json（profiles.json 持久化用，对齐 D5））、gradle.properties、自有 gradle/libs.versions.toml 锁定 Compose/Kotlin 实测兼容组合；从 client/ 拷贝 gradle wrapper；验收=`./gradlew build` 绿（空 Main）
- [x] 1.2 `Main.kt` 应用骨架：窗口启动、`-Dskiko.renderApi=SOFTWARE` 软渲染回退参数文档化、深色主题占位；验收=headless 环境构建绿 + 启动入口可被单测冒烟（无显示环境跳过实际渲染）

## 2. 会话核心（协议消费，无 UI）

- [x] 2.1 `SessionController.kt`（client-windows/src/main/kotlin/cloudphone/desktop/session/SessionController.kt）会话状态机：连接→HELLO/START_SESSION→会话态（挂起/恢复/断开、档位循环重发 START_SESSION 不断链、HUD StateFlow、AuxNotice 上抛），protocol 后端回调经 Channel 桥接协程；验收=`SessionControllerTest.kt` 单测经本机源桩 loopback 断言 HELLO/START_SESSION/挂起恢复/档位切换/REJECT_BAD_TOKEN 路径
- [x] 2.2 `VideoPipeline.kt`：TransportBackend 视频片 → JavaCV FFmpeg H.264 解码 → RGBA → ImageBitmap；latest-wins 单消费者（解码按序保 P 帧参考链、上屏仅最新帧；逐级计数：接收片数/解码帧数/积压丢弃帧数/上屏帧数，经 StateFlow 供 HUD 与诊断；提供默认关闭的 debug 开关将原始 H.264 输入片 dump 到本地文件，供 VM 冒烟黑屏/花屏取证）；验收=`VideoPipelineTest.kt` 单测用 JavaCV 编码器合成样例 H.264 流，断言解码输出帧与积压丢弃计数
- [x] 2.3 `AudioSink.kt`：AUDIO_CHUNK(ADTS) → JavaCV AAC 解码 PCM 48kHz/stereo → AudioOutput 接口（javax.sound SourceDataLine 实现 + 测试用 null-sink），jitter 120ms 水位/队列 32 帧/积压>800ms 丢最旧；验收=`AudioSinkTest.kt` 单测合成 ADTS 断言解码 PCM 帧数与 jitter 丢弃/起播逻辑
- [x] 2.4 `InputMapper.kt`：视口坐标→归一化 65535（letterbox 映射、黑边防护）、左键 DOWN/MOVE/UP、拖动滑动、滚轮小步、右键=BACK 注入路径；验收=`InputMapperTest.kt` 纯函数单测覆盖映射边界与黑边
- [x] 2.5 `AuxBridge.kt`：CLIPBOARD_SET/GET/TEXT（10s 超时降级）、TEXT_INPUT{seq} ACK 对账（5s 超时）、AuxNotice→UI 状态；验收=`AuxBridgeTest.kt` mock transport 单测覆盖超时与回执路径
- [x] 2.6 `DesktopLanScanner.kt`（client-windows/src/main/kotlin/cloudphone/desktop/discovery/DesktopLanScanner.kt，LanScanner 桌面移植）：NetworkInterface 推导 /24、并发 TCP 探测（300ms/64/10s/去重/不写档案/进度 ≤10Hz）；验收=`LanScannerTest.kt` 单测对本机 ServerSocket 探测命中与超时排除
- [x] 2.7 `DesktopSourceProfiles.kt`（client-windows/src/main/kotlin/cloudphone/desktop/profiles/DesktopSourceProfiles.kt，SourceProfiles 档案移植）：`~/.cloudphone/profiles.json` 读写（host+tcpPort+backend 去重、连接成功自动入库、档位偏好），token 不落日志；验收=`SourceProfilesTest.kt` 单测临时目录覆盖去重与入库时机

## 3. Compose UI 三屏与桌面形态

- [x] 3.1 `AppWindow.kt` + `Theme.kt`：自定义标题栏（最小化/关闭）、全屏切换、快捷键 F11/Ctrl+Shift+X/Ctrl+Shift+K、深色控制台主题（bg #0b0e14/surface #141922/accent #4f8cff→#22d3ee）；验收=编译绿 + `AppWindow.kt` 快捷键/全屏状态逻辑单测
- [x] 3.2 `HomeScreen.kt`：扫描面板、发现卡片（连接/单应用）、档案卡片（置灰/删除）、新建抽屉表单（端口并排 22333,22334、后端三选默认 lan-direct、token 密码态、高级折叠默认 跟随本机/60/8M）、自定义档位本地校验（width 320–2560/height 240–2560/aspect-exact，内联报错）；验收=编译绿 + `HomeScreen.kt` 表单校验与状态投影单测
- [x] 3.3 `SessionScreen.kt`：letterbox Canvas 渲染 VideoPipeline 帧、HUD 胶囊（fps/码率/RTT/丢包/解码实际分辨率/后端+加密徽章）、回落通告条、底部工具坞（键盘/截屏/档位循环/旋转本地/单应用/断开，AuxNotice 驱动置灰）、虚拟导航键（BACK 可用，HOME/RECENTS 占位禁用）、会话抽屉（挂起/恢复/断开）、文字输入面板（ACK 状态）、音量 popover、截屏保存当前帧；验收=编译绿 + `SessionScreen.kt` HUD 状态投影/工具坞置灰逻辑单测
- [x] 3.4 `AppsScreen.kt`：AppListRequest（1s 超时显示重试入口）、磁贴网格（图标占位+应用名、无包名）、名称搜索、单应用徽章与退出、磁贴固化桌面快捷方式（.lnk 经用户桌面目录）；验收=编译绿 + `AppsScreen.kt` 列表状态/重试逻辑单测

## 4. 打包与文档

- [x] 4.1 jpackage 打包配置：compose.desktop.application DSL（packageMsi for Windows、vendor/图标/主类），Linux 侧 `createDistributable` 可产出 app-image 验证配置正确；验收=`./gradlew createDistributable` 产物存在且 packageMsi 任务在任务列表可见
- [x] 4.2 docs/windows-client.md：构建/运行（JRE 17、软件渲染参数）、防火墙出站规则、档案文件位置、打包步骤、VM 冒烟验证说明、已知限制（HOME/RECENTS 占位等）；验收=文档存在且覆盖上述条目

## 5. Windows 实机冒烟链路（脚本交付）

- [x] 5.1 scripts/win_vm_smoke.sh：网络可达性显式探测（10.0.2.2:22333，不可达输出诊断+预案非 0 退出）、宿主构建产物分发进 dockur/windows 容器、VM 内软件渲染启动、RDP/QEMU monitor 截图取证（首页/连接/视频上屏/断开）、`--self-check` 无 VM 自检模式；验收=宿主执行 `--self-check` 绿（无 VM 环境自检通过），实际 VM 端到端在验收阶段执行并按 docs 记录

## Deliverables

```yaml
- path: client-windows/settings.gradle.kts
  kind: new
- path: client-windows/build.gradle.kts
  kind: new
- path: client-windows/gradle.properties
  kind: new
- path: client-windows/gradle/libs.versions.toml
  kind: new
- path: client-windows/gradlew
  kind: new
- path: client-windows/gradle/wrapper/gradle-wrapper.properties
  kind: new
- path: client-windows/gradle/wrapper/gradle-wrapper.jar
  kind: new
- path: client-windows/src/main/kotlin/cloudphone/desktop/Main.kt
  kind: new
- path: client-windows/src/main/kotlin/cloudphone/desktop/AppWindow.kt
  kind: new
- path: client-windows/src/main/kotlin/cloudphone/desktop/session/SessionController.kt
  kind: new
- path: client-windows/src/main/kotlin/cloudphone/desktop/session/VideoPipeline.kt
  kind: new
- path: client-windows/src/main/kotlin/cloudphone/desktop/session/AudioSink.kt
  kind: new
- path: client-windows/src/main/kotlin/cloudphone/desktop/session/InputMapper.kt
  kind: new
- path: client-windows/src/main/kotlin/cloudphone/desktop/aux/AuxBridge.kt
  kind: new
- path: client-windows/src/main/kotlin/cloudphone/desktop/discovery/DesktopLanScanner.kt
  kind: new
- path: client-windows/src/main/kotlin/cloudphone/desktop/profiles/DesktopSourceProfiles.kt
  kind: new
- path: client-windows/src/main/kotlin/cloudphone/desktop/ui/HomeScreen.kt
  kind: new
- path: client-windows/src/main/kotlin/cloudphone/desktop/ui/SessionScreen.kt
  kind: new
- path: client-windows/src/main/kotlin/cloudphone/desktop/ui/AppsScreen.kt
  kind: new
- path: client-windows/src/main/kotlin/cloudphone/desktop/ui/Theme.kt
  kind: new
- path: client-windows/src/test/kotlin/cloudphone/desktop/SessionControllerTest.kt
  kind: new
- path: client-windows/src/test/kotlin/cloudphone/desktop/VideoPipelineTest.kt
  kind: new
- path: client-windows/src/test/kotlin/cloudphone/desktop/AudioSinkTest.kt
  kind: new
- path: client-windows/src/test/kotlin/cloudphone/desktop/InputMapperTest.kt
  kind: new
- path: client-windows/src/test/kotlin/cloudphone/desktop/AuxBridgeTest.kt
  kind: new
- path: client-windows/src/test/kotlin/cloudphone/desktop/LanScannerTest.kt
  kind: new
- path: client-windows/src/test/kotlin/cloudphone/desktop/SourceProfilesTest.kt
  kind: new
- path: scripts/win_vm_smoke.sh
  kind: new
- path: docs/windows-client.md
  kind: new
- path: specs/windows-desktop-client/prototype/index.html
  kind: reference
- path: specs/windows-desktop-client/prototype/session.html
  kind: reference
- path: specs/windows-desktop-client/prototype/apps.html
  kind: reference
```

## 验证修复（原地打回第 1 轮）

### 上轮实现结论（自动携带，来自上一轮 implementer 收工结论）

Windows 桌面串流客户端 SPEC 全部 16 项任务实现完毕：client-windows/ Gradle 工程（Kotlin JVM 17 + Compose Desktop 1.6.11 + JavaCV FFmpeg 6.1.1，protocol 复合构建）；2.x 会话核心（SessionController 状态机/VideoPipeline latest-wins 软解/AudioSink 水位丢帧/InputMapper letterbox 归一化/AuxBridge 超时对账/DesktopLanScanner /24 发现/DesktopSourceProfiles 档案）；3.x 三屏与桌面形态（AppWindow 快捷键+标题栏/HomeScreen 表单校验/SessionScreen HUD 与工具坞/AppsScreen 列表与 .lnk 固化）；4.1 jpackage（createDistributable app-image 产出、packageMsi 可见）；4.2 docs/windows-client.md；5.1 win_vm_smoke.sh --self-check 绿。模块内 98 例测试全绿，全量回归经 bin/run_full_suite.py 有界入口执行（6 pass/0 fail，证据 .fenix/verify-evidence/windows-desktop-client/）。tasks.md 16 项已全部勾选，与本文件 completed_tasks 1:1 一致。本轮为重派收口：复跑 client-windows 单测全绿（HEAD 与上轮验证内容一致、树干净），并修正 findings 为对象数组（上轮字符串数组被产物硬门拒收）。

上轮登记的 findings：
- deliverable-dispute: spec/changes/windows-desktop-client/tasks.md 任务 3.4 行「AppListRequest（1s 超时显示重试入口）」 — 规划层校准任务 3.4 文本为「客户端 3s 失败判定显示重试入口」；实现以 spec.md 权威 3s 判定期为验收基准，无需代码改动

- [ ] 先复核失败有效性：逐条核对 `spec/changes/windows-desktop-client/verification-report.md` 中失败场景的期望断言（状态字面量/选择器/前置数据）与产品实际行为是否相符——断言与产品不符属场景缺陷，修场景文件；确属产品行为不符才修产品代码
- [ ] 按复核结论修复：场景缺陷改场景文件，真实产品 Bug 改产品代码
- [ ] 修复后重跑验证确认通过
