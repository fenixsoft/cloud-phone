/**
 * SessionClient 单元测试（vitest，注入替身 WS / 手动时钟 / 手动定时器）：
 * 首连 HELLO→START_SESSION 全流程、显式拒绝不重试、PONG 时钟估计、
 * STATS/音频/剪贴板/文本回执消费、APP_LIST 对账、ATTACH 断线重连、超窗放弃。
 */
import { describe, expect, it, vi } from 'vitest';

import { encodeControlMessage, encodeControlRecord, decodeControlMessage, type ControlMessage } from '../src/protocol/Messages';
import { RECORD_HEADER_BYTES, encodeRecord } from '../src/protocol/Framing';
import { SessionClient, SessionRejectedError, type SessionEvents, type SessionParameters, type WebSocketLike } from '../src/net/SessionClient';

// ── 替身 ──

class FakeWebSocket implements WebSocketLike {
  binaryType = 'blob';
  readyState = 1;
  sent: Uint8Array[] = [];
  onopen: (() => void) | null = null;
  onclose: ((ev: { code: number; reason: string }) => void) | null = null;
  onerror: ((ev: unknown) => void) | null = null;
  onmessage: ((ev: { data: ArrayBuffer }) => void) | null = null;
  constructor(registry: FakeSocketRegistry) {
    registry.sockets.push(this);
  }
  send(data: ArrayBuffer | Uint8Array): void {
    this.sent.push(data instanceof Uint8Array ? data : new Uint8Array(data));
  }
  close(code?: number, reason?: string): void {
    this.onclose?.({ code: code ?? 1000, reason: reason ?? '' });
  }
  /** 测试侧：源端下行一条控制消息 */
  serverSend(msg: Parameters<typeof encodeControlMessage>[0]): void {
    const record = encodeControlRecord(msg as never);
    this.onmessage?.({ data: record.buffer.slice(record.byteOffset, record.byteOffset + record.byteLength) as ArrayBuffer });
  }
  /** 测试侧：源端下行一条 0x02 视频记录 */
  serverSendVideo(payload: Uint8Array): void {
    const record = encodeRecord(0x02, payload);
    this.onmessage?.({ data: record.buffer.slice(record.byteOffset, record.byteOffset + record.byteLength) as ArrayBuffer });
  }
}

class FakeSocketRegistry {
  sockets: FakeWebSocket[] = [];
  factory = (): FakeWebSocket => new FakeWebSocket(this);
  get last(): FakeWebSocket {
    return this.sockets[this.sockets.length - 1];
  }
}

/** 手动定时器：scheduleTimer 收集待触发函数，测试显式推进 */
class ManualTimers {
  private pending: { fn: () => void; at: number }[] = [];
  schedule = (fn: () => void, ms: number): ReturnType<typeof setTimeout> => {
    this.pending.push({ fn, at: Date.now() + ms });
    return 0 as unknown as ReturnType<typeof setTimeout>;
  };
  intervalFns: (() => void)[] = [];
  scheduleInterval = (fn: () => void): ReturnType<typeof setInterval> => {
    this.intervalFns.push(fn);
    return 0 as unknown as ReturnType<typeof setInterval>;
  };
  /** 触发所有到期的 setTimeout */
  flush(): void {
    const due = this.pending.splice(0);
    for (const p of due) p.fn();
  }
  get pendingCount(): number {
    return this.pending.length;
  }
}

/** 断言消息类型并收窄（expect 的 toBe 不参与 TS 收窄） */
/** 出站字节 = 完整记录（[type][len][payload]），剥头解码控制载荷 */
const ctl = (bytes: Uint8Array): ControlMessage => decodeControlMessage(bytes.subarray(RECORD_HEADER_BYTES));

function asKind<K extends ControlMessage['kind']>(msg: ControlMessage, kind: K): Extract<ControlMessage, { kind: K }> {
  if (msg.kind !== kind) throw new Error(`期望 ${kind}，实得 ${msg.kind}`);
  return msg as Extract<ControlMessage, { kind: K }>;
}

function acceptedAck(overrides: Partial<Parameters<typeof encodeControlMessage>[0] & { kind: 'helloAck' }> = {}): Parameters<typeof encodeControlMessage>[0] {
  return {
    kind: 'helloAck',
    status: 0,
    statusName: 'ACCEPTED',
    reason: '',
    sessionId: 0,
    width: 0,
    height: 0,
    fps: 0,
    bitrateKbps: 0,
    encoder: '',
    displayId: 0,
    p2pAnswer: '',
    hungSessions: [],
    crypto: null,
    ...overrides,
  } as Parameters<typeof encodeControlMessage>[0];
}

function makeClient(registry: FakeSocketRegistry, timers: ManualTimers, opts: { nowSeq?: number[]; reconnectWindowMs?: number } = {}) {
  let nowIdx = 0;
  const nowSeq = opts.nowSeq;
  const client = new SessionClient(
    registry.factory,
    () => (nowSeq ? (nowIdx < nowSeq.length ? nowSeq[nowIdx++] : nowSeq[nowSeq.length - 1]) : 1000),
    timers.schedule,
    timers.scheduleInterval,
    { reconnectWindowMs: opts.reconnectWindowMs },
  );
  return client;
}

function collect<K extends keyof SessionEvents>(client: SessionClient, event: K): SessionEvents[K][] {
  const bag: SessionEvents[K][] = [];
  client.on(event, (p) => bag.push(p));
  return bag;
}

describe('SessionClient 首连与显式失败', () => {
  it('HELLO→START_SESSION 全流程：sessionStarted 采用真实参数', async () => {
    const registry = new FakeSocketRegistry();
    const timers = new ManualTimers();
    const client = makeClient(registry, timers);
    const started: SessionParameters[] = collect(client, 'sessionStarted');

    const promise = client.connect({
      wsUrl: 'ws://gw/ws',
      token: 'tok',
      mode: { kind: 'fullscreen' },
      profile: { width: 1280, height: 720, fps: 60, bitrateKbps: 8000 },
    });
    const ws = registry.last;
    ws.onopen!();
    await Promise.resolve();
    // 第一条出站 = HELLO（p2pOffer 空串 = 无加密块）
    const hello = asKind(ctl(ws.sent[0]), 'hello');
    expect(hello.token).toBe('tok');
    expect(hello.p2pOffer).toBe('');
    ws.serverSend(acceptedAck()); // 握手 ACK（占位参数）
    await Promise.resolve();
    await Promise.resolve();
    // 第二条出站 = START_SESSION（宽高/帧率/码率随档位）
    const start = asKind(ctl(ws.sent[1]), 'startSession');
    expect(start).toMatchObject({ sessionId: 0, mode: 1, width: 1280, height: 720, fps: 60, bitrateKbps: 8000 });
    ws.serverSend(
      acceptedAck({
        sessionId: 7,
        width: 1080,
        height: 2400,
        fps: 60,
        bitrateKbps: 16000,
        encoder: 'c2.android.avc.encoder',
        displayId: 0,
      }),
    );
    await promise;
    expect(started).toHaveLength(1);
    expect(started[0]).toEqual({
      sessionId: 7,
      width: 1080,
      height: 2400,
      fps: 60,
      bitrateKbps: 16000,
      encoder: 'c2.android.avc.encoder',
      displayId: 0,
    });
    expect(client.currentSession?.sessionId).toBe(7);
    client.close();
  });

  it('握手 ACK 的 hungSessions 不被 START_SESSION 参数应答覆写（挂机恢复入口，web-hang-attach-resume 回归）', async () => {
    const registry = new FakeSocketRegistry();
    const timers = new ManualTimers();
    const client = makeClient(registry, timers);

    const promise = client.connect({
      wsUrl: 'ws://gw/ws',
      token: 'tok',
      mode: { kind: 'fullscreen' },
      profile: { width: 1280, height: 720, fps: 60, bitrateKbps: 8000 },
    });
    const ws = registry.last;
    ws.onopen!();
    await Promise.resolve();
    // 握手 ACK：占位参数 + 非空挂机清单（design.md D8.2：重连页据此展示恢复入口）
    ws.serverSend(
      acceptedAck({ hungSessions: [{ sessionId: 5, mode: 1, packageName: 'com.demo.app', width: 720, height: 1280 }] }),
    );
    await Promise.resolve();
    await Promise.resolve();
    // START_SESSION 参数应答：仅真实会话参数，源端省略挂机清单（解码缺省空表，design.md D8.3）
    ws.serverSend(
      acceptedAck({ sessionId: 7, width: 1280, height: 720, fps: 60, bitrateKbps: 8000, encoder: 'enc', displayId: 0, hungSessions: [] }),
    );
    await promise;
    // 挂机清单保持握手 ACK 的值——被参数应答覆写清空即恢复入口恒缺
    expect(client.lastHungSessions).toEqual([{ sessionId: 5, mode: 1, packageName: 'com.demo.app', width: 720, height: 1280 }]);
    client.close();
  });

  it('HELLO REJECT_BAD_TOKEN：显式失败抛 SessionRejectedError，不排重连', async () => {
    const registry = new FakeSocketRegistry();
    const timers = new ManualTimers();
    const client = makeClient(registry, timers);
    const rejected = collect(client, 'rejected');

    const promise = client.connect({ wsUrl: 'ws://gw/ws', token: 'bad', mode: { kind: 'fullscreen' } });
    const ws = registry.last;
    ws.onopen!();
    await Promise.resolve();
    ws.serverSend(acceptedAck({ status: 1, statusName: 'REJECT_BAD_TOKEN', reason: 'token 不匹配' }));
    await expect(promise).rejects.toThrow(SessionRejectedError);
    expect(rejected).toEqual([{ status: 1, statusName: 'REJECT_BAD_TOKEN', reason: 'token 不匹配' }]);
    expect(collect(client, 'reconnectScheduled')).toHaveLength(0); // 无重连排程
    client.close();
  });

  it('START_SESSION REJECT_BUSY：显式失败', async () => {
    const registry = new FakeSocketRegistry();
    const timers = new ManualTimers();
    const client = makeClient(registry, timers);
    const promise = client.connect({ wsUrl: 'ws://gw/ws', token: 'tok', mode: { kind: 'fullscreen' } });
    const ws = registry.last;
    ws.onopen!();
    await Promise.resolve();
    ws.serverSend(acceptedAck());
    await Promise.resolve();
    await Promise.resolve();
    ws.serverSend(acceptedAck({ status: 2, statusName: 'REJECT_BUSY', reason: '观看端占用' }));
    await expect(promise).rejects.toThrow(SessionRejectedError);
    client.close();
  });

  it('WS 在 HELLO_ACK 前关闭（网关上游拒连 1011）：connect() 显式失败不悬挂（BUG-01 回归）', async () => {
    const registry = new FakeSocketRegistry();
    const timers = new ManualTimers();
    const client = makeClient(registry, timers);
    client.rememberConnect({ wsUrl: 'ws://gw/ws', token: 'tok' }); // 首连即记忆（main.ts doConnectOnce 同序）
    const failed = collect(client, 'reconnectFailed');
    const promise = client.connect({ wsUrl: 'ws://gw/ws', token: 'tok', mode: { kind: 'fullscreen' } });
    const ws = registry.last;
    ws.onopen!();
    await Promise.resolve();
    expect(asKind(ctl(ws.sent[0]), 'hello').token).toBe('tok'); // HELLO 已发出、ACK 未到
    ws.close(1011, 'upstream gone'); // 网关因上游拒连关链（server.js WS_CLOSE_UPSTREAM_GONE）
    // 修复前：onWsClosed 只清超时定时器、不拒绝挂起等待者 → 此 await 永悬挂（连接按钮永久 disabled）
    await expect(promise).rejects.toThrow('会话尚未建立，无法自动重连');
    expect(failed).toHaveLength(1);
    expect(failed[0].reason).toBe('会话尚未建立，无法自动重连'); // 与 reconnectFailed 收尾同口径
    expect(client['helloWaiter']).toBeNull(); // 等待者已落定清理，不残留悬挂态
  });

  it('WS 在 START_SESSION 应答前关闭：connect() 显式失败不悬挂', async () => {
    const registry = new FakeSocketRegistry();
    const timers = new ManualTimers();
    const client = makeClient(registry, timers);
    const promise = client.connect({ wsUrl: 'ws://gw/ws', token: 'tok', mode: { kind: 'fullscreen' } });
    const ws = registry.last;
    ws.onopen!();
    await Promise.resolve();
    ws.serverSend(acceptedAck()); // 握手 ACK（占位参数）
    await Promise.resolve();
    await Promise.resolve();
    expect(asKind(ctl(ws.sent[1]), 'startSession')).toMatchObject({ mode: 1 }); // START_SESSION 在飞
    ws.close(1011, 'upstream gone'); // 应答未到即断
    await expect(promise).rejects.toThrow('连接已关闭');
    expect(client['startWaiter']).toBeNull();
  });

  it('会话中切换在飞时连接关闭：startOrSwitchSession 显式失败不悬挂', async () => {
    const registry = new FakeSocketRegistry();
    const timers = new ManualTimers();
    const client = makeClient(registry, timers);
    const promise = client.connect({ wsUrl: 'ws://gw/ws', token: 'tok', mode: { kind: 'fullscreen' } });
    const ws = registry.last;
    ws.onopen!();
    await Promise.resolve();
    ws.serverSend(acceptedAck());
    await Promise.resolve();
    await Promise.resolve();
    ws.serverSend(acceptedAck({ sessionId: 7, width: 720, height: 1280, fps: 60, bitrateKbps: 8000, encoder: 'enc', displayId: 0 }));
    await promise; // 会话已建立（currentSession 就位）
    const sw = client.startOrSwitchSession({ kind: 'singleApp', packageName: 'com.example.app' });
    await Promise.resolve();
    ws.close(1011, 'upstream gone');
    await expect(sw).rejects.toThrow('HELLO/START_SESSION 应答未达');
  });

  it('档位区间校验：越界档位本地拒绝', () => {
    expect(SessionClient.validateProfile({ width: 200, height: 720, fps: 60, bitrateKbps: 8000 })).toContain('width');
    expect(SessionClient.validateProfile({ width: 1280, height: 720, fps: 90, bitrateKbps: 8000 })).toContain('fps');
    expect(SessionClient.validateProfile({ width: 1280, height: 720, fps: 60, bitrateKbps: 50000 })).toContain('bitrateKbps');
    expect(SessionClient.validateProfile({ width: 1280, height: 720, fps: 60, bitrateKbps: 8000 })).toBeNull();
  });
});

describe('控制面：PING/PONG 时钟估计与数据消费', () => {
  it('应答源端入站 PING：Pong{nonce, senderUs, echoUs=nowUs}', () => {
    const registry = new FakeSocketRegistry();
    const timers = new ManualTimers();
    const client = makeClient(registry, timers, { nowSeq: [777] });
    const ws = new FakeWebSocket(registry);
    client['ws'] = ws;
    client['bindSocket'](ws);
    ws.serverSend({ kind: 'ping', nonce: 0x1122334455667788n, senderUs: 1000000123n });
    const pong = asKind(ctl(ws.sent[0]), 'pong');
    expect(pong.nonce).toBe(0x1122334455667788n);
    expect(pong.senderUs).toBe(1000000123n);
    expect(pong.echoUs).toBe(777n);
    client.close();
  });

  it('自发 PING→PONG：RTT=t_recv−t_send，offset=echoUs−(t_send+t_recv)/2', () => {
    const registry = new FakeSocketRegistry();
    const timers = new ManualTimers();
    // now 序列：t_send=1000 →（远端应答）→ t_recv=2000
    const client = makeClient(registry, timers, { nowSeq: [1000, 2000] });
    const clockBag = collect(client, 'clock');
    const ws = new FakeWebSocket(registry);
    client['ws'] = ws;
    client['bindSocket'](ws);
    client['startPingLoop']();
    // 触发一次 ping tick
    timers.intervalFns[0]();
    const ping = asKind(ctl(ws.sent[0]), 'ping');
    expect(ping.senderUs).toBe(1000n);
    // 源端回 PONG：nonce 对上，echoUs = 源端钟 5000
    ws.serverSend({ kind: 'pong', nonce: ping.nonce, senderUs: ping.senderUs, echoUs: 5000n });
    expect(clockBag).toHaveLength(1);
    expect(clockBag[0].rttUs).toBe(1000); // 2000 − 1000
    expect(clockBag[0].offsetUs).toBe(3500); // 5000 − (1000+2000)/2
    client.close();
  });

  it('STATS / audioChunk / videoRecord 事件透传', () => {
    const registry = new FakeSocketRegistry();
    const timers = new ManualTimers();
    const client = makeClient(registry, timers);
    const statsBag = collect(client, 'stats');
    const audioBag = collect(client, 'audioChunk');
    const videoBag = collect(client, 'videoRecord');
    const ws = new FakeWebSocket(registry);
    client['ws'] = ws;
    client['bindSocket'](ws);
    ws.serverSend({ kind: 'stats', sessionId: 7, seq: 1, fps: 59.8, bitrateKbps: 7950, rttMs: 12.5, lossPct: 0, transport: 0 });
    ws.serverSend({ kind: 'audioChunk', ptsUs: 123456789n, seq: 9, payload: new Uint8Array([1, 2]) });
    ws.serverSendVideo(new Uint8Array([0xaa, 0xbb]));
    // STATS 浮点按 f32 线路量化（fps/码率/rtt/loss 均为 f32 编码）
    expect(statsBag[0]).toMatchObject({ sessionId: 7, fps: Math.fround(59.8), bitrateKbps: 7950, rttMs: 12.5 });
    expect(audioBag[0]).toMatchObject({ ptsUs: 123456789n, seq: 9 });
    expect(Array.from(videoBag[0])).toEqual([0xaa, 0xbb]);
    client.close();
  });

  it('APP_LIST 请求-应答对账；textInputAck / clipboardText / auxNotice 事件', async () => {
    const registry = new FakeSocketRegistry();
    const timers = new ManualTimers();
    const client = makeClient(registry, timers);
    const textBag = collect(client, 'textInputAck');
    const clipBag = collect(client, 'clipboardText');
    const auxBag = collect(client, 'auxNotice');
    const ws = new FakeWebSocket(registry);
    client['ws'] = ws;
    client['bindSocket'](ws);

    const appsPromise = client.listApps();
    await Promise.resolve();
    const req = asKind(ctl(ws.sent[0]), 'appListRequest');
    ws.serverSend({
      kind: 'appList',
      requestId: req.requestId,
      apps: [{ packageName: 'com.a', label: 'A' }],
    });
    await expect(appsPromise).resolves.toHaveLength(1);

    const seq = client.sendTextInput('你好');
    expect(seq).toBe(1);
    ws.serverSend({ kind: 'textInputAck', seq: 1, ok: false, reason: '无焦点窗口' });
    expect(textBag[0]).toMatchObject({ seq: 1, ok: false, reason: '无焦点窗口' });

    client.sendClipboardGet();
    ws.serverSend({ kind: 'clipboardText', text: 'src-clip' });
    expect(clipBag[0]?.text).toBe('src-clip');
    ws.serverSend({ kind: 'auxNotice', channel: 2, reason: '设备多窗口模式' });
    expect(auxBag[0]).toMatchObject({ channel: 2, reason: '设备多窗口模式' });
    client.close();
  });
});

describe('断线重连（ATTACH）', () => {
  async function establish(registry: FakeSocketRegistry, _timers: ManualTimers, client: SessionClient): Promise<FakeWebSocket> {
    const promise = client.connect({
      wsUrl: 'ws://gw/ws',
      token: 'tok',
      mode: { kind: 'fullscreen' },
      profile: { width: 1280, height: 720, fps: 60, bitrateKbps: 8000 },
    });
    const ws = registry.last;
    ws.onopen!();
    await Promise.resolve();
    ws.serverSend(acceptedAck());
    await Promise.resolve();
    await Promise.resolve();
    ws.serverSend(acceptedAck({ sessionId: 42, width: 1280, height: 720, fps: 60, bitrateKbps: 8000, encoder: 'enc', displayId: 0 }));
    await promise;
    return ws;
  }

  it('意外断开→退避重连→START_SESSION{sessionId, mode=ATTACH}，不发 IDR_REQUEST', async () => {
    const registry = new FakeSocketRegistry();
    const timers = new ManualTimers();
    const client = makeClient(registry, timers);
    client.rememberConnect({ wsUrl: 'ws://gw/ws', token: 'tok', profile: { width: 1280, height: 720, fps: 60, bitrateKbps: 8000 } });
    const first = await establish(registry, timers, client);
    const scheduled = collect(client, 'reconnectScheduled');
    const started: SessionParameters[] = collect(client, 'sessionStarted');

    const dateSpy = vi.spyOn(Date, 'now').mockReturnValue(1_000_000);
    first.close(1006, 'upstream gone');
    // 首次退避 500ms 排程
    expect(scheduled).toEqual([{ attempt: 1, delayMs: 500 }]);
    timers.flush(); // 触发重连：新 WS 打开 + 握手 ACK + START_SESSION(ATTACH) ACK
    const ws2 = registry.last;
    expect(ws2).not.toBe(first);
    ws2.onopen!();
    await Promise.resolve();
    const hello2 = asKind(ctl(ws2.sent[0]), 'hello');
    expect(hello2.mode).toBe(3); // ATTACH
    ws2.serverSend(acceptedAck());
    await Promise.resolve();
    await Promise.resolve();
    const start2 = asKind(ctl(ws2.sent[1]), 'startSession');
    expect(start2).toMatchObject({ sessionId: 42, mode: 3 });
    // 全程无 IDR_REQUEST（源端附着自动触发关键帧）
    const sentKinds = ws2.sent.map((r) => ctl(r).kind);
    expect(sentKinds.includes('idrRequest')).toBe(false); // 无 IDR_REQUEST（源端附着自动触发关键帧）
    ws2.serverSend(acceptedAck({ sessionId: 42, width: 1280, height: 720, fps: 60, bitrateKbps: 8000, encoder: 'enc', displayId: 0 }));
    await Promise.resolve();
    await new Promise((r) => setTimeout(r, 0));
    // 收集器在 establish 之后注册：仅捕获重连后的 sessionStarted（坐标基准换新信号）
    expect(started).toHaveLength(1);
    expect(started[0]).toEqual({
      sessionId: 42,
      width: 1280,
      height: 720,
      fps: 60,
      bitrateKbps: 8000,
      encoder: 'enc',
      displayId: 0,
    });
    expect(client.currentSession?.sessionId).toBe(42);
    dateSpy.mockRestore();
    client.close();
  });

  it('重连链：握手 ACK 刷新的挂机清单不被 ATTACH 参数应答覆写（web-hang-attach-resume 回归）', async () => {
    const registry = new FakeSocketRegistry();
    const timers = new ManualTimers();
    const client = makeClient(registry, timers);
    client.rememberConnect({ wsUrl: 'ws://gw/ws', token: 'tok', profile: { width: 1280, height: 720, fps: 60, bitrateKbps: 8000 } });
    const first = await establish(registry, timers, client);
    const dateSpy = vi.spyOn(Date, 'now').mockReturnValue(1_000_000);
    first.close(1006, 'upstream gone');
    timers.flush(); // 触发重连：新 WS + HELLO + ATTACH
    const ws2 = registry.last;
    expect(ws2).not.toBe(first);
    ws2.onopen!();
    await Promise.resolve();
    // 重连握手 ACK：源端挂机表含原会话 #42
    ws2.serverSend(acceptedAck({ hungSessions: [{ sessionId: 42, mode: 1, packageName: '', width: 1280, height: 720 }] }));
    await Promise.resolve();
    await Promise.resolve();
    // ATTACH 参数应答：省略挂机清单——不得覆写握手 ACK 的清单
    ws2.serverSend(acceptedAck({ sessionId: 42, width: 1280, height: 720, fps: 60, bitrateKbps: 8000, encoder: 'enc', displayId: 0 }));
    await new Promise((r) => setTimeout(r, 0));
    expect(client.currentSession?.sessionId).toBe(42);
    expect(client.lastHungSessions).toEqual([{ sessionId: 42, mode: 1, packageName: '', width: 1280, height: 720 }]);
    dateSpy.mockRestore();
    client.close();
  });

  it('ATTACH 被 REJECT_BUSY 显式拒绝：reconnectFailed 停止重试', async () => {
    const registry = new FakeSocketRegistry();
    const timers = new ManualTimers();
    const client = makeClient(registry, timers);
    client.rememberConnect({ wsUrl: 'ws://gw/ws', token: 'tok' });
    await establish(registry, timers, client);
    const failed = collect(client, 'reconnectFailed');
    const dateSpy = vi.spyOn(Date, 'now').mockReturnValue(1_000_000);
    registry.last.close(1006, 'gone');
    timers.flush(); // 触发重连
    const ws2 = registry.last;
    ws2.onopen!();
    await Promise.resolve();
    ws2.serverSend(acceptedAck());
    await Promise.resolve();
    await Promise.resolve();
    ws2.serverSend(acceptedAck({ status: 2, statusName: 'REJECT_BUSY', reason: '会话已被接管' }));
    await new Promise((r) => setTimeout(r, 0));
    expect(failed).toHaveLength(1);
    expect(failed[0].reason.includes('REJECT_BUSY')).toBe(true);
    expect(collect(client, 'reconnectScheduled')).toHaveLength(0); // 显式拒绝后不再排程重连
    dateSpy.mockRestore();
    client.close();
  });

  it('超 30s 窗口：reconnectFailed 放弃', async () => {
    const registry = new FakeSocketRegistry();
    const timers = new ManualTimers();
    const client = makeClient(registry, timers, { reconnectWindowMs: 1000 });
    client.rememberConnect({ wsUrl: 'ws://gw/ws', token: 'tok' });
    await establish(registry, timers, client);
    const failed = collect(client, 'reconnectFailed');
    let fakeNow = 1_000_000;
    const spy = vi.spyOn(Date, 'now').mockImplementation(() => fakeNow);
    registry.last.close(1006, 'gone');
    timers.flush(); // 第一次重连尝试：WS 打不开
    registry.last.onerror?.(null); // reconnectOnce 失败 → 再排程（此时已近窗口边界）
    fakeNow += 5_000; // 推进超过窗口
    timers.flush();
    await new Promise((r) => setTimeout(r, 0));
    expect(failed).toHaveLength(1);
    expect(failed[0].reason).toContain('重连窗口');
    spy.mockRestore();
    client.close();
  });

  it('指数退避：500ms 起步倍增封顶 5s', async () => {
    const registry = new FakeSocketRegistry();
    const timers = new ManualTimers();
    const client = makeClient(registry, timers);
    client.rememberConnect({ wsUrl: 'ws://gw/ws', token: 'tok' });
    await establish(registry, timers, client);
    const scheduled = collect(client, 'reconnectScheduled');
    let fakeNow = 1_000_000;
    const spy = vi.spyOn(Date, 'now').mockImplementation(() => fakeNow);
    // 反复断开（重连的 WS 打开即关，模拟网关不可达）
    registry.last.close(1006, 'gone');
    const expected = [500, 1000, 2000, 4000, 5000, 5000];
    for (let i = 0; i < expected.length - 1; i++) { // 首个 500ms 由 close 直接排程
      timers.flush();
      timers.flush();
      // 重连尝试的 WS 打不开（onerror 拒绝 reconnectOnce；不重复触发 onclose 防双排程）
      registry.last.onerror?.(null);
      fakeNow += 10; // 时间基本不流动，主要验证倍增序列
      await new Promise((r) => setTimeout(r, 0));
    }
    expect(scheduled.map((s) => s.delayMs)).toEqual(expected);
    spy.mockRestore();
    client.close();
  });
});
