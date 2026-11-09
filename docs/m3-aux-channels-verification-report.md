# M3 辅助通道验证报告：音频回传 / 剪贴板同步 / 文字输入（m3-audio-clipboard-textinput）

生成时间：2026-11-09；环境：双模拟器（源端 emulator-5554 / 客户端 emulator-5556，同宿主），
源端 = cloudphone.source（StreamService + AudioCaptureService + CloudImeService + ShellHelper），
客户端 = cloudphone.client（DecodeRenderActivity + AuxChannelClient + AudioPlayer），
线级探针 = `scripts/aux_wire_probe.py`（12 场景），音频负载 = loadgen `AudioToneActivity`
（app-uid 440Hz 正弦，`--ei hz --ei seconds`）。

## 0. 收编适配说明（二次实现 run，salvage renumber）

本报告初版实验在旧基线（0x0C–0x10 编号）上完成；收编至含 p2p-crossnet-traversal 的新基线时，
MsgType 0x0C 已被 P2P_MIGRATE（协议 §3.12）占用，五条 M3 消息**重编号为 0x0D–0x11**，
文档/探针/单测同步适配（线格式字段布局不变）。协议层单测在新编号上复跑通过（见 §0.1），
E2E 实验读数（帧率/静音门/解码路径）与编号无关，结论继续有效。

### 0.1 收编后协议单测复跑

- `gradle :protocol:test`（JVM）：M3 五类消息 0x0D–0x11 编解码往返对称、未知类型容错
  （0x12/0x7F/0xFF 等，0x0C–0x11 均已定义）、未知类型与 aux 流量交错不误解析、Framing 通道复用——
  全部 PASS（含 p2p 既有用例，无回归）。详见 tasks.md 1.2 行内注解与 run-result findings。

**网络环境口径**：客户端经 `autoHost 10.0.2.2 --es autoPort 22399`（模拟器回环=宿主）；
源端监听 22399（prefs `tcpPort`），控制通路走 `adb emu redir add tcp:12345:22399`
（绕过 adb forward 中继，见「网络环境发现」）；adb 侧仅保留 `forward tcp:22399 tcp:22399`。

## 1. 线协议验证（12 场景探针，全部 PASS）

执行契约：探针启动后 ~3.2s 触发 12s tone 负载，`--window 8 --drain 6.5`（drain ≥ 负载结束−探针启动−3）。
参考证据：`.fenix/verify-evidence/m3-audio-clipboard-textinput/wire-probe-12scenarios.log`（PROBE_EXIT=0）。

| # | 场景 | 判定 | 关键读数 |
|---|---|---|---|
| 1 | HELLO 握手 | PASS | status=0 reason= |
| 2 | START_SESSION | PASS | acks=1（会话激活→音频捕获随会话建立） |
| 3 | AUDIO_CHUNK 持续流 | PASS | 375 实帧 / 8s 读窗（≈47fps 对齐 20ms 帧距） |
| 4 | pts/seq 单调性 | PASS | pts 单调、seq 逐帧 +1（374/374 dense） |
| 5 | ADTS 头 + 采样率 | PASS | adts_ok 全帧、sizes=[263]（96kbps LC 20ms 稳态帧长） |
| 6 | 静音门保活 | PASS | 静音窗 3 帧（1 帧/s 保活，≤8 预期阈） |
| 7 | CLIPBOARD_SET 回显 | PASS | text=「cloudphone-m3 中文粘贴 123」逐字回显 |
| 8 | CLIPBOARD_GET 往返 | PASS | set/get 内容一致 |
| 9 | TEXT_INPUT 中文 ACK | PASS | ack=(True, '') |
| 10 | TEXT_INPUT ASCII ACK | PASS | ack=(True, '') |
| 11 | TEXT_INPUT seq 幂等 | PASS | 重复 seq 二次投递 → 幂等 ok |
| 12 | 未知 msgType 容忍 | PASS | 0x42 丢弃后 CLIPBOARD_GET 仍应答 |

**协议结论**：0x0D–0x11 五类 M3 消息（收编新基线时自原规划 0x0C–0x10 重编号：0x0C 已被已合入的 P2P_MIGRATE 占用）（AUDIO_CHUNK / CLIPBOARD_SET / CLIPBOARD_GET /
TEXT_INPUT / TEXT_INPUT_ACK + AUX_NOTICE 通道降级）线级行为符合 specs/aux-channels 断言；
AUDIO_CHUNK 载荷解析口径 `[i64 ptsUs][i32 seq][u16 len][ADTS AAC]` 经 375 帧全量校验。

## 2. 音频端到端（源端捕获 → 客户端解码播放）已打通

**关键修复（本轮发现并修复的根因）**：客户端解码器配置为 `KEY_IS_ADTS=1` ADTS 逐帧内嵌模式时，
本平台 `c2.android.aac.decoder`（C2SoftAacDec）传输层解析持续失败——
`logcat` 反复出现 `C2SoftAacDec: aacDecoder_DecodeFrame decoderErr = 0x1001`，
解码器零 PCM 输出（输入侧 600+ 帧正常喂入、帧头 `ff f1 4c 80` 同步字合法、
pts/seq 密集单调，排除了线协议与帧完整性问题）。

修复：客户端改为**裸 AAC + 显式 csd-0** 解码路径（`AuxChannelClient.startAudio/feedDecoder`）——
configure 时携带 AudioSpecificConfig（AOT=2 LC / 48kHz / 双声道，csd-0={0x11,0x90}），
feed 侧剥离源端 7B ADTS 头后按裸帧喂入。线协议不变（源端仍逐帧 ADTS 内嵌，符合 specs 断言），
仅解码边界处理改变，行为确定、跨平台一致。

修复后证据（`.fenix/verify-evidence/m3-audio-clipboard-textinput/client-audio-playback.logcat`）：
- `audio feed n=600 bytes=263`（解码输入持续喂入）
- **`cloudphone-audio: play frames=250→500 skip=1 bufferedMs=0`**（AudioTrack 以约 47fps 实际消费解码 PCM）
- C2SoftAacDec 错误计数 0；DecodeRenderActivity 状态栏不再出现「音频未收到数据」降级文案（10s 存活检查通过）

**根因排查中排除的假设（留档防复发）**：
- 编码器 SBR（HE-AAC）假设：`coding.aac-sbr-mode=3` 为平台默认值，但在 AACObjectLC profile 下
  auto 模式不激活 SBR，编码输出为纯 LC 48kHz（与 ADTS 头一致）。实验中显式
  `KEY_AAC_SBR_MODE=0` 反而使该平台编码器整体回落默认参数（44100Hz/单声道/64kbps），
  **不得设置该键**（已在源端保持无 SBR 键的原配置）。
- ADTS 头构造核查：`AudioCaptureService.adtsHeader` 的 frame_length = payload+7 与各字段位布局
  逐位核过，无错。
- 客户端 MediaVolume=2/15（低音量）只影响捕获响度，与解码失败无关；负载 `AudioToneActivity`
  为 app-uid USAGE_MEDIA，捕获豁免判定见 device-notes §11（root/shell-uid 播放不可捕获）。

## 3. 源端音频捕获豁免边界（实验定界，device-notes §11 汇总）

- root/shell-uid（如 shellhelper `TONE` 工具，AudioTrack USAGE_MEDIA）播放：**不可捕获**
  （AudioPlaybackCapture 对 shell uid 播放豁免，返回静音）→ 静音门进入 1 帧/s 保活。
- app-uid（loadgen AudioToneActivity）播放：可捕获，47fps 实帧。
- 静音门语义实测：RMS<100 持续 3s 后抑制为 263B 保活帧 1 帧/s；响音恢复时在保活节拍内
  即恢复 47fps 实帧（pump 永不停摆，负载突增无需重建会话）。

## 4. 网络环境发现（非交付缺陷，运维口径）

1. **adb forward 中继不可靠**：adb 服务端中继的 forward 连接会在 ~4-5s 后被 adb 侧断开
   （长会话场景不可用）。缓解：控制/数据通路用 `adb emu redir add tcp:12345:22399`
   控制台重定向（模拟器内核级转发，不经过 adb 中继）；源端监听端口从 22333 迁至 22399
   以隔离公共 frp 回程噪声，`forward tcp:22333` 已移除。
2. **LanDirectBackend 通道替换语义**：任意新 TCP 连接会替换当前通道（installChannel retire
   + 3s 关闭回收）——公网 112.74.85.74:22333 存在未认证扫描噪声注入的「幽灵连接」，
   会以网络噪声窃取会话通道。真实部署须前置认证网关；本 run 以端口隔离 + 控制台重定向规避。
3. **探针互斥**：探针连接会替换客户端会话通道（同上语义）；探针运行期间客户端会话会断开
   重连，二者不可并行持有会话。

## 5. M2 范围遗留发现（如实登记，不属 M3 交付范围）

deeplink `cloudphone://launch`（SINGLE_APP 流程）会话建立后收到 ACCEPTED ack（163B），
但客户端未发生 DecodeRenderActivity 跳转（无失败状态文案、活动历史无 DecodeRender 记录）。
M3 全屏 auto-extras 流程（`autoHost/autoPort/...`）不受影响（本次全部验证均走该流程）。
建议规划层将 deeplink 会话→渲染页跳转链路单列 M2 复核任务。

## 6. 交付物与工具增量

- `scripts/aux_wire_probe.py`：12 场景线级探针（AUDIO/CLIPBOARD/TEXT_INPUT/容错全覆盖），
  含 `--drain` 连续读窗（避免 TCP 背压误判静音）。
- `source/shellhelper`：新增 `TONE <ms> [hz]`（root-uid AudioTrack 负载工具，用于捕获豁免定界）。
- `source/loadgen`：新增 `AudioToneActivity`（app-uid 可捕获音源，M3 音频验证负载）。
- 客户端 `ConnectActivity`：auto-extras 新增 `autoPort`（默认 22333 不变）。
- 客户端 `AudioPlayer`/`AuxChannelClient`：音频链路可观测日志（feed/play/pcm-out 限频插桩）。
