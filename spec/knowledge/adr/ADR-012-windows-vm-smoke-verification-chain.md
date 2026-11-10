# ADR-012：Windows 实机验证走 dockur/windows VM 脚本化冒烟链

date: 2026-11-10
status: accepted

## Context

桌面客户端的渲染、输入与真实传输行为无法用 headless JVM 单测覆盖，手工 RDP 验收不可重复、agent 无法独立执行，Windows 侧此前没有实机验证环境。用户自有 dockur/windows VM（QEMU slirp 网络、RDP 暴露宿主环回）可作为 Windows 实机载体，但 slirp 网关对宿主环回的可达性无法事先保证，且 VM 内无 GPU。

## Decision

Windows 实机验证统一走脚本化冒烟链（scripts/win_vm_smoke.sh）：宿主构建产物 → 分发进 dockur/windows VM → 以软件渲染回退参数启动（-Dskiko.renderApi=SOFTWARE）→ 经 RDP/QEMU monitor 分阶段截图取证；链路源端为宿主 Android 模拟器（slirp 网关 10.0.2.2 映射宿主 adb forward 端口）。脚本第一步显式探测源端可达性，不可达时输出探测结果与修复预案（dockur 端口映射直连或宿主 socat 中转）并以非 0 退出码收尾，禁止静默失败；链路保持 agent 可独立执行、提供无 VM 的 --self-check 自检模式。

## Consequences

Windows 侧验收有了可重复、可留证（分阶段截图）的固定媒介，后续 Windows 相关工作沿同一链路回归，不再各自搭环境；代价是验证依赖 VM 与模拟器源端环境齐备（缺环境时以 --self-check 与诊断分支兜底），slirp 可达性风险被前置为脚本的显式探测与文档化预案，而非实现期隐式踩坑。
