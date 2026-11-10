# Windows 桌面串流客户端（续 2）

<!-- knowledge: capability=windows-desktop-client rev=1
     sources=spec/changes/windows-desktop-client
     part=2 -->

桌面端会话核心：解码渲染管线、会话编排状态机与鼠标/键盘输入映射（主页：[windows-desktop-client](windows-desktop-client.md)；辅助通道消费见[续 3](windows-desktop-client-3.md)）。

## 规则

- 解码渲染管线：视频片经 FFmpeg(JavaCV) H.264 解码为 RGBA 帧转 ImageBitmap 交 Compose Canvas 上屏（软解，解码循环为单消费者协程）；lan-direct 路径会话建立后 ≤3s 出现画面；HUD 分辨率读数取解码实际输出值而非 START_SESSION 请求值。
- latest-wins 渲染兜底（对齐 client-decode-render 同名语义）：解码按序进行（保 P 帧参考链完整），上屏仅取最新帧，积压的陈旧帧解码后丢弃并计数；积压过深时解码输入快进至下一关键帧边界（丢弃帧计数），不得在参考链中间直接丢弃待解码帧（解码器缺参考帧会产生花屏）；丢弃计数进入 HUD/统计，另备默认关闭的 debug 开关可 dump 原始 H.264 供黑屏/花屏取证。
- letterbox 视口：画面按源分辨率 letterbox 居中，窗口缩放保持宽高比不拉伸，黑边填充剩余区域。
- 会话编排：连接→HELLO/HELLO_ACK→START_SESSION→视频消费→输入回传的编排由独立 SessionController 状态机承担（挂起函数 + StateFlow 暴露会话/HUD/AuxNotice 状态），protocol 后端回调线程经 Channel 桥进协程，UI 只做状态投影，编排逻辑可脱离 UI 跑 JVM 单测。
- 挂起/恢复/断开：挂起停止视频消费但保持控制链（可恢复）；恢复重新拉流上屏且 HUD 恢复更新；断开（按钮或 Ctrl+Shift+X）终止源端会话并回首页，档案保持。
- 会话中档位循环：按 1080P·60 → 720P·60 → 1080P·30 → 自定义 序列以新参数重发 START_SESSION，控制链不断开，画面切换后 HUD 分辨率更新为解码实际值。
- 鼠标输入映射：左键 press/move/release 映射为 InputAction DOWN/MOVE/UP，坐标按 letterbox 视口换算归一化 65535（分辨率无关）；按住左键拖动等效触摸滑动；滚轮产生连续小步滑动；letterbox 黑边区域点击不产生越界坐标的 INPUT_EVENT（黑边防护钳制）。
- 键盘与导航语义：右键单击等效 BACK（复用 Android 端边缘侧滑返回的注入路径）；会话窗口获得键盘焦点时可打印字符键经 TEXT_INPUT 直发（等效文字面板语义）；非字符键（功能键/修饰键）不产生注入（协议无独立按键通道，scancode 全键盘映射为后续增强）；HOME/RECENTS 保持占位禁用。
- 控制台呈现：letterbox 画面 + 玻璃拟态 HUD 胶囊（fps/码率/RTT/丢包/解码实际分辨率/后端+加密徽章）+ 底部工具坞（键盘/截屏/档位循环/旋转本地/单应用/断开）+ 虚拟导航键（BACK 注入、HOME/RECENTS 占位）+ 右侧会话抽屉（挂起/恢复/断开）；旋转仅切换客户端本地窗口呈现不改源端；截屏保存当前解码帧到本地文件，会话不中断。
- 单应用串流：进入单应用列表发送 AppListRequest（源端目标 1s 内回传为源端侧语义，客户端不裁源端时限），客户端 3s 无应答判定失败并显示重试入口（不静默空列表）；磁贴网格呈现（图标占位+应用名，不显示包名），搜索框按名称实时过滤；点选磁贴进入单应用串流会话并显示「单应用 · <应用名>」徽章，退出回列表；磁贴支持固化为 Windows 桌面快捷方式（.lnk 写用户桌面目录，等效 Android requestPinShortcut + cloudphone://launch 直达语义）。

## 代码锚点

- 主实现：client-windows/src/main/kotlin/cloudphone/desktop/session/VideoPipeline.kt（解码与 latest-wins 计数）、client-windows/src/main/kotlin/cloudphone/desktop/session/SessionController.kt（会话编排状态机）、client-windows/src/main/kotlin/cloudphone/desktop/session/InputMapper.kt（鼠标键盘映射与黑边钳制）
- 单源符号：client-windows/src/main/kotlin/cloudphone/desktop/ui/SessionScreen.kt（控制台呈现投影）、client-windows/src/main/kotlin/cloudphone/desktop/ui/AppsScreen.kt（单应用列表/磁贴/.lnk 固化）
- 守卫测试：client-windows/src/test/kotlin/cloudphone/desktop/VideoPipelineTest.kt（合成 H.264 流断言解码与积压丢弃）、client-windows/src/test/kotlin/cloudphone/desktop/SessionControllerTest.kt（建链/回落/挂起恢复编排）、client-windows/src/test/kotlin/cloudphone/desktop/InputMapperTest.kt（映射边界与黑边）

## 溯源

- 2026-11-10 windows-desktop-client（spec/changes/windows-desktop-client/）— 新建本页：确立桌面端解码渲染 latest-wins 与 letterbox、SessionController 会话编排（挂起/恢复/档位循环/断开）、鼠标键盘输入映射与黑边防护、控制台呈现投影、单应用串流与 .lnk 快捷方式固化规则。
