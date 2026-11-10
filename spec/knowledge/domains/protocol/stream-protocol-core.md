# 串流线协议核心

<!-- knowledge: capability=stream-protocol-core rev=2
     sources=spec/changes/m0-a2a-streaming-pipeline,spec/changes/client-ux-and-input-latency,spec/changes/p2p-crossnet-traversal -->

源端 App 与客户端 App 之间唯一的通信契约：定义握手配对、加密协商、控制消息集、视频帧封装与统计交换，两端引用同一共享实现，后续 C++ 化与鸿蒙端按同一线上格式复刻。

## 规则

- 通道划分：控制面走可靠流（TCP / frp-TCP），逐记录封装 `[u8 recordType][u32 payloadLen][u8 msgType][字段...]`；视频面默认 UDP + sequence + NACK 重传，TCP 承载时降级为长度前缀流式封装。
- 字节序为大端；字段布局 C 兼容（紧凑排列无填充、含协议版本字段），保证协议 C++ 化不破坏线上格式。
- 握手与配对认证：客户端连接后发送 HELLO（协议版本、会话 token、观看模式，及可选加密协商尾部字段），源端 500ms 内回复 HELLO_ACK——accepted 携带会话参数（分辨率/帧率/码率/编码器信息）与协商结果，rejected 携带拒绝原因；token 不匹配必须以显式错误码拒绝（REJECT_BAD_TOKEN）并关闭连接，不走超时或未知错误路径。
- 加密协商扩展：HELLO/HELLO_ACK 尾部可选字段 cryptoAlgo{0=none, 1=AES128GCM}、encryptOn 意图、双方 32B nonce；扩展不 bump PROTOCOL_VERSION（保持 1），无加密字段的老字节流解码回落 none 缺省值；加密协商失败不阻塞连接建立（详见 stream-encryption 能力页）。
- 控制消息集共 11 类：HELLO(0x01)、HELLO_ACK(0x02)、START_SESSION(0x03)、STOP_SESSION(0x04)、IDR_REQUEST(0x05)、INPUT_EVENT(0x06)、STATS(0x07)、PING(0x08)、PONG(0x09)、APP_LIST_REQUEST(0x0A)、APP_LIST(0x0B)；收到未知类型消息忽略且不中断会话。
- START_SESSION 携带 width/height/fps/bitrateKbps 四元组，观看模式为整屏或指定包名的单应用。
- 视频帧封装：每帧携带 sequence（单调递增）、发送侧单调时钟微秒时间戳、关键帧标记、长度前缀；UDP 单帧分片携带 fragment index/count 供重组，重组失败放弃该帧并计入丢包统计。
- IDR 即时响应：连接建立、重连或持续丢包触发 IDR_REQUEST，源端 200ms 内产生关键帧；断开 30s 内同 token 重连 1s 内恢复可见画面。
- 统计交换：双端以 ≥1Hz 交换 STATS（fps、码率、RTT、丢包率），驱动客户端统计显示与验证矩阵采集。
- APP_LIST 为请求-应答制可选能力：AppListRequest（空载荷）→ AppListMessage（AppEntry{packageName,label} 列表）；源端不主动推送，老客户端不发该消息不受影响，无 BREAKING。
- 协议常量与封包逻辑仅在 `protocol/` 共享模块单一定义，两端引用同一实现；`docs/protocol.md` 完整描述消息类型、字段布局、字节序与握手机制，且与实现一致（按文档手工构造的消息可被对端解析）。

## 代码锚点

- 主实现：protocol/src/main/kotlin/cloudphone/protocol/Messages.kt（消息常量、编解码、PROTOCOL_VERSION）
- 单源符号：protocol/src/main/kotlin/cloudphone/protocol/Framing.kt（逐记录封包/解包）
- 消费方：source/app/src/main/kotlin/cloudphone/source/StreamService.kt（源端会话处理）、client/app/src/main/kotlin/cloudphone/client/DecodeRenderActivity.kt（客户端控制/帧收发）
- 守卫测试：protocol/src/test/kotlin/cloudphone/protocol/MessagesTest.kt、FramingTest.kt

## 溯源

- 2026-11-08 m0-a2a-streaming-pipeline（spec/changes/m0-a2a-streaming-pipeline/）— 新建本页：确立通道划分、控制消息集（初版 9 类）、视频帧封装、IDR 即时响应、STATS 交换与协议单一实现规则。
- 2026-11-09 client-ux-and-input-latency（spec/changes/client-ux-and-input-latency/）— 增补 APP_LIST_REQUEST/APP_LIST 两条消息与请求-应答兼容规则。
- 2026-11-10 p2p-crossnet-traversal（spec/changes/p2p-crossnet-traversal/）— 增补 Hello/HelloAck 加密协商可选字段与不 bump 版本兼容规则，token 拒绝语义锚定显式错误码。
