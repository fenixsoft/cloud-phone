/**
 * 冒烟用例（任务 7.1，模拟器 FULLSCREEN 口径，design.md「Playwright 冒烟」）：
 * 1. 首帧渲染：连接后 canvas 像素非空（getImageData 探针）；
 * 2. 触控注入：点画布 → 浏览器 WS 发出 INPUT_EVENT（0x06；源端侧 t2/t3 打点由
 *    run 外侧 adb logcat 断言，见 .fenix/verify-evidence/web-browser-client/）；
 * 3. STATS：浏览器侧收到源端 STATS 推送且 HUD fps > 0；
 * 4. SINGLE_APP 模拟器不可用（device-notes §2.1 镜像级限制）→ 同会话内验收
 *    「失败带原因回连接页」回退（真机直达口径人工复测，client-ux D6 同口径）。
 *
 * 单用例串行全链路：镜像约束下一授权一凭据、共享采集管线闲置 ~60s 失效，
 * 会话一旦建立必须连续使用（用例内完成全部断言），避免管线中途闲置死亡。
 */

import { expect, test } from '@playwright/test';
import { ensureSourcePipeline } from './grant';
import { RECORD_HEADER_BYTES } from '../src/protocol/Framing';
import { MsgType } from '../src/protocol/Messages';
import { E2E_STATE } from './global-setup';

test('FULLSCREEN 冒烟全链路：首帧 → 触控注入 → STATS → 单应用拒绝回退', async ({ page }) => {
  // 复位路径（force-stop + UI 授权 + 管线重建）最坏 ~2min，放宽单用例超时
  test.setTimeout(180_000);
  // 镜像约束：确保共享采集管线在位（投影丢失时自动重授权重建）
  await ensureSourcePipeline();

  // WS 出站帧统计（INPUT_EVENT 断言用）：控制记录头 [u8 recordType][u32 payloadLen]，
  // 头后首字节为 msgType，统计键由 MsgType 常量生成
  const sentTypes: Record<string, number> = {};
  const inputEventKey = `0x${MsgType.INPUT_EVENT.toString(16)}`;
  page.on('websocket', (ws) => {
    ws.on('framesent', (f) => {
      if (typeof f.payload === 'string') return;
      const u8 = Buffer.from(f.payload as Buffer);
      const key = u8.length > RECORD_HEADER_BYTES ? `0x${u8[RECORD_HEADER_BYTES].toString(16)}` : 'short';
      sentTypes[key] = (sentTypes[key] ?? 0) + 1;
    });
  });

  await page.goto(`/?token=${E2E_STATE.token}`);
  // token 已从 URL 收进 localStorage（地址栏不滞留）
  await expect(page.getByLabel('配对 Token')).toHaveValue(E2E_STATE.token);

  await page.getByRole('button', { name: '连接' }).click();

  // 首帧渲染：canvas 非空像素（HELLO → START_SESSION → 视频记录 → WebCodecs 解码全链路）
  await expect
    .poll(
      async () =>
        page.evaluate(() => {
          const canvas = document.getElementById('video-canvas') as HTMLCanvasElement | null;
          if (!canvas || canvas.width === 0) return 0;
          const ctx = canvas.getContext('2d');
          if (!ctx) return 0;
          const { data } = ctx.getImageData(canvas.width / 2 - 8, canvas.height / 2 - 8, 16, 16);
          let nonZero = 0;
          for (let i = 3; i < data.length; i += 4) if (data[i] !== 0) nonZero++;
          return nonZero;
        }),
      { timeout: 30_000, intervals: [1_000, 2_000] },
    )
    .toBeGreaterThan(0);

  // 触控注入：画布点击 → WS 出站 INPUT_EVENT。viewport 480×960（playwright.config），
  // 会话档位 UI 默认 720p60（720×1280）：画布 letterbox 适配视口，点击坐标经
  // InputBridge 归一化为会话 u16 坐标，与 CSS 像素非 1:1，取视口中部点即可
  await page.locator('#video-canvas').click({ position: { x: 240, y: 480 } });
  await expect
    .poll(async () => sentTypes[inputEventKey] ?? 0, { timeout: 10_000, intervals: [500] })
    .toBeGreaterThan(0);

  // STATS：HUD fps 字段为正数（源端 ≥1Hz 推送，浏览器消费渲染；非 '—' 占位）
  await expect
    .poll(async () => page.locator('#hud-fps').textContent(), { timeout: 20_000, intervals: [1_000] })
    .not.toBe('—');
  const fps = Number((await page.locator('#hud-fps').textContent()) ?? '0');
  expect(fps).toBeGreaterThan(0);

  // 应用网格在位（APP_LIST 请求-应答链路）
  const appItem = page.locator('.app-item').first();
  await expect(appItem).toBeVisible({ timeout: 15_000 });

  // SINGLE_APP 模拟器不可用 → 显式拒绝 + 回连接页带原因（回退路径验收）
  await appItem.click();
  await expect(page.locator('#connect-error')).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('#connect-error')).toContainText(/REJECT|拒绝/);
  // 回连接页后会话页隐藏：#video-canvas 静态声明于 #session-page 内，会话拆除只切换
  // section 的 hidden 属性、DOM 节点不清除，故断言隐藏态而非节点计数归零
  await expect(page.locator('#video-canvas')).toBeHidden();
});

test('REJECT_BAD_TOKEN：错误原因显式呈现，停留连接页', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('配对 Token').fill('wrong-token');
  await page.getByRole('button', { name: '连接' }).click();
  await expect(page.locator('#connect-error')).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('#connect-error')).toContainText(/拒绝|token/i);
  // 不进入应用网格
  await expect(page.getByRole('heading', { name: '选择应用' })).toHaveCount(0);
});
