# Proposal: client-ui-redesign

## Why

现有客户端 UI 为粗糙的 LinearLayout 表单页（ConnectActivity 手输 host/token/端口/后端/档位），信息拥挤、无连接发现、无设置入口，与「云手机遥控器」的产品定位不匹配。经 ui-ux-pro-max 设计候选 + 单文件 HTML 原型两轮批注定稿（用户已明示认可），本 SPEC 按定稿原型重构客户端全部四屏界面，使真机日常可用性与可演示性达标。

## What Changes

- **连接首页（新）**：进入自动扫描同局域网子网并探测源端默认 TCP 22333 端口，发现的源端以卡片列表呈现（在线/无响应状态徽章、地址、传输后端、上次连接信息），支持重试扫描；扫描中/未发现两态完整
- **手动新建连接（新）**：未发现（公网/热点）时空态引导；常驻「新建连接」底部表单（连接名称、主机地址独占一行、TCP/UDP 端口同行、传输后端 lan-direct/p2p/frp 三选、配对 token、高级选项：分辨率/帧率/码率/观看模式），保存即追加为可一键连接/可删除的档案卡片（与既有 source-profiles 能力衔接，持久化字段复用其存储）
- **控制台（重构）**：已连接后进入全幅画面控制台：顶部 HUD 统计条（FPS/码率/RTT/丢包/分辨率，复用既有 StatsView 数据源）、底部工具坞（键盘/截屏/串流档位切换/旋转/单应用入口/断开）+ Android 虚拟导航键（BACK 经既有 INPUT_EVENT 通道注入；HOME/RECENTS 协议无对应消息，预留占位）、会话抽屉（挂起/恢复/断开，衔接既有会话生命周期语义）
- **单应用功能（重构）**：控制台可进入单应用列表页（图标+名称磁贴、右上角图钉 = 加到桌面，复用既有 app-shortcuts 能力），点选磁贴打开单应用画面并显示模式徽章（一键退出回系统画面）
- **设置页（新，预留）**：外观/串流档位默认值/传输偏好（默认端口 22333）/触控输入/安全（预留占位）/关于（版本号）；首页右上角常驻入口
- **视觉语言（视觉参照）**：深空黑底 HUD/Sci-Fi 风格（#0B0B10 底、#3B82F6 发射蓝、信号绿/警戒红语义色、mono 仅用于数据读出）；定稿原型为纯 HTML/CSS 仿样，**视觉参照、无约束力**——Android 侧用原生组件 idiomatic 落地，信息架构/状态流转/字段集合以 spec Scenario 为准

## Capabilities

### New Capabilities

- `client-ui-redesign`：客户端界面壳重构——局域网源端发现、连接首页、手动建档、控制台 HUD 与工具坞、单应用列表、设置页

### Modified Capabilities

- 无（source-profiles / app-shortcuts / client-decode-render / resolution-passthrough 等既有能力的 spec 级行为不变：本 SPEC 只重做其 UI 呈现层并复用其既有接口——档案存取、APP_LIST 枚举、快捷方式创建、档位下发、统计上报均按既有能力页现行规则调用）

## Impact

- **影响代码面**：client 模块——ConnectActivity（首页重写、置为 LAUNCHER 入口）、MainActivity（退役删除，入口职责并入首页）、SourceProfiles（字段/列表接口扩展）、DecodeRenderActivity（控制台 HUD/工具坞/导航键/抽屉）、StatsView（HUD 样式）、SourceAppsActivity（磁贴化）、新增 LanScanner（子网 22333 探测）、新增 SettingsActivity、res/layout 全套重写、res/values 主题色板、AndroidManifest（新 Activity 注册）
- **兼容性**：无协议改动、无源端改动、无 BREAKING；档案存储沿用 source-profiles 既有持久化（老档案照常回填）
- **不触碰**：protocol / source / transport 模块零改动；工作区他人未提交改动不由本 SPEC 的 Deliverables 判定覆盖——实现分支以合并后 HEAD 为基线
- **界面原型（视觉参照、无约束力）**：`specs/client-ui-redesign/prototype/index.html`（元素 id 锚点标注 + 6 屏整页截图 shots/）；纯 HTML/CSS 仿样，不构成 UI 真相源绑定，布局与组件选型以 Android 原生 idiomatic 实现为准，信息架构与行为以 spec.md Scenario 为准；仓库无 DESIGN.md 设计宪法，视觉基准即该原型定稿
