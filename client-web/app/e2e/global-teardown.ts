/**
 * e2e 全局拆卸：停网关进程（PID 由 setup 落盘交换）。
 */

import { readFileSync, unlinkSync } from 'node:fs';
import { execSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PID_FILE = path.join(HERE, '.gateway.pid');

export default function globalTeardown(): void {
  try {
    const pid = Number(readFileSync(PID_FILE, 'utf-8').trim());
    if (pid > 0) execSync(`kill ${pid} 2>/dev/null || true`, { stdio: 'ignore' });
    unlinkSync(PID_FILE);
  } catch {
    /* 无残留 */
  }
}
