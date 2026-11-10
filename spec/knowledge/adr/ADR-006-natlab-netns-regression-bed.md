# ADR-006：打洞逻辑回归以 natlab 实验床为准，模拟器与真机不进 netns

date: 2026-11-10
status: accepted

## Context

P2P 打洞此前只在「同宿主 UDP 直连」下验证通过，跨网（家庭 NAT ↔ 蜂窝/办公网）成功率从未验证。真机当时不可接入自动化；emulator 走 slirp 用户态网络，不便接 tap，无法放进 netns 做受控网络实验；而打洞逻辑又需要可控、可复现、可回归的 NAT 行为条件。

## Decision

NAT 模拟实验室（natlab）用两个 JVM 进程作协议级对端：直接依赖 protocol 库跑真实 transport 栈（StunClient / P2pBackend 被真实验证而非模拟），两进程分置不同 netns（各带独立 lo 与出口 veth），NAT 用 iptables 分档构造（EI 默认档 / symmetric 随机端口档 / 混合档，两端可独立选档形成 3×3 组合矩阵）。打洞逻辑的一切改动以 natlab 矩阵的成功率与耗时数据回归；模拟器与真机一律不进 netns，真机跨网判定走人工手册复核。

## Consequences

打洞改动可在无 adb 设备环境全自动回归，矩阵数据落盘可对比；netns NAT 与运营商真实 NAT（尤其 CGNAT 端口随机化）行为有差距，natlab 结论一律标注「实验室口径」，最终可用性判定仍需真机；维护面新增 netns/iptables 脚本与 JVM 对端进程的构建运维。
