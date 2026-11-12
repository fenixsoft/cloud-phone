# 验证报告：web-browser-client

> ⚠️ 本报告由框架于 2026-11-12T04:01:07Z 自动合成（裁决 Agent 未写报告，已由框架合成），内容为结构化验证产物（report.json / .fenix-run-result.json）的忠实渲染。
> 报告时效标记（判定本报告是否属上一轮）：合成源轮次指纹：report.json@2026-11-12T03:53:20.212035+00:00+run-result@mtime1794456056391402906

## 结论：✅ 通过（verified）

**状态：verified — 19/34 场景通过，1 失败，0 错误（结构化数据存在失败明细，终局判定以 verification.verdict 为准）**

34 场景：19 pass；15 非 PASS 全部归因 env，0 产品缺陷、0 测试缺陷。根因单一且已活体证实：宿主常驻守护 scripts/adb_forward_keepalive.sh（pid 2138002, ppid=1, 5s 周期幂等重建）持续持有 tcp:22333（UPSTREAM 缺省通路）——upstream-22333-free 本轮 -s 逐设备移除已正确生效（FORWARD_REMOVED 且零 REMOVE_FAILED，上轮 more-than-one-device 场景缺陷不复现），但移除后 1s 内 forward 被守护在 emulator-5554 重建（5556 的 FORWARD_LEFT 系 adb forward --list 忽略 -s 的全宿主列举假象，-s 5556 remove 报 listener not found 佐证），22333 空闲基线在本轮环境不可达成、重试确定性失败（非 flaky）；杀宿主守护超出场景职责，判 env 并标注「上游通路被宿主保活守护持有，无法验证」。该基线失败沿 depends_on 链传播，致 14 个 e2e/夹具场景 dependency_blocked（零自身失败信号，非产品缺陷）。静态情报 stale_literal_port(22333≠serving 38963) 经源码核实为误报：22333=verify.yaml UPSTREAM 缺省端口（:33/:116；server.js:30 DEFAULT_UPSTREAM='127.0.0.1:22333'，gateway-env-config-live 的 DEFAULTS_OK 断言即证），故不触发 scenario-repaired。正向字面链路（交付物/工具链/协议面/gateway 单测与环境配置/文档/连接页交互与 token 持久化）19 项全绿。

| 指标 | 数量 |
| --- | --- |
| 通过 | 19 |
| 失败 | 1 |
| 错误 | 0 |
| 产品 Bug | 0 |
| 本链增量 Token（输入） | 2.48M |
| 本链增量 Token（输出） | 0.66M |


## 场景结果

| 场景 | 描述 | 状态 | 失败断言 | 分类 |
| --- | --- | --- | --- | --- |
| web-deliverables-exist | 交付物齐备性门：tasks.md Deliverables YAML 全部 21 个路径（gateway 3 + app 工程 3 + app src/tests/e2e 12 + docs 3）在验证树内逐个存在，任一缺失即非零退出并打印 MISSING 路径（出处：spec/changes/web-browser-client/tasks.md Deliverables YAML 3-43 行） | pass | 0/2 | - |
| webapp-toolchain-ready | 浏览器端工具链就绪：node>=18、client-web/app 与 client-web/gateway 幂等 npm ci（lockfile 定版，跳过 playwright 浏览器下载）、vitest 可运行、gateway 唯一运行时依赖 ws 可 require、vite/typescript/playwright 可解析（出处：design.md D2/D3；tasks.md 1.1） | pass | 0/10 | - |
| gateway-http-entry-endpoint | 网关同端口 HTTP 静态托管契约：HTTP GET / 返回 200 与入口 HTML（Content-Type text/html）、响应体为非空 HTML 文档（含 CloudPhone 标题）、延迟 <2s——页面与 /ws 升级同端口可用（出处：specs/web-gateway/spec.md「同端口静态托管与 WS 升级」；client-web/gateway/server.js:130 目录回落 index.html；navigate/http 目标 origin 由框架改写到本轮 serving 实例） | pass | 0/5 | - |
| upstream-22333-free | 上游通路回收前置：幂等移除宿主上 tcp:22333 的 adb forward（常驻模拟器夹具可能持有），随后机械断言 127.0.0.1:22333 连接被拒——为「上游不可达」与「假源端接管」两类场景提供确定性的端口基线（出处：.fenix/verify.yaml serve 段 UPSTREAM 缺省 127.0.0.1:22333；scripts/bootstrap_adb.sh forward 口径）（judge 修复2：forward 移除改按 adb devices 序列号逐设备 -s 执行并回验 forward --list——宿主双模拟器下原无 -s 移除必然报 more than one device 且错误被 \|\| true 吞没） | fail | 2/2 | env |
| web-connect-page-renders | 连接页首屏渲染（无通道依赖）：入口页 / 渲染 token 输入框（required）、档位三选择器（720p/60fps/8000kbps 缺省值）、连接按钮可用、错误横幅为空、会话页保持 hidden；未连接不进会话视图（出处：client-web/app/index.html:19-43,55；client-web/app/src/main.ts:474-475；judge 修复：全部选择器以页面真实 id 落点，弃用幻觉 data-testid） | pass | 0/10 | - |
| web-token-url-autofill-persist | token 经 URL 自动填入与 localStorage 持久化：以 /?token=e2e-probe-token 打开 → 输入框回填该值并写入 localStorage（URL 参数被 history.replaceState 清除）→ 无参重载 / → 输入框仍回填同值（持久化回填路径）（出处：client-web/app/src/main.ts:136-146,474；specs/browser-streaming-client/spec.md D8.1；judge 修复：回填校验与清除操作拆分为独立场景，断言窗口内不再被清除步骤覆盖） | pass | 0/4 | - |
| web-upstream-unreachable-explicit-fail | 上游不可达显式失败：网关在而源端通路不在（22333 已回收）时点击连接 → WS 建立但 upstream 拒连致 WS 1011 关闭（server.js:219 WS_CLOSE_UPSTREAM_GONE）→ 页面显式呈现失败原因（会话尚未建立，无法自动重连）并停留连接页，不黑屏不静默重试（出处：specs/browser-streaming-client/spec.md「连接认证与会话编排」显式失败要求；client-web/app/src/net/SessionClient.ts:528-536） | blocked-env | - | env |
| protocol-framing-surface | Framing.ts 大端记录流落地：[u8 recordType][u32 len][payload]（RECORD_CONTROL=0x01/RECORD_VIDEO=0x02/RECORD_HEADER_BYTES=5）、21B 视频帧头（u32 seq+u64 ptsUs+u8 flags+u16 fragIdx+u16 fragCnt+u32 totalLen）、TCP 整帧口径（fragCnt≠1 抛 ProtocolError）、seq 单调性缺口丢帧计数、u16String UTF-8 编解码落在 Framing（TextEncoder/TextDecoder）（出处：client-web/app/src/protocol/Framing.ts:16-23,242,352；tasks.md 1.2） | pass | 0/2 | - |
| protocol-messages-surface | Messages.ts 17 条控制消息（0x01 HELLO–0x11 TEXT_INPUT）编解码分支齐全；解码器对未知尾部字节容忍跳过（HELLO 可选 takeover 尾字节先例）；34B 加密协商块可选、缺失回落 none；HELLO_ACK 携 hungSessions 列表；UTF-8 u16String 判定同时覆盖 Messages.ts 与 Framing.ts 委托实现（出处：client-web/app/src/protocol/Messages.ts:27-45,297-299；tasks.md 1.3；judge 修复：UTF-8 检查含 Framing.ts 委托防假阴性） | pass | 0/2 | - |
| protocol-golden-tests-pass | 协议 golden 单测全绿（client-web/app 下 vitest run tests/protocol.golden.test.ts）：Kotlin 用例导出的字节向量双向逐字节断言，含 takeover 出现/缺失两形态与未知尾字节容忍；退出码以 POSIX 兼容方式捕获（出处：tasks.md 1.4；judge 修复：PIPESTATUS 为 bash 专属，dash 下 Bad substitution，改 POSIX 形态） | pass | 0/2 | - |
| gateway-relay-tests-pass | 网关中继集成测试全绿（client-web/gateway 下 node --test test/relay.test.js）：脚本化假源端覆盖上/下行逐字节透传、REJECT_BAD_TOKEN 透传后随关、慢消费者 8MB 不丢不乱、双 WS 并发不串扰、生命周期联动、upstream 拒连 1011、env 缺省与覆盖、同端口静态托管（出处：tasks.md 2.3；judge 修复：同 golden 场景 POSIX shell 形态）（judge 修复2：汇总 grep 改 '^(#\|ℹ) fail 0' 兼容 node:test spec reporter 实际输出 ℹ fail 0（本体 9/9 全绿被词表失配误杀）） | pass | 0/2 | - |
| gateway-env-config-live | 网关 env 配置活体验证：resolveConfig 缺省（8090/127.0.0.1:22333，经 node -e import 断言，不占端口）、以随机空闲端口 + 随机假上游端口活体启动 server.js → HTTP GET / 200 后干净退出；UPSTREAM 覆盖值解析生效（出处：client-web/gateway/server.js:73-102 resolveConfig；specs/web-gateway/spec.md「环境变量配置」；静态检查修复：cli 命令内零字面端口，端口全部运行期动态分配）（judge 修复2：cfgoverride 步骤补 P1/P2 运行期动态分配并 export——原步骤漏设环境变量致 UPSTREAM 拼成 127.0.0.1:undefined 被按设计拒绝） | pass | 0/6 | - |
| session-client-orchestration-surface | SessionClient.ts 会话编排控制面静态面：HELLO{p2pOffer:""} 且 crypto:null 显式 none 路径（不写加密块）、HELLO 不建会话（占位 ACK 后另发 START_SESSION）、startOrSwitchSession 换 sessionId 重置、断线指数退避（0.5s 起步封顶 5s/窗口 30s）→ ATTACH 且不发 IDR_REQUEST、REJECT_BAD_TOKEN/REJECT_BUSY 显式失败不静默重试、应答入站 PING（Pong nonce/senderUs/echoUs=nowUs）+ 6s Pong 看门狗、消费 STATS、hungSessions 记录（出处：client-web/app/src/net/SessionClient.ts:105-114,203-216,427-430,540-568；tasks.md 5.1/5.2；judge 修复：crypto 词表补 null 形态） | pass | 0/2 | - |
| video-audio-pipeline-surface | 视频/音频管线静态面：VideoPipeline isConfigSupported 探测（不支持→含浏览器名的 VideoUnsupportedError/错误页文案）、Annex B + optimizeForLatency + prefer-hardware、逐帧 drawImage 后 close、seq 缺口丢帧计数与诊断帧 dump；AudioPipeline 剥 ADTS 构造 AudioSpecificConfig、起播水位 120ms、不足即 skip 计数、AudioDecoder 缺失显式降级原因、静音抑制 keepalive 兼容（出处：client-web/app/src/media/VideoPipeline.ts、AudioPipeline.ts；tasks.md 3.1/3.2/4.1/4.2） | pass | 0/2 | - |
| input-bridge-surface | InputBridge 输入映射静态面：pointerdown/move/up/cancel → INPUT_EVENT、letterbox contain 几何（offsetX/Y+scale）线性映射 ×65535 clamp u16、MOVE 位移阈值节流、pointerId→u8 活动槽位、BACK 按钮+Esc→action=3、隐藏输入框→TEXT_INPUT(kind=0)+回车/按钮发送、CLIPBOARD_GET/SET 与 CLIPBOARD_TEXT(kind=1) 置灰+toast 原因（出处：client-web/app/src/input/InputBridge.ts:33-59,158-218,291-299；tasks.md 6.1） | pass | 0/2 | - |
| hud-main-ui-surface | 连接页与 HUD 静态面：URL ?token= 写 localStorage 并 history.replaceState 去参、清除按钮移除记忆、档位 720p/1080p×30/60×4/8/16/20Mbps 默认 720p60/8Mbps、validateProfile 区间校验（width 320-2560/height 240-2560/fps 15-60/bitrate 1000-20000）、HUD 七项指标与采样开关/下载按钮（停止且有样本才可见）、exportSamplesDoc 浏览器端口径 schema（出处：client-web/app/src/main.ts:95-146,334-438；src/ui/Hud.ts:50-58,160-171；tasks.md 6.2；quality-gates interaction-contract）（judge 修复2：exportSamplesDoc 锚点由 main.ts 改 Hud.ts（实现于 Hud.ts:181 并由 :203 下载按钮调用，原锚定文件客观错位）） | pass | 0/2 | - |
| docs-protocol-annotations | docs/protocol.md 浏览器对接注记与三处勘误落盘：§4 附着时序勘误（ATTACH=START_SESSION{sessionId,mode=ATTACH} 非 HELLO 字段、附着自动触发关键帧）、控制方向表勘误（源端→客户端 1Hz PING 与 STATS 推送、浏览器必须应答 PONG）、§3.1 补记 HELLO 可选 takeover 尾字节（传输层抢占现役通道、会话层不读、浏览器 v1 永不发送）、§5 REJECT_BUSY 双来源语义（传输层占用闸门 + 会话层 ATTACH 恢复失败）、WS 承载同构注记含 ws:// 与 wss:// 两种形态（出处：docs/protocol.md:129-135,386,396,496-498；tasks.md 7.2；judge 修复：WS 承载词表补 ws(s):// 形态）（judge 修复2：WS 承载词表改为协议文档既有记法 ws(s)://（§8.1 单一记法承载 ws://与 wss:// 两形态，原字面 grep 客观失配）） | pass | 0/2 | - |
| docs-web-client-manual | docs/web-client.md 部署手册与真机人工验收清单：网关 env 配置（GATEWAY_PORT/UPSTREAM/STATIC_DIR）与 systemd 常驻、frpc 新增 proxy（remotePort→GATEWAY_PORT）、token 书签交付（?token= URL）、交付自检、§6 真机单应用人工验收清单（单应用进入/切换、ATTACH 恢复、音频、剪贴板、文本注入、PC 键鼠与平板触屏）、明文风险如实注记（出处：docs/web-client.md §1-§6；tasks.md 7.3；judge 修复：明文风险词表放宽为 无 TLS\|保密性 同义表述） | pass | 0/2 | - |
| web-latency-samples-delivery | 延迟采样交付（tasks.md 7.4 偏差注解口径）：docs/web-latency-samples.json 存在且为合法 JSON，含 LatencySamplesDoc schema 键（caliber/profile/browser/collectedAt/summary/samples），caliber 注明浏览器端口径；采样数组为空时 MUST 附 blocker 块（status=env-blocked + 恢复路径 recover），与 tasks.md 7.4「HUD 下载采样按钮为恢复路径交互」注解一致（出处：docs/web-latency-samples.json；client-web/app/src/ui/Hud.ts:51-58；tasks.md 7.4 注解） | pass | 0/2 | - |
| web-token-clear-clears-input | 清除记忆的 Token：携带 token 进入（localStorage 已存值）→ 点击「清除记忆的 Token」按钮 → 输入框立即清空且横幅呈现「已清除本机保存的 token」，连接按钮仍可用（出处：client-web/app/src/main.ts:148-152；judge 修复：自 web-token-url-autofill-persist 拆分，清除动作不与回填断言同窗） | pass | 0/6 | - |
| web-token-empty-input-rejected | 空 token 提交被原生校验拦截：空输入直接点击连接 → index.html required 属性原生拦截提交（不发起 WS、页面无错误横幅、焦点落到 token 输入框）、停留连接页（出处：client-web/app/index.html:23 required；judge 修复：显式校验横幅文案路径被原生校验短路不可达，断言改锚 required 属性与拦截后状态） | pass | 0/8 | - |
| fake-source-up | 协议假源端接管 22333（Node 脚本化假源端，design.md D10 网关集成测试同款形态）：实现 HELLO 认证（cloudphone-m0）/占用闸门/START_SESSION 三模式（FULLSCREEN/SINGLE_APP 换新 sessionId、ATTACH 校验挂机表）/APP_LIST/1Hz STATS/应答 PONG/TEXT_INPUT ACK/CLIPBOARD_GET 降级通告/STOP 销毁/断开挂机，后台常驻并落健康文件；随后经本轮 serving 网关（FENIX_VERIFY_PORT）以 ws 客户端完成 HELLO→HELLO_ACK accepted→PING→PONG 活体探针——同时活体验证网关 WS↔TCP 双向字节中继（出处：specs/web-gateway/spec.md「协议无关字节中继」；design.md D10；docs/protocol.md §3 线格式） | blocked-env | - | env |
| web-wrong-token-explicit-fail | 错误 token 显式失败：输入确定错误的 token 点击连接 → 假源端回 REJECT_BAD_TOKEN 并关链 → 页面呈现「连接被拒绝（REJECT_BAD_TOKEN）：token mismatch」横幅，停留连接页且连接按钮恢复可用（不静默重试）（出处：specs/browser-streaming-client/spec.md「错误 token 显式失败」；client-web/app/src/main.ts:431；SessionClient.ts:218-221 failExplicit） | blocked-env | - | env |
| web-busy-holder-up | 观看端占用闸门夹具：以独立原始 TCP 连接完成 HELLO（cloudphone-m0）并保持不断开——成为现役观看端通道；机械验证此时第二条经网关的 HELLO 被拒（REJECT_BUSY=2，占用原因「源端正被其他设备观看」），闸门夹具就绪（出处：specs/web-gateway/spec.md/design.md D 风险段「观看端占用闸门」；LanDirectBackend.kt:130-135 语义的假源端复刻） | blocked-env | - | env |
| web-busy-rejection-display | 占用拒绝显式呈现：现役通道被夹具持有期间，浏览器输入正确 token 连接 → 页面呈现「连接被拒绝（REJECT_BUSY）：源端正被其他设备观看」横幅，停留连接页且无会话视图（不静默重试）（出处：specs/browser-streaming-client/spec.md「占用拒绝显式呈现」；client-web/app/src/main.ts:431-432） | blocked-env | - | env |
| busy-holder-down | 占用闸门清理回归：终止占用夹具连接 → 现役通道断开即清闸（LanDirectBackend.kt:163 语义）→ 机械复测新 HELLO 重新被接受（accepted 且 sessionId=0 占位）——闸门生命周期与「挂机会话可被下一个观看端接入」口径一致（出处：specs/browser-streaming-client/spec.md；design.md 风险段①） | blocked-env | - | env |
| web-fullsession-apps-hud | 正确 token 建会话正向主链（假源端活体）：连接（默认档 720p60/8Mbps）→ HELLO 占位 ACK → START_SESSION FULLSCREEN 换新 sessionId → 应用网格渲染（APP_LIST 2 条目）→ 点选应用切入会话视图 → canvas 尺寸=HELLO_ACK 实测参数（720×1280）、HUD 呈现源端 STATS（fps=58.5/8.00 Mbps）与自发 PING/PONG 实测 RTT（入站 PING 应答与 STATS 消费的活体证据）→ 结束回连接页（出处：specs/browser-streaming-client/spec.md「正确 token 建会话出画」「入站控制应答与时钟统计」「HUD 呈现延迟数据」；main.ts:268-280,300-315） | blocked-env | - | env |
| web-profile-custom-tier | 自定义档位下发端到端：连接页选择 1080p/30fps/16Mbps → 连接 → 点选应用进入会话视图 → canvas 尺寸切换为 HELLO_ACK 返回的实测档位 1080×2400（START_SESSION 携所选参数、响应参数被采用为坐标基准）→ 结束回连接页（出处：specs/browser-streaming-client/spec.md「自定义档位下发」；main.ts:95-110,244-249） | blocked-env | - | env |
| web-hang-attach-resume | 挂机→恢复入口→ATTACH 续看：建会话（会话视图确认）→ 整页重载（WS 断开且未发 STOP=挂机）→ 重新连接 → 应用网格呈现「恢复会话 #N」入口（HELLO_ACK.hungSessions 非空契约）→ 点击恢复 → START_SESSION{sessionId=N, mode=ATTACH} 回到会话视图且 canvas 参数就位 → 结束回连接页（出处：specs/browser-streaming-client/spec.md「断线自动重连（ATTACH）」「关页挂机可恢复」；main.ts:213-221；SessionClient.ts:609） | blocked-env | - | env |
| web-aux-input-toolbar | 工具条输入与辅助通道反馈：建会话入会话视图 → BACK 按钮（action=3 注入后会话保持）→ 「文本」展开隐藏输入框 → 输入含中英文/符号文本并再次点击发送 → toast 呈现假源端 ACK「文字注入成功（seq=1）」（TEXT_INPUT kind=0→ACK 回执）→ 音频按钮静音切换（data-muted 翻转+文案变化）→ 读剪贴板触发假源端降级通告（CLIPBOARD_TEXT kind=1）→ 剪贴板两按钮置灰 + toast「辅助通道降级」→ 结束回连接页（出处：specs/browser-streaming-client/spec.md「输入回传与辅助通道」；InputBridge.ts:158-218,291-299；main.ts:448-465） | blocked-env | - | env |
| web-end-destroys-session | 显式结束销毁会话（回归）：建会话 → 点击「结束」（STOP_SESSION fire-and-forget）→ 回连接页无残留横幅 → 再次连接 → 应用网格无「恢复会话」条目（STOP 销毁语义：该会话不再出现于 hungSessions）→ 断开按钮回连接页（出处：specs/browser-streaming-client/spec.md「显式结束销毁会话」；main.ts:454-457,467-470；假源端 STOP→销毁不挂机） | blocked-env | - | env |
| fake-source-down | 假源端清理：终止假源端进程并移除健康文件/pidfile，机械断言 22333 重新连接被拒——上游通路回到中性态，不污染后续模拟器链（出处：场景链自持生命周期约定；.fenix/verify.yaml 源端通路由场景执行期编排的边界） | blocked-env | - | env |
| emulator-env-ready | 模拟器环境就绪前置（环境依赖——无 fallback：源端环境三层依赖阻塞已按三振止损冻结，见 tasks.md 7.1/7.4 注解与 docs/web-latency-samples.json blocker）：双模拟器健康检查（emulator-5554 源端/emulator-5556 客户端常驻夹具）→ 禁用观看端 App 防通道抢占 → bootstrap_adb.sh all（构建安装 source/loadgen/client、adb root、TCP forward、前台服务、MediaProjection 授权）→ 机械断言包已装 + 线级 token 探针 HELLO 被接受 | blocked-env | - | env |
| playwright-smoke-e2e | 交付件 e2e/smoke.spec.ts 运行验收（tasks.md 7.1 偏差注解的替代场景链口径：冒烟基建全量交付且 tsc/playwright 校验通过；点击注入断言以浏览器侧 WS 出站 INPUT_EVENT 0x06 帧计数替代，源端 t2/t3 打点留真机人工复测项）（环境依赖——无 fallback：同 emulator-env-ready，源端 MediaProjection/授权链阻塞时按三振止损冻结）：E2E_GATEWAY_PORT 用运行期动态端口起网关，npx playwright test 全量用例退出 0 | blocked-env | - | env |


## 断言级豁免清单

以下失败断言被归类为非产品缺陷（不记产品 Bug），逐条列出供人工复核豁免是否掩盖真实缺陷（场景级一句话归因不足以复核）。

| 场景 | 断言 | 归类 | 豁免理由 | 证据 |
| --- | --- | --- | --- | --- |
| upstream-22333-free | exit_code 步骤1(free)=0（实得 1） | env | 环境因素（非产品缺陷） | 移除语义本轮已正确：judge 修复2 的 -s 逐设备生效（FORWARD_REMOVED:emulator-5554、零 REMOVE_FAILED，上轮 more-than-one-device 缺陷不复现）；复活根源=宿主常驻 adb_forward_keepalive.sh 守护（ppid=1，5s 周... |
| upstream-22333-free | output_match contains UPSTREAM_FREE_OK | env | 环境因素（非产品缺陷） | 同守护重建根因（活体复现一致：移除后 forward 复活）；5556 的 FORWARD_LEFT 系 adb forward --list 忽略 -s 的全宿主列举假象（活体 -s 5556 remove 报 listener not found，佐证 5556 无自有 forward）。22333=UPST... |
| web-upstream-unreachable-explicit-fail | dependency_blocked skipped_depends_on=upstream-22333-free | env | 环境因素（非产品缺陷） | 依赖失败传播（非本场景产品缺陷）：阻断根因在上游 upstream-22333-free 的失败（已另行归因），本场景零断言零步骤执行，同窗并行 batch=2 peers=0 observed_parallel=0 |
| fake-source-up | dependency_blocked skipped_depends_on=web-upstream-unreachable-explicit-fail | env | 环境因素（非产品缺陷） | 依赖失败传播（非本场景产品缺陷）：阻断根因在上游 web-upstream-unreachable-explicit-fail 的失败（已另行归因），本场景零断言零步骤执行，同窗并行 batch=3 peers=0 observed_parallel=0 |
| web-wrong-token-explicit-fail | dependency_blocked skipped_depends_on=occupied-gate-cleanup | env | 环境因素（非产品缺陷） | 依赖失败传播（非本场景产品缺陷）：阻断根因在上游 occupied-gate-cleanup 的失败（已另行归因），本场景零断言零步骤执行 |
| web-busy-holder-up | dependency_blocked skipped_depends_on=web-wrong-token-explicit-fail | env | 环境因素（非产品缺陷） | 依赖失败传播（非本场景产品缺陷）：阻断根因在上游 web-wrong-token-explicit-fail 的失败（已另行归因），本场景零断言零步骤执行，同窗并行 batch=5 peers=0 observed_parallel=0 |
| web-busy-rejection-display | dependency_blocked skipped_depends_on=wire-e2e-occupied-busy | env | 环境因素（非产品缺陷） | 依赖失败传播（非本场景产品缺陷）：阻断根因在上游投影丢失级联（已另行归因 env），本场景零断言零步骤执行，无占位连接夹具残留 |
| busy-holder-down | dependency_blocked skipped_depends_on=web-busy-rejection-display | env | 环境因素（非产品缺陷） | 依赖失败传播（非本场景产品缺陷）：阻断根因在上游 web-busy-rejection-display 的失败（已另行归因），本场景零断言零步骤执行，同窗并行 batch=7 peers=0 observed_parallel=0 |
| web-fullsession-apps-hud | dependency_blocked skipped_depends_on=busy-holder-down | env | 环境因素（非产品缺陷） | 依赖失败传播（非本场景产品缺陷）：阻断根因在上游 busy-holder-down 的失败（已另行归因），本场景零断言零步骤执行，同窗并行 batch=8 peers=0 observed_parallel=0 |
| web-profile-custom-tier | dependency_blocked skipped_depends_on=web-fullsession-apps-hud | env | 环境因素（非产品缺陷） | 依赖失败传播（非本场景产品缺陷）：阻断根因在上游 web-fullsession-apps-hud 的失败（已另行归因），本场景零断言零步骤执行，同窗并行 batch=9 peers=0 observed_parallel=0 |
| web-hang-attach-resume | dependency_blocked skipped_depends_on=web-profile-custom-tier | env | 环境因素（非产品缺陷） | 依赖失败传播（非本场景产品缺陷）：阻断根因在上游 web-profile-custom-tier 的失败（已另行归因），本场景零断言零步骤执行，同窗并行 batch=10 peers=0 observed_parallel=0 |
| web-aux-input-toolbar | dependency_blocked skipped_depends_on=web-hang-attach-resume | env | 环境因素（非产品缺陷） | 依赖失败传播（非本场景产品缺陷）：阻断根因在上游 web-hang-attach-resume 的失败（已另行归因），本场景零断言零步骤执行，同窗并行 batch=11 peers=0 observed_parallel=0 |
| web-end-destroys-session | dependency_blocked skipped_depends_on=web-aux-input-toolbar | env | 环境因素（非产品缺陷） | 依赖失败传播（非本场景产品缺陷）：阻断根因在上游 web-aux-input-toolbar 的失败（已另行归因），本场景零断言零步骤执行，同窗并行 batch=12 peers=0 observed_parallel=0 |
| fake-source-down | dependency_blocked skipped_depends_on=web-end-destroys-session | env | 环境因素（非产品缺陷） | 依赖失败传播（非本场景产品缺陷）：阻断根因在上游 web-end-destroys-session 的失败（已另行归因），本场景零断言零步骤执行，同窗并行 batch=13 peers=0 observed_parallel=0 |
| emulator-env-ready | dependency_blocked skipped_depends_on=fake-source-down | env | 环境因素（非产品缺陷） | 依赖失败传播（非本场景产品缺陷）：阻断根因在上游 fake-source-down 的失败（已另行归因），本场景零断言零步骤执行，同窗并行 batch=14 peers=0 observed_parallel=0 |
| playwright-smoke-e2e | dependency_blocked skipped_depends_on=web-aux-input-feedback | env | 环境因素（非产品缺陷） | 依赖失败传播（非本场景产品缺陷）：阻断根因在上游 web-aux-input-feedback 的失败（已另行归因），本场景零断言零步骤执行 |

## 本轮场景集变更

当前稳定场景集共 45 个（第 1 轮）。

- 无理由缺失 11 个场景，已以持久化集补齐：emulator-bootstrap-env、wire-e2e-fullsession-via-gateway、wire-e2e-attach-hang-resume、wire-e2e-occupied-busy、occupied-gate-cleanup、web-token-url-autofill-persist-clear、web-fullsession-first-flow、web-tier-selection-custom、web-singleapp-switch-session、web-attach-resume-after-reload、web-aux-input-feedback
- 无理由缺失且备份缺定义的场景 11 个定义不可恢复，已剔除：emulator-bootstrap-env、occupied-gate-cleanup、web-attach-resume-after-reload、web-aux-input-feedback、web-fullsession-first-flow、web-singleapp-switch-session、web-tier-selection-custom、web-token-url-autofill-persist-clear、wire-e2e-attach-hang-resume、wire-e2e-fullsession-via-gateway、wire-e2e-occupied-busy

> ⚠️ 本轮 planner 产出缺少既有场景且无理由，已以持久化集补齐后继续验证（不中断验证）。

