# ADR-008：视频片级 AES-128-GCM 流加密与可选字段协商纪律

date: 2026-11-10
status: accepted

## Context

视频流（含屏幕内容）与输入事件（触摸坐标）在 frp 中转与 P2P UDP 路径上明文传输，公网路径可被窃听与篡改。协议为自研二进制 v1，新旧两端并存，不能为加密破坏兼容；用户明确约束：加密若显著增加传输字节数，必须提供开关。

## Decision

流加密采用片级 AES-128-GCM AEAD（javax.crypto，API 26+ 全量覆盖）：每个视频片独立加密+认证（+16B tag，开销 ≈1.6%，明文负载不膨胀），AAD 绑定帧头 sequence/flags 防重排篡改。密钥经 HKDF-SHA256 从配对 token 派生（salt=双方握手 nonce 逐字节 XOR，info=方向 → c2s/s2c 每方向独立子钥），nonce=32bit 方向标识 + 64bit counter（视频 counter 由帧头确定性推导，控制 counter 独立空间）；512 窗口滑动位图防重放；打洞探针帧 sequence=0 恒明文（打洞期对端尚无密钥）。协商走 Hello/HelloAck 可选尾部字段，不 bump PROTOCOL_VERSION，老端忽略未知字段回落 none，协商失败永不阻塞连接建立；token 校验前置，不匹配以显式错误码拒绝。用户开关 encryptOn 默认开，关闭时线路逐字节回到明文现状。

## Consequences

后续任何流或通道扩展（如音频双流加密）都必须适配该 AEAD 方案的 AAD 绑定、方向子钥与 counter 空间分配，不能另起加密机制；「新字段全部 optional、不 bump 版本、未知字段容错」自此成为协议演进的默认扩展路径；低端机 AES-GCM 并发性能依赖片级独立性兜底，性能不达标以关闭开关降级，而非更换算法。
