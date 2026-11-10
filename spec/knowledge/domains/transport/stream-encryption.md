# 串流流加密

<!-- knowledge: capability=stream-encryption rev=1
     sources=spec/changes/p2p-crossnet-traversal -->

视频片与控制通道的片级 AES-128-GCM 加密能力：密钥派生、协商、重放防护与用户开关，覆盖 lan / p2p / frp 全部网络路径。

## 规则

- 加密算法为片级 AES-128-GCM AEAD（javax.crypto，API 26+ 全量覆盖）：每个视频片独立加密+认证（+16B tag），密文总字节数 ≤ 明文 +18B/片（16B tag + 2B 长度扩展容差），实测开销 ≈1.6%，明文负载不填充不膨胀。
- 视频片 AAD 绑定帧头 `[u32 sequence][u8 flags]`（防重排篡改，AAD 不匹配即 GCM 认证拒绝）；控制记录 AAD = `[u64 counter]`。
- 密钥派生：HKDF-SHA256（RFC 5869 extract+expand），ikm=配对 token，salt=双方 32B 握手 nonce 逐字节 XOR，info=方向 → c2s / s2c 每方向独立 128bit 子钥；发送侧按角色取子钥（client 发 c2s 收 s2c，source 相反），两方向密钥隔离。
- nonce 布局 12B = `[u32 方向标识][u64 counter]`（大端；c2s=1、s2c=2）：视频片 counter 由帧头确定性推导 `sequence*65536 + fragmentIndex`（双端无需传输即可重建，同帧多片各占独立 counter，杜绝 GCM nonce 重用）；控制记录 counter 取 2^62 起单调序号（与视频 counter 空间隔离，可靠有序流上按到达顺序重建）。
- 重放防护：接收侧 512 窗口滑动位图——窗口内已见 counter 丢弃、未见标记并放行、窗口外旧 counter 丢弃、新 counter 前移窗口；先 GCM 认证后占窗口（认证失败的损坏片不占窗口）。
- 协商经 Hello/HelloAck 可选尾部字段：cryptoAlgo{0=none, 1=AES128GCM} + encryptOn 意图 + 双方 32B nonce（每次握手重新生成）；字段可选、不 bump PROTOCOL_VERSION，老端忽略未知字段 → 协商结果 none（向后兼容）；双方开关都开且算法都支持 → AES128GCM，任一不满足 → none；协商失败永不阻塞连接建立。
- token 校验最前置：token 不匹配 → HELLO_ACK rejected（REJECT_BAD_TOKEN，"token mismatch"），绝不进入协商/加密；拒绝走显式错误码而非超时或未知错误。
- 明文边界：打洞探针帧 sequence=0 恒明文（打洞期对端尚无密钥），加密路径显式豁免；探针不计统计、不下发解码。
- 加密开关：SourceProfile.encryptOn 默认 true（老档案无此键缺省开，向后兼容）；连接页有加密 Switch 与协商结果显示（「AES-128-GCM / 未加密(对端不支持)」）；encryptOn=false 时线路字节与明文路径逐字节一致（codec disabled 直通分支，无额外字段）。
- 加密覆盖控制与视频双通道，经 TransportBackend.enableCrypto 下发至当前通道，lan / p2p / frp 全后端通用；重连后必须重新握手协商（新 codec 实例，counter 全新）。
- 性能口径：1080p mid 档加密开/关同负载对比（fps / 码率 / RTT 与差值百分比）记录进 verification-report；性能回退超限时复用 encryptOn 关闭片加密，不更换算法。

## 代码锚点

- 主实现：protocol/src/main/kotlin/cloudphone/transport/CryptoCodec.kt（AEAD 加解密、Hkdf 派生、重放窗口）
- 单源符号：protocol/src/main/kotlin/cloudphone/protocol/Messages.kt（协商字段编解码、PROTOCOL_VERSION）
- 消费方：source/app/src/main/kotlin/cloudphone/source/StreamService.kt（协商裁决与 token 前置）、client/app/src/main/kotlin/cloudphone/client/ConnectActivity.kt（开关/nonce/协商结果显示）、client/app/src/main/kotlin/cloudphone/client/SourceProfiles.kt（encryptOn 档案字段）
- 守卫测试：protocol/src/test/kotlin/cloudphone/transport/CryptoCodecTest.kt（往返/AAD 篡改/方向子钥隔离/重放/512 窗口/开销上限/开关关闭字节一致）

## 溯源

- 2026-11-10 p2p-crossnet-traversal（spec/changes/p2p-crossnet-traversal/）— 新建本页：确立片级 AEAD 方案、HKDF 方向子钥、512 重放窗口、可选字段协商、token 前置拒绝与加密开关规则。
