# source-profiles — 客户端源端档案持久化

## ADDED Requirements

### Requirement: 源端档案自动保存
连接成功（HELLO 握手 ACCEPTED 且会话发起成功）时，客户端 SHALL 将本次连接参数（名称、host、TCP 端口、UDP 端口、传输后端、token、最后连接时间）持久化到本地；同 host+TCP 端口+后端 已存在时 SHALL 更新该条而非新增。

#### Scenario: 首次连接成功后自动入库
- **WHEN** 用户手动输入 host=192.168.20.123:22333 token=cloudphone-m0 后端=lan-direct 并连接成功
- **THEN** 重启客户端 App 后连接页下拉中存在该源端档案，选中即可完整回填全部字段

#### Scenario: 重装客户端后一键恢复（系统自动备份通道）
- **WHEN** 客户端 allowBackup=true 且设备备份传输可用，用户卸载重装客户端后
- **THEN** 录入过的源端信息经系统自动备份恢复，在档案列表中可见、可选、可直接连接，无需逐字段重输（无备份传输的环境卸载重装后档案丢失属预期降级，首次需重输；不做云同步/多设备导入导出，见 design.md Non-Goals）

#### Scenario: 同源端重复连接不产生重复档案
- **WHEN** 对同一 host:port+后端 连续成功连接 3 次
- **THEN** 档案列表中该源端仅一条记录，lastConnectedAt 更新为最后一次

### Requirement: 连接页档案选择与删除
连接页 SHALL 提供已保存源端的下拉选择器，选中后自动填充全部连接字段；SHALL 提供删除入口移除指定档案。

#### Scenario: 下拉选择自动填充
- **WHEN** 档案列表有 2 条记录，用户在下拉中选择第二条
- **THEN** host/端口/token/后端输入区立即填充为该档案值

#### Scenario: 删除档案
- **WHEN** 用户删除任一档案
- **THEN** 该条从下拉与存储中消失，且不影响其余档案

### Requirement: 档案数据边界
档案 token 属敏感信息，SHALL 仅存于应用私有存储（SharedPreferences/MODE_PRIVATE），SHALL NOT 出现在日志或导出文件中。

#### Scenario: 私有存储隔离
- **WHEN** 检查应用私有目录外任何可见面（日志/备份面）
- **THEN** token 不以明文出现
