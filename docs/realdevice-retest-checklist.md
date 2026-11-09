# 真机重测检查表（single-app-realdevice-retest）

执行层手册：把 `docs/device-notes.md` §4 真机差距清单逐项展开为可勾选步骤（操作 / 预期 / 失败时抓什么）。
结论层仍在 device-notes——**检查表执行后必须把结果回写 device-notes（§4 现状列 + §8 记录），避免双源漂移**（design D5）。

前置工具：

```bash
bash scripts/realdevice_bootstrap.sh --serial <serial>   # 真机一键接入 + PUSH→SPAWN→PING→CREATE 分段诊断
python3 scripts/measure_latency.py --touch --samples 20 --client <client_serial> --source <source_serial>
```

前置：网络通路（脚本末尾会按设备形态输出同样指引）

- 同网（真机与服务器同 LAN）→ lan-direct 直连：客户端 App host 填服务器 IP:22333。
  `adb forward` 与模拟器控制台 redir 仅模拟器可用，真机不适用。
- 跨网 → frp 既有隧道：客户端 host 填公网入口（现部署 112.74.85.74:22333；宿主 `frpc -c frpc.toml` 常驻）。
- p2p → 无需端口预约（STUN SRFLX + UDP 打洞）。

执行约定：每项先勾 `[ ]`，执行后改 `[x]` 并在「实测」行记录日期 + 结论 + 证据指针；失败项附诊断包
（脚本自动落 `docs/realdevice-probe-report-<serial>-<ts>.json` + `*-diag/`）。

---

## 1. shell 助手重推 + CREATE 冒烟（§4 行「shell 助手」，§2.1 背景）

- [ ] 操作：`bash scripts/realdevice_bootstrap.sh --serial <serial> --full`（构建 shellhelper.dex → 幂等推送 `/data/local/tmp/cloudphone/` → app_process 拉起 → PING → CREATE 720x1280@320）
  - 实测：
- 预期：人读报告总结论 `OK`（四段 pass；CREATE 后助手存活复验 + `dumpsys display` 登记 `cloudphone-vd-720x1280`），JSON 落盘字段完整
- 失败时抓什么：脚本已自动抓（logcat 尾 500 行 / dmesg avc|kill / getenforce / build props / id）。
  - `MIRROR_LIMIT`：§2.1 特征（PING 正常 + CREATE 段静默消失、无 crash）→ 镜像/策略限制非代码缺陷，按 §2.1 重测建议比对 sepolicy 域（`u:r:su:s0` vs `platform_app`）
  - `SELINUX_DENIED`：报告引用的 `avc: denied` 行 → 按具体被拒权限排查 sepolicy
  - `SHELL_UNAVAILABLE`：SPAWN 即失败 → user 版限制特征，单应用模式走 FULLSCREEN 降级（见 device-notes §8）
  - `UNKNOWN`：人工分析 `*-diag/` 诊断包

## 2. 单应用会话端到端（§4 行「shell 助手」的单应用落地，§7.1 链路）

- [ ] 操作：源端+客户端完成首连与投影授权（`scripts/bootstrap_adb.sh grant` 口径的顺序铁律仅模拟器；真机在 App 内手动授权）→ 客户端应用抽屉长按目标应用 → 发起单应用会话（或 START_SESSION SINGLE_APP）
  - 实测：
- 预期：≤3s 出画（720p 口径），画面只含目标应用；旋转跟随无扭曲
- 失败时抓什么：`adb logcat | grep -E 'StreamService|START_SESSION|直达失败|目标应用'`（源端回执带「目标应用 <pkg>」字样）；若 START_SESSION 回降级原因 → 回到项 1 核对助手 CREATE 段

## 3. 触摸延迟 P95 ≤ 300ms（§6.3 真机目标与测量方法；§4 表无直接行）

- [ ] 操作：`python3 scripts/measure_latency.py --touch --samples 20 --client <client_serial> --source <source_serial>`（真机替代 adb forward 直连 IP；`adb shell settings put global window_animation_scale 0` 先关动画）
  - 实测：
- 预期：P95 ≤ 300ms（模拟器 60fps 负载基线 43ms；静屏 ≤800ms 口径）
- 失败时抓什么：分段看 logcat `cloudphone-touch` ①–⑥ 插桩；确认 SAW 悬窗权限未降级（`adb shell appops get <client_pkg> SYSTEM_ALERT_WINDOW`，真机需手动允许「显示在其他应用上层」，否则 InputComposer 自动降级并 WARN，见 §6.1）

## 4. deeplink 直达（§7.2/§7.3 真机清单）

- [ ] 操作：杀掉客户端进程 → 桌面点单应用快捷方式 → 冷启直达；另测浏览器/Notes 点 `cloudphone://launch?host=<host>&backend=<lan-direct>&app=<pkg>&name=<名>&auto=1`；再点一个未安装目标应用的快捷方式
  - 实测：
- 预期：快捷方式冷启直达单应用会话；**自动化（adb/am start）触发深链在真机可能被 BackgroundActivityStart 拦截（Android 10+），需先手动打开一次客户端**；未安装目标 → 弹「直达失败：<原因>（目标应用 <pkg>）」而非挂死
- 失败时抓什么：`adb logcat | grep -iE 'deeplink|BackgroundActivityStart|ConnectActivity|handleDeeplink'`

## 5. OEM 查杀 + 电池白名单（§4 行「OEM 后台查杀」）

- [ ] 操作：设置 → 电池 → 源端 App 设「无限制/不优化」（MIUI：省电策略→无限制；EMUI：启动管理→手动管理三开关全开；Pixel：电池→不受限制）
  - 实测：
- 预期：StreamService 前台服务常驻；连续切换后台/清理最近任务 10 分钟后会话仍活
- 失败时抓什么：`adb shell dumpsys activity services cloudphone.source`（FGS 是否在跑）+ `adb logcat | grep -iE 'am_kill|lmkd|CloudphoneService'`

## 6. 锁屏 60s 不断流（§4 行「OEM 后台查杀」子项）

- [ ] 操作：建立单应用（或整屏）会话 → 锁屏等待 60s → 解锁
  - 实测：
- 预期：锁屏期间客户端不断流（或按锁屏暂停口径，解锁后 ≤2s 恢复出画）
- 失败时抓什么：两端 logcat（PING/PONG 看门狗掐死重连记录、编码器断粮、`MEDIA_PROJECTION` FGS 状态）；核对 wake lock 与锁屏保活设置

## 7. 1080p60 硬解复核（§4 行「解码器」）

- [ ] 操作：客户端连真机源端，跑 1080p60 会话 60s；核对客户端 `DecodeRenderActivity` 日志选中的解码器
  - 实测：
- 预期：选中 `c2.qti.* / OMX.qcom.*` 等真机硬解（真机无 goldfish，`pickDecoder()` 的 goldfish 黑名单不应误伤）；fpsRecv≈60、丢帧 <1%
- 失败时抓什么：`adb logcat | grep -iE 'pickDecoder|MediaCodec|c2\.|OMX'`；若落到 `c2.android.avc`（软解）→ 硬解不可用或黑名单过宽，记录解码器全列表（`adb shell cmd media.codec` 或 `dumpsys media.codec`）

## 8. 硬编吞吐（§4 行「编码吞吐」）

- [ ] 操作：1080p60 会话，源端观察编码 fps 与 bitrate 峰值（模拟器软编基线 720p60≈850kbps，见 §4）
  - 实测：
- 预期：真机硬编 1080p60 稳定 52-60fps，bitrate 达档位峰值（助手 CREATE 当前 8Mbps@60fps 口径）
- 失败时抓什么：`adb shell dumpsys media.codec`（编码器实例与吞吐）+ 源端统计；低吞吐先排除分辨率档位与散热降频

## 9. P2P wireKind=P2P 与异 NAT 核对（§4 行「P2P 打洞真实性」）

- [ ] 操作：两台异网真机（蜂窝 / WiFi 各一）`autoBackend p2p` 建会话
  - 实测：
- 预期：`wireKind=P2P`；对端 SRFLX 地址**非**同一 NAT 公网映射（双模拟器同宿主 NAT 的「直连成功」可能是假阳性）
- 失败时抓什么：`python3 scripts/nat_check.py` + `adb logcat | grep -iE 'P2p|STUN|SRFLX'`；打洞失败回落 relay 时记录 wireKind 与两端 NAT 类型

## 10. 加密范围确认（§4 行「加密」——范围条目，非验收步骤）

- [ ] 操作：确认本次真机验收的网络拓扑
  - 实测：
- 预期：M0 视频流未加密（spec 范围外）——真机公网（frp/p2p）暴露明文流，**验收限内网/测试网**；DTLS/SRTP 留后续版本
- 失败时抓什么：n/a（范围守门：若在公网做验收，需评审明确接受明文风险并记录）

---

## 执行结果回写约定（design D5）

1. 每项执行后更新本文件勾选与「实测」行（日期 + 结论 + 证据指针）。
2. 同步把**结论层**更新到 `docs/device-notes.md`：
   - §4 表格「现状」列改为真机实测结果（日期标注）；
   - §8「真机重测准备」追加执行记录段落；
   - §2.1 若真机复测出与模拟器不同特征（如 CREATE 直接 SecurityException），在 §2.1 追加「真机补充」小节。
3. 诊断包（JSON + diag 目录）随 issue 附证；截图类证据落 `.fenix/verify-evidence/<spec>/`。

## 附录 A：模拟器失败路径自验记录（tasks.md 5.1，2026-11-09）

环境：emulator-5554（sdk_gphone64_x86_64，API 34，UE1A.230829.050 userdebug，与 §2.1 同镜像指纹）。
证据目录：`.fenix/verify-evidence/single-app-realdevice-retest/`。

| # | 运行 | 命令 | 结论 | 说明 |
|---|---|---|---|---|
| 1 | 真实探针（原版 dex，uid0） | `bash scripts/realdevice_bootstrap.sh --serial emulator-5554 --probe-only` | **OK** | PUSH skip / SPAWN pass / PING pass（PONG 1）/ CREATE pass（displayId 登记 + 存活复验）——§2.1 的 CREATE 静默杀**今日未复现**（连发 25 次 CREATE 全 OK、VD 全登记、助手存活；uid2000 模式下 CREATE 立即 `SecurityException: packageName must match the calling uid`，均非 §2.1 静默杀形态） |
| 2 | 故障注入复现（§2.1 特征） | 同上 + 设备侧 dex 换为故障注入变体（CREATE 执行中 `halt`）| **MIRROR_LIMIT** | 生产脚本零改动；SPAWN pass → PING pass → CREATE 段进程静默消失 → 结论 `MIRROR_LIMIT` + 证据绑定（无 crash、无 AVC、getenforce=Enforcing、build=userdebug）+ JSON 字段完整。分类链路（SPAWN/PING/CREATE/诊断包/结论映射/双格式输出）全链实证 |
| 3 | 结论恢复 | 推回原版 dex（md5 `5a021ace…` 核验一致） | OK | 探针复跑结论 OK |

**5.1 字面验收说明**：任务字面「断言结论为 MIRROR_LIMIT」依赖模拟器镜像级限制在今日环境复现；实测该限制已不复现（环境漂移，上表 #1），故以**故障注入复现 §2.1 静默杀特征**替代验收（上表 #2，生产脚本未改动，仅设备侧测试工件受控替换并在验证后恢复）。MIRROR_LIMIT 分类逻辑、诊断包收集与双格式输出按 §1–§3 需求逐项实证通过。

## 附录 B：故障注入 dex 复现方法（仅供复验，不入生产）

1. 复制 `source/shellhelper/src/cloudphone/shell/ShellHelper.java`，在 `createDisplay()` 的
   `createVirtualDisplay` 调用前插入 `Runtime.getRuntime().halt(0);`（进程在 CREATE 执行中消失、
   无应答、无 crash——与 §2.1 静默 SIGKILL 外部可观测特征一致）。
2. 按 `scripts/build_shell_helper.sh` 同链路 javac+d8 构建 dex，`adb push` 覆盖
   `/data/local/tmp/cloudphone/shellhelper.dex`（先备份原 dex + md5）。
3. 跑 `bash scripts/realdevice_bootstrap.sh --serial <serial> --probe-only` → 断言 `MIRROR_LIMIT`。
4. **恢复原版 dex 并核对 md5**（本表附录 A #3 已执行）。
