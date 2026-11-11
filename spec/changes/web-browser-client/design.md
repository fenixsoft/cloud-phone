# Design: web-browser-client（浏览器 Web 客户端，路线 A）

## Context

- 用户场景：机主与少数人在**浏览器**（PC/平板/手机）直接操作家中云手机上的**某个特定 APP**（延迟敏感、画面持续运动的游戏类）。
- 技术路线已在 brainstorm 中对比并选定 **路线 A：WebSocket + WebCodecs**；完整 WebRTC（路线 B，云原神同款）作为弱网实测不达标时的演进路径，本设计不实现但保留演进空间。
- 现状地基：M0–M3 管线完备，线协议真相源 `docs/protocol.md`（大端记录流 `[u8 recordType][u32 len][payload]`，控制 `0x01` / 视频 `0x02`，21B 视频帧头，17 条控制消息 0x01–0x11）。已有 Android 客户端（`client/`）与 Windows 客户端（`client-windows/`）两个行为参照实现。
- **本变更对源端 Android 工程（`source/`、`protocol/`）零改动**——这是刻意约束：延迟专项（`spec/changes/client-ux-and-input-latency`）正在另一会话进行中，本变更不得与其产生源码冲突；浏览器侧证据（ptsUs 管线延迟、PING/PONG 时钟偏移）反而可以喂给延迟专项。
- 协议已内建浏览器所需的一切：HELLO 配对 token 认证、SINGLE_APP 单应用虚拟屏模式（HELLO/START_SESSION 携带包名）、APP_LIST 枚举、INPUT_EVENT（多点触摸 + BACK，坐标归一化 u16）、AUDIO_CHUNK（AAC 48kHz stereo 96kbps 20ms 帧，线上含 ADTS 头）、剪贴板三条、TEXT_INPUT（含中文/emoji，ACK 制）、STATS/IDR_REQUEST/PING/PONG、ATTACH 断线重挂（≤30s，IDR ≤200ms，出画 ≤1s）。

## Goals / Non-Goals

### Goals

1. 新增 `client-web/`（浏览器 TS 客户端 + 宿主机网关），在浏览器中完成：token 连接 → 应用列表 → 单应用画面 → 触控操作 + 音频播放 + 剪贴板/文本注入 + 统计 HUD。
2. 网关 = **协议无关字节中继**（WS↔TCP）+ 静态页托管，单端口经 frp 暴露；源端零改动。
3. 浏览器端以 TS 复刻线协议（`docs/protocol.md` 逐字节一致），配 golden-vector 单元测试。
4. 浏览器侧延迟可观测：管线延迟（帧到达−ptsUs 经时钟偏移校正）、RTT、解码队列、丢帧计数，HUD 展示并可落 JSON。
5. 验收：协议单测 + 网关中继集成测试 + Playwright 冒烟（模拟器，FULLSCREEN 口径）+ 真机单应用人工复测。

### Non-Goals（v1 明确不做）

- **WebRTC/UDP 传输**（路线 B 演进项）。
- **加密**：浏览器 HELLO 不带加密协商块 → 走既有 `none` 明文路径（协议 §7.6 兼容矩阵明确支持；与当前 frp 明文现状同级）。TS 版 §7（WebCrypto HKDF+AES-GCM）为 fast-follow。
- **会话中动态码率**：协议无对应 msgType（档位在 HELLO/START_SESSION 定死，会话中改码率需源端支持，超出"源端零改动"约束）。v1 仅做**会话启动档位选择**。
- 浏览器端麦克风/音频上行。
- 网关多源路由（v1 单一 upstream）。
- PC 虚拟摇杆/按键映射子系统（v1 键鼠仅"鼠标点击=单点触摸"+ Esc=BACK；触屏设备为第一公民）。
- Firefox / iOS Safari 不做第一公民：WebCodecs 已 feature-detect，视频可用的浏览器都尽力支持；音频解码不支持的浏览器明确降级为静音+原因提示（复用 CLIPBOARD_TEXT(kind=1) 的"显式降级"模式——音频通道不可用即置灰并显示原因）。

## Decisions

### D1 架构：网关协议无关，协议实现在浏览器

```
[Android 源端]──现有协议·TCP──▶[宿主机网关]──WebSocket·二进制──▶[浏览器 TS 客户端]
     (adb forward 22333)      字节中继·无协议知识     protocol.ts: Framing/Messages
                                                    WebCodecs 解码 → canvas
                                                    AUDIO_CHUNK → WebAudio
                                                    pointer → INPUT_EVENT ↑
```

- 网关不理解任何消息语义：WS 二进制帧 ↔ upstream TCP 双向搬运（带背压：TCP 慢时暂停读 WS，反之同理）。认证、会话、错误全部由源端协议层裁决（token 错 → 源端 REJECT_BAD_TOKEN 并关闭 → 网关随机关 WS）。
- 理由：协议只写一遍（TS 版 `protocol.ts`，浏览器与未来工具共用）；网关永远不需要跟协议演进；Windows 客户端已验证"跨语言逐字节复刻 + golden 测试"可行（`docs/protocol.md` §6 即为此约定而写）。
- 解码器健壮性规则：TS 解码器对**未知尾部字节一律容忍跳过**（线格式先例：HELLO 有 protocol.md 未记载的可选 `takeover` 尾字节，Messages.kt:132 编码仅置位时出现、:550 解码容忍缺失）；golden 向量以真机实际字节为准，protocol.md 陈旧处在对接注记中勘误。

### D2 网关实现：Node.js 单文件 + `ws` 依赖，同端口托管静态页

- `client-web/gateway/server.js`（~200 行）：`ws`（npm，唯一依赖）做 WebSocket；静态文件服务内建（无框架）；单端口同时服务页面与 `/ws` 升级 → 浏览器同源连接，无 CORS、无第二隧道。
- 配置（env）：`GATEWAY_PORT`（默认 8090）、`UPSTREAM`（默认 `127.0.0.1:22333`，即 `scripts/bootstrap_adb.sh` 建立的 adb forward 口；真机 Wi-Fi 场景可配 LAN IP:port）。
- 生命周期：systemd 单元或 nohup，与现有 frpc 部署同级；frp 侧新增一条 proxy（如 remotePort 18788 → 本机网关口），沿用仓库既有隧道模式。
- 语言取舍：选 Node 与浏览器同为 TS/JS 生态（未来若网关需升级为协议感知——如路线 B 的 RTP 打包——语言栈不分裂）；Go 单二进制是备选，被"生态一致性"否决。

### D3 浏览器端工具链：Vite + 原生 TypeScript，无 UI 框架

- `client-web/app/`：vanilla TS + 少量 CSS。页面结构简单（连接页 → 应用网格 → 全屏 canvas + 悬浮工具条），不需要框架；构建产物为纯静态文件，由网关托管。
- 新增依赖仅 dev 侧：vite、typescript、vitest（协议单测）、playwright（冒烟）。运行时零依赖。

### D4 视频解码：WebCodecs VideoDecoder + canvas

- 帧数据为 MediaCodec 输出 **Annex B**（带起始码）。配置：`VideoDecoder({ codec: 'avc1.640029' 档位级别按 SPS 实测修正, avc: { format: 'annexb' }, optimizeForLatency: true, hardwareAcceleration: 'prefer-hardware' })`；连接时先 `isConfigSupported` 探测，失败给出明确错误页（含浏览器名与版本）。
- ptsUs（源端单调钟 µs）直接作 `VideoFrame.timestamp`；渲染走 canvas 2D `drawImage(videoFrame)`，渲染后 `close()`。
- 21B 帧头的 `sequence/fragIdx/fragCnt`：TCP 流模式整帧单记录（fragCnt=1），浏览器按 seq 单调性计丢帧；flags bit0 关键帧用于 IDR 统计。
- 时序：`optimizeForLatency` + 逐帧 decode-then-render，不加抖动缓冲（TCP 已保证有序完整；视频侧缓冲 = 延迟）。

### D5 音频：剥 ADTS → AudioDecoder → AudioWorklet 环形缓冲

- `AUDIO_CHUNK.payload` 含 ADTS 头：首帧解析 ADTS 取 profile/采样率/声道，构造 `AudioSpecificConfig`（2 字节）作 AudioDecoder description，之后剥除每帧 7B ADTS 头喂 `AudioDecoder({ codec: 'mp4a.40.2' })`。
- 播放：AudioWorklet 环形缓冲，**起播水位 120ms、不足即 skip 并计数**——逐字对齐协议 §3.13 的客户端口径（与 Android/Windows 客户端一致）。
- 源端静音抑制（3s 停发 + 1 帧/s keepalive）：ring buffer 天然兼容，keepalive 帧照常解码入队。
- 特性探测：`window.AudioDecoder` 不存在 → 静音 + 工具条显示"音频不可用（浏览器不支持）"，视频与触控不受影响。

### D6 输入与辅助通道

- 触摸：`pointerdown/move/up/cancel` → `INPUT_EVENT{action, pointerId, x, y}`。坐标：canvas 实际显示区域（含 letterbox 黑边裁剪）线性映射到会话分辨率，再归一化 ×65535 取 u16；越界钳制。pointerId → u8 活动指针表映射（浏览器 pointerId 可为任意 int，源端仅 1 字节；上限 10 指针）。**注意：源端 v1 忽略 pointerId、单点合成注入**（InputInjector.kt:31），多点触控仅为协议保留能力——Web UI 不承诺捏合/多指手势。
- BACK：工具条返回键 + 桌面键盘 Esc → `action=3`。
- 文本注入：工具条"文本"按钮唤起隐藏 `<input>`（移动端拉起系统键盘），提交走 `TEXT_INPUT(kind=0)`，按 ACK（kind=1, seq 回显）提示成败。
- 剪贴板：读取/写入两按钮走 `CLIPBOARD_GET/SET`，回执与错误经 `CLIPBOARD_TEXT(kind)` 处理，kind=1 置灰按钮并 toast 原因。
- 会话参数以**建会话 START_SESSION 响应的 HELLO_ACK 实测 w/h** 为坐标基准（协议 §3.3 的 /16 对齐偏差语义）；HELLO 自身的 ACK 参数全为占位 0（见 D8），**每次切换会话（新 START_SESSION）后必须重置坐标基准**。

### D7 时钟与统计 HUD

- `PING/PONG ≥1Hz`：PONG 线格式为 `(nonce, senderUs, echoUs)`——**senderUs 是浏览器发送时间戳的原样回显**（浏览器钟，StreamService.kt:287），echoUs 是源端应答时刻（源端钟）。没有第二个源端时间戳，**不是四时戳 NTP**。估计口径：`RTT = t_recv − t_send`；`offset ≈ echoUs − (t_send + t_recv)/2`（假设源端应答延迟 ≪ RTT）。
- **入站控制必须应答**：源端在会话期间 1Hz 主动向客户端发 PING（StreamService.kt:705）并 1Hz 推送 STATS（:675-716）。浏览器必须解码入站 PING 并回 `Pong(nonce, senderUs, nowUs())`——源端以 Pong 样本维护 smoothedRttMs 作音频积压 watchdog（>800ms 清排队 AUDIO_CHUNK）。protocol.md 方向表"PING 仅 C→S"已陈旧，对接注记勘误；浏览器→源端的 STATS 被源端忽略（:289），HUD 直接消费源端推送的 STATS。
- 每帧管线延迟 = `now() − (ptsUs + offset)`；HUD 展示：fps、码率（源端 STATS + 滑动窗口实测）、RTT、管线延迟 P50/P95、解码队列 `decodeQueueSize`、seq 缺口丢帧数。数据可落 `docs/web-latency-samples.json`（口径注明浏览器端），供延迟专项比对。

### D8 会话 UX 流（对齐既有客户端行为，经源码核实）

1. 连接页：token 输入（URL `?token=` 自动填并存 localStorage，可清除）+ 档位选择（D9）。
2. 连接：WS → TCP → `HELLO{token, p2pOffer=""}`（无加密块 = none 路径）。**HELLO 不建会话**：其 ACK 携 sessionId=0 与全 0 占位参数（StreamService.kt:551-561），并附 hungSessions 挂机会话清单（重连页可据此展示"恢复上次观看"）。档位放 HELLO 无效。
3. 建会话：`START_SESSION{sessionId=0, mode=FULLSCREEN, w/h/fps/bitrate=档位}` → **响应同为 HELLO_ACK(0x02)**，携真实 sessionId/w/h/fps/bitrateKbps/encoder/displayId（StreamService.kt:563-659；Windows SessionController.kt:287-408 同序）→ 视频开始。APP_LIST 需 ACTIVE 会话，故在此之后。
4. 应用网格：`APP_LIST_REQUEST` → `APP_LIST` 渲染；点选应用 → `START_SESSION{mode=SINGLE_APP, packageName}` 切入单应用。**每次 START_SESSION 源端自动挂起旧会话并产生新 sessionId**（StreamService.kt:566）——浏览器必须整体换用新 sessionId（后续 INPUT_EVENT/STOP 全部带新 id）并按新 HELLO_ACK 参数重置坐标基准（单应用虚拟屏分辨率可与整屏不同；旧 id 的事件被静默丢弃）。
5. 断线重连：WS 断开自动重连（指数退避，上限 30s 窗口）→ HELLO → 用记忆的 sessionId（或 HELLO_ACK.hungSessions）走 **`START_SESSION{sessionId=N, mode=ATTACH}`**（附着即 ATTACH，协议 §3.3；Windows SessionController.kt:603-621 同法）→ HELLO_ACK{sessionId=N, 真实参数} 恢复。**附着自动触发 IDR**（SessionManager.kt:137/145），无需也不应再发 IDR_REQUEST——该消息硬连线到整屏共享采集（StreamService.kt:279），对 SINGLE_APP 无效。超时或被源端显式拒绝（REJECT_BUSY——传输层占用闸门或 StreamService.kt:628 恢复失败 / REJECT_UNSUPPORTED_MODE）则停止自动重试、回连接页带原因展示，不盲重试（用户可手动重试）。
6. 显式"结束"按钮 → `STOP_SESSION`（销毁虚拟屏与 App；**无响应消息**，fire-and-forget，发后即可关页）；直接关页 = 挂机（语义与既有客户端一致）。

### D9 档位：镜像 `client-ux-and-input-latency` D2 的既有口径

- 分辨率 720p/1080p × 帧率 30/60 × 码率 4/8/16/20Mbps，浏览器默认 **720p60/8Mbps**（不动"跟随本机"项——浏览器无本地分辨率对应语义，桌面窗口尺寸仅作显示缩放）。
- 客户端侧仅做区间校验（width 320–2560、height 240–2560、fps 15–60、bitrate 1000–20000kbps），对齐 /16 交给源端（协议 §3.3 目标档位语义）。

### D10 测试与验收

- **协议单测（vitest）**：Framing 读写往返、分片重组、17 条消息逐字段 golden vector（向量从 Kotlin `FramingTest.kt`/`MessagesTest.kt` 用例与真机实际字节导出，双向断言逐字节一致；解码器按 D1 规则容忍未知尾部字节）。
- **网关集成测试**：脚本化假源端（Node TCP server 回放录制的字节流）+ WS 客户端解析断言；含背压用例（upstream 停读不丢字节）。
- **Playwright 冒烟（模拟器，FULLSCREEN 口径）**：起 bootstrap 环境 → 打开页面 → token 连接 → 断言首帧渲染（canvas 像素非空）→ 注入点击 → 断言浏览器侧收到 STATS 且 fps>0、logcat 出现源端真实标签 `cloudphone-touch`（INPUT 注入 t3 打点，InputInjector.kt:35）与 `cloudphone-source`。（`cloudphone-stats` 是 Android 客户端侧标签，源端不存在，勿用。）模拟器 **SINGLE_APP 不可用**（device-notes §2.1，镜像级限制），单应用路径在模拟器只验收"失败带原因回连接页"回退，直达口径由真机人工复测（与 client-ux D6 验收口径一致）。
- **真机人工验收单**：单应用进入/切换/挂机 ATTACH、音频、剪贴板、文本注入、PC 键鼠与平板触屏各过一遍。

## Risks / Trade-offs

- **TCP over frp 弱网抖动**：无拥塞反馈层，码率>带宽时以延迟膨胀形式表现。接受（家用/自用网络稳定；路线 B 为既定演进路径；延迟专项在管线侧的优化同样惠及本客户端）。
- **WebCodecs annexb 各浏览器实现差异**：以 `isConfigSupported` 探测 + 明确错误页兜底；Chrome/Edge 为第一验证目标。
- **Safari 系 AAC AudioDecoder 缺口**：显式静音降级（非静默失败）。
- **frp 明文**：与现状同级；token 即唯一凭据，勿在公共网络使用；TS 加密为 fast-follow。
- **两会话并行工程冲突**：本变更不触碰 `source/`、`protocol/`、`client/`；`docs/protocol.md` 仅追加"浏览器客户端对接注记"小节（纯文档）。
- **观看端占用闸门（传输层单通道语义，评审实测修正）**：REJECT_BUSY 有两处来源——①**传输层占用闸门**：`sourceBusyText` 恒非空（TransportBackend.kt:146 默认"源端正被其他设备观看"，全仓无清空点），存在现役通道时新 HELLO（无 takeover）一律被拒（LanDirectBackend.kt:130-135 与 rejectPendingChannel :198-206；FrpBackend.kt:130 同构）→ **同一时刻仅一个观看端通道在役，第二个浏览器（同机多标签或两人）的 HELLO 会收到 REJECT_BUSY 与占用原因**；现役通道断开即清闸（LanDirectBackend.kt:163），挂机会话可被下一个观看端 ATTACH。②**会话层 ATTACH 恢复失败**：StreamService.kt:628 以 REJECT_BUSY 回 `manager.lastError`（正落在浏览器断线重连主路径）。HELLO 的 `takeover` 尾字节在传输层抢占的是**现役通道**（LanDirectBackend.kt:137-145 退役 incumbent、其客户端收 DISCONNECTED），会话层完全不读该字段（StreamService 无 takeover 引用）——被踢端会话转挂机、仍可恢复。浏览器 v1 永不发送 takeover、不做抢占/互踢 UI；对 REJECT_BUSY 一律显式呈现原因（见 D8.5）；"整屏+单应用并存"是同一观看端内的既定特性，`handleStartSession`（StreamService.kt:563-659）确无会话层并发上限。

## Migration Plan

1. `client-web/gateway/` + `client-web/app/` 新目录，独立 npm 工程，不进任何 Gradle 构建。
2. `docs/protocol.md` 追加对接注记（WS 承载 = 既有 frp/TCP 流记录模式逐字节同构；HELLO 不带加密块 = none 路径），并勘误三处与源码不符的陈旧描述：§4 附着时序（ATTACH = `START_SESSION{sessionId, mode=ATTACH}`，非 HELLO 字段）、控制方向表（源端→客户端存在 1Hz PING 与 STATS 推送）、§3.1/§5 补记 HELLO 可选 takeover 尾字节（传输层抢占现役通道，会话层不读）与 REJECT_BUSY 双来源语义（传输层观看端占用闸门 + 会话层 ATTACH 恢复失败 StreamService.kt:628）。
3. 部署：宿主机起网关 + frpc 增加 proxy 条目；页面以 `http://<frp host>:<port>/?token=<配对token>` 书签交付。
4. 后续演进开关：路线 B（WebRTC）时网关原地升级为信令+RTP 端点，浏览器协议层/输入层代码全部保留复用。

## Open Questions

1. 实施流程走哪条：本仓库 Fenix 流（fenix-new 生成 proposal/tasks 后进实现循环）还是 superpowers writing-plans？（设计文档本身已按仓库惯例落在 `spec/changes/web-browser-client/design.md`。）
2. 网关语言 Node.js（理由见 D2）是否认可，还是倾向 Go 单二进制？
3. v1 明文（跳过 §7 加密）是否接受？（与当前 frp 后端明文同级。）
4. 网关部署机 = 现模拟器宿主机（frpc 所在机）？还是另有部署目标？
