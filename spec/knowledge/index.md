# 知识真相源
> 最后更新：2026-11-10 22:14 UTC+8

本项目为安卓→安卓「云端手机」单应用串流系统：源端 App 采集编码 → 网络传输 → 客户端 App 解码渲染，触摸回传闭环，支持断开挂机、重连秒回与单应用/整屏两种观看范式。检索路径：先在本表定位能力域，进域导航页后定位能力页。

## 能力域

| 编号 | 能力域 | 简介 | 能力数量 | 更新时间 |
| --- | --- | --- | --- | --- |
| 1 | [protocol](domains/protocol/_domain.md) | 串流线协议：两端共享的握手认证、加密协商、控制消息、帧封装与统计交换契约 | 1 | 2026-11-10 17:25 |
| 2 | [source](domains/source/_domain.md) | 源端：画面采集、H.264 编码、虚拟屏单应用会话与挂机恢复 | 1 | 2026-11-09 10:37 |
| 3 | [client](domains/client/_domain.md) | 客户端：Android 与 Windows 桌面两种客户端形态的解码渲染、输入回传、界面壳与连接体验、交互延迟 | 7 | 2026-11-10 22:14 |
| 4 | [transport](domains/transport/_domain.md) | 传输：lan-direct / p2p / frp 三网络路径、跨网打洞与流加密、直连诊断 | 3 | 2026-11-10 17:07 |
| 5 | [validation](domains/validation/_domain.md) | 验证：负载生成、验收矩阵与端到端延迟测量，真机一键接入诊断与重测检查表 | 2 | 2026-11-10 18:29 |
| 6 | [aux-channels](domains/aux-channels/_domain.md) | 辅助通道：远端 App 音频下行、剪贴板双向同步与完整 Unicode 文字注入 | 1 | 2026-11-10 17:25 |

## 架构决策记录

| 编号 | 状态 | 摘要 |
| --- | --- | --- |
| [ADR-001](adr/ADR-001-binary-wire-protocol.md) | accepted | 自研二进制线协议替代 RTSP/WebRTC，控制/视频双通道 + C 兼容布局 |
| [ADR-002](adr/ADR-002-virtual-display-shell-bootstrap.md) | accepted | 单应用模式走虚拟屏 + 特权 shell 助手进程，两级权限产品语义 |
| [ADR-003](adr/ADR-003-transport-abstraction-waterfall.md) | accepted | TransportBackend 统一抽象三后端，p2p 5s 回落 frp 网络瀑布 |
| [ADR-004](adr/ADR-004-session-outlives-connection.md) | accepted | 会话生命周期优先于连接生命周期，断开挂机、token 重连秒回 |
| [ADR-005](adr/ADR-005-latency-data-first-threshold-writeback.md) | accepted | 延迟修复数据先行，验收阈值放宽必须回写 SPEC |
| [ADR-006](adr/ADR-006-natlab-netns-regression-bed.md) | accepted | 打洞逻辑以 natlab 实验床矩阵数据回归，模拟器/真机不进 netns |
| [ADR-007](adr/ADR-007-hole-punch-fallback-chain.md) | accepted | 打洞按 v6 直连→多候选→端口预测→FRP 兜底逐级回退，会话绑 sessionId 支持端点迁移 |
| [ADR-008](adr/ADR-008-fragment-aead-stream-encryption.md) | accepted | 视频片级 AES-128-GCM AEAD + HKDF 方向子钥，协商走可选字段不 bump 协议版本 |
| [ADR-009](adr/ADR-009-aux-flows-on-control-tcp-video-first.md) | accepted | 辅助流复用控制 TCP 记录通道不新增传输面，积压丢音频保视频 |
| [ADR-010](adr/ADR-010-ime-focus-proxy-clipboard-textinput.md) | accepted | 剪贴板/组合文本注入经源端内置 IME 焦点代理，方向不对称如实降级 |
| [ADR-011](adr/ADR-011-desktop-client-consumes-protocol-nofork.md) | accepted | 客户端新形态经复合构建消费 protocol 单一事实源，协议零 fork、既有工程零改动 |
| [ADR-012](adr/ADR-012-windows-vm-smoke-verification-chain.md) | accepted | Windows 实机验证走 dockur/windows VM 脚本化冒烟链，软渲染回退 + 分阶段截图取证 + 不可达显式诊断 |
