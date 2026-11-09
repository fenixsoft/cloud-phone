# ADR-003：TransportBackend 统一抽象与 p2p→frp 网络瀑布

date: 2026-11-08
status: accepted

## Context

源端与客户端可能处于同主机、可打洞 NAT 或仅公网隧道可达三种网络环境；本机家庭 NAT 为 endpoint-independent（cone）可打洞，但对端可能是对称型，必须能优雅降级。

## Decision

lan-direct / p2p / frp 三后端统一实现 TransportBackend 抽象（connect/accept/send/recv/close/stats），经配置切换、上层管线零感知；p2p（STUN 地址发现 + frp-TCP 信令 + UDP 双向打洞）5s 未成自动回落 frp；UDP 视频通道连续失败降级 TCP-over-frp。

## Consequences

新网络路径以新增后端形式接入，采集/编码/解码管线代码零改动；同主机双 qemu NAT 间打洞可能假成功，p2p 可用性结论须真机对真机复测并在报告标注拓扑局限。
