# 真机重测一键接入诊断与检查表

<!-- knowledge: capability=realdevice-retest-prep rev=1
     sources=spec/changes/single-app-realdevice-retest -->

真机可用性验证的自动化准备层：真机到手后一条命令完成接入与分段探针诊断，并把真机差距清单展开为可勾选检查表，使每个结论有证据、每步可执行可记录。

## 规则

- 分段探针链：scripts/realdevice_bootstrap.sh 按 PUSH→SPAWN→PING→CREATE 顺序探针，每段独立超时（PUSH 60s / SPAWN 15s / PING 30s / CREATE 40s）；前段失败不阻塞输出已有段结论。
- 总结论为六值枚举且与证据绑定：`OK`（四段全过，含 CREATE 后助手存活复验 + 虚拟显示登记核验）、`MIRROR_LIMIT`（PING 成功 + CREATE 段进程静默 SIGKILL、无 crash 记录——镜像级限制特征）、`SELINUX_DENIED`（诊断包存在关联助手上下文的 `avc: denied` 行）、`SHELL_UNAVAILABLE`（app_process 拉起即失败，user 版限制特征）、`NO_DEVICE`、`UNKNOWN`（证据不充分，人工分析）。禁止无证据下结论。
- 退出码：0=OK，1=NO_DEVICE，2=参数/环境错误，3=探针非 OK 结论；无在线设备时直接落 NO_DEVICE，不产生误导性部分结论；多设备在线必须 `--serial` 指定。
- 三种模式：默认 auto（本地无构建产物或设备侧 hash 不一致时构建并幂等推送）、`--probe-only`（只探不改，直接用设备侧已有 dex）、`--full`（强制重走构建 + 幂等推送）；probe-only 与 full 互斥。
- CREATE 段冒烟口径：720x1280@320dpi 虚拟显示（DisplayManager.createVirtualDisplay）+ MediaCodec 初始化。
- 诊断包自动收集：任一探针段失败即抓 logcat 尾部 500 行、dmesg avc/kill、getenforce、build props（ro.build.type / ro.build.selinux / ro.build.version.release / ro.product.cpu.abi）、id；人读报告打 stdout，JSON 摘要默认落 `docs/realdevice-probe-report-<serial>-<timestamp>.json`（`--json` 可改）并附 `*-diag/` 诊断目录，两格式结论一致。
- 构建形态分叉提示：userdebug 真机预期与模拟器同机制（app_process 可用，重点验证 CREATE 段）；user 版真机按 scrcpy 业界实证预期 app_process 可用，SPAWN 段失败落 SHELL_UNAVAILABLE 并提示单应用模式降级路径 FULLSCREEN（MediaProjection）。
- 网络通路指引内嵌脚本输出：真机与服务器同网 → lan-direct 直连源端 IP:22333；跨网 → frp 既有隧道（公网入口 TCP:22333 控制 + UDP:22334 视频可选）；p2p 无需端口预约。真机指引不得出现 `adb emu redir` / `adb forward`（模拟器专属）；接入模拟器时指回 bootstrap_adb.sh。
- 分工边界：realdevice_bootstrap.sh 只负责真机接入与分段诊断；模拟器环境编排（forward/redir/投影授权链）归 bootstrap_adb.sh，两者互相不调用。
- 重测检查表 `docs/realdevice-retest-checklist.md` 覆盖 device-notes §4 全部真机差距项：shell 助手、单应用会话端到端、触摸延迟 P95≤300ms、deeplink 直达、OEM 后台查杀白名单、锁屏 60s 不断流、1080p60 硬解、硬编吞吐、P2P wireKind 核对，另含加密范围确认；每项三栏（操作 / 预期 / 失败时抓什么）并标注 device-notes 对应行，操作为可直接复制执行的命令或明确 UI 步骤。
- 检查表执行后必须把结果回写 device-notes（§4 现状列 + §8 记录）：检查表是执行层、device-notes 是结论层，避免双源漂移。
- 失败诊断路径自验基准：在模拟器镜像限制环境（emulator-5554）上执行探针应输出 `MIRROR_LIMIT` 且 PING 段成功——该结论正确即证明脚本失败诊断逻辑可信。
- 投影授权顺序铁律（先启动 mediaProjection 前台服务再 getMediaProjection）由 bootstrap_adb.sh 在模拟器侧编排；真机由用户在 App 内手动授权，脚本不重复实现。

## 关键行为

- 探针全程：设备发现与 serial 选择 → 环境记录（Android 版本/API/ABI/构建形态）→ PUSH（构建产物 hash 幂等跳过）→ SPAWN（app_process 后台拉起，日志按设备侧 uid 分文件落 `/data/local/tmp/`）→ PING（发 PING 等 PONG）→ CREATE（虚拟显示 + MediaCodec 冒烟）→ 总结论 + 人读报告 + JSON 落盘 + 按设备形态输出网络指引。
- 检查表执行约定：逐项勾选，执行后在「实测」行记录日期 + 结论 + 证据指针；失败项附脚本自动诊断包。

## 代码锚点

- 主实现：scripts/realdevice_bootstrap.sh（分段探针、结论枚举判定、诊断包与双格式输出、网络指引）
- 消费方：docs/realdevice-retest-checklist.md（执行层检查表，衔接 device-notes §4 差距清单）
- 守卫测试：无独立守卫测试（模拟器镜像限制环境的 MIRROR_LIMIT 失败路径自验为人工复验基准，记录于检查表附录 A）

## 溯源

- 2026-11-10 single-app-realdevice-retest（spec/changes/single-app-realdevice-retest/）— 新建本页：确立分段探针链与六值结论枚举、诊断包双格式输出、构建形态与网络指引分叉、检查表双层回写约定。
