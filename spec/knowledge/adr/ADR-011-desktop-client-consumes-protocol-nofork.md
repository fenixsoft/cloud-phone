# ADR-011：客户端新形态经复合构建消费 protocol，协议零 fork

date: 2026-11-10
status: accepted

## Context

项目需要第二个客户端形态（Windows 桌面端）覆盖桌面观看与操作场景。protocol/ 已是零 Android 依赖的纯 Kotlin JVM 库（消息/帧编解码/三传输后端/加密/统计），若每个客户端形态各自实现协议栈，将出现多份协议实现漂移：消息布局、加密协商、容错语义任何一处演进都要多方手工同步，回归面随客户端形态数量线性放大。

## Decision

客户端新形态一律以「消费方」姿态接入协议：独立 Gradle 工程经复合构建 `includeBuild("../protocol")` 以依赖坐标 cloudphone:protocol:0.1.0 引用本地 protocol/ 模块，协议消息、帧编解码、三传输后端、加密、统计全部复用，不 fork、不重实现任何协议/传输逻辑，新形态源码树不得出现协议包同名源文件；新形态自成一体不进根构建图，protocol/、source/、client/ 既有工程零改动。

## Consequences

协议单一事实源对全部客户端形态生效：协议演进一次改动、所有形态同时受益，Android 客户端对新形态无感知。代价是新形态被约束在 Kotlin/JVM 技术栈（Compose Desktop 同语言同工具链是配套选型）；protocol 模块的 API 面成为形态能力边界——形态特有需求（如桌面全键盘按键映射）协议无对应通道时，只能走既有通道语义（如 TEXT_INPUT）或推动协议侧演进，不得在形态内私改协议。
