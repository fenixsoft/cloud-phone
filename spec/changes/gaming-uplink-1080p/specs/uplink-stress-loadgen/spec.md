# uplink-stress-loadgen — 高熵 GPU 负载、三网络上行压测与瓶颈归属量化

## ADDED Requirements

### Requirement: 高熵负载场景
`source/loadgen` GameLoadActivity SHALL 提供高熵 GPU 场景（GL 片元着色器粒子/噪声场），强度经 intent extra `entropy` 三档（low/mid/high）控制，且 SHALL 保持源端渲染帧率稳定在 60fps（不掉帧的负载才有资格谈码率标定）。

#### Scenario: mid 档码率标定达标
- **WHEN** 源端以 1080p 采集 mid 档高熵负载，串流游戏档 8Mbps 目标
- **THEN** 编码器实际输出字节率稳态 ≥8Mbps（稳态窗口 ≥60s），若达不到则强度参数调整后重标定直至达标或如实记录编码器受限上限

#### Scenario: 三档强度可复现区分
- **WHEN** 依次以 low/mid/high 档跑各 60s
- **THEN** 编码器输出码率呈单调阶梯（档位间差 ≥2Mbps），标定数据写入 docs/uplink-report.md

### Requirement: 三网络压测矩阵
压测 runner SHALL 对 {lan-direct, frp, p2p} 三路径 × 1080p × mid 档各执行 ≥120s 持续压测（稳态窗口 ≥60s），采集 fps/码率/RTT/丢包（片级）/温度或频率衰减，报告落 docs/uplink-report.md。

#### Scenario: 三路径数据齐备
- **WHEN** 执行完整压测
- **THEN** 报告含三路径各一组稳态数据（fps/bitrate/RTT/丢片率），p2p 组明确标注同宿主拓扑局限

#### Scenario: 稳态口径
- **WHEN** 任一组压测数据进入报告
- **THEN** 该数据为后 60s 稳态窗口统计（启动瞬态剔除），采样粒度 ≤1s

### Requirement: 瓶颈归属判定
报告 SHALL 含「编码器吞吐 / 传输吞吐 / 网络质量」三段链式归因结论，每结论 SHALL 附证据数据（编码器输出字节率与输入帧率、Socket 层字节率、RTT 膨胀/丢片率），SHALL 区分「编码器受限（ENCODER_CAPPED-真机复核）」与「网络受限」。

#### Scenario: 编码器受限不被误判为网络故障
- **WHEN** 编码器输出持续低于 8Mbps 且编码器输入帧率满 60fps，同时传输层吐出速率与编码器输出一致、RTT 无膨胀
- **THEN** 报告结论为 ENCODER_CAPPED-真机复核，网络段判定独立记录（不因编码器到顶而记网络 FAIL）

### Requirement: 判定口径
frp 与 p2p 路径 SHALL 独立判定：稳态 sustained ≥8Mbps 且 fps ≥50 且丢帧率 <0.5% → 该路径网络 PASS；编码器受限时网络段判定不受影响；任一路径网络段不达标 SHALL 附 RTT/丢片证据与归因（家庭上行 or VPS 中转上限）。

#### Scenario: frp 路径达标
- **WHEN** frp 路径稳态 sustained ≥8Mbps、fps ≥50、丢帧率 <0.5%
- **THEN** frp 网络段判定 PASS，报告给出与空载基线的 RTT 对比

### Requirement: 真机人工体验手册
`docs/device-notes.md` SHALL 新增真机游戏体验章节：家庭上行基线测定（iperf3 对公网 VPS）、loadgen 高熵 App 步骤、真实游戏步骤（≥10 分钟、每 2 分钟记录 StatsView fps/码率/RTT/丢包 + 主观 1–5 分）、量化判定（fps ≥50、码率 ≥8Mbps）。

#### Scenario: 手册可独立执行
- **WHEN** 用户仅凭手册在真机上操作
- **THEN** 每步均有可直接执行的命令或明确 UI 操作与记录表格，判定标准为数值而非主观描述
