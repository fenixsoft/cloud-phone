## ADDED Requirements

### Requirement: 负载生成 App
MUST 提供两种负载：游戏档（恒定 60fps 持续动画 + 帧计数 overlay，可点击交互元素）与应用档（静态列表页，画面静止）；二者可独立安装于源端模拟器。

#### Scenario: 游戏档恒定帧率
- **WHEN** 游戏档负载 App 在源端前台运行
- **THEN** 其 overlay 显示的渲染帧率稳定在 60±2fps

#### Scenario: 应用档静止画面
- **WHEN** 应用档负载 App 打开且无交互
- **THEN** 画面内容静止（源端编码帧率自然下降）

### Requirement: 矩阵自动化
`scripts/run_matrix.py` MUST 一条命令完成 3 网络 × 2 负载 × 2 分辨率（720p/1080p）= 12 组合，各跑 60s，自动完成模拟器检查、App 安装、授权弹窗处理、连接建立、指标采集；每组合失败自动重试 1 次；产出 `docs/validation-report.md`（每组合：fps 均值、P95 帧间隔、端到端延迟 P50/P95、码率、丢包率、通过判定）。

#### Scenario: 一键全矩阵
- **WHEN** 在双模拟器就绪的主机执行 run_matrix.py
- **THEN** 无人工干预跑完 12 组合并生成含通过判定的报告

### Requirement: 端到端延迟测量
视频帧 MUST 内嵌源端单调时钟时间戳，客户端以 PTS 差值统计端到端延迟；MUST 另以白闪法（源端周期白帧 → 客户端像素检测）校准一次全链路延迟并写入报告。

#### Scenario: 白闪校准
- **WHEN** 校准模式运行
- **THEN** 报告记录白闪法延迟与 PTS 法延迟的差值

### Requirement: 可用判定标准
矩阵报告 MUST 按以下线判定每组合通过与否：游戏档平均 fps≥45、端到端延迟 P95≤200ms、触摸往返 P95≤200ms、frp 路径 60s 不断流、应用档静止码率≤1Mbps；未达标组合 MUST 在报告中标注原因与与真机目标的差距说明。

#### Scenario: 游戏档达标判定
- **WHEN** 游戏档组合实测 fps≥45 且延迟 P95≤200ms
- **THEN** 该组合标记 PASS

#### Scenario: 未达标组合标注
- **WHEN** 某组合 fps<45 或延迟 P95>200ms
- **THEN** 报告标记 FAIL 并附原因分析
