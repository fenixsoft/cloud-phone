# 串流档位传递

<!-- knowledge: capability=resolution-passthrough rev=1
     sources=spec/changes/client-ux-and-input-latency -->

串流分辨率/帧率/码率由客户端连接页选择并经 START_SESSION 下发：默认跟随本机分辨率，源端按 aspect-exact 等比适配。

## 规则

- 分辨率档位：连接页提供「跟随本机」（默认，取客户端当前屏幕真实像素尺寸：API 30+ 用 maximumWindowMetrics，低版本降级 realDisplayMetrics）、「720p」、「1080p」、「自定义宽高」；选定值随 START_SESSION 的 width/height 下发源端。
- 横竖屏映射：「跟随本机」取当前朝向尺寸；720p/1080p 按客户端当前朝向映射（竖屏 720x1280 / 1080x1920）。
- 帧率与码率档位：帧率 30/60（默认 60）、码率 4/8/16/20 Mbps（默认 8），随 START_SESSION 的 fps/bitrateKbps 下发；选 30fps 时源端按 ≤30fps 目标出帧。
- 客户端本地校验：width 320–2560、height 240–2560（与源端编码钳制区间一致），非法输入本地拦截并提示合法范围、不发请求；对齐交给源端处理，客户端只做范围校验。
- 源端适配语义（aspect-exact）：以请求档位短边为基准，按源端自身屏幕宽高比等比适配并做 /16 对齐（允许 ±15px 级偏差，日志可见）；宽高比与源屏不一致时客户端 letterbox 显示无扭曲。协议文档明确「客户端传的是目标档位，源端保证短边吻合、宽高比与源屏一致」。
- 档位仅在连接时生效，同一会话内不改参数；源端对同参请求幂等、参数变化才重建管线。

## 代码锚点

- 主实现：client/app/src/main/kotlin/cloudphone/client/ConnectActivity.kt（档位 UI、范围校验与下发）
- 单源符号：source/app/src/main/kotlin/cloudphone/source/EncoderPipeline.kt（SessionParams.normalized 钳制）、source/app/src/main/kotlin/cloudphone/source/ScreenCapture.kt（aspect-exact 适配与管线重建）
- 守卫测试：无

## 溯源

- 2026-11-09 client-ux-and-input-latency（spec/changes/client-ux-and-input-latency/）— 新建本页：确立档位选择、本地校验区间与源端 aspect-exact 适配语义（协议零改动，纯客户端 UI 增强）。
