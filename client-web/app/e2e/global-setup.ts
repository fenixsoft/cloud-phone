/**
 * e2e 全局装配（任务 7.1）：
 * 1. 源端环境（bootstrap 口径，scripts/bootstrap_adb.sh 的最小子集）：APK 安装 →
 *    adb forward → StreamService 前台服务 → MediaProjection 授权（uiautomator 自动点击）；
 * 2. 网关：node server.js（GATEWAY_PORT=8090 / UPSTREAM=127.0.0.1:22333 /
 *    STATIC_DIR=../app/dist），等端口就绪后交 Playwright。
 */

import { execSync, spawn, type ChildProcess } from 'node:child_process';
import { existsSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { APP_GRANT_PATTERN, START_NOW_PATTERN, tapText } from './uiautomator';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PID_FILE = path.join(HERE, '.gateway.pid');
const APP_DIR = path.resolve(HERE, '..');
// worktree 根 = 仓库根（client-web/app 上两级）；主仓 APK 作为构建缺失时的回退
const REPO_ROOT = path.resolve(APP_DIR, '..', '..');
// 主仓根：E2E_MAIN_REPO 显式覆盖 → git 公共目录推导（worktree 检出即主仓根）→ git 不可用回退本检出根
const MAIN_REPO = process.env.E2E_MAIN_REPO ?? mainRepoFromGit(REPO_ROOT);
const GATEWAY_DIR = path.resolve(APP_DIR, '..', 'gateway');
const SOURCE_SERIAL = process.env.E2E_SOURCE_SERIAL ?? 'emulator-5554';
const HOST_PORT = Number(process.env.E2E_SOURCE_PORT ?? 22333);
const GATEWAY_PORT = Number(process.env.E2E_GATEWAY_PORT ?? 8090);
// 构建仓库：本检出内有 source/gradlew 用本检出，缺失（特殊 worktree/稀疏检出）回退主仓
const SOURCE_PROJECT = existsSync(path.join(REPO_ROOT, 'source', 'gradlew')) ? REPO_ROOT : MAIN_REPO;
// APK 路径与构建仓库绑定：已存在产物优先（worktree → 主仓），都不存在时指向 SOURCE_PROJECT
// 的构建输出路径——「构建发生地」与「安装路径」一致，adb install 不会指向不存在的文件
function resolveApk(): string {
  return [
    path.join(REPO_ROOT, 'source', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'),
    path.join(MAIN_REPO, 'source', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'),
  ].find((p) => existsSync(p)) ?? path.join(SOURCE_PROJECT, 'source', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
}
let APK = resolveApk();
// 设备侧 stamp：安装成功后写入的宿主 APK 指纹（size:mtimeMs），下次运行回读比对决定是否重装
const APK_STAMP_PATH = '/sdcard/.e2e_apk_stamp';
// 网关就绪预算：探测次数 × 间隔；报错文案由同一常量生成，行为与文案一致
const GATEWAY_PROBE_COUNT = 50;
const GATEWAY_PROBE_INTERVAL_MS = 200;
const GATEWAY_READY_TIMEOUT_MS = GATEWAY_PROBE_COUNT * GATEWAY_PROBE_INTERVAL_MS;

let gateway: ChildProcess | null = null;
// 装配侧网关清理责任标记：spawn 后置 true，setup 成功返回即移交 teardown（置 false）。
// SIGINT/SIGTERM/exit 兜底仅在标记为 true（装配未完成、teardown 尚未接管）时生效，
// 正常路径仍由 teardown 按 PID 文件杀网关并回收，避免重复 kill 噪音。
let gatewayOwnedBySetup = false;

/** e2e token 覆盖通道：E2E_STATE.token = process.env.E2E_TOKEN 回退默认 cloudphone-m0，
 *  消费方为 e2e 探针与冒烟用例（grant.ts / smoke.spec.ts）。网关进程不经此模块传递——
 *  teardown 经 .gateway.pid 文件交换 PID（globalTeardown 读 PID 清理）。 */
export const E2E_STATE = { token: process.env.E2E_TOKEN ?? 'cloudphone-m0' };

/** 由 git 公共目录推导主仓根：worktree 检出返回主仓根，主仓检出返回本仓根 */
function mainRepoFromGit(from: string): string {
  try {
    const commonDir = execSync('git rev-parse --git-common-dir', {
      cwd: from,
      stdio: ['ignore', 'pipe', 'ignore'],
    })
      .toString()
      .trim();
    return path.dirname(path.resolve(from, commonDir));
  } catch {
    return from;
  }
}

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

/** 宿主侧 APK 指纹（size:mtimeMs，epoch 值与时区无关）——设备侧 stamp 的写入/比对基准 */
function apkFingerprint(): string | null {
  if (!existsSync(APK)) return null;
  const st = statSync(APK);
  return `${st.size}:${st.mtimeMs}`;
}

async function waitForGateway(): Promise<void> {
  for (let i = 0; i < GATEWAY_PROBE_COUNT; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${GATEWAY_PORT}/`);
      if (res.ok) return;
    } catch {
      /* 未就绪 */
    }
    await sleep(GATEWAY_PROBE_INTERVAL_MS);
  }
  throw new Error(`网关 ${GATEWAY_READY_TIMEOUT_MS / 1000}s 内未就绪`);
}

/** 异常退出兜底（幂等）：仅当清理责任仍在装配侧时杀网关并回收 PID 文件，返回是否
 *  实际执行了清理。装配中途被杀若无兜底，网关会成为无 PID 记录的孤儿占住 8090，
 *  后续运行 waitForGateway 探到旧网关「假就绪」，新网关因端口占用退出。 */
function cleanupGatewayBestEffort(): boolean {
  if (!gatewayOwnedBySetup) return false;
  gatewayOwnedBySetup = false;
  try {
    gateway?.kill();
  } catch {
    /* 网关已退出 */
  }
  try {
    unlinkSync(PID_FILE);
  } catch {
    /* PID 文件未落盘或已被回收 */
  }
  return true;
}

/** 装配期进程级兜底：SIGINT/SIGTERM/exit 时杀网关并回收 PID 文件，SIGINT/SIGTERM
 *  按信号惯例退出码终止（130/143）。装配成功返回后 gatewayOwnedBySetup=false，
 *  三者均 no-op——正常路径由 globalTeardown 按 PID 文件清理，信号处理交还 Playwright
 *  自身的优雅退出流程。 */
function installGatewayExitGuards(): void {
  process.on('SIGINT', () => {
    if (cleanupGatewayBestEffort()) process.exit(130);
  });
  process.on('SIGTERM', () => {
    if (cleanupGatewayBestEffort()) process.exit(143);
  });
  process.on('exit', () => cleanupGatewayBestEffort());
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

  // APK 安装：**仅在缺失或宿主指纹与设备侧 stamp 不一致时**执行——`adb install -r` 会杀掉源端
  // 进程并作废其 MediaProjection 授权（授权令牌一次性且绑定进程），重复安装会让
  // 后续 grant 全部落空（REJECT_UNSUPPORTED_MODE 反复出现即此因）。新旧判定不用
  // dumpsys lastUpdateTime（设备本地时间无时区，宿主 Date.parse 按时区解释，时区差/
  // 时钟漂移会误触发重装），改比对安装成功后写入设备侧的宿主 APK 指纹；stamp 缺失
  // （首次运行/被清理）按重装处理以重建基准。
  const installed = await adb('shell', 'pm', 'path', 'cloudphone.source').catch(() => '');
  let needInstall = !installed.includes('package:');
  if (!needInstall) {
    const stamp = await adb('shell', 'cat', APK_STAMP_PATH).catch(() => '');
    const fp = apkFingerprint();
    needInstall = fp === null || stamp.trim() !== fp;
  }
  if (needInstall) {
    if (!existsSync(APK)) {
      await new Promise<void>((resolve, reject) => {
        const build = spawn('./gradlew', ['assembleDebug', '-q'], { cwd: path.join(SOURCE_PROJECT, 'source'), stdio: 'inherit' });
        build.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`源端 APK 构建失败 exit ${code}`))));
        build.on('error', reject);
      });
      APK = resolveApk(); // 构建后按实际产物重解析，确保安装路径 = 构建输出路径
    }
    await adb('install', '-r', '-t', APK);
    const fp = apkFingerprint();
    if (fp !== null) {
      await adb('shell', 'echo', fp, '>', APK_STAMP_PATH)
        .catch(() => console.warn('[e2e] APK 指纹 stamp 写入失败，下次运行将按缺失重装'));
    }
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
  // 授权按钮模式集与 grant.ts 共用 e2e/uiautomator.ts 单一实现（对齐 bootstrap_adb.sh）
  for (const pattern of [APP_GRANT_PATTERN, START_NOW_PATTERN]) {
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
  gatewayOwnedBySetup = true;
  installGatewayExitGuards();
  try {
    // spawn 后立即落盘 PID（不等就绪）：装配中途被 SIGINT/崩溃终止时 teardown 与
    // 信号兜底仍能按 PID 找到网关，避免其在 8090 成为无 PID 记录的孤儿
    writeFileSync(PID_FILE, String(gateway.pid ?? 0));
    await waitForGateway();
  } catch (err) {
    // 就绪等待/PID 落盘失败：先杀掉已 spawn 的网关并回收 PID 文件再重抛——否则孤儿
    // 进程占住端口，下次运行 waitForGateway 会探到旧网关而「假就绪」
    cleanupGatewayBestEffort();
    throw err;
  }
  // setup 成功：清理责任移交 globalTeardown（读 PID 文件杀并回收），信号兜底转 no-op
  gatewayOwnedBySetup = false;
  console.log(`[e2e] 环境就绪：网关 :${GATEWAY_PORT} → 源端 :${HOST_PORT}（${SOURCE_SERIAL}）`);
}
