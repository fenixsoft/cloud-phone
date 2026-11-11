/**
 * e2e 全局拆卸：停网关进程（PID 由 setup 落盘交换，teardown 读 PID 文件清理）。
 *
 * 副作用边界（有意跨 run 保留，本 teardown 不复位）：global-setup 设置的 svc power
 * stayon true、adb forward tcp:22333、源端前台服务与已授予的 MediaProjection 授权均
 * 留给下一次运行/共享该模拟器的任务复用——授权令牌一次性且绑定源端进程，复位只会逼出
 * 更贵的重装重授权链路；本 teardown 只负责停掉网关进程并回收 PID 文件。
 */

import { readFileSync, unlinkSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PID_FILE = path.join(HERE, '.gateway.pid');
const TERM_GRACE_MS = 2000;
const POLL_INTERVAL_MS = 100;

/** 读 /proc/<pid>/cmdline 校验目标身份（node + server.js），防 PID 被系统复用后误杀无关进程 */
function isGatewayProcess(pid: number): boolean {
  try {
    const argv = readFileSync(`/proc/${pid}/cmdline`, 'utf-8')
      .split('\0')
      .filter(Boolean);
    return argv.some((a) => a === 'node' || a.endsWith('/node')) && argv.some((a) => a.endsWith('server.js'));
  } catch {
    return false; // 进程已退出或无 /proc（非 Linux）→ 不 kill
  }
}

const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));

export default async function globalTeardown(): Promise<void> {
  let pid = 0;
  try {
    pid = Number(readFileSync(PID_FILE, 'utf-8').trim());
  } catch {
    return; /* 无残留 PID 文件 */
  }
  try {
    if (pid > 0 && isGatewayProcess(pid)) {
      try {
        process.kill(pid, 'SIGTERM');
      } catch {
        /* 已退出 */
      }
      // 轮询等待退出（TERM_GRACE_MS 宽限）；SIGTERM 被忽略/未及时退出时 SIGKILL 降级，
      // 确保 8090 端口在下一次运行前及时释放
      let alive = true;
      const deadline = Date.now() + TERM_GRACE_MS;
      while (alive && Date.now() < deadline) {
        await sleep(POLL_INTERVAL_MS);
        try {
          process.kill(pid, 0);
        } catch {
          alive = false;
        }
      }
      if (alive) {
        try {
          process.kill(pid, 'SIGKILL');
        } catch {
          /* 已退出 */
        }
      }
    } else if (pid > 0) {
      console.warn(`[e2e] PID ${pid} 身份校验未通过（非 node server.js），跳过 kill 防 PID 复用误杀`);
    }
  } finally {
    try {
      unlinkSync(PID_FILE);
    } catch {
      /* 已不存在 */
    }
  }
}
