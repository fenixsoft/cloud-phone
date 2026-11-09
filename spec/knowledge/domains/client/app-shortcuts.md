# 源端应用枚举与桌面快捷方式

<!-- knowledge: capability=app-shortcuts rev=1
     sources=spec/changes/client-ux-and-input-latency -->

客户端远程打开源端应用的两条通路：会话内应用列表页（点「打开」即进单应用串流）与桌面快捷方式（点图标经 deeplink 自动连接直达）。

## 规则

- 应用枚举：客户端发送 AppListRequest，源端以 PackageManager 枚举带 LAUNCHER intent 的可启动应用，仅排除源端 App 自身，其余应用（含 cloudphone.loadgen 负载、第三方、系统应用）不做人工过滤；条目含合法包名与非空标签；仅会话在线时响应，1s 内回传。
- 应用列表页：会话在线时从会话抽屉进入，展示标签+包名（图标省略，不做应用图标提取）；每项提供「打开」与「加到桌面」两个操作。
- 「打开」：以 SINGLE_APP 模式发起该应用的 StartSession 并进入串流画面；源端为单应用不可用环境（模拟器镜像）时展示既有明确拒绝文案。
- 列表加载失败：AppListRequest 3s 无应答时展示重试入口与失败原因，不崩溃。
- 桌面快捷方式：经 ShortcutManager.requestPinShortcut 创建桌面图标；图标 Intent 为 `cloudphone://launch?host=&tcpPort=&udpPort=&backend=&token=&pkg=` deeplink（指向 ConnectActivity，exported）；点击后自动填充连接字段（并落档案）→ 静默自动连接 → 成功即以 SINGLE_APP 打开该应用，源端正常时 5s 内进入串流画面（真机口径）；连接失败携带原因（含目标应用名）回连接页，字段已自动填充便于重试。
- 快捷方式 id 形如 `launch-<host>-<port>-<pkg哈希>`，图标用客户端默认图标；同一源端同一应用重复创建生成独立可用图标（或第二次提示已存在）。
- Launcher 无钉选能力（requestPinShortcut 回调不支持）时 Toast「当前桌面不支持创建快捷方式」，不崩溃，应用列表「打开」始终可用。
- deeplink 中的 token 为设备本地私有面（launcher 数据）明文，风险量级与桌面图标等同；协议加密属后续里程碑范围。

## 代码锚点

- 主实现：client/app/src/main/kotlin/cloudphone/client/SourceAppsActivity.kt（应用列表页）
- 单源符号：client/app/src/main/kotlin/cloudphone/client/ConnectActivity.kt（launch deeplink 解析与自动连接）
- 消费方：client/app/src/main/kotlin/cloudphone/client/SessionDrawerActivity.kt（「源端应用」入口）、source/app/src/main/kotlin/cloudphone/source/StreamService.kt（AppListRequest handler）
- 守卫测试：scripts/applist_wire_probe.py（APP_LIST 线协议探针）

## 溯源

- 2026-11-09 client-ux-and-input-latency（spec/changes/client-ux-and-input-latency/）— 新建本页：确立枚举消息语义、应用列表页、快捷方式直达与钉选降级规则。
