# windows-desktop-client — Windows 桌面串流客户端（与 Android 客户端全对齐）

## ADDED Requirements

### Requirement: 协议层复用与工程形态
Windows 客户端 SHALL 作为独立 Gradle 工程 `client-windows/`（Kotlin JVM 17 + Compose Desktop）经复合构建 `includeBuild("../protocol")` 消费 `protocol/`（依赖坐标 `cloudphone:protocol:0.1.0`），协议消息、帧编解码、三传输后端、加密、统计 SHALL 全部复用 protocol 模块，SHALL NOT fork 或重实现任何协议/传输逻辑；SHALL NOT 改动 protocol/、source/、client/ 现有代码。

#### Scenario: 复合构建解析
- **WHEN** 在 `client-windows/` 执行 `./gradlew build`
- **WHEN** 依赖图中的 `cloudphone:protocol:0.1.0` 被复合构建替换为本地 protocol/ 模块
- **THEN** 构建成功且产物单一代码源（protocol 类仅来自 `../protocol`）

#### Scenario: 既有工程零影响
- **WHEN** 在仓库根对 protocol/、source/、client/ 做 git diff
- **THEN** 无任何改动（client-windows 为纯新增顶层目录）

### Requirement: 解码渲染管线与 latest-wins
视频片 SHALL 经 FFmpeg(JavaCV) H.264 解码为 RGBA 帧并经 Compose Canvas 上屏；渲染 SHALL 采用 latest-wins 语义（单消费者仅保留最新待解码帧，积压帧丢弃并计数）；视口 SHALL 按源分辨率 letterbox 居中，窗口缩放不拉伸比例；HUD 分辨率 SHALL 取解码实际值而非 START_SESSION 请求值。

#### Scenario: 视频端到端上屏
- **WHEN** 客户端与源端建立会话且源端推流
- **THEN** 客户端窗口内 ≤3s 出现画面（lan-direct 路径），画面随源端内容变化

#### Scenario: 卡顿兜底
- **WHEN** 解码积压（网络突发导致帧堆积）
- **THEN** 仅丢弃积压帧保留最新帧上屏（不逐帧补播），丢弃计数进入 HUD/统计

#### Scenario: 比例保持
- **WHEN** 用户拖拽改变窗口尺寸
- **THEN** 画面按源宽高比 letterbox 缩放，无拉伸变形，黑边填充剩余区域

### Requirement: 鼠标与键盘输入映射
左键 press/move/release SHALL 映射为 InputAction DOWN/MOVE/UP（坐标按视口映射归一化 65535，letterbox 黑边区域点击不产生越界坐标）；按住左键拖动 SHALL 等效触摸滑动；滚轮 SHALL 产生连续小步滑动；右键 SHALL 等效 BACK（复用 Android 端 BACK 注入路径）；HOME/RECENTS SHALL 保持占位禁用（对齐 Android 端现状）。

#### Scenario: 单击与拖动
- **WHEN** 用户在画面区左键单击/按住拖动
- **THEN** 源端对应位置产生触摸按下/滑动序列，坐标落点与视口映射一致

#### Scenario: 右键返回
- **WHEN** 用户在画面区右键单击
- **THEN** 源端收到 BACK 注入，行为与 Android 端边缘侧滑返回一致

#### Scenario: 黑边区域防护
- **WHEN** 用户在 letterbox 黑边区域点击
- **THEN** 不产生越界归一化坐标的 INPUT_EVENT

### Requirement: 三传输后端接入与加密
客户端 SHALL 经 protocol 模块支持 lan-direct / p2p / frp 三后端选择与自动回落（p2p 失败回落 frp，回落 SHALL 呈现通告条）；加密 SHALL 经 enableCrypto(CryptoCodec) 启用（AES-128-GCM 片级 + HKDF 方向子钥 + 512 重放窗口语义由 protocol 保证）；token 错配 SHALL 呈现 REJECT_BAD_TOKEN 明确失败（非静默）；HUD SHALL 显示传输后端与加密状态徽章。

#### Scenario: lan-direct 直连
- **WHEN** 用户选择 lan-direct 后端连接宿主 adb forward 地址
- **THEN** 会话建立，HUD 显示 lan-direct + 加密徽章，RTT ≤5ms 量级

#### Scenario: p2p 回落 frp
- **WHEN** p2p 打洞失败（超时）
- **THEN** 自动回落 frp 建立会话，界面出现回落通告条，HUD 后端徽章更新为 frp

#### Scenario: token 错配
- **WHEN** 用户输入错误 token 连接
- **THEN** 连接失败并明确提示鉴权拒绝（REJECT_BAD_TOKEN），不进入会话

### Requirement: 音频下行
客户端 SHALL 消费 AUDIO_CHUNK(ADTS AAC-LC 48kHz stereo)：JavaCV 解码 → PCM 经 javax.sound.sampled 播放；jitter 语义对齐 aux-channels-m3（120ms 起播水位、队列 32 帧上限、控制通道积压>800ms 丢最旧音频帧保视频）；音量 popover SHALL 在播放端做音量控制；音频不可用时 SHALL 给出明确原因文案（非静默无声）。

#### Scenario: 音频端到端
- **WHEN** 源端播放有声内容且客户端音频开启
- **THEN** 客户端听到声音（连续、无可感知断流）

#### Scenario: 积压防护
- **WHEN** 控制通道积压超过阈值
- **THEN** 丢弃最旧音频帧，视频不阻塞，统计计数更新

#### Scenario: 音量调节
- **WHEN** 用户在音量 popover 拖动滑杆
- **THEN** 播放音量即时变化且不影响传输与解码

### Requirement: 剪贴板与文字输入
客户端粘贴 SHALL 经 CLIPBOARD_SET 下发源端（10s 超时降级提示，非静默）；源端复制 SHALL 经 CLIPBOARD_GET/CLIPBOARD_TEXT 回执到本地剪贴板（10s 超时降级）；文字输入面板 SHALL 经 TEXT_INPUT{seq,utf8} 注入完整 Unicode（含中文），每条等待 ACK（5s 超时提示失败），seq 去重语义由源端保证；AuxNotice 降级 SHALL 驱动对应工具坞按钮置灰并显示原因。

#### Scenario: 粘贴到源端
- **WHEN** 用户在文字面板点「粘贴」且源端剪贴板通道可用
- **THEN** 本地剪贴板文本经 CLIPBOARD_SET 更新到源端，超 10s 无回执则提示降级

#### Scenario: 中文注入
- **WHEN** 用户在文字面板输入中文并发送、源端 IME 活跃
- **THEN** 源端输入框出现完整中文文本，客户端收到 ACK{seq} 成功

#### Scenario: 源端复制到本地
- **WHEN** 源端复制文本且通道可用
- **THEN** 文本出现在客户端本地剪贴板

### Requirement: 连接首页与档案持久化
首页 SHALL 呈现：顶栏+网络徽章、扫描面板（进度 ≤10Hz 节流）、发现卡片（连接/单应用入口）、档案卡片（连接/删除，探测无响应置灰）、常驻新建连接抽屉；新建表单 SHALL 含名称/主机（独占行）/TCP+UDP 端口并排（默认 22333,22334）/后端三选（默认 lan-direct）/token 密码态/高级折叠（档位默认跟随本机、60fps、8Mbps）；档案 SHALL 持久化到 `~/.cloudphone/profiles.json`（host+tcpPort+backend 去重、连接成功自动入库），token SHALL 仅存本机文件且不出日志；扫描结果 SHALL NOT 写入档案。

#### Scenario: 新建并连接入库
- **WHEN** 用户经抽屉新建档案并连接成功
- **THEN** 首页出现该档案卡片，重启应用后仍在（含档位偏好）

#### Scenario: 局域网发现
- **WHEN** 同网段存在源端且用户展开扫描面板
- **THEN** ≤10s 内发现卡片出现该源端（host+port 去重），扫描结果不写档案

#### Scenario: 档案置灰
- **WHEN** 档案主机当前探测无响应
- **THEN** 该档案卡片连接按钮置灰，删除按钮可用

### Requirement: 控制台与会话能力
控制台 SHALL 呈现：letterbox 画面、玻璃拟态 HUD（fps/码率/RTT/丢包/分辨率/加密态）、底部工具坞（键盘/截屏/档位循环/旋转本地/单应用/断开）、虚拟导航键、右侧会话抽屉；挂起 SHALL 停止视频消费保持控制链（可恢复），恢复 SHALL 重新拉流，断开 SHALL 回首页且会话在源端正确终止；会话中档位循环 SHALL 按 1080P·60→720P·60→1080P·30→自定义 序列重发 START_SESSION 不断控制链；截屏 SHALL 保存当前帧到本地文件。

#### Scenario: 挂起与恢复
- **WHEN** 用户挂起会话后恢复
- **THEN** 视频停止消费后重新拉流上屏，控制链未断开，HUD 恢复更新

#### Scenario: 会话中切换档位
- **WHEN** 用户点击档位循环
- **THEN** 重发 START_SESSION 切换分辨率/帧率/码率，控制链不断，画面切换流畅，HUD 分辨率更新为解码实际值

#### Scenario: 断开回首页
- **WHEN** 用户断开会话（按钮或 Ctrl+Shift+X）
- **THEN** 回到首页，源端会话终止，档案保持

### Requirement: 单应用串流
单应用入口 SHALL 发送 AppListRequest 并在 1s 内期望回传（超时显示重试入口）；单应用列表 SHALL 呈现磁贴网格（图标占位+应用名，不显示包名），支持名称搜索；选择磁贴 SHALL 进入单应用串流并显示单应用徽章，可退出回列表；磁贴 SHALL 支持固化为桌面快捷方式。

#### Scenario: 列表回传
- **WHEN** 用户进入单应用列表且源端在线
- **THEN** 1s 内磁贴呈现应用名列表；超时则显示重试入口而非空列表静默

#### Scenario: 单应用串流
- **WHEN** 用户点击某应用磁贴
- **THEN** 进入该应用串流会话并显示单应用徽章，退出后回列表

### Requirement: 档位选择与校验
档位 SHALL 支持 跟随本机/720P/1080P/自定义 × 30/60fps × 4/8/16/20Mbps；自定义 SHALL 本地校验 width 320–2560 / height 240–2560 且 aspect-exact（宽高比与跟随本机目标一致）；校验失败 SHALL 表单内联报错（不发 START_SESSION）；默认档位 SHALL 为 跟随本机/60fps/8Mbps。

#### Scenario: 自定义档位合法
- **WHEN** 用户输入 1080×2340·60fps·12Mbps 范围外值（如 width=300）
- **THEN** 表单内联报错且连接使用默认/上次合法档位

#### Scenario: 默认档位
- **WHEN** 新档案未修改高级选项
- **THEN** 会话以跟随本机/60fps/8Mbps 发起 START_SESSION

### Requirement: 桌面形态与打包
客户端 SHALL 提供自定义窗口标题栏（最小化/关闭）、全屏模式（F11 或按钮）；快捷键 SHALL 至少含 F11 全屏 / Ctrl+Shift+X 断开 / Ctrl+Shift+K 键盘面板；SHALL 提供 jpackage 打包配置（Windows 下可产出安装包）；文档 SHALL 说明 JRE 要求、防火墙出站规则、VM 冒烟验证步骤。

#### Scenario: 快捷键
- **WHEN** 会话中按 Ctrl+Shift+X
- **THEN** 断开回首页；按 F11 切换全屏；按 Ctrl+Shift+K 打开文字输入面板

#### Scenario: 打包产物
- **WHEN** 在 Windows 机器执行打包任务
- **THEN** 产出可安装的 Windows 安装包（jpackage），安装后可直接启动客户端

### Requirement: Windows 实机冒烟验证链路
`scripts/win_vm_smoke.sh` SHALL 在宿主构建客户端产物、分发进 dockur/windows VM、以软件渲染回退启动、经 RDP/QEMU monitor 截图取证；链路 SHALL 覆盖：首页渲染、与宿主模拟器源端（10.0.2.2:22333 经 adb forward）连接建立、视频上屏、断开回首页；脚本 SHALL 显式探测网络可达性并在不可达时输出诊断（非静默失败）。

#### Scenario: 冒烟端到端
- **WHEN** 宿主模拟器源端就绪且执行 win_vm_smoke.sh
- **THEN** 脚本产出 VM 内客户端的首页/会话/断开各阶段截图，会话阶段截图含非黑视频区与 HUD

#### Scenario: 网络不可达诊断
- **WHEN** VM 到宿主 forward 端口不可达
- **THEN** 脚本输出可达性探测结果与修复预案提示（端口映射/socat 中转），退出码非 0
