# 传输三后端

<!-- knowledge: capability=transport-backends rev=1
     sources=spec/changes/m0-a2a-streaming-pipeline -->

网络路径层：lan-direct / p2p / frp 三种后端统一抽象、配置切换，覆盖同主机环回、UDP 打洞直连与公网隧道兜底，并提供 NAT 诊断与逐秒统计。

## 规则

- 统一抽象：TransportBackend 统一 connect/accept/send/recv/close/stats 语义；三后端（lan-direct / p2p / frp）经配置切换，对采集/编码/解码上层管线透明（切换后端管线代码零改动）。
- lan-direct：同主机拓扑下源端端口经 `adb forward` 暴露到主机环回，一跳连通，实测 RTT ≤5ms。
- p2p：双端经 STUN 获取 SRFLX 地址（内置多节点列表，Cloudflare 优先、可配置；国内 stun.qq.com / stun.miwifi.com:3478 被滤属已知环境事实）；信令（SRFLX 地址+token）复用 frp-TCP 通道交换；双端同时向对端 SRFLX 发 UDP 完成打洞；5s 未成上报失败并自动回落 frp 后端，会话不中断、回落事件留痕。
- frp：经公网 112.74.85.74:22333(TCP) / 22334(UDP) 真实公网回环传输；UDP 路径须支持 60s 连续视频流无断流（受限速时允许码率自适应下调并记录）；UDP 视频通道连续失败自动降级 TCP-over-frp。
- frp-UDP 主机侧补偿：adb 转发仅支持 TCP，主机侧经 scripts/udp_relay.py 将环回 127.0.0.1:22334 的 UDP 中继进源端模拟器。
- 直连诊断：STUN 探测 NAT 类型、SRFLX 地址与端口分配特征，输出结构化 JSON 判定；本机基线为 endpoint-independent（cone，三 STUN 同 socket 同映射）。
- 传输统计：每后端输出 ≥1Hz 的 RTT、丢包率、码率、fps，供客户端 UI 与验证矩阵使用；STATS 标注 transport 类型。
- 已知拓扑局限：同主机双 qemu NAT 间打洞可能假成功，p2p 可用性结论以真机对真机复测为准（人工步骤）。

## 代码锚点

- 主实现：protocol/src/main/kotlin/cloudphone/transport/TransportBackend.kt（统一抽象）、protocol/src/main/kotlin/cloudphone/transport/LanDirectBackend.kt、P2pBackend.kt、FrpBackend.kt（三实现）
- 单源符号：protocol/src/main/kotlin/cloudphone/transport/StunClient.kt（SRFLX 发现）、protocol/src/main/kotlin/cloudphone/transport/Diagnostics.kt（NAT 判定）、protocol/src/main/kotlin/cloudphone/transport/Stats.kt（统计模型）
- 消费方：source 与 client 两端会话建立、scripts/nat_check.py（诊断 CLI）
- 守卫测试：protocol/src/test/kotlin/cloudphone/transport/TransportLoopbackTest.kt、P2pAndStunTest.kt

## 溯源

- 2026-11-08 m0-a2a-streaming-pipeline（spec/changes/m0-a2a-streaming-pipeline/）— 新建本页：确立三后端抽象、p2p 打洞与回落、frp 公网回环、直连诊断与传输统计规则。
