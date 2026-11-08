## ADDED Requirements

### Requirement: 整屏采集
源端获得 MediaProjection 授权后 MUST 持续采集主屏画面并送入编码管线；屏幕旋转或分辨率变化 MUST 重建编码管线并立即发送 IDR。

#### Scenario: 授权后持续出帧
- **WHEN** 用户在模拟器上同意 MediaProjection 弹窗并开始整屏会话
- **THEN** 编码器持续产出 H.264 帧且无人工干预持续 ≥60s

### Requirement: 单应用虚拟屏模式
具备 shell bootstrap 权限时，源端 MUST 能创建虚拟屏并在其上启动指定包名的 App；该会话的画面采集 MUST 仅来自该虚拟屏。

#### Scenario: 指定应用在虚拟屏启动
- **WHEN** 客户端发送 START_SESSION{package=负载App} 且 shell bootstrap 就绪
- **THEN** 源端创建虚拟屏、在该屏启动目标 App，客户端仅看到该 App 画面

### Requirement: 硬件编码管线
源端 MUST 使用 MediaCodec H.264 编码，支持按会话参数配置分辨率/帧率/码率，并支持随时响应 IDR_REQUEST。

#### Scenario: 动态 IDR
- **WHEN** 源端收到 IDR_REQUEST
- **THEN** 200ms 内编码器产生关键帧并发出

### Requirement: 会话挂机与恢复
传输断开 MUST 只停止编码与传输，MUST NOT 销毁虚拟屏或杀死其上的 App 进程；相同 token 重连 MUST 恢复同一会话画面。源端 MUST 支持至少 2 个单应用会话同时挂机。

#### Scenario: 断开挂机
- **WHEN** 客户端断开连接 30s 后重连
- **THEN** 源端虚拟屏与 App 进程未销毁，重连后 1s 内恢复该会话画面

#### Scenario: 多会话并存
- **WHEN** 相继挂机两个不同 App 的单应用会话
- **THEN** 两个虚拟屏与 App 均存活，客户端可任选其一观看

### Requirement: 输入注入
源端 MUST 将收到的 INPUT_EVENT 注入对应 display（触摸 down/move/up）；注入坐标按会话分辨率归一化换算。

#### Scenario: 触摸命中目标
- **WHEN** 客户端在画面按钮位置发送触摸事件
- **THEN** 目标 App 在虚拟屏上产生对应点击响应（画面出现可观测变化）
