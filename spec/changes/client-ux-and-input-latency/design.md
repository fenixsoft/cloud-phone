# Design: client-ux-and-input-latency

## Context

M0 SPEC（m0-a2a-streaming-pipeline）已交付并 12/12 PASS：双模拟器全链 60fps 串流可用，真机首测暴露本 SPEC 四项需求。现状关键事实：

- 客户端连接参数为手输（ConnectActivity `Config(host, tcpPort, udpPort, kind, token, useUdpVideo)`），无任何持久化
- START_SESSION 协议已携带 width/height/fps/bitrateKbps 四元组，但连接页无 UI（仅自动化 extras 通道 autoWidth/autoHeight）；源端 `EncoderPipeline.SessionParams.normalized` 高度上限已放宽至 2560，`ScreenCapture.buildPipeline` aspect-exact 按请求短边等比适配源屏——传档位通路就绪，缺的只是客户端 UI
- 触摸链路已修复接线（TouchChannel → InputEvent → helper INJECT display 0），但真机反馈「几秒才有一次反应」；模拟器同场景实测：静屏（设置页）串流 ≈1fps，源端 EGL 合成 app_time_stats 显示 ~1000ms/帧（swiftshader 软渲染 + 编码负载），初步归因链条为「静屏合成慢 → 采集仅在内容变化时出帧 → 视觉反馈被拖到秒级」，触摸注入本身（recv InputEvent → INJECT OK）为毫秒级——**该归因须以插桩数据证实，禁止未测量就定论**
- 协议现有消息：Hello/HelloAck/StartSession/StopSession/IdrRequest/InputEvent/StatsMessage/Ping/Pong；控制面 TCP 逐记录 flush，帧面 UDP/TCP 双路径

## Goals

1. 重装/换机后源端连接信息一键恢复，不逐字段重输
2. 串流档位（分辨率/帧率/码率）由客户端决定，默认跟随本机分辨率
3. 源端应用可从客户端桌面图标一键直达（单应用模式）
4. 静屏触摸响应从秒级压到 P95 ≤ 800ms（模拟器口径），真机目标 ≤ 300ms

## Non-Goals

- 不做应用图标真实图标提取（列表/快捷方式用标签文字+通用图标即可，M1 再做）
- 不做档案云同步/多设备导入导出 UI（仅本地持久化；「备份恢复」Scenario 指同机重装路径）
- 不改动编码协议帧格式与传输三后端架构
- 不追求模拟器上把触摸延迟压到真机级（swiftshader 合成瓶颈非本仓库代码可控，口径分 device 区记录）

## Decisions

### D1 源端档案：SharedPreferences + JSON 数组，单文件单类
`client/.../SourceProfiles.kt`：`SourceProfile(name, host, tcpPort, udpPort, backend, token, lastConnectedAt)`，存 `SharedPreferences(MODE_PRIVATE)` 内 JSON（org.json，Android 自带）。自动保存时机=START_SESSION ACCEPTED；键=host+tcpPort+backend（同键更新）。连接页下拉 `Spinner` 填充档案列表 + 「删除」按钮。不用 Room/DataStore（依赖最小化，数据量 ≤ 数十条）。

### D2 分辨率传递：纯 UI 增强，协议零改动
连接页新增三控件：分辨率 Spinner（跟随本机/720p/1080p/自定义…）+ 帧率 Spinner（30/60）+ 码率 Spinner（4/8/16/20Mbps）。「跟随本机」取 `windowManager.maximumWindowMetrics`（API 30+）或 `realDisplayMetrics`（降级路径）当前真实尺寸。客户端只做 240–2560 范围校验，对齐交给源端（aspect-exact 已处理 /16 对齐）。横竖屏：跟随本机直接取当前朝向尺寸；720p/1080p 按客户端当前朝向映射（竖屏 720x1280）。源端语义不变：请求短边=档位短边，等比适配源屏——协议文档补「目标档位语义」一段。

### D3 应用枚举：协议加两条消息，源端 App 自己枚举（无 shell 依赖）
`AppListRequest`（空载荷）/ `AppListMessage{apps: List<AppEntry(packageName,label)>}`。源端 handler：`packageManager.queryIntentActivities(Intent(MAIN).addCategory(LAUNCHER), 0)` → 排除 `cloudphone.source` 自身 → label 取 `loadLabel`。会话在线才响应（请求-应答制，天然兼容旧客户端）。编码走既有 Framing 通用记录通道，无新 RECORD 类型。

### D4 桌面快捷方式：requestPinShortcut + cloudphone://launch deeplink
- 快捷方式 Intent：`Intent(ACTION_VIEW, "cloudphone://launch?host=..&tcpPort=..&udpPort=..&backend=..&token=..&pkg=..")` 指向 ConnectActivity（manifest 加 scheme cloudphone 的 intent-filter，exported=true）
- ConnectActivity onNewIntent/onCreate 解析 launch deeplink：填充字段（并落一份档案，D1 通路复用）→ 静默自动连接 → 成功即 SINGLE_APP startSession → 进 DecodeRenderActivity；失败带原因回连接页（单应用模拟器镜像限制的既有 REJECT 文案自然复用）
- 「加到桌面」：`ShortcutInfoCompat`/`ShortcutManager.requestPinShortcut`，id=`launch-<host>-<port>-<pkg哈希>`，图标用客户端默认图标（不提取应用图标，Non-Goal）
- 入口：SessionDrawerActivity 加「源端应用」按钮 → 新 `SourceAppsActivity`（ListView：标签+包名+两按钮）
- 降级：Launcher 拒绝钉选（`callbackIntent` 不支持）→ Toast「当前桌面不支持创建快捷方式」

### D5 触摸延迟：先插桩测量，按数据修，禁止盲改
**根因假设**（待证实）：触摸注入（②③）是毫秒级，瓶颈在④源屏合成→采集出帧——静屏下 swiftshader 合成 ~1fps + 采集按内容变化出帧，视觉反馈被拖秒级；模拟器软渲染是主因素，真机硬件合成预期远好，但**静屏变频档的交互死区在真机同样存在**（静止屏≈0 出帧），故修复须对两类场景都有效。

插桩方案（先做，为修复定位）：源端 InputInjector 注入完成打点 + ScreenCapture 出帧打点（帧间隔>200ms 标记 idle 段）+ 客户端 DecodeRenderActivity 渲染打点；`measure_latency.py` 增「触摸口径」：串流静屏画面下脚本注入触摸（input tap 改为经客户端协议链路或 adb 直注源端）+ 客户端截屏 diff 检测视觉变化，输出 P50/P95。模拟器上跑 20 次。

修复手段按测量结论择优组合（实现者以数据说话，允许只实施其中生效项）：
1. **输入触发帧率提升（预期主修复）**：InputEvent 到达后 2s 窗口内，源端主动驱动采集管线出帧（必要时以轻量 overlay 心跳/VD 重绘强制合成——机制实现者验证，能达成「输入后 2s 内 ≥5fps」即可），窗口结束回落变频档
2. **客户端 latest-wins 渲染兜底**：解码输出仅渲染最新帧（`releaseOutputBuffer(index, false)` 丢弃陈旧帧），防止低 fps × 队列深度放大延迟（现 frameQueue 深度 8，静屏 1fps 下最坏 ~8s——须测量证实或排除该因素）
3. TCP_NODELAY 复核（两端 socket 已逐记录 flush，确认无 Nagle 合批残留）

### D6 验收环境与口径
- 模拟器口径：双模拟器（swiftshader）验收 touch-latency-fix Scenario 阈值 800ms；快捷方式创建/直达、档案、分辨率 UI 均在模拟器完成功能性验收
- 真机口径：device-notes 记录真机测量步骤与 ≤300ms 目标，真机单应用快捷方式直达由用户人工复测（沿用 device-notes §2.1/§4 手册；模拟器单应用镜像限制不变）
- 「禁止伪造验收」红线沿用：所有 Scenario 数据须来自真实运行

## Risks

| 风险 | 缓解 |
|------|------|
| swiftshader 合成慢使模拟器上 800ms 阈值仍不可达 | 插桩会先给出合成段耗时基线；若证实为平台瓶颈，输入提升窗口能把「有变化就出帧」的部分压到合成节拍内，阈值放宽须在 device-notes 记录数据依据并在设计文档留痕，不得静默放水 |
| 部分国产 Launcher 不支持 requestPinShortcut | D4 降级路径（Toast 提示），应用列表「打开」始终可用 |
| 输入提升窗口强制合成需要 overlay 权限（SYSTEM_ALERT_WINDOW） | 优先采用无需权限的机制（如 VD 端自绘/编码器请求关键帧+短时固定帧率）；确需权限时走「引导用户授权」文档路径，M0 不得静默依赖 |
| deeplink token 明文在 shortcut intent 中 | shortcut 为设备本地私有面（launcher 数据），风险与桌面图标同量级；档案存储已约束 MODE_PRIVATE；协议加密属 M1 范围 |
| StartSession 自定义宽高触发源端频繁 VD 重建 | 源端 ensure_pipeline 已有同参幂等 + 参数变化才重建语义，UI 侧对同一会话内不改参数（仅连接时生效） |
