# Tasks: p2p-crossnet-traversal

## Deliverables

```yaml
- path: protocol/src/main/kotlin/cloudphone/transport/CryptoCodec.kt
  kind: new
- path: protocol/src/test/kotlin/cloudphone/transport/CryptoCodecTest.kt
  kind: new
- path: protocol/src/main/kotlin/cloudphone/protocol/Messages.kt
  kind: modify
- path: protocol/src/main/kotlin/cloudphone/transport/TransportBackend.kt
  kind: modify
- path: protocol/src/main/kotlin/cloudphone/transport/P2pBackend.kt
  kind: modify
- path: protocol/src/main/kotlin/cloudphone/transport/StunClient.kt
  kind: modify
- path: protocol/src/main/kotlin/cloudphone/transport/LanDirectBackend.kt
  kind: modify
- path: protocol/src/main/kotlin/cloudphone/transport/FrpBackend.kt
  kind: modify
- path: source/app/src/main/kotlin/cloudphone/source/StreamService.kt
  kind: modify
- path: client/app/src/main/kotlin/cloudphone/client/SourceProfiles.kt
  kind: modify
- path: client/app/src/main/kotlin/cloudphone/client/ConnectActivity.kt
  kind: modify
- path: scripts/natlab_setup.sh
  kind: new
- path: scripts/natlab_run.py
  kind: new
- path: docs/protocol.md
  kind: new
- path: docs/device-notes.md
  kind: modify
```

## 1. 加密核心（protocol）

- [x] 1.1 `protocol/src/main/kotlin/cloudphone/transport/CryptoCodec.kt`：AES-128-GCM 片级 AEAD（javax.crypto），HKDF-SHA256(token, salt=双方 nonce XOR, info=direction) 派生方向子钥，nonce=32bit 方向+64bit counter，AAD=帧头 sequence/flags，+16B tag，API 26+ 纯 JVM 可测
- [x] 1.2 `protocol/src/test/kotlin/cloudphone/transport/CryptoCodecTest.kt`：加解密往返（空片/最大片/counter 边界）、AAD 篡改拒绝、方向子钥隔离（c2s 密钥解不开 s2c）、512 窗口重放位图（重复/窗口外旧 counter 丢弃、正常序列不受影响）、开销断言 ≤明文+18B/片
- [x] 1.3 Messages 协商扩展：Hello/HelloAck 可选字段 cryptoAlgo{0=none,1=AES128GCM}+双方 32B nonce+encryptOn（不 bump PROTOCOL_VERSION，老端忽略回落 none）+token 不匹配拒绝错误码；探针帧 sequence=0 恒明文保障
- [x] 1.4 TransportBackend/P2pBackend/LanDirectBackend/FrpBackend 接入 CryptoCodec（视频片与控制通道按 direction 加密，协商结果 none 时路径逐字节与现状一致）

## 2. 打洞增强（protocol）

- [x] 2.1 多候选探测：StunClient 多节点 SRFLX 汇总 + 本地多网卡候选（含 IPv6），P2pBackend 并行 out-probe，v6 直连优先；每级独立耗时统计进 Diagnostics
- [x] 2.2 symmetric 端口预测：对端 SRFLX 端口差分外推窗口 ≤64 批量探测（仅 symmetric 对端启用）；失败不阻塞（FRP 兜底，视频切中转标记 degraded）
- [x] 2.3 端点迁移：会话绑 sessionId 不绑地址，信令更新对端地址（显式 setRemote），sequence=0 探针重连复用，统计连续不重置

## 3. 应用层接入（source/client）

- [x] 3.1 StreamService：加密协商结果落会话状态，token 校验拒绝路径，加密开关从 StartSession 参数透传
- [x] 3.2 SourceProfiles 增 encryptOn（默认 true）；ConnectActivity 增加密 Switch 与协商结果显示（「AES-128-GCM / 未加密(对端不支持)」）

## 4. natlab 验证床（scripts）

- [x] 4.1 natlab_setup.sh：两 netns+veth+iptables 三档 NAT（默认 EI/AP-RR；symmetric per-destination MASQUERADE --random-ports；混合档），规则可独立启停，setup 幂等（重复执行可复位）
- [x] 4.2 natlab 对端 JVM 进程：protocol 库直跑 transport 栈（StunClient/P2pBackend 真实逻辑），配对脚本拉起两端并注入 token/nonce
- [x] 4.3 natlab_run.py：3×3 NAT 组合矩阵打洞验证，输出成功率/平均耗时表（数据文件落 docs/ 或 scripts/ 供回归对比）；打洞增强逐项开关（预测启用/禁用对比）；端点迁移场景（netns 规则变更模拟切换 IP 后恢复）
- [x] 4.4 无 adb 设备环境全流程自验通过（纯 JVM 对端，不因 adb devices 为空失败）

## 5. 文档与验证

- [x] 5.1 docs/protocol.md：v1 协议字段表 + 协商扩展时序（老端兼容矩阵）+ 加密帧格式（AAD/tag/nonce 布局）+ token 拒绝错误码
- [x] 5.2 docs/device-notes.md 增补：真机跨网打洞步骤（蜂窝/办公网对家庭宽带，成功判定=连接页显示 P2P 直连非 degraded）、加密开关位置说明、natlab 结论「实验室口径」标注
- [x] 5.3 verification-report：加密往返/重放/兼容/开销单测结果、1080p mid 档加密开/关性能对比（fps/码率/RTT 差值）、natlab 3×3 矩阵成功率表、开关关闭线路字节回归对比

## 验证修复（原地打回第 1 轮）

### 上轮实现结论（自动携带，来自上一轮 implementer 收工结论）

恢复 run：收编遗产分支 fenix/p2p-crossnet-traversal-1794197291 全部 16 任务已提交成果至新基线（遗产基于旧基线，整树合并会反向删除 client-ui-redesign/gaming-uplink 文件，故按文件收编：无漂移交付件直接 checkout，四个基线漂移文件 LanDirectBackend/StreamService/ConnectActivity/device-notes 三方合入；device-notes 冲突解法=保留基线 §9+§2.3，本 SPEC 真机手册重编号 §10 并同步修正 §8.x 交叉引用）。交付面：CryptoCodec AES-128-GCM 片级 AEAD+HKDF 方向子钥+512 重放窗口、Hello/HelloAck 可选协商块（不 bump 版本，老端回落 none）、四后端加密接入、多候选探测+symmetric 端口预测≤64+P2P_MIGRATE 端点迁移、StreamService/连接页加密开关与协商显示、natlab 双 netns 三档 NAT 验证床（setup/run/perf 三脚本）、docs/protocol.md+device-notes §10+verification-report、docs/natlab-matrix.json 实测数据。验证：bin/run_full_suite.py --timeout 3600 四阶段全 PASS；protocol 全量单测 70/70（含 cloudphone.transport.* 加密/打洞套件）；natlab 实证随档（主树 .fenix/verify-evidence/p2p-crossnet-traversal/natlab-noadb-run.{json,log}、docs/natlab-m

- [ ] 先复核失败有效性：逐条核对 `spec/changes/p2p-crossnet-traversal/verification-report.md` 中失败场景的期望断言（状态字面量/选择器/前置数据）与产品实际行为是否相符——断言与产品不符属场景缺陷，修场景文件；确属产品行为不符才修产品代码
- [ ] 按复核结论修复：场景缺陷改场景文件，真实产品 Bug 改产品代码
- [ ] 修复后重跑验证确认通过
