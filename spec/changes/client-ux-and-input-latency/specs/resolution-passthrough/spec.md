# resolution-passthrough — 客户端向源端传递串流档位

## ADDED Requirements

### Requirement: 分辨率档位选择
连接页 SHALL 提供「分辨率」选择：默认档「跟随本机」（取客户端当前屏幕真实像素尺寸）、「720p」、「1080p」、「自定义」（宽×高输入）；选定值 SHALL 随 START_SESSION 的 width/height 字段下发源端。

#### Scenario: 默认跟随本机
- **WHEN** 客户端屏幕为 1080x2400 且用户未改分辨率选择即连接
- **THEN** START_SESSION 携带 width=1080 height=2400（或与该机真实分辨率一致的值）

#### Scenario: 选择 720p
- **WHEN** 用户选择「720p」并连接
- **THEN** START_SESSION 携带 width=720 height=1280（横竖屏按客户端朝向映射）

#### Scenario: 自定义分辨率非法输入拦截
- **WHEN** 用户输入 width=99999 或非正数
- **THEN** 连接被本地拦截并提示合法范围（宽高 240–2560，需 16 对齐由源端处理，客户端只做范围校验）

### Requirement: 帧率与码率可调
连接页 SHALL 提供帧率（30/60）与码率（4/8/16/20 Mbps）选择，默认 60fps / 8Mbps；选定值随 START_SESSION 的 fps/bitrateKbps 下发。

#### Scenario: 切换 30fps
- **WHEN** 用户选择 30fps 并连接成功
- **THEN** 源端编码器按 ≤30fps 目标出帧（客户端 StatsView fps 读数 ≤31）

### Requirement: 源端适配语义（既有 aspect-exact 语义确认）
源端 SHALL 以客户端请求档位的短边为基准，按源端自身屏幕宽高比等比适配（既有 aspect-exact 逻辑）；协议文档 SHALL 明确「客户端传的是目标档位，源端保证短边吻合、宽高比与源屏一致」。

#### Scenario: 1080x2400 请求在 20:9 源屏的适配
- **WHEN** 客户端请求 1080x2400，源端屏为 1080x2400（同比例）
- **THEN** 源端以 1080 短边等比采集（≈1080x2400，/16 对齐后允许 ±15px 级偏差并在日志可见）

#### Scenario: 720p 请求在 20:9 源屏的适配
- **WHEN** 客户端请求 720x1280，源端屏为 1080x2400（20.6:9 与 16:9 不同比例）
- **THEN** 源端采集为 720x1600（短边=720、源屏比例），客户端 letterbox 显示无扭曲
