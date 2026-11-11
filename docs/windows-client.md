# CloudPhone Windows 桌面客户端（windows-desktop-client）

云手机 Windows 桌面串流客户端：LAN 直连 / P2P 打洞 / FRP 中继三后端，视频 H.264 软解码、
音频 AAC 软解码播放、鼠标键盘输入注入、剪贴板与组合文本辅助通道、局域网自动发现与源端档案。

## 1. 构建（Linux 构建机 / Windows 均可）

前置：**JDK 17**（`java -version` 应输出 17.x）；Gradle 走仓库自带 wrapper（8.7），无需本机安装。

```bash
cd client-windows
./gradlew test                 # 单元测试（FFmpeg natives 随依赖自动就位）
./gradlew createDistributable  # 产出 app-image（jpackage 目录形态，见 build/compose/binaries/main/app/）
```

复合构建：`client-windows/settings.gradle.kts` 经 `includeBuild("../protocol")` 直接消费
`protocol/` 模块，改协议后重新构建即可，无需发布。

## 2. 运行

### 2.1 正常桌面（有 GPU）

```bash
./gradlew run                                   # 开发期运行
build/compose/binaries/main/app/CloudPhoneDesktop/bin/CloudPhoneDesktop   # app-image 直接运行
```

### 2.2 VM / 无 GPU 环境（软件渲染，默认推荐路径）

dockur/windows 等 VM 无 GPU，Compose Desktop 的 Direct3D/Skia 后端初始化可能失败，
**必须**加软件渲染 JVM 参数启动：

```
java -Dskiko.renderApi=SOFTWARE -jar CloudPhoneDesktop.jar
```

app-image 形态：编辑 `bin/CloudPhoneDesktop.cfg` 在 `java-options` 追加
`-Dskiko.renderApi=SOFTWARE`。`scripts/win_vm_smoke.sh` 已内置该参数。

### 2.3 Windows 防火墙出站规则

客户端为纯出站 TCP/UDP（不监听入站端口）：

- 出站 TCP `22333`（默认源端控制端口，可在表单改）；
- 出站 UDP `22334`（视频流，可在表单改；关闭 UDP 视频则不使用）。

企业策略受限环境（首条命令确认策略，其次新建放行规则）：

```powershell
Get-NetFirewallProfile | Select-Object Name, DefaultOutboundAction
New-NetFirewallRule -DisplayName "CloudPhone Desktop Outbound" -Direction Outbound -Action Allow -Protocol TCP -RemotePort 22333
New-NetFirewallRule -DisplayName "CloudPhone Desktop Outbound UDP" -Direction Outbound -Action Allow -Protocol UDP -RemotePort 22334
```

## 3. 档案文件位置

源端连接档案（连接成功自动入库，含档位偏好）：

```
%USERPROFILE%\.cloudphone\profiles.json     （Windows）
~/.cloudphone/profiles.json                 （Linux 构建机调试时同路径）
```

- upsert 键 = `host + tcpPort + backend`，同键不产生重复档案；
- **token 明文仅存该文件**（用户目录，供 A2A 握手）；界面展示与日志恒不含 token；
- 删除档案即删除文件中对应条目；文件损坏视为空档案，下次保存自动重写。

## 4. 打包（Windows 实机执行）

```powershell
cd client-windows
.\gradlew.bat packageMsi    # 产出 build/compose/binaries/main/msi/CloudPhoneDesktop-0.1.0.msi
.\gradlew.bat packageExe    # 备选 exe 安装器
```

MSI 已配置：开始菜单组「CloudPhone」、桌面快捷方式、目录可选、按用户安装（免管理员）。
Linux 构建机上 `createDistributable` 产出 app-image 即验证了打包配置正确性
（Windows 侧 jpackage 实际产包在 Windows 实机执行）。

## 5. VM 冒烟验证（scripts/win_vm_smoke.sh）

宿主侧执行；无 VM 时用自检模式验证链路脚本本身：

```bash
./scripts/win_vm_smoke.sh --self-check   # 无 VM 自检（网络探测跳过、构建+单测+产物检查）
./scripts/win_vm_smoke.sh                # 完整链路：10.0.2.2:22333 可达性探测 → 产物分发 → VM 软件渲染启动 → 截图取证
```

完整链路步骤：

1. **网络可达性**：显式探测 `10.0.2.2:22333`（`10.0.2.2` 为 QEMU/容器 VM 视角的宿主网关地址，
   即宿主机上运行的源端），不可达输出诊断
   （源端未启动/端口被防火墙拦截/网段不匹配）与预案，非 0 退出；
2. **产物分发**：宿主 `createDistributable` app-image 拷入 dockur/windows 容器共享目录；
3. **VM 内启动**：以软件渲染参数启动客户端；
4. **截图取证**：经 RDP / QEMU monitor `screendump` 取首页/连接/视频上屏/断开四态截图，
   存 `docs/bootstrap-screenshots/windows-client/`。

实际 VM 端到端在验收阶段执行，结果按本节记录。

### 5.1 VM 端到端实测记录（dockur/windows，2026-11-11）

在 Linux 构建机上以 dockurr/windows 容器（Windows 11，KVM）为受控端、宿主 AVD
`emulator-5554`（cloudphone.source）为源端，完成便携包端到端验证。**结论：视频回传、
双向控制、输入注入全部打通。** 截图存 `docs/bootstrap-screenshots/windows-client/`。

拓扑与通道：

- 源端：`adb -s emulator-5554 shell am start-foreground-service -n cloudphone.source/.StreamService
  -a cloudphone.source.START_CAPTURE --ei width 1080 --ei height 1920 --ei fps 60 --ei bitrateKbps 8000`
  → "shared capture ready 1080x1920@60/8000kbps"；设备侧 LISTEN 0.0.0.0:22333。
- 网络：容器内 QEMU 用户网（VM=172.30.0.2），宿主侧 `socat TCP-LISTEN:22333,bind=172.18.0.1,fork`
  转发到 adb forward 的 127.0.0.1:22333；VM 内客户端目标 `172.18.0.1:22333`，
  token `cloudphone-m0`，`useUdpVideo=false`（adb forward 无 UDP）。
- 客户端部署：便携 zip 放 `/root`（=VM 内 `\\host.lan\Data`），经 Windows Startup 目录
  bat（离线 losetup+ntfs-3g 注入，见 §7.1）开机自动 `java -cp "lib/app/*"
  cloudphone.desktop.MainKt --software-render --autotest 172.18.0.1 22333` 免 GUI 连接。

实测结果（日志时间 2026-11-11）：

| 环节 | 证据 |
|---|---|
| 会话建立 | 01:11:02 recv Hello → StartSession → capture ready (unchanged) → audio capture started (AAC-LC 48k stereo 96kbps)；Ping/Pong 1s 节拍持续 |
| 视频镜像 | 源端开/关 Settings，客户端画布像素差 183,864 / 183,494 px，实时同步 |
| 输入注入 | 客户端画布单击 → 源端 logcat `recv InputEvent`（DOWN+UP 对，间隔 106ms）→ 源端页面跳变 121,629 px，客户端画布同步 182,331 px |
| 截图取证 | `e2e-phone-before-click.png` / `e2e-phone-after-client-click.png`（源端）、`e2e-client-video-before.png` / `e2e-client-video-after.png`（客户端屏） |

实测中发现并修复的两个实现缺陷（已回灌 §3/§7 产物）：

1. **根链 clickable 吞掉画布点击**：SessionScreen 根 Box 的聚焦 clickable 在 Main 派发中
   先于指针注入 handler 消费 DOWN，原实现按 `isConsumed` 拦截导致画布点击永远不注入。
   现注入仅以「坐标映射进视频矩形 + 已连接」为条件（dock/HUD/抽屉天然被映射排除）。
2. **skiko ICU 数据缺失**：Windows 包根必须有 `icudtl.dat`（取自
   skiko-awt-runtime-windows-x64 jar），缺失时启动即
   `EXCEPTION_ACCESS_VIOLATION`（skiko.dll 初始化 SkLoadICU）。
3. **UDP 视频在 NAT/中继环境不可达**（FRP/adb 转发均只透传 TCP）：客户端进入
   STREAMING 后若 8s 仍无解码帧，自动以 `useUdpVideo=false`（TCP 视频）重启会话
   一次并在通知条提示「UDP 视频通道不可达……已自动切换为 TCP 视频」；控制/音频
   走 TCP 不受影响。实测（09:08）：UDP 模式 STREAMING 且 0 帧 → TCP 重启后帧
   立即到（`hasFrames=true`）。

经 FRP 中继连接（如 `112.74.85.74:22333`）的前提：frpc 端配置了
`localPort = 22333 → remotePort = 22333` 的 TCP 代理且 frpc 已加载该配置
（改配置后必须重启 frpc，frpc 无热加载；frps 侧端口 OPEN 但无 banner 通常
= 代理未注册）。连接参数：host=FRP 公网地址，端口 22333，token 与源端一致
（默认 `cloudphone-m0`）；视频由客户端自动回退为 TCP，无需额外设置。

4. **双客户端互踢重连风暴（"连接断开，正在重连"死循环）**：源端传输层对
   新连接 last-HELLO-wins 抢占（FRP 路径更甚——accept 即掐死现役通道），
   两个开着自动重连的客户端互踢永不停。修复（四层）：
   - **源端会话占用保护**：`TransportBackendBase.sourceBusyText` 非空时，
     现役通道在位则新客户端收到 `HelloAck(REJECT_BUSY)`（含指引文案）；
     lan-direct/frp 两后端统一走 pending 晋升 + 占用检查。换设备观看 =
     先断开当前观看端（Moonlight 单占用语义）。
   - **Android 客户端**：收 BUSY 后置 `busyRejected` 闸门，自动重连
     （断线触发 + watchdog >6s 无 Pong 触发）全部抑制，UI 显示
     「源端正被其他设备观看，已停止重连…」；用户显式点连接时闸门复位。
     HELLO/START_SESSION 应答超时 2s/3s → 5s/8s（FRP 链路下 2s 偶发误超时）。
   - **Desktop 客户端**：断线重连退避 1s 固定 → 指数 1s→30s；BUSY 拒绝
     落入 FAILED 分支（已有：显式拒绝不重试）。
   - **死通道清引用**：`LanDirectBackend.onChannelDead` 原来只报事件不清
     `channel` 引用——占用闸门会误判"现任仍 在位"导致所有后来者永远 BUSY
     （回归测试抓出，已修：现役通道死亡即置 null）。
   实测（10:23-10:25）：A 连入 → B 两次尝试即静默（不再风暴）→ A 断开 →
   B 无缝接管（StartSession + 编码器拉起 + Pong 存活）。
   另：Moonlight（GameStream 协议）评估后不采用——其服务端需要 GPU PC
   （Sunshine 无 Android host），且视频/控制全 UDP 架构无法过 adb forward
   与 FRP 纯 TCP 隧道；自研 TCP 协议方向与此环境匹配。

5. **主动抢占（takeover）**：被占用的客户端可以主动断掉对方抢走观看权。
   - **协议**：HELLO 增加可选尾字节 `takeover`（仅 true 时写，老解码器忽略
     未知尾字节，向后兼容）。
   - **源端**：占用闸门先解码 pending 通道首条 HELLO——`takeover=true` 则
     不回 BUSY，直接按正常晋升流程踢掉现役通道接管；`false` 才回
     `REJECT_BUSY`。lan-direct/frp 两后端行为一致。
   - **Desktop 客户端**：收 BUSY 自动重试一次 `takeover=true`（通知条提示
     「正在抢占…」）；每个 start() 生命周期只自动抢占一次（`preemptUsed`），
     断线重连不带 takeover——两台桌面端不会互相踢成死循环。
   - **Android 客户端**：主动连接收 BUSY 弹「源端被占用」对话框
     （「是否断开对方并接管观看权限？」），用户点「抢占观看」才以
     `takeover=true` 重连；自动重连永不抢占。
   实测（10:58-11:00）：桌面端对 Android 现役自动抢占（`recv StartSession`，
   被踢端 `reconnect stopped: source busy`）；Android 对桌面现役弹对话框，
   点击后 `HELLO takeover requested` → 5ms 内 `recv StartSession` 接管，
   原桌面端停止收帧。

VM 操控通道备注（复现实测用）：RDP 因 xfreerdp 缺陷不可用；启动应用走 Startup 目录离线注入；
GUI 自动化经 QEMU VNC websocket（容器内 127.0.0.1:8006/websockify）注入 KeyEvent/PointerEvent，
屏幕取证用 HMP `screendump` + OCR（本构建机无视觉模型，靠 OCR/像素差判断）。


## 6. 已知限制

- **HOME / RECENTS 虚拟导航键为占位禁用**（对齐 Android 客户端现状），仅 BACK 可注入；
- 键盘注入走组合文本 TEXT_INPUT 通道（可打印字符；非字符键/F 键区不注入，scancode 全键盘映射为后续增强）；
- 右键 = BACK（Android 边缘侧滑的桌面等效），不弹上下文菜单；
- 视频/音频为 **FFmpeg 软解**（低配机器高分辨率高帧率档位 CPU 占用偏高，可用档位循环降到 720P）；
- 局域网发现按本机 /24 网段 TCP 探测（300ms/64 并发/10s 上限），跨网段源端请手输 IP；
- 源端 IP/端口变化后旧档案仍按原值展示，需删除后重新发现或手输；
- Windows 侧 MSI 实际产包（需 Windows 实机）；VM 端到端已改由 dockur/windows 容器完成，见 §5.1。

## 7. 免安装便携版（Linux 交叉构建，无 Windows 机器参与）

jpackage 不支持交叉编译（exe/msi 需 Windows + WiX），但在 Linux 上可全自动产出
**免安装便携目录**（jlink 运行时 + launch4j 启动器），产物形如：

```
build/portable-win-x64/CloudPhoneDesktop-0.1.0-portable-win-x64.zip
└─ CloudPhoneDesktop/
   ├─ CloudPhoneDesktop.exe        # launch4j 3.50 GUI stub（requires64Bit，chdir 到 exe 目录）
   ├─ skiko-windows-x64.dll        # Compose Skia 引擎（-Dskiko.library.path=. 加载）
   ├─ lib/app/*.jar                # createDistributable 依赖集 - Linux natives + skiko-windows runtime
   ├─ lib/app/resources/
   ├─ runtime/                     # jlink 交叉运行时（Temurin 17.0.21+9 windows jmods，ALL-MODULE-PATH）
   └─ README.txt
```

构建步骤（已验证，2026-11-10）：

1. **依赖集**：取 `createDistributable` app-image 的 `lib/app/`（jar 与平台无关），
   剔除 Linux 专属（`skiko-awt-runtime-linux-x64`、`libskiko-linux-x64.so`、
   `ffmpeg-*-linux-x86_64-gpl`、`javacpp-*-linux-x86_64`），从 Maven Central 补
   `org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.8.4`（版本必须与
   `skiko-awt` 完全一致），并把该 jar 内的 `skiko-windows-x64.dll` 解到包根目录。
2. **运行时**：下载 Windows JDK（Temurin 17.0.21+9，[TUNA 镜像](https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/)），
   用**同版本** Linux jlink 交叉链接：

   ```bash
   ./jdk-17.0.21+9/bin/jlink --module-path <win-jdk>/jmods \
     --add-modules ALL-MODULE-PATH --output runtime \
     --no-header-files --no-man-pages --strip-debug --compress=2
   ```

   注意：**jlink 与 jmods 必须同版本**（跨补丁版本报
   `ModuleTarget attribute is missing for java.base module`）。
3. **启动器**：launch4j 3.50（Maven Central `net.sf.launch4j:launch4j:3.50` +
   `workdir-linux64` 分类构件 + xstream/commons-beanutils/commons-logging/commons-collections），
   headless 构建 `java -cp … net.sf.launch4j.Main config.xml`（须在 workdir 目录内执行，
   其按 jar 位置寻找 `bin/windres`）。配置要点：`<chdir>.</chdir>`、`<cp>lib/app/*</cp>`
   （JVM 通配符展开——launch4j classpath 硬上限 260 字符，40 个哈希 jar 名必超）、
   `<jre><path>runtime</path><requires64Bit>true</requires64Bit></jre>`、
   JVM 参数 `-Dcompose.application.resources.dir=lib/app/resources
   -Dcompose.application.configure.swing.globals=true -Dskiko.library.path=.`
   （与 jpackage app-image `CloudPhoneDesktop.cfg` 逐条对齐）。
4. **验证**（Linux 上可做）：`file` 确认 exe=PE32(GUI)/java.exe=PE32+/dll=PE32+ x86-64；
   宿主 JVM 以 `-cp "lib/app/*" cloudphone.desktop.MainKt` 跑入口冒烟
   （headless 守卫下启动入口冒烟通过，exit=0，证明依赖集完整可解析）。

与 jpackage MSI 的差异：无安装器形态（解压即用）、无开始菜单/快捷方式、exe 未签名
（首次运行 SmartScreen 告警）。正式安装器仍走 Windows 实机
`gradlew.bat packageExe/packageMsi`（§4.1）。

### 7.1 部署进 dockur/windows VM（无 RDP/SSH 通道时的离线注入法）

容器（dockurr/windows）只暴露 8006（noVNC）与 3389（RDP，xfreerdp 2.6.1 存在
静默 exit=1 缺陷不可用），无 SSH；Start 搜索索引损坏、Win+R 不生效，GUI 启动程序
不可靠。验证过的免 GUI 部署流程：

```bash
docker stop -t 60 windows
losetup -fP /root/dockur-windows/windows/data.img   # GPT：p3 = Windows C:
mount -t ntfs-3g -o rw /dev/loopXp3 /mnt/win
# 写 <mount>/Users/Docker/AppData/Roaming/Microsoft/Windows/Start Menu/Programs/Startup/go_boot.bat
#   内容：解压 zip（tar -xf C:\Users\Docker\cd.zip -C C:\Users\Docker\，zip 自带
#   CloudPhoneDesktop/ 根目录）→ java 前台运行 + app.log 周期拷回 \\host.lan\Data\ 取证
sync && umount /mnt/win && losetup -d /dev/loopX
docker start windows        # autologon（Docker/adminPassword）→ Startup bat 自动执行
```

包根**必须含 `icudtl.dat`**（从 skiko-awt-runtime-windows-x64 jar 解出），否则启动即
`SkLoadICU: datafile missing → EXCEPTION_ACCESS_VIOLATION`。`--autotest <host> <port> [token]`
为本次验证新增的启动参数：直接以给定地址/token 建会话（udpPort=0 强制 TCP 视频），
便于无人值守 VM 验证（改动在 client-windows Main.kt/AppRoot.kt，单测 104 例全绿）。

## 8. 操作延迟实测工具（源端移动靶）

`LatencyTestActivity`（source APK 内，MainActivity →「延迟测试（移动靶）」进入）：3 个
60px 圆点分泳道横向弹跳，5 档速度 300/700/1400/2400/4000 px/s（对应命中窗
399/171/86/50/30ms）；点圆计分（score/streak/命中率 HUD），点 HUD 速度标签循环换挡，
底部两角热区微调。60fps Choreographer 驱动（源端静屏时 SurfaceFlinger ~1Hz idle 合成
会吃掉画面更新，悬窗 60fps 驱动后触摸→视觉 P50 128ms/P95 247ms，见 §5 缺陷 3 修复）。

端到端实证（2026-11-11）：emulator-5556 Android 客户端（frp 通道 10.0.2.2:22333）画布
点击 → 源端注入 → 命中 `latency-test HIT score=1 streak=1 @(541,618)`（300px/s 档，
预测落点与实际触摸点亚像素吻合）；换挡（连点 HUD 标签 700→300px/s）亦经客户端完成。
取证截图 docs/bootstrap-screenshots/windows-client/latency-hit-{source,client}.png。
证据插桩 tag `cloudphone-touch`：t1 客户端发送 / t3 源端注入 / t5 编码 / t6 渲染 /
doFrame 与 HIT 打点。APK：`/opt/apk-pub/cloudphone-source-20261111c.apk`
(md5 9e546537276b2dee598eff41b2e62a9f)。

## 9. Android 全屏沉浸与源端僵尸监听加固（2026-11-11 16:5x）

**缺陷 6：全屏"黑边"（新 Android 上系统栏未隐藏）。** d 版全屏已做画面裁切铺满
（AspectFitSurfaceView.fillMode cover），但系统栏隐藏用的是 deprecated
`systemUiVisibility` flags——在 Android 11+（用户新机）会被平台忽略：状态栏/导航栏
仍显示（黑色），叠加在满屏画面上下即用户所见"一圈黑边"。修复（DecodeRenderActivity
hide/showSystemBars）：`SDK>=30` 走 `window.setDecorFitsSystemWindows(false)` +
`window.insetsController.hide(WindowInsets.Type.systemBars())` +
BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE；`<30` 保留 legacy flags。API 34 模拟器实测
`statusBars visible=false`，画面 100 列全覆盖。

**缺陷 7：源端僵尸监听（会话结束后不再接受新连接）。** 16:26 一例：会话结束后源端
进程存活、TCP 端口可连（内核 backlog），但 `recv Hello` 永不再出现——客户端表现为
"连接断开，正在重连"无限循环，仅重启源端进程可恢复。根因：`LanDirectBackend`/
`FrpBackend` 的 accept 线程只 catch 了 `accept()` 的 IOException，
`installChannel` 内部（如 `getInputStream`）抛出的其他异常直接杀死 accept 线程
（daemon、无日志）；`TcpStreamChannel.startReading` 读泵亦只 catch
IOException/ProtocolException，分发回调里的意外异常会静默死线程、现役通道不释放。
修复（protocol）：两处 accept 循环对 `installChannel` 整体 try/catch(Throwable)
（记 STREAM_ERROR + 关 socket + 继续），读泵补 `catch (Throwable)` 走 onError 正常
断流处置。回归：垃圾字节探针×3 + 连接后 force-stop + 立即重连，accept 线程全部
存活、新会话正常建立。发布：client `cloudphone-client-20261111e.apk`
(md5 f92c899796b69adf0c52a10a664c10ad)、source `cloudphone-source-20261111d.apk`
(md5 a34cc87656e5645035d0f055b1985b75)。
