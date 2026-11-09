# Proposal: m3-audio-clipboard-textinput

## Why

M3 里程碑补齐三块体验缺口：①远端 App 有声无音（游戏/视频音画不同步缺失音频通道）；②剪贴板单向断裂（Android 10+ 后台剪贴板读写需焦点，客户端↔源端复制粘贴不通）；③文字输入缺中文通路（现有注入仅坐标/按键事件，IME 组合输入无法传递）。M0 验收矩阵已验证视频+触摸主干，本 SPEC 在既有传输与协议栈上扩展三条辅助通道。用户立项原话包含「音频/剪贴板/文字输入（M3）」。

## What Changes

- 新增源端音频捕获与下行通道：AudioPlaybackCapture（API 29+，低版本如实降级）+ AAC-LC 48kHz stereo 96kbps（20ms 帧），AUDIO_CHUNK 走控制 TCP 记录通道（带宽小、可靠有序），PTS 与视频同钟；RMS 静音 3s 停发（1/s keepalive）；客户端 jitter buffer 120ms 播放
- 新增剪贴板双向通道：源端内置极简 IME（CloudImeService，并入 source APK）作焦点代理绕过 Android 10+ 后台剪贴板限制——客户端粘贴按钮→CLIPBOARD_SET→IME setPrimaryClip；源端→客户端方向在 IME 活跃或源端 App 前台时可用（否则按钮置灰降级）
- 新增文字输入通道：TEXT_INPUT{seq,utf8}→IME commitText（完整中文支持），兜底 shellhelper `input text`（仅 ASCII）；新 MsgType 0x0C–0x10 五条消息；IME 启用进 bootstrap_adb.sh（`ime enable/set`）+ 无 adb 时设置页引导
- 客户端会话控制条新增 键盘/粘贴/复制 按钮；协议未知类型容错验证补齐

## Capabilities

### New Capabilities

- `aux-channels-m3`：音频下行捕获播放、剪贴板双向同步、文字输入三辅助通道（协议消息、IME 焦点代理、客户端 UI、降级路径）

### Modified Capabilities

- `stream-protocol-core`：MsgType 0x0C–0x10 新增、未知类型容错行为验证

## Impact

- **影响代码面**：source（StreamService/AudioCapture 新增/CloudImeService 新增）、protocol（Messages 新消息类型）、client（DecodeRenderActivity 控制条/音频播放器/剪贴板服务）、scripts（bootstrap_adb.sh IME 启用）、docs（device-notes 手册）
- **兼容性**：三通道全部可独立关闭；老客户端收到新 MsgType 走既有容错路径（需验证补齐）；协议版本不 bump
- **风险**：平台限制如实呈现——音频 capture 是「拷贝」不静音源端本地外放（同时出声）；剪贴板反向依赖 IME 活跃状态（不可用即 UI 置灰，不静默失败）；低版本 Android 无 AudioPlaybackCapture（API 29 门槛）
