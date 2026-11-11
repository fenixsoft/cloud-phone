# cloudphone 线协议规范（M0）

本文是 `protocol/` 共享模块所实现线协议的完整描述。协议常量与封包逻辑仅在
`protocol/src/main/kotlin/cloudphone/` **单一定义**（M1 将以 C++ 复刻同一定义）。
按本文手工构造的消息，与 protocol 模块构造的结果逐字节一致。

- 实现真相源：`protocol/src/main/kotlin/cloudphone/protocol/Messages.kt`、`Framing.kt`
- 单元测试：`protocol/src/test/kotlin/cloudphone/protocol/FramingTest.kt`（编解码往返、分片重组）

## 1. 总则

| 项 | 约定 |
|---|---|
| 字节序 | **大端**（network order），所有多字节整数与 IEEE-754 float 均按大端字节序 |
| 字符串 | `u16 字节长度 + UTF-8 字节`（长度不含自身 2 字节） |
| 字段对齐 | **紧凑排列，无填充**：字段按表序逐字节写出，不含 C struct 对齐空洞（M1 C 化按同序逐字段 pack/unpack） |
| 布尔/枚举 | 单字节 `u8`，取值见各表 |
| 未知消息 | 收到未定义 `msgType` 的控制记录 **MUST 忽略且不中断会话**（记录头已给出载荷长度，整段跳过即可） |
| 通道 | 控制消息走可靠流通道（TCP/frp-TCP）；视频默认走 UDP（分片+重组），frp/TCP 模式降级为流记录承载视频 |

## 2. 封帧层（Framing）

### 2.1 流记录（TCP 通道，控制+视频复用）

```
[u8 recordType][u32 payloadLen][payload ...]
```

| recordType | 含义 | payload |
|---|---|---|
| `0x01` | 控制消息 | `[u8 msgType][字段...]`（§3） |
| `0x02` | 视频帧 | 视频载荷（§2.2） |

- `payloadLen` 为大端 u32，不含 5 字节记录头。
- 单记录上限 8 MiB（`MAX_RECORD_BYTES`），超限视为线路损坏，关闭连接。
- 流式封装：连续记录首尾相接，按长度前缀逐条读取。

### 2.2 视频载荷（流记录 payload 或 UDP 数据报）

```
[u32 sequence][u64 ptsUs][u8 flags][u16 fragIdx][u16 fragCnt][u32 totalLen][分片字节...]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `sequence` | u32 | 帧序号，发送侧单调递增（回绕自然溢出）；缺号可统计为丢包 |
| `ptsUs` | u64 | 源端**单调时钟**微秒时间戳（`SystemClock.elapsedRealtimeNanos()/1000`），端到端延迟测量依据 |
| `flags` | u8 | bit0 = 关键帧（IDR，`FLAG_KEYFRAME=0x01`），其余位保留为 0 |
| `fragIdx` | u16 | 本分片下标，从 0 起 |
| `fragCnt` | u16 | 本帧总分片数（单分片帧 = 1） |
| `totalLen` | u32 | 整帧（未分片）字节长度 |
| 分片字节 | bytes | 本分片承载的帧数据片段 |

- 帧头固定 **21 字节**（`VIDEO_HEADER_BYTES`）。
- UDP 通道：单帧 > MTU 时分片，每片自带 fragIdx/fragCnt；**UDP 数据报 = `[u8 recordType=0x02] + 视频载荷分片`**，默认按载荷 ≤1200B 分片（`DEFAULT_MAX_DATAGRAM`，为 IP/UDP 头留余量）。
- 接收端按 sequence 聚片重组：乱序分片先缓存（含首片未到时的中间片孤儿缓存），凑齐出帧；超时（默认 500ms）未凑齐整帧丢弃并计入丢帧统计；已出帧 sequence 的重放分片静默忽略。

## 3. 控制消息（msgType 一览）

| msgType | 名称 | 方向 | 说明 |
|---|---|---|---|
| `0x01` | HELLO | C → S | 握手与配对认证 |
| `0x02` | HELLO_ACK | S → C | 握手应答（≤500ms） |
| `0x03` | START_SESSION | C → S | 发起新会话或附着挂机会话 |
| `0x04` | STOP_SESSION | C → S | 结束会话（单应用模式销毁虚拟屏与 App） |
| `0x05` | IDR_REQUEST | C → S | 请求关键帧（源端 ≤200ms 产生 IDR） |
| `0x06` | INPUT_EVENT | C → S | 输入事件（触摸/远端 back） |
| `0x07` | STATS | 双向 | ≥1Hz 统计（**源端 → 客户端为持续 1Hz 推送**，浏览器观看端 HUD 即消费该下行；客户端→源端方向为 M0 观测上报，浏览器端不发送） |
| `0x08` | PING | 双向 | RTT/时钟偏移探测（**勘误（web-browser-client）**：非单向——客户端自发 1Hz PING 之外，源端亦有 1Hz 主动 PING 推送（StreamService.kt:696-701 看门狗 RTT 探测），浏览器端 MUST 应答之；两端各自以收到的 PING 回 PONG） |
| `0x09` | PONG | 双向 | PING 应答（Pong{nonce, senderUs, echoUs=nowUs}，方向随触发端） |
| `0x0A` | APP_LIST_REQUEST | C → S | 请求枚举源端可启动应用（请求-应答制） |
| `0x0B` | APP_LIST | S → C | 可启动应用列表应答（会话在线才应答） |
| `0x0C` | P2P_MIGRATE | 双向（经信令通道） | 端点迁移：对端地址变化后的新候选推送（p2p-crossnet-traversal 扩展，会话不重建） |
| `0x0D` | AUDIO_CHUNK | S → C | 音频块（M3 辅助通道，控制 TCP 下行，20ms AAC 帧） |
| `0x0E` | CLIPBOARD_SET | C → S | 写源端剪贴板（IME 焦点代理） |
| `0x0F` | CLIPBOARD_GET | C → S | 请求读源端剪贴板（请求-应答制） |
| `0x10` | CLIPBOARD_TEXT | S → C | 剪贴板文本回执（kind=0）或辅助通道错误回执（kind=1） |
| `0x11` | TEXT_INPUT | C → S / S → C | 组合文本注入（kind=0，C→S）或注入回执 ACK（kind=1，S→C） |

控制记录 payload 首字节即 msgType，其后按各消息字段表排列。

### 枚举常量（跨消息共用）

**WatchMode（观看模式）**

| 值 | 名称 | 说明 |
|---|---|---|
| 1 | FULLSCREEN | 整屏（MediaProjection 主屏） |
| 2 | SINGLE_APP | 单应用（虚拟屏 + 包名） |
| 3 | ATTACH | 附着到已挂机会话（sessionId 必填） |

**InputAction（输入动作）**

| 值 | 名称 |
|---|---|
| 0 | DOWN |
| 1 | UP |
| 2 | MOVE |
| 3 | BACK（远端 back 键，边缘侧滑手势触发） |

**TransportKind（STATS.transport）**

| 值 | 名称 |
|---|---|
| 0 | LAN_DIRECT |
| 1 | P2P |
| 2 | FRP |
| 3 | P2P_FALLBACK_FRP（打洞失败回落） |

### 3.1 HELLO（0x01）

| 序 | 字段 | 类型 | 说明 |
|---|---|---|---|
| 1 | version | u16 | 协议版本（M0 = 1） |
| 2 | token | str | 配对 token，不匹配即拒绝 |
| 3 | mode | u8 | WatchMode |
| 4 | packageName | str | 单应用模式目标包名（整屏/附着为空串） |
| 5 | width | u16 | 请求分辨率宽（0 = 源端默认） |
| 6 | height | u16 | 请求分辨率高 |
| 7 | fps | u8 | 请求帧率 |
| 8 | bitrateKbps | u32 | 请求码率 kbps |
| 9 | sessionId | i32 | ATTACH 模式的目标会话 id（其余为 0） |
| 10 | p2pOffer | str | 客户端候选集 `"ip:port,ip:port;nat=sym"`（多候选：SRFLX 多节点 + 本地网卡候选；`;nat=sym` 尾缀 = 本端判定 symmetric；空串 = 不打洞。M0 单候选文本为其 n=1 特例） |
| 11 | 加密协商块 | 34B（可选） | 仅当客户端开启加密时携带：`[u8 cryptoAlgo][u8 encryptOn][32B cryptoNonce]`。老端（v1 初版）忽略整块（按剩余长度判定有无），PROTOCOL_VERSION 不变 |

- **加密协商块**：`cryptoAlgo` ∈ CryptoAlgo（§7.5）；`encryptOn` = 客户端开关意图；
  `cryptoNonce` = 32B 随机（每次握手重生成，HKDF salt 材料）。

> **勘误/补记（web-browser-client）：takeover 尾字节**。HELLO 线载荷在加密协商块之后
> 还有一个**可选 1 字节** takeover 标志（`Messages.kt:386`：`if (msg.takeover) 1 else 0`，
> Windows SessionController.kt:81/338 可携带）。语义边界：该字节只在**传输层**生效——
> `LanDirectBackend.kt:190` / `FrpBackend.kt:181` 解析后抢占现役通道（退役 incumbent，
> 其客户端收 DISCONNECTED，会话转挂机可恢复）；**会话层完全不读**（StreamService/SessionManager
> 无 takeover 引用）。老端解码器对任意尾字节容忍（按剩余长度截断）。**浏览器观看端 v1
> 永不发送 takeover**、不做抢占/互踢；对现役通道被占（REJECT_BUSY）一律显式呈现原因。

### 3.2 HELLO_ACK（0x02）

| 序 | 字段 | 类型 | 说明 |
|---|---|---|---|
| 1 | status | u8 | 错误码（§5） |
| 2 | reason | str | 拒绝原因（接受时空串） |
| 3 | sessionId | i32 | 源端分配的会话 id（拒绝时 0） |
| 4 | width | u16 | 实际会话分辨率宽 |
| 5 | height | u16 | 实际会话分辨率高 |
| 6 | fps | u8 | 实际帧率 |
| 7 | bitrateKbps | u32 | 实际码率 |
| 8 | encoder | str | 编码器名（如 `OMX.qcom.video.encoder.avc`） |
| 9 | displayId | i32 | 采集目标 display id（拒绝时 -1） |
| 10 | p2pAnswer | str | 源端候选集 `"ip:port,ip:port;nat=sym"`（格式同 p2pOffer；空串 = 不打洞） |
| 11 | hungCount | u16 | 挂机会话数 N |
| 12 | N × 挂机项 | — | `{i32 sessionId, u8 mode, str packageName, u16 width, u16 height}` |
| 13 | 加密协商块 | 34B（可选） | 仅当源端接受加密协商时携带：`[u8 cryptoAlgo][u8 encryptOn][32B cryptoNonce]`（协商结果与 server nonce，§7.6 时序） |

### 3.3 START_SESSION（0x03）

| 序 | 字段 | 类型 | 说明 |
|---|---|---|---|
| 1 | sessionId | i32 | 新会话传 0（源端分配）；附着传目标 id |
| 2 | mode | u8 | WatchMode |
| 3 | packageName | str | 单应用包名 |
| 4 | width | u16 | 分辨率宽 |
| 5 | height | u16 | 分辨率高 |
| 6 | fps | u8 | 帧率 |
| 7 | bitrateKbps | u32 | 码率 |

**目标档位语义（resolution-passthrough）**：客户端在 width/height（及 fps/bitrateKbps）填的是
**目标档位**，不是精确采集尺寸承诺。源端保证：

1. **短边吻合**：采集短边 = 请求档位短边（`min(width,height)`）；
2. **比例保真**：采集宽高比 = 源端自身屏幕真实宽高比（aspect-exact 等比适配，
   实现见 `ScreenCapture.buildPipeline`）——客户端 letterbox 显示无扭曲；
3. **/16 对齐偏差**：编码器要求宽高 16 对齐，等比缩放后逐维向下取整到 16 的倍数，
   允许 ±15px 级偏差。例：请求 1080x2400 于 1080x2400 同比例源屏 → 实际 ≈1080x2400；
   请求 720x1280 于 1080x2400（20.6:9）源屏 → 实际 720x1600（短边=720、源屏比例）。
   适配结果在源端日志 `capture: aspect-exact ...` 可见，并经 HELLO_ACK/会话 ACCEPTED
   的 width/height 字段回传实际值；
4. **默认档**：width=height=0（或 fps/bitrateKbps=0）→ 源端取默认 1280x720@60fps/8000kbps；
5. 范围钳制在源端 `EncoderPipeline.SessionParams.normalized`（width 320–2560、
   height 240–2560、fps 15–60、bitrate 1000–20000kbps）；客户端侧仅做同区间范围校验，
   对齐（/16）交给源端处理。

### 3.4 STOP_SESSION（0x04）

| 序 | 字段 | 类型 | 说明 |
|---|---|---|---|
| 1 | sessionId | i32 | 目标会话 id |

### 3.5 IDR_REQUEST（0x05）

| 序 | 字段 | 类型 | 说明 |
|---|---|---|---|
| 1 | sessionId | i32 | 目标会话 id |

### 3.6 INPUT_EVENT（0x06）

| 序 | 字段 | 类型 | 说明 |
|---|---|---|---|
| 1 | sessionId | i32 | 目标会话 id |
| 2 | action | u8 | InputAction |
| 3 | pointerId | u8 | 多点触控指针 id |
| 4 | x | u16 | 归一化横坐标 ∈ [0, 65535]（无符号，按会话分辨率换算） |
| 5 | y | u16 | 归一化纵坐标 ∈ [0, 65535] |

### 3.7 STATS（0x07）

| 序 | 字段 | 类型 | 说明 |
|---|---|---|---|
| 1 | sessionId | i32 | 会话 id |
| 2 | seq | i32 | 统计序号，单调递增 |
| 3 | fps | f32 | 帧率 |
| 4 | bitrateKbps | f32 | 码率 kbps |
| 5 | rttMs | f32 | 实测 RTT 毫秒 |
| 6 | lossPct | f32 | 丢包率百分比 |
| 7 | transport | u8 | TransportKind |

### 3.8 PING（0x08）

| 序 | 字段 | 类型 | 说明 |
|---|---|---|---|
| 1 | nonce | i64 | 随机数，PONG 原样回显 |
| 2 | senderUs | i64 | 发送侧单调时钟微秒 |

### 3.9 PONG（0x09）

| 序 | 字段 | 类型 | 说明 |
|---|---|---|---|
| 1 | nonce | i64 | 回显 PING.nonce |
| 2 | senderUs | i64 | 回显 PING.senderUs |
| 3 | echoUs | i64 | 响应侧单调时钟微秒 |

### 3.10 APP_LIST_REQUEST（0x0A）

| 序 | 字段 | 类型 | 说明 |
|---|---|---|---|
| 1 | requestId | i32 | 请求序号（客户端自选），APP_LIST 原样回显 |

- 客户端 → 源端，空载荷消息（仅 requestId）。**请求-应答制**：客户端不发该消息即零影响
  （老客户端兼容，无 BREAKING）；源端不主动推送。

### 3.11 APP_LIST（0x0B）

| 序 | 字段 | 类型 | 说明 |
|---|---|---|---|
| 1 | requestId | i32 | 回显 APP_LIST_REQUEST.requestId |
| 2 | appCount | u16 | 应用条目数 N |
| 3 | N × 条目 | — | `{str packageName, str label}` 逐条紧凑排列 |

- 源端 → 客户端。枚举规则：`PackageManager.queryIntentActivities(MAIN + LAUNCHER)`，
  排除源端 App 自身（`cloudphone.source`），其余应用（负载/第三方/系统应用）不过滤；
  包名去重，标签取 `loadLabel`（非空，异常时回退包名）。
- **应答条件与时序**：仅当会话在线（存在 ACTIVE 会话）才应答，目标 ≤1s（实测 <100ms）；
  无会话时静默忽略（客户端以 3s 超时判定列表获取失败）。

### 3.12 P2P_MIGRATE（0x0C）

| 序 | 字段 | 类型 | 说明 |
|---|---|---|---|
| 1 | candidates | str | 发起端新候选集（格式同 p2pOffer，多候选 + `;nat=sym` 尾缀） |

- 端点迁移（p2p-crossnet-traversal 2.3）：会话绑定 sessionId 不绑网络地址。任一端检测到
  本端出口映射变化（重跑 SRFLX 发现，候选文本变化）即经**信令通道**向对端发本消息；
  对端收到后 `migrateTo(newCandidates)` 显式更新对端地址并以 sequence=0 探针重连复用
  （探针帧恒明文，见 §7.4），视频流不断、统计不重置。
- 老端收到 0x0C：未知 msgType 按总则「忽略且不中断会话」处理。

### 3.13 AUDIO_CHUNK（0x0D，M3 aux-channels-m3）

| 序 | 字段 | 类型 | 说明 |
|---|---|---|---|
| 1 | ptsUs | i64 | 捕获时刻微秒时间戳，与视频 PTS 同钟（`SystemClock.elapsedRealtimeNanos()/1000`） |
| 2 | seq | i32 | 音频帧序号，发送侧单调递增（skip/对账统计依据） |
| 3 | payloadLen | u16 | AAC 载荷字节数 N |
| 4 | payload | bytes | AAC 访问单元（48kHz stereo 96kbps，20ms 帧；线协议含 ADTS 头，客户端解码侧剥除） |

- 源端 → 客户端。经控制 TCP 记录通道下行（可靠有序：音频丢帧比延迟更伤体验）。
- 静音零流量：源端 RMS 低于阈值持续 3s 后停发，仅保留 1 帧/s keepalive（防 NAT 老化与通道活性验证）。
- 客户端以 120ms 起播水位 jitter buffer 播放，缓冲不足 skip 并计数。

> 编号说明：本节五条消息原规划 0x0C–0x10，0x0C 已被 P2P_MIGRATE（3.12）占用，
> 收编新基线时重编号为 **0x0D–0x11**。

### 3.14 CLIPBOARD_SET（0x0E，M3）

| 序 | 字段 | 类型 | 说明 |
|---|---|---|---|
| 1 | text | str | 目标剪贴板文本（UTF-8，客户端本地剪贴板内容） |

- 客户端 → 源端。源端经内置 IME（CloudImeService，持焦点合法）`setPrimaryClip` 写入；
  成功回 CLIPBOARD_TEXT(kind=0) 应答，失败（IME 未启用/不可写）回 CLIPBOARD_TEXT(kind=1) 错误回执。

### 3.15 CLIPBOARD_GET（0x0F，M3）

- 空载荷（仅 msgType 字节）。客户端 → 源端，请求-应答制。
- 成功回 CLIPBOARD_TEXT(kind=0)；源端无焦点/IME 未启用回 CLIPBOARD_TEXT(kind=1) 错误回执。

### 3.16 CLIPBOARD_TEXT（0x10，M3）

| 序 | 字段 | 类型 | 说明 |
|---|---|---|---|
| 1 | kind | u8 | 0 = 剪贴板文本回执；1 = 辅助通道错误回执 |
| 2 | （kind=0）text | str | 剪贴板文本（UTF-8） |
| 2 | （kind=1）channel | u8 | AuxChannel：1=AUDIO 2=CLIPBOARD 3=TEXT_INPUT |
| 3 | （kind=1）reason | str | 失败/降级原因（无焦点、IME 未启用、源端系统不支持音频捕获等） |

- 源端 → 客户端。kind=1 为通用辅助通道错误回执：客户端据此置灰对应按钮并显示原因文案，
  非静默失败（spec aux-channels-m3 剪贴板/降级 Scenario）。

### 3.17 TEXT_INPUT（0x11，M3）

| 序 | 字段 | 类型 | 说明 |
|---|---|---|---|
| 1 | kind | u8 | 0 = 注入请求（C → S）；1 = 注入回执 ACK（S → C） |
| 2 | seq | i32 | 注入序号（客户端自选单调递增；ACK 原样回显，重复投递去重） |
| 3 | （kind=0）text | str | 注入文本（完整 Unicode，含中文/emoji） |
| 3 | （kind=1）ok | u8 | 1 = 注入成功；0 = 失败 |
| 4 | （kind=1）reason | str | 失败原因（无焦点/IME 未启用/非 ASCII 且无 IME 等；成功为空串） |

- 主路径：源端 IME `commitText` 注入（完整 Unicode）。兜底：IME 未启用且文本纯 ASCII
  → shellhelper `input text`（空格转义）；非 ASCII 且无 IME → ACK 失败（reason 说明）。
- 每条 TEXT_INPUT SHALL 有 ACK 回执（成功/失败原因）；seq 重复投递去重不重复注入。

## 3A. 辅助通道兼容性（M3）

- 五条新消息（0x0D–0x11）全部为增量扩展，**不 bump PROTOCOL_VERSION**：
  老端/未知 msgType 走既有容错路径（整记录忽略、连接不受影响）。
- 协商不引入新握手字段：三通道均为运行期可用性探测，音频/剪贴板不可用即不发对应消息
  或经 CLIPBOARD_TEXT(kind=1) 通告原因，客户端按超时/回执感知降级。

## 4. 握手与会话时序（文字版）

**首次连接（新会话，含加密协商，p2p-crossnet-traversal 1.3）**

```
client                          source
  │  TCP/frp-TCP connect           │
  │── HELLO{version,token,mode,   │
  │      packageName,w,h,fps,     │
  │      bitrate,p2pOffer,        │
  │      [加密协商块 algo=1,      │  校验 token（≤500ms）
  │       encryptOn,clientNonce]}►│  （token 不符 → REJECT_BAD_TOKEN + 关闭连接）
  │◄── HELLO_ACK{accepted,        │  加密协商：双方都带块且都 encryptOn → AES128GCM；
  │      sessionId,w,h,fps,       │  任一方缺块/关闭 → none（逐字节与 v1 现状一致）
  │      bitrate,encoder,         │
  │      displayId,p2pAnswer,     │
  │      hungSessions[],          │
  │      [加密协商块 algo,        │
  │       encryptOn,serverNonce]}─│
  │  双方各自 HKDF 派生方向子钥（§7.1），后续视频片/控制记录按 §7 加密
  │  （视频通道按后端建立：UDP 直连/公网 UDP/TCP 复用）
  │◄══ 视频帧（sequence=0 探针帧恒明文，首个视频帧为 IDR 关键帧）══│
  │── PING / ◄── PONG（≥1Hz RTT） │
  │◄── STATS ──/── STATS（≥1Hz）  │
  │── INPUT_EVENT（触摸/back）───►│
```

**端点迁移（p2p-crossnet-traversal 2.3）**

```
某端出口映射变化（如 Wi-Fi→蜂窝）
  │── P2P_MIGRATE{candidates=新候选集} ──►对端（信令通道，会话不重建）
  对端 migrateTo(newCandidates) 显式 setRemote + sequence=0 探针重连复用；
  视频流续传、统计（fps/码率/RTT/丢帧计数）连续不重置。
```

**附着挂机会话（重连 ≤30s，同 token）**

```
client                          source
  │── HELLO{token, p2pOffer:"",  │  传输层握手认证（占位参数，会话层不裁档）
  │      无加密块=none}─────────►│
  │◄── HELLO_ACK{accepted,       │
  │      hungSessions[]}────────│
  │── START_SESSION{sessionId=N, │  **勘误（web-browser-client）**：附着动作 =
  │      mode=ATTACH}──────────►│  START_SESSION（StreamService.handleStartSession:563-659），
  │◄── HELLO_ACK{accepted,       │  **非 HELLO{mode=ATTACH} 字段路径**（旧§4 时序图
  │      sessionId=N,真实参数}──│  已过时；HELLO.sessionId 字段不被会话层消费）。
  │◄══ 关键帧 + 后续视频（≤1s 出画）══│  **附着自动触发 IDR**（SessionManager.kt:137
  │                              │  capture.requestIdr / :145 vd.requestIdr）——客户端
  │                              │  **无需也不应再发 IDR_REQUEST**（该消息硬连线到整屏
  │                              │  共享采集 StreamService.kt:279，对 SINGLE_APP 无效）。
```

浏览器端断线重连即走此口径：指数退避（0.5s 起步封顶 5s，总窗口 30s）→ HELLO（同 token）
→ `START_SESSION{sessionId=N, mode=ATTACH}` → HELLO_ACK 真实参数恢复；超窗或被显式拒绝
（REJECT_BUSY / REJECT_UNSUPPORTED_MODE）即停止自动重试、回连接页带原因。

**会话结束（STOP_SESSION）**：源端销毁该会话虚拟屏与其上的 App 进程；仅断开连接（不 STOP）则进入挂机，会话保留在 HELLO_ACK.hungSessions 列表中。

## 5. 错误码（HELLO_ACK.status）

| 值 | 名称 | 含义 |
|---|---|---|
| 0 | ACCEPTED | 接受，携带会话参数 |
| 1 | REJECT_BAD_TOKEN | token 不匹配，拒绝并关闭连接。**加密协商拒绝复用同一错误码路径**：源端校验 token 失败时，无论协商块内容如何一律以此码拒绝，且在发送 HELLO_ACK 前不建立任何加密状态（p2p-crossnet-traversal 3.1：token 校验拒绝路径优先于加密协商） |
| 2 | REJECT_BUSY | **勘误（web-browser-client）：双来源语义**。① **传输层观看端占用闸门**（主路径）：`sourceBusyText` 恒非空（TransportBackend.kt:146 默认"源端正被其他设备观看"，全仓无清空点），存在现役通道时新 HELLO（无 takeover）一律被拒——LanDirectBackend.kt:130-135 / rejectPendingChannel:198-206、FrpBackend.kt:130 同构；现役通道断开即清闸（LanDirectBackend.kt:163），挂机会话可被下一个观看端 ATTACH。② **会话层 ATTACH 恢复失败**：StreamService.kt:628 以 REJECT_BUSY 回 `manager.lastError`（正落在浏览器断线重连主路径）。**会话层本身无并发上限**（旧"M0 ≥2 并存"描述已过时；`handleStartSession` 确无并存裁剪——"整屏+单应用并存"是同一观看端内既定特性）。浏览器端对该码一律显式呈现占用原因、不自动盲重试 |
| 3 | REJECT_VERSION | 协议版本不支持 |
| 4 | REJECT_UNSUPPORTED_MODE | 观看模式不支持（如无 shell 权限时的单应用模式降级拒绝） |

> 加密协商**没有独立错误码**：协商不一致（任一端不支持/关闭）不是错误，结果回落 `none`
> （§7.6 兼容矩阵），会话照常建立。

## 6. M1 C++ 化兼容性约定

1. **定宽原语**：所有字段使用定宽类型（u8/u16/u32/i32/i64/f32），M1 直接映射 `uint8_t/uint16_t/uint32_t/int32_t/int64_t/float`。
2. **大端 + 紧凑排列**：无 struct 对齐填充；C 侧 MUST 逐字段大端读写（或 `#pragma pack(1)` 后自行字节序转换），不得直接 memcpy 本机序结构体。
3. **字符串**：一律 `u16 长度前缀 + UTF-8`，无终结符；C 侧按长度边界切分。
4. **msgType/recordType/枚举值冻结**：本文所有常量数值为线上格式承诺，M1 不得改动数值含义；新增消息只能追加新 msgType，旧端按"未知消息忽略"兼容。
5. **版本协商**：HELLO.version 单调递增；源端支持版本集合包含客户端版本 → 继续，否则回 REJECT_VERSION。M0 唯一版本 = 1。语义扩展（新增字段）通过追加新消息类型实现，M0 不做消息内版本分叉。
6. **32 位帧序号**：sequence 线上宽度 u32，C 侧以 `uint32_t` 承载（回绕按无符号比较）。

## 7. 加密层（p2p-crossnet-traversal，CryptoCodec）

实现真相源：`protocol/src/main/kotlin/cloudphone/transport/CryptoCodec.kt`
（`CryptoCodecTest.kt` 覆盖往返/AAD 篡改拒绝/方向隔离/重放窗口/开销断言）。

### 7.1 密钥派生（RFC 5869 HKDF-SHA256）

```
PRK = HKDF-Extract(salt = clientNonce XOR serverNonce（32B 逐字节）, ikm = 配对 token)
子钥 = HKDF-Expand(PRK, info = "c2s" | "s2c", L = 16)   // 每方向独立 AES-128 密钥
```

- 双方 nonce 逐字节 XOR 作 salt：任一端 nonce 变化即换全部子钥（每次握手重生成 nonce，
  会话密钥不跨会话复用）。
- 方向以角色定义：client→source = `c2s`，source→client = `s2c`；两端只持有对端方向的
  解密钥，方向子钥互相不可解（测试断言）。

### 7.2 nonce 布局（12B，大端）

```
[u32 direction][u64 counter]     direction: c2s=1、s2c=2
```

- **视频片 counter**：由帧头确定性推导 `counter = sequence * 65536 + fragIdx`
  （u32 sequence × u16 fragIdx = 48bit 空间），线上**不传 counter**，双端按帧头重建
  nonce；同帧多片各占独立 counter，杜绝 GCM nonce 重用。
- **控制记录 counter**：可靠有序流上从 `2^62` 起单调自增（与视频 counter 空间隔离），
  接收端按到达顺序重建。

### 7.3 AAD 与 tag（AES-128-GCM 片级 AEAD）

| 载荷 | AAD | counter |
|---|---|---|
| 视频分片 | `[u32 sequence][u8 flags]`（帧头 sequence/keyframe 绑定，防重排/篡改） | `sequence*65536+fragIdx` |
| 控制记录 | `[u64 counter]` | 2^62 起单调 |

- tag 128bit；**密文输出 = ciphertext ‖ tag**，无独立头，每片开销恒 **+16B**
  （测试断言 ≤明文+18B）。
- 重放防护：每方向 512bit 滑动窗口位图（视频 `FragReplayGuard` 与控制 `ReplayWindow`
  分离）——重复/窗口外旧 counter 丢弃，正常序列不受影响。

### 7.4 探针帧恒明文与降级路径

- P2P 打洞探针 wire = `[0x02][sequence=0 帧头][1B 载荷]`，**恒明文**（`P2pBackend`
  短路、`CryptoCodec` 不做特判）：打洞探针必须先于密钥协商可用，且 sequence=0 探针
  不占用加密 counter 空间。
- 协商结果 `none` 时，加密/解密调用方直接透传原字节，**线路逐字节与 v1 现状一致**
  （`CryptoTransportTest` 回归断言）。

### 7.5 CryptoAlgo 枚举（协商块首字节）

| 值 | 名称 | 说明 |
|---|---|---|
| 0 | NONE | 明文，与协议 v1 现状逐字节一致 |
| 1 | AES128GCM | §7.1–7.3 片级 AEAD |

### 7.6 协商判定与老端兼容矩阵

**判定规则**（HELLO/HELLO_ACK 均按「剩余长度 ≥34B」判定协商块有无，PROTOCOL_VERSION
恒为 1 不变）：

```
enable = HELLO带块∧encryptOn ∧ HELLO_ACK带块∧encryptOn ∧ algo=1（AES128GCM）
否则 → none（含任一端缺块/开关关闭/algo 未知）
```

| 客户端 \ 源端 | 老端（无协商块） | 新端 encryptOn=false | 新端 encryptOn=true |
|---|---|---|---|
| 老端（无协商块） | none | none | none |
| 新端 encryptOn=false | none | none | none |
| 新端 encryptOn=true | none（老端按忽略未知字段兼容） | none | **AES128GCM** |

- 全矩阵会话均正常建立；仅 (true, true) 单元格启用 §7 加密。
- 老端新消息兼容：P2P_MIGRATE（0x0C）被老端按「未知消息忽略」处理，不影响会话。

## 8. 浏览器观看端对接注记（web-browser-client）

浏览器观看端（`client-web/app`，经 `client-web/gateway` WS↔TCP 纯字节中继）承载的是
**既有 frp/TCP 流记录模式逐字节同构**的协议：网关不做任何解析，`/ws` 上每条 WS 二进制
帧 = 一段连续字节流切片，流记录（§2.1）与控制消息（§3）语义完全不变。

### 8.1 连接与握手

- WS 端点 `ws(s)://<网关>:8090/ws`；网关 env：`GATEWAY_PORT`(8090) / `UPSTREAM`(127.0.0.1:22333) / `STATIC_DIR`（静态托管浏览器端构建产物）。
- HELLO：`p2pOffer=""` + **无加密协商块**（§7.6 兼容矩阵 none 路径）；`takeover` 尾字节**永不发送**（§3.1 补记）。
- 会话建立 = HELLO（占位参数）→ `START_SESSION{mode, w/h/fps/bitrateKbps}` → HELLO_ACK **真实参数**（sessionId/encoder/displayId 以此为准采用）。
- 显式失败不静默重试：REJECT_BAD_TOKEN / REJECT_BUSY（双来源语义见 §5）/ REJECT_UNSUPPORTED_MODE → 呈现原因，用户手动重试。

### 8.2 控制面（浏览器侧职责）

- 应答源端 1Hz 入站 PING：`Pong{nonce, senderUs, echoUs=nowUs}`（nowUs 为浏览器单调钟 `performance.now()×1000`）。
- 自发 1Hz PING 做时钟估计：`RTT = t_recv − t_send`、`offset ≈ echoUs − (t_send+t_recv)/2`；逐帧管线延迟 = `now(browser) − (ptsUs(源端钟) + offset)`。
- 消费源端 ≥1Hz STATS 推送（fps/码率/rtt/丢帧/transport）驱动 HUD。
- 看门狗：自发 PING >6s 无 Pong 即判死连接走重连（对齐 Windows SessionController 看门狗口径）。

### 8.3 断线重连

意外断开 → 指数退避（0.5s 起步、倍增、单次封顶 5s、总窗口 30s）→ HELLO（同 token）→
`START_SESSION{sessionId=N, mode=ATTACH}`（附着自动 IDR，见 §4 勘误；**不发 IDR_REQUEST**）。
超窗或 ATTACH 被显式拒绝 → 停止自动重试，回连接页带原因。

### 8.4 媒体与降级

- 视频：H.264 Annex B（`avc:{format:"annexb"}` + `optimizeForLatency:true` + `prefer-hardware`），`isConfigSupported` 探测不支持 → 含浏览器名的显式错误页（不黑屏等待）。
- 音频：AUDIO_CHUNK 为 **ADTS 帧**（syncword 0xFFF；首帧解析 sampling_frequency_index/channel_configuration 构造 2B AudioSpecificConfig 作 AudioDecoder description，codec `mp4a.40.2`，其后逐帧剥 7/9B ADTS 头）；播放走 AudioWorklet 环形缓冲，起播水位 120ms、不足即 skip 计数、积压 >800ms 丢最旧（对齐 Android AudioPlayer 口径，§3.13）。`window.AudioDecoder`/`AudioWorklet` 缺失 → 显式降级（控件置灰 + 原因提示），视频触控不受影响。
- 剪贴板/文本：CLIPBOARD_TEXT kind=1（auxNotice）→ 控件置灰 + 原因 toast（源端降级如实呈现）。

### 8.5 档位

镜像 client-ux D2 口径：720p/1080p × 30/60 × 4/8/16/20Mbps，浏览器默认 720p60/8M；
客户端侧仅做区间校验（width 320–2560、height 240–2560、fps 15–60、bitrate 1000–20000kbps），/16 对齐交源端。
