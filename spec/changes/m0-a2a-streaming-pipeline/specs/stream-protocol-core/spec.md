## ADDED Requirements

### Requirement: 会话握手与配对认证
客户端连接后 SHALL 发送 HELLO（协议版本、会话 token、观看模式），源端 MUST 在 500ms 内回复 HELLO_ACK（接受并携带会话参数：分辨率/帧率/码率/编码器信息，或拒绝原因）。token 不匹配 MUST 拒绝并关闭连接。

#### Scenario: token 正确被接受
- **WHEN** 客户端以正确 token 发送 HELLO
- **THEN** 源端 500ms 内返回 accepted 的 HELLO_ACK，携带编码会话参数

#### Scenario: token 错误被拒绝
- **WHEN** 客户端以错误 token 发送 HELLO
- **THEN** 源端返回 rejected 的 HELLO_ACK 并关闭连接

### Requirement: 控制通道消息集
协议 MUST 定义并实现消息：HELLO、HELLO_ACK、START_SESSION（整屏或指定包名）、STOP_SESSION、IDR_REQUEST、INPUT_EVENT、STATS、PING/PONG；收到未知类型消息 MUST 忽略且不中断会话。

#### Scenario: 未知消息不影响会话
- **WHEN** 对端发送未定义类型的消息
- **THEN** 接收方忽略该消息，既有会话继续正常收发

### Requirement: 视频帧封装
每个视频帧 MUST 携带 sequence（单调递增）、发送侧时间戳（单调时钟微秒）、关键帧标记、长度前缀；UDP 通道单帧分片 MUST 携带 fragment index/count 以便重组；TCP 通道使用长度前缀流式封装。

#### Scenario: 帧序列号连续性
- **WHEN** 客户端连续接收视频帧
- **THEN** sequence 严格递增，缺号可被统计为丢包

#### Scenario: UDP 分片重组
- **WHEN** 单帧大于 MTU 经 UDP 通道发送
- **THEN** 客户端按 fragment index/count 完整重组或放弃该帧并统计

### Requirement: IDR 请求即时响应
客户端连接建立、重连或发生持续丢包时发送 IDR_REQUEST，源端 MUST 在 200ms 内产生一个 IDR 关键帧。

#### Scenario: 重连秒回画面
- **WHEN** 客户端断开后 10s 内以相同 token 重连
- **THEN** 源端 200ms 内发出 IDR，客户端 1s 内恢复可见画面

### Requirement: 协议文档与常量单一定义
`docs/protocol.md` MUST 完整描述消息类型、字段布局、字节序、握手机制；协议常量与封包逻辑 MUST 且仅 MUST 在 `protocol/` 共享模块单一定义，两端引用同一实现。

#### Scenario: 文档与实现一致
- **WHEN** 按 docs/protocol.md 描述手工构造一条 HELLO 消息
- **THEN** 对端解析结果与 protocol 模块构造的完全一致

### Requirement: 统计消息
双端 MUST 以 ≥1Hz 交换 STATS（fps、码率、RTT、丢包率），驱动客户端统计显示与验证矩阵采集。

#### Scenario: 统计持续更新
- **WHEN** 会话进行 10s
- **THEN** 双端 STATS 至少更新 10 次
