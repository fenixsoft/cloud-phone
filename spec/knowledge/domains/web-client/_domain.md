# web-client
> 最后更新：2026-11-12 23:16 UTC+8

浏览器观看端域：零安装的第三观看形态——宿主机 WS↔TCP 协议无关字节中继网关（同端口托管静态页、经 frp 单端口暴露）+ 浏览器端 WebCodecs 串流客户端（连接与会话编排、解码渲染、音频、输入回传、延迟 HUD），源端与既有工程零改动。

## 能力页

| 能力页 | 简介 | 更新时间 |
| --- | --- | --- |
| [Web 网关（WS↔TCP 字节中继）](web-gateway.md) | 协议无关纯字节双向中继、背压不丢不乱、同端口静态托管与 /ws 升级、env 配置与 systemd/frp 部署 | 2026-11-12 23:16 |
| [浏览器串流观看端](browser-streaming-client.md) | token 连接与 HELLO/START_SESSION 会话编排、ATTACH 断线重连、WebCodecs 解码渲染、音频播放与显式降级、输入映射与延迟 HUD | 2026-11-12 23:16 |
