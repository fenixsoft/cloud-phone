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
| `0x07` | STATS | 双向 | ≥1Hz 统计 |
| `0x08` | PING | C → S | RTT/时钟偏移探测 |
| `0x09` | PONG | S → C | PING 应答 |

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
| 10 | p2pOffer | str | 客户端 SRFLX `"ip:port"`（p2p 打洞；空串 = 不打洞） |

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
| 10 | p2pAnswer | str | 源端 SRFLX `"ip:port"`（空串 = 不打洞） |
| 11 | hungCount | u16 | 挂机会话数 N |
| 12 | N × 挂机项 | — | `{i32 sessionId, u8 mode, str packageName, u16 width, u16 height}` |

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

## 4. 握手与会话时序（文字版）

**首次连接（新会话）**

```
client                          source
  │  TCP/frp-TCP connect           │
  │── HELLO{version,token,mode,   │
  │      packageName,w,h,fps,     │
  │      bitrate,p2pOffer} ──────►│  校验 token（≤500ms）
  │◄── HELLO_ACK{accepted,        │  （token 不符 → rejected + 关闭连接）
  │      sessionId,w,h,fps,       │
  │      bitrate,encoder,         │
  │      displayId,p2pAnswer,     │
  │      hungSessions[]} ────────│
  │  （视频通道按后端建立：UDP 直连/公网 UDP/TCP 复用）
  │◄══ 视频帧（首帧为 IDR 关键帧）══│
  │── PING / ◄── PONG（≥1Hz RTT） │
  │◄── STATS ──/── STATS（≥1Hz）  │
  │── INPUT_EVENT（触摸/back）───►│
```

**附着挂机会话（重连 ≤30s，同 token）**

```
client                          source
  │── HELLO{mode=ATTACH,          │
  │      sessionId=N, token} ───►│  会话 N 仍存活（虚拟屏/App 未销毁）
  │◄── HELLO_ACK{accepted,        │
  │      sessionId=N, ...} ──────│
  │── IDR_REQUEST ──────────────►│  ≤200ms 产生 IDR
  │◄══ 关键帧 + 后续视频（≤1s 出画）══│
```

**会话结束（STOP_SESSION）**：源端销毁该会话虚拟屏与其上的 App 进程；仅断开连接（不 STOP）则进入挂机，会话保留在 HELLO_ACK.hungSessions 列表中。

## 5. 错误码（HELLO_ACK.status）

| 值 | 名称 | 含义 |
|---|---|---|
| 0 | ACCEPTED | 接受，携带会话参数 |
| 1 | REJECT_BAD_TOKEN | token 不匹配，拒绝并关闭连接 |
| 2 | REJECT_BUSY | 源端会话资源满（M0 ≥2 并存，超出即拒） |
| 3 | REJECT_VERSION | 协议版本不支持 |
| 4 | REJECT_UNSUPPORTED_MODE | 观看模式不支持（如无 shell 权限时的单应用模式降级拒绝） |

## 6. M1 C++ 化兼容性约定

1. **定宽原语**：所有字段使用定宽类型（u8/u16/u32/i32/i64/f32），M1 直接映射 `uint8_t/uint16_t/uint32_t/int32_t/int64_t/float`。
2. **大端 + 紧凑排列**：无 struct 对齐填充；C 侧 MUST 逐字段大端读写（或 `#pragma pack(1)` 后自行字节序转换），不得直接 memcpy 本机序结构体。
3. **字符串**：一律 `u16 长度前缀 + UTF-8`，无终结符；C 侧按长度边界切分。
4. **msgType/recordType/枚举值冻结**：本文所有常量数值为线上格式承诺，M1 不得改动数值含义；新增消息只能追加新 msgType，旧端按"未知消息忽略"兼容。
5. **版本协商**：HELLO.version 单调递增；源端支持版本集合包含客户端版本 → 继续，否则回 REJECT_VERSION。M0 唯一版本 = 1。语义扩展（新增字段）通过追加新消息类型实现，M0 不做消息内版本分叉。
6. **32 位帧序号**：sequence 线上宽度 u32，C 侧以 `uint32_t` 承载（回绕按无符号比较）。
