import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['tests/**/*.test.ts'],
    // e2e/ 归 Playwright（npx playwright test），vitest 不收集
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
  },
});
