# 设备实测笔记（M0 A2A Streaming Pipeline）

记录 M0 验收过程中的真机/模拟器实测结论、镜像级限制、绕行方案与真机重测手册。
所有发现均来自双模拟器实跑（详见 `validation-report.md`），源码均已落盘修复或降级。

## 1. 测试环境

| 项 | 值 |
|---|---|
| 模拟器 ×2 | Android SDK Emulator（qemu，x86_64），`emulator-5554`（源端）/ `emulator-5556`（客户端） |
| 系统镜像 | sdk_gphone64_x86_64，API 34（UE1A.230829.050，userdebug，可 `adb root`） |
| 网络通路 | lan-direct：宿主 `adb forward tcp:22333` + `adb emu redir udp:22334`；frp：公网 112.74.85.74（TCP:22333/UDP:22334 frpc 隧道）；p2p：STUN SRFLX + UDP 打洞 |
| 构建链 | AGP 8.x / Kotlin 2.0 / compileSdk 34 / minSdk 26；JDK 17（/opt/jdk/jdk-17*，非 PATH） |

## 2. 镜像级限制（已确认复现 + 绕行）

### 2.1 shell 助手 CREATE 被系统 SIGKILL（单应用模式降级）
- 现象：`CLASSPATH=/data/local/tmp/shellhelper.dex app_process /system/bin cloudphone.shell.ShellHelper`
  启动后 `PING/PONG/INJECT` 正常，但执行 `CREATE`（`DisplayManager.createVirtualDisplay` + MediaCodec 初始化）时
  进程被系统静默 SIGKILL，logcat 无 crash（`adb shell` 会话内以 uid 0（u:r:su:s0）与 uid 2000 均复现）。
- 判定：镜像对 app_process 进程持 VM 的限制（Zygote 外进程的 display/codec 权限模型），
  非代码缺陷。scrcpy 在真机依赖同一机制可用，推测与该 userdebug 镜像的 SELinux/服务白名单有关。
- 影响/降级：**单应用模式（SINGLE_APP）在此模拟器上不可用**，START_SESSION 会返回明确降级原因；
  整屏模式（FULLSCREEN，MediaProjection 路径）为本次验收主路径。
- 重测建议（真机）：userdebug 真机上重复「推送 dex → app_process 拉起 → CREATE」并抓 `dmesg | grep -i avc`
  与 logcat `am_kill`，确认是否为 sepolicy 域（`u:r:su:s0` vs `platform_app`）差异。

### 2.2 MediaProjection 一授权一实例、闲置令牌约 60 秒失效
- 现象（均实复现）：
  1. 同一 `resultData` 二次 `getMediaProjection` → `SecurityException: Don't re-use the resultData …`；
  2. 授权后约 60s 不建 VD → `createVirtualDisplay` 抛 `token that has timed out`；
  3. 同一 projection 实例第二次 `createVirtualDisplay` → `SecurityException`（例外文案明示禁止）。
- 架构影响：**授权（grant）→ 铸造凭据（credential）→ 建 VD+编码器 必须一次成型**。
  源端采用「共享采集管线」：授权后立即以启动方参数建常驻管线（VD+MediaCodec），
  会话（START_SESSION）只挂接帧流，不再触碰投影令牌。
- 参数变更（如矩阵的 720p30/1080p30 切换）必须**重新授权**后用新凭据重建管线：
  `run_matrix.ensure_pipeline` 在参数变化时自动重走授权 + `am start-foreground-service -a
  cloudphone.source.START_CAPTURE --ei width … --ei bitrateKbps …`。
- **（2026-11-09 起，上述重新授权约束已被零令牌自适应取代）** 任意客户端档位（分辨率/
  帧率/码率）即插即连，不再需要后台重新授权：`ScreenCapture.adaptTo()` 换编码器 surface
  （`VirtualDisplay.setSurface` + 失败兜底 `vd.resize`），同一投影实例零令牌完成切档并即时
  IDR；`ensureSharedCapture` 异参先 adapt、失败则保留现行管线照常挂接（HelloAck 回实际
  尺寸，客户端 letterbox 兼容）。协议线格式未改，客户端 APK 无需更新。
  实证：`scripts/test_adaptive_client.py` 两轮异参（1080x1920@30/5000 → 720x1280@24/3000）
  经 127.0.0.1 与公网 112.74.85.74:22333 均 PASS（HelloAck ACCEPTED + adaptTo 日志 + 出帧）。
- 顺序铁律：**必须先启动 `mediaProjection` 型前台服务，再调 `getMediaProjection`**
  （Android 14 要求授权时 FGS 已在运行，否则令牌无效）。`bootstrap_adb.sh` 与
  `MainActivity.requestProjection()` 均已保证该顺序。

### 2.3 模拟器 goldfish 解码器「吃帧不出帧」
- 现象：客户端自动选中 `c2.goldfish.h264.decoder`（模拟器虚拟硬解）后，输入缓冲正常消费、
  `dequeueInputBuffer` 阻塞，`dequeueOutputBuffer` 永不返回（仅一条 `Ignoring stale input buffer
  done callback` 后静默）。换成 `c2.android.avc.decoder`（软解）立即恢复。
- 处理：`DecodeRenderActivity.pickDecoder()` 过滤名称含 `goldfish` 的解码器（模拟器专属，真机无此类型），
  硬解优先、软解兜底。

### 2.4 分桶存储：App 读不到 adb 写入的文件（FUSE 属主隔离）
- 现象：矩阵参数曾用 `/sdcard` 文件下发；`adb shell` 写入的文件属主为 shell，
  App 以 `EACCES (Permission denied)` 打开；写入应用专属目录 `/sdcard/Android/data/<pkg>/files/` 亦同
  （文件属主 shell ≠ App uid）；`run-as` 反向写亦被拒。
- 结论：**跨 adb ↔ App 的参数通道必须走 intent extras**（服务 intents 可带任意参数），
  文件通道在 API 30+ 上不可靠。

### 2.5 宿主共享端口的 UDP 远端污染
- 现象：lan-direct 的 UDP 视频通道曾默认开启并以「首包学习」确定远端；宿主侧 frpc /
  adb emu redir（均绑 22334/UDP）的无关流量经重定向打到源端 UDP 口，把「远端地址」学习成
  frp 黑洞，视频静默丢失（源端统计 fps=60「已发送」，客户端 fpsRecv=0）。
- 处理：lan-direct 默认关闭 UDP 视频且 `UdpVideoChannel(learnFromFirstPacket=false)`——
  首包学习仅保留给 p2p 打洞（P2pBackend 显式传 true）；lan/frp 一律显式 `setRemote`。

## 3. 平台通用坑（与设备无关，实跑踩实）

- **NetworkOnMainThreadException 静默吞包**：控制下发必须在工作线程（ConnectActivity 曾在
  UI 线程调 `startSession`，异常被 catch 吞掉，表现为「源端收不到 StartSession」）。
- **BufferedOutputStream 必须逐记录 flush**：64KB 写缓冲不 flush 时，小控制包滞留
  （HELLO_ACK 延迟 500ms+）、会话请求完全无法出栈；实时视频逐帧 flush（Nagle 已关）。
- **LinkedBlockingQueue 有界 ack**：`SynchronousQueue.offer` 无消费者时丢 HELLO_ACK（握手竞态）。
- **解码队列深度即延迟上界**：深度 180 时解码略慢于收流会把 P50 推到 300ms+；
  深度 8（≈133ms@60fps）+ 丢旧保关键帧后稳态 delayMs≈0，P95 达标。
- **跨设备时钟不可相减**：源端 PTS 与客户端本地钟不同纪元，帧延迟口径改为
  「收流→渲染」客户端本地时延；端到端绝对延迟用白闪法（`measure_latency.py`）。

## 4. 真机重测手册（M0 → 真机差距清单）

| 差距 | 现状 | 真机重测步骤 |
|---|---|---|
| P2P 打洞真实性 | 双模拟器同宿主 NAT，打洞「直连成功」可能为假阳性 | 两台异网真机（蜂窝/WiFi 各一），`autoBackend p2p`，核对 `wireKind=P2P` 与对端 SRFLX 地址非同一 NAT 公网映射 |
| 加密 | M0 视频流未加密（spec 范围外） | 真机公网（frp/p2p）暴露明文流，验收限内网/测试网；后续版本补 DTLS/SRTP |
| OEM 后台查杀 | 模拟器无查杀 | 真机开启电池白名单 + 前台服务保活；验证锁屏/切后台 60s 不断流 |
| shell 助手 | 模拟器 SIGKILL（见 2.1） | userdebug 真机重推 dex，抓 `dmesg`/sepolicy avc；user 版不可用则单应用模式保留降级路径 |
| 解码器 | 模拟器软解（goldfish 规避） | 真机硬解（c2.qti/OMX.qcom 等），复核 1080p60 解码吞吐与延迟 |
| 编码吞吐 | 模拟器 c2.android.avc（软编）720p60 ≈ 850kbps | 真机硬编复核 1080p 与 bitrate 峰值 |

## 5. 运维备忘

- 重装源端 APK（`install -r`）会杀进程 → **投影授权与采集管线全部失效**，必须重跑：
  `bash scripts/bootstrap_adb.sh grant`（内部顺序：起服务 → 授权 →（矩阵）带参建管线）。
- 客户端重装只需重连（会话级状态在服务端）。
- `adb emu redir add udp:22334:22334` 幂等性差：已存在时先 `redir del` 再 `add`（bootstrap 已处理）。
- frpc 隧道由宿主 `frpc -c frpc.toml` 常驻；公网入口 112.74.85.74:22333(TCP)/22334(UDP)。

## 6. 触摸延迟口径（client-ux-and-input-latency SPEC 5.4）

### 6.1 模拟器实测（双模拟器 lan-direct，scripts/measure_latency.py --touch）

| 口径 | 修复前（静屏） | 修复后（静屏） | 修复后（60fps 负载） |
|---|---|---|---|
| P50 | 651ms | 266ms | 1ms（≈1 帧内）|
| P95 | 3104ms | **575ms（≤800ms 达标）** | **43ms（≤300ms 达标）** |
| max | 3153ms | 686ms | 70ms |

- 采样：300ms 间隔注入 20 次；t1（客户端触摸产生）→ t6（同 seq 帧渲染完成），两端 logcat 时钟同源宿主。
- 分段（修复后）：注入 94-145ms（含 relay 抖动）、采集+编码 20-96ms（修复前 228-540ms+）、传输+渲染 5-16ms。
- 修复构成：InputComposer 输入窗口 5fps 合成驱动（静屏 SurfaceFlinger 对虚拟屏镜像按 ~1Hz idle 合成是根因，公共 API 无「强制合成」入口，经 bootstrap `appops set ... SYSTEM_ALERT_WINDOW allow` 登记透明 1×1 悬窗；未授权自动降级并 WARN）+ 客户端 latest-wins 渲染（`releaseOutputBuffer(false)` 丢弃积压陈旧帧）+ TCP_NODELAY 复核（`TcpLink` 两端均已设置，无需改动）+ 控制通道 PING/PONG 看门狗（>6s 无 Pong 掐死重连）。
- 60fps 负载帧率不回退：触摸期间源端编码 52-59fps（名义 60fps，指针轨迹叠加开销 ~3-5%），触摸响应 ≈1 帧内。

### 6.2 已知环境限制（模拟器口径专属）

- client→host 的 slirp NAT（10.0.2.2）对 guest 出站 TCP 写入有 **0.3-2s 批量合并**（tcpdump 实证：字节离开 guest 内核后成簇到达宿主 adb forward），该延迟发生在 App 之下、真机不存在此层；measure_latency.py 以时钟自校准（中位数偏差 ±350ms）配对，被批量合并的样本无法配对计入口径，属保守估计。
- adb forward 半开连接：宿主侧通道死亡时无 FIN/RST 传播到 guest TCP 栈，客户端写入 0ms「成功」但永不到达（Ping/Pong 看门狗即为该场景而设）。
- 静屏测量用 pointer_location 叠加层保证每次触摸产生可捕获视觉变化（测量框架装置，非产品代码）。

### 6.3 真机目标与测量方法

- 真机目标：触摸→视觉变化 **P95 ≤ 300ms**（SPEC touch-latency-fix）。
- 方法：`python3 scripts/measure_latency.py --touch --samples 20 --client <serial> --source <serial>`；真机替代 adb forward（直连 IP），预期无 relay 批量化，插桩分段（①–⑥ cloudphone-touch 日志）口径同本节。
- 真机判定依据：模拟器 swiftshader 软编码/软解码与本机 GPU 硬编硬解的口径差异主要落在「采集+编码」段（模拟器 20-96ms，真机硬解预计 <30ms）；注入与传输段口径可直接迁移。

## 7. 单应用快捷方式 Launcher 兼容性与真机验证清单（client-ux-and-input-latency 4.3/6.2）

### 7.1 快捷方式链路实现回顾

- 客户端深链：`cloudphone://launch?host=<host>&backend=<lan-direct|frp|p2p>&app=<package>&name=<显示名>&auto=1`；
  ConnectActivity.onNewIntent（singleTask）→ handleDeeplink → upsert 档案 → 直接 START_SESSION SINGLE_APP。
- 桌面快捷方式：ShortcutManager `createShortcutResultIntent`（API ≥26）/ 兼容路径，icon=应用图标，
  intent 指向 ConnectActivity + 深链 extras；档案去重以 `host|backend|package` 为键，同源端多次创建只保留一条。
- 源端：StreamService 收 START_SESSION mode=SINGLE_APP → 拉起目标包主 Activity 到虚拟屏 → 只采集该屏。
  启动超时/进程死亡 → 回执失败原因（含「目标应用 <pkg>」字样）→ 客户端弹「直达失败」并落档案备注。

### 7.2 Launcher 兼容性说明（模拟器实测 + 真机预期差异）

- Pixel Launcher/API ≥26 动态快捷方式：正常创建与展示，点击走深链，单应用会话 ≤3s 出画（实测 720x1600）。
- 第三方 Launcher（Nova/微软桌面/国产 ROM）：`isRequestPinShortcutSupported` 差异大；不支持请求固定时，
  客户端降级为「应用内快捷方式列表」（长按档案项直达，功能等价）——真机验证时先确认该开关路径。
- 图标：目标应用 icon 经 `createIconBitmap` 压到 adaptive-icon 尺寸；个别 Launcher 对非 adaptive 位图有白边
  （显示瑕疵，不影响功能）；真机抽屉内核对图标清晰度。
- 深链未安装客户端时：浏览器/Notes 里点 cloudphone:// 链接无响应属预期（无 App Link 域名校验）；
  快捷方式本体始终经 ConnectActivity，不受影响。
- 后台启动限制（Android 10+）：从快捷方式点击是用户交互，不受限制；但 **从自动化（adb/备份恢复）触发深链**
  在真机上可能被 BackgroundActivityStart 拦截 → 需先手动打开一次客户端。真机清单含此项。

### 7.3 真机人工验证清单（单应用快捷方式）

1. 安装客户端 + 源端（源端为源设备上的 APK；客户端机与源机同网段或走 frp 公网入口）。
2. 首连：ConnectActivity 输入 host/backend → 连接成功出画（验证投影授权弹窗、SAW 悬窗权限弹窗——
   真机需手动允许「显示在其他应用上层」，否则触摸延迟修复自动降级并 WARN，见 device-notes 6.1）。
3. 创建单应用快捷方式：应用列表长按目标应用 → 创建到桌面；核对桌面图标与名称。
4. 杀掉客户端进程 → 桌面点快捷方式 → 冷启直达单应用会话（验证档案持久化 + SINGLE_APP 全链）。
5. 目标应用未安装时点快捷方式 → 应弹「直达失败：<原因>（目标应用 <pkg>）」而非挂死。
6. 同一 host+app 再建一次 → 档案不重复（3.1 去重语义）。
7. 触摸延迟：`adb shell settings put global window_animation_scale 0` 后人工体感 + 真
   机跑 `measure_latency.py --touch --samples 20`（两端真机 serial），P95 ≤ 300ms。
8. 旋转/折叠：竖→横屏切换，SINGLE_APP 会话保持、画面比例正确（aspect-fit 无黑边异常）。

### 7.4 本变更验证记录汇总（各 Scenario 证据指针）

证据根目录：`/root/cloud-phone/.fenix/verify-evidence/client-ux-and-input-latency/`

| Scenario（specs/） | 结论 | 证据 |
|---|---|---|
| source-profiles（3.1-3.3） | PASS | `applist_wire_probe.txt`（A 无会话静默忽略/B 在线 5ms 应答+未知 0x0C 容忍）；档案去重/持久化 logcat 摘录见 tasks.md 1.3 已勾记录 |
| resolution-passthrough（2.1-2.3） | PASS | `resolution_passthrough_22.md`：1080x2400→1072x2400 aspect-exact（/16 对齐）+ 进程内切换 720x1600 letterbox 无扭曲；附 FULLSCREEN 挂接忽略请求参数缺陷的修复记录 |
| app-shortcuts（4.1-4.3） | PASS | `appshortcuts_e2e.md`（深链→SINGLE_APP→出画）、`launcher_with_shortcut.png`（桌面快捷方式）、失败路径=伪造包名/不可达端口均得「直达失败」并落盘 |
| touch-latency-fix（5.1-5.4） | PASS | `touch_baseline.json`（P50=651/P95=3104）→ `touch_fixed.json`（P50=266/P95=575 ≤800）→ `touch_fixed_60fps.json`（P50=1/P95=43 ≤300，帧率 52-59 不回退）；`touch_latency_summary.md` 汇总；device-notes §6 口径差异 |
| 全量回归（6.1） | PASS | `full-suite-report.json`（8/8：协议单测+三模块构建+lan-direct 线级冒烟+矩阵 lan-direct/game/720p 60.3fps+frp 入口+p2p STUN）；`validation-report.md` 矩阵行 |

## 8. 真机重测准备（single-app-realdevice-retest）

### 8.1 一键接入与分段诊断脚本

`scripts/realdevice_bootstrap.sh`：真机到手后一条命令完成接入 + PUSH→SPAWN→PING→CREATE 分段诊断。

```bash
bash scripts/realdevice_bootstrap.sh --serial <serial>          # 默认 auto：缺产物则构建 + 幂等推送
bash scripts/realdevice_bootstrap.sh --serial <serial> --full   # 强制重走构建 + 推送
bash scripts/realdevice_bootstrap.sh --serial <serial> --probe-only --json <path>   # 只探不改
```

- 每段独立超时（PUSH 60s / SPAWN 15s / PING 30s / CREATE 40s），前段失败不阻塞输出已有段结论。
- 结论枚举：`OK | MIRROR_LIMIT | SELINUX_DENIED | SHELL_UNAVAILABLE | NO_DEVICE | UNKNOWN`，全部证据绑定
  （AVC 行 / am_kill 记录 / getenforce / 构建形态），任一段失败自动抓诊断包
  （logcat 尾 500 行、dmesg avc|kill、getenforce、build props、id），JSON 摘要落
  `docs/realdevice-probe-report-<serial>-<ts>.json` + 诊断侧车目录。
- 网络指引内嵌脚本末尾：真机同网 lan-direct 直连源端 IP:22333（`adb forward`/模拟器控制台 redir
  仅模拟器可用）；跨网走 frp 既有隧道（§5）。模拟器设备保留 `bootstrap_adb.sh` 分工（两脚本互不调用）。
- 与 §2.2 授权顺序铁律的关系：本脚本只负责真机接入与助手诊断；整屏模式的
  「先起 FGS 再 getMediaProjection」授权编排仍由 `bootstrap_adb.sh` / App 内手动授权承担，本脚本不重复实现。

### 8.2 重测检查表

`docs/realdevice-retest-checklist.md`：§4 差距清单逐项展开为三栏执行步骤（操作/预期/失败时抓什么），
覆盖 shell 助手 CREATE、单应用会话、触摸 P95≤300ms（`measure_latency.py --touch` 口径）、deeplink 直达、
OEM 电池白名单、锁屏 60s、1080p60 硬解 c2.qti 复核、硬编吞吐、P2P wireKind 异 NAT 核对与加密范围。
执行后按检查表「回写约定」把结论更新回 device-notes（§4 现状列 + §8 记录）。

### 8.3 user 版真机预期差异

- user 版上 app_process 机制预期可用——scrcpy 在量产 user 机依赖同一机制（业界实证）；
  若 SPAWN 段失败（`SHELL_UNAVAILABLE`），单应用模式不可用，保留 FULLSCREEN（MediaProjection）降级路径。
- user 版 SELinux 策略与 userdebug 不同，可能触发未知拒绝形态 → 落 `UNKNOWN` + 完整诊断包人工分析；
  脚本已按 `ro.build.type` 分叉输出对应预期说明（design D3）。
- 模拟器自验记录（2026-11-09）：§2.1 的 CREATE 静默杀**在该日镜像实例未复现**（uid0 连发 25 次 CREATE
  全部成功；uid2000 下 CREATE 立即 `SecurityException: packageName must match the calling uid`）；
  MIRROR_LIMIT 诊断路径以故障注入复现 §2.1 特征实证（见检查表附录 A/B）。真机重测时以 §2.1 特征
  （PING 正常 + CREATE 静默消失 + 无 crash）为准绳，勿假定模拟器当日行为外推到真机。
## 9. 真机游戏上行体验（gaming-uplink-1080p SPEC 3.2）

> 模拟器软编 1080p60 编码吞吐上限 ≈4Mbps@12fps（docs/uplink-report.md 附录 A），高熵负载的
> 码率/帧率结论在模拟器上「编码器即天花板」，**最终判定必须真机硬编复核**。本章为真机人工体验
> 手册：每步可直接执行，判定全部数值化（非主观「感觉流畅」）。

### 9.1 家庭上行基线测定（iperf3 对公网 VPS 单跑）

先行测定——它是后续所有「码率不达标」归因的参照（家庭上行 vs VPS 中转两因素，uplink-report §frp 归因）。

```bash
# VPS 侧（112.74.85.74，或现有 frp 所在 VPS；先安装 iperf3：apt install -y iperf3）
iperf3 -s -p 5201                      # 服务端常驻（防火墙放行 TCP/UDP 5201-5202）

# 家庭侧（源端所在主机，即模拟器宿主机）
iperf3 -c 112.74.85.74 -p 5201 -t 30 -P 4        # 上行带宽（4 并发，30s，取 SUM 行 Mbps）
iperf3 -c 112.74.85.74 -p 5201 -t 30 -R          # 下行带宽（家庭下载方向）
iperf3 -c 112.74.85.74 -p 5201 -t 30 -u -b 100M  # UDP 上行（Jitter/丢包列）
```

记录表：

| 指标 | 实测值 | 判定 |
|---|---|---|
| TCP 上行 Mbps（SUM，30s） | ______ | ≥15Mbps 才够游戏档 1080p（设计区间 8–15Mbps + 头部余量） |
| UDP 上行 Mbps / Jitter ms / 丢包 % | ______ | 丢包 <0.5% 为网络段 PASS 参照 |
| RTT 基线（`ping -c 20 112.74.85.74` 取 avg） | ______ ms | 与压测期 RTT 对比算膨胀 |

### 9.2 loadgen 高熵 App 步骤（串流链真机复测）

1. 真机装源端与 loadgen：`adb -s <真机serial> install -r source/app/build/outputs/apk/debug/app-debug.apk source/loadgen/app/build/outputs/apk/debug/app-debug.apk`；完成投影授权（`scripts/bootstrap_adb.sh grant`，顺序铁律见 §2）。
2. 启动高熵负载：`adb shell am start -S -n cloudphone.loadgen/.GameLoadActivity --es entropy mid`（三档 low/mid/high；档位定义=编码器实际输出，见 uplink-report 附录 A）。
3. 客户端连真机源端（frp 路径），会话参数 1080p60 / 10000kbps。
4. 每 2 分钟记录 StatsView（抽屉内）：fps / 码率 kbps / RTT ms / 丢包 %，连续 ≥10 分钟 → 填 §9.4 表。

### 9.3 真实游戏步骤（最终判定）

1. 真机源端装 1–2 款自选真实游戏（x86 兼容性自担，不做自动化——用户已确认）。
2. 串流游戏档 1080p60（码率目标 ≥10000kbps），实际游玩（战斗/加载/转场均覆盖）≥10 分钟。
3. 每 2 分钟记录一次 StatsView 四项 + 主观评分（1=不可接受 … 5=与本地无差异），填 §9.4 表。
4. 网络段异常时补充：`adb -s <客户端serial> shell ss -tn` 截图（对端归属）+ 客户端 logcat `cloudphone-latency` 尾迹。

### 9.4 记录表（每 2 分钟一档，≥10 分钟 = ≥5 行）

| 时刻 | 内容 | fps | 码率(kbps) | RTT(ms) | 丢包(%) | 主观(1–5) |
|---|---|---|---|---|---|---|
| 0–2min | | | | | | |
| 2–4min | | | | | | |
| 4–6min | | | | | | |
| 6–8min | | | | | | |
| 8–10min | | | | | | |

### 9.5 量化判定（全部满足才算真机 PASS）

- 稳态 fps ≥50（60fps 会话）；
- 稳态码率 ≥8Mbps（若码率 <8Mbps 且 StatsView fps 满 60：编码器受限 → ENCODER_CAPPED-真机复核不成立，按 §9.1 基线与 RTT 膨胀判网络段）；
- 丢片率 <0.5%；RTT 膨胀 ≤ max(20ms, 2×§9.1 基线)；
- 主观评分 ≥4 且无可感知卡顿记录；
- 任一不达标：按 uplink-report §三段链式归因口径定位段（编码器/传输/网络），证据（StatsView 照片 + §9.1 数据）留档。

### 2.3 源端通道晋升语义（2026-11-09）
LanDirectBackend SOURCE 角色自本日起为 **pending→promote** 两段式：accept 的新连接先挂 pending，收到其第一条控制记录（合法 HELLO）才晋升为现役通道并退役旧通道（last-HELLO-wins）。裸 TCP 连接（探针/扫描器/健康检查）不再踢会话。同一时间仍只支持一个活跃客户端——后连接的真客户端会踢掉前者（`installChannel`/`onChannelControl`）。配套：`adb-forward-keepalive.service`（5s 自检，重建消失的 forward 规则，脚本 scripts/adb_forward_keepalive.sh）。

## 10. 跨网 P2P 打洞与加密开关真机手册（p2p-crossnet-traversal 5.2）

### 10.1 真机跨网打洞步骤（蜂窝/办公网客户端 ↔ 家庭宽带源端）

1. **源端（家庭宽带侧）**：启动 SourceActivity（前台服务 + MediaProjection 授权），
   记下配对 token 与公网中转（frp，TCP:22333 / UDP:22334 隧道）可达性。
2. **客户端（另一网络侧）**：ConnectActivity 填 host（frp 公网地址）+ token，
   backend 选 `p2p`，保持「端到端加密」开关状态（默认开）。
3. **握手**：客户端 HELLO 携多候选 offer（双 STUN 节点 SRFLX + 本地网卡候选，
   `;nat=sym` 尾缀标识 symmetric 判定）；源端 HELLO_ACK 回多候选 answer。
4. **打洞**：双端并行 out-probe 全候选集；对端 symmetric 时额外启用端口预测
   （≤64 端口差分外推窗口批量探测，前向+后向）。
5. **成功判定 = 实时状态视图（StatsView）transport 显示 `p2p(直连)`**；
   显示 `p2p→frp(回落)` 即打洞失败走中转（功能可用但属 degraded，公网中转带宽受限）。
   连接页状态区同时显示协商结果：`加密：AES-128-GCM` 或 `未加密（对端不支持）`。
6. **端点迁移验证（蜂窝场景重点）**：会话中切换 Wi-Fi ↔ 蜂窝（出口映射变化），
   客户端重跑 SRFLX 发现并经信令发 P2P_MIGRATE；预期画面短暂停顿后恢复（探针重连复用），
   StatsView 统计连续不重置。蜂窝 NAT 常为 symmetric/池化，迁移恢复依赖 FRP 信令通道
   在线——若中转也不可达，会话按既有断流逻辑终止。
7. **常见失败排查**：双端均 strict（如双 symmetric/双 AP-RR）时直连成功率低（见 §10.3
   natlab 矩阵），属预期——公网中转兜底保证会话建立；打洞耗时 >5s 自动回落。

### 10.2 加密开关位置说明

- **入口**：ConnectActivity 连接表单复选框「**端到端加密（AES-128-GCM，对端不支持自动
  回落明文）**」，缺省勾选；开关随连接档案持久化（每档案独立，回填显示）。
- **生效口径**：开关只表达本端意图；实际是否加密由握手协商决定（双端都带协商块且都
  开启 → AES-128-GCM；任一端老版本/关闭 → 明文，协议逐字节与 v1 现状一致）。
- **结果显示**：连接成功后状态区显示「加密：AES-128-GCM / 未加密（对端不支持）」，
  logcat `session ready, 加密：…` 同步输出；会话中可从实时状态视图持续确认。
- **运维注意**：加密仅作用于流内容（视频片 + 控制记录，含 +16B/片开销）；打洞探针帧
  （sequence=0）恒明文，不泄露密钥材料。

### 10.3 natlab 结论「实验室口径」标注

本文档与 SPEC 引用的 natlab 数据（`docs/natlab-matrix.json`）均为**实验室口径**，
不得直接外推为真实蜂窝/家宽成功率：

- NAT 用 Linux netns + iptables 构造（EI 端口保持全锥 / AP-RR 受限锥 / symmetric
  `--random-fully` / sym-seq 微池化段），内核 NAT 行为同构、无运营商级 QoS/限速/
  应用层网关干扰；
- STUN 节点与端点同宿主（时延 <10ms，打洞耗时数字只反映协议栈开销，不含真实 RTT）；
- 矩阵结论的**有效范围**：协议栈逻辑正确性（多候选汇总、symmetric 判定、预测装配、
  迁移恢复、FRP 兜底不阻塞），而非「某类真实 NAT 下命中率 X%」；
- 真机口径请按 §10.1 步骤实测，成功判定以 StatsView transport 显示为准。
## 11. M3 辅助通道平台限制与真机复核（m3-audio-clipboard-textinput SPEC）

### 11.1 音频下行平台限制（如实呈现，非缺陷）

- **capture 是拷贝不是重定向**：AudioPlaybackCapture 捕获的是远端 App 音频输出的
  **拷贝**，源端本地照常外放（无法静音源端扬声器/听筒）。串流时源端与客户端同时出声
  是平台行为；源端消音需用户自行在源端设备调低媒体音量（不影响捕获流）。
- **API 门槛**：AudioPlaybackCapture 需 **Android 10（API 29）+**。API<29 源端
  音频通道不建立，源端会话激活时下发 AuxNotice（协议 0x10 kind=1），客户端状态行
  明示「源端系统不支持音频捕获」，非静默无声。
- **usage 匹配**：仅捕获 usage=GAME/MEDIA 的播放流（设计 D1）；通话/告警等系统音
  不在捕获范围（隐私边界，平台如此）。
- **静音零流量**：源端 RMS 持续 3s 低于阈值后仅保 1 帧/s keepalive；游戏 loading/
  文字界面基本零音频流量。
- **模拟器注意**：捕获可用性依镜像而定；若 `AudioRecord` 初始化失败（state 未就绪），
  源端按「镜像禁用 AudioPlaybackCapture」降级并通告，真机复核以 §11.4 为准。

### 11.2 剪贴板方向性限制（Android 10+ 规则内）

| 方向 | 通路 | 可用条件 | 不可用时表现 |
|---|---|---|---|
| 客户端 → 源端（粘贴） | CLIPBOARD_SET → IME setPrimaryClip | 源端已启用 CloudImeService（全版本可用） | AuxNotice「输入法未启用」，客户端粘贴按钮置灰 |
| 源端 → 客户端（复制） | CLIPBOARD_GET → IME getPrimaryClip → CLIPBOARD_TEXT | IME 活跃（源端有获焦输入框）或源端 App 前台 | AuxNotice「无获焦输入框」，复制按钮置灰+原因 |

- **反向读取是受控能力**：Android 10+ 后台剪贴板读取需焦点，IME 是唯一合法焦点
  代理——源端无获焦输入框时复制不可用是平台规则，UI 明示原因（非静默失败/超时）。
- **启用引导**：有 adb 走 `scripts/bootstrap_adb.sh ime`（ime enable/set + 自检）；
  无 adb 环境走源端设置页「启用云剪贴板输入法」按钮（跳系统输入法设置）。

### 11.3 文字输入路径表（TEXT_INPUT{seq,utf8}）

| 条件 | 路径 | 结果 |
|---|---|---|
| CloudImeService 已启用且有获焦输入框 | IME `commitText`（主路径） | 完整 Unicode（中文/emoji）注入成功，ACK{seq,ok=1} |
| IME 已启用但无获焦输入框 | — | ACK{seq,ok=0,「源端无获焦输入框」}（明确报错不丢字） |
| IME 未启用且文本纯 ASCII（含空格） | shellhelper `input text`（空格转 %s，TEXT 命令 base64 传输） | 注入成功，ACK ok=1 |
| IME 未启用且文本含非 ASCII | — | ACK{seq,ok=0,「需启用云剪贴板输入法」}（明确报错不静默丢字） |

- seq 重复投递去重（LRU 64 条）；每条 TEXT_INPUT 必有 ACK 回执（成功/失败原因）。
- 硬键键盘映射（INPUT_EVENT 按键通道）保留不动，TEXT_INPUT 是组合文本补充。

### 11.4 真机复核步骤（三通道，模拟器通过后逐项过）

1. **环境**：真机源端装源端 APK（bootstrap_adb.sh install 可复用）；`bash
   scripts/bootstrap_adb.sh ime` 启用云剪贴板输入法（或真机设置页手动启用）。
2. **音频捕获**：源端播放带声视频/游戏 ≥30s；客户端观察：状态行无音频降级提示、
   logcat `AudioTrack` 持续消费、听得到声音且 ≤500ms 起播；源端静音 3s 后
   `logcat cloudphone-source` 应见 keepalive（1 帧/s）而非常规 50 帧/s。
3. **粘贴往返**：本机复制任意中文 → 客户端「粘贴」→ 源端设备打开任意输入框粘贴，
   应得本机文本；客户端状态行「已粘贴到源端 ✓」。
4. **中文输入**：源端打开任意输入框（获焦）→ 客户端「键盘」面板输入中文发送 →
   源端输入框出现完整中文、客户端「已注入 ✓」；IME 未启用时重复应得「需启用云
   剪贴板输入法」错误回执（不静默丢字）。
5. **复制反向**：源端输入框获焦状态下长选文本复制 → 客户端「复制」→ 本机粘贴应得
   源端文本；源端无获焦输入框时客户端「复制」应置灰并显示「需源端输入框获焦」。
6. **音频拷贝语义确认**：串流中源端扬声器仍出声（capture 是拷贝，见 §11.1）——
   记录为预期行为，不算缺陷。

### 11.5 模拟器全链路验证结论（2026-11-09，双模拟器 5554/5556）

12 场景线级探针 12/12 PASS（`docs/m3-aux-channels-verification-report.md` §1，
证据 `.fenix/verify-evidence/m3-audio-clipboard-textinput/`）；音频端到端
（捕获→ADTS 线传→客户端解码→AudioTrack 播放）已打通。复核新增平台事实：

- **捕获豁免按 uid**：root/shell-uid 播放（shellhelper `TONE`，USAGE_MEDIA）
  **不可捕获**（返回静音→静音门保活）；app-uid 播放（loadgen `AudioToneActivity`）
  可捕获（47fps 实帧）。真机复核音频必须用 app 进程播放，shell 里 `am` 直发
  AudioTrack 类工具不产生可捕获音频。
- **静音门实测**：RMS<100 持续 3s → 263B 保活帧 1 帧/s；响音恢复在保活节拍内
  即回 47fps 实帧（pump 永不停摆）。低媒体音量（STREAM_MUSIC=2/15）下捕获响度
  贴近 RMS 阈值，验证时建议先拉满音量。
- **客户端解码路径（关键）**：`KEY_IS_ADTS=1` ADTS 内嵌模式在本平台
  c2.android.aac.decoder（C2SoftAacDec）持续 `decoderErr=0x1001` 零输出
  （帧头/喂入均正常）。实现改走**裸 AAC + 显式 csd-0**（{0x11,0x90}=LC/48k/双声道），
  feed 侧剥离 7B ADTS 头；线协议不变。真机如遇同类解码器，同路径规避。
  **不要设置 KEY_AAC_SBR_MODE**：实验中显式置 0 反使该平台编码器整体回落默认参数
  （44100Hz/单声道/64kbps）；LC profile 下 sbr-mode=auto(3) 不会激活 SBR，保持默认。
- **网络环境（运维口径，详见报告 §4）**：adb forward 中继连接 ~4-5s 被 adb 侧断开，
  长会话须用 `adb emu redir add` 控制台重定向（内核级转发不经 adb 中继）；
  源端监听迁 22399 隔离公共 frp 回程噪声；LanDirectBackend「新连接替换当前通道」
  语义在公网暴露端口时会被扫描噪声窃取通道（幽灵连接），生产须前置认证网关。
