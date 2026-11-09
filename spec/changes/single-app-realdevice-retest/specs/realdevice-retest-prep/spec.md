# realdevice-retest-prep — 真机重测一键接入、分段探针诊断与检查表

## ADDED Requirements

### Requirement: 分段探针链
`scripts/realdevice_bootstrap.sh` SHALL 按 PUSH→SPAWN→PING→CREATE 顺序执行分段探针，每段独立超时（PUSH 60s / SPAWN 15s / PING 30s / CREATE 40s），前段失败 SHALL NOT 阻塞输出已有段结论。

#### Scenario: 全链通过
- **WHEN** 在真机上执行 `bash scripts/realdevice_bootstrap.sh --full` 且助手四段全部成功
- **THEN** 输出结论 `OK`，并打印后续单应用会话验证指引（指向检查表）

#### Scenario: CREATE 段镜像级限制
- **WHEN** PUSH/SPAWN/PING 三段成功、CREATE 段进程被静默 SIGKILL（超时无响应、logcat 无 crash）
- **THEN** 输出结论 `MIRROR_LIMIT` 并附 §2.1 特征说明（与模拟器实测一致）

### Requirement: 结论枚举与证据绑定
结论 SHALL 为枚举 `OK | MIRROR_LIMIT | SELINUX_DENIED | SHELL_UNAVAILABLE | NO_DEVICE | UNKNOWN` 之一，且 SHALL 附对应诊断证据（AVC 行 / am_kill 记录 / getenforce / 构建形态）。

#### Scenario: 无设备
- **WHEN** `adb devices` 无任何设备时执行脚本
- **THEN** 结论 `NO_DEVICE`，退出码非 0，不产生误导性部分结论

#### Scenario: SELinux 拒绝
- **WHEN** CREATE 段失败且 dmesg 存在关联助手进程的 `avc: denied` 记录
- **THEN** 结论 `SELINUX_DENIED` 并引用具体 AVC 行

### Requirement: 诊断包自动收集
任一探针段失败 SHALL 自动收集诊断包（logcat 尾部 500 行、dmesg avc/kill、getenforce、build props、id），并输出 JSON 摘要到 `docs/realdevice-probe-report-<serial>-<timestamp>.json`。

#### Scenario: JSON 落盘
- **WHEN** CREATE 段失败
- **THEN** 生成 JSON 文件包含全部诊断字段与结论枚举，人读报告与 JSON 结论一致

### Requirement: 模拟器失败路径自验
在模拟器（emulator-5554，镜像限制环境）上执行探针 SHALL 输出 `MIRROR_LIMIT`，作为脚本失败诊断路径的正确性证明。

#### Scenario: 镜像限制环境复现
- **WHEN** 在 emulator-5554 上执行 `bash scripts/realdevice_bootstrap.sh --probe-only`
- **THEN** 结论为 `MIRROR_LIMIT`，JSON 报告落盘，且 PING 段结论为成功（与 §2.1 一致）

### Requirement: 网络通路指引
脚本 SHALL 按设备连接形态输出网络指引：真机与服务器同网 → lan-direct 直连 IP 指引（明确标注 `adb emu redir`/`adb forward` 为模拟器专属）；跨网 → frp 既有隧道端口指引。

#### Scenario: 真机指引不含模拟器专属命令
- **WHEN** 真机（非 emulator-）接入并完成探针
- **THEN** 输出的指引中不出现 `adb emu` 命令，lan-direct 指引为直连源端 IP:22333

### Requirement: 重测检查表完备性
`docs/realdevice-retest-checklist.md` SHALL 覆盖 device-notes §4 全部差距项（shell 助手、单应用会话、触摸 P95≤300ms、deeplink 直达、OEM 查杀白名单、锁屏 60s 不断流、1080p60 硬解、硬编吞吐、P2P wireKind 核对），每项 SHALL 含操作/预期/失败时抓什么三栏，并标注与 §4 的对应行。

#### Scenario: 差距项全覆盖
- **WHEN** 对照 device-notes §4 表格逐行核对检查表
- **THEN** §4 每一行都能在检查表中找到对应执行步骤，无遗漏

#### Scenario: 检查表可执行
- **WHEN** 用户按检查表任一项操作
- **THEN** 该项操作为可直接复制执行的命令或明确的 UI 操作步骤，预期结果可观察判定
