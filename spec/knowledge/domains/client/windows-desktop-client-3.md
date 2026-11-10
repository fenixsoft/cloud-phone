# Windows 桌面串流客户端（续 3）

<!-- knowledge: capability=windows-desktop-client rev=1
     sources=spec/changes/windows-desktop-client
     part=3 -->

桌面端三辅助通道的消费侧：音频下行播放、剪贴板双向同步与文字输入，jitter 与超时纪律逐条对齐 aux-channels-m3 既有语义（主页：[windows-desktop-client](windows-desktop-client.md)；会话核心与输入映射见[续 2](windows-desktop-client-2.md)）。

## 规则

- 音频下行：AUDIO_CHUNK(ADTS AAC-LC 48kHz stereo) 经 JavaCV 解码为 PCM 16bit，经 javax.sound.sampled（SourceDataLine）播放；jitter 语义对齐 aux-channels-m3——120ms 起播水位、播放队列 32 帧上限、控制通道积压 >800ms 丢最旧音频帧保视频不阻塞，丢弃计入统计；源端侧静音 3s 降 1fps keepalive 等行为不变，桌面端只做对等消费。
- 音量与可用性：音量控制在播放端做 PCM 缩放（音量 popover 滑杆即时生效，不影响传输与解码）；音频不可用时给出明确原因文案（AuxNotice/降级原因上抛 UI），禁止静默无声。
- 剪贴板双向：客户端粘贴经 CLIPBOARD_SET 下发源端（等待回执 10s 超时降级提示，非静默）；源端复制经 CLIPBOARD_GET/CLIPBOARD_TEXT 回执写入本地剪贴板（同样 10s 超时降级）。
- 文字输入：文字输入面板经 TEXT_INPUT{seq,utf8} 注入完整 Unicode（含中文），每条等待 ACK{seq}（5s 超时提示失败），面板呈现等待/成功/失败状态；seq 去重语义由源端保证。会话窗口键盘焦点下可打印字符键直发同走 TEXT_INPUT（见[续 2](windows-desktop-client-2.md) 输入映射）。
- AuxNotice 降级：降级通告驱动对应工具坞按钮置灰并显示原因；不可用态一律明示、禁止静默失败（对齐 aux-channels-m3 降级纪律）。

## 代码锚点

- 主实现：client-windows/src/main/kotlin/cloudphone/desktop/session/AudioSink.kt（AAC 解码与 jitter 播放）、client-windows/src/main/kotlin/cloudphone/desktop/aux/AuxBridge.kt（剪贴板/TEXT_INPUT 超时对账与 AuxNotice 映射）
- 消费方：client-windows/src/main/kotlin/cloudphone/desktop/ui/SessionScreen.kt（音量 popover/文字面板/工具坞置灰）
- 守卫测试：client-windows/src/test/kotlin/cloudphone/desktop/AudioSinkTest.kt（合成 ADTS 断言解码与 jitter 丢弃）、client-windows/src/test/kotlin/cloudphone/desktop/AuxBridgeTest.kt（超时与回执两路径）

## 溯源

- 2026-11-10 windows-desktop-client（spec/changes/windows-desktop-client/）— 新建本页：确立桌面端音频下行 jitter 对齐（120ms 水位/32 帧上限/>800ms 丢音频保视频）、播放端音量、剪贴板双向 10s 超时、TEXT_INPUT 5s ACK 对账与 AuxNotice 置灰降级规则。
