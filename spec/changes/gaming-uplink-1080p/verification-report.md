# 验证报告：gaming-uplink-1080p

> ⚠️ 本报告由框架于 2026-11-09T04:35:23Z 自动合成（裁决 Agent 未写报告，已由框架合成），内容为结构化验证产物（report.json / .fenix-run-result.json）的忠实渲染。
> 报告时效标记（判定本报告是否属上一轮）：合成源轮次指纹：report.json@2026-11-09T04:35:23.018460+00:00+run-result@mtime1794198923053254990

## 结论：✅ 通过（verified）

**状态：verified — 6/6 场景通过，0 失败，0 错误（全部场景通过）**

全绿快道: 6/6 场景通过、0 失败 0 警告——无分类对象，跳过 judge 机械 verified

| 指标 | 数量 |
| --- | --- |
| 通过 | 6 |
| 失败 | 0 |
| 错误 | 0 |
| 产品 Bug | 0 |
| 本链增量 Token（输入） | 0.17M |
| 本链增量 Token（输出） | 0.02M |


## 场景结果

| 场景 | 描述 | 状态 | 失败断言 | 分类 |
| --- | --- | --- | --- | --- |
| gl-entropy-60fps | 高熵 GL 场景本地跑稳验证：构建安装后以 entropy=mid 启动 GameLoadActivity，采集 12s logcat，渲染 fps 稳定 ≥55（不掉帧的负载才有资格谈码率标定） | pass | 0/3 | - |
| tier-calibration-ladder | 档位标定数据校验：docs/uplink-calibration.json 三档编码器输出按双分支判定——三档可区分时呈单调阶梯（相邻档差 ≥2Mbps）；编码器饱和时（三档收敛极差 ≤0.5Mbps，SPEC『如实记录编码器受限上限』口径）须 high 档顶满吞吐（≥3.8Mbps）且 mid 档如实记录 ENCODER_CAPPED 裁决/标志与归因文本；两种分支均要求编码器输入帧率 >5（数据可信） | pass | 0/3 | - |
| stress-matrix-results | 压测矩阵数据校验：docs/uplink-stress-results.json 含 lan-direct/frp/p2p 三组 mid 档 120s 压测 + high 极限探针；frp/p2p 网络段独立判定（丢片<0.5%、RTT 膨胀在容差内）、路径端点证据齐全、三段速率一致（编码器受限归因成立） | pass | 0/3 | - |
| uplink-report-content | 压测报告内容校验：docs/uplink-report.md 含三路径稳态数据表、三段链式归因结论（附证据）、p2p 同宿主拓扑局限标注、frp 两因素标注、档位标定附录与 ENCODER_CAPPED 真机复核项 | pass | 0/3 | - |
| device-notes-manual | 真机手册校验：docs/device-notes.md §9 含 iperf3 家庭上行基线命令、loadgen 高熵 App 步骤、真实游戏 ≥10 分钟每 2 分钟记录口径（StatsView 四项 + 主观 1–5 分）与量化判定（fps ≥50、码率 ≥8Mbps） | pass | 0/3 | - |
| runner-live-smoke | 压测 runner 实跑冒烟：uplink_stress.py 对 lan-direct 跑 30s（稳态 20s、空载基线 10s）全链路（管线对齐→高熵会话→双端采集→稳态统计→判定）成功并落 JSON | pass | 0/4 | - |

## 本轮场景集变更

当前稳定场景集共 6 个（第 1 轮）。

- 首轮写入 6 个场景

