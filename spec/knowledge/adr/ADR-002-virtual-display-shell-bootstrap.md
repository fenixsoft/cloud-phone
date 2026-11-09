# ADR-002：单应用模式走虚拟屏 + 特权 shell 助手进程

date: 2026-11-08
status: accepted

## Context

云端应用范式要求只串流单个 App（应用抽屉 → 点开单个 App → 退出回抽屉），而普通应用权限无法创建虚拟屏并跨屏启动任意应用，需要一条特权执行通路。

## Decision

单应用模式以虚拟屏承载目标 App（`am start --display` 启动指定包名），特权操作经 shell 助手进程执行（adb root + app_process，scrcpy 同款范式）。产品语义固定为两级权限：基础级 MediaProjection（整屏）/ 增强级 shell bootstrap（单应用）；真机授权链（Shizuku/无线调试）接入时两级权限产品语义不变。

## Consequences

模拟器镜像上 app_process 助手被静默 SIGKILL，单应用在该环境不可用，必须向客户端回传明确拒绝原因并以真机人工复测兜底；会话挂机语义建立在虚拟屏由源端 SessionManager 长期持有之上。
