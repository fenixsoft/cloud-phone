# Proposal: 安卓到安卓串流全链路走通 (M0)

## Why

远程手机上运行的应用无法在另一台手机上直接使用：现有方案（向日葵/ToDesk/AirDroid/Vysor、scrcpy 客户端类）全部是"整屏远程桌面"范式，缺少"云端应用/云端手机"式的**单应用串流**（应用抽屉 → 点开单个 App → 退出回抽屉 → 断开挂机），且均无安卓→鸿蒙端支持。本项目（开源，Apache-2.0）先服务作者自有设备互连。M0 里程碑：在双模拟器上把「源端采集编码 → 三种网络路径 → 客户端解码渲染 → 触摸回传」全链路走通并量化验证，为下一步真机人工实测与 M2 鸿蒙端铺路。

已验证的环境事实（立项时侦察结论）：
- 本机为作者家庭服务器（内网 192.168.20.123，电信出口 14.155.102.104），KVM 双模拟器已就绪（emulator-5554=src / emulator-5556=client）。
- 家庭网络 NAT 为 **endpoint-independent mapping（cone 型）**：三个独立 STUN 服务器从同一 socket 返回相同外部映射 → 本端 P2P 打洞可行；国内 STUN（stun.qq.com / stun.miwifi.com:3478）被网络过滤，STUN 需备多节点。
- 公网 FRP 中转路径为**真实公网回环**：本机 frpc 已挂载面板隧道 t3CcW1J2DMkW(TCP 127.0.0.1:22333→112.74.85.74:22333) 与 9dNPmvI3sR2C(UDP :22334)，模拟器客户端连 112.74.85.74:22333 即走真实公网。
- VPS 无现成 SSH 密钥（后续如需自建信令/STUN 节点再申请）。

## What Changes

- 新建 `source/` Android 工程：串流源 App —— 整屏 MediaProjection 采集 + 单应用虚拟屏模式（shell bootstrap，模拟器 adb root 路径）、MediaCodec H.264 硬编、会话生命周期管理（断开挂机/重连凭 token 秒回 IDR）。
- 新建 `client/` Android 工程：观看端 App —— MediaCodec 硬解 + SurfaceView 渲染、触摸/手势采集回传、最小连接 UI（手动地址+配对码；mDNS 发现留后续里程碑）。
- 新建 `protocol/` 共享模块与 `docs/protocol.md` 协议文档：M0 用 Kotlin 实现（两端共享），C++ 协议核心库为 M1 目标（本次只定协议格式与常量，保证后续 C 化不破坏线上格式）。
- 新建 `transport` 三后端：`lan-direct`（局域网直连）/ `p2p`（STUN 地址发现 + FRP 承载信令 + UDP 打洞）/ `frp`（TCP/UDP 公网隧道兜底）。
- 新建 `validation-harness`：负载生成 App（游戏档恒定 60fps 动画 / 应用档静态页面）、分辨率矩阵（≥720p/1080p）、三网络端到端自动化测试脚本与"可用"指标判定。

## Capabilities

### New Capabilities

- `stream-protocol-core`: 会话握手/控制通道/视频帧线协议（framing、配对认证、IDR 请求、统计字段）与协议文档。
- `source-capture-encode`: 源端画面采集（整屏 MediaProjection + 单应用虚拟屏）、MediaCodec 硬编、会话挂机与恢复。
- `client-decode-render`: 客户端硬解渲染、输入事件回传、最小连接 UI。
- `transport-backends`: lan-direct / p2p / frp 三种网络路径、传输统计与直连诊断。
- `validation-harness`: 负载生成、分辨率矩阵、三网络自动化验收与指标判定。

### Modified Capabilities

（无——全新仓库，不存在既有能力；`spec/changes/` 与 `spec/archive/` 均无历史 specs。）

## Impact

- 全新 Gradle 工程 ×2 + 共享协议模块；依赖 AGP/Kotlin/Android SDK 34（本机 /opt/android-sdk 已装齐，Java 17 就绪）。
- 双模拟器常驻运行（24 核 62G 内存、swiftshader 软渲染、磁盘余 28G 需注意 AVD 磁盘配额）。
- 网络依赖：公网 FRP 面板隧道两条（已开通并在 frpc-remote.toml 落盘）；公网 STUN 节点（Cloudflare 可达）。
- 不改动 `/etc/frp/` 既有生产代理；M0 不涉及真实手机与鸿蒙端；音频/剪贴板/IME 属 M3 非目标。
