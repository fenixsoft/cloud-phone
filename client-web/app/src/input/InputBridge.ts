/**
 * 输入桥（任务 6.1）：指针事件 → INPUT_EVENT（letterbox 裁剪线性映射 ×65535 clamp）、
 * BACK 按键/Esc、隐藏输入框 → TEXT_INPUT（kind=0 + seq 自增，ACK 提示）、
 * CLIPBOARD_GET/SET（回执 CLIPBOARD_TEXT 写本地剪贴板 + toast；kind=1 auxNotice →
 * 控件置灰 + toast 原因）。
 *
 * 坐标口径（对齐 Windows/Android 端）：以画布内 letterbox 实际视频区域为基准，
 * (clientXY − offset)/scale → 源分辨率坐标 → round(×65535/SW|SH) clamp u16；
 * 源端按归一化值 + 会话分辨率还原绝对坐标。
 */

import { InputAction } from '../protocol/Messages';

export type InputSender = {
  /** 发送 INPUT_EVENT（sessionId 由会话层携带） */
  inputEvent(action: number, pointerId: number, x: number, y: number): void;
  textInput(text: string): number;
  clipboardSet(text: string): void;
  clipboardGet(): void;
};

export interface Letterbox {
  /** 视频在画布元素内的偏移（CSS px）与缩放 */
  offsetX: number;
  offsetY: number;
  scale: number;
}

/** letterbox 几何：contain 缩放居中（canvas 客户区 vs 源分辨率） */
export function letterboxGeometry(clientW: number, clientH: number, videoW: number, videoH: number): Letterbox {
  if (clientW <= 0 || clientH <= 0 || videoW <= 0 || videoH <= 0) {
    return { offsetX: 0, offsetY: 0, scale: 1 };
  }
  const scale = Math.min(clientW / videoW, clientH / videoH);
  return {
    offsetX: (clientW - videoW * scale) / 2,
    offsetY: (clientH - videoH * scale) / 2,
    scale,
  };
}

/** 视频区内 CSS 坐标 → 归一化 u16（源分辨率基准，clamp [0,65535]） */
export function toNormalized(videoX: number, videoY: number, videoW: number, videoH: number): { x: number; y: number } {
  const clampU16 = (v: number): number => Math.max(0, Math.min(65535, Math.round(v)));
  return {
    x: clampU16((videoX / videoW) * 65535),
    y: clampU16((videoY / videoH) * 65535),
  };
}

/** client 坐标（相对画布左上）→ 归一化 u16（letterbox 外 clamp 到边缘） */
export function mapClientPoint(px: number, py: number, geo: Letterbox, videoW: number, videoH: number): { x: number; y: number } {
  const videoX = (px - geo.offsetX) / geo.scale;
  const videoY = (py - geo.offsetY) / geo.scale;
  return toNormalized(videoX, videoY, videoW, videoH);
}

export interface InputBridgeHost {
  /** 当前会话分辨率（sessionStarted 时更新；换新即重置坐标基准） */
  videoWidth: number;
  videoHeight: number;
  sessionId: number;
}

export interface ToastSink {
  toast(text: string): void;
}

/** 隐藏输入框 + 工具栏（main.ts 传入真实元素；测试注入最小接口） */
export interface InputBridgeDom {
  /** 隐藏文本输入框（会话页工具栏「文字」展开） */
  textInput: HTMLInputElement;
  btnBack: HTMLElement;
  btnText: HTMLElement;
  btnClipRead: HTMLElement;
  btnClipWrite: HTMLElement;
}

export class InputBridge {
  private readonly pointerTable = new Map<number, number>(); // PointerEvent.pointerId → u8 序号（1..255）
  private nextPointerSeq = 0;
  private readonly listeners: Array<() => void> = [];
  /** CLIPBOARD_TEXT kind=1 auxNotice 后置灰（源端降级） */
  clipboardDisabled = false;
  private sessionWidth = 0;
  private sessionHeight = 0;

  constructor(
    private readonly sender: InputSender,
    private readonly host: InputBridgeHost,
    private readonly dom: InputBridgeDom,
    private readonly toastSink: ToastSink,
    private readonly canvas: HTMLCanvasElement,
  ) {
    this.sessionWidth = host.videoWidth;
    this.sessionHeight = host.videoHeight;
    this.bind();
  }

  /** 会话参数换新（ATTACH 恢复/重开）：重置坐标基准与活动指针表 */
  onSessionParameters(width: number, height: number, sessionId: number): void {
    this.sessionWidth = width;
    this.sessionHeight = height;
    this.host.videoWidth = width;
    this.host.videoHeight = height;
    this.host.sessionId = sessionId;
    this.pointerTable.clear();
    this.nextPointerSeq = 0;
  }

  private bind(): void {
    // ── 指针：pointerdown/move/up → INPUT_EVENT{DOWN/MOVE/UP}（多指 u8 表，源端 v1 只取单点）──
    const point = (ev: Event): void => {
      const pe = ev as PointerEvent;
      if (this.sessionWidth === 0) return;
      const rect = this.canvas.getBoundingClientRect();
      const geo = letterboxGeometry(rect.width, rect.height, this.sessionWidth, this.sessionHeight);
      const { x, y } = mapClientPoint(pe.clientX - rect.left, pe.clientY - rect.top, geo, this.sessionWidth, this.sessionHeight);
      this.sender.inputEvent(this.actionOf(pe.type), this.pointerIdU8(pe.pointerId, pe.type), x, y);
    };
    this.listen(this.canvas, 'pointerdown', point);
    this.listen(this.canvas, 'pointermove', point);
    this.listen(this.canvas, 'pointerup', (ev) => {
      point(ev);
      this.pointerTable.delete((ev as PointerEvent).pointerId); // 抬起释放槽位
    });
    this.listen(this.canvas, 'pointercancel', (ev) => {
      point(ev);
      this.pointerTable.delete((ev as PointerEvent).pointerId);
    });
    // 触屏长按上下文菜单/选择干扰
    this.listen(this.canvas, 'contextmenu', (ev) => ev.preventDefault());

    // ── BACK：工具栏按钮 + Esc 键 → INPUT_EVENT{action=3}（指针字段置零）──
    this.listen(this.dom.btnBack, 'click', () => this.sendBack());
    if (typeof globalThis.addEventListener === 'function') {
      this.listen(globalThis, 'keydown', (ev) => {
        const ke = ev as KeyboardEvent;
        if (ke.key === 'Escape') {
          ke.preventDefault();
          this.sendBack();
        }
      });
    }

    // ── 文本注入：隐藏输入框回车/按钮 → TEXT_INPUT{kind=0, seq 自增}；ACK 由会话层回 toast ──
    const sendText = (): void => {
      const text = this.dom.textInput.value;
      if (!text) return;
      this.sender.textInput(text);
      this.dom.textInput.value = '';
      this.toastSink.toast(`文字已发送（seq 回执后提示结果）`);
    };
    this.listen(this.dom.btnText, 'click', sendText);
    this.listen(this.dom.textInput, 'keydown', (ev) => {
      const ke = ev as KeyboardEvent;
      if (ke.key === 'Enter') {
        ke.preventDefault();
        sendText();
      }
    });

    // ── 剪贴板 ──
    this.listen(this.dom.btnClipRead, 'click', () => {
      if (this.clipboardDisabled) {
        this.toastSink.toast('剪贴板读取不可用（源端降级）');
        return;
      }
      this.sender.clipboardGet(); // 回执 CLIPBOARD_TEXT → onClipboardText / auxNotice → onAuxNotice
    });
    this.listen(this.dom.btnClipWrite, 'click', () => {
      if (this.clipboardDisabled) {
        this.toastSink.toast('剪贴板写入不可用（源端降级）');
        return;
      }
      void navigator.clipboard
        ?.readText()
        .then((text) => {
          if (!text) {
            this.toastSink.toast('本地剪贴板为空');
            return;
          }
          this.sender.clipboardSet(text);
          this.toastSink.toast('已写入源端剪贴板');
        })
        .catch(() => this.toastSink.toast('本地剪贴板读取失败（需授权或 HTTPS）'));
    });
  }

  private listen(target: EventTarget | HTMLElement, type: string, fn: (ev: Event) => void): void {
    const listener = fn as EventListener;
    (target as EventTarget).addEventListener(type, listener);
    this.listeners.push(() => (target as EventTarget).removeEventListener(type, listener));
  }

  private actionOf(type: string): number {
    switch (type) {
      case 'pointerdown':
        return InputAction.DOWN;
      case 'pointermove':
        return InputAction.MOVE;
      default:
        return InputAction.UP;
    }
  }

  /** PointerEvent.pointerId → u8 活动槽位（1..255 循环复用；0 保留给非指针语义） */
  private pointerIdU8(pointerId: number, type: string): number {
    if (type === 'pointerdown' || !this.pointerTable.has(pointerId)) {
      if (!this.pointerTable.has(pointerId)) {
        this.nextPointerSeq = (this.nextPointerSeq % 255) + 1;
        this.pointerTable.set(pointerId, this.nextPointerSeq);
      }
    }
    return this.pointerTable.get(pointerId) ?? 0;
  }

  private sendBack(): void {
    this.sender.inputEvent(InputAction.BACK, 0, 0, 0);
  }

  /** CLIPBOARD_TEXT 回执：写本地剪贴板 + toast（auxNotice kind=1 时已置灰） */
  async onClipboardText(text: string): Promise<void> {
    try {
      await navigator.clipboard?.writeText(text);
      this.toastSink.toast('源端剪贴板已复制到本地');
    } catch {
      this.toastSink.toast(`源端剪贴板内容：${text.slice(0, 40)}`);
    }
  }

  /** auxNotice kind=1（剪贴板降级）：置灰两个剪贴板按钮 + toast 原因 */
  onAuxNotice(channel: number, reason: string): void {
    if (channel === 2) {
      // AuxChannel.CLIPBOARD
      this.clipboardDisabled = true;
      this.dom.btnClipRead.setAttribute('disabled', 'true');
      this.dom.btnClipWrite.setAttribute('disabled', 'true');
    }
    this.toastSink.toast(`辅助通道降级（channel=${channel}）：${reason || '不可用'}`);
  }

  /** TEXT_INPUT 回执 toast（ok=false 显式失败原因） */
  onTextInputAck(seq: number, ok: boolean, reason: string): void {
    this.toastSink.toast(ok ? `文字注入成功（seq=${seq}）` : `文字注入失败（seq=${seq}）：${reason || '未知原因'}`);
  }

  dispose(): void {
    for (const off of this.listeners) off();
    this.listeners.length = 0;
  }
}
