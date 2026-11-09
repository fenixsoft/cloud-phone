# Tasks: m3-audio-clipboard-textinput

## Deliverables

```yaml
- path: source/app/src/main/kotlin/cloudphone/source/AudioCaptureService.kt
  kind: new
- path: source/app/src/main/kotlin/cloudphone/source/ime/CloudImeService.kt
  kind: new
- path: source/app/src/main/AndroidManifest.xml
  kind: modify
- path: source/app/src/main/kotlin/cloudphone/source/StreamService.kt
  kind: modify
- path: protocol/src/main/kotlin/cloudphone/protocol/Messages.kt
  kind: modify
- path: protocol/src/test/kotlin/cloudphone/protocol/MessagesTest.kt
  kind: modify
- path: client/app/src/main/kotlin/cloudphone/client/DecodeRenderActivity.kt
  kind: modify
- path: client/app/src/main/kotlin/cloudphone/client/AudioPlayer.kt
  kind: new
- path: client/app/src/main/kotlin/cloudphone/client/AuxChannelClient.kt
  kind: new
- path: scripts/bootstrap_adb.sh
  kind: modify
- path: docs/device-notes.md
  kind: modify
```

## 1. 协议扩展（protocol）

- [x] 1.1 `protocol/src/main/kotlin/cloudphone/protocol/Messages.kt` 新增 MsgType 0x0C–0x10（AUDIO_CHUNK{pts,seq,payload}/CLIPBOARD_SET{text}/CLIPBOARD_GET/CLIPBOARD_TEXT{text}/TEXT_INPUT{seq,utf8}+ACK 回执），sealed ControlMessage 扩展，编解码往返对称（以 0x0D–0x11 替代验收：0x0C 已被已合入基线的 P2P_MIGRATE 占用，五条消息重编号 0x0D–0x11，线格式字段布局不变）（用户裁决 2026-11-09 18:02 接受 0x0D–0x11 替代验收）
- [x] 1.2 未知 MsgType 容错单测补齐进 `protocol/src/test/kotlin/cloudphone/protocol/MessagesTest.kt`（未定义类型丢弃、连接不受影响、0x0C–0x10 编解码往返断言）（以 0x0D–0x11 往返断言替代验收：编号随 1.1 重编号，单测已补齐并全绿）（用户裁决 2026-11-09 18:02 接受 0x0D–0x11 替代验收）

## 2. 源端音频（source）

- [x] 2.1 `source/app/src/main/kotlin/cloudphone/source/AudioCaptureService.kt`：AudioPlaybackCapture（API 29+，usage GAME/MEDIA 匹配）→ MediaCodec AAC-LC 48kHz stereo 96kbps 20ms 帧，PTS=SystemNanoTime 同视频钟；RMS 静音 3s 停发+1/s keepalive；API<29 返回不可用状态
- [x] 2.2 StreamService 集成：AUDIO_CHUNK 写控制 TCP 记录通道，PING/PONG 看门狗扩展控制通道积压监测（超阈值丢最旧音频帧保视频）；会话结束释放捕获

## 3. 源端 IME 与输入/剪贴板（source）

- [x] 3.1 `source/app/src/main/kotlin/cloudphone/source/ime/CloudImeService.kt`：极简 InputMethodService（无键盘 UI），onCreateInputView 返回零尺寸视图；commitText 注入、setPrimaryClip、IME 活跃时读剪贴板上报 CLIPBOARD_TEXT
- [x] 3.2 `source/app/src/main/AndroidManifest.xml` 注册 IME（BIND_INPUT_METHOD + android.service.inputmethod intent-filter + xml/method.xml）
- [x] 3.3 StreamService 辅助消息处理：CLIPBOARD_SET→IME 粘贴、CLIPBOARD_GET→读剪贴板回 CLIPBOARD_TEXT（无焦点/未启用回错误回执）、TEXT_INPUT→IME commitText 主路径+ACK 回执、兜底 shellhelper input text（纯 ASCII 空格转义）、seq 去重
- [x] 3.4 源端设置页「启用云剪贴板输入法」入口（ACTION_INPUT_METHOD_SETTINGS，检测 IME 未启用时显示）

## 4. 客户端（client）

- [x] 4.1 `client/app/src/main/kotlin/cloudphone/client/AudioPlayer.kt`：AudioTrack + 120ms 起播水位 jitter buffer，不足 skip 计数，独立音量/静音开关
- [x] 4.2 `client/app/src/main/kotlin/cloudphone/client/AuxChannelClient.kt`：AUDIO_CHUNK 消费/解译、TEXT_INPUT 发送与 ACK 对账（超时回执）、CLIPBOARD_SET/GET/TEXT 往返与 10s 超时降级
- [x] 4.3 `client/app/src/main/kotlin/cloudphone/client/DecodeRenderActivity.kt` 控制条：键盘（文本面板多行发送）/粘贴/复制三按钮+音频静音按钮；不可用态置灰与原因文案（源端版本/输入法未启用/无焦点）

## 5. 环境与文档

- [x] 5.1 `scripts/bootstrap_adb.sh` 增 IME 启用（ime enable/set CloudImeService）与启用状态自检输出
- [x] 5.2 `docs/device-notes.md` 增补：音频平台限制说明（capture 是拷贝不静音源端外放、API29 门槛）、剪贴板方向性限制（反向需 IME 活跃）、文字输入路径表（IME/ASCII 兜底/报错）、真机复核步骤（音频捕获、粘贴往返、中文输入）
- [x] 5.3 verification-report：0x0C–0x10 编解码与容错单测结果、模拟器端到端三通道验证（音频播放/粘贴往返/中文注入）、降级路径验证（API<29/IME 未启用/无焦点置灰）（以 0x0D–0x11 编解码与容错单测结果替代验收：编号随 1.1 重编号，报告 docs/m3-aux-channels-verification-report.md 已含收编适配说明 §0）（用户裁决 2026-11-09 18:02 接受 0x0D–0x11 替代验收）
