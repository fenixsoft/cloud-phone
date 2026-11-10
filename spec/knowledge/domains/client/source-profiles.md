# 源端档案持久化

<!-- knowledge: capability=source-profiles rev=2
     sources=spec/changes/client-ux-and-input-latency,spec/changes/client-ui-redesign -->

客户端侧的源端连接档案：连接成功自动保存/更新源端信息，重装或更换场景后从下拉一键回填，消除逐字段重输。

## 规则

- 自动保存时机：连接成功（HELLO 握手 ACCEPTED 且会话发起成功）时持久化本次连接参数——名称、host、TCP 端口、UDP 端口、传输后端、token、最后连接时间、来源标记（origin：manual=手输/表单保存，auto=连接成功自动入库；老档案缺该键按「手输」缺省兼容读）。
- 去重键为 host + TCP 端口 + 传输后端：同键更新既有档案（刷新 lastConnectedAt），不产生重复条目。
- 连接首页以档案卡片列表呈现已保存档案，卡片提供一键连接与删除（删除不影响其余档案，删除即持久化同步移除）；手动建档经首页「新建连接」底部表单保存，复用同一存储与去重键。
- 存储形态：SharedPreferences（MODE_PRIVATE）内 JSON 数组（org.json），单文件单类实现；档案 token 属敏感信息，仅存应用私有存储，不出现在日志或导出文件中。
- 系统备份通道：客户端 allowBackup=true，档案 prefs 文件纳入系统自动备份——设备备份传输可用时，卸载重装后档案自动恢复、可直接连接；无备份传输的环境卸载重装后档案丢失属预期降级，首次需重输。
- 不做云同步与多设备导入导出。
- `cloudphone://launch` deeplink 进入时同样落一份档案（复用同一保存通路）。

## 代码锚点

- 主实现：client/app/src/main/kotlin/cloudphone/client/SourceProfiles.kt（档案模型与存取器）
- 消费方：client/app/src/main/kotlin/cloudphone/client/ConnectActivity.kt（档案卡片连接/删除/deeplink 落档）、client/app/src/main/kotlin/cloudphone/client/ui/ManualConnectSheet.kt（手动建档保存）
- 守卫测试：无

## 溯源

- 2026-11-09 client-ux-and-input-latency（spec/changes/client-ux-and-input-latency/）— 新建本页：确立自动保存、同键去重、下拉回填/删除、私有存储与系统备份恢复通道规则。
- 2026-11-10 client-ui-redesign（spec/changes/client-ui-redesign/）— 档案消费界面改档案卡片口径（一键连接/删除），新增手动建档入口与来源标记字段（origin 缺省「手输」兼容读）。
