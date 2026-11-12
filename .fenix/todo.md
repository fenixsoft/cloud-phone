## 2026-11-08

- 自上次自省以来无 SPEC 活动，跳过自省。

## 2026-11-09

### 浪费
- **client-ux规划首跑空转21分钟死亡，重跑再耗34分钟**
  - 自进化：问题成立 / 建议部分合理 / — 不需要
  - 影响：client-ux 规划阶段墙钟 55 分钟，首个规划 run（planner-plan，测试场景规划 agent）21.1 分钟跑完 24 轮后进程死亡，未落任何场景产物，第二个 attempt（同车道重试编号）从零重取证再花 33.9 分钟才交付，纯浪费约 21 分钟 agent 时长；对比 planner-plan 组中位 749.2s（数据摘要第六节口径）为本例单 attempt 的 2.7 倍。
  - 证据：run planner-plan-client-ux-and-input-latency-1794150065-a1（run_metrics 行：duration 1268.9s、54 turns、remaining_tasks=18、单轮停滞 max 604.9s）本地 23:22:14 打出 604.9s 停滞轮，23:22:15 fenixd.log 记「planner-plan failed (attempt 1)」，run jsonl 尾部 error 事件为「Exit code: 1」；23:22:25 派 attempt 2，23:56:18 成功（run_metrics duration 2032.8s、163 turns、91 次工具调用、落盘 35 条场景）。数据摘要把两 attempt 墙钟都记成 3311.961s，回源 run_metrics 实为 a1 1268.9s + a2 2032.8s 的串行和（≈3301.7s 加开销）——该字段是阶段墙钟同时记到两个 attempt，不是单 run 时长。
  - 调查评估：在处置 client-ux-and-input-latency（触控延迟与客户端 UX SPEC）规划阶段暴露。已核实事实：首 attempt 在第 24 轮 604.9s 的 API 往返（远超 300s 升级线）结束后整体退出，dsh-events.jsonl 事件流本身以 turn_end/final 正常收尾、无 stderr 栈，故死亡根因（网关断连、dsh 崩溃、本机资源）证据不足——未定、多假说并存，只断言「长停滞轮后进程退出、零产物」。与存量工作关系：既有的 in-place retry（原地重试，add-judge-artifact-gate）面向产物门失败，停滞判停（run-stop-loss）缺省关闭，均不覆盖本形态；attempt 重建时 worktree 复用但取证工作无增量产物可续，全部重做。结论：单次 provider 长尾即放大成 55 分钟规划阶段，缺产物增量落盘与停滞样本归因留存。
  - 建议：planner-plan 车道增加阶段内产物增量落盘（取证记录、场景草稿即写即存），attempt 重建时声明复用已落盘中间产物；run_metrics 对 ≥300s 单轮追加 provider 长尾标记字段，为后续 provider 分流提供逐 run 证据。

- **全窗16个run合计2.5小时API停滞，最长单轮604.9秒**
  - 自进化：问题成立 / 建议部分合理 / — 不需要
  - 影响：窗口 45 个 run 中 16 个命中 API 停滞（单轮往返 >60s 计一次 stall，停滞=请求发出后长时间无响应的等待），按深扫聚合行合计 9044 秒（约 2 小时 31 分）纯等待，占窗口 run 总时长的大头；成功 run 也不例外——planner-judge run 1807.8s 里 stalls=6、单轮最长 519.6s，implementer run 5759.1s 里 stalls=9、停滞合计 1147.6s。
  - 证据：逐 run 回源核实：16 个 run 的 run_metrics 行 stalls/max 与数据摘要第六节 16/16 一致（如 planner-plan a1 stalls=3 max=604.9、a2 stalls=10 max=376.5、impl-m0-a2a-streaming-pipeline-1794128070-a1 stalls=10 max=149.2 且 duration 9453.5s 为窗口最长 run）；合计 9044s 为该 16 行 total 字段求和（口径：run 内 >60s 往返样本时长求和，逐 run 总量行出自 daemon 深扫聚合，非本人重算）；fenixd.log 在飞侧仅逐条 WARNING 留痕（如本地 23:50:05「API round-trip stall: 376.5s (round #66)」），无任何干预动作。
  - 调查评估：跨 spec 现象（m0 与 client-ux 两车道、planner/implementer/judge/spec-review/admin 全角色都有），max 分布 79s–604.9s，形态指向 provider 网关长尾而非单车道问题；token 字段全窗为 0（usage_source=available 但 input_tokens=0），成本只能按时长口径量化，无法按金额。与存量工作关系：api_round_stall_escalated 升级信号（evo-add-api-stall-disposition-closeout 2.1）对 success 终态显式豁免（admin_monitor.py「曾停滞但正常完成」不落信号），而本窗 16 个停滞 run 全部成功收尾——升级面零触发；停滞判停机制另见改进条目。结论：本窗形态（成功收尾的长停滞）在现有信号与止损面上完全不可见，属系统性敞口而非一次性失误。
  - 建议：在日回顾聚合面（而非逐 run 告警，避免违背「延迟数据不触发独立人工告警」铁律）增加窗口级停滞合计观测行，并对 ≥300s 停滞轮打 provider 长尾样本标记，为 provider 分流/更换决策留证据。

### 失误
- **首日配置失误致模型路由全失效，单次评审烧19条run**
  - 自进化：问题成立 / 建议不合理 / — 不需要
  - 影响：m0-a2a-streaming-pipeline 首次方案评审（spec-review，SPEC 方案单轮只读评审 agent）从 16:07 拖到 16:26 才成功：19 条 run 记录里 15 条被两次 daemon 重启清扫、3 条死在 dsh 启动失败，另有 planner-plan 3 连秒挂（进程启动数秒内即死）触发 16:32-16:37 的 300 秒全车道冷却（冷却期间该 spec 的 implementer/fast-track/repair/planner-plan/verifier 派发与管理员唤起全部跳过），m0 流水线整体推迟约 47 分钟（16:07 起草、16:54 planner-plan 才成功）。
  - 证据：根因为单因确定性，证据充分：① 16:11 配置加载日志逐角色列出全部 13 个 agents.models.*.provider 引用未注册 provider 名（bigmodel.cn / inferaiapi.com / maomaoai / deepseek——注册表里只有 bigmodel-cn，属命名笔误类错误），加载器仅打 ERROR「已跳过，不阻塞加载」继续运行；② 宿主机无 claude CLI（实测 which claude 失败，dsh 在 /usr/bin/dsh），而 harness（spawn agent 进程的边界实现）缺省值是 claude（core/config.py HARNESS_DEFAULT），07:57-08:17 产生 36 条 FileNotFoundError 'claude' 日志（agent_runner 20、spec_review 15、arbiter 1）；③ dsh 侧诊断 startup-2026-11-08T08-22-25：fenix-model-config 插件断言「模型未显式配置」拒绝启动，headless-runner 等 4 插件连锁未激活——即失败样本里的「headless-runner (required)」输出。恢复轨迹：4 次配置热重载（16:09/16:11/16:26/16:32）加 daemon 重启（15:52 首启后 16:16、16:22、16:49 再启），16:26 spec-review 成功、16:54 planner-plan 成功；告警面 instant_fail_storm（告警 f5816fbcf092，秒挂风暴）与 spec-review-incomplete ×6（评审器连败至尝试上限，18:53 批量收口）。
  - 调查评估：在处置 m0-a2a-streaming-pipeline（A2A 串流管线 SPEC，当日 19:32 已 done）评审阶段暴露。已核实事实如证据所列，属首日 bring-up 的执行层配置失误：项目 15:52 才启动 daemon、16:04 才提交 bootstrap skeleton，配置编辑引入笔误 provider 名、且 harness 与宿主二进制不匹配，迭代 4 次热重载加 3 次重启才收敛；当日已修复（现行 config 13 角色全指向 bigmodel-cn、agent.harness=dsh，两 spec 后续全程正常）。两点残余缺口：① 同一笔误当天 19:37 二次回归——告警 83309dfbf020（provider_ref_invalid，agents.models.arbiter.provider 引用 bigmodel.cn，handled=false），现行 config 已无该键，修复动作无留痕（config.yaml 不入 git，无 diff 可核），此点标注未核实；② spawn 失败路径不写终态，15 条 run 记录残留到重启才被 startup terminal-state sweep（daemon 启动时把非终态 run 批量标 interrupted 的清扫机制）闭合，构成数据摘要「其他终态 19」的主体。与存量工作关系：加载器既有逐角色 provider 校验但刻意非阻塞，无派发前硬门——系统性修复见改进条目。
  - 建议：补两条低成本收尾：给 arbiter 笔误的修复补一条带留痕的处置记录或 commit 闭掉告警 83309dfbf020；spawn 失败路径即时写 error 终态，不再依赖重启清扫收敛非终态残留。

- **活性报告未入库阻塞merge-back 11分钟，双run收场**
  - 自进化：问题成立 / 建议部分合理 / — 不需要
  - 影响：client-ux-and-input-latency 的 merge-back（实现分支合并回主树）在 00:37-00:48 被主树 3 个未提交重叠文件阻塞 11 分钟，6 轮自动 merge-only 重试全败，最终消耗 1 个 admin run（13 轮）加 1 个 repair run（37 轮）才收场；同一 ERROR 每 2 分钟复记还触发了 2 条 daemon_errors 升级告警（指纹 3469e5f8），其中一条被在飞 admin run 覆盖跳过。
  - 证据：fenixd.log 00:37:05 起连续 6 条「Merge-back blocked by WIP overlap precheck (policy=escalate)」（WIP overlap precheck=合并前对主树未提交改动与分支改动路径重叠的拦截闸门，防 stash pop 静默丢弃，主树零污染），重叠文件为 docs/bootstrap-screenshots/projection_grant_try1.png、docs/validation-report.md、docs/validation-report.md.progress.json；告警 816fab70e5b5（mergeback_wip_overlap major）00:41 唤起 Administrator，admin 提交 07a7ee6（00:44，收编主树再生产物，validation-report 落为 20:17 矩阵重跑版 12/12 PASS）；00:46 merge rehearsal（合并预演）检出同 3 路径语义冲突进入 repair 通道，00:48 re-merge-back 完成（merge 提交 81c6395，parents=07a7ee6+d3b6857）。
  - 调查评估：在处置 client-ux-and-input-latency 收工合并时暴露。已核实事实：docs/validation-report.md 及其 progress.json 是 scripts/run_matrix.py 每次实跑都整文件再生的活性报告（git show 07a7ee6 可见 19:28 版被 20:17 版整表替换）；m0 验证车道 20:17 在主树重跑矩阵后只入库了 per-spec 的 verification-report（提交 eb26488 仅 +56 行该文件），再生主报告留在主树未提交；而 client-ux 分支在自己的 worktree 里也实跑矩阵并把同名文件提交上了分支（提交 7006cc8「6.1 矩阵实跑修正」）——两条车道写同一活性报告路径，merge-back 必撞重叠闸门。根因归属：再生后不入库是执行层遗漏，但「两车道共享活性报告写面」是结构性诱因，复发概率高（见改进条目）。次要留痕：repair run 收尾时「产物硬门未识别 agent_type='repair'（按无门放行）」与「预算计数对账偏差 used=1 vs 正常收工 run 数=0」两条 WARNING 属小缺陷。与存量工作关系：spec/changes 活跃区仅两 spec、archive 为空，无既有条目覆盖此写面治理。
  - 建议：矩阵/验证类再生脚本收尾即自动提交活性报告（或改写入归档专用路径）；merge-back 预检对已归因为主树再生产物的重叠自动收编放行，而非升级人工面；顺带修 repair 角色产物硬门识别与预算计数口径。

### 可改进
- **停滞监督面缺位：判停关闭加成功豁免，2.5小时停滞零止损**
  - 自进化：问题部分成立 / 建议部分合理 / — 不需要
  - 影响：复发敞口：按本窗实测，同类窗口单日约 9044 秒（2.5 小时）API 停滞等待、单轮最长 604.9 秒，因全部成功收尾而零告警零止损，每次照付；叠加判活通道因 claude CLI 缺失全程 fail-open（通道不可用时放行不拦截，liveness.py 自述「dsh 独立部署且宿主无 claude CLI」形态，最后一条 binary_missing 见 19:24），长 run 实际仅剩零事件/零产出兜底。
  - 证据：config.yaml 无任何 agents.run_stall_budget_* 键（core/config.py:469 缺省关闭，代码注释自述「evo-add-run-stop-loss 2.1，缺省关闭」）；admin_monitor.py _check_api_round_stall_escalated 对 success 终态显式豁免；本窗 16 个停滞 run 终态全部 success（run_metrics 回源核实）。
  - 调查评估：既有能力对账：停滞观测三层（60s 统计线、120s 在飞 WARNING 线、300s 升级线）与 run 停滞预算判停均已建成，缺的是「启用判定」与「成功终态聚合面」，非重复立项，也不是要新建告警流。机制铁律「延迟数据不触发独立人工告警」应继续遵守，建议动作落在既有判停机制的灰度启用与日回顾数据投影上。
  - 建议：SPEC 提案：① 灰度启用 run_stall_budget（先观测键后判定键，阈值按本窗 p90/max 实测校准）；② run_metrics 增加窗口级停滞合计投影供日回顾消费；③ ≥300s 停滞轮计入 provider 健康度计数，为分流换道提供依据；④ 评估判活通道改用 dsh 或随部署修复 claude 二进制。

- **配置错误无派发前预检，全角色失效仍连发19分钟**
  - 自进化：问题成立 / 建议部分合理 / — 不需要
  - 影响：复发敞口：单次同类配置失误按本窗实测代价为 25 次无效派发 run（19 条评审记录加 3 次秒挂加 2 次 arbiter、1 次 administrator 失败）、12 条以上告警记录、3 次 daemon 重启、47 分钟流水线延迟；一次派发前预检可把该代价压到零。
  - 证据：加载器对未注册 provider 仅打 ERROR「已跳过，不阻塞加载」（16:11，13 角色全失效）后继续派发，失效要等到 dsh 侧 fenix-model-config 插件断言才暴露；'claude' CLI 缺失同样到 spawn 时点才 FileNotFoundError；provider_ref_invalid 告警 19:37 才发出且不拦截派发——同一笔误当天二次出现（arbiter 角色）证明告警面无阻断力。
  - 调查评估：与存量关系：core/config.py 既有逐角色非阻塞校验，admin_monitor 既有 provider_ref_invalid 告警，但「配置声明」与「运行时可用」之间无一致性硬门，检索日志与代码注释未见 config-preflight 类既有项，不与既有 evo 重复。结论：这是把首日失误放大 25 倍的结构缺口，值得立项。
  - 建议：SPEC 提案：daemon 启动与热重载时执行配置预检（provider 引用齐注册、harness 二进制存在、模型三元组齐备），预检失败则拒绝热重载、冻结派发并出单条汇总告警（替代逐 run 失败加多告警流），预检通过自动解除冻结。

- **verify契约前置起草缺位，spec起草37分钟后才落契约**
  - 自进化：问题部分成立 / 建议部分合理 / — 不需要
  - 影响：复发敞口：m0 从起草（16:07）到验证契约落位（17:00，提交 96a21df）间隔 37 分钟，期间产生 8 条 verify_contract_missing 告警记录（前置预检发现 .fenix/verify.yaml 即验证契约文件仍是全注释模板态）并动用 2 个 admin run（admin-m0-...-1794128069 与 -1794128669）代起草，另有 1 条信号在契约已 resolved 后重复派发（告警 38179311da56）。
  - 证据：告警时间链：16:32 首条 verify_contract_missing、16:54 与 17:04 两次「Administrator 处置中」、17:01 第二个 admin run 派发、17:09 重复信号告警（自述「在契约已被前序 admin run 起草落位并 resolved 之后重复派发」）；后续 01:16 又以 verify_regression_missing（regression 段缺失信号）补段一次（提交 f3b577e，protocol JVM 单测 5 类 39 例）——同一天内 verify.yaml 触发三种信号流；另有 15:57 whitelist_untracked 告警（.fenix/verify.yaml 被 .gitignore ! 规则放行却未入库，现 git ls-files 已确认入库）。
  - 调查评估：在处置 m0-a2a-streaming-pipeline 进入验证前置预检时暴露。已核实事实如上；处置本身走升级链自动化完成（零人工落盘），功能上闭环，但「spec 起草时契约必须已可裁决」这一前置条件无人把关，导致每个新 spec 大概率重演一遍代起草流程。与存量关系：active 区两 spec 中仅 m0 触发（client-ux 由并行出题车道供场景），属首日新项目契约冷启动形态；未检索到契约前置起草类既有条目。结论：契约生命周期应从「验证时被动升级」改为「起草时主动落位」，并把 resolved 后信号重复派发修掉。
  - 建议：SPEC 提案：spec 进入 drafted 状态时即校验 verify.yaml 是否可裁决（非全注释模板），不可裁决则立即派一次性代起草并在 spec 卡片标注契约状态；修正已 resolved 信号的重复派发路径；新项目 bootstrap 时预置最小可裁决契约模板。
- **[arbiter:client-u] env 归因证据门（judge 以未测量的环境量定性 env）**
  - 自进化：问题成立 / 建议合理 / ✓ 值得立项 · 处理：待处理 · 删除：否
  - 影响：env 误判会把产品回归以『环境豁免』永久放行；本场景已因此连续 2 轮 warning 并烧掉 judge+admin 两段链，最终仍需人类。所有依赖 judge env 归因的场景集都受影响。
  - 证据：judge_attribution.json 中 regression-matrix-12-combos 判 env『swiftshader 软渲染确定性天花板（两轮 37.4/38.6）』；但 /tmp/admin-load-sample.txt 显示 load1 5.07~17.60 波动期间该组合 fps 恒定 36.2~37.5、源端 CPU 恒定 285~288%，历史两轮同宿主 1080p 51.8~59.1 PASS（git 21fefb6/07a7ee6），且 judge 历史 smoke 探针全部为 720p（planner-judge-1794160702 转录 7 次 --only lan-direct,game,720p）。；触发 run_id=arbiter-client-ux-and-input-latency-1794162973（签名 client-ux-and-input-latency::warning_stale_triage，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 warning_stale_triage（spec=client-ux-and-input-latency）：'client-ux-and-input-latency' 以下场景连续多轮均为 warning，需人工 triage 甄别是否真缺陷被悬置：regression-matrix-12-combos（连续 2 轮）。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：env 归因必须引用可复核的环境量测（负载/CPU/温度/驱动版本）并要求验证窗口外的对照样本；无对照的 env 结论降级为 inconclusive 并自动进 recheck，不得直接进豁免握手。
- **[arbiter:client-u] 实现自验以 --only 720p 子集替代验收全矩阵**
  - 自进化：问题成立 / 建议部分合理 / — 不需要
  - 影响：本 spec 的 1080p 性能回归在实现阶段漏检，直到首轮验证（18:12）才暴露，随后连续 2+ 轮 warning 升级、链上消耗两段 run。
  - 证据：commit 7006cc8（任务 6.1『矩阵实跑』）只执行 `--only lan-direct,game,720p`；impl-result.json 的 18 项 completed_tasks 无 12 组合全跑记录；而验收场景 test_scenarios.json#regression-matrix-12-combos 要求 12 条 passed=true（含 1080p）。；触发 run_id=arbiter-client-ux-and-input-latency-1794162973（签名 client-ux-and-input-latency::warning_stale_triage，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 warning_stale_triage（spec=client-ux-and-input-latency）：'client-ux-and-input-latency' 以下场景连续多轮均为 warning，需人工 triage 甄别是否真缺陷被悬置：regression-matrix-12-combos（连续 2 轮）。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：impl 收工门：当 spec 验收含全量矩阵/长场景时，impl-result 必须附该验收场景原命令的完整产物（12/12 或带归因的失败），否则不允许勾 6.x 收工；或把矩阵子集跑标注为 partial 并在 verify 前置拦截。
- **[arbiter:client-u] 矩阵场景断言只覆盖 progress 首尾条目**
  - 自进化：问题成立 / 建议合理 / — 不需要
  - 影响：若 run_matrix 退出码语义漂移或中途异常，中间组合失败可能被漏判为 PASS；本轮 1080p 失败仅靠 exit_code 断言兜住，断言与描述不一致。
  - 证据：.fenix/verify-progress/client-ux-and-input-latency/test_scenarios.json 中 regression-matrix-12-combos 的 assertions 仅有 `json_output [0].passed` 与 `json_output [11].passed`，其余 10 组合靠 run_matrix 退出码间接覆盖；场景描述却写『恰 12 条逐条 passed=true』。；触发 run_id=arbiter-client-ux-and-input-latency-1794162973（签名 client-ux-and-input-latency::warning_stale_triage，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 warning_stale_triage（spec=client-ux-and-input-latency）：'client-ux-and-input-latency' 以下场景连续多轮均为 warning，需人工 triage 甄别是否真缺陷被悬置：regression-matrix-12-combos（连续 2 轮）。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：增加聚合断言（一步 python 校验 len==12 且 all(passed)，或 json_output 指向汇总字段），把『逐条 passed』从描述落到断言；同类多组合场景统一模板。
- **[arbiter:client-u] 升级件自环回派（链终点 escalation alert 二次入 Arbiter 车道）**
  - 自进化：问题成立 / 建议合理 / ✓ 值得立项 · 处理：待处理 · 删除：否
  - 影响：每次 Arbiter 升级都会多派一轮同证据调查（本签名 2 轮，约 15 万 tokens/7 分钟量级），占用单飞串行队列并推迟真正新信号的裁决；升级件（给人类的裁决包）与 Arbiter 回执语义混淆，直到 arbiter_rulings 配额（=2）耗尽才停。
  - 证据：core/pm_engine/family.py:_send_human_escalation_alert 在 store.append 成功后无条件走 narrative_yielded_to_arbiter → ArbiterLane.submit_for_alert，未以 chain_context 非空短路；fenixd.log 实证：18:43:30.431 arbiter 产裁决包升级 → 18:43:30.677 落告警 c6fccc1be3e8（kind=escalation, chain.endpoint_kind=arbiter-escalated）→ 18:43:30.847 'arbiter 触发入队 ... source=escalation-alert'，本 run 与 1794162973 证据完全相同（最近验证仍为 18:31:57Z）。；触发 run_id=arbiter-client-ux-and-input-latency-1794163410（签名 client-ux-and-input-latency::warning_stale_triage，来源 escalation-alert）
  - 调查评估：触发信号 warning_stale_triage（spec=client-ux-and-input-latency）：'client-ux-and-input-latency' 以下场景连续多轮均为 warning，需人工 triage 甄别是否真缺陷被悬置：regression-matrix-12-combos（连续 2 轮）。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：在 family 咽喉对 chain_context 非空的链终点件跳过 submit_for_alert（只保留 narrative 或零补叙）；或在 submit_for_alert 入口检测 entry['chain'] 存在即短路；补单测覆盖「链终点件不产生新的 arbiter 触发」。
- **[arbiter:client-u] 矩阵进度未记录实际采集/编码几何，跨轮 fps 基线不可比**
  - 自进化：问题成立 / 建议合理 / ✓ 值得立项 · 处理：待处理 · 删除：否
  - 影响：验收矩阵跨轮/跨 commit 比较失真，本次仲裁险些把 +24% 像素的真实负载增长整体误判为产品回归；也直接影响 env 归因与 D6 阈值决策的可信度。
  - 证据：21fefb6 与 07a7ee6 的 docs/validation-report.md.progress.json 仅记 resolution 标签「1080p」与 fps，不记 VD/编码尺寸；cb0536b 后实际为 1072x2400（/tmp/admin-src-logcat.txt：capture: vd=... 1072x2400@30），旧管道为 1920x1080；两轮被当作同一组合直接比较（55.7 vs 36.2fps）。；触发 run_id=arbiter-client-ux-and-input-latency-1794163410（签名 client-ux-and-input-latency::warning_stale_triage，来源 escalation-alert）
  - 调查评估：触发信号 warning_stale_triage（spec=client-ux-and-input-latency）：'client-ux-and-input-latency' 以下场景连续多轮均为 warning，需人工 triage 甄别是否真缺陷被悬置：regression-matrix-12-combos（连续 2 轮）。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：progress JSON 每条组合增记 vdW/vdH/encW/encH/requestedW/H、pipeline commit 与 gpu 模式；报告 markdown 增列实际几何列；跨轮比较按几何分组或显式标注「不可比」。
- **[arbiter:client-u] 人工裁定落位后升级告警仍回派（human disposition 吸收窗缺失）**
  - 自进化：问题成立 / 建议部分合理 / — 不需要
  - 影响：冻结后由人工以 commit/报告落位终审的 spec 会被升级告警自环回派 Arbiter，每轮消耗一个 run 预算；本次为同签名第二次自环（前次 18:43→18:47 已由 arbiter todo 记录），若 Arbiter 未识别裁定会重复升级空转。
  - 证据：commit 20e3690 于 2026-11-08T21:29:33Z 落盘（verification-report.md 人工裁定「验收通过、SPEC 收口」）；同签名告警 7c3ed1bd8e67 仍于 21:30:50.970Z 由上一轮 Arbiter 基于裁定前快照生成，框架随即于 21:30:51.119Z 以 source=escalation-alert 回派本 run（fenixd.log）；框架代码 grep 'human disposition' 无消费点（/root/fenixd 全量），人工裁定仅以 commit/报告文本存在。；触发 run_id=arbiter-client-ux-and-input-latency-1794173451（签名 client-ux-and-input-latency::judge_rerun_cap，来源 escalation-alert）
  - 调查评估：触发信号 judge_rerun_cap（spec=client-ux-and-input-latency）：'client-ux-and-input-latency' judge 重跑超上限（6/5 轮），已冻结等待人工裁决。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：升级告警回派前检查 spec 的 verification-report 是否含人工裁定/最近 human disposition commit；或把人工裁定登记为一等状态（STATE 字段/台账），供升级链入队前短路；或在 Arbiter 触发快照中固定采集 git log 最近 human disposition commit 供裁决复用。
### 知识真相源
- [ ] knowledge-distill 重试：归档 client-ux-and-input-latency — run 失败: 调用 claude CLI 失败: [Errno 2] No such file or directory: 'claude'
- [ ] knowledge-distill 重试：归档 m0-a2a-streaming-pipeline — run 失败: 调用 claude CLI 失败: [Errno 2] No such file or directory: 'claude'
- **[arbiter:p2p-cros] daemon 关停击杀的 impl run 不获死亡豁免退款（预算伪耗尽）**
  - 影响：daemon 重启窗口内的 run 死亡照样消耗 impl 尝试预算 → impl_budget_exhausted 假阳性冻结/人工升级（本 spec used=4 中 2 格为非实质消耗，直接触发本次升级）；对账偏差仅告警不修正、无人工核查工具面；同类形态可在任何长 run 跨 daemon 重启时复现。
  - 证据：fenixd.log：05:56:48Z impl-1 interrupted(reason=daemon shutdown)+flush error 后，06:06:55Z impl-2 派发直接 used=2/4 无退款；对照 01:31:07–01:40:40Z 同夜 4 次「预算豁免（死亡 run）used 回退」均发生于 daemon 在飞观测窗；STATE p2p 条目 _budget_exempt_runs=4 全部来自 01:31–01:40 且无本 spec 退款锚点字段；08:05:08Z/08:21:12Z 两条「预算计数对账偏差 used=2/3 vs runs 目录正常收工 run 数=1…不自动改数，供人工核查」为框架自身失真自证（两源：日志×STATE）。；触发 run_id=arbiter-p2p-crossnet-traversal-1794215565（签名 p2p-crossnet-traversal::impl_budget_exhausted，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 impl_budget_exhausted（spec=p2p-crossnet-traversal）：'p2p-crossnet-traversal' 实现尝试预算耗尽（4/4），已冻结等待人工裁决。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：recover_interrupted 重启恢复路径对「interrupted reason=daemon shutdown 且无 run-result 事件」的 run 调 refund_dead_run_once 补豁免（锚点幂等原语已具备）；或 _reconcile_impl_budget 对可从转录证明的 daemon 关停死亡自动退款；至低给对账偏差告警附带一键退款/豁免动作面。
- **[arbiter:p2p-cros] code-fix fastlane 修复 commit 停留侧分支，主树不落位且无台账跟踪合回**
  - 影响：唯一剩余验证缺陷的修复不进任何后续基线（重修 run 自 master 切出仍 644、重验必复失败），修复工作悬空浪费、spec 收敛被无谓拉长一轮派发；同形态可复现于任何 bug-found→fastlane 修复→spec 冻结/回退的时序交错。
  - 证据：commit d94a5dd（scripts/natlab_run.py mode-only 修复，修复验证 100755+--help exit0 通过）仅存在于分支 fenix/p2p-crossnet-traversal-1794215453（parent e3db994，落后 master tip f763988 一提交）；master ls-tree 该文件=100644、主树工作区实测 -rw-r--r--、后续 rework worktree 基线同 100644（git 两源互证）；.fenix/code-review/fix-progress.json idle 且仅跟踪 2026-11-08 旧批（code-fix-1794159697），无任何台账条目跟踪本次 fastlane 修复的合回；fix-out jsonl 仅为 run 输出。冻结发生在 code-fix success（09:12:20Z）后 24s，合回被阻断/遗弃无台账可查。；触发 run_id=arbiter-p2p-crossnet-traversal-1794215565（签名 p2p-crossnet-traversal::impl_budget_exhausted，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 impl_budget_exhausted（spec=p2p-crossnet-traversal）：'p2p-crossnet-traversal' 实现尝试预算耗尽（4/4），已冻结等待人工裁决。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：fastlane 修复成功后登记 tracked pending-merge 条目（spec unfreeze/下一 checkpoint 消费合回，幂等）；或重验/rework 派发基线自动并载该 findings 对应的未合回修复分支；合回被 WIP 门/frozen 阻断时在 spec STATE 面留可见注记，避免静默悬空。
- **[arbiter:p2p-cros] daemon 关停击杀的 impl run 不获死亡豁免退款（预算伪耗尽）**
  - 影响：daemon 重启窗口内的 run 死亡照样消耗 impl 预算 → impl_budget_exhausted 假阳性冻结/人工升级（本 spec used=4 中 2 格为伪耗尽，直接触发本次升级）；同类形态可在任何长 run 跨 daemon 重启时复现
  - 证据：fenixd.log 08:05:08Z/08:21:12Z「预算计数对账偏差 used=2/3 vs runs 正常收工=1…不自动改数，供人工核查」（本 run fresh 复核 L4939/L5044）× STATE _budget_exempt_runs=4（全来自 01:31–01:40Z 死亡豁免窗，无本 spec 退款锚点）两源互证；05:56:48Z impl-1 interrupted(reason=daemon shutdown) 后 impl-2 派发直接 used=2/4 无退款；触发 run_id=arbiter-p2p-crossnet-traversal-1794216592（签名 p2p-crossnet-traversal::impl_budget_exhausted，来源 escalation-alert）
  - 调查评估：触发信号 impl_budget_exhausted（spec=p2p-crossnet-traversal）：'p2p-crossnet-traversal' 实现尝试预算耗尽（4/4），已冻结等待人工裁决。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：recover_interrupted 对「interrupted reason=daemon shutdown 且无 run-result 事件」的 run 调 refund_dead_run_once（锚点幂等原语已在）；或 _reconcile_impl_budget 对可从转录证明的关停死亡自动退款；至低给对账偏差告警附一键退款/豁免动作面
- **[arbiter:p2p-cros] code-fix fastlane 修复 commit 停留侧分支，主树不落位且无台账跟踪合回**
  - 影响：唯一剩余验证缺陷的修复不进任何后续基线，重修 run 自 master 切出仍 644、重验必复失败，修复工作悬空浪费、spec 收敛被无谓拉长；同形态可复现于任何 bug-found→fastlane 修复→spec 冻结/回退时序交错
  - 证据：commit d94a5dd（scripts/natlab_run.py mode-only 修复）仅存在于分支 fenix/p2p-crossnet-traversal-1794215453；master b325b72 ls-tree 该文件=100644、主树工作区实测 -rw-r--r--、重修 worktree 基线同 100644（git 两源互证，本 run fresh 复核）；.fenix/code-review/fix-progress.json 无本批跟踪条目；触发 run_id=arbiter-p2p-crossnet-traversal-1794216592（签名 p2p-crossnet-traversal::impl_budget_exhausted，来源 escalation-alert）
  - 调查评估：触发信号 impl_budget_exhausted（spec=p2p-crossnet-traversal）：'p2p-crossnet-traversal' 实现尝试预算耗尽（4/4），已冻结等待人工裁决。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：fastlane 修复成功后登记 tracked pending-merge 条目（unfreeze/下一 checkpoint 幂等消费合回）；或重验/rework 派发基线自动并载对应未合回修复分支；合回被 WIP 门/frozen 阻断时在 STATE 面留可见注记
- **[arbiter:single-a] arbiter/admin unfreeze→testing 不入队 verifier（_status_to_event 缺 testing 键，30min full_scan 兜底与 15min 孤儿探测节奏差）**
  - 影响：每轮 env 阻塞解冻/复位都不直接派发重验，必先烧一个 orphaned_state/admin run 才可能入队（11:31Z 事故与本轮空转环两度复现），拉长冻结恢复链路、空耗 admin token；处置链在环境已修复时无法自然收敛
  - 证据：.fenix/admin_disposition-admin-single-app-realdevice-retest-1794224827.json 代码级定位（core/pm_engine `_status_to_event` 无 testing 键、`_recover_testing` 挂 30 分钟周期 full_scan）；fenixd.log 11:31:02Z 前序 Arbiter unfreeze→testing 后零派发、11:47:07Z orphaned_state 成条；11:54:35.950Z 与 12:02:53.868Z 两次 admin_monitor「verifier 入队重验」复位行均无 state_machine 落盘与实际派发（_judge_real_dispatches 恒 2）；触发 run_id=arbiter-single-app-realdevice-retest-1794225773（签名 single-app-realdevice-retest::verify_env_blocked，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 verify_env_blocked（spec=single-app-realdevice-retest）：用户指令冻结：验证环境阻塞连续 2 轮，admin 处置未奏效，转人工 — env 失败场景不得判 verified（须升级 admin 处置）: probe-only-happy-path-ok、mirror-limit-fault-injection-repro
  > [成本参考] 重新出题成本：累计 费用未回传 / 1.0M tokens。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：为 `_status_to_event` 增补 testing→verify 入队事件，使显式解冻同步派发 verifier；或孤儿探测对「testing 且无在飞 verifier」的 spec 直接入队重验而非仅告警 Admin
- **[arbiter:single-a] unfreeze/reset→testing 不入队 verifier（_status_to_event 缺 testing 键，处置与恢复通道全部空转）**
  - 影响：所有依赖「解冻/复位后自动重验」的车道（Arbiter 白名单③、admin env 阻塞复位直通道、revise_scenario_reverify 修订重验出口、daemon 启动恢复）在 testing 态全部空转，spec 无法获得新验证轮，只能被 fast-track 用陈旧 report 收尾并再冻结；本轮已造成同一签名 3 次 Arbiter 唤起、6+ 次 admin run 与 2 次 daemon 级处置的全部无效消耗。
  - 证据：.fenix/logs/fenixd.log：11:20:58.369 admin 直通道声称「transition_gate(testing) + verifier 入队重验」后无任何 verify run 落地；11:31:02 与 12:18:47 两轮 Arbiter 解冻（side={'unfrozen': True, 'unfreeze_status': 'testing'}，全计数清零）后同样无 verify_engine 运行；12:39:05 revise_scenario_reverify「重派 verifier 重验，已写调度唤醒信号」且 12:39:05.300 scheduling_kick 被消费（唤起 spec status=testing）后仍无运行；12:53:48 新 daemon Recovery「re-triggering verify」同样未派生。该 spec verify_engine 仅 09:52:58/10:04:42 两轮。前任 Arbiter 已于 12:18:50 记 todo（第 111 行），本轮补强证据：重启后新代码依旧复现。；触发 run_id=arbiter-single-app-realdevice-retest-1794229305（签名 single-app-realdevice-retest::verify_env_blocked，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 verify_env_blocked（spec=single-app-realdevice-retest）：用户指令冻结：验证环境阻塞连续 2 轮，admin 处置未奏效，转人工 — env 失败场景不得判 verified（须升级 admin 处置）: probe-only-happy-path-ok、mirror-limit-fault-injection-repro
  > [成本参考] 重新出题成本：累计 费用未回传 / 1.0M tokens。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：为 testing 态在 _status_to_event/派发表补 verifier 映射（testing→spec_implemented 或专用 reverify 事件），或在 unfreeze/reset/修订重验/恢复四处统一改走「显式入队 verifier + 落地回执核验（N 分钟内无 verify run 即告警回滚）」；恢复（Recovering interrupted SPEC）路径对「最近 report 已含 env 失败场景且 _scenario_revision_reverify_pending」的 spec 禁用 fast-track 直通 verified。
- **[arbiter:single-a] 处置动作回执缺下游落地校验（声称 verifier 入队/重派成功但无 run 落地，无任何告警或回滚）**
  - 影响：处置链对「成功」的判定止步于动作发出，不校验下游 run 落地，导致空转处置被记为成功、告警被闭环（12:02:10「Admin 处置成功链闭环」后 40 秒 spec 即再冻结），升级链对各车道失效失去感知，问题要等下一次冻结才暴露。
  - 证据：.fenix/logs/fenixd.log：11:20:58.369「env 阻塞处置复位（直通道）：transition_gate(testing) + verifier 入队重验」——此后 23 分钟无 verify run，直通道回执为成功语义；12:39:05.170 revise_scenario_reverify 回执 steps 含「重派 verifier 重验，已写调度唤醒信号」，12:39:05.300 kick 被消费，其后无任何 verifier 派发或失败记录；两次空转均由后续 Arbiter/Admin 逐日志考古才发现。；触发 run_id=arbiter-single-app-realdevice-retest-1794229305（签名 single-app-realdevice-retest::verify_env_blocked，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 verify_env_blocked（spec=single-app-realdevice-retest）：用户指令冻结：验证环境阻塞连续 2 轮，admin 处置未奏效，转人工 — env 失败场景不得判 verified（须升级 admin 处置）: probe-only-happy-path-ok、mirror-limit-fault-injection-repro
  > [成本参考] 重新出题成本：累计 费用未回传 / 1.0M tokens。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：为「入队/重派 verifier」类动作增加落地回执校验（N 分钟窗口内无对应 agent_type run 落地即升级告警并标记处置无效）；处置回执写入可核验句柄（预期 run 类型+派发时间窗），供 dashboard 与处置链对账。
- **[arbiter:single-a] escalate 裁决终态化后同签名告警秒级再派发 arbiter（无抑制窗/回派去重）**
  - 影响：已上升人工的签名仍被自动回派 arbiter：每轮约 0.15M input token 无状态变更空耗，人工裁决流将被同签名重复包淹没，真实待决项被稀释；escalation_chain_state 已记 arbiter_rulings=1 却仍再派发，计数未参与派发抑制
  - 证据：.fenix/logs/fenixd.log 13:16:37.393730Z『arbiter 触发入队（单飞串行）: sig=single-app-realdevice-retest::verify_env_blocked alert=d8fc3fb68cbb source=escalation-alert』与 13:16:37.393910Z chain-escalated（chain-1794229305527-6876be08）同秒；上一轮裁决 ledger 条目 arb-ledger-1794230148605（13:15:48Z escalate）；本 run id=arbiter-single-app-realdevice-retest-1794230197 为该签名第 4 次唤起，无新证据、无人工动作；触发 run_id=arbiter-single-app-realdevice-retest-1794230197（签名 single-app-realdevice-retest::verify_env_blocked，来源 escalation-alert）
  - 调查评估：触发信号 verify_env_blocked（spec=single-app-realdevice-retest）：用户指令冻结：验证环境阻塞连续 2 轮，admin 处置未奏效，转人工 — env 失败场景不得判 verified（须升级 admin 处置）: probe-only-happy-path-ok、mirror-limit-fault-injection-repro
  > [成本参考] 重新出题成本：累计 费用未回传 / 1.0M tokens。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：escalation chain 在 escalate 终态化时对同签名加抑制窗/指数 backoff（人工消费、spec 状态变化或新证据指纹出现才解禁再派发）；或 escalate 后告警转 pending_human 态停止自动回派，仅人工/admin 显式消费后 re-arm；arbiter_rulings 计数接入派发闸（超 1 次仅累积不重派）
- **[arbiter:single-a] 修复合并后运行面滞后：daemon 不重启仍跑旧代码，且无 pending-restart 提示面**
  - 影响：自进化车道『修复已合并』与『缺陷已消除』之间存在无提示窗口：期间 arbiter/admin 的解冻/复位类处置按旧代码空转并被记为成功语义（配合 todo 已登记的回执缺口形成双盲），问题要等下一次冻结才暴露
  - 证据：fenixd git ba9249a0f 合并于 13:11:50Z，运行 daemon（daemon_runtime.json pid 2829079）启动于 12:53:47Z——合并后修复不在运行面；restart_history.jsonl 显示重启需外部触发（12:52:50Z 条目→12:53:47Z 生效）；本 spec 13:01:45Z 再冻结发生在『修复已合并但运行面仍旧代码』窗口内；触发 run_id=arbiter-single-app-realdevice-retest-1794230197（签名 single-app-realdevice-retest::verify_env_blocked，来源 escalation-alert）
  - 调查评估：触发信号 verify_env_blocked（spec=single-app-realdevice-retest）：用户指令冻结：验证环境阻塞连续 2 轮，admin 处置未奏效，转人工 — env 失败场景不得判 verified（须升级 admin 处置）: probe-only-happy-path-ok、mirror-limit-fault-injection-repro
  > [成本参考] 重新出题成本：累计 费用未回传 / 1.0M tokens。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：修复落 master 后由框架置 pending-restart 标记并单发告警（关联标注受影响的在飞冻结签名），人工/运维按提示重启；或对关键处置面（state_machine/queue）引入版本指纹自检，运行面与 HEAD 不一致时在处置回执中降级为『需重启后重试』而非成功语义
- **[arbiter:p2p-cros] 升级闸门冻结与code-fix-fastlane派发竞态（0工作量修复轮计入失败轮）**
  - 影响：任何 spec 第 N 轮验证失败恰逢 fastlane 派发时，最后一次自动修复机会被静默清零；「连续失败 N 轮」计数把 0 工作量的修复轮计为真实失败轮，直接制造本例第 3 轮冻结——升级闸门对人工的陈述（自动修复连续失败）与事实（自动修复从未运行）背离，误导裁决。
  - 证据：fenixd.log 6611-6637：13:04:39.702Z fastlane 判定 eligible=True（三条件全满足）并派发 code-fix run 68fb6e-1794229479；13:04:41.462Z 升级闸门第 3 轮冻结；13:04:42.079Z 击杀在飞 code-fix 子进程（存活 2.4s、0 API 轮、0 工具调用，run 归档 killed_without_cli_result source=frozen）；13:04:42.583Z 回落 full-round 亦 dispatch-failed；fastlane_decision.json streak=2 证明同形态已发生 2 次。；触发 run_id=arbiter-p2p-crossnet-traversal-1794230197（签名 p2p-crossnet-traversal::verify_fail_escalation，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 verify_fail_escalation（spec=p2p-crossnet-traversal）：'p2p-crossnet-traversal' 升级闸门连续失败第 3 轮，已冻结等待人工裁决。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：冻结评估与 fastlane 派发同锁串行（先撤后冻或先冻后不派）；或升级闸门计数排除「修复 attempt 未执行即被 frozen 击杀（0 turns）」的轮次并在冻结原因中如实标注；或冻结前对在飞 code-fix 给宽限期（等待其完成或显式取消并标记 void）。
- **[arbiter:p2p-cros] daemon恢复路径绕过修复段直接重验（制造空转重复轮）**
  - 影响：重启恢复后的重验在零修复前提下逐字复现上轮失败，白烧升级闸门轮次并向 3 轮冻结阈值空转计数；本例 round3 即此形态的直接产物，与 gap1 叠加把可自愈的一行缺陷升级成人工裁决件。
  - 证据：fenixd.log 12:53:48-12:54:05：Recovering interrupted SPEC p2p-crossnet-traversal (was testing)→Recovery: re-triggering verify→implemented→testing→派发 verifier 全量重验；恢复前预算解冻后无任何 impl/code-fix 修复 run 补跑（09:12:45Z 被保全的修复 run 未续做）；verdict_stability.json 相邻两轮 stamp 恒等、round3 相邻轮 diff 为空——与冻结时「进度已保全，解冻后续做」的承诺语义不一致。；触发 run_id=arbiter-p2p-crossnet-traversal-1794230197（签名 p2p-crossnet-traversal::verify_fail_escalation，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 verify_fail_escalation（spec=p2p-crossnet-traversal）：'p2p-crossnet-traversal' 升级闸门连续失败第 3 轮，已冻结等待人工裁决。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：恢复重验前检查冻结期是否有被保全未执行的修复 run（_frozen_hold 遗产锚点），先重派修复段再进入验证；或恢复触发的首轮重验标记 recovery-round 不计入升级闸门连续失败计数；或恢复时对上轮冻结原因做归因复读，凡属冻结/恢复类机制打断（非修复失败）即清零连败计数。

## 2026-11-10
### 可改进
- **[arbiter:client-u] worktree-pre-rmtree-stash-ref-loss**
  - 自进化：问题成立 / 建议合理 / ✓ 值得立项 · 处理：待处理 · 删除：否
  - 影响：任何 impl 后冻结 spec 的实现交付（尤其全套重实现）在分支删除 + worktree 清扫后沦为悬空对象，默认 gc.pruneExpire 窗口内可被 GC 静默清除；本案 run4 交付已处此险，人工裁决前丢失则三套交付折损一套。
  - 证据：fenixd.log 06:50:38Z 清扫 impl-client-ui-redesign-1794286517 worktree 时留快照并指引「recover via: git stash store 584de3ee…」，但当前 refs/stash 不存在、git for-each-ref --contains 4306e3f 为空、fsck 报悬空 commit——stash ref 为 per-worktree 引用，随 worktree rmtree 一并销毁，快照指引失效，run4 全套重实现（9 提交 c3143e5→4306e3f）现零引用可达。两源互证：worktree_manager 快照日志 ↔ git 引用/对象实测。；触发 run_id=arbiter-client-ui-redesign-1794302874（签名 client-ui-redesign::impl_budget_exhausted，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 impl_budget_exhausted（spec=client-ui-redesign）：'client-ui-redesign' 实现尝试预算耗尽（4/4），已冻结等待人工裁决。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：pre-rmtree 快照改存共享引用（主 gitdir refs/preservation/<run_id>）或 git note/独立 bundle；STATE 锚点增设 _last_impl_tip 记录悬空 tip；终态 impl 分支保留至 merge-back 决策后再删。
- **[arbiter:client-u] impl-budget-wall-manual-unfreeze-silent-bounce**
  - 自进化：问题成立 / 建议部分合理 / — 不需要
  - 影响：触发信号建议的人工补救路径（「人工重置计数器即重新获得自动修复机会」）无一等通道：只解冻不重置必在分钟级回弹再冻结，空耗一次升级环且静默跳过使人工误判为派发阻塞；错误文案 run 锚点丢失劣化裁决质量。
  - 证据：fenixd.log 09:04:26Z 批量解冻重置 12 个计数键但不含 _impl_run_budget_used；09:07:14-09:11:54 恢复循环 5 次触发 orphan-ready 重派均被静默跳过（无拒绝日志，对照同秒 single-app-realdevice-retest 有「预算计数 +1 used=2/4」日志）；09:11:55Z 按 4/4 再冻结。另冻结 error 文案「最近实现 run=未知」与归档在案的 run4 锚点相矛盾（锚点丢失）。两源互证：日志时序 ↔ STATE 冻结条目。；触发 run_id=arbiter-client-ui-redesign-1794302874（签名 client-ui-redesign::impl_budget_exhausted，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 impl_budget_exhausted（spec=client-ui-redesign）：'client-ui-redesign' 实现尝试预算耗尽（4/4），已冻结等待人工裁决。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：unfreeze_spec 检测 used≥budget 时显式拒绝并提示重置通道，或提供显式 reset_impl_budget 参数（台账留痕）；派发器预算拒绝从静默改为 WARNING 级原因日志；冻结 error 文案回填最近 run 归档锚点。

## 2026-11-11

### 浪费
- **供应商配额耗尽后仍连派51败，当日自省被击中且不补跑**
  - 自进化：问题成立 / 建议合理 / ✓ 值得立项 · 处理：待处理 · 删除：否
  - 影响：窗口（因 11-10 自省失败实为约 48 小时）159 个 run 中 51 个失败（32%），数据摘要列出的失败样本 10/10 全部是同一面配额墙（RATE_LIMIT 429 code 1310，限额 2026-11-10 10:43:04 重置）；部分 run 死前已烧掉实质工作（repair run 86 轮/893 秒、planner-plan 41 轮后死），且 11-10 的每日自省 run 因此失败，当日回顾永久缺失。
  - 证据：fenixd.log 首条 429 在 11-09 13:34:06Z（arbiter-p2p-crossnet-traversal-1794231045，这是 p2p 跨网升级件的裁决 run，16 轮 74.2 秒后死）；self_review 组件 11-09 18:04:49Z 记「每日自省失败：2026-11-10」，其后 11-10 01:30/02:03Z 又两次同因失败；self_review_state.json 在失败当刻把 last_run_date 推进到 2026-11-10——失败也计为已跑，调度器不会补跑。
  - 调查评估：跨全部 spec 的基础设施事件，非单 spec 问题。已核实：429 报文自带重置时间戳（「限额将在 2026-11-10 10:43:04 重置」），框架有 provider 级 300 秒 switch-cooldown（add-provider-failover）与 arbiter infra-failed 分级（不烧死人开关资格，fenixd.log 7255 行），但没有任何按重置时间的全局派发冻结——失败形态是「冷却 5 分钟→再试→再死」，失败分布拉满 13.2 小时直到重置。根因归属：系统性缺口（无配额感知熔断），非一次性失误；各车道各自重试放大次数，失败计数器/死人开关资格被 infra 分级保护住了，但 run 预算没被保护。与存量工作关系：11-09 既有条目「配置错误无派发前预检」针对配置失效预检，与本条（配额耗尽感知）互补不重复；admin_infra_breaker.json（基础设施熔断器状态）现仅 spec-review 一条且 closed，breaker 未覆盖配额类故障。结论：重置时间已写在报文里而框架不读，属低成本高收益的止损缺口。
  - 建议：解析 429 code 1310 报文中的重置时间，对该 provider 触发全局派发冻结至重置时刻（复用 infra breaker 通道，单条汇总告警替代逐 run 失败）；自省 run 失败不得推进 last_run_date，失败后按退避补跑当日回顾。

- **规划75分钟产出的15条场景全部因超时字段非法整批拒绝**
  - 自进化：问题成立 / 建议合理 / ✓ 值得立项 · 处理：待处理 · 删除：否
  - 影响：m3-audio-clipboard-textinput 的场景规划 run（planner-plan，测试场景规划 agent）跑 4481 秒、193/341 轮（组中位 1204 秒的 3.7 倍），产出的 15 条场景在写前预校验（场景落盘前的合法性校验闸）15/15 全部非法、零合法可执行，整批丢弃退回重排；后续补 1 轮重排加 3 轮 planner-judge（评审出题 agent）run，该 spec 验证链当天上午空转一轮。
  - 证据：fenixd.log 11-10 03:49:55Z 整批拒绝原文：15 条场景全部「步骤0 timeout 超过上限 1800 秒，收到 60000.0/900000.0/1800000.0/…」——这些值全是 60000ms=60s、300000ms=5min、1800000ms=30min 这类以毫秒计的整分钟值，且 1800000 恰等于上限 1800 秒（30 分钟）本身，指向规划器按毫秒写、校验器按秒读的单位错位；离群 run planner-plan-m3-audio-clipboard-textinput-1794222969-a1 duration 4481.1s、stalls=10 合计 1281.6s（数据摘要第六节）。
  - 调查评估：在处置 m3-audio-clipboard-textinput（音频/剪贴板/文字注入 SPEC）验证准备阶段暴露。已核实：拒绝发生在 11-10 03:49:55Z，距规划 run 产出约 15 小时（其间验证链被 429 配额墙堵住），退回 plan（剩余重排 1 次）后重排与 3 轮 judge 于 03:39–04:49Z 连发，spec 最终未冻结。根因：场景产物 timeout 字段的单位约定在规划器与校验器之间不一致，校验报错只回显数值与上限、未提示单位嫌疑，重排一轮缺少关键纠错信息。与存量工作关系：写前预校验与退回重排机制本身按设计工作（不写空文件、不持久化非法集），缺口只在单位约定与报错质量，检索 todo 与知识域未见既有条目覆盖。结论：一次单位错位烧掉约 75 分钟规划加一轮验证编排，属可机械预防的浪费。
  - 建议：场景 schema 给 timeout 显式单位字段（秒），规划提示词与模板写明单位；预校验报错附带换算嫌疑提示（值超上限且可被 1000 整除时提示「疑似毫秒」），让重排一轮命中而非盲改。

### 失误
- **重试与推送计数越限：上限3次却执行到第4次才冻结**
  - 自进化：问题成立 / 建议合理 / ✓ 值得立项 · 处理：待处理 · 删除：否
  - 影响：两个独立子系统的重试计数越过上限各一次、以「4/3」收场：client-ui-redesign 的调度重试在 11-09 已按 3/3 冻结转人工后，11-10 又执行了第 4 次尝试并以 4/3 再次冻结；m0 的 git push 重试在「已停止自动重试（3/3）」3 分钟后又跑第 4 次。每次多烧一轮派发，且给人工的冻结陈述（已试次数/上限）与事实不符，误导裁决。
  - 证据：fenixd.log 11-09 13:34:43.911Z「All retries exhausted for 'client-ui-redesign' — 冻结转人工（3/3，fp=a812d1171a33，失败指纹）」与 11-10 06:40:28.141Z 同句（4/3，fp=0c9759dd9960）；同一秒内两条「实现尝试预算计数 +1 used=4/4」（13:34:43.066 与 .460，一次失败计了两次）；m0 push 在 11-09 01:53:43Z（3/3，「已停止自动重试」）与 01:56:46Z（4/3）两条 ERROR。
  - 调查评估：跨 spec 的框架记账缺陷。已核实事实如上；越限的具体代码路径未逐行核实，多假说并存：①上限判断先增后查/比较符有误；②重试重武装（rearm，配置热重载后放行新重试）或解冻路径放行新尝试但不清零计数；③同一失败被两个入口各计一次（13:34:43 的双 +1 与此吻合）。与存量工作关系：todo 既有条目「升级闸门冻结与code-fix-fastlane派发竞态（0工作量修复轮计入失败轮）」与「impl-budget-wall-manual-unfreeze-silent-bounce」分别覆盖 fastlane 竞态计数与解冻不重置预算，本条的 4/3 是同族记账缺陷在调度重试计数与 push 重试计数上的新实例，互补不重复。结论：上限语义被破坏会连带损害升级链可信度，应统一修复并加单测。
  - 建议：重试计数改为「先比较后原子递增」（cap 判断用 ≥），解冻/重武装路径显式清零对应计数键并留痕；给 push 与 impl 两条计数路径补越限单测；冻结文案中的 used/上限取自同一计数源。

- **散件WIP收编提交落地52秒后，同一处置又被记失败**
  - 自进化：问题成立 / 建议合理 / — 不需要
  - 影响：admin（管理员处置 agent）于 20:31:04 以提交 86e5fe3 收编主树 5 个散件（p2p-crossnet-traversal 验证轮遗留的 natlab 矩阵数据、投影授权截图等），52 秒后其处置动作 commit_stray_wip（一键提交信号清单内散件）执行失败并记 ERROR——目标实际已达成，处置台账却留下失败记录与一条噪声 ERROR。
  - 证据：master 分支 reflog：86e5fe3 提交于 2026-11-10 20:31:04 +0800，说明自述按 maintree_stray_wip_stale（主树散件无主滞留盘点信号）指纹 37e2bf7d0956714b 认领入库；fenixd.log 12:31:56Z（即 20:31:56 本地）「处置 choice 执行失败 choice_id=commit_stray_wip spec='cloud-phone'：信号清单内文件已全部不在当刻工作区改动中……不产生空提交」。
  - 调查评估：在处置 cloud-phone 项目级散件信号时暴露。已核实：失败守卫本身工作正确（清单为空不做空提交），同指纹告警一次性、改动消失后盘点自动复位，无功能后果，损失仅账面。根因：同一 admin run 内「自行 git 收编」与「调用处置执行器」先后作用于同一目标、无幂等短路，执行器把「文件已不在（因为刚被提交）」当失败而非成功空操作。与存量工作关系：与既有条目「处置动作回执缺下游落地校验」（声称成功无落地）方向相反（已落地却记失败），同属处置回执语义缺陷族，互补。结论：低危害高确定性的小修，不值得单独立项，可与处置回执语义修复合并处理。
  - 建议：commit_stray_wip 执行器对「清单文件已在 HEAD 且路径与最近收编提交匹配」返回 success-noop 并注明收编提交 hash，与真正丢失文件的失败区分。

### 可改进
- **推送成功判据校验过时的main分支，36个提交滞留本地**
  - 自进化：问题成立 / 建议合理 / ✓ 值得立项 · 处理：待处理 · 删除：否
  - 影响：pm_engine 的收尾推送成功判据（D1：git push origin main）推的是本地 main 分支，而实际工作自 11-09 起全部落在 master——7 次「Main push succeeded」里除首次建立远端外均为空转成功（本地 main 引用整个时段只停在两个值上），origin/main 停在 11-09 16:21 的合并提交、落后本地 94 个提交，origin/master 停在 11-10 18:36、落后 36 个提交；windows-desktop-client 全套交付（合并、验证报告、知识蒸馏、code-review 修复）仅存于本地与 fenix/* 特性分支，单机故障即丢一整天交付。
  - 证据：engine.py 3333/3446 行 `_git_push_ref("main")` 执行 `git push origin main`；main 分支 reflog 仅两条：11-09 09:26 自 master 创建（969d7a7）→ 11-10 16:56 被「branch: Reset to origin/main」（0023fdf），期间零移动，而同期「Main push succeeded」7 次（11-09 02:02Z m0 至 11-10 14:14Z windows-desktop）；实测 rev-list 计数 origin/main..master=94、origin/master..master=36；11-10 05:03Z p2p 推送被远端以 non-fast-forward 拒绝（本地 main 落后远端），约两小时后被人工式复位后才推成功。
  - 调查评估：在处置 windows-desktop-client 收尾推送时暴露（推送「成功」而交付未上远端）。已核实事实如上；origin/main 如何在本地 main 不动的情况下领先来源未核实——多假说：人工或其他 clone 外带推送/复位，两次外带干预（复位 main、origin/master 推进到 623978c）都发生在框架推送失败之后。根因：推送成功判据与工作落地分支脱钩（工作在 master、判据校验 main），pushed 状态不再证明交付可达远端——STATE.md 8 个 spec 全标 pushed 与远端 36 提交缺口并存即自证。与存量工作关系：todo 既有条目「code-fix fastlane 修复 commit 停留侧分支，主树不落位」讲修复件不进主树基线，本条是收尾推送判据错引用，同属交付落位域但机制不同，互补。结论：本窗最大资产风险，pushed 状态当前不可信。
  - 建议：推送门改为推送实际集成分支（检出分支或配置的 base ref），推送前后比对远端 SHA 断言 tip 前进；done-spec 推送出现 Everything up-to-date 空转成功时发单条告警；daemon 启动时对账本地领先提交数并上报。

- **死人开关9次触发全部无告警落账，半成品升级包静默**
  - 自进化：问题不成立 / 建议不合理 / — 不需要
  - 影响：arbiter（全局裁决 agent）的死人开关（同签名连败达阈后停止自主尝试、把半成品调查包升级人工的保险机制）窗口内触发 9 次，全部 alert=None——升级只落 arbiter 台账与日志，admin 告警面（人工查看的唯一告警流）没有对应记录，人工无法从告警得知有半成品裁决包待消费。
  - 证据：arbiter.py 1408–1429 行：`alert_id = trigger.get("alert_id")`，仅当触发源带 alert_id 才调 `_upgrade_alert`，否则只写 ledger 与 ERROR 日志；fenixd.log 窗口内 10 条「死人开关触发」全部带 alert=None（11-09 01:01–01:31Z 七连发：single-app/gaming/p2p/m3-audio 的 redispatch_loop_detected 与 spec-review-incomplete，另有 13:35Z client-ui、13:55Z m3-audio）；.fenix/admin_alerts.json 全部 150 条记录中检索不到任何死人开关半成品包条目。
  - 调查评估：触发背景是 dsh 版本漂移与凭据缺失引发的裁决/评审连败潮（见另一条目），死人开关本身正确止住了自主重试。已核实：经 chain-handoff（升级链移交）进入 arbiter 的 trigger 不带 alert_id，升级分支被 if 短路，只剩台账加日志。根因：升级动作把「改写既有告警」当唯一通路，缺「无告警则新建」的兜底。与存量工作关系：todo 既有条目「升级件自环回派」「escalate 裁决终态化后同签名秒级再派发」覆盖升级链的回派与去重，本条是同一升级面上的可见性缺口（包根本没进人工面），互补不重复。结论：九连发里人工一次都没被主动告知，保险机制的「升级」半边形同虚设。
  - 建议：死人开关升级在 alert_id 缺失时回退为新建 admin 告警（kind=escalation，关联台账条目 id 与半成品包摘要）；补单测：trigger 无 alert_id 必产生告警记录。

- **（恶化）API停滞84个run合计9.8小时，判停仍未启用**
  - 自进化：问题成立 / 建议合理 / — 不需要
  - 影响：复发敞口：本窗 84 个 run 记录到 API 停滞（单轮请求往返超 60 秒的等待），合计 35229 秒（约 9.8 小时）纯等待，单轮最长 580.1 秒、10 个 run 单轮 ≥300 秒、25 个 run 单轮 ≥200 秒；停滞几乎全部由成功收尾的 run 承担，零告警零止损，较上窗（16 run/2.5 小时）明显放大。
  - 证据：数据摘要第六节 API 停滞聚合逐行回源：84 行 total 求和 35229.2s，max 列 ≥300 秒共 10 行（含 planner-judge-client-ux-and-input-latency-1794169117-a1 的 580.1s、impl-windows-desktop-client-1794311717-a1 的 336.9s）；arbiter-single-app-realdevice-retest-1794225773 duration 953.9s 中停滞占 748.6s（78%）；fenixd.log 侧仍只有逐条 WARNING（如「API round-trip stall: 121.1s」）无任何干预动作。
  - 调查评估：本条为 11-09 既有条目「停滞监督面缺位：判停关闭加成功豁免，2.5小时停滞零止损」的恶化续报（read_todo 未注入，判定依本人只读复核 .fenix/todo.md）。复核确认 config.yaml 仍无 agents.run_stall_budget_* 三键（core/config.py 525 行起缺省关闭逻辑未变），成功终态豁免也未改。口径注记：上窗 16 run/9044s 出自离群 run 深扫子集，本窗 84 run 为 stalls>0 全集，口径不完全可比，但「零止损、零聚合观测」结论两边一致且量级上行。与存量工作关系：不重复立项，沿用既有条目四点提案（灰度启用判停、窗口级投影、provider 健康计数、判活通道）。结论：恶化趋势成立，既有提案优先级应上调。
  - 建议：（不另立新案）按既有条目推进 SPEC：灰度启用 run_stall_budget 判停并按本窗 p90 校准阈值；日回顾聚合面新增窗口级停滞合计观测行；≥300 秒停滞轮计入 provider 健康度计数。

- **凭据缺失与版本漂移击穿预检缝隙，两波秒挂触发死人开关**
  - 自进化：问题成立 / 建议合理 / ✓ 值得立项 · 处理：待处理 · 删除：否
  - 影响：11-09 白天两波车道级启动失败：上午 dsh 版本漂移（harness 升到 0.2.2-rc.1，超出兼容白名单 0.2.0-rc.2，配套 bundle 插件拒载）叠加 deepseek 路由无 API key，裁决与评审 run 连败并触发 7 次死人开关；午后 repair 角色（修复 agent）因未分配 provider 链回落 harness 默认配置（同样无 key 的 deepseek 路由）秒挂 3 连击 ×2 个 spec，两车道各进 300 秒冷却——直接可数至少 9 个 run 零产出死亡。
  - 证据：fenixd.log 11-09 01:01:36Z「harness 前置健康检查拦截 dispatch: dsh 版本 '0.2.2-rc.1' 不在兼容白名单」拦截了部分派发，但同分钟 arbiter-single-app-realdevice-retest-1794186096/-1794186099 仍被派发并以插件不兼容 + MISSING_CREDENTIAL llm-deepseek（deepseek 官方路由无凭据）死亡，spec-review-gaming-uplink-1080p 两条 run 同因死亡（01:03:51/56Z）；07:45:11Z「harness dsh 未分配 provider 链，回落 harness 默认配置（role=repair）」后 repair-m3-audio-clipboard-textinput-1794210310 a1–a3 秒挂（存活 3 秒）进冷却，08:05Z p2p 车道同形态再演。
  - 调查评估：跨车道基础设施失误，发生在多 spec 并行收尾期。已核实：版本预检存在但只覆盖它管理的派发入口，arbiter 车道未被拦截；凭据缺失要到 dsh spawn 时点才暴露；harness-default 回落仅 WARNING 且每进程只提醒一次，repair 角色无显式链无人拦截；秒挂计数与 300 秒冷却按设计兜住了扩大。根因归属：执行层升级/配置失误 + 预检面三处系统性残缺（车道覆盖、凭据存在性、回落路径静默）。与存量工作关系：11-09 既有条目「配置错误无派发前预检，全角色失效仍连发19分钟」的提案方向被本窗证实，本条补其三点新机制细节（本条为补强证据，非重复立项）；当日自愈路径（凭据何时补、版本如何对齐）无留痕，未核实。结论：预检面应从版本白名单扩展到凭据与链路存在性，回落路径应显式化。
  - 建议：派发预检补两类断言：provider 路由凭据存在性、角色 provider 链缺省回落时首派发即冻结该角色并出单条告警（替代静默回落）；版本白名单拦截覆盖全部 agent 车道。

## 2026-11-12

### 浪费
- **21个run全部命中API停滞，2小时44分纯等待零止损**
  - 自进化：问题成立 / 建议合理 / — 不需要
  - 影响：本窗 21 个 run 全部记录 API 停滞（单轮请求往返超 60 秒的等待），合计 9872.4 秒（约 2 小时 44 分）纯等待、均摊每 run 470 秒；最重的实现 run（impl-web-browser-client-1794393480-a1，web-browser-client 主实现）墙钟 9808 秒中停滞 2086.4 秒（21%），场景规划 run 3713 秒中停滞 1531.1 秒（41%）——规划阶段四成时间在等 API。
  - 证据：数据摘要第六节 21 行停滞聚合逐行求和 9872.4 秒（口径：run 内 >60s 往返样本时长求和）；run_metrics（run 收尾落盘的量化指标行）回源：impl run stalls=21 max=190.3s、planner-plan stalls=12 max=262.7s；fenixd.log 窗口内「API round-trip stall」WARNING/INFO 共 32 条，全部仅留痕零干预（样本 17:55:21Z code-fix run 第 4 轮 147.1s）；provider 全部 bigmodel-cn；本回顾 run 开跑后自身也复现 122.6s/142.2s 停滞（窗口关闭后），长尾仍在持续。
  - 调查评估：跨全部车道与角色（admin/code-fix/code-review/impl/planner-plan/planner-judge 乃至自省 run），max 分布 66s–262.7s，形态指向 provider 网关长尾而非单车道问题。本条为 11-09「全窗16个run合计2.5小时API停滞」的窗口事实续报：本窗 run 数少但停滞覆盖率 100%（21/21）为观测以来最高，均摊 470s/run 较上窗 419s/run 上行。系统性监督缺口见可改进节既有条目续报，此处不重复立项。
  - 建议：窗口级停滞合计已可从本回顾数据面持续观测；配合既有判停提案灰度启用后，把均摊停滞比（停滞秒/run 墙钟秒）纳入日回顾作趋势行。

- **验证环境根治后spec滞留blocked57分钟才恢复重验**
  - 自进化：问题成立 / 建议合理 / ✓ 值得立项 · 处理：待处理 · 删除：否
  - 影响：web-browser-client 的验证环境已于 01:09 根治并探针实证，但 spec 在 blocked 态空等约 57 分钟、其中 39 分钟无任何 run 在飞，直到 60 分钟超时滞留信号触发一次 admin run（1038 秒/166 轮）才转回 testing 并派发 verifier——验证恢复被纯机制延迟拖慢，多烧 1 个 distress 信号与 1 个本可不发生的 admin run。
  - 证据：fenixd.log 17:09:02Z「Admin 处置成功链闭环: sig=web-browser-client::verify_env_blocked run=admin-web-browser-client-1794415508 unfrozen=False」（处置闭环但未执行解冻）；17:48:27Z blocked_stuck（spec 滞留 blocked 超阈值信号）「blocked 超过 63 分钟」（阈值 60 分钟，STATE updated=00:45:08Z）；18:05:47Z「Gate transition: blocked → testing」与「Dispatching verifier (P5)」；其间 17:09–17:48 日志仅 run 归档动作。
  - 调查评估：在处置 web-browser-client 验证阶段暴露。已核实事实：环境修复处置自身成功（bootstrap 根治提交 0a30212 于 01:07 落地，处置回执自述「环境已修复并探针实证」），但闭环回执 unfrozen=False 表明该处置路径不含解冻动作，框架也没有「环境已修复→自动解冻重验」的联动，只能等 60 分钟 stuck 阈值兜底后由 admin run 显式迁移；末次 admin run 内处置选项 manual_fixed_reverify 还因作用域不匹配执行失败记 ERROR（spec 作用域动作被当信号作用域消费），迁移靠 run 的门转换路径完成。根因归属：处置「修复环境」与「解冻状态」两段语义分离且无联动校验，属系统性缺口而非一次性失误。与存量工作关系：todo 既有条目「[arbiter:single-a] unfreeze/reset→testing 不入队 verifier」与「处置动作回执缺下游落地校验」同族，本例为复发补强——连探针实证已修复的成功处置也不触发状态迁移，不另立新案。结论：处置回执应携带并校验状态迁移落地，否则 stuck 定时器+额外 admin run 是必然代价。
  - 建议：不另立新案：按既有两条件推进——env 修复类处置闭环动作扩展为「解冻→testing→入队 verifier」（复用既有 unfreeze 通道），处置回执校验迁移已落地（N 分钟内无状态变化或无 verifier 派发即告警回滚）；处置选项的作用域校验前置到 admin 产出解析时。

### 失误
- **场景集扩容未同步契约serve段，验证被打回13分钟**
  - 自进化：问题成立 / 建议合理 / ✓ 值得立项 · 处理：待处理 · 删除：否
  - 影响：web-browser-client 实现合并当刻（21:46:58）被验证契约静态门打回：场景 kind 集（api/cli/web 三类场景形态）已超出契约声明（verify.yaml 验证契约文件无 serve 段——被验证应用如何拉起并可服务的约定段），按 fail-fast 拦截且场景执行数为 0，随后动用 1 个 admin run（857 秒/128 轮）代起草 serve/ready 段，验证启动推迟约 13 分钟（21:46 打回、21:59 契约落位）。
  - 证据：ERROR 2026-11-11T13:46:58.999Z「契约 serve 一致性门打回 'web-browser-client'…无 serve 段且 config commands.start 无有效兜底」；处置记录 verify_contract_missing（验证契约缺失类信号，21:48:19）自述「出题产物消费期打回，场景执行数为 0；共 1 处缺口」；admin run admin-web-browser-client-1794404819（857s）产出补段提交 b959617（21:59:20，diff 自述「场景集由纯 cli 扩为 api/cli/web 混合，serve/ready 不再可省」）。
  - 调查评估：在处置 web-browser-client 验证准备时暴露。已核实：契约 96a21df 起草时（11-08）场景集为纯 cli、serve/ready 依法省略（提交 diff 注释自证）；本 spec 出题（planner-plan 场景规划 agent，18:38 派发）把场景集扩为 api/cli/web 时未同步补段，直到实现合并消费出题产物才被静态门拦截——门本身按设计工作（零场景执行、不触发 planner replan），缺口在「场景集变更与契约段位同步」无前置校验。与存量工作关系：同族第三次复发——①11-08 契约模板态 verify_contract_missing、②11-09 regression 段事后补记（f3b577e）、③本次 serve 段；11-09 既有改进条目「verify契约前置起草缺位」已立提案，本条为复发实例补强（新形态：kind 集扩容），不重复立项。结论：出题产物落盘前按 kind 集校验契约段位，可把该族三类缺口全部前移消灭。
  - 建议：并入既有「契约前置起草」提案：场景产物落盘前按其 kind 集静态校验 serve/ready 等段位齐备，缺失即出单条信号并在出题轮内同步代起草，不等消费期打回。

- **散件收编落地50秒后处置又被记失败，同型第二度复发**
  - 自进化：问题成立 / 建议合理 / — 不需要
  - 影响：admin-cloud-phone-1794411503 于 23:50:44 以提交 93b9413/6e69678 收编主树 21 个散件并根治截图出库，仅 50 秒后（23:51:34）其处置选项 commit_stray_wip（一键提交信号清单内散件的处置动作）执行失败记 ERROR——目标实际已达成，处置台账再添一条噪声失败记录，与 11-10 事故（收编落地 52 秒后同错）构成 24 小时内第二度复发。
  - 证据：fenixd.log 15:51:34Z ERROR「处置 choice 执行失败 choice_id=commit_stray_wip spec='cloud-phone'：信号清单内文件已全部不在当刻工作区改动中（同指纹一次性告警，清单陈旧），不产生空提交；清单(21)」；git log 93b9413 提交于 23:50:44（同 run 自行收编，提交说明自述按 commit_stray_wip 认领）；对照 11-10 12:31:56Z 同句 ERROR（清单(5)）。
  - 调查评估：在处置 cloud-phone 主树散件信号（maintree_stray_wip_stale，主树无主滞留改动的周期盘点信号）第 2 度复发处置时暴露。已核实：失败守卫本身正确（清单为空不做空提交）、同指纹一次性告警、改动已入库零功能损失，代价仅一条噪声 ERROR 与失败台账记录。根因与 11-10 实例相同：同一 admin run 内「agent 自行 git 收编」与「处置执行器按清单重放提交」双路径先后作用于同一目标，执行器把「文件已不在（因刚被提交）」记为失败而非成功空操作。与存量工作关系：todo 既有条目（11-10「散件WIP收编提交落地52秒后，同一处置又被记失败」）已给出 success-noop 修法并判定「不值得单独立项，可与处置回执语义修复合并处理」——次日即复发第二度，说明该合并修复排期应提前，不另立新案。结论：修法明确且低成本，随处置回执语义修复合并落地即可断根。
  - 建议：按既有条目修法落地：commit_stray_wip 执行器对「清单文件已在 HEAD 且路径与最近收编提交匹配」返回 success-noop 并注明收编提交 hash；agent 已自行收编时跳过执行器重放。

- **蒸馏重试台账两笔欠账实为已完成工作，17次催办未闭合**
  - 自进化：问题成立 / 建议合理 / — 不需要
  - 影响：知识蒸馏重试台账挂着两条 11-09 登记的欠账（client-ux-and-input-latency 与 m0-a2a-streaming-pipeline 的归档蒸馏重试），而两者蒸馏内容在登记后 41 分钟即已入库（291fb59），欠账实为已完成工作；台账催办信号（knowledge_distill_retry_stale，重试欠账超 24 小时未重试的提示信号）至今已连发 17 次无一次触发闭合，同一套知识域当晨体检却结论「通过」——两个框架面对同一状态各说各话，已空转催办近 3 天。
  - 证据：fenixd.log 11-09 02:02:14Z/02:02:59Z 两条「knowledge-distill 放弃产出…调用 claude CLI 失败…已清理 spec/knowledge/ 半成品并登重试台账」；git 291fb59（11-09 10:43:42 +0800，即 02:43:42Z「knowledge: distill m0-a2a-streaming-pipeline, client-ux-and-input-latency」，创建 ADR-001~005 与 client 域页）；admin_alerts 中 knowledge_distill_retry_stale 共 17 条（11-09 16:04Z 至 11-11 16:04Z）；.fenix/knowledge_audit_report.md 体检时间 2026-11-12 00:04:21「结论：通过，6 域/17 能力页/12 ADR」与最后一条催办信号同分钟。
  - 调查评估：跨 spec 的台账状态误置。已核实：重试登记（02:02Z，claude CLI 缺失致 run 失败）早于产物入库（02:43Z）41 分钟，此后蒸馏车道 11-10 起恢复正常（windows-desktop-client 等 5 个蒸馏提交落地），但这两条旧登记从未被消费，催办信号按周期连发 17 次。根因：重试台账只有「失败登记/超时催办」两个动作，缺「产物存在性闭合检查」——登记前与催办前都不核对知识页/蒸馏提交是否已落地。与存量工作关系：todo 知识真相源节两条未勾选项即本体；与既有条目「死人开关9次触发全部无告警落账」同属「事件已发生而登记面不消化」族，互补不重复。结论：低成本闭合检查可同时消掉两条僵尸 todo 与 17 连发信号，并防后续 agent 误判为真实欠账而重做蒸馏。
  - 建议：knowledge_distill_retry_stale 信号发出前先核对登记条目的蒸馏产物是否已存在（按 spec 名检索知识域页与蒸馏提交，产物时间晚于失败登记即自动闭合并留痕），已存在则直接闭环不再催办；closure 同时回写 todo 对应勾选项。

### 可改进
- **（恶化）停滞监督面三窗未动：判停关闭加成功豁免依旧**
  - 自进化：问题成立 / 建议部分合理 / — 不需要
  - 影响：复发敞口：本窗 21 个 run 无一例外承担 API 停滞等待（合计 9872.4 秒、均摊 470 秒/run，均摊较上窗 419 秒继续上行），因全部成功收尾而零告警零止损，每次照付；判停机制依旧缺省关闭、成功终态依旧豁免，本窗 19 个成功 run 全部带停滞通过。
  - 证据：config.yaml 仍无 agents.run_stall_budget_* 三键（core/config.py:525 缺省关闭注释未变）；admin_monitor.py 成功豁免取数单源 _result_event_is_success 自述「success 终态豁免（验收 4「曾停滞但正常完成」）」；本窗升级告警仅 2 条且均与停滞无关（成功 run 全数豁免）；fenixd.log 窗口内停滞 WARNING/INFO 32 条零干预，单轮最长 262.7 秒（planner-plan run）。
  - 调查评估：本条为 11-09「停滞监督面缺位：判停关闭加成功豁免，2.5小时停滞零止损」与 11-11「（恶化）API停滞84个run合计9.8小时，判停仍未启用」的第三窗恶化续报（read_todo 未注入，判定依本人只读复核 .fenix/todo.md 与现行代码，口径注记：本窗 21 行为 stalls>0 全集恰等于全 run 集，覆盖率为精确值）。新增事实一处：api_round_stall_escalated 升级信号已带 code-review 车道 stall_redispatch（停滞致败场景受控重派）自动处置语义（evo-add-api-stall-disposition-closeout 2.1），但成功终态豁免未变，本窗停滞 run 全部成功故该机制零触发（日志检索 0 次）。结论：恶化趋势三窗连续，既有提案优先级应继续上调，不另立新案。
  - 建议：（不另立新案）按既有条目推进 SPEC：灰度启用 run_stall_budget 判停并按本窗实测（均摊 470s、p90 至最高 262.7s）校准阈值；≥200s 停滞轮计入 provider 健康度计数，为分流换道留证据。

- **升级件证据快照不随环境根治更新，人工确认包面临陈旧**
  - 自进化：问题成立 / 建议部分合理 / — 不需要
  - 影响：复发敞口：impl_unattainable_tasks（实现者诚实申报任务无法由 agent 完成、转人工确认的升级信号）升级件的证据快照冻结在申报时刻，所指环境阻塞其后被根治也不回写——人工按建议动作到 /fenix-approve（人工审批口）确认时看到的仍是「需人工完成环境动作」的过时结论，最坏场景是人工重复执行已完成的环境修复，参照本次同类修复实测代价 1 个 admin run（1433 秒/166 轮）。
  - 证据：升级件 4ab1dd4ce63b（impl_unattainable_tasks，web-browser-client，21:46:58 创建，handled=false 至今）证据文本列举源端三层依赖「MediaProjection 一次性凭据闲置约60s失效…权限框进程冻结」；其后 admin-web-browser-client-1794415508 于 01:07 落根治提交 0a30212（bootstrap 固化关闭 cached-app freezer，根治授权 tap 延迟致双重授权互杀）、01:09 处置回执「8 个 wire-e2e/web 场景…（env 阻塞，非产品缺陷），环境已修复并探针实证」；02:05:47 框架已重新派发 verifier，该升级件记录无任何字段链接根治提交或重验结果。
  - 调查评估：在处置 web-browser-client 的 7.4 任务（浏览器端延迟采样，需模拟器会话稳定运行≥60秒采集真实管线延迟样本）收口时暴露。已核实：升级件与其所指环境阻塞的根治件在同一 spec 上先后落地，但告警存储（admin_alerts）没有「关联根治件/重验证据回写」通路，重验结果同样不会更新升级件。归因证据边界：0a30212 根治的是处置回执点名的「二次授权互杀级联」，而 7.4 证据列举的一次性凭据时效等依赖是否同样解除尚未验证（重验在飞、报告未出），故本条只断言「升级件快照陈旧、缺更新通路」的形态层结论，不断言 7.4 重验必过。与存量工作关系：既有条目「人工裁定落位后升级告警仍回派（human disposition 吸收窗缺失）」讲升级件不吸收人工裁定，本条是其反面——升级件也不吸收 admin 根治证据，同属升级件生命周期缺更新通路，互补不重复。结论：升级件一旦创建即成孤本快照，是转人工机制的结构性短板，值得立项补「证据回写/自动消解」通路。
  - 建议：SPEC 提案：admin 处置落根治提交时，若提交说明或处置关联信号命中某未处理升级件的证据要素，在该升级件记录追加「根治件 hash + 待重验标注」；重验覆盖受影响场景后自动把升级件转 resolved 附重验证据，未通过才保留人工确认，人工包内并列展示申报时证据与根治后证据。
- **[arbiter:web-brow] bootstrap-grant-confirmation（授权自动化无活性确认即报成功）**
  - 影响：所有依赖 emulator-bootstrap-env 的场景族（wire-e2e-*/web-*/playwright-smoke，共 12+ 场景）在授权静默失败/失效时级联 blocked-env；每轮验证 ~2.2M tokens 在无活性确认下盲烧；验证报告被洗白门禁拒收后整轮作废。
  - 证据：scripts/bootstrap_adb.sh:116-145 grant_projection 点击确认弹窗后立即 return 0，不校验投影是否真正落地；round B 实证：projection_grant_try1.png mtime=02:06:45 单次确认分支退出（try2/try3 未写），而 02:17 wire-e2e 已 status=4（凭据失效/未落地均静默）；两源互证：验证报告豁免证据 + 截图 mtime 链。对照：实现侧 e2e/grant.ts「探针 12s 超时+等 shared capture ready 日志信号」已验证可正确识别授权落地（e2e-evidence.md）。grant 子命令（L180）也不含 freezer 关闭与复位链，单独调用时与 all 分支行为不对齐。；触发 run_id=arbiter-web-browser-client-1794421227（签名 web-browser-client::verify_env_blocked，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 verify_env_blocked（spec=web-browser-client）：用户指令冻结：验证环境阻塞连续 2 轮，admin 处置未奏效，转人工 — env 失败场景不得判 verified（须升级 admin 处置）: wire-e2e-fullsession-via-gateway、wire-e2e-attach-hang-resume、wire-e2e-occupied-busy、web-busy-rejection-display、occupied-gate-cleanup、web-wrong-token-explicit-fail、web-fullsession-first-flow、web-tier-selection-custom
  > [成本参考] 。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：①grant_projection tap 后轮询 logcat「shared capture ready」或 dumpsys MediaProjection 活性，超时重试、最终非零退出；②grant 前强制 HOME+force-stop 复位（防同任务其他 Activity 遮挡授予按钮——活体实测 LatencyTestActivity 遮挡形态）；③grant 子命令对齐 all 分支的 freezer/FGS 前置。
- **[arbiter:web-brow] wire-e2e-credential-lifecycle（一次性凭据与场景编排时序错配）**
  - 影响：wire-e2e-fullsession 及 11 个依赖传播场景在当前编排下每轮必然 env-block（结构性、非偶发），任何「修活后重验」都重复失败；是该 spec 冻结循环的直接机制。
  - 证据：源端 MediaProjection 为一次性凭据设计：一授权一凭据、闲置 ~60s 失效、captureLost 自动重授权立即消费凭据（MainActivity.kt:259/263-269 注释明示；e2e-evidence.md 实现侧两轮 9 次尝试取证）；而当前 32 场景集将 wire-e2e-fullsession-via-gateway 排在第 14 位（前置 13 场景含 npm/vitest/playwright 分钟级耗时），轮首授权到会话创建间隔 11 分钟（02:06:45→02:17 实测）；场景声明的「emulator-bootstrap-env 重授权兜底」在本轮未实际触发 bootstrap grant（try1.png 未被 02:17 重写）。两源互证：验证报告跨轮指纹一致 + 截图/进程时间链。；触发 run_id=arbiter-web-browser-client-1794421227（签名 web-browser-client::verify_env_blocked，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 verify_env_blocked（spec=web-browser-client）：用户指令冻结：验证环境阻塞连续 2 轮，admin 处置未奏效，转人工 — env 失败场景不得判 verified（须升级 admin 处置）: wire-e2e-fullsession-via-gateway、wire-e2e-attach-hang-resume、wire-e2e-occupied-busy、web-busy-rejection-display、occupied-gate-cleanup、web-wrong-token-explicit-fail、web-fullsession-first-flow、web-tier-selection-custom
  > [成本参考] 。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：①会话时点就近授权：wire-e2e-fullsession 的 ensure_pipeline/步骤 0 就地复位+grant+等 ready（凭据新鲜度与会话创建对齐）；②或场景集编排把 wire-e2e-fullsession 紧邻 bootstrap 放置（编排语义取舍需产品面拍板）；③或与源端产品面协商延长凭据有效期/支持免弹窗静默授权（产品缺陷面，走实现分支立项）。
- **[arbiter:web-brow] arbiter-rerigger-duplicate-after-escalate**
  - 影响：同签名 frozen spec 在升级包 pending 期间被重复唤起 Arbiter，每轮 ~1-2M tokens 重复裁决消耗，结论必然重复；升级包越多、回扫唤起越频繁，消耗线性放大。
  - 证据：arbiter_ledger.jsonl：前任 run 18:34:37 收工落 escalate 报告，chain-1794421227392 于 18:34:43.429 入链（arbiter-escalated，pending 人工）；同秒回扫唤起本 run arbiter-1794422083（转单上下文两字段空串=非转单），无新增证据，本次终裁与前任一致（escalate）。；触发 run_id=arbiter-web-browser-client-1794422083（签名 web-browser-client::verify_env_blocked，来源 escalation-alert）
  - 调查评估：触发信号 verify_env_blocked（spec=web-browser-client）：用户指令冻结：验证环境阻塞连续 2 轮，admin 处置未奏效，转人工 — env 失败场景不得判 verified（须升级 admin 处置）: wire-e2e-fullsession-via-gateway、wire-e2e-attach-hang-resume、wire-e2e-occupied-busy、web-busy-rejection-display、occupied-gate-cleanup、web-wrong-token-explicit-fail、web-fullsession-first-flow、web-tier-selection-custom
  > [成本参考] 。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：消费端对已有 pending escalation package（handled=false）的签名抑制 Arbiter 重唤起；或 re-trigger payload 附前次 ruling 摘要并声明『仅新证据落地后再裁』的增量裁决语义。
- **[arbiter:web-brow] admin-monitor-orphan-check-generation-blind**
  - 影响：凡「处置生效后 spec 又被后续验证轮再冻结」的场景都会重演：孤儿处置收敛核查把状态前移误判为处置失败，产生假阴性人工升级，并经回扫重复触发同签名仲裁调查（烧仲裁预算、污染人工告警面）；触发建议中「已生效可手动 resolve 收口信号」分支永远不会被框架自动走向，因为生效核查只比对当前状态、不认「生效后状态已合法前移」。
  - 证据：fenixd.log 18:05:47.151（直通道 blocked→testing 复位成功）与 18:06:23-59（round B 重验实际发生）证明处置已生效；18:20:27.206/.311（verified 洗白门禁再冻结）为处置后的新代际状态；19:10:23 admin_monitor「Orphaned final disposition group not effective, escalated to human: admin-web-browser-client-1794419307」以当前 frozen/blocked 反推旧处置未生效（fail-closed），admin_alerts 条目 40e8d6fd4292/43a51528ef70 留痕；arbiter ledger 证明同签名 1 小时内第 3 次仲裁调查（1794421227/1794422083/1794425770）。两源互证：fenixd.log 时序 + admin_alerts/arbiter_ledger 台账。；触发 run_id=arbiter-web-browser-client-1794425770（签名 web-browser-client::blocked_stuck，来源 startup）
  - 调查评估：触发信号 blocked_stuck（spec=web-browser-client）：Administrator 处置终态未收口（run admin-web-browser-client-1794419307）：处置组已终态但底层信号 blocked_stuck 仍未收口，生效核查未找到动作已实际生效的真实状态证据，需人工介入。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：生效核查引入处置代际水位线：处置完成时记录直通道复位时间戳与消费轮次；收敛核查失败前先核对处置后是否出现过「离开 blocked/testing 的推进轨迹」（STATE 更新时序或 gate transition 日志），有则判「已生效、被新代际取代」并转记为新信号而非升级人工；或对处置后再冻结的代际开新信号条目并回链旧处置组，避免旧组孤儿误判。
- **[arbiter:web-brow] scenario-same-class-defect-one-per-round**
  - 影响：影响所有批量生成含 probe/fixture 步骤场景的 spec：同款缺陷按依赖链深度串行放大为 N 轮重跑，直接拖撞 judge_rerun_cap（本案例即此机制与 gap-1 叠加致冻结）；验证收敛时间与 token 成本随链深度线性膨胀
  - 证据：.fenix/verify-progress/web-browser-client/{prev-judge-result.json,.fenix-run-result.json,test_scenarios.json}：fake-source-up（第14轮）与 web-busy-holder-up（第15轮）先后以完全同款缺陷失败——步骤命令内冗余 cd client-web/gateway（引擎已按 step.cwd 切目录）+ 裸 node 丢 NODE_PATH 致 MODULE_NOT_FOUND('ws')；且残余 active 集内 busy-holder-down 仍带同款 redundant-cd+无 NODE_PATH 模式。三个实例同源（同代批量生成场景），但依赖链执行顺序使其逐轮各浮现一个，每实例消耗一整轮 judge（~10min、~0.1M tokens）与一次修复申报配额；触发 run_id=arbiter-web-browser-client-1794459958（签名 web-browser-client::judge_rerun_cap，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 judge_rerun_cap（spec=web-browser-client）：'web-browser-client' judge 重跑超上限（6/5 轮），已冻结等待人工裁决。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：①场景加载期静态 lint：validate_scenario_dicts 增加「命令内 cd 与 step.cwd 目录重复」与「命令引用 /tmp 脚本且裸调 node 无 NODE_PATH」两条机械检查，加载即报而非执行期才败；②judge 裁决协议允许同因类批修复：同一缺陷类命中多场景时一次修复全链并一次申报，不必逐轮等失败复现；③批量生成器模板收敛 cd/NODE_PATH 口径（生成端根治）
- **[arbiter:web-brow] 纯归因模式testing停泊与Recovery重派回环**
  - 影响：无修复纯归因模式下验证链结构性不可收敛：每次 Recovery 重派机械重放同一未修复缺陷、rerun 计数必然递增至触顶，产生必然的冻结与人工升级，每轮白耗一次 34 场景执行与 judge token（本链累计 input 2.5M+/output 690K+）；同时把可由框架自愈的局面反复升级给人类。
  - 证据：fenixd.log：06:51:39Z 拒收修复申报后 spec 保持 testing 且无驱动（「场景文件不改写、不重入执行，转纯归因」）→ 07:09:20Z orphaned_state（testing 超 17 分钟无驱动）→ 07:14:18Z「Recovery: re-triggering verify for undriven testing」全量重执行 34 场景 → 07:15:26Z rerun 6/5 触顶冻结。本窗口两小时内 rerun 计数 0→6，其中末轮为 Recovery 自动重派而非 judge 主动复判。；触发 run_id=arbiter-web-browser-client-1794467726（签名 web-browser-client::judge_rerun_cap，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 judge_rerun_cap（spec=web-browser-client）：'web-browser-client' judge 重跑超上限（6/5 轮），已冻结等待人工裁决。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：无修复纯归因模式进入时将 spec 停泊在不可调度保持态（如 frozen_hold 类）而非 testing；或 Recovery 重派在「场景集+产物指纹未变且末轮含未修复 test-defect」时跳过执行直接沿用既有归因、不递增 rerun 计数；或首次「耗尽后拒收」即转人工决策路径而非继续消耗 rerun 上限。
- **[arbiter:web-brow] 跨轮裁决缓存与闭包征用失配**
  - 影响：缓存写而不用导致每次派发全量重执行，纯归因模式下结果机械可预期（同场景集+同未修复缺陷），全量重跑只烧预算不改结论；也使 rerun 上限被无信息增量消耗。
  - 证据：fenixd.log 06:51:39Z「跨轮裁决缓存已更新 34 个场景结论入缓存（四元组分量 build=✓/config=✓）」，但 07:14:49Z 紧随其后的派发「闭包征用后无可采信缓存条目，全量执行」——分钟级前写入的缓存未被下一次派发采信；verdict_stability.json 仍只含 04:18:54Z 第 1 轮记录（prev_stamp=null、report_mtime_ns 指向旧报告），解冻后各轮均未入册。；触发 run_id=arbiter-web-browser-client-1794467726（签名 web-browser-client::judge_rerun_cap，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 judge_rerun_cap（spec=web-browser-client）：'web-browser-client' judge 重跑超上限（6/5 轮），已冻结等待人工裁决。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：闭包征用对同指纹（场景集+build/config 四元组不变）条目的采信策略放宽；verdict_stability 戳记接入派发前判定，使「指纹未变」轮次复用裁决而非重执行；为纯归因模式定义显式的缓存复用语义。
