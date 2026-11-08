## ADDED Requirements

### Requirement: 硬解渲染
客户端 MUST 使用 MediaCodec H.264 硬解并以 SurfaceView/SurfaceTexture 渲染视频；局域网路径首帧出画 MUST ≤2s。

#### Scenario: 首帧快速出画
- **WHEN** 客户端在局域网路径连接会话
- **THEN** 2s 内画面可见且持续更新

### Requirement: 触摸回传
客户端 MUST 采集触摸 down/move/up 以归一化坐标 + displayId 经控制通道回传，事件间隔 ≤30ms 不丢失起止。

#### Scenario: 滑动完整回传
- **WHEN** 用户在画面上做一次连续滑动
- **THEN** 源端收到 down→若干 move→up 完整序列并注入虚拟屏

### Requirement: 客户端手势语义（云端应用范式）
客户端返回手势（边缘侧滑）MUST 触发远端 back 而非本机退出；Home 手势 MUST 结束当前会话回到会话抽屉且不向远端发送 Home。

#### Scenario: 返回手势发远端 back
- **WHEN** 用户在会话全屏内做边缘侧滑返回手势
- **THEN** 远端 App 收到 back 键事件，客户端会话不退出

#### Scenario: Home 手势回抽屉
- **WHEN** 用户做 Home 手势
- **THEN** 客户端退出全屏回到会话抽屉，远端无 Home 事件

### Requirement: 连接 UI 与会话抽屉
客户端 MUST 提供手动连接 UI（host:port、token、模式选择：整屏/单应用选包名）与挂机会话列表抽屉（M0 文字列表，可点入恢复观看）。

#### Scenario: 手动连接建立
- **WHEN** 用户输入正确地址与 token 选择单应用模式并连接
- **THEN** 显示可观看会话或发起指定包名的新会话

### Requirement: 实时统计显示
客户端 MUST 实时显示当前会话 fps、码率、RTT、丢包率，刷新 ≥1Hz。

#### Scenario: 统计面板更新
- **WHEN** 会话播放中
- **THEN** 统计面板数值每秒更新且与 STATS 消息一致
