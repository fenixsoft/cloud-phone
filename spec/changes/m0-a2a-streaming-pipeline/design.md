## Context

全新仓库 `/root/cloud-phone`（Apache-2.0 开源，master 分支已 init，fenix 框架已挂）。产品愿景与既有调研结论（scrcpy/云游戏/CRTR 延迟预算、双档传输、两级权限、三级网络瀑布、会话挂机语义）见 `docs/`（随本 SPEC 补齐协议与验证文档）。本 SPEC 为 M0 里程碑：双模拟器走通安卓→安卓全链路。

环境事实（立项时已验证）：
- 本机 = 作者家庭服务器：Ubuntu 22.04，24 核 62G，KVM 开启，磁盘余 28G；`/opt/android-sdk`（SDK 34 + emulator 37.2）与 AVD `src`/`client`（pixel_6）就绪，常驻 emulator-5554(emulator-5556 见 adb)。
- 家庭网络 NAT = endpoint-independent（cone）：三 STUN 同 socket 同映射（14.155.102.104:17317）；国内 STUN qq/miwifi:3478 被滤，Twilio/Nextcloud/Cloudflare 可达。
- 真实公网 FRP 回环：frpc 已挂面板隧道 `t3CcW1J2DMkW`(TCP 127.0.0.1:22333↔112.74.85.74:22333) 与 `9dNPmvI3sR2C`(UDP :22334)；模拟器经 qemu NAT 出公网可直连 112.74.85.74。
- VPS(134.175.54.198 / 112.74.85.74) 无 SSH 密钥权限，M0 不自建信令/STUN 节点。

## Goals / Non-Goals

**Goals:**
1. 双模拟器端到端流程：源端采集编码 → 网络传输 → 客户端解码渲染 → 触摸回传，全链路贯通。
2. 三种网络路径可切换且全部实测：`lan-direct`（同主机一跳）/ `p2p`（STUN+FRP 信令+UDP 打洞）/ `frp`（真实公网 TCP/UDP 回环）。
3. 负载与分辨率矩阵：游戏档（恒定 60fps 动画）× 应用档（静态页），≥720p/1080p 两档，3 网络 × 2 负载 × 2 分辨率 = 12 组合自动化验收并产出报告。
4. 会话挂机最小闭环：断开停传输不停虚拟屏，重连凭 token ≤1s 恢复画面。
5. 单应用模式最小闭环：虚拟屏 + 指定包名启动 + 客户端手势语义（远端 back / Home 退出回抽屉）。
6. `docs/protocol.md` 协议文档与协议常量单一定义（为 M1 C++ 化锁定线上格式）。

**Non-Goals:** 音频/剪贴板/IME（M3）；自适应码率与双档自动切换完整策略（M1）；FEC/弱网对抗（M1）；C++ 协议核心库（M1，本里程碑只定格式）；鸿蒙端（M2）；mDNS 发现与真机无线调试/Shizuku 授权链（M1 真机化时）；传输层加密（M0 仅 token 鉴权，**真机公网使用前必须先完成 M1 加密**）；会话实时缩略图（M0 文字列表）。

## Decisions

1. **自研轻量二进制协议，不用 RTSP/WebRTC**：依赖重、鸿蒙端复刻成本高；协议文档独立成篇是开源卖点。控制通道走可靠流（TCP/frp-TCP），视频通道 UDP + sequence + NACK 重传（frp/TCP 模式下降级为 TCP 承载视频）；字段布局 C 兼容对齐（M1 直接 C++ 化不破坏格式）。备选 WebRTC 否决理由：引入 libwebrtc 体积/复杂度失控，且整屏/虚拟屏采集定制困难。
2. **编码 H.264（video/avc）而非 H.265**：模拟器编解码器兼容性最稳（c2.android.avc 软编兜底、真机硬编全覆盖）；H.265 8-20Mbps 游戏档参数留 M1 真机启用。帧内嵌源端单调时钟时间戳用于端到端延迟测量。
3. **单应用模式用虚拟屏 + shell bootstrap**：`DisplayManager.createVirtualDisplay` + `am start --display <id>`；模拟器上以 adb root + `app_process` 启动 shell 助手进程执行 privileged 命令（scrcpy/Dioxamine 同款范式，即"增强级权限"的 M0 替身）；真机路径（Shizuku/无线调试）M1 接入，两级权限产品语义不变。
4. **TransportBackend 统一抽象三后端**：`lan-direct`=源端端口经 `adb forward` 暴露到主机环回（模拟器拓扑下"局域网"= 同主机一跳，RTT≤5ms 量级）；`p2p`=双端 STUN（Cloudflare 优先，多节点列表可配）取 SRFLX，信令复用 frp-TCP 22333 通道交换 offer/answer，UDP 双向同发打洞，5s 未成自动回落 `frp`（cone NAT 已验证可行，但对端对称型时必须能优雅降级）；`frp`=客户端直连 112.74.85.74:22333(TCP)/22334(UDP) 真实公网回环。备选"只用 TCP 单通道"否决理由：UDP 是 P2P 与低延迟的根基，M0 必须把 UDP 路径跑通。
5. **会话生命周期 > 连接生命周期**：源端前台服务内 SessionManager 持有虚拟屏与 App；断开只停编码器/传输；重连同 token 立即 `IDR_REQUEST` 秒回画面。MediaProjection 授权在模拟器上用 uiautomator 自动点击弹窗。
6. **验证矩阵一键化**：`scripts/run_matrix.py` 编排 adb/emulator：12 组合各跑 60s，采集 fps 均值/P95 帧间隔/端到端延迟（PTS 差值 + 白闪像素检测校准一次）/码率/丢包，产出 `docs/validation-report.md`。可用线：游戏档平均 fps≥45 且延迟 P95≤200ms、frp 路径 60s 不断流、触摸往返 P95≤200ms（软渲染放宽值，真机目标 80ms 在 M1 复核）、应用档静止码率≤1Mbps。
7. **仓库布局**：`source/`、`client/`、`protocol/`（Gradle composite build 共享模块：消息+传输后端+统计，两端纯 Kotlin 共用）、`docs/`、`scripts/`；Gradle wrapper distributionUrl 用腾讯镜像（国内网络）；version catalog 统一依赖版本。

## Risks / Trade-offs

- [模拟器 swiftshader 软渲染/软编达不到 60fps] → 指标线放宽（fps≥45）并在报告注明与真机差距；1080p 组合失败时允许 720p 复测标注。
- [国内 STUN 被滤] → 内置多节点列表（Twilio/Nextcloud/Cloudflare）+ 可配置；p2p 失败永有 frp 兜底。
- [frp UDP 隧道限速/丢包] → UDP 视频通道连续失败自动降级 TCP-over-frp；报告记录降级事件。
- [双模拟器同主机拓扑下"局域网/公网"语义与真实手机有差、同 host 双 qemu NAT 打洞可能假成功] → 报告明确标注拓扑局限；真机对真机 P2P 复测列入用户人工步骤。
- [MediaProjection 授权弹窗自动化脆弱] → uiautomator + 3 次重试 + 失败截图存档。
- [磁盘余 28G 紧张] → Gradle 缓存限 tmp，AVD data 分区维持默认，构建产物不入库。
- [M0 明文传输] → 风险声明：真机公网使用必须在 M1 加密落地之后；模拟器与局域网内可接受。

## Migration Plan

全新增代码，无迁移。回滚 = 删除仓库目录。M0 验收通过后的人工步骤（不进 tasks）：作者在真机安装 client 复测局域网路径；随后 M1 立项（加密/自适应码率/真机授权链）。

## Open Questions

- 同主机双 qemu NAT 间 UDP 打洞成功率未知（可能 qemu 用户态网络直接旁路）→ 由 `p2p` 后端在 M0 实测并记录；若假成功/失败，协议逻辑正确性以"真机对真机"人工步骤补验。
- frp 面板 UDP 隧道实际可用带宽未知 → 矩阵报告实测记录（受限时允许降码率，记录事件）。
