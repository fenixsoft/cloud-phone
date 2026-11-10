# 客户端解码渲染与输入回传

<!-- knowledge: capability=client-decode-render rev=2
     sources=spec/changes/m0-a2a-streaming-pipeline,spec/changes/client-ux-and-input-latency,spec/changes/client-ui-redesign -->

客户端 App 的画面消费侧：MediaCodec 硬解 SurfaceView 渲染、触摸/手势采集回传、连接 UI 与实时统计，实现「云端应用」交互范式。

## 规则

- 硬解渲染：MediaCodec H.264 硬解 + SurfaceView/SurfaceTexture 渲染；局域网路径首帧出画 ≤2s。视频真实尺寸到达后按宽高比 letterbox 适配视口，触控归一化基于该视口。
- latest-wins 渲染兜底：解码照常进行（保 P 帧参考链完整），仅当帧队列为空（本帧即最新）或本帧为关键帧时上屏，积压的陈旧帧直接丢弃不上屏——防止低帧率下显示滞后随队列积压深度放大。
- 触摸回传：采集 down/move/up，坐标归一化到 [0,65535]（分辨率无关）+ displayId 经控制通道回传；事件间隔 ≤30ms 不丢失起止序列；多点触控按 pointerId 逐指追踪。
- 手势语义（云端应用范式）：边缘侧滑（落点在左/右缘 24dp 内且水平滑动 ≥80dp）触发远端 back（INPUT_EVENT InputAction.BACK），客户端会话不退出。
- 连接入口与控制台壳层由客户端界面壳能力（client-ui-redesign）定义：连接首页卡片发起连接，控制台含 HUD 统计条/工具坞/虚拟导航键/会话抽屉；连接失败与握手被拒原因回显用户。
- 实时统计：会话内实时显示 fps、码率、RTT、丢包率，刷新 ≥1Hz，数值与 STATS 消息一致。

## 代码锚点

- 主实现：client/app/src/main/kotlin/cloudphone/client/DecodeRenderActivity.kt（解码渲染与 latest-wins）
- 单源符号：client/app/src/main/kotlin/cloudphone/client/input/TouchChannel.kt（触摸采集与手势语义）、client/app/src/main/kotlin/cloudphone/client/StatsView.kt（统计面板）
- 消费方：client/app/src/main/kotlin/cloudphone/client/ConnectActivity.kt（连接首页入口）
- 守卫测试：无（行为验收经 validation-harness 矩阵与延迟测量覆盖）

## 溯源

- 2026-11-08 m0-a2a-streaming-pipeline（spec/changes/m0-a2a-streaming-pipeline/）— 新建本页：确立硬解渲染、触摸回传、手势语义、连接 UI 与统计显示规则。
- 2026-11-09 client-ux-and-input-latency（spec/changes/client-ux-and-input-latency/）— 增补 latest-wins 渲染兜底规则（触摸延迟压制的客户端侧机制）。
- 2026-11-10 client-ui-redesign（spec/changes/client-ui-redesign/）— 连接 UI 条目改写为界面壳口径，消费方锚点随会话抽屉并入控制台更新；移除随旧会话抽屉废弃的 Home 手势条目。
