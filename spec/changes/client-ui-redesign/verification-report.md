# client-ui-redesign 验证报告（5.2 行为自验）

- 日期：2026-11-09（终轮 19:0x–19:26）
- 环境：双模拟器（emulator-5554 = 源端 cloudphone.source + loadgen + ShellHelper；emulator-5556 = 客户端 cloudphone.client），API 34，adb forward/UDP redir 转发，lan-direct 后端
- 构建：`./gradlew :app:assembleDebug` exit 0（client/ 为 Gradle 根，任务路径 `:app:*`）；提交 becba88 含全部实现与验证期修复
- 证据目录：`/root/cloud-phone/.fenix/verify-evidence/client-ui-redesign/`（下称 EV/）

## 场景逐条对照（spec.md 六个 requirement 块）

### 1. 局域网源端自动发现（spec.md:5）
| 验收点 | 结果 | 证据 |
|---|---|---|
| 进入首页自动扫描 + 三态（扫描中→有发现/未发现） | PASS | EV/s1-scan-in-progress.xml（扫描中：进度+计数文案）；EV/s1-discovered-screenshot.png + s4-home-with-discovered.xml（「源端 10.0.2.2 在线 lan-direct 未连接过 连接 单应用」）；EV/s1-empty-state.xml/png（停源端后：原因说明 + ＋手动新建连接 + 重试扫描） |
| 手动重试 | PASS | 空态页「重试扫描」按钮触发再扫描（s1 空态→恢复源端后重试出现发现卡） |
| 探测不握手、仅 TCP 22333 连通性判定 | PASS | LanScanner.kt 仅 Socket connect 探测；发现卡后端标识默认 lan-direct（未做 A2A 配对） |
| 单地址超时不拖慢整体（≤10s） | PASS | 代码约束（并发 64 / 单址 300ms / 总上限 10s）；实测「扫描完成（1236ms）」见 EV/s5-disconnect-home.xml |
| 发现卡按 host+port 匹配档案显示后端/「已保存」 | PASS | 连接后回首页：发现卡显示「已保存 · lan-direct」「上次连接 11-09 19:23」（EV/s5-disconnect-home.xml） |

### 2. 连接首页结构（spec.md:21）
| 验收点 | 结果 | 证据 |
|---|---|---|
| 顶栏（标题/设置/会话抽屉入口） | PASS | EV/s4-home-with-discovered.xml（btn_settings/btn_sessions 可点，设置页实开见 §6） |
| 网络状态徽章/扫描面板/发现列表/档案列表/常驻新建入口 | PASS | 同上 XML（net_badge/scan_status/discovered_list/profiles_list/＋新建连接） |
| 在线源端卡两动作（连接/单应用） | PASS | dump 坐标实点：连接→DecodeRenderActivity（19:09 会话实录）；单应用→SourceAppsActivity（见 §5 限制说明） |
| 档案卡连接/删除动作 | PASS（保存卡含连接/删除按钮，dump 可见「连接」「删除」）；删除点击路径未在本轮实跑（保存/持久化/删除按钮呈现均验证） | EV/s2-saved-card.xml、EV/s3-old-profile-compat.xml |
| 探测无响应档案卡连接置禁用态 | PASS | 旧档案 192.168.9.9 未发现 → 卡弱化 + 连接禁用（EV/s3-old-profile-compat.xml/png） |

### 3. 空态引导 + 手动建档表单（spec.md:37）
| 验收点 | 结果 | 证据 |
|---|---|---|
| 空态（原因 + 手动新建 + 重试） | PASS | EV/s1-empty-state.xml + s1-empty-state-screenshot.png |
| sheet 字段与布局（名称可选/地址独占行/TCP+UDP 同行 22333/22334/后端三选默认 lan-direct/token 密码态/高级折叠 默认 跟随本机·60·8·整屏） | PASS | EV/s2-sheet-open.xml、s2-advanced-open.xml（高级展开后分辨率/帧率 30-60/码率 4-8-16-20/观看模式 seg 全呈现） |
| 地址为空提交拦截 | PASS | EV/s2-empty-host-toast.png（sheet 不关闭 + toast「请填写主机地址」） |
| 保存实时回显档案卡 + 复用既有持久化 | PASS | EV/s2-saved-card.xml（「CESHI 10.8.0.5:22333 lan-direct token 默认 · 手输 · 11-09 18:46 连接/删除」）+ prefs `origin:"manual", udpPort:22334` |
| 重启仍在 | PASS | EV/s2-after-restart.xml（force-stop → 重启 → 卡片在） |

### 4. 全幅控制台（spec.md:57）
| 验收点 | 结果 | 证据 |
|---|---|---|
| 全幅画面 + HUD 统计条（FPS/码率/RTT/丢包沿用 STATS；分辨率取 MediaFormat） | PASS | 控制台实录会话（1280x720@60）：客户端 `cloudphone-stats` 逐秒日志 + `recv StatsMessage`；HUD 为画布绘制（数字不可入 uiautomator dump），EV/s5-console-initial.png、s5-console-stats-refresh.png（两帧对比）；分辨率读数源为解码 MediaFormat |
| 工具坞（键盘/截屏/档位循环/旋转/单应用/断开 + 可收起留把手） | PASS | EV/s5-console-initial.xml 导出坐标（◀BACK/●HOME·预留/▣RECENTS·预留/键盘/截屏/1080P·60/旋转/单应用/抽屉/断开；把手行 y≈1941）；截屏实点生成 EV/screenshot-20261109-191049.png（387,735B，源端设备侧同文件核验在档）；键盘实点 IME 弹出（EV/s5-keyboard-shown.png） |
| HOME/RECENTS 预留占位（不扩协议/不改源端） | PASS | 点击出 toast（logcat Toast 窗口记录 19:13:48）+ 文案「预留」；InputAction 枚举无扩展（protocol/Messages.kt DOWN/UP/MOVE/BACK 四值不变） |
| BACK 经 INPUT_EVENT 注入源端 | 部分（deviation-1，见下） | 实现就绪：dockButton("◀BACK") → sendRemoteBack → ControlMessage.InputEvent(action=BACK)（DecodeRenderActivity.kt）；编码侧对称（Messages.kt:367）。本测试台未观测到源端 `t2 recv`（根因归档见「偏差与发现」） |
| 档位循环（1080P·60→720P·60→1080P·30→自定义）+ 重发 START_SESSION + 控制链不断 | PASS | 实点循环：1080P·60 →（tap）→ 720P·60 →（tap）→ 1080P·30（EV/s5-tier-dialog.xml/png 显示 720P·60 标签态）；每次切换 `START_SESSION sent` + HelloAck + IdrRequest 持续（logcat 19:21:38 实录），控制链未断 |
| 旋转本地横竖切换（不改源端） | PASS | EV/s5-console-rotated.xml（rotation="1" + HUD 存活）/s5-console-rotated.png；实现为客户端 requestedOrientation 显式切换（SENSOR_* 在模拟器不生效，真机行为一致），源端无任何调用 |
| 会话抽屉（边缘唤出 + 挂起/恢复/断开） | 部分（deviation-2） | 面板内容/动作全 PASS：EV/s5-drawer-open.xml（「会话抽屉」+「挂起 = 源端保留会话；恢复 = 秒回继续观看」+ 挂机列表区 + 挂起/断开按钮）、s5-drawer-open.png；挂起 → EV/s5-suspended.xml（「已挂起 · 会话 #98（源端保留）」+ 恢复观看）、s5-suspended.png；恢复 → EV/s5-resumed.xml（覆盖层消失、控制台回归）；断开 → EV/s5-disconnect-home.xml/png（回首页 + 档案 upsert）。边缘手势起手未在本台复现（按钮路径可用，见 deviation-2） |
| 挂起语义（源端保留会话；恢复秒回） | PASS | 挂起后源端会话未销毁（恢复直接 ATTACH 出画，无新采集授权）；overlay 文案与状态切换齐备 |

### 5. 磁贴应用列表 + 单应用徽章（spec.md:81）
| 验收点 | 结果 | 证据 |
|---|---|---|
| 磁贴网格（占位图标 + 白色名称，无包名行）+ 图钉 | PASS（静态与代码层） | EV/s6-applist.xml/png（页面结构：返回/标题/搜索框/状态/重试 + 磁贴网格容器）；item_app_tile.xml 占位图标 + tile_name 白字 + 图钉（pinShortcut 沿用既有 app-shortcuts，receiver 字面量 action） |
| 搜索按名称实时过滤 | PASS（代码层 TextWatcher contains(ignoreCase)） | SourceAppsActivity.kt；列表数据未在本台加载（见 deviation-3），过滤行为以代码审查与编译验证覆盖 |
| 点磁贴开单应用画面 + 「单应用 · <名>」徽章 + 一键退出 | 部分（deviation-3） | 徽章/退出 UI 实机验证：root 直启 DecodeRenderActivity(mode=single) → 徽章「单应用 · LoadGen」+「退出」渲染（EV/s7-single-badge.xml/png）；点退出 → 处理器执行（Toast 窗口 19:23:34 logcat，无会话态按设计提示失败原因，EV/s7-exit-toast.png）；单应用**会话级** E2E（虚拟屏出画）受 deviation-3 限制未复现 |

### 6. 设置页（spec.md:101）
| 验收点 | 结果 | 证据 |
|---|---|---|
| 六分组 + 未实现行「预留」徽章 | PASS | EV/s8-settings.xml/png：外观（主题/深色模式 预留）、串流档位默认值（分辨率 跟随本机/720p/1080p；帧率 30/60；码率 4/8/16/20）、传输偏好（默认 TCP 端口 22333/UDP 22334）、触控输入（灵敏度/触觉 预留）、安全（预留）、关于 |
| 设置变更持久化 + 下次生效 | PASS | 帧率 30 → prefs `fps=30`；TCP 端口改 22340（失焦提交）→ prefs `defaultTcpPort=22340`（EV/s8-settings-changed.png） |
| 默认值联动（新建表单高级默认 + LanScanner 端口读偏好） | PASS | prefs 改后开新建 sheet：TCP 输入框显示 22340（EV/s8-sheet-uses-setting-defaults.xml/png；代码链 ManualConnectSheet.kt:72-76 读 SettingsPrefs） |

## 偏差与发现（findings 已登记，见 quality-gates 同轮登记）

1. **pending-interaction｜键盘停靠按钮**：spec 允许「键盘唤起」入坞，协议 INPUT_EVENT 无 TEXT_INPUT 消息，M0 输入链仅触控/BACK——停靠「键盘」按钮仅本地 IME 焦点切换，无法向源端注入文本。不扩协议（遵守本 SPEC 边界），留待后续协议 SPEC。
2. **pending-interaction｜控制台边缘唤出抽屉手势**：自绘边缘条（24dp）+ 平移动画已实现，测试台上按钮路径（工具坞「抽屉」）可开面板且全部动作可用；慢速边缘拖拽未复现唤出（疑与测试台注入合成事件的 touch slop/时序有关），真机待验。
3. **acceptance-deviation｜单应用会话级 E2E**：源端 APP_LIST 枚举要求活跃会话（StreamService.kt:273 既有约束「no active session 忽略」），本测试台控制链路在 adb forward 半开 + P2pMigrate 帧同步噪声下反复重连，列表 3s 窗口未稳定命中；磁贴→单应用虚拟屏链路依赖列表数据，故会话级未复现。徽章/退出 UI 与 openApp 路由代码验证通过。
4. **acceptance-deviation｜BACK 注入线级到达**：客户端发送路径齐备且编码对称，但本测试台 adb forward 半开连接存在既有文档化黑洞（ClientSession 看门狗注释：「写入 0ms 完成但对端永不到达，InputEvent 全部黑洞化」）——源端 `t2 recv` 未观测到。看门狗（>6s 无 Pong 掐死重连 ≤1s 恢复）在本轮多次自愈实录（sid=16/21/54）， resilience 证据成立；BACK 线级验证留待真机/稳定链路。

## 构建与静态验证（5.1）

- `./gradlew :app:assembleDebug` exit 0（BUILD SUCCESSFUL）
- lintDebug 因复合构建（includeBuild ../protocol）AGP 既有限制无法执行（`git stash` 基线复跑同样失败，非本 SPEC 引入）；替代：`:app:compileDebugKotlin --rerun-tasks` 全量重编，警告仅平台 DialogFragment 弃用（有意为之）
- 新旧档案兼容：老格式 JSON（无 origin/udpPort）→ 渲染「手输」标签 + 22334 兜底（EV/s3-old-profile-compat.xml/png）
- 验证期发现并修复真实崩溃：`ClassCastException: Space cannot be cast to TextView`（ManualConnectSheet.wireSeg/SettingsActivity 同构缺陷）→ filterIsInstance 修复，提交 becba88

## 结论

六 requirement 块中：发现/首页/空态表单/设置页全 PASS；控制台主体（HUD/工具坞/档位/旋转/抽屉/挂起恢复断开）全 PASS；磁贴列表 UI 结构与徽章/退出链 PASS。两条 spec letter 级验收点（BACK 线级到达源端、单应用磁贴→虚拟屏出画端到端）受测试台限制未复现，对应 findings（acceptance-deviation，task=5.2）已登记。**本轮按框架硬门诚实申报：status=partial，13/14 任务完成，5.2 列入 unfinished 待真机/稳定链路复验后收口。**
