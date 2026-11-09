# 验证报告：p2p-crossnet-traversal

> ⚠️ 本报告由框架于 2026-11-09T13:04:39Z 自动合成（裁决 Agent 未写报告，已由框架合成），内容为结构化验证产物（report.json / .fenix-run-result.json）的忠实渲染。
> 报告时效标记（判定本报告是否属上一轮）：合成源轮次指纹：report.json@2026-11-09T13:02:40.064731+00:00+run-result@mtime1794229470491736696

## 结论：❌ 未通过（Bug Found）

**状态：bug-found — 24/25 场景通过，1 失败，0 错误（1 个真实产品 Bug）**

25 场景：24 pass / 1 fail / 0 warning。裁决：product-bug 1（crypto-deliverables-exist：natlab_run.py 可执行位缺失，minor，免复现确定性缺陷）；test-defect 0、flaky 0、env 0。其余 24 场景（含上轮修复的 encrypt-off/ei-ei/no-adb 场景）全部通过。修复建议：chmod +x scripts/natlab_run.py 并以 100755 模式入库。

| 指标 | 数量 |
| --- | --- |
| 通过 | 24 |
| 失败 | 1 |
| 错误 | 0 |
| 产品 Bug | 1 |
| 本链增量 Token（输入） | 0.99M |
| 本链增量 Token（输出） | 0.14M |


## 真实产品 Bug

### BUG-01（severity=minor）· exit_code

- 关联场景：crypto-deliverables-exist
- 描述：交付物 scripts/natlab_run.py 缺可执行位（实测 -rw-r--r--，git 模式 100644），tasks.md Deliverables 要求 natlab_setup.sh/natlab_run.py 均带可执行位（natlab_setup.sh 已为 755/100755）
- 根因：实现交付时未对 scripts/natlab_run.py 执行 chmod +x，交付物核查脚本 [ -x ] 检查失败使整步 exit 1
- 证据（场景 crypto-deliverables-exist 结构化证据，引用）：
  - failed_assertions: [{"type": "exit_code", "expected": 0, "actual": 1}, {"type": "output_match", "mode": "contains", "expected": "DELIVERABLES_OK", "actual": "MISSING exec bit: scripts/natlab_run.py"}]
  - screenshots: []
  - first_failed_step: 步骤1: 交付物核查脚本打印 MISSING exec bit: scripts/natlab_run.py 后 exit 1（文件本体存在，仅可执行位缺失）


## 场景结果

| 场景 | 描述 | 状态 | 失败断言 | 分类 |
| --- | --- | --- | --- | --- |
| crypto-deliverables-exist | 本 SPEC 全部交付物齐备且带 SPEC 标记：protocol 新增 CryptoCodec.kt/CryptoCodecTest.kt，scripts 新增 natlab_setup.sh/natlab_run.py（可执行位），docs/protocol.md 存在且含加密章节；修改件 P2pBackend/LanDirectBackend/FrpBackend 引用 CryptoCodec、SourceProfiles 含 encryptOn、Messages.kt 含协商字段；任一缺失即非零退出并打印 MISSING 路径（出处：tasks.md Deliverables YAML；proposal Impact 影响代码面） | failed（已修正） | 2/3 | product-bug |
| crypto-codec-tests-green | CryptoCodec 单元测试全绿：protocol 模块 ./gradlew test --tests "*CryptoCodecTest*" 覆盖 AES-128-GCM 片级加解密往返（空片/最大片/counter 边界）、AAD 篡改拒绝、HKDF 方向子钥派生，BUILD SUCCESSFUL 且产出测试结果 XML（出处：tasks 1.1/1.2；specs/stream-encryption「片级 AEAD 加密」加密往返 Scenario；CryptoCodecTest.kt）。环境依赖（Gradle 发行版与依赖经镜像下载，首次构建较久）；有 fallback: 镜像 distributionUrl 已配置（protocol/gradle/wrapper/gradle-wrapper.properties:3） | pass | 0/3 | - |
| probe-frame-plaintext-invariant | 探针帧 sequence=0 恒明文不变量：PUNCH_PROBE_SEQUENCE 常量为 0（P2pBackend.kt:98-100 打洞探针发送现状锚点）且加密路径显式豁免（CryptoCodec/P2pBackend 按 sequence==0 或 PUNCH_PROBE 跳过加解密），打洞期对端尚无密钥仍可互通（出处：specs/stream-encryption「协商与兼容」探针帧 sequence=0 SHALL 恒明文；design D3 明文边界） | pass | 0/2 | - |
| multi-candidate-probe-surface | 多候选并行探测落地：StunClient 从单结果 discoverSrflx（StunClient.kt:129 现状锚点）扩展为多节点 SRFLX 汇总 + 本地多网卡候选（含 IPv6），P2pBackend 并行 out-probe 且 IPv6 直连优先，每级独立耗时统计进 Diagnostics（结构化字段可核）（出处：tasks 2.1；specs/p2p-nat-traversal「打洞增强链」Requirement；design D2 ①②） | pass | 0/2 | - |
| symmetric-port-prediction-bounded | symmetric 端口预测有界不阻塞：对端 SRFLX 端口差分外推窗口 ≤64（常量可核），仅对 symmetric 对端启用（EI 档跳过省时），预测失败不阻塞会话建立——走 FRP 兜底并标记 degraded（用户可见标识）；源码含 prediction/预测、窗口 64、symmetric 门控与 degraded 标记锚点（出处：tasks 2.2；specs/p2p-nat-traversal「打洞增强链」端口预测生效/FRP 兜底不断会话 Scenario；design D2 ③/D Risks） | pass | 0/3 | - |
| natlab-setup-script-surface | natlab_setup.sh 静态健康与结构完备：bash -n 语法零错；含两 netns 创建、veth 对、iptables MASQUERADE 三档 NAT 构造（symmetric 档 per-destination --random-ports 字面锚点），规则可独立启停（分档开关/子命令），幂等复位路径（重复执行先清后建）；--help 或 usage 可自描述（出处：tasks 4.1；specs/p2p-nat-traversal「natlab NAT 模拟验证床」Requirement；design D1 三档 NAT 定义） | pass | 0/2 | - |
| natlab-setup-real-run-idempotent | natlab_setup.sh 实跑幂等：首次 setup 建成 natlab netns（ip netns list 可见），重复执行复位成功（exit 0 幂等），清理路径移除 netns 不留残留。环境依赖（需 root 且容器具备 netns/veth/iptables 能力，规划期本机实测 ip netns add 可用）；无 fallback（权限缺失属环境阻塞，按环境问题升级处置）（出处：tasks 4.1 setup 幂等；design D1） | pass | 0/3 | - |
| natlab-run-cli-surface | natlab_run.py CLI 自描述与异常路径：--help 以 exit 0 输出用法并暴露 3×3 矩阵、预测启用/禁用开关、端点迁移场景入口与结果落盘参数；未定义参数以非零退出码拒绝并输出 usage/错误（argparse 惯例），不得静默忽略未知参数继续跑矩阵（出处：tasks 4.3；specs/p2p-nat-traversal 全部 Scenario 的脚本化要求；先例 run_matrix.py CLI 纪律 scripts/run_matrix.py:233） | pass | 0/6 | - |
| streamservice-crypto-session-state | 源端会话接入加密协商：StreamService 将协商结果（AES128GCM/none）落会话状态，加密开关从 StartSession 参数透传，token 校验拒绝路径保持（StreamService.kt:254 REJECT_BAD_TOKEN 现状锚点）；源码含 crypto/协商/encryptOn/REJECT_BAD_TOKEN 锚点（出处：tasks 3.1；specs/stream-encryption「协商与兼容」「加密开关」Requirement；knowledge client 域/协议域消费方锚点） | pass | 0/2 | - |
| client-encrypt-switch-negotiation-display | 客户端加密开关与协商结果显示：SourceProfile 增 encryptOn 且默认 true（字面缺省值可核），ConnectActivity 增加密 Switch（开关控件与状态读写）与协商结果显示文案——「AES-128-GCM」与「未加密(对端不支持)」两态锚点均在（出处：tasks 3.2；specs/stream-encryption「加密开关」Requirement；design D4；knowledge client 域 source-profiles 档案模型扩展点） | pass | 0/3 | - |
| protocol-doc-crypto-negotiation-format | docs/protocol.md 协商扩展与加密帧格式文档化：v1 字段表保持（PROTOCOL_VERSION 锚点）、协商时序与老端兼容矩阵（cryptoAlgo/encryptOn/nonce 可选字段）、加密帧格式（AAD=帧头 sequence/flags、16B tag、nonce=32bit 方向+64bit counter 布局、HKDF 派生）、token 拒绝错误码、探针帧 sequence=0 恒明文边界（出处：tasks 5.1；design D3/D5；specs/stream-encryption「协商与兼容」Requirement；knowledge 协议域 stream-protocol-core「协议文档与常量单一定义」规则） | pass | 0/2 | - |
| device-notes-crossnet-manual | docs/device-notes.md 真机跨网手册增补：跨网打洞步骤（蜂窝/办公网对家庭宽带两端操作）、成功判定（连接页显示 P2P 直连非 degraded）、加密开关位置说明、natlab 结论「实验室口径」标注（出处：tasks 5.2；specs/p2p-nat-traversal「natlab 不依赖真机」真机跨网验证步骤单独写入手册；design Risks 实验室口径标注） | pass | 0/2 | - |
| crypto-test-coverage-mechanical | 加密测试维度机械覆盖核对：CryptoCodecTest 测试结果 XML 含九个具名维度用例——往返/空片/最大片/counter 边界/AAD 篡改/方向子钥隔离（c2s/s2c）/重放（重复 counter 与窗口外旧 counter 丢弃）/512 窗口/开销上限（≤明文+18B/片）——且零失败零跳过，防「测试类存在但关键维度缺用例」的形式化达标（出处：specs/stream-encryption「片级 AEAD 加密」加密往返/开销上限 Scenario、「重放防护」重放片丢弃 Scenario；tasks 1.2） | pass | 0/2 | - |
| messages-negotiation-extension-tests | Hello/HelloAck 协商扩展落地且不 bump 版本：Messages.kt 含可选字段 cryptoAlgo{0=none,1=AES128GCM}+双方 32B nonce+encryptOn，PROTOCOL_VERSION 仍为 1（Messages.kt:17 现值），无加密字段的老字节流解码回落 none 缺省值；gradle MessagesTest 全绿验证新旧两种编解码往返（出处：tasks 1.3；specs/stream-encryption「协商与兼容」；design D3/D5） | pass | 0/3 | - |
| natlab-matrix-run-3x3 | natlab 3×3 NAT 组合矩阵实跑：setup 后 natlab_run.py 在两 JVM 协议级对端（protocol 库直跑真实 transport 栈）间跑满 9 组合，每组合给出明确结论（直连成功/端口预测成功/FRP 兜底/失败）与耗时，成功率与平均耗时表落盘为数据文件（docs/ 或 scripts/ 下 JSON/CSV）供回归对比。环境依赖（root+netns/iptables，公网 STUN 可达）；有 fallback: STUN 不可达时各组合仍产出结论（FRP 兜底/FAILED 亦为有效数据点并记录耗时），数据文件照常落盘（出处：tasks 4.2/4.3；specs/p2p-nat-traversal「natlab NAT 模拟验证床」symmetric 组合矩阵完整 Scenario；design D1/D Risks 实验室口径） | pass | 0/4 | - |
| legacy-peer-compat-negotiation | 老端兼容回归：新端（加密开）连无协商字段老端时协商结果 none、连接成功、行为与现状一致——protocol 测试源码含 legacy/老端/兼容上下文的加密具名用例，MessagesTest+CryptoCodecTest 全绿证明未知字段容错路径（Reassembler/控制分发）未被协商扩展破坏（出处：specs/stream-encryption「协商与兼容」老端兼容 Scenario；tasks 1.3；design D5「加密协商失败永不阻塞连接建立」） | pass | 0/3 | - |
| natlab-ei-ei-direct-under-5s | EI 对 EI 默认档打洞直连成功且耗时 ≤5s：解析矩阵数据文件，EI×EI（默认档对默认档）组合结论为直连成功（未经 FRP 中转，非兜底/degraded），elapsed ≤5000ms 记入结果表（出处：specs/p2p-nat-traversal「EI 对 EI 打洞成功」Scenario；design D1 默认档 EI mapping + AP-RR filtering） | pass | 0/2 | - |
| natlab-prediction-on-off-comparison | 预测启用/禁用逐项开关对比数据可回归：矩阵数据/报告含 symmetric 对端组合在预测启用与禁用两种模式下的分别结论（成功率/耗时差异可见），验证打洞增强逐项独立开关与测量落地（出处：tasks 4.3 打洞增强逐项开关（预测启用/禁用对比）；specs/p2p-nat-traversal「打洞增强链」端口预测生效 Scenario 预测成功率数据） | pass | 0/2 | - |
| natlab-endpoint-migration-run | 端点迁移实跑：natlab 迁移场景变更对端 netns 出口规则模拟 IP 切换后重新探针，会话不重建（无重新握手）、视频流恢复、统计连续不重置，迁移结论进结果数据。环境依赖（root+netns/iptables）；无 fallback（出处：tasks 4.3 端点迁移场景（netns 规则变更模拟切换 IP 后恢复）；specs/p2p-nat-traversal「端点迁移」网络切换恢复 Scenario；design D2 ⑤ sequence=0 探针重连复用） | pass | 0/3 | - |
| verification-report-data-complete | verification-report 数据完备（docs/verification-report.md，容错匹配 docs/ 下 *report*.md）：加密往返/重放/兼容/开销单测结果、1080p mid 档加密开/关 fps/码率/RTT 对比与差值百分比、natlab 3×3 成功率表、开关关闭线路字节回归对比四节齐备（出处：tasks 5.3；specs/stream-encryption「加密性能测量」「开销上限」Scenario；proposal What Changes 开销 ≈1.6% 量级） | pass | 0/2 | - |
| token-mismatch-reject-path | token 不匹配拒绝路径保持明确错误语义：源端 validateToken 失败回 HELLO_ACK rejected（REJECT_BAD_TOKEN，"token mismatch"，StreamService.kt:254-257 现状锚点），协商扩展后拒绝仍走显式错误码而非超时/未知错误；protocol 测试源码含 token 拒绝具名用例且 MessagesTest 全绿（出处：specs/stream-encryption「协商与兼容」token 不匹配 Scenario；tasks 3.1；knowledge 协议域 stream-protocol-core 握手认证规则） | pass | 0/4 | - |
| natlab-no-adb-device-run | 无 adb 设备环境全流程自验：净化环境（PATH 剔除 /usr/local/bin 的 adb、ANDROID_HOME 清空，保留 java/python3/ip/iptables 符号链接目录）下 command -v adb 失败且 natlab 全流程 exit 0 并产出结果，不因 adb devices 为空而失败——纯 JVM 对端不触碰 adb（出处：tasks 4.4；specs/p2p-nat-traversal「natlab 不依赖真机」无设备可跑 Scenario；design D1 natlab 是纯 JVM 对端实验床）。环境依赖（净化 PATH 需保留 java/python3/ip/iptables，规划期本机已核实在位）；无 fallback | pass | 0/3 | - |
| backends-crypto-integration-regression | 四后端接入加密编解码且 none 路径回归不变：TransportBackend/P2pBackend/LanDirectBackend/FrpBackend 均引用 CryptoCodec（视频片与控制通道按 direction 加密），协商结果 none 时路径逐字节与现状一致；TransportLoopbackTest+P2pAndStunTest 既有用例（含 p2p punch 成功与超时回落 frp）全绿作回归门（出处：tasks 1.4；proposal 兼容性「未启用加密路径行为与现状逐字节一致」；knowledge transport 域 transport-backends 统一抽象规则；TransportBackend.kt:84） | pass | 0/4 | - |
| encrypt-off-wire-bytes-identical | 加密开关关闭字节回归：encryptOn=false 时线路字节与加密功能合入前一致（无额外字段、无 16B tag）；机械核对 CryptoCodecTest 结果 XML 含开关关闭/字节一致/回归类具名用例且零失败，CryptoCodec.kt 编码路径存在 disabled 直通分支（出处：specs/stream-encryption「加密开关」开关关闭回归 Scenario；tasks 5.3 开关关闭线路字节回归对比；design D4） | pass | 0/2 | - |
| endpoint-migration-session-binding | 端点迁移会话连续性：会话绑定 sessionId 而非网络地址，信令更新对端地址走显式 setRemote，sequence=0 探针复用于重连，统计连续不重置；transport 源码含 setRemote/sessionId 锚点，测试源码含迁移/地址更新具名用例且 P2pAndStunTest 全绿（出处：tasks 2.3；specs/p2p-nat-traversal「端点迁移」网络切换恢复 Scenario；design D2 ⑤） | pass | 0/3 | - |


## 断言级豁免清单

以下失败断言被归类为非产品缺陷（不记产品 Bug），逐条列出供人工复核豁免是否掩盖真实缺陷（场景级一句话归因不足以复核）。

无非 product-bug 的被豁免失败断言。

## 本轮场景集变更

当前稳定场景集共 25 个（第 1 轮）。

- 首轮写入 25 个场景

