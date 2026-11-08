## Deliverables

```yaml
- path: client/app/src/main/kotlin/cloudphone/client/SourceProfiles.kt
  kind: new
- path: client/app/src/main/kotlin/cloudphone/client/SourceAppsActivity.kt
  kind: new
- path: client/app/src/main/kotlin/cloudphone/client/ConnectActivity.kt
  kind: modify
- path: client/app/src/main/kotlin/cloudphone/client/SessionDrawerActivity.kt
  kind: modify
- path: client/app/src/main/kotlin/cloudphone/client/DecodeRenderActivity.kt
  kind: modify
- path: client/app/src/main/kotlin/cloudphone/client/input/TouchChannel.kt
  kind: modify
- path: client/app/src/main/AndroidManifest.xml
  kind: modify
- path: source/app/src/main/kotlin/cloudphone/source/StreamService.kt
  kind: modify
- path: source/app/src/main/kotlin/cloudphone/source/ScreenCapture.kt
  kind: modify
- path: source/app/src/main/kotlin/cloudphone/source/EncoderPipeline.kt
  kind: modify
- path: source/app/src/main/kotlin/cloudphone/source/input/InputInjector.kt
  kind: modify
- path: protocol/src/main/kotlin/cloudphone/protocol/Messages.kt
  kind: modify
- path: docs/protocol.md
  kind: modify
- path: docs/device-notes.md
  kind: modify
- path: scripts/measure_latency.py
  kind: modify
```

## 1. 源端档案持久化（source-profiles）

- [x] 1.1 实现 SourceProfiles.kt：SourceProfile(name/host/tcpPort/udpPort/backend/token/lastConnectedAt) 模型与 SharedPreferences(MODE_PRIVATE) JSON 数组存取（upsert 键=host+tcpPort+backend、list、delete、无日志泄漏 token）
- [ ] 1.2 ConnectActivity.kt 集成档案：连接页顶部「已保存源端」Spinner（选中自动填充全部字段）、删除入口、连接成功（START_SESSION ACCEPTED）自动 upsert 档案与 lastConnectedAt；AndroidManifest.xml 将 allowBackup 改为 true（档案 prefs 纳入系统自动备份支撑重装恢复路径，device-notes 记录无备份传输环境的降级口径）
- [ ] 1.3 模拟器端到端自验：首次连接成功→重启 App 档案在列且回填正确；同源端连 3 次仅 1 条；删除后不再出现（日志截图或 logcat 摘录入验证记录）

## 2. 分辨率/帧率/码率传递（resolution-passthrough）

- [ ] 2.1 ConnectActivity.kt 新增档位 UI：分辨率 Spinner（跟随本机=真实屏幕尺寸默认档/720p/1080p/自定义宽高对话框，本地校验 width 320–2560、height 240–2560，对齐源端 EncoderPipeline 钳制区间）、帧率 Spinner(30/60 默认60)、码率 Spinner(4/8/16/20Mbps 默认8)；选定值随 START_SESSION width/height/fps/bitrateKbps 下发
- [ ] 2.2 适配验证：请求 1080x2400（同比例源屏）源端 aspect-exact 日志 ≈1080x2400；请求 720p 于 20:9 源屏得 720x1600 且客户端 letterbox 无扭曲（复用现有 AspectFitSurfaceView）
- [x] 2.3 docs/protocol.md 补「目标档位语义」段：客户端传目标档位，源端保证短边吻合+源屏宽高比（含 /16 对齐偏差说明）

## 3. APP_LIST 协议与源端枚举（app-shortcuts 后端半）

- [x] 3.1 Messages.kt 新增 AppListRequest/AppListMessage（AppEntry{packageName,label} 列表）与编解码，Framing 通用记录通道复用，单测覆盖编解码往返
- [x] 3.2 StreamService.kt 处理 AppListRequest：packageManager.queryIntentActivities(MAIN+LAUNCHER) 枚举、排除 cloudphone.source 自身、会话在线才应答；docs/protocol.md 增补两条消息字段表与时序
- [ ] 3.3 验证：客户端会话在线发请求 1s 内收到含负载 App 的列表；无会话时请求被忽略（老客户端兼容）

## 4. 客户端应用列表与桌面快捷方式（app-shortcuts 前端半）

- [ ] 4.1 实现 SourceAppsActivity.kt：会话在线拉取 AppListMessage 展示（标签+包名），每项「打开」（SINGLE_APP StartSession 直进串流）与「加到桌面」（requestPinShortcut，id=launch-<host>-<port>-<pkg哈希>）；3s 无应答展示重试；钉选被拒 Toast 降级
- [ ] 4.2 ConnectActivity.kt 支持 cloudphone://launch deeplink（host/tcpPort/udpPort/backend/token/pkg 参数）：自动填充+落档案+静默连接+SINGLE_APP 直达，失败带原因回连接页；AndroidManifest.xml 加 scheme intent-filter 与 SourceAppsActivity 注册；SessionDrawerActivity.kt 加「源端应用」入口
- [ ] 4.3 模拟器端到端验证：创建快捷方式→桌面点击图标→自动连接直达（单应用在模拟器被镜像级拒绝时验证「失败带原因回连接页」路径；「打开」按钮同口径验证）；静默连接成功路径用全屏模式旁路验证 UI 自动化不回归

## 5. 触摸响应插桩与修复（touch-latency-fix）

- [ ] 5.1 全链插桩（覆盖①–⑥）：TouchChannel 触摸事件产生打点（①）、InputInjector 注入完成打点（②③）、ScreenCapture 出帧打点（④，帧间隔>200ms 标记 idle）、EncoderPipeline 编码出帧打点（⑤）、DecodeRenderActivity 渲染打点（⑥）（cloudphone-* tag 可检索）
- [ ] 5.2 scripts/measure_latency.py 增触摸口径：串流静屏下注入触摸+客户端侧视觉变化检测，输出注入→视觉变化 P50/P95（20 次），先采基线数据（预期秒级）入验证记录
- [ ] 5.3 实施修复（以 5.2 数据选型）：输入后 2s 窗口内源端驱动采集出帧 ≥5fps（机制实现者验证：优先免权限方案）、客户端解码 latest-wins 渲染（陈旧帧 releaseOutputBuffer(false)）、TCP_NODELAY 复核；ScreenCapture.kt/StreamService.kt/InputInjector.kt/EncoderPipeline.kt/DecodeRenderActivity.kt 按选型落码（选型涉及关键帧请求/短时固定帧率时触及 EncoderPipeline）
- [ ] 5.4 修复后复测：静屏触摸 P95 ≤800ms（模拟器口径）与 60fps 负载不回退（P95 ≤300ms）双场景达标，前后对比数据入验证记录；docs/device-notes.md 记录模拟器/真机口径差异、真机目标 ≤300ms、可复现测量步骤

## 6. 回归与收尾

- [ ] 6.1 全量回归：3 后端（lan-direct/frp/p2p）全屏模式连接+60fps 冒烟、矩阵关键组合（lan game 720p）复跑通过，确认新 UI 未破坏既有自动化 extras 通道（auto* 参数路径）
- [ ] 6.2 提交与文档收口：docs/device-notes.md 快捷方式 Launcher 兼容性说明与真机人工验证清单（含单应用快捷方式真机步骤）、验证记录汇总（各 Scenario 证据指针）
