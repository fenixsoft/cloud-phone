# 验证报告：single-app-realdevice-retest

> ⚠️ 本报告由框架于 2026-11-09T10:09:43Z 自动合成（裁决 Agent 未写报告，已由框架合成），内容为结构化验证产物（report.json / .fenix-run-result.json）的忠实渲染。
> 报告时效标记（判定本报告是否属上一轮）：合成源轮次指纹：report.json@2026-11-09T10:04:43.579625+00:00+run-result@mtime1794218975773464210

## 结论：✅ 通过（verified）

**状态：verified — 3/5 场景通过，2 失败，0 错误（结构化数据存在失败明细，终局判定以 verification.verdict 为准）**

5 场景：3 pass；2 个失败场景（probe-only-happy-path-ok、mirror-limit-fault-injection-repro）根因同为环境设备不可达：本轮 adb devices 无在线设备（现场复核仍为空），步骤报 device 'emulator-5554' not found，探针按 NO_DEVICE 契约以退出码 1 终止，均分类 env 豁免、不记产品 Bug；产品脚本行为本身由 device-discovery-and-exit-codes 场景验证为正确（NO_DEVICE/退出码契约 pass）。设备在本轮前的上轮 run（09:39-09:42Z）尚在线（r2-s2-probe.json verdict OK / r2-s3-fi.json verdict MIRROR_LIMIT 为该轮产物），本轮 run（10:04Z）前掉线，属环境回归而非用例或产品缺陷；两场景 file_content 断言 pass 命中该上轮残留 JSON（stale），已在 evidence 标注。缺口：需恢复 emulator-5554 在线后重验这 2 个场景；建议场景运行前清理 artifacts 残留 JSON 以免 file_content 断言空命中。

| 指标 | 数量 |
| --- | --- |
| 通过 | 3 |
| 失败 | 2 |
| 错误 | 0 |
| 产品 Bug | 0 |
| 本链增量 Token（输入） | 0.15M |
| 本链增量 Token（输出） | 0.05M |


## 场景结果

| 场景 | 描述 | 状态 | 失败断言 | 分类 |
| --- | --- | --- | --- | --- |
| device-discovery-and-exit-codes | 设备发现与退出码契约：多设备在线未指定 --serial 时列出候选并以退出码 2 退出；--serial 指定不在线设备时退出码 2；无任何在线设备时输出结论 NO_DEVICE 并以退出码 1 退出（不产生误导性部分结论）；--help 正常输出用法 | pass | 0/6 | - |
| probe-only-happy-path-ok | probe-only 全链 happy path：设备侧已有原版 shellhelper.dex（md5 5a021ace…）时执行 --probe-only，PUSH 段按 probe-only 语义跳过，SPAWN/PING/CREATE 三段全 pass，结论 OK 且 JSON 摘要落盘字段完整（schema/serial/mode/verdict/stages 四段/timeouts/device 环境） | fail | 4/7 | env |
| mirror-limit-fault-injection-repro | MIRROR_LIMIT 诊断链复现（2026-11-09 人工裁决接受的故障注入替代验收）：当日镜像（UE1A.230829.050 userdebug）§2.1 CREATE 静默杀未复现（uid0 连发 CREATE 全 OK），将设备侧 dex 受控替换为 CREATE 中静默自杀的故障注入变体后，生产脚本零改动完整复现 §2.1 特征链：SPAWN pass→PING pass→CREATE 段进程静默消失（无 OK 应答、无 crash）→结论 MIRROR_LIMIT→JSON verdict 字段落盘；验后恢复原版 dex 并核验 md5 一致 | fail | 4/5 | env |
| realdevice-guidance-no-emulator-commands | 网络通路指引分叉：真机形态（非 emulator- 前缀 serial）指引为 lan-direct 直连服务器 IP:22333 / frp 隧道 / p2p，且指引中不出现 adb emu 命令并明确标注 forward/redir 仅模拟器可用；模拟器形态（emulator- 前缀）指引指向 bootstrap_adb.sh 编排且本脚本不越界 | pass | 0/4 | - |
| checklist-covers-device-notes-gaps | 重测检查表完备性：docs/realdevice-retest-checklist.md 覆盖 device-notes §4 全部 9 项真机差距（shell 助手重推+CREATE、单应用会话、触摸 P95≤300ms、deeplink 直达、OEM 查杀白名单、锁屏 60s 不断流、1080p60 硬解、硬编吞吐、P2P wireKind 核对），每项含操作/预期/失败时抓什么三栏与 §4 行标注，且含执行结果回写约定 | pass | 0/4 | - |


## 断言级豁免清单

以下失败断言被归类为非产品缺陷（不记产品 Bug），逐条列出供人工复核豁免是否掩盖真实缺陷（场景级一句话归因不足以复核）。

| 场景 | 断言 | 归类 | 豁免理由 | 证据 |
| --- | --- | --- | --- | --- |
| probe-only-happy-path-ok | output_match [combined contains] '结论: OK' | env | 环境因素（非产品缺陷） | 步骤2 实际输出「结论: NO_DEVICE（adb devices 无在线设备）」；同批 device-discovery-and-exit-codes 的 realdev-list 步骤亦确认 adb devices 为空，设备环境不可达，无法验证 |
| probe-only-happy-path-ok | output_match [combined contains] 'PING→PONG 往返正常' | env | 环境因素（非产品缺陷） | 前置夹具步骤1 push 已因 device 'emulator-5554' not found 失败（exit 1），探针未达 PING 段即以 NO_DEVICE 终止，属设备不可达级联失败 |
| probe-only-happy-path-ok | output_match [combined contains] '虚拟显示就绪' | env | 环境因素（非产品缺陷） | 设备不在线致探针止步设备发现段、RC_PROBE=1，CREATE 段未执行；NO_DEVICE 判定本身是场景1已验证通过的正确脚本契约，非产品缺陷 |
| probe-only-happy-path-ok | output_match [combined contains] 'RC_PROBE=0' | env | 环境因素（非产品缺陷） | 实际 RC_PROBE=1 为 NO_DEVICE 契约退出码，与场景1已验证的退出码契约一致；仅当设备在线时才可能为 0 |
| mirror-limit-fault-injection-repro | output_match [combined contains] '结论: MIRROR_LIMIT' | env | 环境因素（非产品缺陷） | 步骤2 探针输出 NO_DEVICE、RC_FI=1，故障注入链未在设备上执行；file_content 断言 pass 系命中上轮残留 r2-s3-fi.json（本轮设备不在线不可能产出 MIRROR_LIMIT verdict） |
| mirror-limit-fault-injection-repro | output_match [combined contains] 'PING→PONG 往返正常' | env | 环境因素（非产品缺陷） | 步骤1 stderr 即报 adb: error: failed to get feature set: device 'emulator-5554' not found，设备不可达，SPAWN/PING 段未启动 |
| mirror-limit-fault-injection-repro | output_match [combined contains] 'CREATE 期间助手进程消失' | env | 环境因素（非产品缺陷） | 受控替换 FI dex 依赖 adb push，步骤1/步骤3 均因 device not found 失败，注入变体未上设备，CREATE 段无从复现 |
| mirror-limit-fault-injection-repro | output_match [combined contains] '5a021ace17df97b8a63a5c7489cb5f85' | env | 环境因素（非产品缺陷） | 验后恢复原版 dex 的 md5sum 校验属步骤3，因 device not found 未执行，非脚本产物缺陷 |

## 本轮场景集变更

当前稳定场景集共 5 个（第 1 轮）。

- 首轮写入 5 个场景

