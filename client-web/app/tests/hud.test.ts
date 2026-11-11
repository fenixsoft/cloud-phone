/**
 * Hud 单元测试：延迟样本环、P50/P95 分位、采样开关语义、web-latency-samples.json 口径文档。
 */
import { describe, expect, it } from 'vitest';

import { Hud, type HudRefs } from '../src/ui/Hud';

function makeHud(): { hud: Hud; refs: HudRefs } {
  const span = (): HTMLElement => ({ textContent: '' } as unknown as HTMLElement);
  const refs: HudRefs = {
    fps: span(),
    bitrate: span(),
    rtt: span(),
    latencyP50: span(),
    latencyP95: span(),
    decodeQueue: span(),
    dropped: span(),
    sampleBadge: span(),
  };
  return { hud: new Hud(refs), refs };
}

describe('Hud', () => {
  it('帧采样 → P50/P95 分位（含 0.1ms 精度）', () => {
    const { hud } = makeHud();
    expect(hud.percentiles()).toBeNull(); // 无样本
    hud.setSampling(true);
    // offset=0，ptsUs 递减制造延迟序列 10,20,…,100 ms（now−pts）
    let pts = 1_000_000n;
    const nowSpy = vi_now(1000); // performance.now=1000ms → nowUs=1e6µs 与 pts 基准对齐
    for (let i = 1; i <= 10; i++) {
      hud.onFrameDecoded(pts, 0);
      pts -= 10_000n; // 每帧 pts 减 10ms → 延迟 +10ms
    }
    const p = hud.percentiles()!;
    expect(hud.sampleCount()).toBe(10);
    expect(p.p50).toBeCloseTo(50, 0); // 样本 0,10,…,90ms；at(0.5)=lat[5]=50
    expect(p.p95).toBeGreaterThanOrEqual(p.p50);
    nowSpy.mockRestore();
    void nowSpy;
  });

  it('采样关闭不记录；重新开启清空', () => {
    const { hud } = makeHud();
    hud.onFrameDecoded(100n, 0); // 未开启
    expect(hud.sampleCount()).toBe(0);
    hud.setSampling(true);
    hud.onFrameDecoded(100n, 0);
    expect(hud.sampleCount()).toBe(1);
    hud.setSampling(false);
    hud.onFrameDecoded(100n, 0);
    expect(hud.sampleCount()).toBe(1); // 关闭时不增
    hud.setSampling(true); // 重新开启清空
    expect(hud.sampleCount()).toBe(0);
  });

  it('RTT 样本 EMA 窗口与 rttMs 换算', () => {
    const { hud } = makeHud();
    for (let i = 0; i < 20; i++) hud.onClockSample(20_000); // 20ms
    expect(hud.rttMs()).toBeCloseTo(20, 0); // 窗口封顶 16 仍 20ms
  });

  it('update 刷新 HUD 字段文本', () => {
    const { hud, refs } = makeHud();
    hud.sessionProfile = { width: 720, height: 1280, fps: 60, bitrateKbps: 8000 };
    hud.update({ fps: 59.8, bitrateKbps: 7950, rttMs: 12, decodeQueueSize: 3, dropped: 7 });
    expect(refs.fps.textContent).toBe('59.8');
    expect(refs.bitrate.textContent).toBe('7.95 Mbps');
    expect(refs.rtt.textContent).toBe('12 ms');
    expect(refs.decodeQueue.textContent).toBe('3');
    expect(refs.dropped.textContent).toBe('7');
    expect(refs.latencyP50.textContent).toBe('—'); // 无样本
  });

  it('exportSamplesDoc：口径注明浏览器端 + summary 结构完整', () => {
    const { hud } = makeHud();
    hud.sessionProfile = { width: 720, height: 1280, fps: 60, bitrateKbps: 8000 };
    hud.setSampling(true);
    const nowSpy = vi_now(2_000_000);
    for (let i = 0; i < 5; i++) hud.onFrameDecoded(1_900_000n + BigInt(i) * 1000n, 0);
    const doc = hud.exportSamplesDoc('Chrome 131.0');
    expect(doc.caliber).toContain('浏览器端口径');
    expect(doc.browser).toBe('Chrome 131.0');
    expect(doc.profile).toEqual({ width: 720, height: 1280, fps: 60, bitrateKbps: 8000 });
    expect(doc.summary.samples).toBe(5);
    expect(doc.summary.p50Ms).toBeGreaterThanOrEqual(0);
    expect(doc.summary.rttMsAvg).toBe(0);
    expect(doc.samples[0]).toHaveProperty('latencyMs');
    expect(doc.samples[0]).toHaveProperty('ptsUs');
    nowSpy.mockRestore();
  });

  it('采样下载按钮：停止后有样本才可见，点击触发下载（docs §6 口径）', () => {
    const handlers: Array<() => void> = [];
    const downloadBtn = {
      hidden: true,
      addEventListener: (_: string, fn: () => void) => handlers.push(fn),
    } as unknown as HTMLElement;
    const span = (): HTMLElement => ({ textContent: '' } as unknown as HTMLElement);
    const base = (): HudRefs => ({
      fps: span(), bitrate: span(), rtt: span(),
      latencyP50: span(), latencyP95: span(),
      decodeQueue: span(), dropped: span(),
    });
    const hud = new Hud({ ...base(), sampleDownload: downloadBtn });
    // 开启采样：下载钮隐藏
    hud.setSampling(true);
    expect((downloadBtn as unknown as { hidden: boolean }).hidden).toBe(true);
    hud.onFrameDecoded(100n, 0);
    // 停止且有样本：可见
    hud.setSampling(false);
    expect((downloadBtn as unknown as { hidden: boolean }).hidden).toBe(false);
    // 点击 → downloadSamplesDoc（browserLabel 读 navigator UA，node 环境走兜底）
    const calls: string[] = [];
    (hud as unknown as { downloadSamplesDoc: (b: string) => void }).downloadSamplesDoc = (b: string) => calls.push(b);
    handlers[0]();
    expect(calls.length).toBe(1);
    // 无样本停止：保持隐藏
    const hud2 = new Hud({ ...base(), sampleDownload: downloadBtn });
    hud2.setSampling(true);
    hud2.setSampling(false);
    expect((downloadBtn as unknown as { hidden: boolean }).hidden).toBe(true);
  });
});

/** performance.now 打点替身（vitest 无 fake-timer 依赖下手动控时） */
function vi_now(value: number): { mockRestore(): void } {
  const original = performance.now.bind(performance);
  performance.now = () => value;
  return { mockRestore: () => (performance.now = original) };
}
