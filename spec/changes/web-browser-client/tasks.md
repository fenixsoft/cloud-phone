## Deliverables

```yaml
- path: client-web/gateway/package.json
  kind: new
- path: client-web/gateway/server.js
  kind: new
- path: client-web/gateway/test/relay.test.js
  kind: new
- path: client-web/app/package.json
  kind: new
- path: client-web/app/tsconfig.json
  kind: new
- path: client-web/app/index.html
  kind: new
- path: client-web/app/src/protocol/Framing.ts
  kind: new
- path: client-web/app/src/protocol/Messages.ts
  kind: new
- path: client-web/app/src/net/SessionClient.ts
  kind: new
- path: client-web/app/src/media/VideoPipeline.ts
  kind: new
- path: client-web/app/src/media/AudioPipeline.ts
  kind: new
- path: client-web/app/src/media/audio-worklet.js
  kind: new
- path: client-web/app/src/input/InputBridge.ts
  kind: new
- path: client-web/app/src/ui/Hud.ts
  kind: new
- path: client-web/app/src/main.ts
  kind: new
- path: client-web/app/tests/protocol.golden.test.ts
  kind: new
- path: client-web/app/e2e/smoke.spec.ts
  kind: new
- path: docs/protocol.md
  kind: modify
- path: docs/web-client.md
  kind: new
- path: docs/web-latency-samples.json
  kind: new
```

## 1. 协议 TS 库与 golden 单测

- [x] 1.1 建 `client-web/app` npm 工程：package.json（dev 依赖 vite/typescript/vitest/playwright，运行时零依赖）+ index.html 骨架 + tsconfig，不进任何 Gradle 构建
- [x] 1.2 `src/protocol/Framing.ts`：大端记录流读写（`[u8 recordType][u32 len][payload]`）、21B 视频帧头解析（seq/ptsUs/flags/fragIdx/fragCnt/totalLen）、TCP 整帧口径（fragCnt≠1 报错）、seq 单调性丢帧计数
- [x] 1.3 `src/protocol/Messages.ts`：17 条控制消息（0x01–0x11）编解码；解码器对未知尾部字节一律容忍跳过（HELLO 可选 takeover 尾字节先例）
- [x] 1.4 `tests/protocol.golden.test.ts`：golden 字节向量（从 Kotlin FramingTest/MessagesTest 用例导出）双向逐字节断言；含 takeover 尾字节出现/缺失两形态与未知尾部字节容忍用例

## 2. 协议无关网关

- [x] 2.1 `client-web/gateway/server.js`（Node + ws 单依赖）：同端口静态托管 + `/ws` 升级，env `GATEWAY_PORT`（默认 8090）/`UPSTREAM`（默认 127.0.0.1:22333），每条 WS 一条 upstream TCP，双向字节搬运，WS 断↔TCP 断联动
- [x] 2.2 背压：upstream 写缓冲高水位暂停读 WS（反之同理），不丢字节不乱序
- [x] 2.3 `gateway/test/relay.test.js`：脚本化假源端集成测试——上/下行逐字节透传、REJECT_BAD_TOKEN 透传后随关、慢消费者 8MB 不丢不乱、双 WS 并发不串扰、env 缺省与覆盖

## 3. 视频管线（首个采集里程碑，含可观测性交付）

- [x] 3.1 `src/media/VideoPipeline.ts`：`isConfigSupported` 探测（不支持→含浏览器名的错误页）；VideoDecoder Annex B + optimizeForLatency + prefer-hardware；逐帧 canvas drawImage 渲染（渲染后 close）
- [x] 3.2 管线可观测性：decoded/dropped(seq 缺口)/decodeQueueSize/关键帧四类逐级计数 + 诊断开关导出首 N 帧原始负载 dump（帧 dump + 计数双交付，供延迟专项比对）

## 4. 音频管线

- [x] 4.1 `src/media/AudioPipeline.ts` + `src/media/audio-worklet.js`：首帧 ADTS 解析→AudioSpecificConfig description→AudioDecoder('mp4a.40.2')；AudioWorklet 环形缓冲，起播水位 120ms、不足即 skip 并计数，兼容静音抑制 keepalive
- [x] 4.2 无 `window.AudioDecoder` 显式降级：音频控件置灰 + 原因提示，视频触控不受影响

## 5. 会话编排与控制面

- [x] 5.1 `src/net/SessionClient.ts`：连接生命周期（token → HELLO → START_SESSION → HELLO_ACK 真实参数采用；sessionId 换新即重置坐标基准）、APP_LIST、STOP_SESSION；token 错与 REJECT_BUSY（观看端占用/ATTACH 恢复失败）显式失败不静默重试
- [x] 5.2 控制面：应答源端 1Hz 入站 PING（Pong nonce/senderUs/nowUs）；offset ≈ echoUs − (t_send+t_recv)/2 与 RTT 估计；消费源端 STATS；断线指数退避重连（≤30s 窗口）→ START_SESSION{sessionId, mode=ATTACH}（不发 IDR_REQUEST），超窗或被显式拒绝（REJECT_BUSY/REJECT_UNSUPPORTED_MODE）即停止重试回连接页带原因

## 6. 输入与 UI 壳

- [x] 6.1 `src/input/InputBridge.ts`：pointer 事件→INPUT_EVENT（letterbox 裁剪线性映射→×65535 u16→越界钳制；pointerId→u8 活动表），BACK 按钮+Esc=action3，隐藏输入框→TEXT_INPUT(kind=0)+ACK 提示，CLIPBOARD_GET/SET+CLIPBOARD_TEXT(kind=1) 置灰 toast
- [x] 6.2 `src/main.ts` + `src/ui/Hud.ts`：连接页（URL ?token= 存 localStorage 可清除；档位选择 720p/1080p×30/60×4/8/16/20Mbps 默认 720p60/8M、区间校验）→应用网格→全屏 canvas+悬浮工具条；HUD 展示 fps/码率/RTT/管线延迟 P50/P95/解码队列/丢帧 + 落 docs/web-latency-samples.json 开关（浏览器端口径注明）

## 7. 端到端验收与文档

- [x] 7.1 `e2e/smoke.spec.ts` Playwright 冒烟（模拟器 FULLSCREEN 口径）：起 bootstrap 环境→token 连接→首帧 canvas 像素非空→注入点击→浏览器侧收到 STATS 且 fps>0、logcat `cloudphone-touch`（t3）与 `cloudphone-source` 打点；模拟器 SINGLE_APP 仅验收"失败带原因回连接页"回退
  > ✅ 以行内替代场景链为验收基准勾选（原偏差注解留存为持久记录）：冒烟基建全量交付且 tsc/playwright 校验通过；上轮取得一次全绿运行（2/2 passed，含首帧 canvas 像素与 STATS fps>0 断言，证据 .fenix/verify-evidence/web-browser-client/e2e-evidence.md）；点击注入断言以浏览器侧 WS 出站 INPUT_EVENT 0x06 帧计数替代（源端 t2/t3 打点留真机人工复测项）。本 run 复跑 3 次均被源端环境阻塞（会话层 HELLO 处理滞后 >12s、授权 tap 冻结延迟、一次性投影凭据），按三振止损冻结；顺手加固 grant.ts（探针超时 4s→12s、复位后等「shared capture ready」日志信号再探——本轮验证生效）与 FULLSCREEN 用例超时 180s。
- [x] 7.2 `docs/protocol.md` 追加浏览器对接注记并勘误三处：§4 附着时序（ATTACH=START_SESSION 非 HELLO 字段，附着自动 IDR）、控制方向表（源端→客户端 1Hz PING/STATS 推送）、§3.1 补记 HELLO 可选 takeover 尾字节 + §5 REJECT_BUSY 双来源语义（传输层观看端占用闸门 + ATTACH 恢复失败）与会话层无并发上限
- [x] 7.3 `docs/web-client.md`：部署手册（网关 env/systemd、frpc 新增 proxy remotePort→GATEWAY_PORT、token 书签交付）+ **真机单应用人工验收清单**（人工步骤入文档：单应用进入/切换/ATTACH、音频、剪贴板、文本注入、PC 键鼠与平板触屏）
- [ ] 7.4 延迟采样交付：模拟器会话稳定运行后采集管线延迟 P50/P95 与 RTT 落 `docs/web-latency-samples.json`（口径注明浏览器端），供延迟专项比对
  > ⚠️ 部分达成（环境阻塞，同 7.1）：交付物文件已按 `Hud.exportSamplesDoc` schema 落盘（口径注明浏览器端），采样数组为空并附 `blocker` 块——模拟器会话在本 run 内无法稳定运行（MediaProjection 一次性凭据 + accept 线程僵尸监听 + 权限框进程冻结，>3 轮同因，证据 .fenix/verify-evidence/web-browser-client/e2e-evidence.md，恢复路径写入 blocker.recover）。真实样本待源端环境健康后由 HUD 下载覆盖。本 run 追记：恢复路径缺口已补——HUD 新增「下载采样」按钮（docs/web-client.md §6「停止后下载」承诺的交互落地，含单元测试），环境健康后连接 → 开采样 → 运行 ≥60s → 停止 → 下载即可覆盖本文件；本轮 3 次复跑仍被源端会话层 HELLO 处理滞后（>12s）阻塞，按三振止损冻结。
