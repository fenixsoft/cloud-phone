# Tasks: client-ui-redesign

参照 `specs/client-ui-redesign/spec.md`（行为契约）与 `design.md`（技术决策）实现；视觉以 `specs/client-ui-redesign/prototype/`（index.html + shots/）为**视觉参照、无约束力**，布局/组件按 Android 原生 idiomatic 落地。基线：以合并后 HEAD 为准（工作区可能有他人未提交改动，勿裹挟）。

## Deliverables

```yaml
- path: client/app/src/main/kotlin/cloudphone/client/discovery/LanScanner.kt
  kind: new
- path: client/app/src/main/kotlin/cloudphone/client/SourceProfiles.kt
  kind: modify
- path: client/app/src/main/kotlin/cloudphone/client/MainActivity.kt
  kind: delete
- path: client/app/src/main/kotlin/cloudphone/client/ConnectActivity.kt
  kind: modify
- path: client/app/src/main/kotlin/cloudphone/client/DecodeRenderActivity.kt
  kind: modify
- path: client/app/src/main/kotlin/cloudphone/client/StatsView.kt
  kind: modify
- path: client/app/src/main/kotlin/cloudphone/client/SourceAppsActivity.kt
  kind: modify
- path: client/app/src/main/kotlin/cloudphone/client/SettingsActivity.kt
  kind: new
- path: client/app/src/main/kotlin/cloudphone/client/ui/ManualConnectSheet.kt
  kind: new
- path: client/app/src/main/res/layout/activity_connect.xml
  kind: new
- path: client/app/src/main/res/layout/activity_settings.xml
  kind: new
- path: client/app/src/main/res/layout/activity_source_apps.xml
  kind: new
- path: client/app/src/main/res/layout/item_device_card.xml
  kind: new
- path: client/app/src/main/res/layout/sheet_manual_connect.xml
  kind: new
- path: client/app/src/main/res/values/colors.xml
  kind: new
- path: client/app/src/main/res/values/themes.xml
  kind: new
- path: client/app/src/main/AndroidManifest.xml
  kind: modify
```

## 1. 设计令牌与基础设施

- [x] 1.1 定义 HUD 视觉令牌：res/values/colors.xml 写入深空黑色板（bg #0B0B10、card #1E1E23、text #F8FAFC、dim #94A3B8、faint #64748B、border #1E293B、accent #3B82F6、ok #34D399、warn #F59E0B、danger #EF4444、on-accent #04101F）与 themes.xml 新增 `Theme.CloudPhone.Hud` 深色主题及卡片/徽章/按钮样式族（字号/圆角/间距以样式内联 item 承载，不新增 dimens.xml；命名对齐 card_*/hud_*/badge_*）
- [x] 1.2 实现 `LanScanner`（client/app/src/main/kotlin/cloudphone/client/discovery/LanScanner.kt）：取本机 IP/前缀（无 WiFi 信息回退 192.168/10. 常见网段），并发 TCP connect 探测 :22333（单地址 300ms 超时、并发 64、总时长 ≤10s、结果按 host+port 去重，协程回调式 API）

## 2. 连接首页与手动建档

- [x] 2.1 重写连接首页 activity_connect.xml + ConnectActivity：顶栏（标题/设置入口/会话抽屉入口）+ 网络状态徽章 + 扫描面板（扫描中进度/重试按钮）+ 发现列表 + 档案列表 + 新建入口，单屏滚动结构（ScrollView+动态子视图挂载 item_device_card.xml，发现卡与档案卡同构、isSaved 区分动作集；零新依赖，不引入 androidx RecyclerView）；AndroidManifest.xml 将 ConnectActivity 置为 LAUNCHER 入口并移除 MainActivity 注册（MainActivity.kt 删除，状态总览职责并入首页/控制台）
- [x] 2.2 接入 LanScanner 三态：进入首页自动扫描一次，扫描中显示进度计数，完成呈现发现卡片（名称/在线徽章/IP:22333/后端/上次连接信息）或未发现空态（原因说明 + 手动新建 + 重试扫描）；无响应档案卡弱化并禁用连接按钮；发现结果与既有档案按 host+port 匹配标「已保存」、不自动写档案
- [x] 2.3 实现新建连接底部表单 sheet_manual_connect.xml + ManualConnectSheet.kt（DialogFragment 底部弹层，平台 API）：名称（可选）/主机地址独占一行/TCP+UDP 同行默认 22333/22334/后端三选 lan-direct 默认/token 密码态/高级折叠（分辨率默认跟随本机、帧率 60、码率 8、观看模式整屏镜像）；地址为空提交给「请填写主机地址」提示；保存写入 SourceProfiles 并即时回显档案卡（含删除动作、重启后仍在）
- [x] 2.4 扩展 SourceProfiles.kt：仅新增手动来源标记字段（UDP 端口字段既有档案已含；读取侧来源标记缺省「手输」、udpPort 缺省 22334 兜底，不迁移存储格式），暴露档案列表增删改查与按 host+port 匹配接口供首页/表单复用

## 3. 控制台重构

- [x] 3.1 重构 DecodeRenderActivity 为全幅控制台：画面全幅 + 顶部 HUD 统计条（StatsView.kt 改深色 HUD 样式，FPS/码率/RTT/丢包实时读数沿用既有 STATS 上报，分辨率读数取解码输出 MediaFormat——STATS 无分辨率字段）+ 底部工具坞（键盘/截屏/档位循环 1080P·60→720P·60→1080P·30→自定义/旋转（本地横竖屏切换）/单应用入口/断开，可收起留把手）+ Android 虚拟导航键 BACK（经既有 INPUT_EVENT InputAction.BACK 注入；HOME/RECENTS 协议无对应消息，预留占位提示）
- [x] 3.2 会话抽屉迁移：SessionDrawerActivity 语义迁移为 DecodeRenderActivity 内自绘滑出抽屉面板（FrameLayout 顶层容器 + 平移手势，零新依赖）（挂起/恢复/断开），会话生命周期调用保持既有接口不变
- [x] 3.3 档位切换接线：工具坞档位点击按 resolution-passthrough 参数语义以新档位重发整屏 START_SESSION（控制链路不断开；源端按新参重建采集/编码管线后 IDR 恢复——既有语义同会话内不改参数，不实现原地改参），HUD 分辨率读数随源端实际输出更新；截屏保存本地并 toast 确认

## 4. 单应用与设置

- [x] 4.1 磁贴化 SourceAppsActivity（activity_source_apps.xml）：GridView 磁贴（占位图标 + 名称白色文字，无包名行，右上角图钉 = 既有 app-shortcuts 创建桌面快捷方式），顶部搜索框按名称实时过滤；卡片「单应用」动作建立连接后直达本页
- [x] 4.2 单应用画面徽章：DecodeRenderActivity 单应用模式顶部显示「单应用 · <应用名>」徽章（圆角背景 TextView）+ 一键退出（结束单应用会话返回系统画面、会话保持）
- [x] 4.3 新增设置页 SettingsActivity.kt（client/app/src/main/kotlin/cloudphone/client/SettingsActivity.kt）+ activity_settings.xml：六分组（外观占位/串流档位默认值/传输偏好默认端口 22333/触控输入占位/安全预留/关于版本号 + 检查更新预留），未实现行统一「预留」徽章且点击无效；设置项写 SharedPreferences，新建表单高级默认值与 LanScanner 端口读取该偏好；AndroidManifest.xml 注册 SettingsActivity 并在首页顶栏接入入口

## 5. 验证与收尾

- [x] 5.1 构建与静态验证：`./gradlew :client:assembleDebug` 编译通过；lint 无新增错误；新旧档案兼容（老 JSON 缺字段默认值读取）
- [ ] 5.2 行为自验（模拟器或 Robolectric 可覆盖范围）：首页三态流转、空态引导、手动建档保存回显与删除、卡片连接/单应用动作触发、控制台 HUD 数值刷新、工具坞档位循环与收起、导航键注入调用、单应用过滤与徽章退出、设置持久化——逐条对照 spec.md Scenario 输出 verification-report.md（含证据）
