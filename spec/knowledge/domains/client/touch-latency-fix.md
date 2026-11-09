# 触摸响应延迟插桩与修复

<!-- knowledge: capability=touch-latency-fix rev=1
     sources=spec/changes/client-ux-and-input-latency -->

触摸「发出 → 客户端画面变化」全链延迟的量化与压制：六段打点定位最大延迟段，输入窗口合成驱动 + latest-wins 渲染双机制，把静屏秒级响应压到百毫秒级。

## 规则

- 全链插桩六段，打点日志统一 `cloudphone-touch` tag 可检索：①t1 客户端触摸事件产生（TouchChannel）→ ②t2 源端收到 InputEvent（StreamService）→ ③t3 注入完成（InputInjector）→ ④t4 源屏内容变化被采集（ScreenCapture，帧间隔>200ms 标记 idle 段）→ ⑤t5 编码出帧（EncoderPipeline，输入窗口内逐帧 + idle 段逐帧）→ ⑥t6 客户端解码渲染完成（DecodeRenderActivity）。
- 静屏延迟根因（插桩测量结论）：注入 0–5ms、编码 ~20ms、传输+渲染 7–14ms 均为毫秒级，大头是静屏时 SurfaceFlinger 对虚拟屏镜像按 ~1Hz idle 节奏合成——触摸引发的 App 重绘要等下一次合成才进镜像；输入窗口关闭时该 idle 节奏仍是常态。
- 主机制「输入窗口合成驱动」：InputEvent 到达后打开/顺延 5s 输入窗口，经 SYSTEM_ALERT_WINDOW 透明 1×1 悬窗以 5fps 失效自己强制 display 0 逐次合成，使镜像帧即时携带触摸画面变化；悬窗完全透明、不可触摸、不获焦点，对源端画面与触摸语义零干扰；SYSTEM_ALERT_WINDOW 权限经 bootstrap `appops set` 显式登记，未授权时降级为 idle 合成节奏并打 WARN。
- 副机制「latest-wins 渲染兜底」：客户端仅渲染最新帧/关键帧，陈旧积压帧丢弃（规则见 client-decode-render 能力页）。
- 输入插桩窗口：InputEvent 后 2s 内编码逐帧打 t5，窗口外仅 idle 段打点。
- 验收阈值：模拟器（swiftshader 软渲染）静屏触摸→视觉变化 P95 ≤ 800ms（当前实测 P95=84ms）；60fps 游戏负载下触摸 P95 ≤ 300ms 且帧率不受合成驱动机制拖累；真机目标 P95 ≤ 300ms。
- 延迟口径文档化：模拟器与真机口径差异、真机目标与可复现测量方法记录于 docs/device-notes.md；测量统一走 measure_latency.py 触摸口径（t1→t6 Wall 时间差，输出 P50/P95 与分段耗时）。
- 修复纪律：延迟类问题先插桩测量、按数据定位主因再改代码（详见 ADR-005）。

## 代码锚点

- 主实现：source/app/src/main/kotlin/cloudphone/source/InputComposer.kt（输入窗口合成驱动悬窗）
- 单源符号：client/app/src/main/kotlin/cloudphone/client/input/TouchChannel.kt（t1）、source/app/src/main/kotlin/cloudphone/source/StreamService.kt（t2 与窗口分发）、source/app/src/main/kotlin/cloudphone/source/input/InputInjector.kt（t3）、source/app/src/main/kotlin/cloudphone/source/ScreenCapture.kt（t4 与 2s 插桩窗口）、source/app/src/main/kotlin/cloudphone/source/EncoderPipeline.kt（t5）、client/app/src/main/kotlin/cloudphone/client/DecodeRenderActivity.kt（t6）
- 消费方：scripts/measure_latency.py（触摸口径 P50/P95 统计）
- 守卫测试：无

## 溯源

- 2026-11-09 client-ux-and-input-latency（spec/changes/client-ux-and-input-latency/）— 新建本页：确立六段插桩、静屏合成节奏根因结论、输入窗口合成驱动机制与验收阈值（模拟器 800ms / 真机 300ms）。
