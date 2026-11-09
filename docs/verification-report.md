# p2p-crossnet-traversal 验证报告（verification-report）

生成时间：2026-11-09（SPEC `spec/changes/p2p-crossnet-traversal/` 收尾验证）。
覆盖任务 5.3 四项：加密单测结果、1080p mid 加密开/关性能对比、natlab 3×3 矩阵、
开关关闭线路字节回归。**本报告全部数据为实验室口径**（docs/device-notes.md §10.3：
同宿主 netns + iptables NAT 模拟，时延不含真实公网 RTT，成功率不可直接外推真实蜂窝/家宽）。

## 1. 加密单测结果（往返/重放/兼容/开销）

`cd protocol && gradle test`：**70 tests / 0 failed**（2026-11-09 实跑，逐类计数取自
`protocol/build/test-results/test/*.xml`）：

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| CryptoCodecTest | 16 | 加解密往返（空片/最大片/counter 边界）、AAD 篡改拒绝、方向子钥隔离（c2s 解不开 s2c）、512 窗口重放位图（重复/窗口外旧 counter 丢弃、正常序列不受影响）、开销断言 ≤明文+18B/片 |
| CryptoTransportTest | 2 | 加密回路往返 + **开关关闭线路字节回归**（§4） |
| MessagesTest | 13 | 含 HELLO/HELLO_ACK 34B 协商块编解码、老端无块兼容、P2P_MIGRATE(0x0C)、token 拒绝错误码 |
| PunchEnhanceTest | 6 | 端口预测双向外推窗口（≤64）、symmetric 判定装配、失败不阻塞（FRP 兜底标记 degraded） |
| P2pAndStunTest | 8 | 多候选 SRFLX 汇总、symmetric 映射判定（2 节点不同端口）、并行 out-probe |
| StunMixTest | 1 | 多 STUN 节点解析（互不吞并） |
| FramingTest | 12 | 流记录/视频分片编解码往返 |
| TransportLoopbackTest | 7 | 三后端传输回路基线（加密前既有语义保持） |
| StatsTest | 5 | STATS 统计语义（迁移场景计数连续不重置） |

## 2. 1080p mid 档加密开/关性能对比（fps/码率/RTT 差值）

测量方式（`scripts/natlab_perf.py`，数据 `docs/natlab-perf.json`、原始日志
`.fenix/verify-evidence/p2p-crossnet-traversal/natlab-perf.log`）：ei×ei **P2P 直连 UDP 路径**
（真实 P2pBackend/StunClient/UdpVideoChannel 栈），源端合成帧泵 1080p mid 档等效载荷
（30fps × 33,333B/帧 ≈ 8,000kbps，每帧 28 个 UDP 分片），client `--perf` 1Hz PING/PONG
测 RTT + 1s 窗口 fps/码率；丢弃前 2 个预热窗口，2 轮 × 开/关各 12s。

| 指标 | 加密开（AES-128-GCM） | 加密关（none） | 差值（开−关） |
|---|---|---|---|
| fps 均值 | 29.7 | 29.8 | **-0.1（-0.3%）** |
| 应用层码率均值 | 7,925 kbps | 7,944 kbps | -18 kbps（-0.2%） |
| 线路码率均值（含 16B/片 tag） | 8,032 kbps | 7,944 kbps | **+88.5 kbps（+1.1%）** |
| RTT 均值 | 0.75 ms | 0.57 ms | +0.18 ms |
| RTT P95 | 1.00 ms | 0.39 ms | +0.61 ms |

结论：同负载下片级 AES-128-GCM 加密 **fps 无可见回退（<0.5%）、RTT 增量亚毫秒级**；
唯一确定性成本是线路字节 +16B/片（28 片/帧下约 +1.1% 线路码率，理论值 28×16×30×8
≈ 107kbps 量级，实测同量级）。片加密并发性好（每片独立 AEAD），无性能达标风险，
无需触发「性能不达标时片加密可独立关闭」预案（encryptOn 开关已可整体关闭，见 §4）。

## 3. natlab 3×3 NAT 矩阵成功率表

`python3 scripts/natlab_run.py --rounds 2`（数据 `docs/natlab-matrix.json`，
2026-11-09 定版轮；打洞成功率 = direct/(direct+fallback)，会话建立率含 FRP 兜底）：

| 组合（client 档 × server 档） | 直连 | 预测 | 兜底 | 失败 | 打洞成功率 | 会话建立率 | 平均打洞耗时 |
|---|---|---|---|---|---|---|---|
| ei × ei | 2 | 0 | 0 | 0 | 100% | 100% | 126 ms |
| ei × ap-rr | 2 | 0 | 0 | 0 | 100% | 100% | 123 ms |
| ei × symmetric | 2 | 0 | 0 | 0 | 100% | 100% | 128 ms |
| ap-rr × ei | 2 | 0 | 0 | 0 | 100% | 100% | 158 ms |
| ap-rr × ap-rr | 0 | 0 | 2 | 0 | 0% | 100% | -（5s 超时后兜底） |
| ap-rr × symmetric | 0 | 0 | 2 | 0 | 0% | 100% | - |
| symmetric × ei | 2 | 0 | 0 | 0 | 100% | 100% | 152 ms |
| symmetric × ap-rr | 0 | 0 | 2 | 0 | 0% | 100% | - |
| symmetric × symmetric | 0 | 0 | 2 | 0 | 0% | 100% | - |

- 语义核对：任一端为 cone 类（ei）→ 直连 100%；双端 strict（ap-rr/symmetric 组合）
  → 直连 0% 但 **FRP 兜底会话建立 100%**（打洞失败不阻塞会话，spec 1.2 强约束）。
- 端点迁移场景：ei×ei 直连后 client 出口 IP 切换（10.200.1.2→10.200.1.3）→
  停滞检测 → 重连 1 次 → 探针重连复用 → 恢复帧 30，统计连续（`migration` 字段）。
- 预测开关对比（`prediction_toggle`）：symmetric 对端下批量探测装配/执行
  （"probing N targets"，N = 候选数 + 64 预测端口）；随机池化 symmetric 下命中率 0
  （窗口与 per-destination 随机分配不相交，如实记录），miss 后 5s 兜底、会话不受影响；
  `--no-prediction` 复跑对照无回归。sym-seq 微池化档（40000-40001 双端口池）用于
  演示 arm/miss 行为——双重随机 NAT 下全栈命中概率 ~1/16 量级，实验轮次内未命中，
  命中路径由 PunchEnhanceTest 单测覆盖（预测端口探针→onPeerReachable→punch_established）。

## 4. 开关关闭线路字节回归对比

三层证据（关闭 encryptOn 时线路与 v1 现状逐字节一致）：

1. **显式回归测试**：`CryptoTransportTest.plaintext path unchanged when crypto never
   enabled`——未 enableCrypto 时 `UdpVideoChannel.crypto == null`、sendVideo 发送路径
   直通（SENT，不抛），即 cipher 缺省时字节路径零改动；既有 `TransportLoopbackTest`
   7 例全绿（加密前基线语义保持）。
2. **协商回退路径**：MessagesTest 老端无协商块兼容用例 + 协商 none 时
   TransportBackend 不挂 codec（`negotiatedCryptoAlgo = NONE` → 调用方透传原字节），
   控制记录/视频片均无新增头字段（tag/ciphertext 仅在 codec 挂载后存在）。
3. **线上旁证**：natlab 矩阵/性能实验中加密关轮次的 wire 码率与 app 码率逐字节相等
   （7944 = 7944，无 tag 开销），加密开轮次差值恰为 +16B/片（§2 表）。

## 5. 遗留与边界（如实记录）

- 性能数据为 JVM 同宿主口径：真实低端机 javax.crypto 并发开销、真实 RTT（蜂窝 30–80ms）
  下的绝对值会不同，但片级加密的开销结构（+16B/片 + 每片一次 AEAD）与开关语义一致。
- 端口预测在「顺序分配型 symmetric NAT」下的命中率实验受限于 iptables 无法构造
  全局顺序分配（内核 SNAT 为 per-tuple 哈希/随机），以随机池化档如实呈现 miss→兜底路径。
