# Proposal: web-browser-client

## Why

机主与少数人需要在**浏览器**（PC/平板/手机）直接操作家中云手机上的某个特定 APP（延迟敏感、画面持续运动）。现有 Android 与 Windows 客户端都要求安装，浏览器是零安装、随手可及的第三种观看端形态；云星穹铁道/云原神已证明 WebRTC 浏览器串流可行，本项目协议（TCP 流记录 + H.264/AAC）经 WebCodecs/AudioDecoder 即可在浏览器消费，网关作为协议无关字节中继即可接入，源端零改动。

## What Changes

- 新增 `client-web/gateway/`：Node.js 单文件 WebSocket↔TCP 字节中继网关（协议无关，带背压），同端口托管浏览器静态页与 `/ws`，单端口经 frp 暴露。
- 新增 `client-web/app/`：vanilla TypeScript 浏览器客户端——逐字节复刻 `docs/protocol.md`（HELLO 认证、START_SESSION 会话、APP_LIST、INPUT_EVENT、AUDIO_CHUNK、剪贴板/TEXT_INPUT、STATS/PING/PONG、ATTACH 重连），WebCodecs 解码 canvas 渲染、AudioDecoder+AudioWorklet 播放、指针输入映射、延迟 HUD（RTT/管线延迟/丢帧）。
- 修正 `docs/protocol.md` 陈旧描述（追加浏览器对接注记）：§4 附着时序（ATTACH = START_SESSION）、控制方向表（源端→客户端 1Hz PING/STATS）、§3.1 补记 HELLO 可选 takeover 尾字节、§5 REJECT_BUSY 双来源语义（传输层观看端占用闸门 + 会话层 ATTACH 恢复失败）。
- 新增部署与使用文档（网关 systemd/frp proxy、token 书签交付）。
- **零改动**：`source/`、`protocol/`、`client/`、`client-windows/` 既有代码不受影响（ADR-011"客户端新形态零协议 fork、既有工程零改动"纪律的浏览器版）。

## Capabilities

### New Capabilities

- `web-gateway`: 协议无关 WebSocket↔TCP 字节中继网关——双向搬运带背压、同端口静态托管与 WS 升级、env 配置（GATEWAY_PORT/UPSTREAM）、上游断开/拒绝语义透传（网关不解析任何消息）。
- `browser-streaming-client`: 浏览器观看端——token 连接与会话编排（HELLO→START_SESSION→HELLO_ACK 真实参数、切换换 sessionId 重置坐标、ATTACH 重连≤30s）、WebCodecs 解码渲染、AAC 音频播放与显式降级、指针/BACK/文本/剪贴板输入、PING/PONG 时钟与延迟 HUD、档位选择、断线自动重连。

### Modified Capabilities

（无——`docs/protocol.md` 勘误为文档修正，不改变任何既有能力的 spec 级行为。）

## Impact

- 新增代码：`client-web/`（独立 npm 工程，不进任何 Gradle 构建；运行时依赖仅 `ws`，dev 侧 vite/typescript/vitest/playwright）。
- 文档：`docs/protocol.md`（追加注记，纯文档）、新增 `docs/web-client.md`。
- 部署面：模拟器宿主机新增常驻网关进程 + frpc 一条 proxy（remotePort → GATEWAY_PORT）；v1 明文（与 frp 现状同级），token 为唯一凭据。
- 不触及延迟专项（client-ux-and-input-latency）正在修改的源端管线代码；浏览器侧延迟观测数据（docs/web-latency-samples.json）反向供其比对。
