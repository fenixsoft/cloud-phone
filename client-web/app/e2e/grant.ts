/**
 * 源端共享采集管线就绪保障（e2e 专用，镜像约束适配）：
 * - 本镜像一授权只能铸一个 MediaProjection 实例（闲置 ~60s 失效）；
 * - 「授权后立即创建共享采集管线（一授权一 VD；会话 attach 不再触碰投影令牌）」
 *   （StreamService.ensureSharedCapture）→ 管线常驻后所有会话零令牌消耗挂接；
 * - 投影丢失后 MainActivity.onResume 自动弹重授权框（captureLost 路径），确认即重建。
 * ⇒ 判据：真实 HELLO + START_SESSION 探针拿到 ACCEPTED（logcat 启发式不可靠——
 *   「ready 后紧接 lost」的抖动与 dumpsys 存活投影并存）。拒绝则走 UI 自动确认重建。
 */

import net from 'node:net';
import { spawn } from 'node:child_process';
import { encodeControlRecord, decodeControlMessage } from '../src/protocol/Messages';
import { RECORD_HEADER_BYTES, RECORD_CONTROL } from '../src/protocol/Framing';

const SERIAL = process.env.E2E_SOURCE_SERIAL ?? 'emulator-5554';
const HOST_PORT = Number(process.env.E2E_SOURCE_PORT ?? 22333);

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

/** 真实探针：HELLO(占位参数) → START_SESSION(FULLSCREEN 默认参) → ACCEPTED 即
 * STOP_SESSION 清理（探针会话不留挂机），返回是否被接受。 */
function probeSessionAccepted(): Promise<boolean> {
  return new Promise((resolve) => {
    const hello = encodeControlRecord({ kind: 'hello', version: 1, token: 'cloudphone-m0', mode: 0, packageName: '', width: 0, height: 0, fps: 30, bitrateKbps: 8000, sessionId: 0, p2pOffer: '' });
    const start = encodeControlRecord({ kind: 'startSession', sessionId: 0, mode: 0, packageName: '', width: 0, height: 0, fps: 30, bitrateKbps: 8000 });
    const s = net.connect(HOST_PORT, '127.0.0.1', () => {
      s.write(hello);
      setTimeout(() => s.write(start), 300);
    });
    let buf = Buffer.alloc(0);
    let decided = false;
    const stop = (sessionId: number): void => {
      s.write(encodeControlRecord({ kind: 'stopSession', sessionId }));
      setTimeout(() => { decided = true; s.destroy(); resolve(true); }, 400);
    };
    const finish = (ok: boolean) => {
      if (decided) return;
      decided = true;
      s.destroy();
      resolve(ok);
    };
    const timer = setTimeout(() => finish(false), 12_000);
    s.on('error', () => { clearTimeout(timer); finish(false); });
    s.on('data', (d: Buffer) => {
      buf = Buffer.concat([buf, d]);
      while (buf.length >= RECORD_HEADER_BYTES) {
        const len = buf.readUInt32BE(1);
        if (buf.length < RECORD_HEADER_BYTES + len) break;
        const record = buf.subarray(RECORD_HEADER_BYTES, RECORD_HEADER_BYTES + len);
        buf = buf.subarray(RECORD_HEADER_BYTES + len);
        if (record[0] !== RECORD_CONTROL) continue;
        try {
          const msg = decodeControlMessage(record.subarray(1));
          if (msg.kind === 'helloAck' && msg.status === 0) {
            clearTimeout(timer);
            stop(msg.sessionId);
          } else if (msg.kind === 'helloAck') {
            clearTimeout(timer);
            finish(false);
          }
        } catch {
          /* 非控制记录忽略 */
        }
      }
    });
  });
}

/** uiautomator dump 中匹配文本的点击中心（bootstrap 同法）。
 * 权限框所在进程（permissioncontroller/systemui）会被系统冻结（cached-app freezing），
 * 冻结后的第一次 tap 仅用于解冻、不派发到控件 —— 故先点一次中性区唤醒再点目标。 */
async function tapText(pattern: string): Promise<boolean> {
  try {
    await adb('shell', 'uiautomator', 'dump', '/sdcard/e2e_grant.xml');
    const xml = await adb('shell', 'cat', '/sdcard/e2e_grant.xml');
    const re = /text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(xml)) !== null) {
      if (!new RegExp(pattern).test(m[1])) continue;
      const x = (Number(m[2]) + Number(m[4])) / 2;
      const y = (Number(m[3]) + Number(m[5])) / 2;
      await adb('shell', 'input', 'tap', '540', '800'); // 解冻 tap（中性区）
      await sleep(900);
      // 单次目标 tap：确认后权限框立即关闭，二次 tap 会落到重建完成的 App 界面
      // 上（误触停止按钮 → 管线秒停「shared capture lost」）
      await adb('shell', 'input', 'tap', String(x), String(y));
      return true;
    }
  } catch {
    /* dump 失败按未命中 */
  }
  return false;
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
    if (await tapText('授予 MediaProjection|授予')) break;
    await sleep(1500);
  }
  for (let i = 0; i < 4; i++) {
    if (await tapText('立即开始|开始采集|Start [Nn]ow|Start recording')) break;
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
