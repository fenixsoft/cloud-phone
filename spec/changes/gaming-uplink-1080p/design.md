# Design: gaming-uplink-1080p

## Context

- M0 验收实测：game 负载 720p 仅 1.0–2.8Mbps（swiftshader 软渲染画面简单，编码器「无东西可压」），1080p 稍高但仍远低于游戏档设计区间 8–15Mbps
- 模拟器编码器为 c2.android.avc **软编**，720p60 实测 ≈850kbps 峰值（device-notes §4 编码吞吐行）——1080p 高熵下软编可能成为第一瓶颈，本 SPEC 必须能区分「编码器到顶」与「网络到顶」
- 现有基座：loadgen（GameLoadActivity/StaticAppActivity 独立 APK 装源端模拟器）、run_matrix.py（管线授权 ensure_pipeline + START_SESSION 参数注入 autoWidth/autoHeight extras + 分组采集）、PipelineCounters（采集/编码逐级计数）
- 上行路径归属：源端在家庭服务器，lan-direct 走局域网、frp 走公网隧道（112.74.85.74 中转）、p2p 为同宿主 UDP 直连（打洞真实性由 p2p-crossnet-traversal SPEC 承担）——「上行考验」的核心是 frp 公网路径（真实上行带宽）与编码器吞吐
- 用户口径已确认：自研高熵负载（Agent 全自动）+ 真机真实游戏人工体验（最终判定），不做真实游戏 APK 自动化

## Goals

1. 高熵负载可稳定压出游戏档码率区间（mid 档 1080p 持续 ≥8Mbps 编码输出），三档强度可复现标定
2. 三网络 1080p 压测数据 + 瓶颈归属结论（编码器/传输/网络三段，证据为计数器与字节率）
3. 真机人工体验手册落位，判定标准量化（非「感觉流畅」）

## Non-Goals

- 不改编码器实现与码控算法（发现瓶颈如实记录，真机硬编复核/算法优化另立项）
- 不做真实游戏 APK 自动化（用户否决：x86 兼容性与反作弊风险）
- 不做 4K/高帧率档（60fps 上限维持现状）
- 不在本 SPEC 内解决跨网 P2P 打洞真实性（独立 SPEC）

## Decisions

### D1 高熵场景：GL 片元着色器粒子/噪声场，档位以编码器输出标定
GameLoadActivity 新增 `--ez entropy <low|mid|high>` intent extra：全屏 GLSurfaceView，片元着色器按时间偏移的多层噪声叠加（value-noise 3 octave + 高对比粒子层 + 每 100ms 随机色相漂移），保证每帧像素大面积变化（空域熵）且全局运动（时域熵）。三档=粒子密度/噪声频率缩放。**标定纪律**：档位定义不看代码参数看编码器输出——low 目标 ≈4Mbps、mid ≈8–12Mbps、high 顶满编码器吞吐上限；达不到即调强度参数，标定结果写入 uplink-report。

### D2 瓶颈归属三段测量
- 编码器段：编码器实际输出字节率（MediaCodec BufferInfo 输出统计，已进 PipelineCounters）+ 编码器输入帧率 + `dequeueOutputBuffer` 饥饿时长——编码器输出持续低于目标码率且输入帧率满 60 → 编码器受限
- 传输段：Socket 层发送/接收字节率（TransportStats 已有）vs 编码器输出字节率——传输吐出速率跟不上编码器产出 → 传输受限
- 网络段：RTT 膨胀（压测期 vs 空载）、UDP 丢片率、TCP 重传/缓冲积压——网络受限
报告按「编码器输出 < 传输能力 < 网络能力」链式归因，每结论附证据数据。

### D3 压测 runner 复用矩阵管线，新增持续负载采集
`scripts/uplink_stress.py` 复用 run_matrix 的 ensure_pipeline（授权顺序铁律：FGS 先于投影授权）与参数注入模式；差异点：单场景时长 120s（默认，可 --duration）、采样 1s 粒度、稳态窗口取后 60s（掐掉启动瞬态）；矩阵 = {lan-direct, frp, p2p} × 1080p × mid 档（high 档作为编码器极限探针单跑 lan-direct）。断言口径：frp/p2p 路径稳态 sustained ≥8Mbps 且 fps ≥50 且丢帧率 <0.5% → PASS；若编码器输出先低于 8Mbps（编码器受限）→ 结论改记「ENCODER_CAPPED-真机复核」，网络段达标仍记网络 PASS——两段分开判定。

### D4 温度与降频观测（模拟器口径，真机手册同口径）
压测期采集宿主负载与模拟器 CPU 频率变化（adb shell dumpsys），长跑 fps 衰减如实记录；真机手册步骤同口径（GT/soc 温度不可得时用 fps 衰减曲线替代）。

### D5 真机人工体验手册量化判定
device-notes 新章节：用户真机跑 loadgen 高熵（装 loadgen APK）与 1–2 款自选真实游戏各 ≥10 分钟，记录 StatsView（fps/码率/RTT/丢包）每 2 分钟一档；判定：游戏档 1080p 稳态 fps ≥50、码率 ≥8Mbps、无可感知卡顿（主观 1–5 分记录）；上行瓶颈（家庭宽带上传速率实测 iperf3 对公网 VPS 单跑）先行测定作为基线。

## Risks

- 软编 1080p60 可能完全顶不到 8Mbps（编码器即天花板）→ 判定口径已拆分（D3 两段分开），结论如实记录，真机硬编复核列入 device-notes 手册
- frp 公网中转受 VPS 带宽上限约束（112.74.85.74 出口带宽未知）→ runner 先测空载路径基线，报告标注「VPS 上限 vs 家庭上行」两因素
