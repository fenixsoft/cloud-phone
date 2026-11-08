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
