# Proposal: single-app-realdevice-retest

## Why

单应用模式（SINGLE_APP）是产品核心范式（应用抽屉直达单应用串流），但至今只在模拟器上验证过「失败路径」：userdebug 镜像对 app_process 助手的 CREATE（DisplayManager.createVirtualDisplay + MediaCodec 初始化）静默 SIGKILL（`docs/device-notes.md` §2.1，镜像级限制非代码缺陷），**真机可用性从未实锤**。用户当前暂无真机可 adb 接入服务器，本 SPEC 把「真机可做的一切自动化准备」落位：真机到手后一条命令完成接入与分段诊断，重测全程有检查表可勾。

## What Changes

- 新增真机一键接入与诊断脚本 `scripts/realdevice_bootstrap.sh`：设备发现与 serial 选择、环境记录（Android 版本/ABI/构建形态 user|userdebug）、shellhelper.dex 构建推送、app_process 助手拉起、PUSH→SPAWN→PING→CREATE 分段探针（每段独立超时），每段失败自动抓诊断包（logcat 尾部 / dmesg AVC / getenforce / build props），输出人读 + JSON 双格式结论
- 新增真机重测检查表 `docs/realdevice-retest-checklist.md`：把 device-notes §4 真机差距清单逐项展开为可勾选执行步骤（命令 + 预期 + 失败诊断动作），覆盖 shellhelper、单应用会话、触摸延迟（目标 P95≤300ms）、deeplink 直达、OEM 后台查杀、锁屏保活、1080p60 硬解、硬编吞吐、P2P wireKind 核对
- 网络通路指引内嵌：真机与服务器同网走 lan-direct 直连 IP（明确 `adb emu redir`/`adb forward` 为模拟器专属不可用于真机），跨网走 frp 既有隧道
- `docs/device-notes.md` 增补「真机重测准备」章节（脚本用法、检查表链接、user 版真机预期差异）

## Capabilities

### New Capabilities

- `realdevice-retest-prep`：真机重测一键接入、分段探针诊断与检查表

### Modified Capabilities

- 无（纯增量工具与文档，不改动传输/协议/采集既有能力）

## Impact

- **影响代码面**：`scripts/realdevice_bootstrap.sh`（新增）、`docs/realdevice-retest-checklist.md`（新增）、`docs/device-notes.md`（增补一章）
- **兼容性**：零协议/客户端/源端代码改动，无 BREAKING
- **风险**：真机最终结论须等设备到位（检查表为人工执行环节，SPEC 交付可自动化部分并在模拟器镜像限制环境上自验失败诊断路径的正确性）
