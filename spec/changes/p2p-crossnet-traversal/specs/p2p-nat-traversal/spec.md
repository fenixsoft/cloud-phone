# p2p-nat-traversal — 跨网打洞增强与 natlab 自动化验证床

## ADDED Requirements

### Requirement: natlab NAT 模拟验证床
`scripts/natlab_setup.sh` SHALL 用 netns+veth+iptables 建两对端网络（三档 NAT：默认 EI/AP-RR、symmetric per-destination 随机端口、混合），`scripts/natlab_run.py` SHALL 在两 JVM 协议级对端（protocol 库直跑 transport 栈）间执行 3×3 NAT 组合矩阵打洞验证，输出各组合成功率与耗时表。

#### Scenario: EI 对 EI 打洞成功
- **WHEN** 两对端均为默认档 NAT（EI mapping + AP-RR filtering）时执行 natlab_run.py
- **THEN** P2P 直连建立成功（未经 FRP 中转），耗时 ≤5s 记入结果表

#### Scenario: symmetric 组合矩阵完整
- **WHEN** 执行完整 3×3 矩阵
- **THEN** 每组合有明确结论（直连成功/端口预测成功/FRP 兜底/失败），成功率与平均耗时落盘为可回归对比的数据文件

### Requirement: 打洞增强链
P2pBackend SHALL 实现按序回退的打洞链：IPv6 直连优先 → 多候选并行探测（多 STUN 节点 SRFLX + 本地候选）→ symmetric 端口预测（窗口 ≤64，仅 symmetric 对端启用）→ FRP 中转兜底（视频切 FRP 标记 degraded）；每级 SHALL 有独立耗时统计。

#### Scenario: 端口预测生效
- **WHEN** 对端为 symmetric NAT（natlab symmetric 档）
- **THEN** 启用 ≤64 端口窗口批量探测并给出预测成功率数据；预测失败时不阻塞会话建立（走 FRP 兜底）

#### Scenario: FRP 兜底不断会话
- **WHEN** 打洞各级均失败
- **THEN** 会话仍建立（视频经 FRP 中转），连接标记 degraded，用户可见标识

### Requirement: 端点迁移
会话 SHALL 绑定 sessionId 而非网络地址；对端地址变化（网络切换）时经信令更新（显式 setRemote），视频流 SHALL 不中断（sequence=0 探针重连复用）。

#### Scenario: 网络切换恢复
- **WHEN** natlab 对端切换出口 IP（netns 规则变更模拟）后重新探针
- **THEN** 会话不重建，视频流恢复且统计连续（无重新握手记录）

### Requirement: natlab 不依赖真机
natlab 全流程（setup + run）SHALL 可在无 adb 设备环境执行（纯 JVM 对端），真机跨网验证步骤单独写入 docs/device-notes.md 手册。

#### Scenario: 无设备可跑
- **WHEN** `adb devices` 为空时执行 natlab 全流程
- **THEN** 矩阵正常执行并产出结果表（不因无设备而失败）

# stream-encryption — 片级 AES-128-GCM 流加密、协商、开关与重放防护

## ADDED Requirements

### Requirement: 片级 AEAD 加密
视频片 SHALL 经 AES-128-GCM 加密（javax.crypto，API 26+）：每片独立加密+认证（+16B tag），AAD 绑定帧头（sequence/flags），密钥 HKDF-SHA256 从配对 token 派生（salt=双方 nonce XOR，info=方向 → 每方向独立子钥），nonce=32bit 方向 + 64bit 单调 counter。

#### Scenario: 加密往返
- **WHEN** CryptoCodec 单元测试对任意字节序列加解密往返（含空片、最大片、边界 counter）
- **THEN** 明文逐字节还原，AAD 不匹配时解密失败（GCM 认证拒绝）

#### Scenario: 开销上限
- **WHEN** 1080p 典型片长分布下测量加密开销
- **THEN** 密文总字节数 ≤ 明文 +18B/片（16B tag + 2B 长度扩展容差），报告记录实测百分比（预期 ≈1.6%）

### Requirement: 重放防护
接收侧 SHALL 维护 512 窗口滑动位图：窗口内已见 counter 丢弃、窗口内未见标记并放行、窗口外旧 counter 丢弃、新 counter 前移窗口。

#### Scenario: 重放片丢弃
- **WHEN** 注入重复 counter 与窗口外旧 counter 加密片
- **THEN** 两者均被丢弃且不进入解码，正常序列不受影响

### Requirement: 协商与兼容
Hello/HelloAck SHALL 增加可选协商字段 cryptoAlgo{0=none,1=AES128GCM}、双方 32B nonce、encryptOn 意图；字段 SHALL 不 bump PROTOCOL_VERSION（老端忽略未知字段回落 none）；token 不匹配 SHALL 拒绝连接（明确错误码）；探针帧 sequence=0 SHALL 恒明文。

#### Scenario: 老端兼容
- **WHEN** 新端（加密开）连老端（无协商字段）
- **THEN** 协商结果 none，连接成功，行为与现状一致（回归验证）

#### Scenario: token 不匹配
- **WHEN** 客户端 token 与源端不一致
- **THEN** 连接被拒绝且错误信息明确（非超时或未知错误）

### Requirement: 加密开关
SourceProfile SHALL 增 encryptOn（默认 true）；连接页 SHALL 有加密 Switch 与协商结果显示（「AES-128-GCM / 未加密(对端不支持)」）；关闭加密时线路 SHALL 与现状逐字节一致。

#### Scenario: 开关关闭回归
- **WHEN** encryptOn=false 时抓包对比既有测试基线
- **THEN** 线路字节与加密功能合入前一致（无额外字段）

### Requirement: 加密性能测量
SHALL 输出加密开启前后同负载（1080p mid 档）吞吐与延迟对比数据进 verification-report，性能回退 SHALL 量化呈现（片加密可独立关闭的开关复用 encryptOn）。

#### Scenario: 性能对比数据
- **WHEN** 执行 1080p mid 档同负载加密开/关对比
- **THEN** 报告含两组 fps/码率/RTT 数据与差值百分比
