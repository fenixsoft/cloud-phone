# 上行压测报告：1080p 高熵负载 × 三网络路径（gaming-uplink-1080p）

生成时间：2026-11-09；环境：双模拟器（源端 emulator-5554 / 客户端 emulator-5556，同宿主）、
负载 = loadgen 高熵 GL 场景（`--es entropy` 三档）、会话参数 = 1920×1080@60fps（游戏档）、
采样 1s 粒度（客户端 cloudphone-stats 1Hz 对齐）、稳态窗口 = 后 60s（启动瞬态剔除）。

**测量口径与三段证据链**（design D2）：

| 段 | 测量项 | 数据源 | 说明 |
|---|---|---|---|
| 编码器段 | 编码器输出字节率 / 出帧 fps / 帧间隔 | 源端 `t5 enc bytes=/gapMs=`（EncoderPipeline 输出逐帧插桩，触摸注入维持 2s 插桩窗口） | PipelineCounters 仅有帧计数无字节计数，编码器输出字节率以既有 t5 逐帧字节插桩为源（零产品码改动） |
| 传输段 | Socket 层发送字节率/帧率、客户端收帧率 | 客户端 `cloudphone-stats`（源端 TransportStats.bytesSent 窗口差值）+ `cloudphone-latency delayMs` 帧到达计数 | TransportStats 收发字节率、序号缺口丢片率 |
| 网络段 | RTT（压测 vs 空载基线）/ 丢片率 / 端点归属 | 客户端 PONG 实测 RTT（`rtt Ms=`）；源端 stats RTT 字段无采样调用方恒为 0，不作口径 | 端点证据：客户端 TCP established 对端 |

## 压测矩阵数据（mid 档 120s/组 + 空载基线 20s）

| 路径 | 稳态 sustained(kbps) | 编码器输出(Mbps) | 编码器 fps | 收帧 fps | 丢片率%(均值/最大) | 客户端RTT 压测/基线/膨胀(ms) | 空载吞吐(kbps) | 端点证据 | 判定 |
|---|---|---|---|---|---|---|---|---|---|
| lan-direct | 2524 | 2.52 | 12.2 | 12.3 | 0.00/0.00 | 19.2/1.0/18.2 | 0（静止 VBR） | endpoint-seen:10.0.2.2 | REFERENCE（参照） |
| frp | 2569 | 2.54 | 12.5 | 12.7 | 0.00/0.00 | 25.8/12.4/13.4 | 0（静止 VBR） | endpoint-seen:112.74.85.74 | ENCODER_CAPPED-真机复核（网络段 PASS） |
| p2p | 2428 | 2.39 | 11.5 | 11.7 | 0.00/0.00 | 30.6/15.0/15.6 | 0（静止 VBR） | endpoint-seen:112.74.85.74 | ENCODER_CAPPED-真机复核（网络段 PASS） |
| lan-direct（high 探针） | 3924 | 3.91 | 11.8 | 12.1 | 0.00/0.00 | 12.7/—/— | — | endpoint-seen:10.0.2.2 | 编码器极限探针（顶满确认） |

三组会话均 120s 不断流（disconnects=0），delay P95 ≤ 4ms（收流→渲染管线延迟）。

## 三段链式归因结论（每结论附证据）

**总瓶颈归属：编码器吞吐（第一瓶颈）**。证据链：`编码器输出 ≈ 传输吐出 ≈ 客户端收帧`，三级速率一致且丢片 0、RTT 膨胀均在容差内——三段中唯一到顶的是编码器。

1. **编码器段（受限）**：mid 档高熵内容下编码器输出 2.39–2.54Mbps@11.5–12.5fps，远低于游戏档 8Mbps 目标；high 档（目标 20000kbps）探针顶满在 3.91Mbps@11.8fps——**编码器吞吐即第一瓶颈，记 ENCODER_CAPPED-真机复核**。
   证据：三组 t5 逐帧字节率（docs/uplink-stress-results.json enc_out_mbps/enc_fps）；标定附录 A 两轮数据；提高内容强度（档位 18,52→24,64）输出不变（4.01→4.03Mbps）= 顶满证明。
   输入帧率口径：负载 App GL 渲染 47–49fps（`cloudphone-loadgen entropy fps=` 日志），编码器只消耗出 ~12fps——输入帧率未满 60 的根因是编码器消费速度而非负载供给。
2. **传输段（未受限）**：Socket 层吐出速率与编码器输出一致（frp 2569 vs 2541kbps，偏差 1.1% < ±10% 容差；p2p 2428 vs 2390kbps），客户端收帧率与编码器出帧一致（12.7 vs 12.5 / 11.7 vs 11.5 fps）——传输层全程跟住编码器产出，无背压丢帧（droppedFrames=0）。
3. **网络段（未受限，frp/p2p 独立判定均 PASS）**：丢片率 0.00%（TransportStats 序号缺口，稳态最大值）；RTT 膨胀 frp +13.4ms（25.8 vs 基线 12.4）、p2p +15.6ms（30.6 vs 基线 15.0），均在容差 max(20ms, 2×基线) 内；frp 端点证据 endpoint-seen:112.74.85.74（真公网路径）。**网络段判定不受编码器到顶影响**（design D3 两段分开口径）。

### frp 两因素标注（VPS 中转上限 vs 家庭上行）

空载 frp 路径 RTT 基线 12.4ms、压测期 25.8ms 且丢片 0——本组压测未触及传输上限（编码器 2.5Mbps 的产出远低于公网中转能力）。**VPS 中转上限与家庭上行带宽的真实余量未被本压测探明**（编码器先到顶）；家庭上行绝对值须以真机 iperf3 对公网 VPS 单跑测定（device-notes §9.1 步骤），届时与 frp 空载吞吐对比即可标注两因素归属。

### p2p 同宿主拓扑局限标注

本环境 p2p 为**同宿主 UDP 直连**（源/客户端双模拟器在同一台物理机，经公网 rendezvous 完成信令）——打洞真实性、真实 NAT 穿越与公网 UDP 丢包特性均未被本组数据覆盖，跨网打洞由 p2p-crossnet-traversal SPEC 承担。本组 p2p 数据仅验证「同宿主 UDP 直连通路在高熵负载下的吞吐/RTT/丢片表现」，不可外推为跨网 p2p 结论。

### 编码器受限的真机复核项（列入检查表）

- 真机硬编（c2.qti/OMX.qcom 系）1080p60 高熵编码吞吐测定（预计远高于模拟器软编 ~4Mbps@12fps）；
- 真机吞吐达标后按 device-notes §9 重跑三段归因（预期瓶颈转移至传输/网络段）；
- 若真机硬编仍 <8Mbps：码控算法优化另立项（本 SPEC Non-Goal）。

<!-- uplink-stress:data-begin -->
<!-- uplink-stress:data-end -->

## 附录 A：档位标定（Task 1.2）

标定纪律：档位定义不看代码参数看编码器实际输出。口径：lan-direct、1080p@60fps、稳态 60s（70s 采集窗）。

**第 1 轮**（noise/particle = low 4.0/12，mid 9.0/26，high 18.0/52；目标 low/mid 10000、high 20000 kbps）：

| 档位 | 编码器输出(Mbps) | 编码器 fps | 传输 sustained(kbps) |
|---|---|---|---|
| low | 1.95 | 11.9 | 1978 |
| mid | 2.30 | 11.6 | 2304 |
| high | 4.01 | 12.2 | 4058 |

**第 2 轮（最终参数）**（noise/particle = low 3.0/9，mid 11.0/30，high 24.0/64）：

| 档位 | 编码器输出(Mbps) | 编码器 fps | 传输 sustained(kbps) | 与第 1 轮差 |
|---|---|---|---|---|
| low | 1.80 | 12.8 | 1819 | −0.15（降强度生效） |
| mid | 2.51 | 12.6 | 2538 | +0.21（升强度生效） |
| high | 4.03 | 12.1 | 4065 | +0.02（顶满不变） |

**结论**：
- 档位阶梯单调可复现（两轮 1.8–1.95 / 2.3–2.51 / 4.01–4.03，复现偏差 ≤0.21Mbps），最终参数以第 2 轮为准（GameLoadActivity `EntropyTier`）。
- **low≈4Mbps、mid 8–12Mbps 目标在本环境物理不可达**：模拟器软编（c2.android.avc）1080p60 编码吞吐上限 ≈4Mbps@12fps（high 档两次独立测量 4.01/4.03 + 极限探针 3.91 三点一致；提高内容强度输出不再增长 = 顶满证明）。按 SPEC「若达不到则强度参数调整后重标定直至达标或如实记录编码器受限上限」口径如实落档；档位间差 ≥2Mbps 的设计期望受同一上限约束（全部三档总跨度仅 2.2Mbps），真机硬编复核后按 §真机复核项重标。
- 三档阶梯证据：`docs/uplink-calibration.json`；压测逐组数据：`docs/uplink-stress-results.json`。

## 附录 B：判定口径与阈值

- 网络段 PASS（frp/p2p 独立）：编码器未受限时 sustained ≥8000kbps 且 fps ≥50 且丢帧 <0.5%；编码器受限时传输吐出速率跟上编码器输出（±10%）且丢帧 <0.5% 且 RTT 膨胀 ≤ max(20ms, 2×基线)。
- ENCODER_CAPPED 判据：编码器输出 <8Mbps 且传输吐出一致（±10%）且客户端收帧与编码器出帧一致（±10%）——三级速率一致即瓶颈在编码器，网络段照常独立判定。
- lan-direct 为参照路径，只记数据不判 PASS/FAIL。
