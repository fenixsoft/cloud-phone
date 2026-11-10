# Windows 桌面串流客户端

<!-- knowledge: capability=windows-desktop-client rev=1
     sources=spec/changes/windows-desktop-client -->

云手机的 Windows 桌面观看端形态：与 Android 客户端同协议对等消费的 Kotlin + Compose Desktop 桌面应用，在 PC 上直接观看与操作源端。协议与传输零重写、经复合构建复用 protocol 模块，桌面侧自建解码渲染、鼠标键盘输入映射、三辅助通道、连接体验与会话编排（会话核心见[续 2](windows-desktop-client-2.md)，辅助通道见[续 3](windows-desktop-client-3.md)），并携带一条可重复的 Windows 实机冒烟验证链路。

## 规则

- 工程形态与协议复用：独立 Gradle 工程 `client-windows/`（Kotlin JVM 17 + Compose Desktop），经复合构建 `includeBuild("../protocol")` 以依赖坐标 cloudphone:protocol:0.1.0 消费本地 protocol/ 模块；协议消息、帧编解码、三传输后端、加密、统计全部复用，不 fork、不重实现任何协议/传输逻辑，桌面源码树不得出现协议包同名源文件。根构建图不加模块（client-windows 自成一体），protocol/、source/、client/ 既有代码零改动——Android 客户端不感知桌面端存在。
- 三传输后端与加密：lan-direct / p2p / frp 三后端经 protocol 模块选择接入；p2p 打洞失败（超时）自动回落 frp 建立会话，回落以界面通告条呈现并更新 HUD 后端徽章。加密经 enableCrypto(CryptoCodec) 启用（AES-128-GCM 片级 + HKDF 方向子钥 + 512 重放窗口由 protocol 保证），HUD 显示传输后端与加密状态徽章；token 错配呈现 REJECT_BAD_TOKEN 明确失败，不静默、不进入会话。
- 连接首页：呈现顶栏+网络徽章、扫描面板（进度 ≤10Hz 节流，扫描中/有发现/未发现三态）、发现卡片（连接/单应用入口）、档案卡片（连接/删除，探测无响应置灰、删除始终可用）与常驻新建连接抽屉；未发现时空态引导（原因说明+手动新建+重试扫描）。新建表单字段：名称（可选）/主机（独占一行）/TCP+UDP 端口并排（默认 22333,22334）/后端三选（默认 lan-direct）/token 密码态输入/高级选项折叠区。
- 档案持久化：档案存 `~/.cloudphone/profiles.json`（桌面用户主目录单文件 JSON），host+tcpPort+backend 去重（同键连接更新既有档案，不产生重复条目），连接成功自动入库，名称/档位偏好随档，重启应用后仍在；token 仅存本机文件且不出日志；扫描结果不写入档案。
- LAN 发现：以 java.net.NetworkInterface 推导 /24 候选（无 Android Context 依赖），对候选地址 TCP 22333 仅 connect 探测不握手（单址 300ms、并发 64、总时长 10s、host+port 去重），扫描进度 ≤10Hz 节流呈现，≤10s 内给出发现结果。
- 档位选择与校验：档位 跟随本机/720P/1080P/自定义 × 30/60fps × 4/8/16/20Mbps 全矩阵可选，默认 跟随本机/60fps/8Mbps；自定义本地校验 width 320–2560 / height 240–2560 且 aspect-exact（宽高比与跟随本机目标一致），校验失败表单内联报错且不发 START_SESSION。
- 桌面形态与快捷键：自定义窗口标题栏（最小化/关闭）与全屏模式；快捷键 F11 全屏、Ctrl+Shift+X 断开回首页、Ctrl+Shift+K 唤起文字输入面板；沿用深色控制台主题。
- 打包与文档：jpackage 打包配置常备（Windows 侧产出 Msi/Exe 安装包，Linux 构建机以 createDistributable 产 app-image 验证配置正确性）；用户手册 docs/windows-client.md 覆盖 JRE 17 要求、软件渲染回退参数（VM 无 GPU 用 -Dskiko.renderApi=SOFTWARE）、防火墙出站规则（lan-direct 纯出站 TCP 为主验收路径）、档案文件位置、打包步骤与 VM 冒烟验证步骤。
- Windows 实机冒烟验证链路：scripts/win_vm_smoke.sh 在宿主构建客户端产物、分发进 dockur/windows VM、以软件渲染回退启动、经 RDP/QEMU monitor 分阶段截图取证（首页渲染/连接建立/视频上屏/断开回首页）；链路源端为宿主 Android 模拟器（slirp 网关 10.0.2.2 映射宿主 adb forward 端口）。脚本第一步显式探测源端可达性，不可达时输出探测结果与修复预案（dockur 端口映射直连或宿主 socat 中转）并以非 0 退出码收尾，禁止静默失败。

## 代码锚点

- 主实现：client-windows/settings.gradle.kts、client-windows/build.gradle.kts（复合构建与依赖锁定）、client-windows/src/main/kotlin/cloudphone/desktop/Main.kt（窗口入口与软渲染回退）、client-windows/src/main/kotlin/cloudphone/desktop/ui/HomeScreen.kt（连接首页/新建表单）
- 单源符号：client-windows/src/main/kotlin/cloudphone/desktop/profiles/DesktopSourceProfiles.kt（档案持久化）、client-windows/src/main/kotlin/cloudphone/desktop/discovery/DesktopLanScanner.kt（/24 探测）
- 消费方：client-windows/src/main/kotlin/cloudphone/desktop/AppWindow.kt（标题栏/全屏/快捷键）
- 守卫测试：client-windows/src/test/kotlin/cloudphone/desktop/SourceProfilesTest.kt（去重与入库时机）、client-windows/src/test/kotlin/cloudphone/desktop/LanScannerTest.kt（探测命中/排除）
- 脚本与手册：scripts/win_vm_smoke.sh、docs/windows-client.md

## 溯源

- 2026-11-10 windows-desktop-client（spec/changes/windows-desktop-client/）— 新建本页（主从分片 3 页）：确立桌面端工程形态与协议零 fork、三传输接入与加密呈现、连接首页/档案持久化/LAN 发现、档位校验、桌面形态与打包、Windows 实机冒烟验证链路规则。
