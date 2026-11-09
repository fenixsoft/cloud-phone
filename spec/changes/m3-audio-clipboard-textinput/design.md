# Design: m3-audio-clipboard-textinput

## Context

- 协议现状：PROTOCOL_VERSION=1，MsgType 0x01–0x0B 已占，0x0C 起可扩展；控制通道是 TCP 记录式（Framing.writeRecord），可靠有序——适合承载低带宽辅助流；视频走 UDP 片通道（或回落控制通道）
- 平台硬约束（Android 10+）：后台 App 剪贴板读写需持有焦点 → 传统「源端 Service 直接读剪贴板」已不可行；焦点代理必须是 IME（InputMethodService 天然持焦点）
- 音频捕获硬约束：AudioPlaybackCapture 需 API 29+，且捕获的是「拷贝」（源端本地照常外放，无法静音源端）；模拟器镜像 AudioPlaybackCapture 可用性待验证（真机复核步骤进手册）
- 编码器可用性：AAC-LC（MediaCodec AAC）API 全量可用；Opus 编码器 API 30+ → 选 AAC-LC 48kHz stereo 96kbps 保兼容
- 时钟基线：视频 PTS 已用 SystemNanoTime 单调钟——音频 PTS 同钟对齐，客户端做音视频同步播放
- IME 限制：InputMethodService 不能在无用户交互下弹 UI；commitText 注入文本无需可见键盘 UI（极简 service 即可，不做键盘面板）
- shellhelper `input text` 仅 ASCII（空格需转义），中文必须走 IME commitText

## Goals

1. 音频：源端远端 App 声音下行到客户端播放，音画同步（同钟 PTS + jitter buffer），静音零流量
2. 剪贴板：客户端→源端粘贴全版本可用；源端→客户端复制在 IME 活跃或前台时可用，不可用时 UI 明确降级
3. 文字输入：中文/全字符集输入到源端 App（IME commitText 主路径，ASCII 兜底 shell），回执确认已启用输入法

## Non-Goals

- 不做麦克风上行（用户没提，隐私敏感，源端 App 声音才是要串的内容）
- 不做剪贴板历史/多板管理（单条当前剪贴板）
- 不做 IME 可见键盘 UI（CloudImeService 只做焦点代理与文本注入，不渲染键盘）
- 不做 OCR 屏幕取词（剪贴板通道已覆盖复制需求）

## Decisions

### D1 音频链路：AAC-LC 96kbps 20ms 帧走控制 TCP 通道，静音零流量
- 捕获：AudioPlaybackCapture（API 29+；API<29 无音频通道，连接页如实标注「源端系统不支持」）； playbackConfig 匹配远端 App usage（GAME/MEDIA）
- 编码：AAC-LC 48kHz stereo 96kbps，20ms 帧（960 samples，~2.4KB/帧）；MediaCodec 异步模式，PTS=捕获时 SystemNanoTime（与视频同钟）
- 传输：AUDIO_CHUNK 走控制 TCP 记录通道——96kbps 带宽远小于控制通道富余，TCP 保可靠有序（音频丢帧比延迟更伤体验，宁阻塞不丢）；TCP 阻塞防护：PING/PONG 看门狗监测控制通道积压（既有心跳扩展 RTT 统计），积压超阈值丢最旧音频帧（jitter buffer skip）
- 播放：客户端 AudioTrack + jitter buffer 120ms 起播水位，缓冲不足 skip（记 skip 计数）；音量独立控制不联动视频
- 静音：源端 RMS<阈值持续 3s 停发，仅每秒 1 帧 keepalive（防 NAT 映射老化与通道活性验证）——游戏loading/文字界面零流量

### D2 剪贴板：IME 焦点代理双向不对称，UI 如实降级
- CloudImeService：极简 InputMethodService 并入 source APK（单 APK 不新增进程），manifest `BIND_INPUT_METHOD`+`android.service.inputmethod` intent；功能=①`setPrimaryClip`（客户端→源端方向：CLIPBOARD_SET 消息→IME 调用，持焦点合法）②读剪贴板上报（源端→客户端：IME 活跃且源端 App 有焦点时 `getPrimaryClip`→CLIPBOARD_TEXT）
- 反向限制如实：源端→客户端需 IME 活跃（源端输入框获焦）或源端 App 前台可读（Android 10+ 规则内）；不可用时客户端「复制」按钮置灰+提示「需源端输入框获焦」，不静默失败
- 启用引导：bootstrap_adb.sh 增 `ime enable cloudphone/.ime.CloudImeService`+`ime set`（模拟器/有 adb 真机全自动）；无 adb 场景源端设置页「启用云剪贴板输入法」按钮跳 ACTION_INPUT_METHOD_SETTINGS，未启用时剪贴板按钮同样降级提示

### D3 文字输入：TEXT_INPUT→commitText 主路径，ASCII 兜底 shell
- TEXT_INPUT{seq,utf8}：客户端文本面板（多行）发送，源端 IME commitText 完整 Unicode（中文/emoji）；seq 用于回执对账（重复投递去重）
- 兜底：IME 未启用时若文本纯 ASCII → shellhelper `input text`（空格转义处理）；非 ASCII 且无 IME → 明确报错提示启用输入法（不静默丢字）
- 回执：源端回 ACK{seq}，客户端面板显示「已注入/失败原因」
- 硬键键盘映射不动：既有按键事件通道保留，TEXT_INPUT 是组合文本补充

### D4 协议扩展：0x0C–0x10 五条消息，不 bump 版本
AUDIO_CHUNK(0x0C)/CLIPBOARD_SET(0x0D)/CLIPBOARD_GET(0x0E)/CLIPBOARD_TEXT(0x0F)/TEXT_INPUT(0x10)；全部为增量消息，老端未知类型走容错分发（不 crash 不误处理）——**未知类型容错行为需补验证**（现状代码有容错分发但无测试覆盖，本 SPEC 补单测）。协商不引入新握手字段（三通道均为运行期可用性探测，能力经 HELLO 既有字段无需变更；音频/剪贴板不可用即不发对应消息，客户端按超时/回执感知降级）。

### D5 客户端 UI：控制条三按钮 + 文本面板
DecodeRenderActivity 会话控制条增「键盘」（弹文本面板，TEXT_INPUT 发送）「粘贴」（CLIPBOARD_SET+本地剪贴板内容）「复制」（CLIPBOARD_GET→等待 CLIPBOARD_TEXT 回执，10s 超时降级提示）；音频独立静音按钮（会话级）。所有降级路径 UI 文案明确原因（系统版本/输入法未启用/无焦点），禁止静默失败。

## Risks

- 模拟器镜像 AudioPlaybackCapture 行为与真机差异（捕获不到远端 App 音频）→ verification 分「捕获通道打通」与「真机复核」两层记录，手册步骤兜底
- TCP 控制通道承载音频在弱网下阻塞视频回落路径（控制通道同时承载视频回落）→ 看门狗积压丢弃策略保视频优先，数据进 verification-report
- IME 与输入框焦点时序竞态（CLIPBOARD_SET 时源端无焦点输入框）→ IME setPrimaryClip 不依赖具体输入框（全局剪贴板），风险低；commitText 需焦点，无焦点时回执明确报错
