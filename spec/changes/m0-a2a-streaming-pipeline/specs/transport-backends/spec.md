## ADDED Requirements

### Requirement: 传输后端统一抽象
`TransportBackend` SHALL 统一抽象 connect/accept/send/recv/close/stats；三后端（lan-direct / p2p / frp）MUST 可经配置切换且对上层管线透明。

#### Scenario: 切换后端不改管线
- **WHEN** 配置文件将后端从 frp 切为 lan-direct 并重启会话
- **THEN** 采集/编码/解码管线代码零改动即可工作

### Requirement: lan-direct 后端
同主机拓扑下，lan-direct MUST 经主机环回一跳连通源端与客户端，连通 RTT MUST ≤5ms。

#### Scenario: 环回直连
- **WHEN** 双端选择 lan-direct 并连接
- **THEN** PING/PONG 实测 RTT ≤5ms 且视频流建立

### Requirement: p2p 后端（STUN+信令+UDP 打洞）
p2p 后端 MUST 经 STUN 获取双端 SRFLX 地址，信令（offer/answer：SRFLX 地址+token）经 frp-TCP 通道交换，双端同时向对端 SRFLX 发 UDP 完成打洞；5s 未成 MUST 上报失败并自动回落 frp 后端。

#### Scenario: 打洞成功走直连
- **WHEN** 双端 NAT 允许打洞
- **THEN** 5s 内建立 UDP 直连，STATS 标注 transport=p2p

#### Scenario: 打洞失败回落
- **WHEN** 5s 内未建立直连
- **THEN** 自动回落 frp 后端并记录回落事件，会话不中断

### Requirement: frp 后端（真实公网回环）
frp 后端 MUST 经公网 112.74.85.74:22333(TCP)/22334(UDP) 完成端到端传输；UDP 路径 MUST 支持 60s 连续视频流无断流（受限速时允许码率自适应下调并记录）。

#### Scenario: 公网 TCP 路径
- **WHEN** 双端选择 frp-TCP 并连接
- **THEN** 端到端会话建立且 60s 内无断流

#### Scenario: 公网 UDP 路径
- **WHEN** 双端选择 frp-UDP 传输视频
- **THEN** 60s 连续视频流完成，丢包率与码率写入报告

### Requirement: 直连诊断
MUST 提供 NAT/直连诊断能力（STUN 探测 NAT 类型、SRFLX 地址、端口分配特征）并输出结构化判定；本机已知基线为 endpoint-independent（cone）。

#### Scenario: 诊断输出 NAT 判定
- **WHEN** 运行诊断
- **THEN** 输出 NAT 类型判定与 SRFLX 地址，判定与已验证基线一致（cone）

### Requirement: 传输统计
每后端 MUST 输出 ≥1Hz 的 RTT、丢包率、码率、fps 统计供 UI 与验证矩阵使用。

#### Scenario: 各后端统计可用
- **WHEN** 任一后端会话进行中
- **THEN** STATS 每秒更新且数值随网络状况变化
