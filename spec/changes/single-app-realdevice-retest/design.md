# Design: single-app-realdevice-retest

## Context

- `docs/device-notes.md` §2.1 实证：模拟器 userdebug 镜像上 shellhelper 的 PING/PONG/INJECT 正常，但 CREATE（`DisplayManager.createVirtualDisplay` + MediaCodec）被系统静默 SIGKILL、logcat 无 crash——**探针必须分段，不能一把梭判定「整体不可用」**
- §4 真机差距清单共 9 项（P2P 真实性、加密、OEM 查杀、shell 助手、解码器、编码吞吐等），已有结论层描述，缺执行层步骤
- shellhelper 构建链已存在：`scripts/build_shell_helper.sh` → `shellhelper.dex` → 推送 `/data/local/tmp/cloudphone/`，拉起命令 `CLASSPATH=/data/local/tmp/shellhelper.dex app_process /system/bin cloudphone.shell.ShellHelper`
- 投影授权顺序铁律（§2.2）：先启动 mediaProjection 型前台服务再 `getMediaProjection`（Android 14 要求）——整屏模式冒烟沿用 `scripts/bootstrap_adb.sh grant` 既有顺序，本脚本不重复实现
- 真机网络通路：lan-direct 需真机与服务器同网（客户端/服务端直连源端 IP），`adb emu redir add` 仅模拟器有效；frp 跨网路径复用既有 frpc 隧道
- 用户暂无真机可 adb 接入：本 SPEC 全部产出须能在「模拟器镜像限制环境」上完成自验（该环境本身就是失败诊断的真实样本）

## Goals

1. 真机到手后一条命令完成接入 + 分段诊断，结论明确落在枚举之一（可用 / 镜像限制 / SELinux 拒绝 / shell 不可用 / 无设备 / 未知）
2. 检查表覆盖 device-notes §4 全部真机差距项，每步可执行、可记录、失败有诊断动作
3. 脚本失败路径在模拟器上自验输出正确（镜像限制结论而非误导性笼统失败）

## Non-Goals

- 不做真机自动测试平台 / 多设备并行矩阵
- 不修复真机测试中发现的新问题（发现即另立项或热修，检查表只负责暴露）
- 不做 user 版真机 root / SELinux 绕过（限制如实报告，单应用保留 FULLSCREEN 降级路径）
- 不改动协议与客户端/源端 Android 代码

## Decisions

### D1 分段探针链 PUSH→SPAWN→PING→CREATE，结论枚举化
每段独立超时与诊断抓取：PUSH（adb push dex，含 build 产物 hash 幂等跳过）→ SPAWN（app_process 后台拉起，日志落文件）→ PING（发 PING 等 PONG，30s 超时）→ CREATE（虚拟显示 + MediaCodec 初始化冒烟，40s 超时）。结论枚举：`OK` / `MIRROR_LIMIT`（CREATE 被静默杀、PING 正常，即 §2.1 特征）/ `SELINUX_DENIED`（dmesg AVC denied 关联助手进程）/ `SHELL_UNAVAILABLE`（app_process 无法启动，user 版限制特征）/ `NO_DEVICE` / `UNKNOWN`。判定依据全部来自诊断包证据，禁止无证据下结论。

### D2 诊断包自动收集与双格式输出
任一段失败即收集：`logcat -d` 尾部 500 行、`dmesg | grep -iE 'avc|kill'`、`getenforce`、`getprop ro.build.type ro.build.selinux ro.build.version.release ro.product.cpu.abi`、`id`。人读报告打 stdout；JSON 摘要落 `docs/realdevice-probe-report-<serial>-<timestamp>.json`，供后续 issue 附证。

### D3 user vs userdebug 分叉提示
按 `ro.build.type` 区分：userdebug 真机预期与模拟器同机制（app_process 可用，需验证 CREATE）；user 版真机 scrcpy 依赖同一机制可用（业界实证），预期可用；若 SPAWN 段失败给出 `SHELL_UNAVAILABLE` 并提示单应用模式降级路径（FULLSCREEN）与检查表对应行。

### D4 网络通路指引内嵌脚本输出
脚本末尾按连接形态输出指引：真机 WiFi adb → 提示与服务器同网时 lan-direct 直连 IP（`adb emu redir` 不可用，明确标注模拟器专属）；跨网 → frp 隧道既有端口。分工边界：本脚本=真机接入与诊断，`bootstrap_adb.sh`=模拟器环境与授权编排，互相不调用。

### D5 检查表与 device-notes 双层职责
`docs/realdevice-retest-checklist.md` 是执行层（三栏：操作/预期/失败时抓什么，逐项引用 device-notes §4 行号），device-notes 是结论层——检查表执行后把结果回写 device-notes，避免双源漂移。

## Risks

- user 版真机 SELinux 对 app_process 的策略与 userdebug 不同，可能触发未知拒绝形态 → 落 `UNKNOWN` 结论 + 完整诊断包，人工分析
- 真机 OEM 差异（MIUI/EMUI 杀后台、权限面板文案）超出脚本可判定范围 → 检查表人工步骤覆盖
