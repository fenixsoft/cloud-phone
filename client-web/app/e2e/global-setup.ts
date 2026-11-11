/**
 * e2e 全局装配（任务 7.1）：
 * 1. 源端环境（bootstrap 口径，scripts/bootstrap_adb.sh 的最小子集）：APK 安装 →
 *    adb forward → StreamService 前台服务 → MediaProjection 授权（uiautomator 自动点击）；
 * 2. 网关：node server.js（GATEWAY_PORT=8090 / UPSTREAM=127.0.0.1:22333 /
 *    STATIC_DIR=../app/dist），等端口就绪后交 Playwright。
 */

import { spawn, type ChildProcess } from 'node:child_process';
import { existsSync, statSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PID_FILE = path.join(HERE, '.gateway.pid');
const APP_DIR = path.resolve(HERE, '..');
// worktree 根 = 仓库根（client-web/app 上两级）；主仓 APK 作为构建缺失时的回退
const REPO_ROOT = path.resolve(APP_DIR, '..', '..');
const MAIN_REPO = '/root/cloud-phone';
const GATEWAY_DIR = path.resolve(APP_DIR, '..', 'gateway');
const SOURCE_SERIAL = process.env.E2E_SOURCE_SERIAL ?? 'emulator-5554';
const HOST_PORT = Number(process.env.E2E_SOURCE_PORT ?? 22333);
const GATEWAY_PORT = Number(process.env.E2E_GATEWAY_PORT ?? 8090);
const APK = [
  path.join(REPO_ROOT, 'source', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'),
  path.join(MAIN_REPO, 'source', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'),
].find((p) => existsSync(p)) ?? path.join(REPO_ROOT, 'source', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
const SOURCE_PROJECT = existsSync(path.join(REPO_ROOT, 'source', 'gradlew')) ? REPO_ROOT : MAIN_REPO;

let gateway: ChildProcess | null = null;

export const E2E_STATE = { token: process.env.E2E_TOKEN ?? 'cloudphone-m0' };

function adb(...args: string[]): Promise<string> {
  return new Promise((resolve, reject) => {
    const p = spawn('adb', ['-s', SOURCE_SERIAL, ...args], { stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    p.stdout.on('data', (d: Buffer) => (out += d.toString()));
    p.stderr.on('data', (d: Buffer) => (out += d.toString()));
    p.on('close', (code) => (code === 0 ? resolve(out) : reject(new Error(`adb ${args.join(' ')} → exit ${code}: ${out}`))));
    p.on('error', reject);
  });
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

/** uiautomator dump 中匹配文本的点击中心（bootstrap 同法） */
async function tapText(pattern: string): Promise<boolean> {
  try {
    await adb('shell', 'uiautomator', 'dump', '/sdcard/e2e_dump.xml');
    const xml = await adb('shell', 'cat', '/sdcard/e2e_dump.xml');
    const re = /<node[^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(xml)) !== null) {
      if (!new RegExp(pattern).test(m[1])) continue;
      const x = (Number(m[2]) + Number(m[4])) / 2;
      const y = (Number(m[3]) + Number(m[5])) / 2;
      await adb('shell', 'input', 'tap', String(x), String(y));
      return true;
    }
  } catch {
    /* dump 失败按未命中处理 */
  }
  return false;
}

async function waitForGateway(): Promise<void> {
  for (let i = 0; i < 50; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${GATEWAY_PORT}/`);
      if (res.ok) return;
    } catch {
      /* 未就绪 */
    }
    await sleep(200);
  }
  throw new Error('网关 80s 内未就绪');
}

export default async function globalSetup(): Promise<void> {
  // ── 源端环境 ──
  const devices = await new Promise<string>((resolve, reject) => {
    const p = spawn('adb', ['devices'], { stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    p.stdout.on('data', (d: Buffer) => (out += d.toString()));
    p.on('close', () => resolve(out));
    p.on('error', reject);
  });
  if (!devices.includes(SOURCE_SERIAL)) {
    throw new Error(`源端模拟器 ${SOURCE_SERIAL} 未连接（adb devices）——e2e 需要 M0 bootstrap 环境`);
  }
  await adb('wait-for-device');

  // APK 安装：**仅在缺失或 APK 比已装版本新时**执行——`adb install -r` 会杀掉源端
  // 进程并作废其 MediaProjection 授权（授权令牌一次性且绑定进程），重复安装会让
  // 后续 grant 全部落空（REJECT_UNSUPPORTED_MODE 反复出现即此因）。
  const installed = await adb('shell', 'pm', 'path', 'cloudphone.source').catch(() => '');
  let needInstall = !installed.includes('package:');
  if (!needInstall) {
    const lastUpdate = await adb('shell', 'dumpsys', 'package', 'cloudphone.source').catch(() => '');
    const m = /lastUpdateTime=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})/.exec(lastUpdate);
    if (m) needInstall = existsSync(APK) && statSync(APK).mtimeMs > Date.parse(m[1].replace(' ', 'T')) + 60_000;
  }
  if (needInstall) {
    if (!existsSync(APK)) {
      await new Promise<void>((resolve, reject) => {
        const build = spawn('./gradlew', ['assembleDebug', '-q'], { cwd: path.join(SOURCE_PROJECT, 'source'), stdio: 'inherit' });
        build.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`源端 APK 构建失败 exit ${code}`))));
        build.on('error', reject);
      });
    }
    await adb('install', '-r', '-t', APK);
    await sleep(1000);
  }

  // 屏幕常亮（熄屏断编码粮）
  await adb('shell', 'svc', 'power', 'stayon', 'true');
  // 输入窗口合成驱动登记（触摸延迟，降级无害）
  await adb('shell', 'appops', 'set', 'cloudphone.source', 'SYSTEM_ALERT_WINDOW', 'allow').catch(() => undefined);
  // 端口转发：host 22333 → 源端 22333
  await adb('forward', '--remove', `tcp:${HOST_PORT}`).catch(() => undefined);
  await adb('forward', `tcp:${HOST_PORT}`, `tcp:${HOST_PORT}`);

  // 起服务（先 FGS 后授权，Android 14 时序）+ MediaProjection 授权自动点击
  await adb('shell', 'am', 'start-foreground-service', '-n', 'cloudphone.source/.StreamService');
  await sleep(1500);
  await adb('shell', 'am', 'start', '-n', 'cloudphone.source/.MainActivity').catch(() => undefined);
  await sleep(1500);
  for (const pattern of ['授予|Grant', '立即开始|开始采集|Start [Nn]ow|Start recording']) {
    for (let i = 0; i < 5; i++) {
      if (await tapText(pattern)) {
        await sleep(2000);
        break;
      }
      await sleep(1500);
    }
  }

  // ── 网关 ──
  gateway = spawn('node', ['server.js'], {
    cwd: GATEWAY_DIR,
    env: {
      ...process.env,
      GATEWAY_PORT: String(GATEWAY_PORT),
      UPSTREAM: `127.0.0.1:${HOST_PORT}`,
      STATIC_DIR: path.join(APP_DIR, 'dist'),
    },
    stdio: ['ignore', 'inherit', 'inherit'],
  });
  await waitForGateway();
  writeFileSync(PID_FILE, String(gateway.pid ?? 0));
  console.log(`[e2e] 环境就绪：网关 :${GATEWAY_PORT} → 源端 :${HOST_PORT}（${SOURCE_SERIAL}）`);
}

/** teardown 通过环境变量把 gateway 句柄传给 teardown 文件（同进程模型限制下用 PID） */
export function gatewayPid(): number | undefined {
  return gateway?.pid;
}
