/**
 * e2e adb 助手单处实现（grant.ts / global-setup.ts 共用，此前双份实现已漂移：
 * 错误文案一处带退出码一处不带）。职责：
 * - `-s <serial>` 目标注入（adb）与无目标全局命令（adbRaw）两入口共用同一套
 *   spawn/超时/settle 骨架，统一非零退出的错误文案（含 exit code 与合并输出）；
 * - 超时兜底：adb 对僵死设备（USB 抖动 / emulator 卡死）可能无限期不返回，无超时
 *   会令 Playwright globalSetup 整体挂死且拿不到任何诊断点。超时 kill 子进程并
 *   reject 带上下文错误，让 setup 失败快速可见（口径对齐 scripts/measure_op_latency.py
 *   对 adb 一律 timeout=30）；
 * - 输出累计上限：`logcat -d` 这类调用输出可达数十 MB，超限截断防 O(n²) 拼接与全量驻留。
 */

import { spawn } from 'node:child_process';

/** adb 单次调用超时（毫秒）：E2E_ADB_TIMEOUT 覆盖，缺省 30s。导出供调用方/错误文案
 *  复用同一档位（含 global-setup 的 devices 探测），保持整条 adb 链路单一超时口径 */
export const ADB_TIMEOUT_MS = Number(process.env.E2E_ADB_TIMEOUT) || 30_000;

/** 源端目标与端口约定单处声明（env 名 + 默认值唯此一处）：global-setup.ts / grant.ts
 *  一律 import 本常量，杜绝三处重复声明各自漂移（adb 注入目标与转发/探测端口各说各话） */
export const SOURCE_SERIAL = process.env.E2E_SOURCE_SERIAL ?? 'emulator-5554';
// E2E_SOURCE_PORT 与 ADB_TIMEOUT_MS 同口径兜底：非数字（Number() 得 NaN）或非正数
// 一律回退默认 22333，避免 `tcp:NaN` 静默注入 adb forward / 探针连接且报错无从定位 env 配错
const hostPort = Number(process.env.E2E_SOURCE_PORT ?? 22333);
export const HOST_PORT = Number.isFinite(hostPort) && hostPort > 0 ? hostPort : 22333;

/** 单次调用输出累计上限（1MB 量级）：`logcat -d`（grant.ts waitForLogMarker 每轮轮询）
 *  输出可达数十 MB，逐 chunk 无上限累计既 O(n²) 拼接又全量驻留；当前消费方（devices
 *  状态匹配 / 日志标记 grep / 错误文案）1MB 足够，超限停止拼接并标记 [truncated]，
 *  仍等进程退出——超时与错误文案照常可用 */
const ADB_OUTPUT_LIMIT_CHARS = 1024 * 1024;

/** spawn/超时/settle 单一骨架：adb（注入 -s）与 adbRaw（全局命令）共用，杜绝双份
 *  实现再度漂移 */
function runAdb(args: string[]): Promise<string> {
  return new Promise((resolve, reject) => {
    const p = spawn('adb', args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    let truncated = false;
    const append = (d: Buffer) => {
      if (truncated) return;
      if (out.length >= ADB_OUTPUT_LIMIT_CHARS) {
        truncated = true;
        out += '\n[truncated]';
        return;
      }
      out += d.toString();
    };
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
    p.stdout.on('data', append);
    p.stderr.on('data', append);
    p.on('close', (code) => settle(() => (code === 0 ? resolve(out) : reject(new Error(`adb ${args.join(' ')} → exit ${code}: ${out}`)))));
    p.on('error', (err) => settle(() => reject(err)));
  });
}

/** 设备目标命令：自动注入 `-s <SOURCE_SERIAL>` */
export function adb(...args: string[]): Promise<string> {
  return runAdb(['-s', SOURCE_SERIAL, ...args]);
}

/** 无目标全局命令（devices 等不绑定 serial 的指令）：不注入 `-s`，global-setup 的
 *  devices 探测经此复用同一套 spawn/超时/settle，替代曾内联复制的第二份实现 */
export function adbRaw(...args: string[]): Promise<string> {
  return runAdb(args);
}

export const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));
