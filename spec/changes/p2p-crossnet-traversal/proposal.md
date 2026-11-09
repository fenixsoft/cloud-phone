# Proposal: p2p-crossnet-traversal

## Why

现有 P2P 实现在「同宿主 UDP 直连」下验证通过，但真实场景是源端在家庭 NAT 后、客户端在另一网络（蜂窝/办公网），跨网打洞成功率从未验证；且现网视频流明文传输（含屏幕内容与输入事件），公网路径存在窃听与篡改风险。用户立项明确两点：跨网真机 P2P 打洞 + 流加密。用户已确认约束：加密纳入本 SPEC 但**必须有开关**——「如果加密导致传输内容变多须有开关选项」（加密若显著增加字节数，用户要能关掉）。经核实：AEAD 片级加密每片仅 +16B tag（≈1.6% 开销），明文字节数不变，但开关仍按用户要求提供。

## What Changes

- 新增 NAT 模拟实验室（natlab）：两台 JVM 协议级对端（复用 protocol 库直跑 transport 栈）置于不同 netns，iptables 构造三档 NAT（EI/EI 默认、symmetric、混合 3×3 组合矩阵），脚本化打洞成功率验证——不依赖真机即可自动验证打洞逻辑
- 打洞增强：IPv6 直连优先 → 多候选并行探测 → 端口预测（symmetric NAT 窗口 ≤64）→ FRP 信令兜底回退 → 会话中端点迁移（网络切换不断流）
- 新增流加密：AES-128-GCM 片级 AEAD（javax.crypto，API 26+），密钥 HKDF-SHA256 从配对 token 派生（双方 nonce XOR 作 salt、方向作 info 派生独立子钥），64bit 计数器 nonce + 512 重放窗口；协商经 Hello/HelloAck 扩展字段（不 bump 协议版本，老端兼容回落 none）
- 客户端连接页新增加密开关（SourceProfile.encryptOn，默认开）与协商结果显示；token 不匹配拒绝连接

## Capabilities

### New Capabilities

- `p2p-nat-traversal`：跨网打洞增强（多候选/端口预测/端点迁移）与 natlab 自动化验证床
- `stream-encryption`：片级 AES-128-GCM 流加密、协商、开关与重放防护

### Modified Capabilities

- `transport-backends`：P2pBackend/LanDirectBackend/FrpBackend 接入加密编解码器与多候选端点
- `stream-protocol-core`：Hello/HelloAck 协商扩展字段、新握手拒绝语义（token 不匹配）

## Impact

- **影响代码面**：protocol（Messages/transport CryptoCodec 新增/TransportBackend 接口扩展）、source（StreamService）、client（SourceProfiles/ConnectActivity）、scripts（natlab_setup.sh/natlab_run.py 新增）、docs（protocol.md/device-notes.md）
- **兼容性**：加密字段可选、老端默认 none 不破坏 v1 协议；探针帧（sequence=0）恒明文；未启用加密路径行为与现状逐字节一致
- **风险**：真机跨网打洞最终判定需真机（Agent 侧 natlab 全自动 + 真机步骤进手册）；symmetric NAT 端口预测有成功率上限（预测失败走 FRP 兜底，不阻塞会话建立）
