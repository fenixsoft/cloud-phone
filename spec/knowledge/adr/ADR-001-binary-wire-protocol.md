# ADR-001：自研二进制线协议替代 RTSP/WebRTC

date: 2026-11-08
status: accepted

## Context

安卓→安卓单应用串流需要两端（未来含鸿蒙端）复刻同一协议栈。现成方案 RTSP/WebRTC 依赖重、libwebrtc 体积与复杂度失控，且整屏/虚拟屏采集定制困难；协议文档独立成篇也是本项目开源卖点。

## Decision

自研轻量二进制线协议：控制通道走可靠流（TCP/frp-TCP）逐记录封装；视频通道 UDP + sequence + NACK 重传，frp/TCP 模式降级为 TCP 长度前缀承载。字段布局 C 兼容（大端、紧凑排列无填充、含协议版本字段），协议常量与封包逻辑仅在 protocol/ 共享模块单一定义；docs/protocol.md 为协议文档真相，且与实现保持一致。

## Consequences

C++ 协议核心库可平移实现而不破坏线上格式；鸿蒙端按同一契约独立实现。代价：放弃 WebRTC 自带的拥塞控制/FEC 生态，弱网对抗需自建。
