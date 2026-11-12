# 验证报告：web-browser-client

> ⚠️ 本报告由框架于 2026-11-12T12:48:06Z 自动合成（裁决 Agent 未写报告，已由框架合成），内容为结构化验证产物（report.json / .fenix-run-result.json）的忠实渲染。
> 报告时效标记（判定本报告是否属上一轮）：合成源轮次指纹：report.json@2026-11-12T12:32:17.299652+00:00+run-result@mtime1794487668526690661

## 结论：❌ 未通过（Bug Found）

**状态：bug-found — 28/34 场景通过，1 失败，0 错误（1 个真实产品 Bug）**

34 场景：28 pass、5 blocked-env（依赖链级联阻断，跨轮已裁决 env 原样沿用）、1 fail（web-hang-attach-resume）。唯一产品缺陷：挂机恢复入口不渲染（客户端以 START_SESSION 参数应答覆写握手 ACK 的 hungSessions，违背 design.md:90 契约），活体复现确定性成立，判 product-bug/major 派 implementer 修复；同轮完成 1 处场景文件原地修复与 11 条静态情报误报核实

| 指标 | 数量 |
| --- | --- |
| 通过 | 28 |
| 失败 | 1 |
| 错误 | 0 |
| 产品 Bug | 1 |
| 本链增量 Token（输入） | 3.63M |
| 本链增量 Token（输出） | 1.03M |


## 真实产品 Bug

### BUG-01（severity=major）· step_error/click

- 关联场景：web-hang-attach-resume
- 描述：挂机恢复入口不渲染：整页重载（挂机）重连后，应用网格不出现「恢复会话 #N」入口，点击恢复步骤 5s 超时，「关页挂机可恢复」核心流程断裂且无替代路径
- 根因：SessionClient.ts:400 对每条 accepted HELLO_ACK 无差别覆写 lastHungSessions；connect()（SessionClient.ts:191）时序为 HELLO→握手 ACK（design.md:90 契约：其 ACK 附 hungSessions 挂机清单，恢复入口据此展示）→START_SESSION→参数应答 ACK（design.md:93 语义仅真实会话参数，源端省略挂机清单=[])，末次覆写把握手 ACK 的非空清单清空；main.ts:429 renderApps(apps, session.lastHungSessions) 渲染的是被清空后的值→恢复入口恒缺
- 证据（场景 web-hang-attach-resume 结构化证据，引用）：
  - failed_assertions: [{"type": "step_error", "selector": "#hung-sessions .hung-item", "expected": "重连后应用网格呈现「恢复会话 #N」恢复入口并可点击", "actual": "Locator.wait_for: Timeout 5000ms exceeded.\nCall log:\n  - waiting for locator(\"#hung-sessions .hung-item\").first to be visible"}]
  - screenshots: []
  - first_failed_step: 步骤12（批次10）：重连点击恢复入口超时。Playwright 全流程复现 hungCount=0 且无控制台错误；原始协议探针证实握手 HELLO_ACK 携 hungSessions=6 条、假源/网关链路正常——数据在客户端被 START_SESSION 参数应答覆写清空


## 场景结果

| 场景 | 描述 | 状态 | 失败断言 | 分类 |
| --- | --- | --- | --- | --- |
| web-deliverables-exist | 交付物齐备性门：tasks.md Deliverables YAML 全部 21 个路径（gateway 3：package.json/server.js/test/relay.test.js；app 工程 3：package.json/tsconfig.json/index.html；app src 11：protocol/Framing.ts、protocol/Messages.ts、net/SessionClient.ts、media/VideoPipeline.ts、media/AudioPipeline.ts、media/audio-worklet.js、input/InputBridge.ts、ui/Hud.ts、main.ts、tests/protocol.golden.test.ts、e2e/smoke.spec.ts；docs 3：protocol.md 修改、web-client.md、web-latency-samples.json）在验证树内逐个存在，任一缺失即非零退出并打印 MISSING 路径（出处：spec/changes/web-browser-client/tasks.md Deliverables YAML 3-43 行） | pass | 0/2 | - |
| webapp-toolchain-ready | 浏览器端工具链就绪：client-web/app 与 client-web/gateway 均可 npm install（registry 可达，node_modules 缺失时安装、已存在则跳过保证幂等），vitest 可运行（npx vitest --version）、gateway 唯一运行时依赖 ws 可 require、app dev 依赖 vite/typescript/playwright 可解析、node>=18 存在（出处：design.md D2 网关 Node+ws 单依赖、D3 工具链 vite+TS+vitest+playwright 运行时零依赖；tasks.md 1.1） | pass | 0/8 | - |
| protocol-framing-surface | Framing.ts 大端记录流落地：读写 [u8 recordType][u32 len][payload]（与 docs/protocol.md §2 一致）、21B 视频帧头解析（sequence/ptsUs/flags/fragIdx/fragCnt/totalLen）、TCP 整帧口径 fragCnt≠1 显式报错、seq 单调性缺口计丢帧（u32 回绕按无符号比较）（出处：tasks.md 1.2；design.md D4 21B 帧头与 seq 单调丢帧计数；docs/protocol.md §6.6 sequence 回绕口径） | pass | 0/2 | - |
| protocol-messages-surface | Messages.ts 17 条控制消息（0x01 HELLO–0x11 TEXT_INPUT）编解码分支齐全；解码器对未知尾部字节一律容忍跳过（HELLO 可选 takeover 尾字节先例：Messages.kt 编码仅置位时出现、解码按 remaining 守卫容忍缺失）；字符串 u16 长度前缀 + UTF-8、定宽大端原语（出处：tasks.md 1.3；design.md D1 解码器健壮性规则；docs/protocol.md §3 控制消息一览与 §6 M1 兼容约定） | pass | 0/2 | - |
| session-client-orchestration-surface | SessionClient.ts 会话编排控制面：HELLO{token, p2pOffer:""} 且不带加密协商块（none 路径）；HELLO 不建会话——ACK 的 sessionId/分辨率占位 0 不被采用；START_SESSION 响应 HELLO_ACK(0x02) 真实 sessionId/w/h/fps/bitrateKbps/encoder/displayId 被采用；sessionId 换新即重置坐标映射基准；APP_LIST_REQUEST/APP_LIST 渲染数据源；STOP_SESSION fire-and-forget；REJECT_BAD_TOKEN 与 REJECT_BUSY（观看端占用/ATTACH 恢复失败双来源）显式失败带原因不静默重试；应答源端 1Hz 入站 PING（Pong nonce/senderUs 原样回显/echoUs=nowUs）；offset ≈ echoUs − (t_send+t_recv)/2 与 RTT=t_recv−t_send 估计；断线指数退避重连上限 30s 窗口、恢复走 START_SESSION{sessionId, mode=ATTACH} 且不发送 IDR_REQUEST(0x05)、超窗或显式拒绝停止重试回连接页带原因（出处：specs/browser-streaming-client/spec.md「连接认证与会话编排」「断线自动重连（ATTACH）」「入站控制应答与时钟统计」；design.md D7/D8；tasks.md 5.1/5.2） | pass | 0/3 | - |
| video-audio-pipeline-surface | 视频/音频管线落地：VideoPipeline 连接时 isConfigSupported 探测（不支持 → 含浏览器名称与版本的明确错误页而非黑屏）、VideoDecoder Annex B（avc format annexb）+ optimizeForLatency + prefer-hardware、ptsUs 直作 VideoFrame.timestamp、逐帧 canvas drawImage 渲染后 close()、decoded/dropped(seq 缺口)/decodeQueueSize/关键帧四类计数 + 诊断开关导出首 N 帧原始负载 dump；AudioPipeline 首帧 ADTS 头解析取 profile/采样率/声道构造 2B AudioSpecificConfig 作 description、之后逐帧剥 7B ADTS 喂 AudioDecoder('mp4a.40.2')、AudioWorklet 环形缓冲起播水位 120ms 不足即 skip 并计数、兼容静音抑制 keepalive 帧；无 window.AudioDecoder 显式降级：音频控件置灰 + 原因提示、视频触控不受影响（出处：tasks.md 3.1/3.2/4.1/4.2；design.md D4/D5；specs/browser-streaming-client/spec.md「视频解码与渲染」「音频播放与显式降级」） | pass | 0/2 | - |
| input-bridge-surface | InputBridge 输入映射落地：pointerdown/move/up/cancel → INPUT_EVENT；letterbox 黑边裁剪后线性映射到会话分辨率再归一化 ×65535 取 u16、越界钳制；pointerId → u8 活动指针表映射（上限 10）；工具条返回键与桌面键盘 Esc → action=3；隐藏输入框提交走 TEXT_INPUT(kind=0) 并按 ACK(kind=1, seq 回显) 提示成败；剪贴板 CLIPBOARD_GET/SET，CLIPBOARD_TEXT(kind=1) 错误回执置灰按钮并 toast 原因（显式降级非静默失败）；UI 不承诺多点/捏合手势（源端 v1 单点合成）（出处：tasks.md 6.1；design.md D6；specs/browser-streaming-client/spec.md「输入回传与辅助通道」） | pass | 0/2 | - |
| hud-main-ui-surface | 连接页与 HUD 落地：URL ?token= 自动填入并存 localStorage 且可清除；档位选择 720p/1080p × 30/60fps × 4/8/16/20Mbps、默认 720p60/8Mbps、本地区间校验 width 320–2560/height 240–2560/fps 15–60/bitrate 1000–20000kbps；页面流 连接页 → 应用网格 → 全屏 canvas + 悬浮工具条；HUD 展示 fps/码率/RTT/管线延迟 P50/P95/解码队列深度/seq 缺口丢帧数 + 落 docs/web-latency-samples.json 开关（口径注明浏览器端）（出处：tasks.md 6.2；design.md D9/D8.1/D7；specs/browser-streaming-client/spec.md「档位选择与校验」） | pass | 0/2 | - |
| docs-protocol-annotations | docs/protocol.md 浏览器对接注记与三处勘误落盘：§4 附着时序勘误（ATTACH = START_SESSION{sessionId, mode=ATTACH} 非 HELLO 字段；附着自动触发 IDR 无需 IDR_REQUEST）；控制方向表勘误（源端→客户端存在 1Hz PING 与 STATS 推送，StreamService.kt:705/:675-716；浏览器→源端 STATS 被源端忽略 :289）；§3.1 补记 HELLO 可选 takeover 尾字节（传输层抢占现役通道 LanDirectBackend.kt:137-145，会话层不读）；§5 REJECT_BUSY 双来源语义（传输层观看端占用闸门 + 会话层 ATTACH 恢复失败 StreamService.kt:628）与会话层无并发上限；WS 承载 = frp/TCP 流记录模式逐字节同构、HELLO 无加密块 = none 路径（出处：tasks.md 7.2；design.md Migration Plan 2 与 Risks 观看端占用闸门条目） | pass | 0/2 | - |
| docs-web-client-manual | docs/web-client.md 部署手册与真机人工验收清单：网关部署（env GATEWAY_PORT/UPSTREAM、systemd 单元或 nohup、单端口经 frp 暴露、frpc 新增 proxy remotePort→GATEWAY_PORT）、token 书签交付（http://<frp host>:<port>/?token=<配对token>）、v1 明文风险与浏览器兼容说明（WebCodecs feature-detect、Safari 音频降级）；真机单应用人工验收清单覆盖 单应用进入/切换/ATTACH 挂机恢复、音频、剪贴板、文本注入、PC 键鼠（鼠标点击=单点触摸、Esc=BACK）与平板触屏，每项含操作步骤与预期（出处：tasks.md 7.3；design.md D2 生命周期、D6、Non-Goals 与真机人工验收单） | pass | 0/2 | - |
| web-latency-samples-delivery | 延迟采样交付（tasks.md 7.4 行内注解替代口径）：docs/web-latency-samples.json 存在且为合法 JSON，含 LatencySamplesDoc schema 键（caliber/profile/browser/collectedAt/summary/samples）且 caliber 注明浏览器端口径；采样数组为空时 MUST 附 blocker 块（status=env-blocked + recover 恢复路径——HUD「采样开启→运行→下载采样」覆盖流程，docs/web-client.md §6 承诺），与上轮实现者偏差注解一致；采样数组非空时校验 summary 分位字段齐备（出处：tasks.md 7.4 注解；client-web/app/src/ui/Hud.ts exportSamplesDoc schema；docs/web-latency-samples.json 现状） | pass | 0/2 | - |
| gateway-http-entry-endpoint | 网关同端口 HTTP 静态托管契约：HTTP GET / 返回 200 与入口 HTML（Content-Type text/html）、响应体为非空 HTML 文档、延迟 <2s——页面与 /ws 升级同端口可用（出处：specs/web-gateway/spec.md「同端口静态托管与 WS 升级」Scenario「页面与通道同端口可用」前半；design.md D2 单端口同时服务页面与 /ws；http_request origin 由框架按当轮被测实例改写，此处 host:port 取 design.md D2 GATEWAY_PORT 缺省 8090 形态） | pass | 0/4 | - |
| web-connect-page-renders | 连接页首屏渲染（无通道依赖）：打开网关入口页 → token 输入框与档位选择器可见且默认档 720p60/8Mbps 处于选中态、连接按钮可见且可用（element_enabled）；未连接态下会话信息/视频画布/悬浮工具条不出现（element_not_exist）；控制台无错误（出处：design.md D8.1 连接页与 D9 默认 720p60/8Mbps；specs/browser-streaming-client/spec.md「档位选择与校验」默认档；navigate 目标 http://127.0.0.1:8090/ 出自 specs/web-gateway/spec.md「同端口静态托管与 WS 升级」/ 入口页 + design.md D2 GATEWAY_PORT 缺省 8090，origin 由框架按当轮实例改写） | pass | 0/13 | - |
| web-token-empty-input-rejected | 空 token 异常路径：不输入任何 token 直接点击连接 → 页面呈现客户端校验提示（非空校验先于 WS 发送）而非白屏/崩溃，停留连接页，会话信息不出现；控制台无未捕获错误（出处：specs/browser-streaming-client/spec.md「连接认证与会话编排」错误显式呈现族；场景覆盖规则·异常路径空输入；navigate 目标出处同上） | pass | 0/8 | - |
| web-token-clear-clears-input | token 清除控件行为：以 /?token=e2e-probe-token 打开（URL 回填+localStorage 持久化自建夹具态）→ 无参重载 → 点击 #forget-token-btn → 输入框值清空、连接按钮仍可用（本场景为裁决拆分修复新增，承接 web-token-url-autofill-persist-clear 原清除尾段断言，原样迁移未弱化；出处：design.md D8.1 token 可清除；tasks.md 6.2；navigate 目标出处同上，origin 由框架按当轮实例改写） | pass | 0/6 | - |
| upstream-22333-free | 上游通路回收前置：幂等移除宿主上 tcp:22333 的 adb forward（常驻模拟器夹具可能持有），随后机械断言 127.0.0.1:22333 连接被拒——为「上游不可达」与「假源端接管」两类场景提供确定性的端口基线（出处：.fenix/verify.yaml serve 段 UPSTREAM 缺省 127.0.0.1:22333；scripts/bootstrap_adb.sh forward 口径） | pass | 0/2 | - |
| web-token-url-autofill-persist | token 经 URL 自动填入与 localStorage 持久化：以 /?token=e2e-probe-token 打开 → 输入框回填该值并写入 localStorage（URL 参数被 history.replaceState 清除）→ 无参重载 / → 输入框仍回填同值（持久化回填路径）（出处：client-web/app/src/main.ts:136-146,474；specs/browser-streaming-client/spec.md D8.1；judge 修复：回填校验与清除操作拆分为独立场景，断言窗口内不再被清除步骤覆盖） | pass | 0/4 | - |
| protocol-golden-tests-pass | 协议 golden 单测全绿（client-web/app 下 vitest run tests/protocol.golden.test.ts）：17 条消息逐字段 golden 向量双向断言逐字节一致（向量从 Kotlin FramingTest.kt/MessagesTest.kt 用例与真机实际字节导出）、HELLO takeover 尾字节出现/缺失两形态、未知尾部字节容忍用例、Framing 读写往返与分片重组（出处：tasks.md 1.4；design.md D10 协议单测条目；D1 golden 以真机实际字节为准） | pass | 0/2 | - |
| gateway-relay-tests-pass | 网关中继集成测试全绿（client-web/gateway/test/relay.test.js）：脚本化假源端（Node TCP server 回放录制字节流）下 上行/下行逐字节透传（含 0x02 视频记录与 0x01 控制记录）、REJECT_BAD_TOKEN 原样送达 WS 后随关（非 1000 正常码）、慢消费者 8MB 二进制不丢字节不乱序（背压高水位暂停读）、双 WS 并发各自独立 upstream 不串扰、env 缺省（8090/127.0.0.1:22333）与覆盖（出处：specs/web-gateway/spec.md「协议无关字节中继」「背压与不丢不乱」「连接生命周期」「环境变量配置」四 Requirement 全部 Scenario；tasks.md 2.3） | pass | 0/2 | - |
| gateway-env-config-live | 网关 env 配置活体验证：不带环境变量启动 server.js → 本机 8090 端口 HTTP GET / 返回 200（缺省 GATEWAY_PORT）；以 GATEWAY_PORT=<随机空闲端口> 再次启动 → 监听覆盖端口；UPSTREAM 覆盖值可配置（连接语义由 gateway-relay-tests-pass 覆盖）；两实例均干净退出（出处：specs/web-gateway/spec.md「环境变量配置」缺省与覆盖 Scenario：首次监听 8090、二次监听覆盖值；design.md D2 env 配置） | pass | 0/4 | - |
| web-upstream-unreachable-explicit-fail | 上游不可达显式失败：网关在而源端通路不在（22333 已回收）时点击连接 → WS 建立但 upstream 拒连致 WS 1011 关闭（server.js:219 WS_CLOSE_UPSTREAM_GONE）→ 页面显式呈现失败原因（会话尚未建立，无法自动重连）并停留连接页，不黑屏不静默重试（出处：specs/browser-streaming-client/spec.md「连接认证与会话编排」显式失败要求；client-web/app/src/net/SessionClient.ts:528-536） | pass | 0/7 | - |
| fake-source-up | 协议假源端接管 22333（Node 脚本化假源端，design.md D10 网关集成测试同款形态）：实现 HELLO 认证（cloudphone-m0）/占用闸门/START_SESSION 三模式（FULLSCREEN/SINGLE_APP 换新 sessionId、ATTACH 校验挂机表）/APP_LIST/1Hz STATS/应答 PONG/TEXT_INPUT ACK/CLIPBOARD_GET 降级通告/STOP 销毁/断开挂机，后台常驻并落健康文件；随后经本轮 serving 网关（FENIX_VERIFY_PORT）以 ws 客户端完成 HELLO→HELLO_ACK accepted→PING→PONG 活体探针——同时活体验证网关 WS↔TCP 双向字节中继（出处：specs/web-gateway/spec.md「协议无关字节中继」；design.md D10；docs/protocol.md §3 线格式） | pass | 0/4 | - |
| web-wrong-token-explicit-fail | 错误 token 显式失败：输入确定错误的 token（definitely-wrong-token-e2e）点击连接 → 页面呈现认证失败与原因（源端 REJECT_BAD_TOKEN reason「token mismatch」，StreamService.kt:499 经 HELLO_ACK 原样透传），不进入视频画面；等待 3s 后错误横幅仍在、仍停留连接页——不静默重试（出处：specs/browser-streaming-client/spec.md「连接认证与会话编排」错误 token 显式失败 Scenario；design.md D8.5 token 错显式失败不静默重试；navigate 目标出处同上） | pass | 0/8 | - |
| web-busy-holder-up | 观看端占用闸门夹具：以独立原始 TCP 连接完成 HELLO（cloudphone-m0）并保持不断开——成为现役观看端通道；机械验证此时第二条经网关的 HELLO 被拒（REJECT_BUSY=2，占用原因「源端正被其他设备观看」），闸门夹具就绪（出处：specs/web-gateway/spec.md/design.md D 风险段「观看端占用闸门」；LanDirectBackend.kt:130-135 语义的假源端复刻） | pass | 0/4 | - |
| web-busy-rejection-display | 浏览器占用拒绝显式呈现：传输层闸门被占位连接持有期间（前置 wire-e2e-occupied-busy 留下的现役通道），浏览器输入正确 token 点击连接 → 页面呈现 REJECT_BUSY 与占用原因（含「源端正被其他设备观看」源端缺省文案，TransportBackend.kt:146 经 HELLO_ACK.reason 原样透传），不进入视频画面（会话信息/画布不出现）；等待 3s 后仍停留连接页且错误横幅仍在——不静默重试不盲重连（出处：specs/browser-streaming-client/spec.md「连接认证与会话编排」占用拒绝显式呈现 Scenario；design.md D8.5 对 REJECT_BUSY 一律显式呈现原因；navigate 目标 http://127.0.0.1:8090/ 出自 specs/web-gateway/spec.md「同端口静态托管与 WS 升级」/ 入口页与 design.md D2 GATEWAY_PORT 缺省 8090，origin 由框架按当轮实例改写） | pass | 0/8 | - |
| busy-holder-down | 占用闸门清理回归：终止占用夹具连接 → 现役通道断开即清闸（LanDirectBackend.kt:163 语义）→ 机械复测新 HELLO 重新被接受（accepted 且 sessionId=0 占位）——闸门生命周期与「挂机会话可被下一个观看端接入」口径一致（出处：specs/browser-streaming-client/spec.md；design.md 风险段①） | pass | 0/4 | - |
| web-fullsession-apps-hud | 正确 token 建会话正向主链（假源端活体）：连接（默认档 720p60/8Mbps）→ HELLO 占位 ACK → START_SESSION FULLSCREEN 换新 sessionId → 应用网格渲染（APP_LIST 2 条目）→ 点选应用切入会话视图 → canvas 尺寸=HELLO_ACK 实测参数（720×1280）、HUD 呈现源端 STATS（fps=58.5/8.00 Mbps）与自发 PING/PONG 实测 RTT（入站 PING 应答与 STATS 消费的活体证据）→ 结束回连接页（出处：specs/browser-streaming-client/spec.md「正确 token 建会话出画」「入站控制应答与时钟统计」「HUD 呈现延迟数据」；main.ts:268-280,300-315） | pass | 0/10 | - |
| web-profile-custom-tier | 自定义档位下发端到端：连接页选择 1080p/30fps/16Mbps → 连接 → 点选应用进入会话视图 → canvas 尺寸切换为 HELLO_ACK 返回的实测档位 1080×2400（START_SESSION 携所选参数、响应参数被采用为坐标基准）→ 结束回连接页（出处：specs/browser-streaming-client/spec.md「自定义档位下发」；main.ts:95-110,244-249） | pass | 0/6 | - |
| web-hang-attach-resume | 挂机→恢复入口→ATTACH 续看：建会话（会话视图确认）→ 整页重载（WS 断开且未发 STOP=挂机）→ 重新连接 → 应用网格呈现「恢复会话 #N」入口（HELLO_ACK.hungSessions 非空契约）→ 点击恢复 → START_SESSION{sessionId=N, mode=ATTACH} 回到会话视图且 canvas 参数就位 → 结束回连接页（出处：specs/browser-streaming-client/spec.md「断线自动重连（ATTACH）」「关页挂机可恢复」；main.ts:213-221；SessionClient.ts:609） | failed（已修正） | - | product-bug |
| web-aux-input-toolbar | 工具条输入与辅助通道反馈：建会话入会话视图 → BACK 按钮（action=3 注入后会话保持）→ 「文本」展开隐藏输入框 → 输入含中英文/符号文本并再次点击发送 → toast 呈现假源端 ACK「文字注入成功（seq=1）」（TEXT_INPUT kind=0→ACK 回执）→ 音频按钮静音切换（data-muted 翻转+文案变化）→ 读剪贴板触发假源端降级通告（CLIPBOARD_TEXT kind=1）→ 剪贴板两按钮置灰 + toast「辅助通道降级」→ 结束回连接页（出处：specs/browser-streaming-client/spec.md「输入回传与辅助通道」；InputBridge.ts:158-218,291-299；main.ts:448-465） | blocked-env | - | env |
| web-end-destroys-session | 显式结束销毁会话（回归）：建会话 → 点击「结束」（STOP_SESSION fire-and-forget）→ 回连接页无残留横幅 → 再次连接 → 应用网格无「恢复会话」条目（STOP 销毁语义：该会话不再出现于 hungSessions）→ 断开按钮回连接页（出处：specs/browser-streaming-client/spec.md「显式结束销毁会话」；main.ts:454-457,467-470；假源端 STOP→销毁不挂机） | blocked-env | - | env |
| fake-source-down | 假源端清理：终止假源端进程并移除健康文件/pidfile，机械断言 22333 重新连接被拒——上游通路回到中性态，不污染后续模拟器链（出处：场景链自持生命周期约定；.fenix/verify.yaml 源端通路由场景执行期编排的边界） | blocked-env | - | env |
| emulator-env-ready | 模拟器环境就绪前置（环境依赖——无 fallback：源端环境三层依赖阻塞已按三振止损冻结，见 tasks.md 7.1/7.4 注解与 docs/web-latency-samples.json blocker）：双模拟器健康检查（emulator-5554 源端/emulator-5556 客户端常驻夹具）→ 禁用观看端 App 防通道抢占 → bootstrap_adb.sh all（构建安装 source/loadgen/client、adb root、TCP forward、前台服务、MediaProjection 授权）→ 机械断言包已装 + 线级 token 探针 HELLO 被接受 | blocked-env | - | env |
| playwright-smoke-e2e | 交付件 e2e/smoke.spec.ts 运行验收（design D10 冒烟链原样执行）：client-web/app 下 npx playwright test e2e/smoke.spec.ts 全绿——smoke 经 FENIX_VERIFY_BASE_URL 打开当轮网关页 → token 连接 → 断言首帧渲染（canvas 像素非空）→ 注入点击 → 断言浏览器侧收到 STATS 且 fps>0、logcat 出现源端真实标签 cloudphone-touch（INPUT 注入 t3 打点，InputInjector.kt:35）与 cloudphone-source（cloudphone-stats 是 Android 客户端侧标签，源端不存在勿用）；收尾复 enable 5556 安卓客户端应用（bootstrap 场景禁用了它）。环境依赖（双模拟器+源端管线+Playwright chromium）；有 fallback: emulator-bootstrap-env 重授权兜底、playwright 浏览器缺失时 npx playwright install chromium 自举（出处：tasks.md 7.1；design.md D10 Playwright 冒烟（模拟器，FULLSCREEN 口径）全条目） | blocked-env | - | env |


## 断言级豁免清单

以下失败断言被归类为非产品缺陷（不记产品 Bug），逐条列出供人工复核豁免是否掩盖真实缺陷（场景级一句话归因不足以复核）。

| 场景 | 断言 | 归类 | 豁免理由 | 证据 |
| --- | --- | --- | --- | --- |
| web-aux-input-toolbar | dependency_blocked skipped_depends_on=web-hang-attach-resume | env | 环境因素（非产品缺陷） | 阻断根因在上游 web-hang-attach-resume 的失败（已另行归因 env），本场景零断言零步骤执行，同窗并行 batch=11 peers=0 observed_parallel=0 |
| web-end-destroys-session | dependency_blocked skipped_depends_on=web-aux-input-toolbar | env | 环境因素（非产品缺陷） | 阻断根因在上游 web-aux-input-toolbar 的失败（已另行归因 env），本场景零断言零步骤执行，同窗并行 batch=12 peers=0 observed_parallel=0 |
| fake-source-down | dependency_blocked skipped_depends_on=web-end-destroys-session | env | 环境因素（非产品缺陷） | 阻断根因在上游 web-end-destroys-session 的失败（已另行归因 env），本场景零断言零步骤执行，同窗并行 batch=13 peers=0 observed_parallel=0 |
| emulator-env-ready | dependency_blocked skipped_depends_on=fake-source-down | env | 环境因素（非产品缺陷） | 阻断根因在上游 fake-source-down 的失败（已另行归因 env），本场景零断言零步骤执行，同窗并行 batch=14 peers=0 observed_parallel=0 |
| playwright-smoke-e2e | dependency_blocked skipped_depends_on=emulator-env-ready | env | 环境因素（非产品缺陷） | 阻断根因在上游 emulator-env-ready 的失败（已另行归因 env），本场景零断言零步骤执行，同窗并行 batch=15 peers=0 observed_parallel=0 |

## 本轮场景集变更

场景集与持久化集一致，无变更。

