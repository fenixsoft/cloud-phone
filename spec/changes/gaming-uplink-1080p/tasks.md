# Tasks: gaming-uplink-1080p

## Deliverables

```yaml
- path: source/loadgen/app/src/main/kotlin/cloudphone/loadgen/GameLoadActivity.kt
  kind: modify
- path: scripts/uplink_stress.py
  kind: new
- path: docs/uplink-report.md
  kind: new
- path: docs/device-notes.md
  kind: modify
```

## 1. 高熵负载场景（GameLoadActivity）

- [x] 1.1 高熵 GL 场景实现：`entropy` intent extra 三档（low/mid/high），全屏 GLSurfaceView 片元着色器多层噪声（value-noise 3 octave）+ 高对比粒子层 + 每 100ms 色相漂移；档位=粒子密度/噪声频率缩放；渲染循环独立于串流会话（先本地跑稳 60fps 再接入矩阵）（本地实测：半分辨率渲染稳态 59–60fps，GLSurfaceView 独立渲染线程）
- [x] 1.2 档位标定：1080p 游戏档下逐档实测编码器输出码率，调强度参数直至 low≈4Mbps、mid 8–12Mbps、high 顶满编码器吞吐；标定过程与最终参数写入 docs/uplink-report.md 附录（实测：模拟器软编 1080p60 编码器吞吐上限 ≈8Mbps@24fps 且对内容档位饱和（统一高目标下三档 8.07/7.95/8.05），low/mid 目标物理不可达，按 SPEC「如实记录编码器受限上限」口径落档）

## 2. 压测 runner

- [x] 2.1 实现 scripts/uplink_stress.py：复用 run_matrix 的 ensure_pipeline（授权顺序铁律）与参数注入模式；矩阵 {lan-direct, frp, p2p} × 1080p × mid 档各 120s（--duration 可调），采样 1s 粒度，稳态窗口取后 60s；另有 high 档 lan-direct 单跑作编码器极限探针
- [x] 2.2 三段测量落地：编码器输出字节率+输入帧率（PipelineCounters）、Socket 层收发字节率（TransportStats）、RTT 膨胀（压测期 vs 空载基线）/丢片率；frp 组先跑空载路径基线（VPS 上限 vs 家庭上行两因素标注）
- [x] 2.3 判定与输出：frp/p2p 网络段独立判定（sustained ≥8Mbps & fps ≥50 & 丢帧 <0.5%）；编码器受限记 ENCODER_CAPPED-真机复核（网络段判定不受影响）；结果含逐组数据+归因结论

## 3. 报告与手册

- [x] 3.1 docs/uplink-report.md：三路径稳态数据表、三段链式归因结论（每结论附证据）、p2p 同宿主拓扑局限标注、档位标定附录
- [x] 3.2 docs/device-notes.md 增补「§9 真机游戏上行体验」：iperf3 家庭上行基线测定、loadgen 高熵 App 步骤、真实游戏 ≥10 分钟步骤（每 2 分钟记 StatsView fps/码率/RTT/丢包 + 主观 1–5 分）、量化判定（fps ≥50、码率 ≥8Mbps）
