/**
 * Playwright 配置（任务 7.1 冒烟）：
 * - 不使用 webServer：网关由 globalSetup（e2e/global-setup.ts）spawn 启动
 *   （静态托管 dist + /ws 反代源端），globalTeardown 按 PID 文件停止；
 * - 无 Playwright 浏览器缓存时先 `npx playwright install chromium`（CI/本机一次性）。
 */

import { existsSync, readdirSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { defineConfig } from '@playwright/test';

// 网关端口与 e2e/global-setup.ts 同一来源：E2E_GATEWAY_PORT 覆盖，默认 8090
const GATEWAY_PORT = Number(process.env.E2E_GATEWAY_PORT ?? 8090);

/** chromium 二进制：E2E_CHROMIUM_BIN 显式覆盖 → 本机 playwright 缓存按 revision 降序探测
 *  （不绑定具体 revision，新版布局 chrome-linux64/chrome、旧版 chrome-linux/chrome）；
 *  无显式覆盖且缓存无可用二进制时返回空串，由下方预检统一报错。 */
function resolveChromiumBin(): string {
  const explicit = process.env.E2E_CHROMIUM_BIN;
  if (explicit) return explicit;
  const cacheRoot = process.env.PLAYWRIGHT_BROWSERS_PATH || path.join(os.homedir(), '.cache', 'ms-playwright');
  let revisions: string[];
  try {
    revisions = readdirSync(cacheRoot)
      .filter((d) => /^chromium-\d+$/.test(d))
      .sort((a, b) => Number(b.slice('chromium-'.length)) - Number(a.slice('chromium-'.length)));
  } catch {
    return ''; // 缓存目录不存在/不可读
  }
  for (const rev of revisions) {
    for (const rel of ['chrome-linux64/chrome', 'chrome-linux/chrome']) {
      const bin = path.join(cacheRoot, rev, rel);
      if (existsSync(bin)) return bin;
    }
  }
  return '';
}

const CHROMIUM_BIN = resolveChromiumBin();
// 配置加载期存在性预检：报错落在加载期而非用例 launch 内部，文案给出可操作出路
if (!existsSync(CHROMIUM_BIN)) {
  throw new Error(
    `chromium 可执行文件不存在：${CHROMIUM_BIN || 'playwright 缓存中未找到 chromium-*'}——` +
      '先 `npx playwright install chromium`，或用 E2E_CHROMIUM_BIN 指定已有二进制路径',
  );
}

export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  globalSetup: './e2e/global-setup.ts',
  globalTeardown: './e2e/global-teardown.ts',
  use: {
    baseURL: `http://127.0.0.1:${GATEWAY_PORT}`,
    headless: true,
    viewport: { width: 480, height: 960 },
    launchOptions: { executablePath: CHROMIUM_BIN },
  },
  reporter: [['list']],
  // 冒烟串行：单一源端 + 单通道闸门（第二个并发通道会 REJECT_BUSY）
  workers: 1,
  retries: 0,
});
