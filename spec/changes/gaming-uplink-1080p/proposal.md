# Proposal: gaming-uplink-1080p

## Why

M0 验收矩阵的负载是 loadgen 简单动画（swiftshader 软渲染、画面熵低），实测码率仅 1.0–2.8Mbps，远低于游戏档设计的 8–15Mbps 上行区间；「真实游戏内容能否撑住 1080p 高码率串流」从未被验证。模拟器软编（c2.android.avc 720p60 峰值 ≈850kbps）与网络路径的瓶颈归属也从未量化。本 SPEC 用自研高熵 GPU 负载把编码器与链路压满，在 1080p 档位下对三网络路径做持续压测，量化「编码器吞吐 vs 传输吞吐 vs 网络质量」三段瓶颈归属；真实游戏由用户真机人工体验做最终判定（手册步骤随本 SPEC 落位）。用户已确认：不做真实游戏 APK 自动化（x86 兼容/反作弊风险）。

## What Changes

- `source/loadgen` GameLoadActivity 新增高熵 GPU 场景：GL 片元着色器粒子/噪声场（空域+时域熵拉满），三档强度（intent extra 可控，按编码器实际输出 kbps 标定，目标档位持续输出 ≥4/8/12Mbps）
- 新增压测 runner `scripts/uplink_stress.py`：1080p 高熵负载 × {lan-direct, frp, p2p} × 时长（默认 120s/组），采集 fps/码率/RTT/丢包/温度，复用 run_matrix 的管线授权与参数注入模式
- 新增压测报告 `docs/uplink-report.md`：每组数据 + **瓶颈归属结论**（编码器吞吐上限 vs 传输层吞吐 vs 网络路径质量，依据 PipelineCounters 逐级计数与编码器输出字节率）
- `docs/device-notes.md` 新增真机游戏人工体验章节：loadgen 高熵 App + 真实游戏各 10 分钟的 StatsView 记录口径与判定标准

## Capabilities

### New Capabilities

- `uplink-stress-loadgen`：高熵 GPU 负载、三网络上行压测与瓶颈归属量化

### Modified Capabilities

- 无（复用 validation-harness 的矩阵管线与 source-capture-encode 的计数器，不改变既有能力验收阈值）

## Impact

- **影响代码面**：`source/loadgen/.../GameLoadActivity.kt`（增强）、`scripts/uplink_stress.py`（新增）、`docs/uplink-report.md`（新增）、`docs/device-notes.md`（增补）
- **兼容性**：loadgen 为独立测试 App（不影响产品功能）；runner 与报告纯增量
- **风险**：模拟器软编可能先于网络到顶（软编吞吐上限）——判定口径明确区分「编码器受限」（结论如实记录，真机硬编复核列入检查表）与「网络受限」，禁止把编码器瓶颈误判为网络故障；p2p 组仍为同宿主拓扑（跨网打洞由 p2p-crossnet-traversal SPEC 承担，报告如实标注拓扑局限）
