# CloudPhone（云手机）— M0 A2A Streaming Pipeline

> Android A2A 云手机：在源端设备上镜像整屏/单应用到远端客户端，双向触控回传。
> M0 范围：端到端流式管线（采集 → 编码 → 传输 → 解码渲染）+ 三种传输后端 + 全自动验收矩阵。
> 中文说明在下方；English summary at the bottom.

## 这是什么

一台 Android 设备（源端）把自己的屏幕实时推给另一台设备/主机（客户端），客户端能看、能点。
典型形态即「云手机」：App 跑在云端 Android 上，用户在本地操作。M0 交付的是这条链路的最小完整实现：

```
┌─ 源端（Android 设备）────────────────┐      ┌─ 客户端 ────────────────┐
│ ScreenCapture（MediaProjection VD）   │      │ DecodeRenderActivity     │
│   ↓ Surface                          │      │   ↑ SurfaceView 渲染     │
│ EncoderPipeline（MediaCodec H.264）   │      │   ↑ MediaCodec 硬/软解   │
│   ↓ H.264 帧                         │ 网络 │   ↑ 视频记录             │
│ StreamService（前台服务/会话/统计）  │ ───→ │ ClientSession（重连≤1s） │
│ InputInjector（shell 注入触摸）      │ ←─── │ TouchChannel（触控回传） │
└──────────────────────────────────────┘      └──────────────────────────┘
        protocol/（共享 Kotlin 模块）：帧封装、控制消息、统计、三种传输后端
```

- **协议**：大端序记录流 `[u8 type][u32 len][payload]`；`0x01` 控制 / `0x02` 视频。
  视频带 21B 头（seq/pts/关键帧/分片），UDP 模式按 ≤1200B 分片；9 条控制消息
  （HELLO/HELLO_ACK/START_SESSION/STOP_SESSION/PING/PONG/IDR_REQUEST/STATS/INPUT）。
- **传输后端**（`protocol/transport/`）：
  - `lan-direct`：局域网 TCP 直连（控制+视频复用一条流），局域网默认路径；
  - `frp`：公网中转（frp TCP/UDP 隧道，穿透 NAT 无公网也能连）；
  - `p2p`：STUN 探测 SRFLX + UDP 打洞直连，5 秒未打通自动回退 frp。
- **可观测性**：源端/客户端 1Hz STATS（fps/码率/rtt/丢包/序号）、管线计数器、
  客户端帧延迟与首帧白闪测量（`logcat` 标签 `cloudphone-latency` / `cloudphone-stats`）。

## 快速开始

前置：两台 Android 设备/模拟器 + 宿主 adb；`JAVA_HOME` 指向 JDK 17；源端镜像需可 adb root（模拟器）。

```bash
# 0) 构建（四个独立 Gradle 工程：protocol / source / client / source/loadgen）
(cd protocol && ./gradlew test)                 # 协议层 32 用例
(cd source && ./gradlew assembleDebug)          # 源端 APK
(cd source/loadgen && ./gradlew assembleDebug)  # 负载 App（游戏/静态）
(cd client && ./gradlew assembleDebug)          # 客户端 APK

# 1) 一键环境装配（安装 4 个产物 / root / forward+redir / 拉起服务 / 自动授权）
bash scripts/bootstrap_adb.sh all          # SERIALS=emulator-5554,emulator-5556 bash scripts/bootstrap_adb.sh all

# 2) 客户端一键连接（lan 直连，宿主口 22333）
adb -s emulator-5556 shell am start -n cloudphone.client/.ConnectActivity \
  --es autoHost 10.0.2.2 --es autoBackend lan-direct

# 3) 源端跑负载 App，让画面动起来（游戏档 60fps 动画 + 白闪按钮）
adb -s emulator-5554 shell am start -n cloudphone.loadgen/.GameLoadActivity

# 4) 看延迟
adb -s emulator-5556 logcat | grep cloudphone-latency     # white-flash Ms=… / frame seq=… delayMs=…
adb -s emulator-5556 logcat | grep cloudphone-stats       # fps=… bitrate=… rtt=… loss=…
```

### 全自动验收矩阵（12 组合）

```bash
python3 scripts/run_matrix.py --serials emulator-5554,emulator-5556 --seconds 60
# 3 网络（lan/frp/p2p）× 2 负载（game/static）× 2 分辨率（720p/1080p）× 60s
# 产出 docs/validation-report.md（PASS/FAIL + 均值/P95/码率/丢包/断流）
# 分辨率切换会自动重新授权（Android 14 一授权一 VD，见 docs/device-notes.md §2.2）
```

### 延迟单独测量

```bash
python3 scripts/measure_latency.py --serial emulator-5556 --seconds 20
# 白闪口径（点击→白帧→渲染，端到端绝对值）+ 帧间口径；JSON 落 docs/latency-samples.json
```

## 源码地图

| 目录 | 内容 |
|---|---|
| `protocol/src/main/kotlin/cloudphone/protocol/` | Messages/Framing（协议与封装）、Stats（统计口径） |
| `protocol/src/main/kotlin/cloudphone/transport/` | TransportBackend 接口 + LanDirect/Frp/P2p/Stun/Diagnostics |
| `source/app/src/main/kotlin/cloudphone/source/` | MainActivity、StreamService（前台服务/会话）、ScreenCapture、EncoderPipeline、SessionManager、InputInjector、shell/ShellBootstrap |
| `source/shellhelper/` | app_process 助手（单应用虚拟屏；模拟器镜像受限见 device-notes §2.1） |
| `client/app/src/main/kotlin/cloudphone/client/` | ConnectActivity/ClientSession、DecodeRenderActivity、TouchChannel、StatsView、SessionDrawerActivity |
| `source/loadgen/` | GameLoadActivity（60fps 动画+白闪）、StaticAppActivity（静态低负载） |
| `scripts/` | bootstrap_adb.sh（装配）、run_matrix.py（验收矩阵）、measure_latency.py、udp_relay.py、nat_check.py、build_shell_helper.sh |
| `docs/` | protocol.md（协议规范）、validation-report.md（验收报告）、device-notes.md（设备实测笔记）、latency-samples.json |

## 验收口径（M0）

- 游戏档：平均 fps ≥ 45，端到端延迟 P95 ≤ 200ms；
- 应用档（静止）：码率 ≤ 1Mbps；
- 稳定性：60 秒观察窗零断流；断线重连 ≤ 1 秒恢复出帧；
- 矩阵：3 网络 × 2 负载 × 2 分辨率，每格 60 秒，失败自动重试 1 次。

## 已知限制（M0）

- 视频流未加密（spec 范围外）：不要把 frp/p2p 端点暴露到公网承载真实内容；
- 单应用模式在模拟器镜像上不可用（shell 助手被系统 SIGKILL，见 device-notes §2.1），真机需重测；
- MediaProjection 授权一次性凭据 + 闲置过期（Android 14）：分辨率切换走零令牌 adaptTo（无需重授权）；
  管线死亡后由三层自愈恢复（设备侧看门狗自动重授权 + 开机自启 + 宿主 grant/watch 巡检，
  见 docs/device-notes.md §5.1），授权一次即可长时运行多客户端访问；
- p2p 在双模拟器同宿主 NAT 下可能假阳性直连，真机异网重测见 device-notes §4。

---

## English Summary

**CloudPhone M0** streams one Android device's screen to another device/host with touch input
returned to the source — the minimal end-to-end pipeline (capture → H.264 encode → transport →
decode/render) of a cloud-phone. A shared Kotlin `protocol/` module defines the framed wire format
(`[u8 type][u32 len][payload]`, control+video multiplexed) and three transports: **lan-direct**
(TCP), **frp** (public relay), and **p2p** (STUN SRFLX + UDP hole punching with frp fallback).
Automation covers environment bring-up (`bootstrap_adb.sh`), a 12-combo acceptance matrix
(`run_matrix.py`, 3 networks × 2 loads × 2 resolutions × 60s) and latency measurement
(`measure_latency.py`, white-flash + frame-gap methods). Acceptance gates: game fps ≥ 45 with
P95 latency ≤ 200ms, static bitrate ≤ 1Mbps, zero disconnects over 60s, reconnect ≤ 1s.
See `docs/device-notes.md` for emulator-image caveats (shell-helper SIGKILL, one-shot
MediaProjection credentials, goldfish decoder) and the real-device retest checklist.
