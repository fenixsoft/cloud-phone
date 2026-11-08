## Deliverables

```yaml
- path: README.md
  kind: new
- path: .gitignore
  kind: modify
- path: docs/protocol.md
  kind: new
- path: docs/validation-report.md
  kind: new
- path: docs/device-notes.md
  kind: new
- path: protocol/settings.gradle.kts
  kind: new
- path: protocol/build.gradle.kts
  kind: new
- path: protocol/src/main/kotlin/cloudphone/protocol/Messages.kt
  kind: new
- path: protocol/src/main/kotlin/cloudphone/protocol/Framing.kt
  kind: new
- path: protocol/src/test/kotlin/cloudphone/protocol/FramingTest.kt
  kind: new
- path: protocol/src/main/kotlin/cloudphone/transport/TransportBackend.kt
  kind: new
- path: protocol/src/main/kotlin/cloudphone/transport/LanDirectBackend.kt
  kind: new
- path: protocol/src/main/kotlin/cloudphone/transport/P2pBackend.kt
  kind: new
- path: protocol/src/main/kotlin/cloudphone/transport/FrpBackend.kt
  kind: new
- path: protocol/src/main/kotlin/cloudphone/transport/StunClient.kt
  kind: new
- path: protocol/src/main/kotlin/cloudphone/transport/Diagnostics.kt
  kind: new
- path: protocol/src/main/kotlin/cloudphone/transport/Stats.kt
  kind: new
- path: source/settings.gradle.kts
  kind: new
- path: source/build.gradle.kts
  kind: new
- path: source/gradle.properties
  kind: new
- path: source/app/build.gradle.kts
  kind: new
- path: source/app/src/main/AndroidManifest.xml
  kind: new
- path: source/app/src/main/kotlin/cloudphone/source/MainActivity.kt
  kind: new
- path: source/app/src/main/kotlin/cloudphone/source/StreamService.kt
  kind: new
- path: source/app/src/main/kotlin/cloudphone/source/SessionManager.kt
  kind: new
- path: source/app/src/main/kotlin/cloudphone/source/ScreenCapture.kt
  kind: new
- path: source/app/src/main/kotlin/cloudphone/source/VirtualDisplaySession.kt
  kind: new
- path: source/app/src/main/kotlin/cloudphone/source/EncoderPipeline.kt
  kind: new
- path: source/app/src/main/kotlin/cloudphone/source/obs/PipelineCounters.kt
  kind: new
- path: source/app/src/main/kotlin/cloudphone/source/shell/ShellBootstrap.kt
  kind: new
- path: source/app/src/main/kotlin/cloudphone/source/input/InputInjector.kt
  kind: new
- path: source/loadgen/settings.gradle.kts
  kind: new
- path: source/loadgen/app/build.gradle.kts
  kind: new
- path: source/loadgen/app/src/main/AndroidManifest.xml
  kind: new
- path: source/loadgen/app/src/main/kotlin/cloudphone/loadgen/GameLoadActivity.kt
  kind: new
- path: source/loadgen/app/src/main/kotlin/cloudphone/loadgen/StaticAppActivity.kt
  kind: new
- path: client/settings.gradle.kts
  kind: new
- path: client/build.gradle.kts
  kind: new
- path: client/gradle.properties
  kind: new
- path: client/app/build.gradle.kts
  kind: new
- path: client/app/src/main/AndroidManifest.xml
  kind: new
- path: client/app/src/main/kotlin/cloudphone/client/MainActivity.kt
  kind: new
- path: client/app/src/main/kotlin/cloudphone/client/ConnectActivity.kt
  kind: new
- path: client/app/src/main/kotlin/cloudphone/client/DecodeRenderActivity.kt
  kind: new
- path: client/app/src/main/kotlin/cloudphone/client/SessionDrawerActivity.kt
  kind: new
- path: client/app/src/main/kotlin/cloudphone/client/input/TouchChannel.kt
  kind: new
- path: client/app/src/main/kotlin/cloudphone/client/StatsView.kt
  kind: new
- path: scripts/bootstrap_adb.sh
  kind: new
- path: scripts/run_matrix.py
  kind: new
- path: scripts/measure_latency.py
  kind: new
- path: scripts/udp_relay.py
  kind: new
- path: scripts/nat_check.py
  kind: modify
```

## 1. 工程骨架与协议核心

- [x] 1.1 建立 Gradle 骨架：source/settings.gradle.kts、source/build.gradle.kts、source/gradle.properties、client/settings.gradle.kts、client/build.gradle.kts、client/gradle.properties、protocol/settings.gradle.kts、protocol/build.gradle.kts、source/loadgen/settings.gradle.kts 九件套（AGP 8.x + Kotlin 2.x + compileSdk 34，minSdk 26；Gradle wrapper distributionUrl 用腾讯镜像），composite build 共享引用根目录 protocol/ 模块；source/app/build.gradle.kts、client/app/build.gradle.kts、source/app/src/main/AndroidManifest.xml、client/app/src/main/AndroidManifest.xml 最小可装配形态；根 .gitignore 补充构建产物与 local.properties 规则
- [x] 1.2 protocol 模块实现 Messages.kt/Framing.kt：HELLO/HELLO_ACK/START_SESSION/STOP_SESSION/IDR_REQUEST/INPUT_EVENT/STATS/PING/PONG 全消息、视频帧封装（sequence/PTS/keyframe/长度前缀）、UDP 分片（fragment index/count）；FramingTest.kt 单元测试覆盖编解码往返与分片重组且全绿
- [x] 1.3 撰写 docs/protocol.md：字节序、逐消息字段表、握手时序图（文字）、错误码、M1 C++ 化兼容性约定（字段 C 对齐、版本协商）
- [x] 1.4 实现 TransportBackend.kt 接口与 Stats.kt 结构（RTT/丢包率/码率/fps，≥1Hz 聚合），两端引用同一实现

## 2. 传输三后端与诊断

- [x] 2.1 实现 FrpBackend.kt：TCP 模式连 112.74.85.74:22333（控制+视频复用），UDP 模式走 22334（视频通道），含断流自动重连与降级记录
- [x] 2.2 实现 LanDirectBackend.kt：源端端口经 adb forward 暴露主机环回，客户端一跳直连（目标 RTT≤5ms）
- [x] 2.3 实现 StunClient.kt 与 P2pBackend.kt：SRFLX 发现（节点列表 Cloudflare/Twilio/Nextcloud 可配置）、信令 offer/answer 经 frp-TCP 交换、双端同时 UDP 发包打洞、5s 未成自动回落 frp 并记录回落事件
- [x] 2.4 实现 Diagnostics.kt：NAT 类型判定（移植 scripts/nat_check.py 同款多节点同 socket 探测），输出结构化 JSON（类型/SRFLX/端口分配特征）
- [x] 2.5 传输层可观测性：逐级计数器（发送/接收/分片丢弃）与坏事件分类统计（断流/重连/打洞回落/UDP→TCP 降级），经 Stats.kt 对外暴露统一接口

## 3. 源端采集与编码

- [x] 3.1 实现 ShellBootstrap.kt 与源端入口 MainActivity.kt：检测/启动 app_process shell 助手（虚拟屏创建、am start --display 单应用启动、触摸注入代理），无 root 时明确报降级原因（M0 模拟器 adb root 可用）
- [x] 3.2 实现 ScreenCapture.kt 与 VirtualDisplaySession.kt：MediaProjection 整屏采集 + 虚拟屏单应用采集双路径；旋转/分辨率变化自动重建并触发 IDR
- [x] 3.3 实现 EncoderPipeline.kt：MediaCodec H.264（720p/1080p × 30/60fps × 可配码率），支持动态 IDR_REQUEST，每帧内嵌源端单调时钟 PTS
- [x] 3.4 源端可观测性 obs/PipelineCounters.kt：管线逐级计数（采集帧数/编码帧数/发送帧数）、采集与编码采样帧 dump 开关（写 /sdcard 供诊断）、坏事件分类（编码失败/采集失败/MediaProjection 授权丢失）
- [x] 3.5 实现 SessionManager.kt 与 StreamService.kt 前台服务：断开停编码留虚拟屏（挂机）、同 token 重连 ≤1s 恢复（IDR）、≥2 个单应用会话并存
- [x] 3.6 实现 InputInjector.kt：INPUT_EVENT 归一化坐标按会话分辨率换算后注入对应 display

## 4. 客户端解码渲染与输入

- [x] 4.1 实现 DecodeRenderActivity.kt：MediaCodec + SurfaceView 硬解渲染，局域网首帧 ≤2s，缺帧/晚到帧统计
- [x] 4.2 实现 input/TouchChannel.kt：触摸 down/move/up 归一化回传（间隔≤30ms 不丢起止）；边缘侧滑→远端 back；Home 手势→退回抽屉且不向远端发 Home
- [x] 4.3 实现 ConnectActivity.kt 与客户端入口 MainActivity.kt：host:port、token、传输后端选择、模式（整屏/单应用+包名）、分辨率与帧率配置
- [x] 4.4 实现 SessionDrawerActivity.kt：挂机会话文字列表，点入凭 token 恢复观看
- [x] 4.5 实现 StatsView.kt：fps/码率/RTT/丢包率 ≥1Hz 实时显示

## 5. 负载生成与测量工具

- [x] 5.1 实现负载 App（source/loadgen/app/build.gradle.kts、AndroidManifest.xml）：GameLoadActivity.kt（恒定 60fps 动画 + 帧计数 overlay + 可点击按钮）、StaticAppActivity.kt（静态列表页），安装到源端模拟器可独立启动
- [x] 5.2 实现 scripts/measure_latency.py：PTS 差值法统计端到端延迟（P50/P95）+ 白闪校准（源端白帧、客户端像素检测）输出校准差值 + 触摸往返延迟（负载 App 点击后 overlay 帧计数/颜色变化回执，客户端检测触摸发出→画面变化差值，输出 P50/P95）
- [x] 5.3 实现 scripts/bootstrap_adb.sh：双模拟器健康检查、四 APK 安装（source/client/loadgen）、adb root/forward、主机侧 UDP 中继（启动 scripts/udp_relay.py：127.0.0.1:22334 ↔ 源端模拟器 UDP 端口，adb 不支持 UDP 转发）、MediaProjection 授权弹窗 uiautomator 自动点击（3 次重试+截图存档）

## 6. 矩阵验收与文档

- [x] 6.1 实现 scripts/run_matrix.py：3 网络（lan-direct/p2p/frp）× 2 负载（game/static）× 2 分辨率（720p/1080p）× 60s 全自动编排，组合失败重试 1 次，生成 markdown 报告（fps 均值/P95 帧间隔/延迟 P50 P95/码率/丢包/通过判定）
- [x] 6.2 实跑全矩阵产出 docs/validation-report.md：12 组合指标与 PASS/FAIL 判定、frp 公网带宽实测、双模拟器拓扑局限说明（P2P 假成功可能）、与真机目标差距分析
- [x] 6.3 撰写 docs/device-notes.md：模拟器 shell bootstrap 说明、真机注意事项（OEM 杀后台/电池白名单引导、"M0 无加密禁止真机公网使用"红线、真机对真机 P2P 复测人工步骤）
- [x] 6.4 撰写 README.md：中英双语项目简介、云端应用范式说明（单应用抽屉/挂机）、架构总览（文字版）、M0 快速开始（双模拟器一键验证）
