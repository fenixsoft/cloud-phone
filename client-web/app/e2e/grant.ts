/**
 * 源端共享采集管线就绪保障（e2e 专用，镜像约束适配）：
 * - 本镜像一授权只能铸一个 MediaProjection 实例（闲置 ~60s 失效）；
 * - 「授权后立即创建共享采集管线（一授权一 VD；会话 attach 不再触碰投影令牌）」
 *   （StreamService.ensureSharedCapture）→ 管线常驻后所有会话零令牌消耗挂接；
 * - 投影丢失后 MainActivity.onResume 自动弹重授权框（captureLost 路径），确认即重建。
 * ⇒ 判据：真实探针——HELLO 被接受且 START_SESSION 应答 HELLO_ACK 为 ACCEPTED（该应答
 *   携带真实 sessionId；logcat 启发式不可靠——「ready 后紧接 lost」的抖动与 dumpsys
 *   存活投影并存），就绪后立即以该 sessionId 发 STOP_SESSION 显式回收，探针不留挂机。
 *   拒绝则走 UI 自动确认重建。
 */

import net from 'node:net';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { HelloStatus, WatchMode, encodeControlRecord, decodeControlMessage } from '../src/protocol/Messages';
import { MAX_RECORD_BYTES, RECORD_HEADER_BYTES, RECORD_CONTROL } from '../src/protocol/Framing';
import { adb, sleep, HOST_PORT } from './adbx';
import { E2E_STATE } from './global-setup';
import { APP_GRANT_PATTERN, START_NOW_PATTERN, tapText } from './uiautomator';

// HOST_PORT 单处声明于 adbx.ts（env 名 + 默认值），此处 import 使用

/** 探针档位 = 浏览器端默认档位（720p 竖屏 60fps / 8Mbps）。源端在「请求档位与现行管线
 * 不同时按请求重建」（StreamService.handleStartSession → ensureSharedCapture），探针档位
 * 与冒烟会话档位一致才能零扰动（不触发 MediaProjection/VD 抖动）。出处：src/main.ts
 * RESOLUTIONS['720p']（720×1280）+ index.html 档位控件默认选中项（60 fps / 8 Mbps）；
 * SessionClient 无可 import 的默认档位常量，故本地声明；防漂移不靠人工对齐——模块加载
 * 时经 assertProbeProfileMatchesPageDefault 与 index.html 默认档位强制比对，漂移即早期失败。 */
const PROBE_PROFILE = { width: 720, height: 1280, fps: 60, bitrateKbps: 8000 } as const;

/** 档位防漂移闸门：模块加载时读取本检出的 index.html，解析档位控件默认选中项（分辨率
 * 选项的 data-width/data-height 属性 + fps/bitrate 的 selected value）与 PROBE_PROFILE 比对，
 * 不一致立即抛错并附三方值（grant.ts PROBE_PROFILE / index.html 默认项 / 分辨率出处
 * main.ts RESOLUTIONS）——e2e 早期失败优于静默漂移触发源端按请求重建管线。 */
function assertProbeProfileMatchesPageDefault(): void {
  const htmlPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'index.html');
  let html: string;
  try {
    html = readFileSync(htmlPath, 'utf8');
  } catch (err) {
    throw new Error(`档位防漂移检查：无法读取 ${htmlPath}（${String(err)}）`);
  }
  // 取指定 <select> 内带 selected 的 <option>：value 为档位值，attrs 串供 data-* 解析
  const selectedOption = (selectId: string): { value: string; attrs: string } => {
    const select = new RegExp(`<select id="${selectId}"[^>]*>((?:(?!</select>)[\\s\\S])*)</select>`).exec(html);
    const option = select ? /<option\s+([^>]*\bselected[^>]*)>/.exec(select[1]) : null;
    const value = option ? /value="([^"]*)"/.exec(option[1]) : null;
    if (!option || !value) {
      throw new Error(`档位防漂移检查：index.html #${selectId} 未解析到 selected 默认项（控件结构变动，防漂移闸门无法核对）`);
    }
    return { value: value[1], attrs: option[1] };
  };
  const res = selectedOption('profile-resolution');
  const pageDefault = {
    width: Number(/data-width="(\d+)"/.exec(res.attrs)?.[1]),
    height: Number(/data-height="(\d+)"/.exec(res.attrs)?.[1]),
    fps: Number(selectedOption('profile-fps').value),
    bitrateKbps: Number(selectedOption('profile-bitrate').value),
  };
  if (pageDefault.width !== PROBE_PROFILE.width || pageDefault.height !== PROBE_PROFILE.height
    || pageDefault.fps !== PROBE_PROFILE.fps || pageDefault.bitrateKbps !== PROBE_PROFILE.bitrateKbps) {
    throw new Error(
      `档位防漂移检查失败：PROBE_PROFILE=${JSON.stringify(PROBE_PROFILE)} ≠ index.html 默认档位 ` +
      `${JSON.stringify(pageDefault)}（选中 ${res.value}，分辨率出处 src/main.ts RESOLUTIONS['${res.value}']）——` +
      '三方对齐前禁止探针（探针档位≠会话档位会触发源端按请求重建管线，违背零扰动）',
    );
  }
}
assertProbeProfileMatchesPageDefault();

/** 等待 logcat 出现指定标记（cached-app freezing 会把授权确认 tap 的生效延迟到
 * 探针之后——管线重建完成的唯一权威信号是 StreamService 的「shared capture ready」
 * 日志，等它再探，避免探针抢跑）。 */
async function waitForLogMarker(pattern: string, timeoutMs: number): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const out = await adb('logcat', '-d');
      if (new RegExp(pattern).test(out)) return true;
    } catch {
      /* 单次 dump 失败继续轮询 */
    }
    await sleep(1500);
  }
  return false;
}

/** 真实探针：HELLO(token 裁决) → START_SESSION(浏览器默认档位) → 第二个 HELLO_ACK 为
 * ACCEPTED 才判就绪（该应答携带真实 sessionId；冷启动 ensureSharedCapture 耗时远超
 * HELLO 往返，给足 30s 窗口替换原 400ms 竞态感知）→ 立即以真实 sessionId 发
 * STOP_SESSION 显式回收（探针会话不留挂机），返回是否被接受。源端 HELLO 应答的
 * sessionId 恒为占位 0，不能用作 STOP 目标。 */
function probeSessionAccepted(): Promise<boolean> {
  return new Promise((resolve) => {
    const hello = encodeControlRecord({ kind: 'hello', version: 1, token: E2E_STATE.token, mode: WatchMode.FULLSCREEN, packageName: '', width: 0, height: 0, fps: 30, bitrateKbps: 8000, sessionId: 0, p2pOffer: '', crypto: null });
    const start = encodeControlRecord({ kind: 'startSession', sessionId: 0, mode: WatchMode.FULLSCREEN, packageName: '', width: PROBE_PROFILE.width, height: PROBE_PROFILE.height, fps: PROBE_PROFILE.fps, bitrateKbps: PROBE_PROFILE.bitrateKbps });
    const s = net.connect(HOST_PORT, '127.0.0.1', () => {
      // START_SESSION 不再固定延迟 300ms 抢跑：源端 HELLO 裁决变慢时 START 先于裁决
      // 到达会被按未握手请求拒绝，该 REJECT 被当作第一响应误判 token 失败 → 触发
      // ensureSourcePipeline 的 force-stop + 重新授权 + 45s 等日志全量复位路径。
      // 改为在 data 处理器收到第一响应 HELLO 裁决 ACCEPTED 后再发（保留 30s 冷启动窗口）
      s.write(hello);
    });
    let buf = Buffer.alloc(0);
    let decided = false;
    let helloAcked = false; // 第一响应（HELLO 裁决）已收，等待 START_SESSION 应答
    let startAccepted = false; // START_SESSION 已被接受（就绪判据成立，仅余 STOP 回收窗口）
    let startTimer: ReturnType<typeof setTimeout> | null = null;
    const finish = (ok: boolean) => {
      if (decided) return;
      decided = true;
      clearTimeout(helloTimer);
      if (startTimer) clearTimeout(startTimer);
      s.destroy();
      resolve(ok);
    };
    const helloTimer = setTimeout(() => finish(false), 12_000);
    s.on('error', () => finish(false));
    // 对端立即断开（force-stop / 端口 RST / 无数据即关）时立即判失败，不烧满 helloTimer；
    // START 已被接受后对端关闭属预期（STOP 回收窗口内），不得经 close 回翻成功判据
    s.on('close', () => { if (!startAccepted) finish(false); });
    s.on('data', (d: Buffer) => {
      buf = Buffer.concat([buf, d]);
      while (buf.length >= RECORD_HEADER_BYTES) {
        const recordType = buf[0]; // 记录头类型字节：切片推进前读取（口径对齐 Framing.RecordStreamParser）
        const len = buf.readUInt32BE(1);
        if (len > MAX_RECORD_BYTES) {
          // 记录长度超上限 = 线路损坏（口径对齐 Framing.RecordStreamParser 的同值防护），
          // 立即失败；否则异常/错位流会空等不存在的记录长度直到 timer 兜底
          finish(false);
          return;
        }
        if (buf.length < RECORD_HEADER_BYTES + len) break;
        const record = buf.subarray(RECORD_HEADER_BYTES, RECORD_HEADER_BYTES + len);
        buf = buf.subarray(RECORD_HEADER_BYTES + len);
        if (recordType !== RECORD_CONTROL) continue;
        try {
          const msg = decodeControlMessage(record); // record 即控制载荷，首字节为 msgType（非 recordType）
          if (msg.kind !== 'helloAck') continue;
          if (!helloAcked) {
            // 第一响应 = HELLO 裁决（token 不匹配 → REJECT_BAD_TOKEN）
            if (msg.status !== HelloStatus.ACCEPTED) {
              finish(false);
              return;
            }
            helloAcked = true;
            s.write(start); // HELLO 裁决 ACCEPTED 后再发 START_SESSION（消除固定 300ms 时序假设）
            startTimer = setTimeout(() => finish(false), 30_000); // START 冷启动窗口
          } else if (msg.status === HelloStatus.ACCEPTED) {
            // 第二响应 = START_SESSION 被接受（就绪判据成立，sessionId 为真实值）
            startAccepted = true; // 判据已成立：其后对端关闭（STOP 回收）不回翻失败
            s.write(encodeControlRecord({ kind: 'stopSession', sessionId: msg.sessionId }));
            // 留出 STOP 往返窗口再断开：先 destroy 会让 DISCONNECTED 抢在 STOP 前到，
            // 走断开挂机路径遗留挂机会话
            setTimeout(() => finish(true), 500);
          } else if (!startAccepted) {
            // 与上方 close 路径同口径：判据成立后的 STOP 回收窗口内，源端若以 helloAck
            // 形态对 STOP_SESSION 回 REJECT（如 session not found），不得回翻已成立的
            // 就绪判据（否则触发 ensureSourcePipeline 无谓的全量复位重建管线）
            finish(false);
          }
        } catch {
          /* 非控制记录忽略 */
        }
      }
    });
  });
}

/** ensureSourcePipeline 总体时限（毫秒）：E2E_PIPELINE_TIMEOUT 覆盖，缺省 80s。复位路径
 * 各阶段串联最坏可达 3 分钟以上，而调用方用例超时为 90s（playwright.config.ts timeout）——
 * 无总体时限会在复位中途被外部打断且丢失卡点诊断，故缺省须小于 90s 才能让自带诊断的
 * 错误先落地（正常复位全路径 ~35-50s，80s 覆盖之）；预期更慢复位时连同用例超时一起经
 * env 放宽。 */
const PIPELINE_TIMEOUT_MS = Number(process.env.E2E_PIPELINE_TIMEOUT) || 80_000;

/** 确保源端可接受新会话（探针 ACCEPTED）。
 * 接受失败 → 全量复位路径：force-stop（清僵尸投影/挂机会话/VD）→ FGS → MainActivity
 * → 手动路径授予（只存凭据不消费，区别于 captureLost 自动重授权路径的立即消费）
 * → 系统框确认 → 探针消费新鲜凭据建管线。
 * 总体 deadline：在各阶段边界检查剩余预算（阶段内部超时/重试逻辑不动；单阶段自身的
 * 45s 日志等待等极端越界要到下一边界才被发现），耗尽即抛带已完成阶段清单的明确错误。 */
export async function ensureSourcePipeline(): Promise<void> {
  const deadline = Date.now() + PIPELINE_TIMEOUT_MS;
  const donePhases: string[] = [];
  const checkBudget = (next: string): void => {
    if (Date.now() < deadline) return;
    throw new Error(
      `ensureSourcePipeline 超出总体时限 ${PIPELINE_TIMEOUT_MS}ms（E2E_PIPELINE_TIMEOUT 可调），` +
      `进入「${next}」前中止；已完成阶段：${donePhases.join(' → ') || '（无）'}`,
    );
  };
  const phaseDone = (name: string): void => { donePhases.push(name); };

  checkBudget('端口转发');
  await adb('forward', `tcp:${HOST_PORT}`, `tcp:${HOST_PORT}`);
  phaseDone('端口转发');

  checkBudget('首次探针');
  if (await probeSessionAccepted()) return;
  phaseDone('首次探针（未接受）');

  checkBudget('force-stop 复位');
  await adb('shell', 'am', 'force-stop', 'cloudphone.source');
  await sleep(2000);
  await adb('logcat', '-c'); // 复位起点清日志，「shared capture ready」只认本轮重建
  await adb('shell', 'am', 'start-foreground-service', '-n', 'cloudphone.source/.StreamService');
  await sleep(2000);
  await adb('shell', 'svc', 'power', 'stayon', 'true');
  await adb('shell', 'am', 'start', '-n', 'cloudphone.source/.MainActivity');
  await sleep(3000);
  phaseDone('force-stop 复位 + FGS + MainActivity');

  checkBudget('授权 tap 轮询');
  for (let i = 0; i < 4; i++) {
    if (await tapText(APP_GRANT_PATTERN, { unfreeze: true })) break;
    await sleep(1500);
  }
  phaseDone('授权 tap 轮询');

  checkBudget('开始采集 tap 轮询');
  for (let i = 0; i < 4; i++) {
    if (await tapText(START_NOW_PATTERN, { unfreeze: true })) break;
    await sleep(1500);
  }
  phaseDone('开始采集 tap 轮询');

  // 授权 tap 的实际生效受系统冻结进程调度延迟（实测 ~13s），必须等管线重建完成的
  // 日志信号再探，否则探针抢跑于对话框确认 → 拒绝
  checkBudget('shared capture ready 日志等待');
  if (!(await waitForLogMarker('shared capture ready', 45_000))) {
    console.warn('[e2e] 未等到 shared capture ready 日志标记，直接进入探针');
  }
  phaseDone('shared capture ready 日志等待');

  checkBudget('复位后探针重试');
  for (let i = 0; i < 3; i++) {
    if (await probeSessionAccepted()) return;
    await sleep(2500);
  }
  throw new Error('源端无法接受会话（复位+授予+管线就绪后 START_SESSION 探针仍未被接受）');
}
