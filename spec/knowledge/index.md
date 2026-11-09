# 知识真相源
> 最后更新：2026-11-09 10:37 UTC+8

本项目为安卓→安卓「云端手机」单应用串流系统：源端 App 采集编码 → 网络传输 → 客户端 App 解码渲染，触摸回传闭环，支持断开挂机、重连秒回与单应用/整屏两种观看范式。检索路径：先在本表定位能力域，进域导航页后定位能力页。

## 能力域

| 编号 | 能力域 | 简介 | 能力数量 | 更新时间 |
| --- | --- | --- | --- | --- |
| 1 | [protocol](domains/protocol/_domain.md) | 串流线协议：两端共享的握手、控制消息、帧封装与统计交换契约 | 1 | 2026-11-09 10:37 |
| 2 | [source](domains/source/_domain.md) | 源端：画面采集、H.264 编码、虚拟屏单应用会话与挂机恢复 | 1 | 2026-11-09 10:37 |
| 3 | [client](domains/client/_domain.md) | 客户端：解码渲染、输入回传、连接体验与交互延迟 | 5 | 2026-11-09 10:37 |
| 4 | [transport](domains/transport/_domain.md) | 传输：lan-direct / p2p / frp 三网络路径与直连诊断 | 1 | 2026-11-09 10:37 |
| 5 | [validation](domains/validation/_domain.md) | 验证：负载生成、验收矩阵与端到端延迟测量 | 1 | 2026-11-09 10:37 |

## 架构决策记录

| 编号 | 状态 | 摘要 |
| --- | --- | --- |
| [ADR-001](adr/ADR-001-binary-wire-protocol.md) | accepted | 自研二进制线协议替代 RTSP/WebRTC，控制/视频双通道 + C 兼容布局 |
| [ADR-002](adr/ADR-002-virtual-display-shell-bootstrap.md) | accepted | 单应用模式走虚拟屏 + 特权 shell 助手进程，两级权限产品语义 |
| [ADR-003](adr/ADR-003-transport-abstraction-waterfall.md) | accepted | TransportBackend 统一抽象三后端，p2p 5s 回落 frp 网络瀑布 |
| [ADR-004](adr/ADR-004-session-outlives-connection.md) | accepted | 会话生命周期优先于连接生命周期，断开挂机、token 重连秒回 |
| [ADR-005](adr/ADR-005-latency-data-first-threshold-writeback.md) | accepted | 延迟修复数据先行，验收阈值放宽必须回写 SPEC |
