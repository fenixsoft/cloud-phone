# Proposal: client-ux-and-input-latency

## Why

M0 双模拟器验收通过、真机首测暴露四类体验/质量问题：源端连接信息每次重装客户端都要重输；串流分辨率写死在源端默认档，与客户端屏幕不匹配；想快速打开源端某个应用必须手动连接+切模式；触摸操作存在「好几秒才有一次反应」的严重延迟（静屏场景尤甚）。本 SPEC 一并解决，使真机日常可用。

## What Changes

- 客户端新增「源端档案」持久化：连接成功自动保存/更新源端信息（host/端口/后端/token），连接页下拉选择历史源端一键填充，支持删除
- 客户端可向源端传递串流分辨率档位：默认「跟随本机分辨率」（客户端当前屏幕实际尺寸），可选 720p/1080p/自定义宽高；帧率与码率同样可调（协议 StartSession 已携带四元组，无需改协议；源端 aspect-exact 适配逻辑已就绪）
- 协议新增 APP_LIST 请求/应答消息：源端枚举本机可启动应用（包名+标签）回传；客户端新增应用列表页，每个应用可「立即打开」（单应用会话）或「创建桌面快捷方式」（ShortcutManager requestPinShortcut，点桌面图标直达 cloudphone://launch deeplink 自动连接并打开该应用）
- 触摸响应迟缓根因定位与修复：插桩「触摸注入→源屏合成→采集出帧→客户端解码渲染」全链时间戳量化各段延迟；实施修复（输入触发帧率提升/渲染 latest-wins 兜底等，以测量结论为准）；验收口径：模拟器触摸→视觉变化 P95 ≤ 800ms，真机目标 ≤ 300ms（写入 device-notes）

## Capabilities

### New Capabilities

- `source-profiles`：客户端源端档案持久化与连接页集成
- `resolution-passthrough`：客户端向源端传递分辨率/帧率/码率档位
- `app-shortcuts`：源端应用枚举、客户端应用列表与桌面快捷方式直达
- `touch-latency-fix`：触摸响应全链延迟插桩、根因定位与修复

### Modified Capabilities

- 无（stream-protocol-core / client-decode-render 等既有能力按其 spec 内 Scenario 自然扩展：APP_LIST 消息在 protocol.md 增补，触摸修复不改变既有验收阈值）

## Impact

- **影响代码面**：client（ConnectActivity/新 SourceProfiles/新 SourceAppsActivity/DecodeRenderActivity/Manifest）、source（StreamService/ScreenCapture 或 EncoderPipeline）、protocol（Messages.kt 新消息）、scripts/measure_latency.py、docs（protocol.md/device-notes.md）
- **兼容性**：协议新增消息为可选能力（老客户端不发 APP_LIST 不受影响），无 BREAKING
- **风险**：requestPinShortcut 依赖 Launcher 支持钉选（主流 Launcher 均支持，不支持的设备降级为应用内长按入口提示）；触摸延迟根因若部分来自模拟器 swiftshader 合成慢，则模拟器验收口径放宽、真机目标另行约定（design.md 风险段展开）
- **真机依赖**：快捷方式「立即打开」走单应用模式，模拟器镜像级限制（app_process CREATE 被静默 SIGKILL）下明确拒绝回传，真机验证步骤沿用 docs/device-notes.md §2.1/§4 手册
