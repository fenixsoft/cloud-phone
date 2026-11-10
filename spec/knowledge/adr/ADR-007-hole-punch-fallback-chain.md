# ADR-007：打洞按序回退增强链，会话绑 sessionId 支持端点迁移

date: 2026-11-10
status: accepted

## Context

原 p2p 打洞只有单级瀑布：SRFLX 交换 + 双端同时探针，5s 超时回落 frp。symmetric NAT（映射端口随目标变化）下该路径必然失败；网络切换（蜂窝 ↔ Wi-Fi）后对端地址失效，会话随连接一起断。

## Decision

打洞执行按序回退增强链：①IPv6 直连优先（同 v6 网可达即免打洞）→ ②多候选并行探测（多 STUN 节点 SRFLX + 本地多网卡候选，含 IPv6）→ ③symmetric 端口预测（对端 SRFLX 端口差分外推，窗口 ≤64，仅对端判定 symmetric 时启用）→ ④全部失败走 FRP 中转兜底（视频切 FRP、标记 degraded）。每级独立开关与独立计时（Diagnostics PunchStages）。端点迁移是链的平行能力：会话绑定 sessionId 而非网络地址，对端地址变化经信令显式 setRemote 更新目标，sequence=0 探针复用于重连，会话不重建、统计不重置。预测失败与打洞超时都不算会话失败——兜底（FRP）是合法终态。

## Consequences

后续任何打洞增强（新候选类型、新预测算法）必须作为链上一级接入，并携带独立开关与计时测量，否则无法回归定位；端口预测命中率受运营商端口随机化限制，degraded 兜底成为常见形态，产品侧必须呈现 degraded 标识；会话绑 sessionId 要求信令通道支持地址更新，sequence=0 探针帧成为重连/迁移的固定协议原语。
