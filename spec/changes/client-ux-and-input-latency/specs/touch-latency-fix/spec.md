# touch-latency-fix — 触摸响应全链延迟插桩与修复

## ADDED Requirements

### Requirement: 全链延迟插桩
系统 SHALL 能量化触摸链路各阶段耗时：①客户端触摸事件产生 ②源端收到 InputEvent ③注入完成 ④源屏内容变化被采集（下一帧 VD 帧）⑤编码出帧 ⑥客户端解码渲染完成。各阶段 SHALL 有可检索日志（cloudphone-* tag）或计数器，至少支持人工定位最大延迟段。

#### Scenario: 静屏触摸延迟分段可见
- **WHEN** 串流静屏画面（≈1fps）下客户端注入一次触摸
- **THEN** 日志中可读出 ②③（应 ms 级）与 ④⑤（静屏下为主要延迟段）的分段耗时

### Requirement: 输入触发响应提速
触摸注入后源端 SHALL 采取措施缩短「注入→视觉变化」延迟（机制按测量结论选定：输入后短时提升采集/合成驱动频率、客户端 latest-wins 渲染兜底、去 Nagle 等），使静屏场景触摸→客户端视觉变化 P95 显著低于现状（现状秒级）。

#### Scenario: 静屏触摸响应达标（模拟器口径）
- **WHEN** 双模拟器 lan-direct 下串流静屏画面，脚本以 300ms 间隔注入 20 次触摸
- **THEN** 触摸→客户端画面可见变化的 P95 ≤ 800ms（修复前实测秒级，修复后须量化对比并记录在 device-notes）

#### Scenario: 游戏负载触摸不回退
- **WHEN** 60fps 游戏负载下注入连续滑动
- **THEN** 触摸响应无明显劣化（P95 ≤ 300ms 模拟器口径），且帧率不低于修复前同场景实测

### Requirement: 延迟口径文档化
docs/device-notes.md SHALL 记录：模拟器（swiftshader 软渲染）与真机的延迟口径差异、真机目标（触摸→视觉变化 P95 ≤ 300ms）、测量方法（measure_latency.py 触摸口径）。

#### Scenario: 文档含可复现测量步骤
- **WHEN** 用户按 device-notes 步骤在真机执行测量
- **THEN** 可得到与模拟器同口径的 P50/P95 数据
