# Design: client-ui-redesign

## Context

客户端（client/ 模块，Kotlin + Android View 体系，无 Compose）现有四屏：ConnectActivity（LinearLayout 表单，手输 host/token/端口/后端/档位）、DecodeRenderActivity + StatsView（控制台）、SourceAppsActivity（应用列表）、SessionDrawerActivity（会话抽屉）。既有能力接口（source-profiles 持久化、app-shortcuts 快捷方式、resolution-passthrough 档位下发、APP_LIST 枚举、统计上报）均已就绪并验收，本 SPEC 只重做 UI 呈现层，全部复用既有接口，不改协议/源端/transport。

设计基准：定稿 HTML 原型 `specs/client-ui-redesign/prototype/index.html`（plain 仿样，**视觉参照、无约束力**）——信息架构/状态流转以 spec.md Scenario 为准，视觉以原型色板/层次为参照，Android 侧用原生组件 idiomatic 落地。仓库无 DESIGN.md 设计宪法。

## Goals / Non-Goals

**Goals:**
- 连接首页：局域网 22333 自动发现（三态：扫描中/有发现/未发现）+ 档案卡列表 + 新建连接表单
- 控制台：全幅画面 + HUD 统计条 + 工具坞 + 虚拟导航键 + 会话抽屉
- 单应用：磁贴化列表（图标+名称、无包名、搜索过滤、图钉加桌面）+ 单应用画面模式徽章
- 设置页：六分组（外观/串流档位/传输偏好/触控输入/安全/关于），未实现项显式「预留」
- 统一视觉语言：深空黑 HUD 风（#0B0B10 底 / #3B82F6 强调 / 语义色 ok #34D399、warn #F59E0B、danger #EF4444；mono 仅数据读出）

**Non-Goals:**
- 不改协议消息、源端采集、传输后端逻辑
- 不实现真实应用图标（沿用 APP_LIST 现状，通用占位图标；M3 既有规划项）
- 不实现主题切换的实际多主题（外观分组 MVP 允许占位）
- 不引入 Jetpack Compose / 第三方 UI 库（保持 View 体系零新依赖）

## Decisions

1. **局域网发现 = 子网遍历 + TCP 22333 探测**（新 `LanScanner`）：从 `WifiManager`/`ConnectivityManager` 取本机 IP 与前缀（无 WiFi 信息时回退 192.168/10. 常见网段），并发探测网段 254 地址 × TCP 22333，connect 成功即判服务存在（不完成 A2A 握手，避免误触发源端会话状态），总时长上限 10s、单地址超时 300ms、线程池并发 64。**备选被拒**：UDP 广播发现（协议无该消息，改源端违约）；mDNS（源端未实现，超出无源端改动约束）。发现的源端信息**不自动写档案**（避免误报污染档案库），仅本次列表呈现；与既有档案按 host+port 匹配标注「已保存」。发现卡后端标识：匹配档案显示档案后端，未匹配的新发现源端显示默认 lan-direct（探测不握手，无法探测真实后端）。
2. **首页 = 单 Activity 多区块滚动（ScrollView + 动态子视图）**：ConnectActivity 重写为「顶栏 + 扫描区块 + 发现列表 + 档案列表 + 新建入口」单屏滚动结构，列表量级小（发现 ≤254 + 档案数十），用 ScrollView 内 LinearLayout 动态挂载 item 布局即可，不引入 androidx RecyclerView（保持零新依赖）；发现卡与档案卡同构布局（状态徽章/kv 信息行/动作脚注），手动保存卡与设备卡共用 item 布局、以 `isSaved` 区分动作集。ConnectActivity 置为 LAUNCHER 入口，MainActivity 退役删除（状态总览职责并入首页/控制台）。**备选被拒**：BottomNav 多页（原型定稿为单页滚动流，且区块少不值得导航框架）。
3. **新建连接 = DialogFragment 底部弹层表单**（窗口底部对齐 + 底部入位动画，平台 API；不引入 material，无 BottomSheetDialogFragment 依赖）：字段布局照 spec（地址独占行、TCP/UDP 同行、后端三选 seg 控件、token 密码态、高级折叠）；保存走既有 SourceProfiles 存储（`SourceProfiles` 仅新增手动来源标记字段——UDP 端口字段既有档案已含（SourceProfile.udpPort 必填持久化）；新字段读取侧给缺省值兼容读，同 encryptOn 先例）。
4. **控制台 = DecodeRenderActivity 内重构，不新增 Activity**：HUD 统计条改为 StatsView 深色 HUD 样式（数据源不变）；工具坞/导航键为画面上层固定 UI 层（工具坞可收起，避免遮挡触控区）；会话抽屉由 SessionDrawerActivity 迁移为同 Activity 内自绘滑出抽屉面板（FrameLayout 顶层容器 + 平移/边缘手势，平台 API；不引入 androidx DrawerLayout）（减少 Activity 栈跳转，保持既有会话生命周期调用不变）。
5. **单应用列表沿用 SourceAppsActivity 磁贴化**：GridView 磁贴（图标占位 + 名称白色 + 图钉角标；平台组件，零新依赖），名称过滤沿用既有列表数据在适配器层做 `filter`；单应用画面复用 DecodeRenderActivity 单应用模式，顶部加「单应用 · <名>」徽章（圆角背景 TextView）+ 退出按钮。
6. **设置 = 新 SettingsActivity + SharedPreferences**：非 PreferenceFragment（要自定义分组样式）；预留行统一「预留」徽章组件且点击无效；串流默认档位与端口偏好写入 SharedPreferences，新建连接表单与 LanScanner 读取。
7. **视觉令牌落 res/values**：色板落 colors.xml；字号/圆角/间距与 HUD 卡片样式族以 styles 内联 item 形式并入 themes.xml（不新增 dimens.xml），命名对齐原型锚点语义（`hud_*`、`dock_*`、`card_*`），全屏深色主题 `Theme.CloudPhone.Hud` 继承既有主题。

## Risks / Trade-offs

- [子网遍历探测耗电/耗时] → 10s 硬上限 + 进入首页仅自动扫一次，其余手动重试；探测纯 connect 无 payload
- [局域网误报（其它设备恰好监听 22333）] → 连接时仍走既有配对/token 校验，误报在连接阶段自然失败，卡片标注「待验证」不自动保存
- [工具坞遮挡画面触控] → 工具坞默认吸底可一键收起，收起态留把手；触控区坐标换算沿用既有渲染 surface 尺寸逻辑
- [工作区可能存在他人未提交改动（当前脏文件集中在 docs/、spec/ 等非 client 路径）] → 实现任务以合并后 HEAD 为基线，Deliverables 只声明本 SPEC 路径；提交纪律禁 `git add .`
- [老档案无来源标记字段] → SourceProfiles 读取侧缺省「手输」（optString 缺省读，同 encryptOn 兼容先例）；udpPort 字段既有档案均已持久化，无需迁移

## Migration Plan

纯客户端 UI 层替换：新 Activity/类新增、ConnectActivity/DecodeRenderActivity/SourceAppsActivity 原地重写布局与交互、SessionDrawerActivity 迁为抽屉组件后删除入口（Activity 保留一个版本以防深链，随后归档）。回滚 = revert 单一实现提交即可，无数据迁移（档案格式向后兼容）。

## Open Questions

- 无（视觉细节歧义以原型截图 shots/ 为参照，实现 Agent 可在锚点语义内自行 idiomatic 取舍）
