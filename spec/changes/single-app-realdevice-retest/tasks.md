# Tasks: single-app-realdevice-retest

## Deliverables

```yaml
- path: scripts/realdevice_bootstrap.sh
  kind: new
- path: docs/realdevice-retest-checklist.md
  kind: new
- path: docs/device-notes.md
  kind: modify
```

## 1. 脚本骨架与设备发现

- [x] 1.1 实现 `scripts/realdevice_bootstrap.sh`：参数 `--serial <serial>`（多设备必填）/`--probe-only`（只探不改，不重新推送 dex）/`--full`（含构建推送）/`--json <path>`；`adb devices` 无设备时输出 `NO_DEVICE` 退出码 1，多设备未指定 serial 时列出候选并退出；环境记录（ro.build.version.release/sdk、ro.product.cpu.abi、ro.build.type）
- [x] 1.2 构建与推送段（PUSH）：调用既有 `scripts/build_shell_helper.sh` 产出 shellhelper.dex，`adb push` 到 `/data/local/tmp/cloudphone/`；同 hash 已存在时跳过重推（幂等）；`--probe-only` 跳过本段直接用设备上现有 dex

## 2. 分段探针链

- [x] 2.1 SPAWN+PING 探针：后台拉起 `CLASSPATH=/data/local/tmp/cloudphone/shellhelper.dex app_process /system/bin cloudphone.shell.ShellHelper`（日志重定向文件），PING→PONG 往返验证（30s 超时）；进程退出即判 SPAWN 失败并抓诊断包
- [x] 2.2 CREATE 探针：经助手下发虚拟显示 + MediaCodec 初始化指令，40s 超时；进程存活但无响应、进程消失（logcat 无 crash）分别留证据；失败触发诊断包收集

## 3. 诊断与结论判定

- [x] 3.1 诊断包收集器：logcat -d 尾部 500 行、`dmesg | grep -iE 'avc|kill'`、`getenforce`、`getprop` 构建形态组、`id`；按 design D1 映射结论枚举（MIRROR_LIMIT：PING 成功 + CREATE 静默消失；SELINUX_DENIED：avc denied 关联助手；SHELL_UNAVAILABLE：SPAWN 即失败），证据行原文进报告
- [x] 3.2 双格式输出：人读报告（stdout，含每段状态与结论）+ JSON 摘要落盘 `docs/realdevice-probe-report-<serial>-<timestamp>.json`（`--json` 可自定义路径）
- [x] 3.3 user/userdebug 分叉提示：按 ro.build.type 输出对应预期说明与降级路径提示（design D3）

## 4. 网络通路指引

- [x] 4.1 脚本末尾按设备形态输出指引：真机同网 lan-direct 直连 IP:22333（明确标注 `adb emu`/`adb forward` 仅模拟器）、跨网 frp 隧道指引；模拟器设备（emulator-*）保留既有 bootstrap_adb.sh 指引并提示分工

## 5. 模拟器失败路径自验

- [x] 5.1 在 emulator-5554 上执行 `--probe-only` 全链，断言结论为 `MIRROR_LIMIT` 且 PING 段成功、JSON 落盘字段完整（报告摘要记入本任务勾选说明与 docs/realdevice-retest-checklist.md 附录）
    - 【2026-11-09 人工裁决·验收通过】用户确认接受故障注入替代验收：当日同镜像（UE1A.230829.050 userdebug）§2.1 CREATE 静默杀未复现，字面判据不可满足属环境漂移非实现缺陷；实现以故障注入完整复现 MIRROR_LIMIT 诊断链（生产脚本零改动、验后原版 dex md5 核验还原），证据见 .fenix/verify-evidence/single-app-realdevice-retest/ 与检查表附录 A/B，环境漂移情报已记 device-notes §8/检查表附录 A。
  - 以「故障注入复现 §2.1 静默杀特征」替代验收（2026-11-09）：字面「MIRROR_LIMIT 结论」依赖的镜像级限制当日未复现——原版 dex uid0 连发 25 次 CREATE 全 OK（探针结论 OK）、uid2000 下 CREATE 立即 SecurityException（探针结论 UNKNOWN）；将设备侧 dex 受控替换为 CREATE 中 halt 的故障注入变体后，生产脚本零改动复现完整 MIRROR_LIMIT 链（SPAWN pass→PING pass→CREATE 静默消失→结论 MIRROR_LIMIT→JSON 字段完整），验后原版 dex 已恢复并核验 md5（5a021ace…）。证据：.fenix/verify-evidence/single-app-realdevice-retest/{probe-final-ok,probe-fi2,probe-u2000}.json 与同名 report-*.txt；摘要见 docs/realdevice-retest-checklist.md 附录 A

## 6. 检查表文档

- [x] 6.1 实现 `docs/realdevice-retest-checklist.md`：覆盖 device-notes §4 全部 9 项差距（shellhelper 重推+CREATE、单应用会话、触摸 P95≤300ms（scripts/measure_latency.py --touch 口径）、deeplink 直达、OEM 查杀电池白名单、锁屏 60s 不断流、1080p60 硬解 c2.qti 复核、硬编吞吐、P2P wireKind=P2P 与对端 SRFLX 异 NAT 核对），每项三栏（操作/预期/失败时抓什么），标注 §4 对应行；执行结果回写 device-notes 的约定说明
- [x] 6.2 `docs/device-notes.md` 增补「§8 真机重测准备」：realdevice_bootstrap.sh 用法、检查表链接、user 版真机预期差异（scrcpy 同机制业界实证 + SHELL_UNAVAILABLE 降级路径）
