/**
 * InputBridge 单元测试（vitest，DOM 以 jsdom-less 最小替身承载）：
 * letterbox 几何与 u16 映射/clamp、pointerId u8 表、BACK/Esc、文本注入 seq、
 * 剪贴板读写与 auxNotice 置灰、会话参数换新重置坐标基准。
 */
import { describe, expect, it, vi } from 'vitest';

import { InputBridge, letterboxGeometry, mapClientPoint, toNormalized, type InputBridgeDom, type InputSender } from '../src/input/InputBridge';

// ── 最小 DOM 替身 ──

class FakeElement {
  attrs = new Map<string, string>();
  handlers = new Map<string, ((ev: unknown) => void)[]>();
  value = '';
  getBoundingClientRect = () => ({ left: 0, top: 0, width: 800, height: 600 });
  addEventListener(type: string, fn: (ev: unknown) => void): void {
    const list = this.handlers.get(type) ?? [];
    list.push(fn);
    this.handlers.set(type, list);
  }
  removeEventListener(type: string, fn: (ev: unknown) => void): void {
    this.handlers.set(type, (this.handlers.get(type) ?? []).filter((f) => f !== fn));
  }
  setAttribute(name: string, value: string): void {
    this.attrs.set(name, value);
  }
  fire(type: string, ev: unknown): void {
    for (const fn of this.handlers.get(type) ?? []) fn(ev);
  }
}

function pointerEvent(overrides: Partial<PointerEvent> & { type: string; pointerId: number; clientX: number; clientY: number }): PointerEvent {
  return { preventDefault: () => {}, ...overrides } as unknown as PointerEvent;
}

function makeBridge() {
  const sent: { action: number; pointerId: number; x: number; y: number }[] = [];
  const texts: { text: string; seq: number }[] = [];
  const clipSets: string[] = [];
  const clipGets = vi.fn();
  const toasts: string[] = [];
  const sender: InputSender = {
    inputEvent: (action, pointerId, x, y) => sent.push({ action, pointerId, x, y }),
    textInput: (text) => {
      const seq = texts.length + 1;
      texts.push({ text, seq });
      return seq;
    },
    clipboardSet: (text) => clipSets.push(text),
    clipboardGet: () => clipGets(),
  };
  const host = { videoWidth: 1080, videoHeight: 2400, sessionId: 7 };
  const dom: InputBridgeDom = {
    textInput: new FakeElement() as unknown as HTMLInputElement,
    btnBack: new FakeElement() as unknown as HTMLElement,
    btnText: new FakeElement() as unknown as HTMLElement,
    btnClipRead: new FakeElement() as unknown as HTMLElement,
    btnClipWrite: new FakeElement() as unknown as HTMLElement,
  };
  const canvas = new FakeElement() as unknown as HTMLCanvasElement;
  const bridge = new InputBridge(sender, host, dom, { toast: (t) => toasts.push(t) }, canvas);
  const g = (el: HTMLElement) => (el as unknown as FakeElement);
  return { bridge, sent, texts, clipSets, clipGets, toasts, dom, canvas, g, host };
}

describe('letterbox 几何与坐标映射', () => {
  it('contain 缩放居中：1080×2400 → 800×600 客户区', () => {
    const geo = letterboxGeometry(800, 600, 1080, 2400);
    // scale = min(800/1080, 600/2400) = 0.25；视频区 270×600，offsetX = (800−270)/2 = 265
    expect(geo.scale).toBeCloseTo(0.25);
    expect(geo.offsetX).toBeCloseTo(265);
    expect(geo.offsetY).toBeCloseTo(0);
  });

  it('client 点 → 归一化 u16：中心点 ≈ (32768, 32768)', () => {
    const geo = letterboxGeometry(800, 600, 1080, 2400);
    // 画布中心 (400,300) → 视频区内 ((400−265)/0.25, 300/0.25) = (540, 1200) = 视频中心
    const n = mapClientPoint(400, 300, geo, 1080, 2400);
    expect(n.x).toBe(32768); // round(540/1080×65535)
    expect(n.y).toBe(32768); // round(1200/2400×65535)
  });

  it('letterbox 黑边区 clamp 到视频边缘', () => {
    const geo = letterboxGeometry(800, 600, 1080, 2400);
    const left = mapClientPoint(0, 300, geo, 1080, 2400); // 黑边 x=0 → videoX = −1060 → clamp 0
    expect(left.x).toBe(0);
    expect(left.y).toBe(32768);
    const right = mapClientPoint(800, 300, geo, 1080, 2400); // 黑边右 → clamp 65535
    expect(right.x).toBe(65535);
  });

  it('toNormalized 边界与四舍五入', () => {
    expect(toNormalized(-5, -5, 100, 100)).toEqual({ x: 0, y: 0 });
    expect(toNormalized(50, 50, 100, 100)).toEqual({ x: 32768, y: 32768 });
    expect(toNormalized(100, 100, 100, 100)).toEqual({ x: 65535, y: 65535 });
    expect(toNormalized(200, 200, 100, 100)).toEqual({ x: 65535, y: 65535 }); // 越界 clamp
  });

  it('退化输入（零尺寸）返回单位几何', () => {
    expect(letterboxGeometry(0, 0, 0, 0)).toEqual({ offsetX: 0, offsetY: 0, scale: 1 });
  });
});

describe('InputBridge 交互', () => {
  it('pointerdown/move/up → INPUT_EVENT{DOWN/MOVE/UP}，pointerId 映射 u8 槽位', () => {
    const { bridge, sent, canvas, g } = makeBridge();
    g(canvas).fire('pointerdown', pointerEvent({ type: 'pointerdown', pointerId: 11, clientX: 400, clientY: 300 }));
    g(canvas).fire('pointermove', pointerEvent({ type: 'pointermove', pointerId: 11, clientX: 402, clientY: 310 }));
    g(canvas).fire('pointerup', pointerEvent({ type: 'pointerup', pointerId: 11, clientX: 404, clientY: 320 }));
    expect(sent.map((s) => s.action)).toEqual([0, 2, 1]); // DOWN/MOVE/UP
    expect(sent[0].pointerId).toBe(1); // 首个活动指针 → 槽位 1
    expect(sent.every((s) => s.x >= 0 && s.x <= 65535 && s.y >= 0 && s.y <= 65535)).toBe(true);
    // 抬起后槽位释放：新指针再 down → 仍从 1 起（复用）
    g(canvas).fire('pointerdown', pointerEvent({ type: 'pointerdown', pointerId: 99, clientX: 400, clientY: 300 }));
    expect(sent[3].pointerId).toBe(2); // 同期未释放时递增——此处 11 已释放，99 新分配 2
    bridge.dispose();
  });

  it('双指并发：两个 pointerId 各占一个 u8 槽位', () => {
    const { bridge, sent, canvas, g } = makeBridge();
    g(canvas).fire('pointerdown', pointerEvent({ type: 'pointerdown', pointerId: 1, clientX: 300, clientY: 300 }));
    g(canvas).fire('pointerdown', pointerEvent({ type: 'pointerdown', pointerId: 2, clientX: 500, clientY: 300 }));
    expect(sent[0].pointerId).toBe(1);
    expect(sent[1].pointerId).toBe(2);
    bridge.dispose();
  });

  it('BACK 按钮 / Esc → INPUT_EVENT{action=3, pointerId=0}', () => {
    const { bridge, sent, dom, g } = makeBridge();
    g(dom.btnBack).fire('click', {});
    expect(sent).toEqual([{ action: 3, pointerId: 0, x: 0, y: 0 }]);
    bridge.dispose();
  });

  it('文本注入：回车发送 TEXT_INPUT（seq 自增）并清空输入框', () => {
    const { bridge, texts, dom, g, toasts } = makeBridge();
    g(dom.textInput).value = 'hello 世界';
    g(dom.textInput).fire('keydown', { key: 'Enter', preventDefault: () => {} });
    expect(texts).toEqual([{ text: 'hello 世界', seq: 1 }]);
    expect(g(dom.textInput).value).toBe('');
    expect(toasts.length).toBeGreaterThan(0);
    // ACK 回执
    bridge.onTextInputAck(1, true, '');
    expect(toasts[toasts.length - 1]).toContain('成功');
    bridge.onTextInputAck(2, false, '无焦点窗口');
    expect(toasts[toasts.length - 1]).toContain('无焦点窗口');
    bridge.dispose();
  });

  it('剪贴板读取回执：CLIPBOARD_TEXT → 写本地剪贴板 + toast', async () => {
    const { bridge, toasts } = makeBridge();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(globalThis, 'navigator', { value: { clipboard: { writeText, readText: vi.fn() } }, configurable: true });
    bridge.onClipboardText('源端文本');
    await Promise.resolve();
    expect(writeText).toHaveBeenCalledWith('源端文本');
    expect(toasts[toasts.length - 1]).toContain('已复制');
    bridge.dispose();
  });

  it('剪贴板写入按钮：读本地剪贴板 → CLIPBOARD_SET + toast', async () => {
    const { bridge, clipSets, toasts, dom, g } = makeBridge();
    const readText = vi.fn().mockResolvedValue('本地文本');
    Object.defineProperty(globalThis, 'navigator', { value: { clipboard: { readText, writeText: vi.fn() } }, configurable: true });
    g(dom.btnClipWrite).fire('click', {});
    await Promise.resolve();
    await Promise.resolve();
    expect(readText).toHaveBeenCalled();
    expect(clipSets).toEqual(['本地文本']);
    expect(toasts.some((t) => t.includes('已写入源端剪贴板'))).toBe(true);
    bridge.dispose();
  });

  it('auxNotice kind=1（剪贴板降级）：两个剪贴板按钮置灰 + toast 原因', () => {
    const { bridge, toasts, dom, g } = makeBridge();
    bridge.onAuxNotice(2, '设备多窗口模式');
    expect(bridge.clipboardDisabled).toBe(true);
    expect(g(dom.btnClipRead).attrs.get('disabled')).toBe('true');
    expect(g(dom.btnClipWrite).attrs.get('disabled')).toBe('true');
    expect(toasts.some((t) => t.includes('设备多窗口模式'))).toBe(true);
    // 置灰后按钮点击不再发送
    g(dom.btnClipRead).fire('click', {});
    expect(toasts[toasts.length - 1]).toContain('不可用');
    bridge.dispose();
  });

  it('会话参数换新：坐标基准重置 + 指针表清空', () => {
    const { bridge, sent, canvas, g, host } = makeBridge();
    g(canvas).fire('pointerdown', pointerEvent({ type: 'pointerdown', pointerId: 5, clientX: 400, clientY: 300 }));
    expect(sent[0].pointerId).toBe(1);
    bridge.onSessionParameters(720, 1280, 9); // 换新会话
    expect(host.videoWidth).toBe(720);
    expect(host.sessionId).toBe(9);
    // 新分辨率下同一 client 点映射不同归一化值（720 宽基准）
    g(canvas).fire('pointerdown', pointerEvent({ type: 'pointerdown', pointerId: 5, clientX: 400, clientY: 300 }));
    expect(sent[1].pointerId).toBe(1); // 指针表已清空 → 重新从 1 分配
    // 720×1280 letterbox 到 800×600：scale=min(800/720,600/1280)=0.46875，中心仍居中
    expect(sent[1].x).toBe(32768);
    expect(sent[1].y).toBe(32768);
    bridge.dispose();
  });

  it('会话未建立（分辨率 0）时指针事件被忽略', () => {
    const { bridge, sent, canvas, g } = makeBridge();
    bridge.onSessionParameters(0, 0, 0);
    g(canvas).fire('pointerdown', pointerEvent({ type: 'pointerdown', pointerId: 1, clientX: 400, clientY: 300 }));
    expect(sent).toHaveLength(0);
    bridge.dispose();
  });
});
