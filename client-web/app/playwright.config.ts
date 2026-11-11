/**
 * Playwright 配置（任务 7.1 冒烟）：
 * - webServer 复用 e2e/global-setup.ts 起的网关（静态托管 dist + /ws 反代源端）；
 * - 无 Playwright 浏览器缓存时先 `npx playwright install chromium`（CI/本机一次性）。
 */

import { defineConfig } from '@playwright/test';

/** 环境已就位的 chromium 二进制（系统 playwright-cli 缓存，revision 1223）；
 *  本地 @playwright/test 期望的 1224 未下载（CDN 不可达），以 executablePath 显式指定复用。 */
const CHROMIUM_BIN = process.env.E2E_CHROMIUM_BIN ?? '/root/.cache/ms-playwright/chromium-1223/chrome-linux64/chrome';

export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  globalSetup: './e2e/global-setup.ts',
  globalTeardown: './e2e/global-teardown.ts',
  use: {
    baseURL: 'http://127.0.0.1:8090',
    headless: true,
    viewport: { width: 480, height: 960 },
    launchOptions: { executablePath: CHROMIUM_BIN },
  },
  reporter: [['list']],
  // 冒烟串行：单一源端 + 单通道闸门（第二个并发通道会 REJECT_BUSY）
  workers: 1,
  retries: 0,
});
