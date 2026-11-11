/**
 * 页面编排（任务 6.2）：连接页（?token= → localStorage 可清除；档位 720p/1080p×
 * 30/60×4/8/16/20Mbps 默认 720p60/8M，区间校验）→ 应用网格（APP_LIST + 挂机恢复）
 * → 全屏 canvas + 悬浮工具条 + HUD；不支持 WebCodecs → 含浏览器名的错误页；
 * 断线 → toast 重连中；重连失败/显式拒绝 → 回连接页带原因。
 */

import { AudioPipeline } from './media/AudioPipeline';
import { VideoPipeline, VideoUnsupportedError, browserName, probeVideoSupport } from './media/VideoPipeline';
import { SessionClient, SessionRejectedError, buildWsUrl, type AppEntry, type HungSession, type SessionParameters, type StartMode, type VideoProfile } from './net/SessionClient';
import { InputBridge } from './input/InputBridge';
import { Hud } from './ui/Hud';

// ── DOM 句柄 ──

const $ = <T extends HTMLElement = HTMLElement>(id: string): T => {
  const el = document.getElementById(id);
  if (!el) throw new Error(`页面缺少 #${id}`);
  return el as T;
};

const connectPage = $('connect-page');
const appsPage = $('apps-page');
const sessionPage = $('session-page');
const connectForm = $<HTMLFormElement>('connect-form');
const tokenInput = $<HTMLInputElement>('token-input');
const profileResolution = $<HTMLSelectElement>('profile-resolution');
const profileFps = $<HTMLSelectElement>('profile-fps');
const profileBitrate = $<HTMLSelectElement>('profile-bitrate');
const connectError = $('connect-error');
const connectBtn = $('connect-btn');
const forgetTokenBtn = $('forget-token-btn');
const appGrid = $('app-grid');
const hungSessionsBox = $('hung-sessions');
const logoutBtn = $('logout-btn');
const canvas = $<HTMLCanvasElement>('video-canvas');
const btnBack = $('btn-back');
const btnClipRead = $('btn-clip-read');
const btnClipWrite = $('btn-clip-write');
const btnAudio = $('btn-audio');
const btnEnd = $('btn-end');
const textInput = $<HTMLInputElement>('text-input');
const toast = $('toast');

const TOKEN_KEY = 'cloudphone.web.token';

/** 浏览器 WebSocket → SessionClient.WebSocketLike 适配（事件载荷收窄为最小结构） */
function wsFactory(url: string): import('./net/SessionClient').WebSocketLike {
  const ws = new WebSocket(url);
  return {
    send: (data) => ws.send(data),
    close: (code, reason) => ws.close(code, reason),
    get readyState() {
      return ws.readyState;
    },
    get binaryType() {
      return ws.binaryType;
    },
    set binaryType(v: string) {
      ws.binaryType = v === 'arraybuffer' ? 'arraybuffer' : 'blob';
    },
    get onopen() {
      return ws.onopen as (() => void) | null;
    },
    set onopen(h: (() => void) | null) {
      ws.onopen = h as ((this: WebSocket, ev: Event) => unknown) | null;
    },
    get onclose() {
      return ws.onclose as ((ev: { code: number; reason: string }) => void) | null;
    },
    set onclose(h: ((ev: { code: number; reason: string }) => void) | null) {
      ws.onclose = h
        ? (ev) => h({ code: ev.code, reason: ev.reason })
        : null;
    },
    get onerror() {
      return ws.onerror as ((ev: unknown) => void) | null;
    },
    set onerror(h: ((ev: unknown) => void) | null) {
      ws.onerror = h as ((this: WebSocket, ev: Event) => unknown) | null;
    },
    get onmessage() {
      return ws.onmessage as ((ev: { data: ArrayBuffer }) => void) | null;
    },
    set onmessage(h: ((ev: { data: ArrayBuffer }) => void) | null) {
      ws.onmessage = h
        ? (ev) => {
            if (ev.data instanceof ArrayBuffer) h({ data: ev.data });
          }
        : null;
    },
  };
}

// ── 档位（默认 720p60 / 8Mbps；区间校验由 SessionClient.validateProfile 承担）──

const RESOLUTIONS: Record<string, { width: number; height: number }> = {
  '720p': { width: 720, height: 1280 },
  '1080p': { width: 1080, height: 2400 },
};

function currentProfile(): VideoProfile {
  const res = RESOLUTIONS[profileResolution.value] ?? RESOLUTIONS['720p'];
  return {
    width: res.width,
    height: res.height,
    fps: Number(profileFps.value),
    bitrateKbps: Number(profileBitrate.value),
  };
}

function showError(msg: string): void {
  connectError.textContent = msg;
  connectError.hidden = !msg;
}

let toastTimer: ReturnType<typeof window.setTimeout> | null = null;
function showToast(text: string): void {
  toast.textContent = text;
  toast.hidden = false;
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => {
    toast.hidden = true;
  }, 3000);
}

/** 单页切换 */
function show(page: 'connect' | 'apps' | 'session'): void {
  connectPage.hidden = page !== 'connect';
  appsPage.hidden = page !== 'apps';
  sessionPage.hidden = page !== 'session';
}

// ── token（URL ?token= 优先写入 localStorage，可清除；不在地址栏滞留）──

function loadToken(): string {
  const url = new URL(window.location.href);
  const fromUrl = url.searchParams.get('token');
  if (fromUrl) {
    localStorage.setItem(TOKEN_KEY, fromUrl);
    url.searchParams.delete('token');
    history.replaceState(null, '', url.toString());
    return fromUrl;
  }
  return localStorage.getItem(TOKEN_KEY) ?? '';
}

forgetTokenBtn.addEventListener('click', () => {
  localStorage.removeItem(TOKEN_KEY);
  tokenInput.value = '';
  showError('已清除本机保存的 token');
});

// ── 全局对象 ──

let video: VideoPipeline | null = null;
let audio: AudioPipeline | null = null;
let session: SessionClient | null = null;
let bridge: InputBridge | null = null;
let hudCtl: Hud | null = null;
let currentParams: SessionParameters | null = null;
let lastClockOffsetUs = 0;

function teardownSessionObjects(): void {
  bridge?.dispose();
  bridge = null;
  session?.close();
  session = null;
  video?.close();
  video = null;
  audio?.close();
  audio = null;
  currentParams = null;
}

/** 音频可用性（任务 4.2 UI：不可用置灰 + 原因提示） */
function applyAudioAvailability(): void {
  if (!audio) return;
  const support = audio.checkSupport();
  if (!support.supported) {
    btnAudio.setAttribute('disabled', 'true');
    btnAudio.title = support.reason;
    showToast(support.reason);
  } else {
    btnAudio.removeAttribute('disabled');
    btnAudio.title = '音频开关';
  }
}

/** 回连接页（断线超窗/显式拒绝/主动结束） */
function backToConnect(reason: string): void {
  teardownSessionObjects();
  show('connect');
  if (reason) showError(reason);
}

/** 应用网格渲染（APP_LIST + 挂机会话恢复入口） */
function renderApps(apps: AppEntry[], hung: HungSession[]): void {
  appGrid.innerHTML = '';
  for (const app of apps) {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = 'app-item';
    item.textContent = app.label || app.packageName;
    item.addEventListener('click', () => void startSingleApp(app.packageName));
    appGrid.appendChild(item);
  }
  hungSessionsBox.innerHTML = '';
  for (const h of hung) {
    const item = document.createElement('button');
    item.type = 'button';
    item.className = 'hung-item';
    item.textContent = `恢复会话 #${h.sessionId}`;
    item.addEventListener('click', () => void switchSession({ kind: 'attach', sessionId: h.sessionId }));
    hungSessionsBox.appendChild(item);
  }
  show('apps');
}

/** 连接内切换：单应用进入 / ATTACH 恢复（START_SESSION，无需重握手） */
async function switchSession(mode: StartMode): Promise<void> {
  if (!session) return;
  try {
    await session.startOrSwitchSession(mode, currentParams ?? undefined);
    show('session');
    sizeCanvas();
  } catch (err) {
    if (err instanceof SessionRejectedError) backToConnect(`会话切换被拒绝（${err.statusName}）：${err.reason}`);
    else backToConnect((err as Error).message);
  }
}

async function startSingleApp(packageName: string): Promise<void> {
  await switchSession({ kind: 'singleApp', packageName });
}



/** canvas 尺寸 = 会话分辨率（letterbox 由 CSS object-fit 语义 + 映射共同承担） */
function sizeCanvas(): void {
  if (!currentParams) return;
  canvas.width = currentParams.width;
  canvas.height = currentParams.height;
}

// ── 会话事件绑定（HUD/视频/音频/输入/断线）──

function bindSessionEvents(s: SessionClient, profile: VideoProfile): void {
  hudCtl = new Hud({
    fps: $('hud-fps'),
    bitrate: $('hud-bitrate'),
    rtt: $('hud-rtt'),
    latencyP50: $('hud-latency-p50'),
    latencyP95: $('hud-latency-p95'),
    decodeQueue: $('hud-queue'),
    dropped: $('hud-dropped'),
    sampleToggle: $('hud-sample-toggle'),
    sampleBadge: $('hud-sample-badge'),
  });
  hudCtl.sessionProfile = profile;

  s.on('sessionStarted', (params) => {
    currentParams = params;
    sizeCanvas();
    bridge?.onSessionParameters(params.width, params.height, params.sessionId);
    hudCtl?.clearSamples();
  });

  s.on('videoRecord', (payload) => {
    try {
      video?.feedRecord(payload);
    } catch (err) {
      console.warn('视频记录异常（帧丢弃）', err);
    }
  });

  s.on('audioChunk', ({ ptsUs, seq, payload }) => {
    audio?.feedChunk(ptsUs, seq, payload);
  });

  s.on('clock', ({ rttUs, offsetUs }) => {
    lastClockOffsetUs = offsetUs;
    hudCtl?.onClockSample(rttUs);
  });

  s.on('stats', (st) => {
    const counters = video?.counters();
    hudCtl?.update({
      fps: st.fps,
      bitrateKbps: st.bitrateKbps,
      rttMs: hudCtl?.rttMs() || st.rttMs,
      decodeQueueSize: counters?.decodeQueueSize ?? 0,
      dropped: (counters?.droppedSeqGap ?? 0) + (counters?.droppedPremature ?? 0),
      audioSkipCount: audio?.stats().skipCount,
    });
  });

  s.on('clipboardText', ({ text }) => void bridge?.onClipboardText(text));
  s.on('auxNotice', ({ channel, reason }) => bridge?.onAuxNotice(channel, reason));
  s.on('textInputAck', ({ seq, ok, reason }) => bridge?.onTextInputAck(seq, ok, reason));

  s.on('disconnected', ({ code, reason }) => {
    showToast(`连接断开（code=${code}），自动重连中…${reason ? ` ${reason}` : ''}`);
  });

  s.on('reconnectScheduled', ({ attempt }) => {
    showToast(`第 ${attempt} 次重连尝试…`);
  });

  s.on('reconnectFailed', ({ reason }) => backToConnect(reason));
}

// ── 连接主流程 ──

connectForm.addEventListener('submit', (ev) => {
  ev.preventDefault();
  void doConnect();
});

async function doConnect(): Promise<void> {
  showError('');
  const token = tokenInput.value.trim();
  if (!token) {
    showError('请输入 token（或通过 ?token= 链接进入）');
    return;
  }
  localStorage.setItem(TOKEN_KEY, token);
  const profile = currentProfile();
  const invalid = SessionClient.validateProfile(profile);
  if (invalid) {
    showError(`档位校验失败：${invalid}`);
    return;
  }

  // 能力预检：不支持 → 含浏览器名的错误页（不发起连接、不黑屏等待）
  const support = await probeVideoSupport();
  if (!support.supported) {
    showError(`当前浏览器不支持视频硬解（${support.browser}）：${support.reason ?? '需要支持 WebCodecs H.264 的浏览器'}`);
    return;
  }

  connectBtn.setAttribute('disabled', 'true');
  try {
    teardownSessionObjects();

    video = new VideoPipeline({
      canvas,
      // 帧延迟采样接入点：offset 由 clock 事件维护（浏览器端口径）
      onFrameDecoded: (ptsUs) => hudCtl?.onFrameDecoded(ptsUs, lastClockOffsetUs),
    });
    await video.probe(support); // 复用预检结果（选定 codec）
    video.start();

    audio = new AudioPipeline({});
    const audioReady = await audio.start();
    applyAudioAvailability();
    if (audioReady) void audio.resumeContext(); // 用户手势内恢复（自动播放策略）

    session = new SessionClient(wsFactory);
    session.rememberConnect({ wsUrl: buildWsUrl(window.location), token, profile });
    bindSessionEvents(session, profile);

    await session.connect({ wsUrl: buildWsUrl(window.location), token, mode: { kind: 'fullscreen' }, profile });

    // 应用网格：FULLSCREEN 建会话后列应用 + 挂机恢复入口
    const apps = await session.listApps();
    renderApps(apps, session.lastHungSessions);
  } catch (err) {
    if (err instanceof VideoUnsupportedError) {
      showError(`当前浏览器不支持视频硬解（${err.support.browser}）：${err.support.reason ?? ''}`);
    } else if (err instanceof SessionRejectedError) {
      showError(`连接被拒绝（${err.statusName}）：${err.reason}`);
    } else {
      showError((err as Error).message);
    }
    teardownSessionObjects();
  } finally {
    connectBtn.removeAttribute('disabled');
  }
}

// ── 工具条 ──

btnBack.addEventListener('click', () => {
  void switchSession({ kind: 'fullscreen' });
});

// 「文本」按钮：展开/收起隐藏输入框（InputBridge 绑定回车发送）
$('btn-text').addEventListener('click', () => {
  const visible = textInput.style.display !== 'none';
  textInput.style.display = visible ? 'none' : 'inline-block';
  if (!visible) textInput.focus();
});

btnEnd.addEventListener('click', () => {
  session?.stopSession();
  backToConnect('');
});

btnAudio.addEventListener('click', () => {
  if (!audio) return;
  const muted = btnAudio.getAttribute('data-muted') === '1';
  audio.setVolume(muted ? 1 : 0);
  btnAudio.setAttribute('data-muted', muted ? '0' : '1');
  btnAudio.textContent = muted ? '音频' : '已静音';
});

logoutBtn.addEventListener('click', () => {
  session?.stopSession();
  backToConnect('');
});

// InputBridge 装配（会话页元素就绪后一次绑定；会话切换仅重置坐标基准）
bridge = new InputBridge(
  {
    inputEvent: (action, pointerId, x, y) => {
      if (session && currentParams) session.sendInputEvent(currentParams.sessionId, action, pointerId, x, y);
    },
    textInput: (text) => session?.sendTextInput(text) ?? 0,
    clipboardSet: (text) => session?.sendClipboardSet(text),
    clipboardGet: () => session?.sendClipboardGet(),
  },
  { videoWidth: 0, videoHeight: 0, sessionId: 0 },
  {
    textInput,
    btnBack,
    btnText: $('btn-text'),
    btnClipRead,
    btnClipWrite,
  },
  { toast: showToast },
  canvas,
);

// ── 启动 ──

tokenInput.value = loadToken();
show('connect');
window.addEventListener('resize', sizeCanvas);
window.addEventListener('orientationchange', sizeCanvas);
void browserName; // 错误页浏览器名经 VideoUnsupportedError 携带（保持引用）
