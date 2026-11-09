# 验证矩阵与延迟测量

<!-- knowledge: capability=validation-harness rev=1
     sources=spec/changes/m0-a2a-streaming-pipeline -->

端到端验收基础设施：负载生成、一键三网络 × 双负载 × 双分辨率矩阵、端到端延迟多口径测量与机械化通过判定。

## 规则

- 负载生成 App 两档（cloudphone.loadgen，独立安装于源端模拟器）：游戏档 GameLoadActivity——恒定 60fps 持续动画 + 帧计数 overlay + 可点击交互元素，overlay 实测帧率稳定 60±2fps；应用档 StaticAppActivity——静态列表页，无交互时画面静止、源端编码帧率自然下降。
- 矩阵自动化：scripts/run_matrix.py 一条命令完成 3 网络 × 2 负载 × 2 分辨率（720p/1080p）= 12 组合、各 60s，自动完成模拟器检查、App 安装、MediaProjection 授权弹窗处理（uiautomator + 3 次重试 + 失败截图存档）、连接建立与指标采集；每组合失败自动重试 1 次；产出 docs/validation-report.md（每组合 fps 均值、P95 帧间隔、端到端延迟 P50/P95、码率、丢包率、通过判定）。
- 端到端延迟多口径：视频帧内嵌源端单调时钟时间戳，客户端以 PTS 差值统计；白闪法（源端周期白帧 → 客户端像素检测）校准一次全链路延迟，报告记录与 PTS 法的差值；触摸往返经负载 App 可点击元素的画面回执（overlay 帧计数/颜色变化）统计 P50/P95。
- 可用判定线（judge 机械化）：游戏档平均 fps≥45 且端到端延迟 P95≤200ms、触摸往返 P95≤200ms、frp 路径 60s 不断流（断流一票 FAIL）、应用档静止码率≤1Mbps；未达标组合标 FAIL 并附原因与真机差距说明。
- 指标线为模拟器 swiftshader 软渲染口径的放宽值；真机目标（延迟 ≤80ms 量级）另行复核；1080p 组合失败允许 720p 复测并在报告标注。
- run_matrix.py CLI 拒绝未定义参数（非零退出 + 用法提示），--only 支持按 backend/load/res 过滤组合。

## 代码锚点

- 主实现：scripts/run_matrix.py（矩阵编排与 judge 判定）
- 单源符号：scripts/measure_latency.py（延迟测量）、scripts/udp_relay.py（frp-UDP 主机侧中继补偿）
- 消费方：docs/validation-report.md（报告产物）
- 守卫测试：无独立守卫测试（judge 边界判定经验证链逐条断言）

## 溯源

- 2026-11-08 m0-a2a-streaming-pipeline（spec/changes/m0-a2a-streaming-pipeline/）— 新建本页：确立负载两档、12 组合矩阵、延迟多口径测量与可用判定线（首轮 12 组合全 PASS）。
