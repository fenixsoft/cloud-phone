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
  - 影响：env 误判会把产品回归以『环境豁免』永久放行；本场景已因此连续 2 轮 warning 并烧掉 judge+admin 两段链，最终仍需人类。所有依赖 judge env 归因的场景集都受影响。
  - 证据：judge_attribution.json 中 regression-matrix-12-combos 判 env『swiftshader 软渲染确定性天花板（两轮 37.4/38.6）』；但 /tmp/admin-load-sample.txt 显示 load1 5.07~17.60 波动期间该组合 fps 恒定 36.2~37.5、源端 CPU 恒定 285~288%，历史两轮同宿主 1080p 51.8~59.1 PASS（git 21fefb6/07a7ee6），且 judge 历史 smoke 探针全部为 720p（planner-judge-1794160702 转录 7 次 --only lan-direct,game,720p）。；触发 run_id=arbiter-client-ux-and-input-latency-1794162973（签名 client-ux-and-input-latency::warning_stale_triage，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 warning_stale_triage（spec=client-ux-and-input-latency）：'client-ux-and-input-latency' 以下场景连续多轮均为 warning，需人工 triage 甄别是否真缺陷被悬置：regression-matrix-12-combos（连续 2 轮）。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：env 归因必须引用可复核的环境量测（负载/CPU/温度/驱动版本）并要求验证窗口外的对照样本；无对照的 env 结论降级为 inconclusive 并自动进 recheck，不得直接进豁免握手。
- **[arbiter:client-u] 实现自验以 --only 720p 子集替代验收全矩阵**
  - 影响：本 spec 的 1080p 性能回归在实现阶段漏检，直到首轮验证（18:12）才暴露，随后连续 2+ 轮 warning 升级、链上消耗两段 run。
  - 证据：commit 7006cc8（任务 6.1『矩阵实跑』）只执行 `--only lan-direct,game,720p`；impl-result.json 的 18 项 completed_tasks 无 12 组合全跑记录；而验收场景 test_scenarios.json#regression-matrix-12-combos 要求 12 条 passed=true（含 1080p）。；触发 run_id=arbiter-client-ux-and-input-latency-1794162973（签名 client-ux-and-input-latency::warning_stale_triage，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 warning_stale_triage（spec=client-ux-and-input-latency）：'client-ux-and-input-latency' 以下场景连续多轮均为 warning，需人工 triage 甄别是否真缺陷被悬置：regression-matrix-12-combos（连续 2 轮）。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：impl 收工门：当 spec 验收含全量矩阵/长场景时，impl-result 必须附该验收场景原命令的完整产物（12/12 或带归因的失败），否则不允许勾 6.x 收工；或把矩阵子集跑标注为 partial 并在 verify 前置拦截。
- **[arbiter:client-u] 矩阵场景断言只覆盖 progress 首尾条目**
  - 影响：若 run_matrix 退出码语义漂移或中途异常，中间组合失败可能被漏判为 PASS；本轮 1080p 失败仅靠 exit_code 断言兜住，断言与描述不一致。
  - 证据：.fenix/verify-progress/client-ux-and-input-latency/test_scenarios.json 中 regression-matrix-12-combos 的 assertions 仅有 `json_output [0].passed` 与 `json_output [11].passed`，其余 10 组合靠 run_matrix 退出码间接覆盖；场景描述却写『恰 12 条逐条 passed=true』。；触发 run_id=arbiter-client-ux-and-input-latency-1794162973（签名 client-ux-and-input-latency::warning_stale_triage，来源 chain-handoff:escalation-alert）
  - 调查评估：触发信号 warning_stale_triage（spec=client-ux-and-input-latency）：'client-ux-and-input-latency' 以下场景连续多轮均为 warning，需人工 triage 甄别是否真缺陷被悬置：regression-matrix-12-combos（连续 2 轮）。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：增加聚合断言（一步 python 校验 len==12 且 all(passed)，或 json_output 指向汇总字段），把『逐条 passed』从描述落到断言；同类多组合场景统一模板。
- **[arbiter:client-u] 升级件自环回派（链终点 escalation alert 二次入 Arbiter 车道）**
  - 影响：每次 Arbiter 升级都会多派一轮同证据调查（本签名 2 轮，约 15 万 tokens/7 分钟量级），占用单飞串行队列并推迟真正新信号的裁决；升级件（给人类的裁决包）与 Arbiter 回执语义混淆，直到 arbiter_rulings 配额（=2）耗尽才停。
  - 证据：core/pm_engine/family.py:_send_human_escalation_alert 在 store.append 成功后无条件走 narrative_yielded_to_arbiter → ArbiterLane.submit_for_alert，未以 chain_context 非空短路；fenixd.log 实证：18:43:30.431 arbiter 产裁决包升级 → 18:43:30.677 落告警 c6fccc1be3e8（kind=escalation, chain.endpoint_kind=arbiter-escalated）→ 18:43:30.847 'arbiter 触发入队 ... source=escalation-alert'，本 run 与 1794162973 证据完全相同（最近验证仍为 18:31:57Z）。；触发 run_id=arbiter-client-ux-and-input-latency-1794163410（签名 client-ux-and-input-latency::warning_stale_triage，来源 escalation-alert）
  - 调查评估：触发信号 warning_stale_triage（spec=client-ux-and-input-latency）：'client-ux-and-input-latency' 以下场景连续多轮均为 warning，需人工 triage 甄别是否真缺陷被悬置：regression-matrix-12-combos（连续 2 轮）。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：在 family 咽喉对 chain_context 非空的链终点件跳过 submit_for_alert（只保留 narrative 或零补叙）；或在 submit_for_alert 入口检测 entry['chain'] 存在即短路；补单测覆盖「链终点件不产生新的 arbiter 触发」。
- **[arbiter:client-u] 矩阵进度未记录实际采集/编码几何，跨轮 fps 基线不可比**
  - 影响：验收矩阵跨轮/跨 commit 比较失真，本次仲裁险些把 +24% 像素的真实负载增长整体误判为产品回归；也直接影响 env 归因与 D6 阈值决策的可信度。
  - 证据：21fefb6 与 07a7ee6 的 docs/validation-report.md.progress.json 仅记 resolution 标签「1080p」与 fps，不记 VD/编码尺寸；cb0536b 后实际为 1072x2400（/tmp/admin-src-logcat.txt：capture: vd=... 1072x2400@30），旧管道为 1920x1080；两轮被当作同一组合直接比较（55.7 vs 36.2fps）。；触发 run_id=arbiter-client-ux-and-input-latency-1794163410（签名 client-ux-and-input-latency::warning_stale_triage，来源 escalation-alert）
  - 调查评估：触发信号 warning_stale_triage（spec=client-ux-and-input-latency）：'client-ux-and-input-latency' 以下场景连续多轮均为 warning，需人工 triage 甄别是否真缺陷被悬置：regression-matrix-12-combos（连续 2 轮）。Arbiter 调查判定为框架缺口——修复涉及框架代码或产品面变更，超出处置白名单，记 todo 由自进化统一立项。
  - 建议：progress JSON 每条组合增记 vdW/vdH/encW/encH/requestedW/H、pipeline commit 与 gpu 模式；报告 markdown 增列实际几何列；跨轮比较按几何分组或显式标注「不可比」。
- **[arbiter:client-u] 人工裁定落位后升级告警仍回派（human disposition 吸收窗缺失）**
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
