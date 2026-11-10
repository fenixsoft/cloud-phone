# P2P 跨网打洞与 natlab 验证床

<!-- knowledge: capability=p2p-nat-traversal rev=1
     sources=spec/changes/p2p-crossnet-traversal -->

P2P 路径的跨网（家庭 NAT ↔ 蜂窝/办公网）直连能力：按序回退的打洞增强链、网络切换下的端点迁移，以及 netns NAT 模拟实验室（natlab）自动化验证床。

## 规则

- 打洞增强链按序回退：IPv6 直连优先（v6 候选排前，同 v6 网可达即免打洞）→ 多候选并行探测（多 STUN 节点 SRFLX + 本地多网卡候选，每 200ms 轮询全部目标）→ symmetric 端口预测（窗口 ≤64，仅对端判定 symmetric 时启用，EI 档跳过省时）→ 全部失败走 FRP 中转兜底（视频切 FRP、连接标记 degraded，用户可见）。每级独立计时进 Diagnostics（PunchStages 逐级记录 elapsedMs）。
- 对端 symmetric 判定：SRFLX 多节点探测同 IP 不同端口即判 symmetric（候选文本 `;nat=sym` 标记随信令传递）；端口预测为对端 SRFLX 端口差分外推批量探测，预测 miss 不阻塞会话建立——FRP 兜底是合法终态，不算打洞失败。
- 5s 打洞定时器内未成上报失败并回落 frp 后端，会话不中断、回落事件留痕（PUNCH_TIMEOUT + PUNCH_FALLBACK_FRP）；定时器超时后打洞在后台继续，命中后视频回切直连。
- 端点迁移：会话绑定 sessionId 而非网络地址；对端地址变化（网络切换）经信令显式 setRemote 更新目标、探针循环恢复，sequence=0 探针复用于重连；会话不重建、无重新握手记录、统计连续不重置。
- natlab 验证床：scripts/natlab_setup.sh 用 netns（natlab-cl / natlab-srv / natlab-wan）+ veth + iptables 构造 NAT 档位（ei=端点无关映射+端口保留、ap-rr=地址依赖过滤、symmetric=per-destination 随机端口、sym-seq=顺序端口供预测命中真实发生、mixed=一端 EI 一端 symmetric），子命令 up / profile / clear / down / status 幂等，规则可独立启停。
- natlab_run.py 在两 JVM 协议级对端（protocol 库直跑 transport 栈，非模拟）间执行 3×3 矩阵（client 档 × server 档 ∈ {ei, ap-rr, symmetric}，默认每组合 2 轮），每组合给出明确结论 direct / prediction / frp_fallback / fail 与耗时；成功率与平均耗时落盘 docs/natlab-matrix.json（--data-out 可改），供打洞逻辑改动回归对比。
- symmetric 相关组合另跑预测启用/禁用对比（--no-prediction 复跑）；EI×EI 默认档结论为直连成功（未经 FRP 中转）且耗时 ≤5s。
- 端点迁移场景：ei×ei 直连建立后改 client netns 出口 IP（10.200.1.2→10.200.1.3）模拟切换，验证会话不重建、视频流恢复、统计连续，迁移结论进结果数据。
- natlab 全流程（setup + run）不依赖 adb 设备——纯 JVM 对端，无设备环境照常产出矩阵；natlab 结论一律标注「实验室口径」，真机跨网判定按 docs/device-notes.md 手册人工复核（成功判定以连接页显示 P2P 直连非 degraded 为准）。

## 代码锚点

- 主实现：protocol/src/main/kotlin/cloudphone/transport/P2pBackend.kt（打洞链、端口预测 PortPredictor、端点迁移）
- 单源符号：protocol/src/main/kotlin/cloudphone/transport/StunClient.kt（discoverSrflxAll 多节点探测与 symmetric 判定）、protocol/src/main/kotlin/cloudphone/natlab/NatlabPeer.kt（JVM 协议级对端）
- 消费方：scripts/natlab_setup.sh（netns/iptables 拓扑）、scripts/natlab_run.py（矩阵执行与数据落盘）
- 守卫测试：protocol/src/test/kotlin/cloudphone/transport/P2pAndStunTest.kt

## 溯源

- 2026-11-10 p2p-crossnet-traversal（spec/changes/p2p-crossnet-traversal/）— 新建本页：确立打洞增强链逐级回退、端口预测门控、端点迁移会话绑定与 natlab 3×3 验证床规则。
