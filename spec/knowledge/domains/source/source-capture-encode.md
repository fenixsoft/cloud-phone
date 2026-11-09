# 源端采集编码与会话挂机

<!-- knowledge: capability=source-capture-encode rev=1
     sources=spec/changes/m0-a2a-streaming-pipeline -->

源端 App 的画面生产侧：MediaProjection 整屏采集、虚拟屏单应用采集、MediaCodec H.264 硬编、输入注入与会话生命周期管理，是「云端手机」范式的源端载体。

## 规则

- 整屏采集：获得 MediaProjection 授权后持续采集主屏画面送编码管线；屏幕旋转或分辨率变化重建编码管线并立即发送 IDR。虚拟屏必须经 `MediaProjection.createVirtualDisplay` 携带投影令牌创建——直接 DisplayManager AUTO_MIRROR 会因缺 CAPTURE_VIDEO_OUTPUT 权限静默拒投。
- 单应用模式：具备 shell bootstrap（特权 shell 助手进程）能力时创建虚拟屏并在其上启动指定包名 App，该会话画面仅来自该虚拟屏；单应用在模拟器镜像上不可用（app_process 助手被静默 SIGKILL），以 HELLO_ACK 携带明确原因拒绝，真机可用。
- 编码管线：MediaCodec H.264（video/avc）硬编，按会话参数配置分辨率/帧率/码率；收到 IDR_REQUEST 200ms 内产生关键帧（PARAMETER_KEY_REQUEST_SYNC_FRAME 即时触发）。
- 会话挂机与恢复：传输断开只停止编码与传输，不销毁虚拟屏、不杀其上 App 进程；同 token 重连 1s 内恢复同一会话画面；源端支持至少 2 个单应用会话同时挂机。前台服务内 SessionManager 持有虚拟屏与 App，会话生命周期优先于连接生命周期。
- 旋转/分辨率变化走零令牌换面复用同一投影实例（同一投影实例二次 createVirtualDisplay 受镜像级限制），换面后按新参数适配并即时请求 IDR。
- 输入注入：INPUT_EVENT 注入对应 display（down/move/up），坐标按会话分辨率归一化换算到目标屏。
- 采集参数钳制与适配：请求宽高钳制在 width 320–2560、height 240–2560（另有 fps 15–60、码率 1000–20000kbps）并做偶数对齐；以请求短边为基准按源屏宽高比等比适配（aspect-exact，/16 对齐）。

## 代码锚点

- 主实现：source/app/src/main/kotlin/cloudphone/source/ScreenCapture.kt（采集与管线适配）、source/app/src/main/kotlin/cloudphone/source/EncoderPipeline.kt（H.264 编码）
- 单源符号：source/app/src/main/kotlin/cloudphone/source/VirtualDisplaySession.kt（虚拟屏会话）、source/app/src/main/kotlin/cloudphone/source/SessionManager.kt（会话生命周期与挂机）
- 消费方：source/app/src/main/kotlin/cloudphone/source/StreamService.kt（控制面驱动）、source/app/src/main/kotlin/cloudphone/source/input/InputInjector.kt（输入注入）、source/app/src/main/kotlin/cloudphone/source/shell/ShellBootstrap.kt（特权能力探测）
- 守卫测试：无（行为验收经 validation-harness 矩阵与真机手册覆盖）

## 溯源

- 2026-11-08 m0-a2a-streaming-pipeline（spec/changes/m0-a2a-streaming-pipeline/）— 新建本页：确立整屏/单应用采集、硬编管线、挂机恢复与输入注入规则。
