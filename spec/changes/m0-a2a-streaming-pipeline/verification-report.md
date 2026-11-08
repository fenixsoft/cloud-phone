# 验证报告：m0-a2a-streaming-pipeline

> ⚠️ 本报告由框架于 2026-11-08T12:17:15Z 自动合成（裁决 Agent 未写报告，已由框架合成），内容为结构化验证产物（report.json / .fenix-run-result.json）的忠实渲染。
> 报告时效标记（判定本报告是否属上一轮）：合成源轮次指纹：report.json@2026-11-08T12:17:15.039445+00:00+run-result@mtime1794140235071285151

## 结论：✅ 通过（verified）

**状态：verified — 26/26 场景通过，0 失败，0 错误（全部场景通过）**

全绿快道: 26/26 场景通过、0 失败 0 警告——无分类对象，跳过 judge 机械 verified

| 指标 | 数量 |
| --- | --- |
| 通过 | 26 |
| 失败 | 0 |
| 错误 | 0 |
| 产品 Bug | 0 |
| 本链增量 Token（输入） | 0.39M |
| 本链增量 Token（输出） | 0.13M |


## 场景结果

| 场景 | 描述 | 状态 | 失败断言 | 分类 |
| --- | --- | --- | --- | --- |
| repo-deliverables-exist | 仓库交付物齐备性：tasks.md Deliverables 清单关键路径（README、docs 三件、protocol 模块源码与测试、transport 七件、source/client 工程关键类、loadgen 两 Activity、scripts 五件、.gitignore 构建产物与 local.properties 忽略规则）在验证树内逐个存在，任一缺失即非零退出并打印 MISSING 路径（出处：spec/changes/m0-a2a-streaming-pipeline/tasks.md Deliverables YAML；tasks 1.1） | pass | 0/2 | - |
| protocol-doc-single-definition | docs/protocol.md 与协议常量单一定义：文档覆盖字节序（大端）、逐消息字段表（HELLO 0x01/HELLO_ACK 0x02/IDR_REQUEST 0x05）、握手时序、错误码、M1 C++ 化兼容（紧凑排列无填充/版本）；protocol/Messages.kt 定义 PROTOCOL_VERSION 与 MsgType 0x01-0x09 常量，两端引用同一实现（出处：specs/stream-protocol-core「协议文档与常量单一定义」；tasks 1.3；docs/protocol.md:14-16、Messages.kt:17-29） | pass | 0/3 | - |
| protocol-messages-complete | 控制通道消息集完备：Messages.kt 定义 HELLO/HELLO_ACK/START_SESSION/STOP_SESSION/IDR_REQUEST/INPUT_EVENT/STATS/PING/PONG 全部九类消息类与 Unknown 兜底类（未定义类型消息被忽略而不中断会话的解码基础）（出处：specs/stream-protocol-core「控制通道消息集」；tasks 1.2；Messages.kt class 清单） | pass | 0/3 | - |
| protocol-unit-tests-green | 协议共享模块单元测试全绿：protocol 模块 ./gradlew test（Framing 12 + Stats 5 + Loopback 7 + P2pAndStun 8）覆盖 HELLO 编解码往返、HELLO_ACK rejected 携带原因（token 错误拒绝）、unknown 消息忽略不中断、UDP 分片乱序重组/缺片超时丢弃/重复片忽略、视频帧头全字段往返、lan-direct 环回低 RTT、断连事件分类（出处：tasks 1.2；specs/stream-protocol-core 全部 Scenario；specs/transport-backends「lan-direct 后端」；FramingTest.kt/TransportLoopbackTest.kt 用例名）。环境依赖（Gradle 发行版与依赖经镜像下载，首次构建较久）；无 fallback（镜像不可达按环境问题升级） | pass | 0/4 | - |
| transport-backends-surface | 传输三后端落地：TransportBackend.kt 统一抽象（connect/accept/send/recv/close/stats 语义）、LanDirectBackend/FrpBackend/P2pBackend 三实现、frp 后端含公网入口 112.74.85.74:22333 常量、P2pBackend 含 5s 未成回落 frp 逻辑、StunClient（SRFLX 发现）与 Diagnostics（NAT 判定→结构化 JSON，endpoint-independent/SRFLX 字段）与 Stats（RTT/丢包/码率/fps）落位（出处：tasks 2.1-2.5；specs/transport-backends「传输后端统一抽象」「p2p 后端」「frp 后端」「直连诊断」「传输统计」） | pass | 0/4 | - |
| nat-check-probe-runs | 直连诊断脚本可运行：nat_check.py 以 exit 0 完成（同 socket 多节点 STUN 探测），输出本机 socket 行与各节点探测行（成功 external: 映射或 FAILED 重试标记，二者其一必现）；国内 qq/miwifi 被滤时 cloudflare 兜底。环境依赖（公网 STUN 可达性，本机基线 endpoint-independent/cone，仅 cloudflare 可达时判定行缺席属环境因素）；有 fallback: 脚本内置 3 节点 × 3 次重试，全部失败仍 exit 0 探测行可见（出处：tasks 2.4；specs/transport-backends「直连诊断」；scripts/nat_check.py:66-99） | pass | 0/4 | - |
| source-pipeline-surface | 源端采集编码管线关键实现：EncoderPipeline 用 MediaCodec H.264（video/avc），ScreenCapture 含 MediaProjection 整屏采集，VirtualDisplaySession 创建虚拟屏，SessionManager.startSingleApp 失败时经 HELLO_ACK REJECT_UNSUPPORTED_MODE 携带明确原因回传（单应用模式在模拟器镜像不可用的降级路径，见 README 已知限制与 device-notes §2.1，真机重测为人工步骤——本场景按该替代口径出题，不派发镜像上无法达成的虚拟屏验收），InputInjector 含 displayId 归属与归一化坐标换算，obs/PipelineCounters 逐级计数，ShellBootstrap detect 失败返回明确降级原因（出处：tasks 3.1-3.6；specs/source-capture-encode；StreamService.kt:269-284、ShellBootstrap.kt:32-47） | pass | 0/4 | - |
| client-surface | 客户端解码渲染与输入关键实现：DecodeRenderActivity 用 MediaCodec + SurfaceView/SurfaceTexture 硬解渲染，TouchChannel 坐标归一化 [0,65535] + 边缘侧滑触发远端 back，StatsView 展示 fps/RTT/码率统计，SessionDrawerActivity 挂机会话列表，ConnectActivity 支持 autoHost/autoBackend/autoWidth/autoPackage 等矩阵自动化 extras 与「握手被拒」原因回显（出处：tasks 4.1-4.5；specs/client-decode-render 全部 Requirement；TouchChannel.kt:11-13、ConnectActivity.kt:369-423） | pass | 0/4 | - |
| scripts-syntax-compile | 脚本静态健康：四个 Python 脚本（run_matrix/measure_latency/udp_relay/nat_check）通过 py_compile，bootstrap_adb.sh 与 build_shell_helper.sh 通过 bash -n 语法检查（出处：tasks 5.2/5.3/6.1 交付脚本） | pass | 0/2 | - |
| run-matrix-cli-surface | run_matrix.py CLI 自描述：--help 以 exit 0 输出「M0 验收矩阵」用法，暴露 --serials/--seconds/--only/--report 四参数（--only 支持 backend,load,res 组合过滤）（出处：tasks 6.1；scripts/run_matrix.py:232-238） | pass | 0/2 | - |
| run-matrix-invalid-arg-fails | 异常路径：run_matrix.py 收到未定义参数时以非零退出码拒绝并输出用法/错误（argparse 惯例），不得静默忽略未知参数继续跑矩阵（出处：specs/validation-harness「矩阵自动化」自动化纪律；run_matrix.py:233） | pass | 0/2 | - |
| run-matrix-judge-rules | 可用判定标准机械化：import run_matrix.judge 对 ComboResult 边界逐条断言——游戏档 fps≥45 且延迟 P95≤200ms 才 PASS（fps=44/P95=201 均 FAIL 且原因含指标）、断流>0 一票 FAIL、应用档码率≤1000kbps PASS/超限 FAIL（出处：specs/validation-harness「可用判定标准」；run_matrix.py:109-120） | pass | 0/2 | - |
| measure-latency-missing-arg-fails | 异常路径：measure_latency.py 缺必填 --client-serial 时以非零退出码拒绝并输出含参数名的用法提示，不产生半截产物（出处：tasks 5.2；scripts/measure_latency.py:99） | pass | 0/2 | - |
| udp-relay-proxy-loopback | udp_relay.py proxy 模式功能正确（adb 无 UDP 转发的补偿件）：本机拉起 UDP 回声服务与 relay proxy（listen→upstream），客户端经 relay 发包收到原样回声，双向转发链路机械验证，退出后清理后台进程（出处：tasks 5.3；design Decisions 4 frp-UDP 主机侧中继；scripts/udp_relay.py proxy 子命令） | pass | 0/2 | - |
| readme-content-check | README.md 内容完备：Android A2A 云手机简介、快速开始（双模拟器）、架构总览、单应用/挂机范式说明、已知限制（M0 无加密红线、单应用模拟器镜像限制真机重测、p2p 同宿主假阳性说明）（出处：tasks 6.4；design Non-Goals 加密红线；README.md:3-13,95-100） | pass | 0/3 | - |
| device-notes-content-check | docs/device-notes.md 内容完备：shell bootstrap 说明（app_process 范式与镜像 SIGKILL 限制）、真机电池白名单/OEM 杀后台引导、M0 未加密禁止真机公网承载红线、真机对真机 P2P 复测人工步骤（出处：tasks 6.3；design Risks；device-notes.md:40,80-92） | pass | 0/3 | - |
| validation-report-wellformed | docs/validation-report.md 为实跑矩阵产物且结构完备：3 网络（lan-direct/p2p/frp）×2 负载（game/static）×2 分辨率（720/1080）逐组合指标表（fps 均值/P95 帧间隔/延迟 P50 P95/码率/丢包/断流）、PASS 判定与 12/12 结论、判定门槛说明、白闪延迟口径、双模拟器拓扑局限（p2p 假成功）与真机差距分析（出处：tasks 6.2；specs/validation-harness「矩阵自动化」「可用判定标准」「端到端延迟测量」；validation-report.md:5-54） | pass | 0/3 | - |
| emulators-boot-ready | 双模拟器在线前置检查：adb devices 至少两台 emulator 处于 device 状态且 sys.boot_completed=1（后续模拟器链路场景的前置门槛）。环境依赖（宿主双模拟器常驻运行）；无 fallback（模拟器由宿主环境提供，缺位按环境问题升级处置）（出处：design 环境事实 emulator-5554/5556；tasks 5.3 健康检查） | pass | 0/2 | - |
| bootstrap-adb-full-run | 一键 bootstrap 全流程：bootstrap_adb.sh all 完成双模拟器健康检查、增量构建并安装 source/loadgen→源端、client→客户端、helper dex 推送 /data/local/tmp、adb root、TCP forward tcp:22333、模拟器控制台 UDP redir udp:22334、前台服务拉起与 MediaProjection 授权 uiautomator 自动点击；跑完机械断言：两端包已装、dex 已推、forward/redir 列表含目标端口（出处：tasks 5.3；design Decisions 4/5；bootstrap_adb.sh:130-144）。环境依赖（双模拟器、授权弹窗自动化）；有 fallback: uiautomator 3 次重试+失败截图存档 docs/bootstrap-screenshots，授权未确认时脚本仍返回 0 由后续矩阵 ensure_pipeline 重授权兜底 | pass | 0/2 | - |
| loadgen-game-launch | 游戏档负载 App 可独立启动：am start -W 启动 GameLoadActivity 返回 Status: ok，2s 后前台焦点归属该 Activity（Choreographer 60fps 动画+帧计数 overlay+可点击按钮为矩阵游戏档 fps/触摸往返判据的采集对象）。环境依赖（双模拟器在线且 bootstrap 已装包）；无 fallback（出处：tasks 5.1；specs/validation-harness「负载生成 App·游戏档恒定帧率」；GameLoadActivity.kt:14-22） | pass | 0/2 | - |
| loadgen-static-launch | 应用档负载 App 可独立启动：am start -W 启动 StaticAppActivity 返回 Status: ok，2s 后前台焦点归属该 Activity（静态列表页画面静止→源端编码帧率自然下降，为应用档码率≤1Mbps 判据的采集对象）。环境依赖（双模拟器在线且 bootstrap 已装包）；无 fallback（出处：tasks 5.1；specs/validation-harness「负载生成 App·应用档静止画面」） | pass | 0/2 | - |
| source-app-launch | 源端 App 入口可启动：LAUNCHER 类别拉起源端应用（MainActivity+ShellBootstrap 权限检测路径，shell 助手缺位时降级提示不崩溃），2s 后前台焦点归属源端包。环境依赖（双模拟器在线且 bootstrap 已装包）；无 fallback（出处：tasks 3.1；specs/source-capture-encode；ShellBootstrap.kt detect 降级路径） | pass | 0/2 | - |
| client-app-launch | 客户端 App 入口可启动：LAUNCHER 类别拉起客户端应用（MainActivity/ConnectActivity 连接 UI：host:port、token、传输后端选择、模式与分辨率配置），2s 后前台焦点归属客户端包。环境依赖（双模拟器在线且 bootstrap 已装包）；无 fallback（出处：tasks 4.3；specs/client-decode-render「连接 UI 与会话抽屉」） | pass | 0/2 | - |
| e2e-lan-fullscreen-smoke | 端到端整屏链路冒烟（lan-direct）：run_matrix --only lan-direct,game,720p --seconds 15 一键完成采集参数下发、客户端自动连接、游戏档负载启动、STATS 采集与判定，exit 0 且 progress JSON 记录该组合 passed=true（隐含硬解出帧、fps≥45、延迟 P95≤200ms、零断流整链贯通）。22333 为产品源端端口常量（bootstrap SOURCE_HOST_PORT 缺省值），非验证实例端口。环境依赖（双模拟器+源端投影授权）；有 fallback: run_matrix 组合内建失败重试 1 次 + ensure_pipeline 授权重建（出处：tasks 6.1；specs/client-decode-render「硬解渲染」、specs/transport-backends「lan-direct 后端」；run_matrix.py:168-205） | pass | 0/5 | - |
| measure-latency-roundtrip | 端到端延迟双口径测量：会话播放中 measure_latency.py --client-serial 客户端串 --duration 15 采集，exit 0 表明 PTS 差值样本>0，样本 JSON 存档落盘且含 ptsDelta/whiteFlash 双口径 P50/P95 汇总（触摸往返经注入点击→负载白闪回执采集）。环境依赖（前序 e2e 冒烟已建立的播放中会话）；无 fallback（出处：tasks 5.2；specs/validation-harness「端到端延迟测量」；measure_latency.py:97-124） | pass | 0/5 | - |
| matrix-full-12-combos | 一键全矩阵：run_matrix.py --seconds 30 无人工干预跑完 3 网络（lan-direct/p2p/frp）×2 负载（game/static）×2 分辨率（720p/1080p）=12 组合，exit 0（全部 PASS），progress JSON 恰 12 条且逐条 passed=true，报告 markdown 生成（组合失败自动重试 1 次）。环境依赖（双模拟器+公网 frp 隧道+STUN 可达）；有 fallback: 组合级重试 1 次、p2p 5s 未成自动回落 frp、UDP 连续失败自动降级 TCP、授权失败 ensure_pipeline 重建（出处：tasks 6.1/6.2；specs/validation-harness「矩阵自动化」；run_matrix.py:250-272） | pass | 0/5 | - |

## 本轮场景集变更

场景集与持久化集一致，无变更。

