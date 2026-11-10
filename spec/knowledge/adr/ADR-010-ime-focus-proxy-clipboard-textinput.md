# ADR-010：剪贴板与组合文本注入经 IME 焦点代理

date: 2026-11-10
status: accepted

## Context

Android 10+ 限制后台应用读写剪贴板：仅持焦点的进程（或满足前台可读条件的应用）可访问。源端串流 Service 无焦点，「Service 直接读写剪贴板」的传统通路在新平台不可行；InputMethodService 天然持有焦点，是唯一可常驻的合法焦点代理载体。同时中文等完整 Unicode 注入无法经既有按键事件通道或 shell `input text`（仅 ASCII）达成，只有 IME commitText 能注入任意文本。两条能力缺口同源：都需要一个持焦点的合法代理。

## Decision

源端 APK 内置极简输入法 CloudImeService（无键盘 UI 的 InputMethodService，单 APK 不新增进程）作为剪贴板与组合文本的唯一焦点代理：客户端→源端剪贴板写入经 CLIPBOARD_SET 由 IME setPrimaryClip 完成（持焦点合法，全版本可用）；源端→客户端剪贴板读取与中文注入仅在 IME 活跃（源端输入框获焦）或源端 App 满足前台可读条件时可用。方向不对称如实呈现：不可用态回执明示原因、客户端按钮置灰，禁止静默失败；IME 未启用时文字输入以纯 ASCII shell 兜底，非 ASCII 明确报错。后续任何剪贴板或文本注入能力都必须经该代理扩展，不得绕过平台焦点规则另辟通路。

## Consequences

- 剪贴板反向读取与中文输入依赖 IME 启用与焦点状态：部署多一步 ime enable/set（bootstrap 脚本自动化 + 源端设置页引导兜底），无焦点时能力不可用且必须显式降级。
- CloudImeService 成为 source APK 常驻组件，其生命周期（被禁用即桥接置空）直接决定剪贴板/文本通道可用性。
- 源端本地外放与剪贴板行为不受串流影响，读取始终在平台规则内进行，无特权绕过。
