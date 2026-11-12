# Web 网关（WS↔TCP 字节中继）

<!-- knowledge: capability=web-gateway rev=1
     sources=spec/changes/web-browser-client -->

云手机浏览器观看面的接入层：宿主机上的 Node.js 单进程网关，把浏览器 WebSocket 连接与源端 TCP 流记录通道做纯字节双向中继，并在同一端口托管浏览器端静态页面，使浏览器观看端零源端改动即可接入既有协议。

## 规则

- 协议无关纯字节中继：每条 WebSocket 连接对应一条新建的 upstream TCP 连接，WS 二进制帧载荷按序写入 TCP，TCP 字节流按序封装为 WS 二进制帧下发；网关不解析、不改写、不过滤、不缓存合并任何消息——认证、会话、错误一律由源端协议层裁决，源端拒绝（如 REJECT_BAD_TOKEN）并关闭 TCP 时，拒绝字节原样送达浏览器后网关随机关闭 WS（非 1000 正常码）。
- 背压与不丢不乱：任一方向写缓冲超过高水位即暂停读取对侧（TCP 慢则停读 WS，反之同理），恢复后继续搬运；任意方向不丢字节、不乱序，网关内存有界不无限缓冲。上游不可达时 WS 以 1011 关闭（WS_CLOSE_UPSTREAM_GONE）。
- 同端口双服务：单一 GATEWAY_PORT 同时服务静态文件与 WS 升级——`/ws` 路径走 WebSocket 升级，其余 GET/HEAD 路径全部静态托管浏览器端构建产物（`/` 为入口页）；浏览器同源连接，无 CORS、无第二隧道。
- env 配置：`GATEWAY_PORT`（缺省 8090）、`UPSTREAM`（缺省 `127.0.0.1:22333`，即 adb forward 口，真机 Wi-Fi 场景可配 LAN 地址）、`STATIC_DIR`（缺省 `../app/dist`）。
- 连接生命周期：WS 断开即关闭对应 TCP，TCP 断开即关闭对应 WS；多条 WS 并存互不串扰，各自独立 upstream 收发，A 的字节不出现在 B 的通道。
- 工程与部署形态：`client-web/gateway/` 独立 npm 工程，不进任何 Gradle 构建；Node ≥18，单文件 server.js，运行时唯一依赖 `ws`。宿主机以 systemd 常驻（cloudphone-web-gateway.service，Restart=always），经 frpc 新增 proxy 条目（remotePort → GATEWAY_PORT）单端口对外暴露；网关无 TLS 终结、无鉴权逻辑，传输保密性与 frp 通道同级，如需 HTTPS 在 frp/nginx 层终结后回源网关。

## 代码锚点

- 主实现：client-web/gateway/server.js
- 守卫测试：client-web/gateway/test/relay.test.js（假源端下上行/下行逐字节透传、拒绝字节原样送达后随关 WS、8MB 慢消费者背压不丢不乱、双 WS 并发不串扰、env 缺省与覆盖）
- 部署手册：docs/web-client.md（systemd 单元、frpc proxy 条目、交付自检表）

## 溯源

- 2026-11-12 web-browser-client（spec/changes/web-browser-client/）— 新建本页：确立网关协议无关字节中继、背压不丢不乱、同端口静态托管与 `/ws` 升级、env 配置、连接生命周期与 systemd/frp 部署形态规则。
