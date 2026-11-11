/**
 * 会话编排与控制面（浏览器侧，任务 5.1/5.2）。
 *
 * 连接生命周期：token → HELLO{p2pOffer:""}（无加密块 = none 路径）→ HELLO_ACK（占位参数，
 * 仅裁决 token/占用）→ START_SESSION{sessionId, mode, w/h/fps/bitrateKbps} → HELLO_ACK
 * （真实会话参数：sessionId/w/h/fps/bitrateKbps/encoder/displayId 采用为坐标与 HUD 基准）。
 *
 * 显式失败不静默重试：REJECT_BAD_TOKEN（token 错）、REJECT_BUSY（传输层观看端占用闸门
 * /会话层 ATTACH 恢复失败）直接以 rejected/reconnectFailed 收尾。
 *
 * 控制面：应答源端 1Hz 入站 PING（Pong{nonce, senderUs, echoUs=nowUs}）；自发 1Hz PING
 * 供时钟估计——RTT = t_recv − t_send，offset ≈ echoUs − (t_send+t_recv)/2；消费源端
 * 1Hz STATS。>6s 无 Pong 掐死连接走重连（对齐 Windows SessionController 看门狗）。
 *
 * 断线重连（ATTACH）：意外断开后指数退避（0.5s 起步，倍增，封顶 5s；总窗口 30s），
 * 重连后 START_SESSION{sessionId=N, mode=ATTACH} 恢复（源端自动触发关键帧，不发
 * IDR_REQUEST——该消息仅作用于整屏共享采集）。超窗或 ATTACH 被显式拒绝
 * （REJECT_BUSY / REJECT_UNSUPPORTED_MODE）即停止重试回连接页带原因。
 */

import { ProtocolError, RECORD_CONTROL, RECORD_VIDEO, RecordStreamParser } from '../protocol/Framing';
import {
  HelloStatus,
  MsgType,
  WatchMode,
  decodeControlMessage,
  encodeControlRecord,
  type AppEntry,
  type ControlMessage,
  type EncodableControlMessage,
  type HungSession,
} from '../protocol/Messages';

export type { AppEntry, HungSession };

/** WebSocket 最小结构接口（注入替身用；binaryType 固定 arraybuffer） */
export interface WebSocketLike {
  send(data: ArrayBuffer | Uint8Array): void;
  close(code?: number, reason?: string): void;
  readonly readyState: number;
  binaryType: string;
  onopen: (() => void) | null;
  onclose: ((ev: { code: number; reason: string }) => void) | null;
  onerror: ((ev: unknown) => void) | null;
  onmessage: ((ev: { data: ArrayBuffer }) => void) | null;
}

export interface VideoProfile {
  width: number;
  height: number;
  fps: number;
  bitrateKbps: number;
}

export type StartMode =
  | { kind: 'fullscreen' }
  | { kind: 'singleApp'; packageName: string }
  | { kind: 'attach'; sessionId: number };

export interface SessionParameters extends VideoProfile {
  sessionId: number;
  encoder: string;
  displayId: number;
}

/** 会话事件（on() 订阅） */
export interface SessionEvents {
  /** WS 已打开，HELLO 已发出 */
  connecting: { attempt: number };
  /** HELLO 被接受（握手层通过，尚未建会话） */
  handshakeAccepted: void;
  /** START_SESSION 应答的 HELLO_ACK 真实参数；sessionId 换新即重置坐标基准 */
  sessionStarted: SessionParameters;
  /** 显式拒绝（首连与重连共用；statusName 为 REJECT_* 语义名） */
  rejected: { status: number; statusName: string; reason: string };
  /** 应用列表应答 */
  appList: { requestId: number; apps: AppEntry[] };
  /** 视频记录载荷（0x02 记录，TCP 整帧口径；由 VideoPipeline 消费） */
  videoRecord: Uint8Array;
  /** 音频块 */
  audioChunk: { ptsUs: bigint; seq: number; payload: Uint8Array };
  /** 源端 STATS（≥1Hz） */
  stats: { sessionId: number; seq: number; fps: number; bitrateKbps: number; rttMs: number; lossPct: number; transport: number };
  /** 时钟估计更新（自发 PING→PONG） */
  clock: { rttUs: number; offsetUs: number };
  /** 剪贴板文本回执 */
  clipboardText: { text: string };
  /** 辅助通道降级通告（CLIPBOARD_TEXT kind=1） */
  auxNotice: { channel: number; reason: string };
  /** 文本注入回执 */
  textInputAck: { seq: number; ok: boolean; reason: string };
  /** WS 断开（意外；重连决策见 reconnectFailed / reconnectScheduled） */
  disconnected: { code: number; reason: string };
  /** 排定一次重连尝试（第 n 次，延迟 ms） */
  reconnectScheduled: { attempt: number; delayMs: number };
  /** 重连放弃（超 30s 窗口或显式拒绝）；reason 供连接页呈现 */
  reconnectFailed: { reason: string };
  /** 收到未知类型记录（容忍跳过；计数供诊断） */
  unknownMessage: { msgType: number };
}

type EventName = keyof SessionEvents;
type Handler<T> = (payload: T) => void;

const HELLO_TIMEOUT_MS = 5_000;
const START_SESSION_TIMEOUT_MS = 5_000;
const APP_LIST_TIMEOUT_MS = 3_000;
const PING_INTERVAL_MS = 1_000;
/** 看门狗：>6s 无 Pong 判死（对齐 Windows SessionController） */
const PONG_WATCHDOG_MS = 6_000;
/** 断线重连总窗口 */
const RECONNECT_WINDOW_MS = 30_000;
const RECONNECT_BASE_MS = 500;
const RECONNECT_MAX_DELAY_MS = 5_000;

export class SessionClient {
  private ws: WebSocketLike | null = null;
  private parser = new RecordStreamParser();
  private handlers = new Map<EventName, Set<Handler<never>>>();
  private pendingStart = false;
  private helloWaiter: ((msg: Extract<ControlMessage, { kind: 'helloAck' }>) => void) | null = null;
  private startWaiter: ((msg: Extract<ControlMessage, { kind: 'helloAck' }>) => void) | null = null;
  private appListWaiters = new Map<number, (msg: Extract<ControlMessage, { kind: 'appList' }>) => void>();
  private textSeq = 0;
  private appListSeq = 0;
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  private lastPongAtMs = 0;
  private pingNonceSeq = 0n;
  private pendingPings = new Map<bigint, number>(); // nonce → t_send (browser µs)

  /** 当前会话参数（sessionStarted 后有效） */
  currentSession: SessionParameters | null = null;
  /** 时钟估计（browser 钟 ↔ 源端钟） */
  clock = { rttUs: 0, offsetUs: 0 };
  /** 用户主动断开：不自动重连 */
  private userClosed = false;
  private reconnectAttempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private readonly wsFactory: (url: string) => WebSocketLike,
    private readonly nowUs: () => number = () => Math.round(performance.now() * 1000),
    private readonly scheduleTimer: (fn: () => void, ms: number) => ReturnType<typeof setTimeout> = (fn, ms) => setTimeout(fn, ms),
    private readonly scheduleInterval: (fn: () => void, ms: number) => ReturnType<typeof setInterval> = (fn, ms) => setInterval(fn, ms),
    private readonly config: { reconnectWindowMs?: number } = {},
  ) {}

  on<K extends EventName>(event: K, handler: Handler<SessionEvents[K]>): () => void {
    let set = this.handlers.get(event);
    if (!set) {
      set = new Set();
      this.handlers.set(event, set);
    }
    set.add(handler as Handler<never>);
    return () => set.delete(handler as Handler<never>);
  }

  private emit<K extends EventName>(event: K, payload: SessionEvents[K]): void {
    const set = this.handlers.get(event);
    if (set) for (const h of set) (h as Handler<SessionEvents[K]>)(payload);
  }

  /** 档位区间校验（spec：本地仅做区间校验，/16 对齐交由源端） */
  static validateProfile(p: VideoProfile): string | null {
    if (p.width < 320 || p.width > 2560) return `width 超出 [320,2560]：${p.width}`;
    if (p.height < 240 || p.height > 2560) return `height 超出 [240,2560]：${p.height}`;
    if (p.fps < 15 || p.fps > 60) return `fps 超出 [15,60]：${p.fps}`;
    if (p.bitrateKbps < 1000 || p.bitrateKbps > 20000) return `bitrateKbps 超出 [1000,20000]：${p.bitrateKbps}`;
    return null;
  }

  /**
   * 首次连接并建立会话（全流程；任一步显式失败即拒绝且不静默重试）。
   * @param wsUrl 形如 ws(s)://host:port/ws
   */
  async connect(opts: { wsUrl: string; token: string; mode: StartMode; profile?: VideoProfile }): Promise<void> {
    this.userClosed = false;
    const ws = this.wsFactory(opts.wsUrl);
    ws.binaryType = 'arraybuffer';
    this.ws = ws;
    this.bindSocket(ws);

    await new Promise<void>((resolve, reject) => {
      ws.onopen = () => resolve();
      ws.onerror = () => reject(new Error('WebSocket 连接失败（网关不可达）'));
    });

    this.emit('connecting', { attempt: 1 });
    this.startPingLoop();

    // ── HELLO：握手认证（占位参数；真实会话参数以 START_SESSION 应答为准）──
    const helloMsg: EncodableControlMessage = {
      kind: 'hello',
      version: 1,
      token: opts.token,
      mode: opts.mode.kind === 'attach' ? WatchMode.ATTACH : opts.mode.kind === 'singleApp' ? WatchMode.SINGLE_APP : WatchMode.FULLSCREEN,
      packageName: opts.mode.kind === 'singleApp' ? opts.mode.packageName : '',
      width: opts.profile?.width ?? 0,
      height: opts.profile?.height ?? 0,
      fps: opts.profile?.fps ?? 0,
      bitrateKbps: opts.profile?.bitrateKbps ?? 0,
      sessionId: opts.mode.kind === 'attach' ? opts.mode.sessionId : 0,
      p2pOffer: '',
      crypto: null,
    };
    const ack = await this.requestHello(helloMsg);
    if (ack.status !== HelloStatus.ACCEPTED) {
      this.failExplicit(ack);
      throw new SessionRejectedError(ack.status, ack.statusName, ack.reason);
    }
    this.emit('handshakeAccepted', undefined);

    // ── START_SESSION：建立（或附着）会话，HELLO_ACK 返回真实参数 ──
    await this.startOrSwitchSession(opts.mode, opts.profile);
  }

  /**
   * 发 START_SESSION 并等真实参数 HELLO_ACK（公开能力：连接内单应用切换 / 挂机会话
   * ATTACH 恢复；重连路径内部复用）。成功发 sessionStarted 事件（坐标基准换新信号）。
   */
  async startOrSwitchSession(mode: StartMode, profile?: VideoProfile): Promise<void> {
    const msg: EncodableControlMessage = {
      kind: 'startSession',
      sessionId: mode.kind === 'attach' ? mode.sessionId : 0,
      mode: mode.kind === 'attach' ? WatchMode.ATTACH : mode.kind === 'singleApp' ? WatchMode.SINGLE_APP : WatchMode.FULLSCREEN,
      packageName: mode.kind === 'singleApp' ? mode.packageName : '',
      width: profile?.width ?? 0,
      height: profile?.height ?? 0,
      fps: profile?.fps ?? 0,
      bitrateKbps: profile?.bitrateKbps ?? 0,
    };
    const ack = await this.requestStartSession(msg);
    if (ack.status !== HelloStatus.ACCEPTED) {
      this.failExplicit(ack);
      throw new SessionRejectedError(ack.status, ack.statusName, ack.reason);
    }
    this.currentSession = {
      sessionId: ack.sessionId,
      width: ack.width,
      height: ack.height,
      fps: ack.fps,
      bitrateKbps: ack.bitrateKbps,
      encoder: ack.encoder,
      displayId: ack.displayId,
    };
    this.emit('sessionStarted', this.currentSession);
  }

  /** HELLO_ACK 显式拒绝：停止一切自动重试，交由 UI 回连接页带原因 */
  private failExplicit(ack: Extract<ControlMessage, { kind: 'helloAck' }>): void {
    this.userClosed = true; // 停止重连循环
    this.clearTimers();
    this.emit('rejected', { status: ack.status, statusName: ack.statusName, reason: ack.reason });
  }

  private requestHello(msg: EncodableControlMessage): Promise<Extract<ControlMessage, { kind: 'helloAck' }>> {
    return new Promise((resolve, reject) => {
      this.helloWaiter = resolve;
      this.send(msg);
      this.scheduleTimer(() => reject(new Error('HELLO 应答超时（源端 500ms 内未回，链路异常）')), HELLO_TIMEOUT_MS);
    });
  }

  private requestStartSession(msg: EncodableControlMessage): Promise<Extract<ControlMessage, { kind: 'helloAck' }>> {
    return new Promise((resolve, reject) => {
      this.pendingStart = true;
      this.startWaiter = resolve;
      this.send(msg);
      this.scheduleTimer(() => {
        this.pendingStart = false;
        reject(new Error('START_SESSION 应答超时'));
      }, START_SESSION_TIMEOUT_MS);
    });
  }

  /** 拉取应用列表（请求-应答制；requestId 对账） */
  async listApps(): Promise<AppEntry[]> {
    if (!this.ws || this.ws.readyState !== 1) throw new Error('未连接');
    const requestId = ++this.appListSeq;
    const promise = new Promise<AppEntry[]>((resolve, reject) => {
      this.appListWaiters.set(requestId, (msg) => resolve(msg.apps));
      this.scheduleTimer(() => {
        if (this.appListWaiters.delete(requestId)) reject(new Error('APP_LIST 应答超时'));
      }, APP_LIST_TIMEOUT_MS);
    });
    this.send({ kind: 'appListRequest', requestId });
    return promise;
  }

  /** 挂机会话恢复入口数据（HELLO_ACK.hungSessions）——由事件消费方持有，这里不做状态存续 */
  lastHungSessions: HungSession[] = [];

  /** 发送输入事件（InputBridge 消费） */
  sendInputEvent(sessionId: number, action: number, pointerId: number, x: number, y: number): void {
    this.send({ kind: 'inputEvent', sessionId, action, pointerId, x, y });
  }

  /** 组合文本注入（kind=0，seq 自增；回执经 textInputAck 事件） */
  sendTextInput(text: string): number {
    const seq = ++this.textSeq;
    this.send({ kind: 'textInput', seq, text });
    return seq;
  }

  /** 写源端剪贴板 */
  sendClipboardSet(text: string): void {
    this.send({ kind: 'clipboardSet', text });
  }

  /** 读源端剪贴板（回执 clipboardText / auxNotice 事件） */
  sendClipboardGet(): void {
    this.send({ kind: 'clipboardGet' });
  }

  /** 结束会话（fire-and-forget，无响应消息） */
  stopSession(): void {
    if (this.currentSession) {
      this.send({ kind: 'stopSession', sessionId: this.currentSession.sessionId });
    }
  }

  /** 用户主动断开：不自动重连 */
  close(): void {
    this.userClosed = true;
    this.clearTimers();
    this.ws?.close(1000, 'client close');
    this.ws = null;
  }

  // ── 内部：控制记录收发 ──

  private send(msg: EncodableControlMessage): void {
    const ws = this.ws;
    if (!ws || ws.readyState !== 1) return;
    ws.send(encodeControlRecord(msg));
  }

  /** 接线 WS 事件（connect/reconnect 内部使用；测试替身直接接线） */
  bindSocket(ws: WebSocketLike): void {
    ws.onmessage = (ev) => {
      try {
        this.feed(new Uint8Array(ev.data));
      } catch (err) {
        if (err instanceof ProtocolError) {
          // 线路损坏：按断开处理走重连（协议层结构性错误不静默吞）
          this.emit('disconnected', { code: -1, reason: `协议错误：${err.message}` });
          ws.close(1011, 'protocol error');
        } else throw err;
      }
    };
    ws.onclose = (ev) => this.onWsClosed(ev.code, ev.reason);
    ws.onerror = () => {
      /* close 事件随后必然到达，交给 onclose */
    };
  }

  private feed(data: Uint8Array): void {
    for (const record of this.parser.feed(data)) {
      if (record.type === RECORD_VIDEO) {
        this.emit('videoRecord', record.payload);
        continue;
      }
      if (record.type !== RECORD_CONTROL) continue; // 未知记录类型整段跳过（容忍）
      const msg = decodeControlMessage(record.payload);
      this.dispatch(msg);
    }
  }

  private dispatch(msg: ControlMessage): void {
    switch (msg.kind) {
      case 'helloAck': {
        if (msg.status === HelloStatus.ACCEPTED) this.lastHungSessions = msg.hungSessions;
        if (this.pendingStart) {
          this.pendingStart = false;
          this.startWaiter?.(msg);
        } else {
          this.helloWaiter?.(msg);
        }
        break;
      }
      case 'appList': {
        const waiter = this.appListWaiters.get(msg.requestId);
        if (waiter) {
          this.appListWaiters.delete(msg.requestId);
          waiter(msg);
        }
        this.emit('appList', { requestId: msg.requestId, apps: msg.apps });
        break;
      }
      case 'audioChunk':
        this.emit('audioChunk', { ptsUs: msg.ptsUs, seq: msg.seq, payload: msg.payload });
        break;
      case 'stats':
        this.emit('stats', {
          sessionId: msg.sessionId,
          seq: msg.seq,
          fps: msg.fps,
          bitrateKbps: msg.bitrateKbps,
          rttMs: msg.rttMs,
          lossPct: msg.lossPct,
          transport: msg.transport,
        });
        break;
      case 'ping':
        // 应答源端 1Hz 入站 PING：Pong{nonce, senderUs, echoUs=nowUs}
        this.send({ kind: 'pong', nonce: msg.nonce, senderUs: msg.senderUs, echoUs: BigInt(this.nowUs()) });
        break;
      case 'pong': {
        // 自发 PING 的应答：RTT = t_recv − t_send；offset ≈ echoUs − (t_send+t_recv)/2
        const tSend = this.pendingPings.get(msg.nonce);
        if (tSend !== undefined) {
          this.pendingPings.delete(msg.nonce);
          const tRecv = this.nowUs();
          const rttUs = tRecv - tSend;
          const offsetUs = Number(msg.echoUs) - (tSend + tRecv) / 2;
          this.clock = { rttUs, offsetUs };
          this.lastPongAtMs = Date.now();
          this.emit('clock', { rttUs, offsetUs });
        }
        break;
      }
      case 'clipboardText':
        this.emit('clipboardText', { text: msg.text });
        break;
      case 'auxNotice':
        this.emit('auxNotice', { channel: msg.channel, reason: msg.reason });
        break;
      case 'textInputAck':
        this.emit('textInputAck', { seq: msg.seq, ok: msg.ok, reason: msg.reason });
        break;
      case 'unknown':
        this.emit('unknownMessage', { msgType: msg.unknownType });
        break;
      default:
        break; // startSession/stop 等上行方向消息不会入站；未知形态忽略
    }
  }

  private startPingLoop(): void {
    this.stopPingLoop();
    this.lastPongAtMs = Date.now();
    this.pingTimer = this.scheduleInterval(() => {
      const ws = this.ws;
      if (!ws || ws.readyState !== 1) return;
      const nonce = ++this.pingNonceSeq;
      const tSend = this.nowUs();
      this.pendingPings.set(nonce, tSend);
      this.send({ kind: 'ping', nonce, senderUs: BigInt(tSend) });
      if (Date.now() - this.lastPongAtMs > PONG_WATCHDOG_MS) {
        // 看门狗：同端 6s 无 Pong 判死 → 触发断开重连路径
        this.lastPongAtMs = Date.now(); // 防抖：同一条死通道只触发一次
        ws.close(4000, 'pong watchdog');
      }
    }, PING_INTERVAL_MS);
  }

  private stopPingLoop(): void {
    if (this.pingTimer) clearInterval(this.pingTimer);
    this.pingTimer = null;
  }

  private clearTimers(): void {
    this.stopPingLoop();
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  // ── 断线重连（ATTACH）──

  private lastConnect: { wsUrl: string; token: string; profile?: VideoProfile } | null = null;

  /** 记忆连接参数（connect 成功后自动记忆；重连 ATTACH 复用） */
  rememberConnect(opts: { wsUrl: string; token: string; profile?: VideoProfile }): void {
    this.lastConnect = opts;
  }

  private onWsClosed(code: number, reason: string): void {
    this.stopPingLoop();
    this.ws = null;
    this.pendingStart = false;
    if (this.userClosed) return;
    this.emit('disconnected', { code, reason });
    if (!this.lastConnect) {
      this.emit('reconnectFailed', { reason: '无连接记忆，无法自动重连' });
      return;
    }
    const disconnectedAt = Date.now();
    const attemptReconnect = () => {
      const windowMs = this.config.reconnectWindowMs ?? RECONNECT_WINDOW_MS;
      const elapsed = Date.now() - disconnectedAt;
      if (elapsed > windowMs) {
        this.emit('reconnectFailed', { reason: `断开超过 ${windowMs / 1000}s 重连窗口` });
        return;
      }
      const delayMs = Math.min(RECONNECT_BASE_MS * 2 ** this.reconnectAttempt, RECONNECT_MAX_DELAY_MS);
      this.reconnectAttempt += 1;
      this.emit('reconnectScheduled', { attempt: this.reconnectAttempt, delayMs });
      this.reconnectTimer = this.scheduleTimer(async () => {
        try {
          await this.reconnectOnce();
        } catch (err) {
          if (err instanceof SessionRejectedError) {
            // ATTACH 显式拒绝（REJECT_BUSY / REJECT_UNSUPPORTED_MODE / token 失效）：停止重试
            this.emit('reconnectFailed', { reason: `附着被拒绝（${err.statusName}）：${err.reason}` });
            return;
          }
          attemptReconnect();
        }
      }, delayMs);
    };
    attemptReconnect();
  }

  /** 单次重连：HELLO（同 token）→ START_SESSION{sessionId, mode=ATTACH}（不发 IDR_REQUEST） */
  private async reconnectOnce(): Promise<void> {
    const memo = this.lastConnect!;
    const sessionId = this.currentSession?.sessionId ?? 0;
    if (sessionId === 0) throw new Error('无会话可附着');
    const ws = this.wsFactory(memo.wsUrl);
    ws.binaryType = 'arraybuffer';
    this.ws = ws;
    this.bindSocket(ws);
    await new Promise<void>((resolve, reject) => {
      ws.onopen = () => resolve();
      ws.onerror = () => reject(new Error('重连失败（网关不可达）'));
    });
    this.emit('connecting', { attempt: this.reconnectAttempt + 1 });
    this.startPingLoop();
    const helloMsg: EncodableControlMessage = {
      kind: 'hello',
      version: 1,
      token: memo.token,
      mode: WatchMode.ATTACH,
      packageName: '',
      width: 0,
      height: 0,
      fps: 0,
      bitrateKbps: 0,
      sessionId,
      p2pOffer: '',
      crypto: null,
    };
    const ack = await this.requestHello(helloMsg);
    if (ack.status !== HelloStatus.ACCEPTED) {
      this.failExplicit(ack);
      throw new SessionRejectedError(ack.status, ack.statusName, ack.reason);
    }
    this.emit('handshakeAccepted', undefined);
    // 会话可能已换 sessionId（源端恢复）：清旧坐标基准由 sessionStarted 事件驱动
    await this.startOrSwitchSession({ kind: 'attach', sessionId }, memo.profile);
    this.reconnectAttempt = 0;
  }
}

/** 会话显式拒绝（REJECT_*） */
export class SessionRejectedError extends Error {
  constructor(public readonly status: number, public readonly statusName: string, public readonly reason: string) {
    super(`${statusName}: ${reason}`);
    this.name = 'SessionRejectedError';
  }
}

/** 构建 WS URL（同源网关：ws(s)://host:port/ws） */
export function buildWsUrl(loc: { protocol: string; host: string }): string {
  const scheme = loc.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${scheme}//${loc.host}/ws`;
}

// 供诊断：消息类型表导出再确认（防 tree-shake 误删常量引用）
export const SUPPORTED_MSG_TYPES = MsgType;
