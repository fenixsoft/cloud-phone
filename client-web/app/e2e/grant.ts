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
import { spawn } from 'node:child_process';
import { HelloStatus, WatchMode, encodeControlRecord, decodeControlMessage } from '../src/protocol/Messages';
import { RECORD_HEADER_BYTES, RECORD_CONTROL } from '../src/protocol/Framing';
import { E2E_STATE } from './global-setup';
import { APP_GRANT_PATTERN, START_NOW_PATTERN, tapText } from './uiautomator';

const SERIAL = process.env.E2E_SOURCE_SERIAL ?? 'emulator-5554';
const HOST_PORT = Number(process.env.E2E_SOURCE_PORT ?? 22333);

/** 探针档位 = 浏览器端默认档位（720p 竖屏 60fps / 8Mbps）。源端在「请求档位与现行管线
 * 不同时按请求重建」（StreamService.handleStartSession → ensureSharedCapture），探针档位
 * 与冒烟会话档位一致才能零扰动（不触发 MediaProjection/VD 抖动）。出处：src/main.ts
 * RESOLUTIONS['720p']（720×1280）+ index.html 档位控件默认选中项（60 fps / 8 Mbps）；
 * SessionClient 无可 import 的默认档位常量，故本地声明，勿与页面档位各自漂移。 */
const PROBE_PROFILE = { width: 720, height: 1280, fps: 60, bitrateKbps: 8000 } as const;

function adb(...args: string[]): Promise<string> {
  return new Promise((resolve, reject) => {
    const p = spawn('adb', ['-s', SERIAL, ...args], { stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    p.stdout.on('data', (d: Buffer) => (out += d.toString()));
    p.stderr.on('data', (d: Buffer) => (out += d.toString()));
    p.on('close', (code) => (code === 0 ? resolve(out) : reject(new Error(`adb ${args.join(' ')} → ${out}`))));
    p.on('error', reject);
  });
}

const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));

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
      s.write(hello);
      setTimeout(() => s.write(start), 300);
    });
    let buf = Buffer.alloc(0);
    let decided = false;
    let helloAcked = false; // 第一响应（HELLO 裁决）已收，等待 START_SESSION 应答
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
    s.on('data', (d: Buffer) => {
      buf = Buffer.concat([buf, d]);
      while (buf.length >= RECORD_HEADER_BYTES) {
        const recordType = buf[0]; // 记录头类型字节：切片推进前读取（口径对齐 Framing.RecordStreamParser）
        const len = buf.readUInt32BE(1);
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
            startTimer = setTimeout(() => finish(false), 30_000); // START 冷启动窗口
          } else if (msg.status === HelloStatus.ACCEPTED) {
            // 第二响应 = START_SESSION 被接受（就绪判据成立，sessionId 为真实值）
            s.write(encodeControlRecord({ kind: 'stopSession', sessionId: msg.sessionId }));
            // 留出 STOP 往返窗口再断开：先 destroy 会让 DISCONNECTED 抢在 STOP 前到，
            // 走断开挂机路径遗留挂机会话
            setTimeout(() => finish(true), 500);
          } else {
            finish(false);
          }
        } catch {
          /* 非控制记录忽略 */
        }
      }
    });
  });
}

/** 确保源端可接受新会话（探针 ACCEPTED）。
 * 接受失败 → 全量复位路径：force-stop（清僵尸投影/挂机会话/VD）→ FGS → MainActivity
 * → 手动路径授予（只存凭据不消费，区别于 captureLost 自动重授权路径的立即消费）
 * → 系统框确认 → 探针消费新鲜凭据建管线。 */
export async function ensureSourcePipeline(): Promise<void> {
  await adb('forward', `tcp:${HOST_PORT}`, `tcp:${HOST_PORT}`);
  if (await probeSessionAccepted()) return;

  await adb('shell', 'am', 'force-stop', 'cloudphone.source');
  await sleep(2000);
  await adb('logcat', '-c'); // 复位起点清日志，「shared capture ready」只认本轮重建
  await adb('shell', 'am', 'start-foreground-service', '-n', 'cloudphone.source/.StreamService');
  await sleep(2000);
  await adb('shell', 'svc', 'power', 'stayon', 'true');
  await adb('shell', 'am', 'start', '-n', 'cloudphone.source/.MainActivity');
  await sleep(3000);
  for (let i = 0; i < 4; i++) {
    if (await tapText(APP_GRANT_PATTERN, { unfreeze: true })) break;
    await sleep(1500);
  }
  for (let i = 0; i < 4; i++) {
    if (await tapText(START_NOW_PATTERN, { unfreeze: true })) break;
    await sleep(1500);
  }
  // 授权 tap 的实际生效受系统冻结进程调度延迟（实测 ~13s），必须等管线重建完成的
  // 日志信号再探，否则探针抢跑于对话框确认 → 拒绝
  await waitForLogMarker('shared capture ready', 45_000);
  for (let i = 0; i < 3; i++) {
    if (await probeSessionAccepted()) return;
    await sleep(2500);
  }
  throw new Error('源端无法接受会话（复位+授予+管线就绪后 START_SESSION 探针仍未被接受）');
}
