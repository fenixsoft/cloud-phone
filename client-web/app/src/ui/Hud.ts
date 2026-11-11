/**
 * HUD（任务 6.2）：fps/码率/RTT/管线延迟 P50/P95/解码队列/丢帧 实时展示 +
 * 延迟采样开关（采集窗口内逐样本落 docs/web-latency-samples.json 口径——
 * 浏览器端口径：管线延迟 = now(browser) − (ptsUs(源端钟) + offset)，offset 为
 * 自发 PING/PONG 时钟估计；下载文件首部注明口径与采集环境）。
 */

export interface HudRefs {
  fps: HTMLElement;
  bitrate: HTMLElement;
  rtt: HTMLElement;
  latencyP50: HTMLElement;
  latencyP95: HTMLElement;
  decodeQueue: HTMLElement;
  dropped: HTMLElement;
  /** 采样开关按钮（可选） */
  sampleToggle?: HTMLElement;
  /** 采样状态徽标（可选） */
  sampleBadge?: HTMLElement;
  /** 采样导出下载按钮（可选；docs/web-client.md §6「停止后下载 web-latency-samples.json」） */
  sampleDownload?: HTMLElement;
}

/** 浏览器标签（采样文档 browser 字段口径：名称 + 主版本；Edge/Opera 需先于 Chrome 判别） */
export function browserLabel(): string {
  const ua = typeof navigator !== 'undefined' ? navigator.userAgent : '';
  const pick = (re: RegExp): string => {
    const m = re.exec(ua);
    return m ? `${m[1]} ${m[2]}.0` : '';
  };
  return (
    pick(/(Edg)\/(\d+)/) ||
    pick(/(OPR)\/(\d+)/) ||
    pick(/(Firefox)\/(\d+)/) ||
    pick(/(Chrome)\/(\d+)/) ||
    pick(/(Version)\/(\d+)/) || // Safari 真实版本在 Version/ 令牌
    'unknown'
  );
}

export interface HudInput {
  fps: number;
  bitrateKbps: number;
  rttMs: number;
  decodeQueueSize: number;
  /** seq 缺口 + 结构性丢弃合计 */
  dropped: number;
  audioSkipCount?: number;
}

/** 单条延迟样本（web-latency-samples.json samples 数组口径） */
export interface LatencySample {
  seq: number;
  ptsUs: string;
  /** 管线延迟 ms（浏览器端口径，含 offset 修正） */
  latencyMs: number;
  capturedAtMs: number;
}

/** docs/web-latency-samples.json 结构（浏览器端口径注明） */
export interface LatencySamplesDoc {
  caliber: string;
  profile: { width: number; height: number; fps: number; bitrateKbps: number };
  browser: string;
  collectedAt: string;
  summary: { p50Ms: number; p95Ms: number; samples: number; rttMsAvg: number };
  samples: LatencySample[];
}

/** 环形样本窗口（容量封顶防内存增长） */
const MAX_SAMPLES = 6000;

export class Hud {
  private sampling = false;
  private samples: LatencySample[] = [];
  private rttSamplesUs: number[] = [];
  private seqCounter = 0;

  constructor(private readonly refs: HudRefs) {
    if (refs.sampleToggle) {
      refs.sampleToggle.addEventListener('click', () => {
        this.setSampling(!this.sampling);
      });
    }
    if (refs.sampleDownload) {
      refs.sampleDownload.addEventListener('click', () => {
        this.downloadSamplesDoc(browserLabel());
      });
    }
  }

  /** 会话参数（采样文档 profile 口径） */
  sessionProfile: { width: number; height: number; fps: number; bitrateKbps: number } | null = null;

  /** 帧到达（视频管线 output 前）：采样开启时记录管线延迟样本 */
  onFrameDecoded(ptsUs: bigint, offsetUs: number): void {
    if (!this.sampling) return;
    if (this.samples.length >= MAX_SAMPLES) return;
    const nowUs = Math.round(performance.now() * 1000);
    const latencyUs = nowUs - (Number(ptsUs) + offsetUs);
    this.samples.push({
      seq: ++this.seqCounter,
      ptsUs: ptsUs.toString(),
      latencyMs: Math.round(latencyUs / 100) / 10, // 0.1ms 精度
      capturedAtMs: Date.now(),
    });
  }

  /** 1s 节拍的 HUD 刷新（main.ts 定时调用） */
  update(input: HudInput): void {
    this.refs.fps.textContent = input.fps.toFixed(1);
    this.refs.bitrate.textContent = `${(input.bitrateKbps / 1000).toFixed(2)} Mbps`;
    this.refs.rtt.textContent = `${Math.max(0, Math.round(input.rttMs))} ms`;
    this.refs.decodeQueue.textContent = String(input.decodeQueueSize);
    this.refs.dropped.textContent = `${input.dropped}${input.audioSkipCount ? ` / 音频skip ${input.audioSkipCount}` : ''}`;
    const p = this.percentiles();
    this.refs.latencyP50.textContent = p ? `${p.p50.toFixed(1)} ms` : '—';
    this.refs.latencyP95.textContent = p ? `${p.p95.toFixed(1)} ms` : '—';
    if (this.refs.sampleBadge) {
      this.refs.sampleBadge.textContent = this.sampling ? `采样中 ${this.samples.length}` : '采样关';
    }
  }

  /** RTT 样本（clock 事件；EMA 窗口 16） */
  onClockSample(rttUs: number): void {
    this.rttSamplesUs.push(rttUs);
    if (this.rttSamplesUs.length > 16) this.rttSamplesUs.shift();
  }

  /** 当前 rtt（ms 均值口径） */
  rttMs(): number {
    if (this.rttSamplesUs.length === 0) return 0;
    return this.rttSamplesUs.reduce((a, b) => a + b, 0) / this.rttSamplesUs.length / 1000;
  }

  /** 窗口内延迟分位（样本 <2 返回 null） */
  percentiles(): { p50: number; p95: number } | null {
    if (this.samples.length < 2) return null;
    const lat = this.samples.map((s) => s.latencyMs).sort((a, b) => a - b);
    const at = (q: number): number => lat[Math.min(lat.length - 1, Math.floor(q * lat.length))];
    return { p50: at(0.5), p95: at(0.95) };
  }

  /** 采样开关 */
  setSampling(on: boolean): void {
    this.sampling = on;
    if (this.refs.sampleDownload) {
      // docs/web-client.md §6 口径：停止后下载（有样本才出现）
      this.refs.sampleDownload.hidden = on || this.samples.length === 0;
    }
    if (!on) return; // 关闭保留数据（再次开启前可 export/clear）
    this.samples = [];
    this.seqCounter = 0;
  }

  isSampling(): boolean {
    return this.sampling;
  }

  sampleCount(): number {
    return this.samples.length;
  }

  /** 导出 docs/web-latency-samples.json 口径文档（下载 + 回传统一断言） */
  exportSamplesDoc(browser: string): LatencySamplesDoc {
    const p = this.percentiles() ?? { p50: 0, p95: 0 };
    const rttAvgMs = this.rttMs();
    return {
      caliber:
        '浏览器端口径：管线延迟 = now(browser) − (ptsUs(源端钟) + offset)；offset 为自发 PING→PONG 时钟估计（offset ≈ echoUs − (t_send+t_recv)/2）；含采集/解码/渲染前全链路',
      profile: this.sessionProfile ?? { width: 0, height: 0, fps: 0, bitrateKbps: 0 },
      browser,
      collectedAt: new Date().toISOString(),
      summary: { p50Ms: p.p50, p95Ms: p.p95, samples: this.samples.length, rttMsAvg: rttAvgMs },
      samples: [...this.samples],
    };
  }

  /** 触发浏览器下载（浏览器侧无文件系统，落盘由用户保存/验收脚本收集） */
  downloadSamplesDoc(browser: string): void {
    const doc = this.exportSamplesDoc(browser);
    const blob = new Blob([JSON.stringify(doc, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'web-latency-samples.json';
    a.click();
    URL.revokeObjectURL(url);
  }

  clearSamples(): void {
    this.samples = [];
    this.seqCounter = 0;
  }
}
