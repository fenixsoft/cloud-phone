# 浏览器串流观看端

<!-- knowledge: capability=browser-streaming-client rev=1
     sources=spec/changes/web-browser-client -->

云手机的浏览器观看端形态（vanilla TypeScript 单页应用，零安装）：在 PC/平板/手机浏览器里完成 token 连接、单应用画面观看与触控/键鼠操作、音频播放、剪贴板与文本注入、延迟观测，经 Web 网关接入源端协议。

## 规则

- 连接与会话编排：以 `HELLO{token, p2pOffer:""}`（无加密协商块 = none 明文路径）发起连接，永不发送 takeover 尾字节。HELLO 自身不建会话：握手 ACK 的 sessionId/分辨率/档位均为占位 0，并附 hungSessions 挂机会话清单（重连恢复入口的数据源）；真实会话参数以 START_SESSION 响应的 HELLO_ACK(0x02) 为准（sessionId/w/h/fps/bitrateKbps/encoder/displayId）。hungSessions 只从握手 ACK 采纳，START_SESSION 应答（源端省略挂机清单）不得覆写已采纳清单。
- 显式失败不静默重试：REJECT_BAD_TOKEN（token 错）、REJECT_BUSY（双来源：传输层观看端占用闸门——同一时刻仅一个观看端通道在役，第二个浏览器的 HELLO 被拒，现役通道断开即清闸；会话层 ATTACH 恢复失败）、REJECT_UNSUPPORTED_MODE、上游不可达（WS 1011）均呈现明确原因并停留连接页，由用户手动重试；不盲重试、不做抢占/互踢 UI。
- 应用列表与单应用切换：存在 ACTIVE 会话时经 APP_LIST_REQUEST 拉取应用网格；点选应用走 `START_SESSION{mode=SINGLE_APP, packageName}`。每次 START_SESSION 源端自动挂起旧会话并产生新 sessionId，浏览器必须立即整体换用新 sessionId（后续 INPUT_EVENT/STOP 全部携带）并按新 HELLO_ACK 参数重置坐标映射基准；旧 id 的事件被源端静默丢弃。
- 断线自动重连：WS 意外断开后指数退避重连（0.5s 起步、倍增、单次封顶 5s、总窗口 30s），用记忆的 sessionId（或握手 ACK 的 hungSessions）走 `START_SESSION{sessionId=N, mode=ATTACH}` 恢复；附着由源端自动触发关键帧，不发送 IDR_REQUEST（该消息仅作用于整屏共享采集，对单应用无效）。恢复后 ≤1s 出画；超窗或 ATTACH 被显式拒绝则停止自动重试、回连接页带原因。自发 PING 超过 6s 无 Pong 判死连接走重连。
- 视频解码与渲染：WebCodecs VideoDecoder 解码 H.264 Annex B（`avc:{format:"annexb"}` + optimizeForLatency + prefer-hardware），连接时先 `isConfigSupported` 探测，不支持时呈现含浏览器名称的明确错误页而非黑屏；ptsUs（源端单调钟 µs）直作 VideoFrame.timestamp，逐帧 decode-then-render 上 canvas 2D，渲染后 close()；不加抖动缓冲（TCP 已保证有序完整）。
- 音频播放与显式降级：剥除 AUDIO_CHUNK 的 ADTS 头，首帧解析 profile/采样率/声道构造 2 字节 AudioSpecificConfig 作 AudioDecoder description（codec `mp4a.40.2`），经 AudioWorklet 环形缓冲播放：起播水位 120ms、不足即 skip 并计数、积压 >800ms 丢最旧；兼容源端静音抑制（3s 停发 + 1 帧/s keepalive 照常解码入队）。`window.AudioDecoder`/`AudioWorklet` 缺失时显式降级：音频控件置灰 + 原因提示，视频与触控不受影响。
- 输入回传与辅助通道：pointerdown/move/up/cancel 映射为 INPUT_EVENT——canvas 显示区域（含 letterbox 黑边裁剪）线性映射到会话分辨率后归一化 ×65535 取 u16，越界钳制；pointerId 映射 u8 活动指针表（上限 10）；工具条返回键与桌面 Esc → action=3；文本经隐藏输入框走 TEXT_INPUT(kind=0)，按 ACK(kind=1, seq 回显) 提示成败；剪贴板读写走 CLIPBOARD_GET/SET，收到 CLIPBOARD_TEXT(kind=1) 降级回执时置灰按钮并 toast 原因。源端 v1 忽略 pointerId 单点合成注入，UI 不承诺多点/捏合手势。
- 入站控制应答与时钟统计：必须应答源端会话期 1Hz 入站 PING（回 `Pong{nonce, senderUs 原样回显, echoUs=nowUs}`，维持源端 smoothedRttMs 音频积压 watchdog 的感知）；自发 1Hz PING 做时钟估计——`RTT = t_recv − t_send`、`offset ≈ echoUs − (t_send+t_recv)/2`，逐帧管线延迟 = `now − (ptsUs + offset)`；HUD 消费源端 ≥1Hz STATS 推送展示 fps、码率、RTT、管线延迟 P50/P95、解码队列深度、seq 缺口丢帧数，并可落 docs/web-latency-samples.json（口径注明浏览器端）。
- 档位选择与校验：720p/1080p × 30/60fps × 4/8/16/20Mbps，默认 720p60/8Mbps；客户端仅做区间校验（width 320–2560、height 240–2560、fps 15–60、bitrate 1000–20000kbps），/16 对齐交源端；档位经 START_SESSION 下发（放 HELLO 无效），无会话中动态码率。
- 连接页与 token：token 经 URL `?token=` 自动填入并存 localStorage 且可清除；页面流 = 连接页 → 应用网格（含挂机恢复入口）→ 全屏 canvas + 悬浮工具条。
- 结束语义：显式「结束」按钮发送 STOP_SESSION（无响应消息 fire-and-forget，销毁虚拟屏与 App，该会话不再出现在挂机清单）；直接关闭页面保持挂机语义（会话出现在下次连接握手 ACK 的 hungSessions，可一键 ATTACH 恢复）。
- 工程与协议对齐形态：`client-web/app` 独立 npm 工程（vanilla TS + Vite，构建产物纯静态由网关托管），运行时零依赖；协议以 TypeScript 逐字节复刻 docs/protocol.md（大端记录流、21B 视频帧头、17 条控制消息 0x01–0x11），golden 向量从 Kotlin 测试用例与真机实际字节导出、双向断言逐字节一致；解码器对未知尾部字节一律容忍跳过（HELLO 可选 takeover 尾字节先例）；TCP 整帧口径 fragCnt≠1 显式报错，seq 单调缺口计丢帧。
- 验收分工：模拟器镜像级不支持 SINGLE_APP（device-notes §2.1），模拟器自动冒烟仅覆盖 FULLSCREEN 口径与「单应用失败带原因回连接页」回退；单应用直达、ATTACH 恢复、音频、剪贴板、文本注入、PC 键鼠与平板触屏按 docs/web-client.md 真机人工验收清单复测。

## 代码锚点

- 主实现：client-web/app/src/main.ts（页面流与 UI 编排）、client-web/app/src/net/SessionClient.ts（会话编排与重连）、client-web/app/src/protocol/Framing.ts、client-web/app/src/protocol/Messages.ts（协议 TS 复刻）
- 单源符号：client-web/app/src/media/VideoPipeline.ts（WebCodecs 解码）、client-web/app/src/media/AudioPipeline.ts 与 client-web/app/src/media/audio-worklet.js（环形缓冲播放）、client-web/app/src/input/InputBridge.ts（输入映射）、client-web/app/src/ui/Hud.ts（延迟观测）
- 守卫测试：client-web/app/tests/protocol.golden.test.ts（17 条消息 golden 向量）、client-web/app/tests/sessionclient.test.ts（编排与 hungSessions 采纳）、client-web/app/e2e/smoke.spec.ts（模拟器 FULLSCREEN 冒烟）
- 手册与数据：docs/web-client.md（部署手册与真机验收清单）、docs/web-latency-samples.json（延迟采样口径）、docs/protocol.md §8（浏览器对接注记）

## 溯源

- 2026-11-12 web-browser-client（spec/changes/web-browser-client/）— 新建本页：确立连接认证与会话编排（HELLO 占位参数/START_SESSION 真实参数、hungSessions 仅握手采纳）、单应用切换换 sessionId 重置坐标基准、ATTACH 断线重连、WebCodecs/AudioWorklet 媒体管线与显式降级、输入映射、入站 PING 应答与 HUD、档位与结束语义、TS 协议复刻 golden 对齐规则。
