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

## 6. 已知限制

- **HOME / RECENTS 虚拟导航键为占位禁用**（对齐 Android 客户端现状），仅 BACK 可注入；
- 键盘注入走组合文本 TEXT_INPUT 通道（可打印字符；非字符键/F 键区不注入，scancode 全键盘映射为后续增强）；
- 右键 = BACK（Android 边缘侧滑的桌面等效），不弹上下文菜单；
- 视频/音频为 **FFmpeg 软解**（低配机器高分辨率高帧率档位 CPU 占用偏高，可用档位循环降到 720P）；
- 局域网发现按本机 /24 网段 TCP 探测（300ms/64 并发/10s 上限），跨网段源端请手输 IP；
- 源端 IP/端口变化后旧档案仍按原值展示，需删除后重新发现或手输；
- Windows 侧 MSI 实际产包与 VM 端到端冒烟在验收阶段于 Windows 实机执行。
