# app-shortcuts — 源端应用枚举、应用列表与桌面快捷方式直达

## ADDED Requirements

### Requirement: APP_LIST 协议消息
协议 SHALL 新增 AppListRequest（客户端→源端）与 AppListMessage（源端→客户端，含 AppEntry{packageName,label} 列表）两条控制消息；源端 SHALL 以 PackageManager 枚举带 LAUNCHER intent 的可启动应用，仅排除源端 App 自身；其余应用（含 cloudphone.loadgen 游戏负载、第三方与系统应用）不做人工过滤。

#### Scenario: 源端枚举回传
- **WHEN** 客户端发送 AppListRequest 且会话在线
- **THEN** 1s 内收到 AppListMessage，列表含源端已安装的可启动应用（≥游戏负载 App），条目含合法包名与非空标签

#### Scenario: 老客户端兼容
- **WHEN** 连接方为不含 APP_LIST 的旧客户端
- **THEN** 不发送该消息即不受影响，源端不主动推送（请求-应答制，无 BREAKING）

### Requirement: 客户端应用列表页
会话在线时客户端 SHALL 提供应用列表入口（会话抽屉页内），展示源端应用（图标可省略，标签+包名），每项提供「打开」与「加到桌面」两个操作。

#### Scenario: 打开单应用
- **WHEN** 用户在应用列表点击「打开」某应用
- **THEN** 客户端以 SINGLE_APP 模式发起该应用的 StartSession 并进入串流画面；源端为单应用不可用环境（模拟器镜像）时展示既有明确拒绝文案

#### Scenario: 列表加载失败
- **WHEN** AppListRequest 3s 无应答
- **THEN** 列表页展示重试入口与失败原因，不崩溃

### Requirement: 桌面快捷方式直达
「加到桌面」SHALL 调用 ShortcutManager.requestPinShortcut 创建桌面图标；图标点击 SHALL 通过 cloudphone://launch?host=&tcpPort=&udpPort=&backend=&token=&pkg= deeplink 进入客户端，自动填充档案并自动连接，成功后直接以 SINGLE_APP 模式打开该应用；连接失败 SHALL 携带原因回到连接页。

#### Scenario: 桌面图标一键直达
- **WHEN** 用户已为源端某应用创建快捷方式，随后从桌面点击该图标
- **THEN** 客户端自动完成连接与单应用会话发起，无需任何手动输入；源端正常可用时 5s 内进入该应用串流画面（真机口径；模拟器因镜像级限制单应用不可用——device-notes §2.1——此时仅验收「失败带原因回连接页」回退路径）

#### Scenario: 快捷方式指向的源端不可达
- **WHEN** 点击桌面图标但源端离线
- **THEN** 客户端回退到连接页并展示失败原因（含目标应用名），档案字段已自动填充便于重试

#### Scenario: 重复创建快捷方式
- **WHEN** 对同一源端同一应用两次「加到桌面」
- **THEN** 生成两个独立桌面图标均可正常工作（或第二次提示已存在，任一行为可接受，以实现为准）

### Requirement: Launcher 不支持钉选的降级
requestPinShortcut 返回不支持（Launcher 无钉选能力）时，客户端 SHALL 提示「当前桌面不支持创建快捷方式」，不崩溃。

#### Scenario: 钉选被拒
- **WHEN** Launcher 拒绝钉选回调
- **THEN** 列表页 Toast 明确提示，应用列表仍可正常使用
