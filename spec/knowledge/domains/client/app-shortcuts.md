# 源端应用枚举与桌面快捷方式

<!-- knowledge: capability=app-shortcuts rev=2
     sources=spec/changes/client-ux-and-input-latency,spec/changes/client-ui-redesign -->

客户端远程打开源端应用的两条通路：会话内应用列表页（点「打开」即进单应用串流）与桌面快捷方式（点图标经 deeplink 自动连接直达）。

## 规则

- 应用枚举：客户端发送 AppListRequest，源端以 PackageManager 枚举带 LAUNCHER intent 的可启动应用，仅排除源端 App 自身，其余应用（含 cloudphone.loadgen 负载、第三方、系统应用）不做人工过滤；条目含合法包名与非空标签；仅会话在线时响应，1s 内回传。
- 应用列表页：自控制台「单应用」入口或首页在线源端卡「单应用」动作进入（均需先建会话），应用以磁贴网格呈现，每磁贴仅含应用图标（通用占位，不做真实图标提取）与应用名称（深色底白色文字），不显示包名；搜索框按名称实时过滤；点选磁贴打开，磁贴右上角图钉「加到桌面」。
- 「打开」：以 SINGLE_APP 模式发起该应用的 StartSession 并进入串流画面；源端为单应用不可用环境（模拟器镜像）时展示既有明确拒绝文案。
- 列表加载失败：AppListRequest 3s 无应答时展示重试入口与失败原因，不崩溃。
- 桌面快捷方式：经 ShortcutManager.requestPinShortcut 创建桌面图标；图标 Intent 为 `cloudphone://launch?host=&tcpPort=&udpPort=&backend=&token=&pkg=` deeplink（指向 ConnectActivity，exported）；点击后自动填充连接字段（并落档案）→ 静默自动连接 → 成功即以 SINGLE_APP 打开该应用，源端正常时 5s 内进入串流画面（真机口径）；连接失败携带原因（含目标应用名）回连接页，字段已自动填充便于重试。
- 快捷方式 id 形如 `launch-<host>-<port>-<pkg哈希>`，图标用客户端默认图标；同一源端同一应用重复创建生成独立可用图标（或第二次提示已存在）。
- Launcher 无钉选能力（requestPinShortcut 回调不支持）时 Toast「当前桌面不支持创建快捷方式」，不崩溃，应用列表「打开」始终可用。
- deeplink 中的 token 为设备本地私有面（launcher 数据）明文，风险量级与桌面图标等同；协议加密属后续里程碑范围。

## 代码锚点

- 主实现：client/app/src/main/kotlin/cloudphone/client/SourceAppsActivity.kt（应用列表页）
- 单源符号：client/app/src/main/kotlin/cloudphone/client/ConnectActivity.kt（launch deeplink 解析与自动连接）
- 消费方：client/app/src/main/kotlin/cloudphone/client/DecodeRenderActivity.kt（控制台「单应用」入口）、client/app/src/main/kotlin/cloudphone/client/ConnectActivity.kt（首页「单应用」卡片直达建连）、source/app/src/main/kotlin/cloudphone/source/StreamService.kt（AppListRequest handler）
- 守卫测试：scripts/applist_wire_probe.py（APP_LIST 线协议探针）

## 溯源

- 2026-11-09 client-ux-and-input-latency（spec/changes/client-ux-and-input-latency/）— 新建本页：确立枚举消息语义、应用列表页、快捷方式直达与钉选降级规则。
- 2026-11-10 client-ui-redesign（spec/changes/client-ui-redesign/）— 应用列表页呈现重写为磁贴口径（图标+名称、去包名、名称搜索过滤、图钉加桌面），入口改自控制台/首页单应用动作；枚举与快捷方式语义不变。
