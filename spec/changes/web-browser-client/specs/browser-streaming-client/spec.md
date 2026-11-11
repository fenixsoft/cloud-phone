# browser-streaming-client

## ADDED Requirements

### Requirement: 连接认证与会话编排
浏览器端 SHALL 以 `HELLO{token, p2pOffer:""}`（无加密块 = none 路径）发起连接，并 MUST 以 `START_SESSION{sessionId, mode, w/h/fps/bitrateKbps}` 建立会话——HELLO 自身不建会话，其 ACK 的 sessionId/分辨率/档位均为占位 0，真实会话参数以 START_SESSION 响应的 HELLO_ACK(0x02) 为准（sessionId/w/h/fps/bitrateKbps/encoder/displayId）。token 错误 MUST 呈现 REJECT_BAD_TOKEN 明确失败，不静默重试。

#### Scenario: 正确 token 建会话出画
- **WHEN** 用户输入正确 token 并连接，浏览器依次完成 HELLO → START_SESSION{mode=FULLSCREEN, 档位}
- **THEN** 页面展示 HELLO_ACK 返回的真实 sessionId 与分辨率，且视频帧开始上屏

#### Scenario: 错误 token 显式失败
- **WHEN** 用户输入错误 token 连接
- **THEN** 页面呈现认证失败（REJECT_BAD_TOKEN）与原因，不进入视频画面

### Requirement: 应用列表与单应用切换
存在 ACTIVE 会话时，浏览器 SHALL 经 `APP_LIST_REQUEST` 拉取应用网格；点选应用 MUST 经 `START_SESSION{mode=SINGLE_APP, packageName}` 切入单应用。**每次 START_SESSION 源端挂起旧会话并产生新 sessionId**，浏览器 MUST 立即换用新 sessionId（后续 INPUT_EVENT/STOP 全部携带）并按新 HELLO_ACK 参数重置坐标映射基准。

#### Scenario: 切入单应用
- **WHEN** 用户在应用网格点选一个应用
- **THEN** 画面切换为该应用虚拟屏，后续触控事件携带新 sessionId 且在源端该虚拟屏生效

#### Scenario: 切换后旧 sessionId 失效
- **WHEN** 用户从应用 A 切换到应用 B
- **THEN** 浏览器采用 B 的 HELLO_ACK 新 sessionId，旧 A 的 sessionId 不再出现在任何后续消息中

### Requirement: 断线自动重连（ATTACH）
WS 意外断开后，浏览器 SHALL 以指数退避自动重连（上限 30s 窗口），用记忆的 sessionId 走 `START_SESSION{sessionId=N, mode=ATTACH}` 恢复；附着由源端自动触发关键帧，浏览器 MUST NOT 依赖 IDR_REQUEST（该消息仅作用于整屏共享采集）。恢复后画面出显时间符合协议 ≤1s 口径；超过窗口则回连接页并带原因。

#### Scenario: 断网恢复续看
- **WHEN** 会话进行中网络中断 5 秒后恢复
- **THEN** 浏览器自动重连并以 ATTACH 恢复原 sessionId，收到关键帧后画面继续，无需用户手动操作

#### Scenario: 超窗回退
- **WHEN** 源端长时间不可达超过 30s 退避窗口
- **THEN** 浏览器停止重试并回连接页，展示最近一次失败原因

### Requirement: 视频解码与渲染
浏览器 SHALL 以 WebCodecs `VideoDecoder`（Annex B、`optimizeForLatency`、`prefer-hardware`）解码 H.264 Annex B 帧并绘制到 canvas；连接时 MUST 先 `isConfigSupported` 探测，不支持时呈现含浏览器名称的明确错误页而非黑屏。

#### Scenario: 支持的浏览器出画
- **WHEN** 在支持 WebCodecs 的浏览器建立会话
- **THEN** 首个关键帧解码后上屏，后续帧按 ptsUs 顺序逐帧渲染

#### Scenario: 不支持时显式报错
- **WHEN** `isConfigSupported` 返回不支持
- **THEN** 页面显示"当前浏览器不支持硬解（附浏览器名）"错误页，不进入黑屏等待

### Requirement: 音频播放与显式降级
浏览器 SHALL 剥除 AUDIO_CHUNK 的 ADTS 头构造 AudioSpecificConfig 喂 `AudioDecoder('mp4a.40.2')`，经 AudioWorklet 环形缓冲播放，起播水位 120ms、不足即 skip 并计数（对齐协议 §3.13 客户端口径），并兼容源端静音抑制（3s 停发 + 1 帧/s keepalive）。`window.AudioDecoder` 缺失时 MUST 显式降级：置灰音频并提示原因，视频与触控不受影响。

#### Scenario: 音频正常播放
- **WHEN** 会话建立且源端非静音
- **THEN** 环形缓冲达 120ms 水位后开始连续播放，无级进 SKIP 计数增长异常

#### Scenario: 无 AudioDecoder 降级
- **WHEN** 浏览器不支持 AudioDecoder
- **THEN** 工具条音频控件置灰并显示"音频不可用（浏览器不支持）"，画面与触控照常工作

### Requirement: 输入回传与辅助通道
浏览器 SHALL 将指针事件映射为 `INPUT_EVENT`（经 letterbox 裁剪线性映射到会话分辨率后归一化 ×65535 u16；越界钳制；BACK 按钮与桌面 Esc → action=3），文本经隐藏输入框走 `TEXT_INPUT(kind=0)` 并以 ACK 提示成败，剪贴板读写走 `CLIPBOARD_GET/SET` 且 `CLIPBOARD_TEXT(kind=1)` 时置灰并 toast 原因。源端 v1 忽略 pointerId（单点合成），UI MUST NOT 承诺多点/捏合手势。

#### Scenario: 点击坐标正确回传
- **WHEN** 用户在 canvas 显示区域某点单击
- **THEN** 源端 logcat `cloudphone-touch` 出现 t3 注入打点，且换算后的归一化坐标与点击位置一致（含 letterbox 边界外点击被钳制的情形）

#### Scenario: BACK 生效
- **WHEN** 用户点击工具条返回键或按 Esc
- **THEN** 源端注入 action=3，前台应用收到返回键行为

### Requirement: 入站控制应答与时钟统计
浏览器 MUST 应答源端 1Hz 入站 PING（回 `Pong{nonce, senderUs, echoUs=nowUs}`），并消费源端 1Hz 推送的 STATS。时钟估计口径：`RTT = t_recv − t_send`，`offset ≈ echoUs − (t_send + t_recv)/2`；每帧管线延迟 = `now() − (ptsUs + offset)`。HUD SHALL 展示 fps、码率、RTT、管线延迟 P50/P95、解码队列深度与 seq 缺口丢帧数，并可落 `docs/web-latency-samples.json`。

#### Scenario: 入站 PING 被应答
- **WHEN** 会话进行中源端按 1Hz 发来 PING
- **THEN** 浏览器对每个 PING 回送 Pong（源端 smoothedRttMs 得以维持，音频积压 watchdog 保持有感知）

#### Scenario: HUD 呈现延迟数据
- **WHEN** 会话稳定运行至少 10 秒
- **THEN** HUD 显示非空的 RTT 与管线延迟 P50/P95，seq 缺口计数与实际丢帧一致

### Requirement: 档位选择与校验
浏览器 SHALL 提供档位选择（720p/1080p × 30/60fps × 4/8/16/20Mbps，默认 720p60/8Mbps），本地仅做区间校验（width 320–2560、height 240–2560、fps 15–60、bitrate 1000–20000kbps），档位经 START_SESSION 下发，/16 对齐交由源端。

#### Scenario: 自定义档位下发
- **WHEN** 用户选择 1080p/60fps/16Mbps 并建会话
- **THEN** START_SESSION 携带所选参数，HELLO_ACK 返回源端 /16 对齐后的实际档位且 HUD 呈现一致

### Requirement: 结束语义
浏览器 SHALL 提供显式"结束"按钮发送 `STOP_SESSION`（无响应消息，fire-and-forget）；直接关闭页面 MUST 保持挂机语义（会话可在下次连接的 HELLO_ACK.hungSessions 中看到并 ATTACH）。

#### Scenario: 显式结束销毁会话
- **WHEN** 用户点击"结束"后重新连接并列出 hungSessions
- **THEN** 该会话不再出现（虚拟屏与 App 已销毁）

#### Scenario: 关页挂机可恢复
- **WHEN** 用户直接关闭页面后再次用同 token 连接
- **THEN** HELLO_ACK.hungSessions 包含原会话，可一键 ATTACH 恢复
