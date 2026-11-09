# Design: p2p-crossnet-traversal

## Context

- 现状（代码核实）：P2pBackend 信令经 frp-TCP 控制通道承载 offer/answer；StunClient 多节点 SRFLX 探测（MAGIC_COOKIE=0x2112A442，XOR_MAPPED_ADDRESS=0x0020）；双端同时 200ms 探针打洞；HELLO.p2pOffer 携带本端 SRFLX 候选；探针帧 sequence=0 保留（接收侧不计统计不下分解码）；控制通道回落期亦承载视频
- 协议现状：PROTOCOL_VERSION=1，MsgType 0x01–0x0B 已分配，0x0C 起可扩展；TransportConfig{role,kind,host,tcpPort,udpPort,useUdpVideo,fragmentBytes}
- 现网明文风险：视频片与输入事件（触摸坐标）公网明文传输——frp 中转与 P2P UDP 路径均未加密
- 用户约束（原话大意）：加密必须有开关，「如果加密导致传输内容变多须有开关选项」→ 加密方案必须控制字节开销且提供显式开关
- 真机暂不可接（用户确认）：Agent 侧用 netns + JVM 协议级对端全自动验证打洞逻辑，真机跨网步骤进 device-notes 手册
- AVD 不能进 netns（emulator slirp 用户态网络不便接 tap）→ natlab 对端用 JVM 进程跑 protocol 库 transport 栈，两进程分置不同 netns， NAT 用 iptables MASQUERADE 构造

## Goals

1. natlab 自动化验证床：3×3 NAT 组合矩阵跑打洞，成功率与耗时数据落盘，打洞逻辑改动可回归
2. 打洞增强：v6 直连优先、多候选并行、symmetric 端口预测、FRP 兜底、端点迁移——逐项可独立开关与测量
3. 流加密：AES-128-GCM 片级 AEAD，协商可选字段（老端兼容），开关默认开，开销 ≈1.6% 明文字节数不变

## Non-Goals

- 不做 TURN 中继服务器（FRP TCP 信令+回落已是兜底，TURN 需公网部署新服务，超出本 SPEC）
- 不做真机自动化打洞测试（真机步骤人工，手册承载）
- 不做端到端音视频双流加密扩展（M3 音频流加密随 M3 SPEC 评估，本 SPEC 只加密视频片与控制通道）
- 不改 STUN 服务器部署（复用现有公共节点探测）

## Decisions

### D1 natlab：JVM 协议级对端 + netns + iptables 三档 NAT
对端是两个 Java 进程，直接依赖 protocol 库跑 transport 栈（StunClient/P2pBackend 逻辑真实验证，而非模拟），各自 netns 内带独立 lo 与出口 veth。NAT 三档（iptables MASQUERADE 构造，两端可独立选档形成 3×3 矩阵）：①默认档=EI mapping + AP-RR filtering（端口保留、地址依赖过滤）；②symmetric 档=per-destination MASQUERADE `--random-ports`（每目标新端口）；③混合档=一端默认一端 symmetric。`scripts/natlab_setup.sh` 建 netns/veth/规则，`scripts/natlab_run.py` 跑矩阵并出成功率/耗时表。模拟器/真机不进 netns（slirp 限制），natlab 是纯 JVM 对端实验床。

### D2 打洞增强链：v6 直连 → 多候选 → 端口预测 → FRP 兜底 → 端点迁移
按序尝试、逐级回退，每级独立计时：①IPv6 直连优先（同 v6 网可达即免打洞）；②多候选并行探测：SRFLX（多 STUN 节点）+ 本地候选（多网卡）同时 out probe；③symmetric NAT 端口预测：对端 SRFLX 端口差分外推窗口 ≤64 端口，批量探测（预测仅对 symmetric 档启用，普通 EI 档跳过省时）；④全部失败→FRP 中转兜底（控制通道已通，视频切 FRP，标记 degraded）；⑤端点迁移：会话绑定 sessionId 不绑地址，网络切换后显式 setRemote 允许更新对端地址，视频流不断（重连探针 sequence=0 复用）。

### D3 加密方案：AES-128-GCM 片级 AEAD，HKDF 派生方向子钥
- 算法：AES-128-GCM（javax.crypto，API 26+ 全量覆盖），片级 AEAD——每个视频片独立加密+认证（+16B tag，1080p 片长 ~1KB 级 → 开销 ≈1.6%），AAD=帧头（sequence/flags 绑定防重排篡改）
- 密钥：HKDF-SHA256(ikm=配对 token, salt=双方 32B 随机 nonce 逐字节 XOR, info=direction("c2s"/"s2c")) → 每方向独立 128bit 子钥；nonce=32bit 方向标识 + 64bit 单调 counter（帧级）
- 重放防护：512 窗口滑动位图，窗口外旧 counter 丢弃
- 明文边界：探针帧 sequence=0 恒明文（打洞期对端尚无密钥）；控制通道与视频通道同密钥不同 direction info
- 协商：Hello/HelloAck 增加可选字段 cryptoAlgo{0=none,1=AES128GCM} + 双方 nonce + encryptOn——字段可选不 bump PROTOCOL_VERSION，老端忽略未知字段→ 协商结果 none（向后兼容）；双方都支持且都开→AES128GCM
- token 校验：token 不同 → 拒绝连接（HELLO_ACK 错误码），防误连与弱身份注入

### D4 加密开关与协商显示（回应用户关切）
SourceProfile 增 `encryptOn`（默认 true）+ 连接页 Switch；协商结果在连接页显示（「加密：AES-128-GCM / 未加密(对端不支持)」）。字节开销事实：明文字节数不变、仅 +16B/片 tag + 握手一次性 ~64B，≠「传输内容变多」量级——开关仍提供，满足用户「若变多须可关」的诉求边界。关闭加密时路径与现状逐字节一致（回归保障）。

### D5 协议与兼容性纪律
新字段全部 optional（proto 风格手动编解码，Framing 记录式扩展）；PROTOCOL_VERSION 保持 1；未知 MsgType/字段容错已有（Reassembler/控制分发），加密协商失败永不阻塞连接建立（最多回落 none）。docs/protocol.md 增补协商时序与字段表。

## Risks

- symmetric NAT 端口预测成功率有限（运营商级 CGNAT 端口随机化）→ 明确兜底 FRP，预测失败不算 FAIL，natlab 数据如实呈现各档成功率
- javax.crypto AES-GCM 在部分低端机并发性能 → 片级独立加密天然并行，测量数据进 verification-report，性能不达标时片加密可关（开关复用）
- netns 实验床与真机真实 NAT 行为有差异（运营商 NAT 更恶劣）→ natlab 结论标注「实验室口径」，真机手册步骤复核
