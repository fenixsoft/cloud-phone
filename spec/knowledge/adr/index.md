# ADR 索引

| 编号 | 状态 | 摘要 |
| --- | --- | --- |
| [ADR-001](ADR-001-binary-wire-protocol.md) | accepted | 自研二进制线协议替代 RTSP/WebRTC：控制/视频双通道、C 兼容布局、协议单一实现 |
| [ADR-002](ADR-002-virtual-display-shell-bootstrap.md) | accepted | 单应用模式走虚拟屏 + 特权 shell 助手进程，两级权限产品语义不变 |
| [ADR-003](ADR-003-transport-abstraction-waterfall.md) | accepted | TransportBackend 统一抽象三后端，p2p 5s 回落 frp 的网络瀑布 |
| [ADR-004](ADR-004-session-outlives-connection.md) | accepted | 会话生命周期优先于连接生命周期：断开挂机、同 token 重连秒回 |
| [ADR-005](ADR-005-latency-data-first-threshold-writeback.md) | accepted | 延迟/性能修复必须先插桩测量，验收阈值放宽必须回写 SPEC |
| [ADR-006](ADR-006-natlab-netns-regression-bed.md) | accepted | 打洞逻辑以 natlab（netns + iptables 三档 NAT + JVM 协议级对端）矩阵数据回归，模拟器/真机不进 netns |
| [ADR-007](ADR-007-hole-punch-fallback-chain.md) | accepted | 打洞按 v6 直连→多候选→端口预测→FRP 兜底逐级回退，会话绑 sessionId 支持端点迁移 |
| [ADR-008](ADR-008-fragment-aead-stream-encryption.md) | accepted | 视频片级 AES-128-GCM AEAD + HKDF 方向子钥，协商走可选字段不 bump 协议版本 |
