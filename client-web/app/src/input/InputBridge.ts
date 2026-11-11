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

/** MOVE 注入最小位移阈值（归一化 u16 单位，≈源分辨率 0.4%）：抑制高频微动，降低共享控制流噪声 */
const MOVE_MIN_DELTA_U16 = 256;

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
  /** 每指针上次已发送的归一化坐标（MOVE 位移阈值节流基准；抬起/换新会话时清除） */
  private readonly lastSentPoint = new Map<number, { x: number; y: number }>();
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
    this.lastSentPoint.clear();
    // 源端剪贴板降级（auxNotice）为会话期暂态：换新/重开会话复位置灰，否则只能刷新页面才能恢复
    this.clipboardDisabled = false;
    this.resetClipboardGray();
  }

  private bind(): void {
    // ── 指针：pointerdown/move/up → INPUT_EVENT{DOWN/MOVE/UP}（多指 u8 表，源端 v1 只取单点）──
    const point = (ev: Event): void => {
      const pe = ev as PointerEvent;
      if (this.sessionWidth === 0) return;
      // MOVE 属按下序列语义：仅注入已按下（在表中）指针的 MOVE，过滤桌面鼠标悬停等无 DOWN 前提的移动流
      if (pe.type === 'pointermove' && !this.pointerTable.has(pe.pointerId)) return;
      const rect = this.canvas.getBoundingClientRect();
      const geo = letterboxGeometry(rect.width, rect.height, this.sessionWidth, this.sessionHeight);
      const { x, y } = mapClientPoint(pe.clientX - rect.left, pe.clientY - rect.top, geo, this.sessionWidth, this.sessionHeight);
      // MOVE 位移阈值节流：与该指针上次已发送点偏差均小于阈值则不注入（抑制高频微动噪声）
      const last = this.lastSentPoint.get(pe.pointerId);
      if (pe.type === 'pointermove' && last && Math.abs(x - last.x) < MOVE_MIN_DELTA_U16 && Math.abs(y - last.y) < MOVE_MIN_DELTA_U16) {
        return;
      }
      this.sender.inputEvent(this.actionOf(pe.type), this.pointerIdU8(pe.pointerId), x, y);
      this.lastSentPoint.set(pe.pointerId, { x, y });
    };
    this.listen(this.canvas, 'pointerdown', (ev) => {
      this.capturePointer(ev as PointerEvent); // 捕获保证移出画布后 up/cancel 仍派发到 canvas，槽位必回收
      point(ev);
    });
    this.listen(this.canvas, 'pointermove', point);
    this.listen(this.canvas, 'pointerup', (ev) => {
      point(ev);
      const pe = ev as PointerEvent;
      this.pointerTable.delete(pe.pointerId); // 抬起释放槽位
      this.lastSentPoint.delete(pe.pointerId);
    });
    this.listen(this.canvas, 'pointercancel', (ev) => {
      point(ev);
      const pe = ev as PointerEvent;
      this.pointerTable.delete(pe.pointerId);
      this.lastSentPoint.delete(pe.pointerId);
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
      const clipboard = this.localClipboard();
      if (!clipboard) {
        // 纯 HTTP（frp 隧道）非安全上下文无本地剪贴板 API：明示原因并置灰，禁止点击后静默无反馈
        this.toastSink.toast('剪贴板写入不可用（需 HTTPS/安全上下文，当前为 HTTP 访问）');
        this.dom.btnClipWrite.setAttribute('disabled', 'true');
        return;
      }
      void clipboard
        .readText()
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

  /** PointerEvent.pointerId → u8 活动槽位（1..255 循环复用；0 保留给非指针语义）。
   *  分配时跳过仍被活动指针占用的序号（防循环复用与活动指针撞号），表满时强制回收最早条目（防抬起丢失泄漏）。 */
  private pointerIdU8(pointerId: number): number {
    const existing = this.pointerTable.get(pointerId);
    if (existing !== undefined) return existing;
    if (this.pointerTable.size >= 255) {
      const oldest = this.pointerTable.keys().next().value;
      if (oldest !== undefined) this.pointerTable.delete(oldest);
    }
    for (let guard = 0; guard < 255; guard++) {
      this.nextPointerSeq = (this.nextPointerSeq % 255) + 1;
      if (![...this.pointerTable.values()].includes(this.nextPointerSeq)) break;
    }
    this.pointerTable.set(pointerId, this.nextPointerSeq);
    return this.nextPointerSeq;
  }

  /** pointerdown 即捕获指针：指针移出画布后 up/cancel 仍派发到 canvas，槽位必回收（防表泄漏） */
  private capturePointer(ev: PointerEvent): void {
    if (typeof this.canvas.setPointerCapture !== 'function') return; // 最小测试替身未模拟时跳过
    try {
      this.canvas.setPointerCapture(ev.pointerId);
    } catch {
      // 指针已失活（NotFoundError 等）：忽略，up/cancel 仍可能自然到达
    }
  }

  private sendBack(): void {
    this.sender.inputEvent(InputAction.BACK, 0, 0, 0);
  }

  /** 本地剪贴板 API：仅安全上下文（HTTPS/localhost）存在；纯 HTTP frp 隧道访问时为 undefined */
  private localClipboard(): Clipboard | undefined {
    return typeof navigator !== 'undefined' ? navigator.clipboard : undefined;
  }

  /** CLIPBOARD_TEXT 回执：写本地剪贴板 + toast（auxNotice kind=1 时已置灰） */
  async onClipboardText(text: string): Promise<void> {
    const clipboard = this.localClipboard();
    try {
      if (!clipboard) {
        // 非安全上下文无 clipboard API：writeText 未执行，禁止假成功提示，降级直显内容
        this.toastSink.toast(`源端剪贴板内容：${text.slice(0, 40)}`);
        return;
      }
      await clipboard.writeText(text);
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

  /** 换新会话解禁源端降级置灰；写按钮在无本地剪贴板 API（非安全上下文）时保持置灰 */
  private resetClipboardGray(): void {
    this.removeDisabledAttr(this.dom.btnClipRead);
    if (this.localClipboard()) this.removeDisabledAttr(this.dom.btnClipWrite);
  }

  private removeDisabledAttr(btn: HTMLElement): void {
    // 最小测试替身未模拟 removeAttribute：存在才调用（真实 DOM 必有）
    if (typeof btn.removeAttribute === 'function') btn.removeAttribute('disabled');
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
