# 操作延迟统计方案（op-latency-measurement）

目标：客户端发出操作信号 → 服务端完成操作 → **单次回调**通知客户端 → 客户端测出总耗时并**分段归因**，定位当前「1 秒多」延迟耗在哪一段。方案向后兼容（老端零影响），复用既有插桩与 PING/PONG。

## 0. 现状盘点（设计输入）

已有分段打点（标签 `cloudphone-touch`，触摸延迟插桩 5.1）：

| 段 | 位置 | 文件 | 含义 |
|---|---|---|---|
| t1 / t1b | 客户端 | `client/app/src/main/kotlin/cloudphone/client/input/TouchChannel.kt:34,48` | 触摸产生 / 发送写出耗时（>20ms 告警） |
| t2 | 源端 | `source/app/src/main/kotlin/cloudphone/source/StreamService.kt:407` | INPUT_EVENT 到达控制线程 |
| t3 | 源端 | `source/app/src/main/kotlin/cloudphone/source/input/InputInjector.kt:35` | 注入完成 costMs（实测 1–2ms） |
| t4 | 源端 | `source/app/src/main/kotlin/cloudphone/source/ScreenCapture.kt:117` | 合成帧入编码器，帧间隔 >200ms 的静屏段（实测 gap≈1000ms） |
| t5 | 源端 | `source/app/src/main/kotlin/cloudphone/source/EncoderPipeline.kt:170` | 编码出帧，`ptsDeltaMs` = 合成→出码排队 |

关键缺口：
1. **INPUT_EVENT 无回执**（`protocol/src/main/kotlin/cloudphone/protocol/Messages.kt:236`，仅 sessionId/action/pointerId/x/y，无 opId）→ 客户端无法闭环计时；
2. t1 与 t2 分属两端时钟，**无对齐手段接入触摸链**（PING/PONG 已有 nonce+senderUs/echoUs，`Messages.kt:256-259`，客户端已算 rtt，`ConnectActivity.kt:348-351`）；
3. t5 止于编码出帧，**下行/解码/渲染段无打点**。

已知嫌疑（方案要量化证实/排除）：
- 静屏时合成器仅 ~1fps 出帧（t4 gap≈1000ms，编码器 2s 自动 IDR 兜底 `EncoderPipeline.kt:88`）；`ScreenCapture.onInputActivity()`（`ScreenCapture.kt:232`）**只延长打点窗口、不唤醒合成** → 触摸后画面变化要等下一次合成，均值 ~500ms、最坏 ~1s+，与「1 秒多」吻合；
- 注入本身仅 1–2ms，不是瓶颈；`TcpLink` 已开 `TCP_NODELAY`（`protocol/src/main/kotlin/cloudphone/transport/TransportBackend.kt:460,474`）；
- MOVE 事件逐条发送（`TouchChannel.kt:67-91`），存在控制通道占用风险。

## 1. 协议扩展（向后兼容）

沿用两个既有模式：TextInput/TextInputAck 的 seq-Ack 对账（`Messages.kt:308-321`）与可选尾块的 `remaining()` 探测（`Messages.kt:371` crypto 块）。

### 1.1 InputEvent 追加可选尾块 opId(u32)
- 客户端：opId≠0 时尾部追加 `putInt(opId)`；opId=0 线格式与现状逐字节一致。
- 兼容性：老源端解码分支（`Messages.kt:596-602`）按定宽字段读完，不校验 `remaining()`，尾字节自然忽略；新端 `remaining()>=4` 读 opId。

### 1.2 新消息 INPUT_ACK = 0x12（源端 → 客户端，MsgType 表 `Messages.kt:21-44` 追加）

```
[u32 opId][u8 result][i64 arrUs][i64 injDoneUs][u64 frameSeq][i64 encPtsUs][i64 srvNowUs]
result: 0=ok 1=unknown session 2=inject failed 3=frame-wait 超时(frameSeq=0)
```

- 老客户端收 0x12 → `Unknown` → 协议既有约定忽略不中断（`Messages.kt:662-666`）；gateway（client-web）是字节中继无需改动。
- 全部时间戳为**服务端单调钟** `nowUs()`（SystemNanoTime 派生微秒，与 ptsUs 同钟，`AudioChunk` 先例 `Messages.kt:271-274`）。

## 2. 服务端分段（服务端内单调钟，无时钟漂移）

`StreamService.handleControl` INPUT_EVENT 分支改造：

```
t2' arrUs = nowUs()                          // 到达（对齐既有 t2 打点）
→ injector.inject(...)（复用 t3）
→ t3' injDoneUs = nowUs()
→ 等「注入后首帧」：向 ScreenCapture/EncoderPipeline 注册一次性回调
   （判定：编码出帧 seq，且其 ptsUs > arrUs 派生的合成时刻）
→ t6a ack 组装：send(InputAck) + logcat
```

报告段位：
- `injectMs = injDoneUs − arrUs`（预期 1–2ms）
- `frameWaitMs = encOutUs − injDoneUs`（**预期大头**；cap 1500ms，超时 result=3）
- `encQueueMs = encOutUs − encPtsUs`（即现有 ptsDeltaMs，合成→编码排队）

超时兜底用 handler `postDelayed` 1500ms：未等到首帧也**必须回调一次**（result=3），保证客户端闭环。

## 3. 客户端测量（一次回调闭全部段）

`TouchChannel`：`opId = AtomicInteger`，**仅 DOWN/UP 携带**（MOVE=0 不回执，防风暴）；发送时刻记 `t_send`（elapsedRealtime，t1 处已有）。

`ConnectActivity.handleControl` 新增 INPUT_ACK 分支：
```
E2E_ack   = t_ackRecv − t_send                      // 客户端单调钟，精确
uplinkMs  = arrUs − clockOffset − t_send            // ±rtt/2 不确定度
serverMs  = (srvNowUs − arrUs)                      // 服务端内差值，精确
downMs    = E2E_ack − uplinkMs − serverMs           // ACK 下行 + 客户端分发
```

时钟偏移复用 PING/PONG 做 NTP 式估计：
```
offset = ((srvRecvUs − cliSendUs) + (srvSendUs − cliRecvUs)) / 2
```
取 min-RTT 样本为基准（会话内 Pong 已有，`ConnectActivity.kt:348`），不确定度 ≈ rtt/2，LAN 下 <5ms。

**视觉反馈闭环（不增加第二条回调消息）**：InputAck 携带 `frameSeq` → 客户端解码渲染线程在该 seq `releaseOutputBuffer` 时打 t8 →
```
glassToGlass = t8 − t_send      // 触摸→该帧上屏，真端到端口径
```

输出统一标签 `cloudphone-latency`：
```
op opId=N transport=LAN_DIRECT e2eMs=.. uplinkMs=.. srvMs=.. downMs=.. injectMs=.. frameWaitMs=.. encQueueMs=.. g2gMs=..
```
（transport 取自会话 STATS.transport，`Messages.kt:73-80`，便于按链路分组。）

客户端内存 ring buffer（最近 200 条 op），StatsView 增加 P50/P95 展示（复用现有统计 UI 约定）。

## 4. 判读表——1 秒多去哪了

| 症状 | 结论 | 修复方向 |
|---|---|---|
| `frameWaitMs` ≈ 500–1000ms | 静屏合成器 ~1fps（当前最大嫌疑） | `onInputActivity` 里主动唤醒：`requestIdr()` / SurfaceFlinger sync frame，而非仅延长打点窗口 |
| `encQueueMs` 大 | 编码器输入排队/backpressure | 降档（分辨率/码率）、查编码器输出拥塞 |
| `uplinkMs` 大 | 控制通道被 MOVE 风暴占用 | 客户端 MOVE 合并/限频（如 50Hz cap） |
| `downMs` 大 | 下行拥塞或解码输入堆积 | 查客户端解码队列深度、码率档 |
| 各段都小但 `g2gMs` 大 | 渲染端丢帧/双缓冲延迟 | 客户端渲染路径（SurfaceView/纹理上传） |
| `E2E_ack` 小但用户仍觉 1s | 控制回执≠画面反馈 | 以 `g2g` 为优化主口径 |

## 5. 分析与验收

- 新增 `scripts/measure_op_latency.py`：抓 `logcat -d` 的 `cloudphone-touch`/`cloudphone-latency`，按 opId 配对，输出各段 P50/P95/max 与直方图，按 transport 分组；`--include-move` 可选计入 MOVE（默认只统计 DOWN/UP）。
- 段位预算（对齐既有验收线 P95≤200ms，`docs/realdevice-retest-checklist.md` 口径）：uplink ≤20ms、inject ≤10ms、frameWait ≤100ms（30fps 下 ≤1 帧）、enc ≤50ms、down+decode+render ≤50ms。
- `scripts/measure_latency.py` 白闪法保留作绝对口径标定（不依赖本方案插桩）；本方案为**带内逐操作**口径，二者可交叉验证。

## 6. 实施清单（5 个改动点）

1. `protocol/Messages.kt`：InputEvent 可选尾块 + INPUT_ACK(0x12) 编解码 + 线格式单测（含「老端忽略尾块」「新端收未知类型」兼容 case）。
2. `source .../StreamService.kt` handleControl：arrUs 记录、注入后一次性首帧回调注册（ScreenCapture 暴露 `onFirstFrameAfter(arrUs, cb)`）、1500ms 超时兜底、t6a 打点。
3. `client .../input/TouchChannel.kt`：opId 生成（DOWN/UP 才携带）+ t_send 记录。
4. `client .../ConnectActivity.kt`：INPUT_ACK 分支计时计算 + ring buffer + StatsView 展示；解码渲染处 frameSeq 命中打 t8。
5. `scripts/measure_op_latency.py` + 本文档验收表落 `docs/realdevice-retest-checklist.md`。
