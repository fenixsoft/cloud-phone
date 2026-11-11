/**
 * e2e 共享 uiautomator 自动化（scripts/bootstrap_adb.sh center_of_text 的 TS 版，单一实现）：
 * uiautomator dump → 正则取节点 bounds → input tap。
 * - 正则以 `<node` 开头锚定，防匹配到非节点文本属性；
 * - dump 文件路径可参数化（缺省统一 /sdcard/e2e_dump.xml，调用方不再各用不同文件名）；
 * - 解冻 tap（可选，unfreeze: true）：权限框所在进程（permissioncontroller/systemui）被
 *   cached-app freezing 冻结时，首次 tap 仅用于解冻、不派发到控件——先点一次屏内中性区
 *   再点目标。中性点按 `adb shell wm size` 实测分辨率推导（宽 40%/高 50%），wm size 不可用
 *   时回退 dump 根节点 bounds 推导；推导失败则跳过解冻（绝不点猜测坐标，小屏越界会被
 *   input tap 静默丢弃）；
 * - 授权按钮模式集对齐 scripts/bootstrap_adb.sh grant_projection（含 Share screen / Cast）。
 * grant.ts 与 global-setup.ts 共用本模块，tapText 不得再各自复制演化。
 */

import { spawn } from 'node:child_process';

const SERIAL = process.env.E2E_SOURCE_SERIAL ?? 'emulator-5554';

/** 应用内「授予」按钮模式（bootstrap_adb.sh grant_projection 同款） */
export const APP_GRANT_PATTERN = '授予';
/** 系统授权确认弹窗模式（bootstrap_adb.sh 同款：中文/英文 + Pixel「Share screen」/「Cast」） */
export const START_NOW_PATTERN = '立即开始|开始采集|Start [Nn]ow|Share screen|Cast';

export interface TapTextOptions {
  /** uiautomator dump 在设备上的落盘路径（缺省 /sdcard/e2e_dump.xml） */
  dumpFile?: string;
  /** 目标 tap 前先点一次屏内中性区解冻（cached-app freezing 场景需要） */
  unfreeze?: boolean;
}

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

/** 屏内中性点：wm size 实测分辨率（Override 优先于 Physical，取最后一次出现）的
 * 宽 40%/高 50%；wm size 不可用时回退 dump 根节点 bounds 推导；均失败返回 null。 */
async function neutralPoint(xml: string): Promise<{ x: number; y: number } | null> {
  try {
    const out = await adb('shell', 'wm', 'size');
    const sizes = [...out.matchAll(/(?:Override|Physical) size:\s*(\d+)x(\d+)/g)];
    const last = sizes[sizes.length - 1];
    if (last) {
      return { x: Math.round(Number(last[1]) * 0.4), y: Math.round(Number(last[2]) * 0.5) };
    }
  } catch {
    /* wm size 失败 → 回退 dump 根节点 bounds */
  }
  const root = /<node[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(xml);
  if (root) {
    return {
      x: Math.round(Number(root[1]) + (Number(root[3]) - Number(root[1])) * 0.4),
      y: Math.round(Number(root[2]) + (Number(root[4]) - Number(root[2])) * 0.5),
    };
  }
  return null;
}

/** uiautomator dump 中匹配文本节点的点击中心（bootstrap 同法），命中后 tap 其中心。 */
export async function tapText(pattern: string, opts: TapTextOptions = {}): Promise<boolean> {
  const dumpFile = opts.dumpFile ?? '/sdcard/e2e_dump.xml';
  try {
    await adb('shell', 'uiautomator', 'dump', dumpFile);
    const xml = await adb('shell', 'cat', dumpFile);
    const re = /<node[^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(xml)) !== null) {
      if (!new RegExp(pattern).test(m[1])) continue;
      const x = (Number(m[2]) + Number(m[4])) / 2;
      const y = (Number(m[3]) + Number(m[5])) / 2;
      if (opts.unfreeze) {
        const point = await neutralPoint(xml);
        if (point) {
          await adb('shell', 'input', 'tap', String(point.x), String(point.y));
          await sleep(900);
        }
      }
      // 单次目标 tap：确认后对话框立即关闭，二次 tap 会落到新界面上（调用方语义）
      await adb('shell', 'input', 'tap', String(x), String(y));
      return true;
    }
  } catch {
    /* dump 失败按未命中处理 */
  }
  return false;
}
