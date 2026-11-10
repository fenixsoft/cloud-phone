# M3 辅助通道（音频下行 / 剪贴板双向 / 文字输入）

<!-- knowledge: capability=aux-channels-m3 rev=1
     sources=spec/changes/m3-audio-clipboard-textinput -->

视频+触摸主干之外的三条辅助通道：远端 App 音频下行播放、客户端↔源端剪贴板同步、完整 Unicode 文字注入。三通道均复用控制 TCP 记录通道承载与既有消息容错机制，可独立降级，不可用态一律明示原因、禁止静默失败。

## 规则

- 消息编号与回执复用：AUDIO_CHUNK=0x0D、CLIPBOARD_SET=0x0E、CLIPBOARD_GET=0x0F、CLIPBOARD_TEXT=0x10、TEXT_INPUT=0x11，全部走控制 TCP 记录通道，不 bump PROTOCOL_VERSION、无新增握手字段；通道能力运行期探测，经回执/降级路径呈现。CLIPBOARD_TEXT(0x10) 以 kind 区分应答体：kind=0 剪贴板文本回执、kind=1 辅助通道降级通告 AuxNotice；TEXT_INPUT(0x11) 同样 kind=0 上行注入、kind=1 注入回执 ACK。
- 音频捕获：源端经 AudioPlaybackCapture 捕获远端 App 音频（匹配 usage GAME/MEDIA），平台门槛 API 29+ 且需 MediaProjection 授权；API<29 音频通道不建立，回 AuxNotice「源端系统不支持音频捕获」（非静默无声）。捕获是拷贝语义——源端本地照常外放，串流不静音源端；shell 身份播放的音频被系统豁免不可捕获，仅应用进程的 GAME/MEDIA 播放可被捕获。
- 音频编码与下行：AAC-LC 48kHz stereo 96kbps，20ms 帧（960 samples，输出带 7B ADTS 头）；帧 PTS 取 elapsedRealtimeNanos 微秒，与视频编码器同一单调钟（音画同钟）。静音判定 RMS<100（约 -50dBFS）持续 3s 停发，仅保 1 帧/s keepalive（防 NAT 映射老化与通道活性验证），恢复出声即回稳态帧率。
- 音频积压防护（视频优先）：源端音频下行队列有界 25 帧（≈500ms），满丢最旧；PING/PONG 看门狗监测控制通道积压，平滑 RTT 超过 800ms 即清空积压音频保视频不阻塞，丢弃计入统计。
- 音频播放：客户端 jitter buffer 以 120ms（6 帧）起播水位播放，缓冲不足 skip 并计数；播放队列上限 32 帧（≈640ms）；客户端剥 ADTS 头以裸 AAC（csd-0 显式配置）解码。静音开关实现为 AudioTrack 音量 0，不停止接收与缓冲，音量独立于视频。
- 剪贴板客户端→源端方向：CLIPBOARD_SET 经源端内置输入法 CloudImeService setPrimaryClip 写入，IME 持焦点合法，全 Android 版本可用；成功回 CLIPBOARD_TEXT 文本回显供单飞对账。
- 剪贴板源端→客户端方向（不对称）：CLIPBOARD_GET 读剪贴板仅在 IME 活跃（源端输入框获焦）或源端 App 满足前台可读条件时可用；不可用回 AuxNotice 明示原因（无获焦输入框/输入法未启用），客户端「复制」按钮置灰并提示原因；客户端等待回执超时 10s 降级提示。
- 输入法启用双通路：有 adb 环境由 bootstrap 脚本 `ime enable/set` 启用 CloudImeService 并自检；无 adb 环境源端设置页检测未启用时显示入口，跳系统输入法设置（ACTION_INPUT_METHOD_SETTINGS），客户端剪贴板/键盘按钮同步降级提示。
- 文字输入主路径：TEXT_INPUT{seq, utf8} 经源端 IME commitText 注入完整 Unicode（中文/emoji），需获焦输入框；每条必有 TextInputAck{seq, ok, reason} 回执；seq 去重（LRU 64：重复投递幂等补发成功回执、不重复注入）；客户端 ACK 对账超时 5s。
- 文字输入兜底：IME 未启用且文本纯 ASCII（0x20–0x7E 含空格）经 shell 助手 `input text` 注入（空格转义在助手侧）；非 ASCII 且无 IME 回失败回执「需启用云剪贴板输入法」（不静默丢字）；既有按键事件通道不受影响，TEXT_INPUT 是组合文本补充。
- 会话控制条四按钮：键盘（多行文本面板）/粘贴/复制/音频开关；AuxNotice 驱动可用性——输入法未启用置灰键盘/粘贴/复制，无获焦输入框置灰键盘/复制，系统不支持置灰音频；不可用按钮附具体原因文案。
- 未知 MsgType 容错是三通道的共同前提：未定义类型记录整段丢弃（Unknown 保留原始字节），不 crash 不误处理不中断会话（守卫规则见 stream-protocol-core 能力页）。

## 关键行为

- 音频端到端：源端出声后客户端约 120ms 水位内起播，稳态 ≈50 帧/s；静音 3s 后流量降至 1 帧/s，恢复出声即在保活节拍内回稳态帧率。
- 粘贴往返：客户端「粘贴」取本地剪贴板→CLIPBOARD_SET→源端回 CLIPBOARD_TEXT 回显对账；源端输入框粘贴即得客户端文本。
- 中文注入：文本面板发送→IME commitText→源端输入框出现完整文本→ACK 成功；重复发送同 seq 幂等不重复注入。
- IME 被禁用即时降级：桥接置空后剪贴板/文本消息立即回未启用回执；源端设置页回到前台复检并显示启用入口。

## 代码锚点

- 主实现（源端捕获/焦点代理）：source/app/src/main/kotlin/cloudphone/source/AudioCaptureService.kt、source/app/src/main/kotlin/cloudphone/source/ime/CloudImeService.kt
- 会话集成与消息处理：source/app/src/main/kotlin/cloudphone/source/StreamService.kt（音频队列/看门狗/CLIPBOARD_*/TEXT_INPUT 分支）
- 消费方（客户端播放与对账）：client/app/src/main/kotlin/cloudphone/client/AudioPlayer.kt、client/app/src/main/kotlin/cloudphone/client/AuxChannelClient.kt、client/app/src/main/kotlin/cloudphone/client/DecodeRenderActivity.kt（控制条）
- 消息定义：protocol/src/main/kotlin/cloudphone/protocol/Messages.kt（0x0D–0x11 常量与编解码）
- 守卫测试：protocol/src/test/kotlin/cloudphone/protocol/MessagesTest.kt（0x0D–0x11 往返对称、未知类型丢弃）
- 环境与手册：scripts/bootstrap_adb.sh（ime enable/set）、docs/device-notes.md（§11 平台限制与真机复核步骤）

## 溯源

- 2026-11-10 m3-audio-clipboard-textinput（spec/changes/m3-audio-clipboard-textinput/）— 新建本页：确立音频下行（捕获/编码/静音零流量/视频优先积压防护/jitter 播放）、剪贴板双向（IME 焦点代理、方向不对称降级）、文字输入（commitText 主路径/ASCII 兜底/seq 去重）与控制条降级规则；辅助消息经裁决定编号 0x0D–0x11（原规划 0x0C–0x10，0x0C 已被 P2P_MIGRATE 占用）。
