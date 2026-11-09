# aux-channels-m3 — 音频下行、剪贴板双向、文字输入三辅助通道

## ADDED Requirements

### Requirement: 音频捕获与下行
源端 SHALL 经 AudioPlaybackCapture（API 29+）捕获远端 App 音频，AAC-LC 48kHz stereo 96kbps 编码为 20ms 帧，AUDIO_CHUNK 经控制 TCP 记录通道下行；PTS SHALL 与视频同钟（SystemNanoTime）；RMS 静音持续 3s SHALL 停发并保持 1/s keepalive。

#### Scenario: 音频端到端播放
- **WHEN** 源端播放有声内容、客户端加入会话并开启音频
- **THEN** 客户端在 ≤500ms 内听到声音，AudioTrack 持续消费 20ms 帧（无连续断流）

#### Scenario: 静音零流量
- **WHEN** 源端内容静音持续 ≥3s
- **THEN** AUDIO_CHUNK 流量降为 1 帧/s keepalive，恢复出声后正常帧率恢复

#### Scenario: 低版本降级
- **WHEN** 源端 API <29
- **THEN** 音频通道不建立，客户端明确显示「源端系统不支持音频捕获」（非静默无声音）

### Requirement: 音视频同钟与 jitter 播放
音频帧 PTS SHALL 与视频 PTS 同源（SystemNanoTime）；客户端 SHALL 以 120ms 起播水位的 jitter buffer 播放，缓冲不足时 skip 并计数，控制通道积压超阈值时丢最旧音频帧保视频。

#### Scenario: 积压防护
- **WHEN** 控制通道积压超阈值（看门狗判定）
- **THEN** 丢弃最旧音频帧、视频不受阻塞，skip 计数进统计

### Requirement: 剪贴板双向（IME 焦点代理）
源端 SHALL 内置极简 IME（CloudImeService，并入 source APK，BIND_INPUT_METHOD）作焦点代理：客户端粘贴→CLIPBOARD_SET→IME setPrimaryClip（全版本可用）；源端→客户端复制经 CLIPBOARD_GET→CLIPBOARD_TEXT 回执，仅在 IME 活跃或源端 App 前台时可用，否则客户端「复制」按钮置灰并提示原因。

#### Scenario: 客户端到源端粘贴
- **WHEN** 客户端点「粘贴」且源端已启用 CloudImeService
- **THEN** 源端剪贴板内容更新（源端输入框粘贴得到客户端文本）

#### Scenario: 源端到客户端复制降级
- **WHEN** 源端无输入框焦点且 App 不满足前台读取条件
- **THEN** 客户端「复制」按钮置灰并显示原因提示（非静默失败/超时）

#### Scenario: 输入法未启用引导
- **WHEN** CloudImeService 未启用（无 adb 环境源端）
- **THEN** 源端设置页入口跳系统输入法设置（ACTION_INPUT_METHOD_SETTINGS），客户端剪贴板/键盘按钮降级提示「需启用云剪贴板输入法」

### Requirement: 文字输入
TEXT_INPUT{seq,utf8} SHALL 经源端 IME commitText 注入完整 Unicode（含中文）；IME 未启用且文本纯 ASCII 时兜底 shellhelper `input text`（空格转义）；非 ASCII 且无 IME SHALL 明确报错；每条 SHALL 有 ACK{seq} 回执（成功/失败原因），seq 去重。

#### Scenario: 中文注入
- **WHEN** 客户端文本面板输入中文并发送、源端 IME 活跃
- **THEN** 源端输入框出现完整中文文本，客户端收到 ACK{seq} 成功

#### Scenario: ASCII 兜底
- **WHEN** IME 未启用且文本为纯 ASCII（含空格）
- **THEN** 经 shellhelper input text 注入成功（空格正确转义），ACK 成功

#### Scenario: 非 ASCII 无 IME 报错
- **WHEN** IME 未启用且文本含非 ASCII
- **THEN** 回执失败并提示「需启用云剪贴板输入法」，不静默丢字

### Requirement: 客户端控制条 UI
会话控制条 SHALL 新增 键盘（文本面板，TEXT_INPUT 发送）/粘贴/复制 按钮，音频独立开关；所有不可用态 SHALL 给出具体原因文案（系统版本/输入法未启用/无焦点）。

#### Scenario: 控制条可用性
- **WHEN** 会话建立后打开控制条
- **THEN** 三按钮可见，不可用项置灰且有原因提示；音频开关独立于视频

### Requirement: 协议容错验证
新 MsgType 0x0C–0x10 SHALL 不 bump PROTOCOL_VERSION；老端/未知类型容错路径 SHALL 有单测覆盖（未知 MsgType 丢弃不 crash 不误处理）。

#### Scenario: 未知消息容错
- **WHEN** 对端收到未定义 MsgType 的记录
- **THEN** 丢弃该记录，连接与会话不受影响（单测断言）
