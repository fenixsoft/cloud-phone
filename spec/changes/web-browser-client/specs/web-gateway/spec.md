# web-gateway

## ADDED Requirements

### Requirement: 协议无关字节中继
网关 SHALL 在每条 WebSocket 连接与一条新建的 upstream TCP 连接之间做**纯字节双向搬运**：WS 二进制帧载荷按序写入 TCP，TCP 字节流按序封装为 WS 二进制帧下发。网关 MUST NOT 解析、改写、过滤、缓存合并任何消息语义（认证、会话、错误一律由源端协议层裁决）。

#### Scenario: 下行字节透传
- **WHEN** 脚本化假源端经 upstream TCP 发送一段任意字节流（含 0x02 视频记录与 0x01 控制记录）
- **THEN** 连接在该网关上的 WS 客户端按相同顺序收到逐字节一致的二进制帧

#### Scenario: 上行字节透传
- **WHEN** WS 客户端发送一段任意二进制帧
- **THEN** 假源端在对应 TCP 连接上按相同顺序收到逐字节一致的载荷

#### Scenario: 错误语义透传
- **WHEN** WS 客户端发出 token 错误的 HELLO，源端回 REJECT_BAD_TOKEN 并关闭 TCP
- **THEN** 网关将该拒绝消息字节原样送达 WS 客户端并随后关闭 WS（非 1000 正常码），全程未解析内容

### Requirement: 背压与不丢不乱
当 upstream TCP 写缓冲超过高水位时，网关 SHALL 暂停读取 WS（反之同理），保证任意方向**不丢字节、不乱序**；恢复后继续搬运。

#### Scenario: 慢消费者不丢字节
- **WHEN** 假源端暂停从 TCP 读取，WS 客户端连续推送 8 MB 二进制数据
- **THEN** 恢复读取后收到全部 8 MB 且顺序与发送一致，网关内存占用有界（不无限缓冲）

### Requirement: 同端口静态托管与 WS 升级
网关 SHALL 在单一 `GATEWAY_PORT` 上同时服务静态文件（浏览器构建产物，`/` 入口页）与 WebSocket 升级（路径 `/ws`）。

#### Scenario: 页面与通道同端口可用
- **WHEN** 以 HTTP GET `/` 访问网关，再以 WS 连接 `ws://<host>:<port>/ws`
- **THEN** 前者返回 200 与入口 HTML，后者升级成功并可完成一轮字节往返

### Requirement: 环境变量配置
网关 SHALL 经环境变量配置：`GATEWAY_PORT`（缺省 8090）、`UPSTREAM`（缺省 `127.0.0.1:22333`，格式 host:port）。

#### Scenario: 缺省与覆盖
- **WHEN** 不带环境变量启动网关，随后以 `GATEWAY_PORT=9000 UPSTREAM=10.0.0.5:22333` 再次启动
- **THEN** 首次监听 8090 且 upstream 为 127.0.0.1:22333，二次监听 9000 且 upstream 为 10.0.0.5:22333

### Requirement: 连接生命周期
网关 SHALL 为每条 WS 连接建立独立 upstream TCP 连接（WS 断开即关闭对应 TCP，TCP 断开即关闭对应 WS），MUST 支持多条 WS 并存且互不串扰。

#### Scenario: 并发连接互不串扰
- **WHEN** 两个 WS 客户端同时连接并各自收发数据
- **THEN** 各自的 upstream 连接独立收发，A 的字节不出现在 B 的通道
