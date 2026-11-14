/**
 * e2e adb 助手单处实现（grant.ts / global-setup.ts 共用，此前双份实现已漂移：
 * 错误文案一处带退出码一处不带）。职责：
 * - 统一 `-s <serial>` 目标注入与非零退出的错误文案（含 exit code 与合并输出）；
 * - 超时兜底：adb 对僵死设备（USB 抖动 / emulator 卡死）可能无限期不返回，无超时
 *   会令 Playwright globalSetup 整体挂死且拿不到任何诊断点。超时 kill 子进程并
 *   reject 带上下文错误，让 setup 失败快速可见（口径对齐 scripts/measure_op_latency.py
 *   对 adb 一律 timeout=30）。
 */

import { spawn } from 'node:child_process';

/** adb 单次调用超时（毫秒）：E2E_ADB_TIMEOUT 覆盖，缺省 30s。导出供内联 adb 调用
 *  （如 global-setup 的 devices 探测）复用同一档位，保持整条 adb 链路单一超时口径 */
export const ADB_TIMEOUT_MS = Number(process.env.E2E_ADB_TIMEOUT) || 30_000;

/** 源端目标与端口约定单处声明（env 名 + 默认值唯此一处）：global-setup.ts / grant.ts
 *  一律 import 本常量，杜绝三处重复声明各自漂移（adb 注入目标与转发/探测端口各说各话） */
export const SOURCE_SERIAL = process.env.E2E_SOURCE_SERIAL ?? 'emulator-5554';
export const HOST_PORT = Number(process.env.E2E_SOURCE_PORT ?? 22333);

export function adb(...args: string[]): Promise<string> {
  return new Promise((resolve, reject) => {
    const p = spawn('adb', ['-s', SOURCE_SERIAL, ...args], { stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      p.kill('SIGKILL');
      reject(new Error(`adb ${args.join(' ')} 超时（${ADB_TIMEOUT_MS}ms，E2E_ADB_TIMEOUT 可调）→ ${out}`));
    }, ADB_TIMEOUT_MS);
    const settle = (fn: () => void) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      fn();
    };
    p.stdout.on('data', (d: Buffer) => (out += d.toString()));
    p.stderr.on('data', (d: Buffer) => (out += d.toString()));
    p.on('close', (code) => settle(() => (code === 0 ? resolve(out) : reject(new Error(`adb ${args.join(' ')} → exit ${code}: ${out}`)))));
    p.on('error', (err) => settle(() => reject(err)));
  });
}

export const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));
