/**
 * cloudphone 协议无关 WebSocket↔TCP 字节中继网关。
 *
 * 设计依据：spec/changes/web-browser-client/design.md D2、specs/web-gateway/spec.md。
 *
 * 职责边界（协议无关）：每条 WS 连接对应一条 upstream TCP 连接，双向**纯字节搬运**——
 * WS 二进制帧载荷按序写入 TCP，TCP 字节流按序封装为 WS 二进制帧下发。本网关 MUST NOT
 * 解析、改写、过滤、缓存合并任何消息语义（认证、会话、错误一律由源端协议层裁决）。
 *
 * 同端口托管：HTTP 静态文件（浏览器构建产物，`/` 入口页）+ `/ws` 路径 WS 升级，
 * 单端口经 frp 暴露，浏览器同源连接（无 CORS、无第二隧道）。
 *
 * 配置（env）：GATEWAY_PORT（缺省 8090）、UPSTREAM（缺省 127.0.0.1:22333，host:port / [v6]:port / 裸 v6 字面量）、
 * STATIC_DIR（缺省 ../app/dist，vite 构建产物）。
 *
 * 运行时唯一依赖：ws。
 */

import http from 'node:http';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer, WebSocket } from 'ws';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/** env 缺省值（docs/web-gateway spec：GATEWAY_PORT 8090 / UPSTREAM 127.0.0.1:22333） */
export const DEFAULT_PORT = 8090;
export const DEFAULT_UPSTREAM = '127.0.0.1:22333';
export const DEFAULT_STATIC_DIR = path.resolve(__dirname, '../app/dist');

/**
 * 解析 UPSTREAM 地址（纯函数）：host:port（IPv4/主机名）、[IPv6 字面量]:port、裸 IPv6 字面量。
 * 裸 IPv6 不带方括号无法无歧义附加端口，端口取 defaultPort；非法形式返回 null。
 * @param {string} raw
 * @param {number} defaultPort
 * @returns {{ host: string, port: number } | null}
 */
function parseUpstreamAddress(raw, defaultPort) {
  const open = raw.indexOf('[');
  const close = raw.indexOf(']');
  if (open >= 0 || close >= 0) {
    if (open !== 0 || close < open) return null; // 只接受 [v6] / [v6]:port 方括号形式
    const host = raw.slice(1, close);
    const rest = raw.slice(close + 1);
    if (!host || host.includes('[') || host.includes(']')) return null;
    if (rest === '') return { host, port: defaultPort };
    const p = Number.parseInt(rest.slice(1), 10);
    if (!rest.startsWith(':') || !Number.isInteger(p) || p <= 0 || p >= 65536) return null;
    return { host, port: p };
  }
  const colonCount = raw.split(':').length - 1;
  if (colonCount === 1) {
    const idx = raw.indexOf(':');
    const host = raw.slice(0, idx);
    const p = Number.parseInt(raw.slice(idx + 1), 10);
    if (host && Number.isInteger(p) && p > 0 && p < 65536) return { host, port: p };
    return null;
  }
  if (colonCount > 1) {
    // 多冒号按 IPv6 字面量处理；形似 v6 却非法（如 1:2:3）判否回落缺省，不留静默误配
    if (net.isIPv6(raw)) return { host: raw, port: defaultPort };
    return null;
  }
  return null; // 无冒号：缺端口
}

/**
 * 解析网关配置（纯函数，便于测试 env 缺省与覆盖）。
 * @param {Record<string, string | undefined>} env
 */
export function resolveConfig(env = process.env) {
  const portRaw = env.GATEWAY_PORT;
  let port = DEFAULT_PORT;
  if (portRaw !== undefined && portRaw !== '') {
    const parsed = Number.parseInt(portRaw, 10);
    if (Number.isInteger(parsed) && parsed > 0 && parsed < 65536) {
      port = parsed;
    } else {
      console.warn(`[gateway] 非法 GATEWAY_PORT="${portRaw}"，回落缺省 ${DEFAULT_PORT}`);
    }
  }

  const upstreamRaw = env.UPSTREAM;
  let upstreamHost = '127.0.0.1';
  let upstreamPort = 22333;
  if (upstreamRaw !== undefined && upstreamRaw !== '') {
    const parsed = parseUpstreamAddress(upstreamRaw, 22333);
    if (parsed) {
      upstreamHost = parsed.host;
      upstreamPort = parsed.port;
    } else {
      console.warn(
        `[gateway] 非法 UPSTREAM="${upstreamRaw}"（需 host:port / [v6]:port / 裸 v6 字面量），回落缺省 ${DEFAULT_UPSTREAM}`,
      );
    }
  }

  const staticDir = env.STATIC_DIR ? path.resolve(env.STATIC_DIR) : DEFAULT_STATIC_DIR;
  return { port, upstreamHost, upstreamPort, staticDir };
}

/** 静态文件 MIME 表（构建产物所需的最小集合） */
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.map': 'application/json',
  '.txt': 'text/plain; charset=utf-8',
  '.wasm': 'application/wasm',
};

/** 静态文件解析：拒绝路径穿越，目录请求回落 index.html；不在托管根内返回 null */
function resolveStaticPath(staticDir, urlPath) {
  let pathname;
  try {
    pathname = decodeURIComponent(new URL(urlPath, 'http://localhost').pathname);
  } catch {
    return null;
  }
  if (pathname.includes('\0')) return null; // NUL 空字节：fs API 收到含 NUL 路径会同步抛错（DoS），必须拒绝
  if (pathname === '/' || pathname === '') pathname = '/index.html';
  const resolved = path.resolve(staticDir, `.${pathname}`);
  if (resolved !== staticDir && !resolved.startsWith(staticDir + path.sep)) return null; // 穿越
  return resolved;
}

/** 静态文件服务（无框架；仅 GET/HEAD；未命中 404） */
function serveStatic(staticDir, req, res) {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, { Allow: 'GET, HEAD' }).end();
    return;
  }
  const filePath = resolveStaticPath(staticDir, req.url ?? '/');
  if (!filePath) {
    res.writeHead(403).end('forbidden');
    return;
  }
  fs.stat(filePath, (err, st) => {
    if (err || !st.isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }).end('not found');
      return;
    }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(filePath).toLowerCase()] ?? 'application/octet-stream',
      'Content-Length': st.size,
      'Cache-Control': 'no-cache',
    });
    if (req.method === 'HEAD') {
      res.end();
      return;
    }
    const stream = fs.createReadStream(filePath);
    stream.on('error', () => {
      // stat 与读取之间文件失效（被删/权限变化/读盘错误）：头已按 st.size 发出，只能销毁响应防悬挂
      res.destroy();
    });
    stream.pipe(res);
  });
}

/**
 * WS 出站背压水位：ws.bufferedAmount（尚未来得及写进内核 socket 的出站字节）
 * 超过高水位即暂停读 upstream TCP；降至低水位以下恢复。两个方向都不丢字节不乱序。
 */
const WS_HIGH_WATER = 1024 * 1024; // 1 MiB
const WS_LOW_WATER = 256 * 1024;

/** upstream 建连超时：UPSTREAM 黑洞（防火墙静默丢包）时 SYN 重传可达分钟级，超时即拆链 */
const UPSTREAM_CONNECT_TIMEOUT_MS = 5000;
/** 中继空闲超时：upstream socket 收发任一方向有活动即刷新，半开连接（断电/NAT 老化）兜底回收 */
const RELAY_IDLE_TIMEOUT_MS = 60000;

/** 每来源 IP 的 WS 并发与新建速率上限：第三方页面不得把网关当免费中继滥用 */
const WS_MAX_CONNS_PER_IP = 8;
const WS_MAX_NEW_CONNS_PER_SECOND = 5;

/** TCP→WS 方向：出站缓冲超水位时暂停 upstream 读，轮询回落低水位后恢复 */
function applyWsBackpressure(tcp, ws, relay) {
  if (!relay.alive || relay.wsOutboundPaused) return;
  relay.peakWsBuffered = Math.max(relay.peakWsBuffered, ws.bufferedAmount);
  if (ws.bufferedAmount <= WS_HIGH_WATER) return;
  relay.wsOutboundPaused = true;
  tcp.pause();
  relay.drainTimer = setInterval(() => {
    if (ws.readyState !== WebSocket.OPEN) {
      clearInterval(relay.drainTimer);
      return;
    }
    relay.peakWsBuffered = Math.max(relay.peakWsBuffered, ws.bufferedAmount);
    if (ws.bufferedAmount <= WS_LOW_WATER) {
      clearInterval(relay.drainTimer);
      relay.wsOutboundPaused = false;
      if (relay.alive) tcp.resume();
    }
  }, 5);
  relay.drainTimer.unref?.();
}

/** ws 入站暂停/恢复（优先用 ws 库官方 pause/resume，旧版本回落底层 socket） */
function pauseWs(ws) {
  if (typeof ws.pause === 'function') ws.pause();
  else ws._socket?.pause();
}
function resumeWs(ws) {
  if (typeof ws.resume === 'function') ws.resume();
  else ws._socket?.resume();
}

/** 上游断开 → WS 以非 1000 码关闭（浏览器按可重连断开处理） */
const WS_CLOSE_UPSTREAM_GONE = 1011;

/**
 * 建立一条 WS↔TCP 中继（双向字节搬运 + 双向背压 + 断开联动）。
 * @returns {{ peakWsBuffered: number }} 观测句柄（供测试断言内存有界）
 */
function relayConnection(ws, upstreamHost, upstreamPort, log) {
  const relay = { alive: true, peakWsBuffered: 0 };
  let tcp = null;
  let wsInboundPaused = false;
  let connectTimer = null;

  const teardown = () => {
    if (!relay.alive) return;
    relay.alive = false;
    if (connectTimer) clearTimeout(connectTimer);
    if (relay.drainTimer) clearInterval(relay.drainTimer);
    if (tcp && !tcp.destroyed) tcp.destroy();
    if (ws.readyState !== WebSocket.CLOSED) {
      ws.close(WS_CLOSE_UPSTREAM_GONE, 'upstream closed');
      // 兜底：close 握手超时直接终止
      const force = setTimeout(() => ws.terminate(), 2000);
      force.unref?.();
    }
  };

  ws.on('message', (data, isBinary) => {
    if (!relay.alive || !tcp) return;
    if (!isBinary) return; // 线协议只有二进制载荷；文本帧非本通道语义，忽略不转发
    const ok = tcp.write(Buffer.from(data)); // Buffer.from 视图拷贝规避 RetainedBuffer 引用
    if (!ok && !wsInboundPaused) {
      // TCP 出站内核缓冲满：暂停读 WS（字节滞留在 WS 侧，不丢不乱序）
      wsInboundPaused = true;
      pauseWs(ws);
      relay.peakWsBuffered = Math.max(relay.peakWsBuffered, ws.bufferedAmount);
    }
  });
  ws.on('close', teardown);
  ws.on('error', (err) => {
    log?.(`ws error: ${err.message}`);
    teardown();
  });

  tcp = net.connect({ host: upstreamHost, port: upstreamPort }, () => {
    if (connectTimer) {
      clearTimeout(connectTimer);
      connectTimer = null;
    }
    log?.(`upstream connected ${upstreamHost}:${upstreamPort}`);
    // 空闲看门狗：socket 收发双向任一活动都刷新计时，黑洞/半开死链兜底回收
    tcp.setTimeout(RELAY_IDLE_TIMEOUT_MS);
  });
  connectTimer = setTimeout(() => {
    log?.(`upstream connect timeout (${UPSTREAM_CONNECT_TIMEOUT_MS}ms)`);
    teardown();
  }, UPSTREAM_CONNECT_TIMEOUT_MS);
  connectTimer.unref?.();

  tcp.on('timeout', () => {
    log?.(`relay idle over ${RELAY_IDLE_TIMEOUT_MS}ms`);
    teardown();
  });

  tcp.on('data', (chunk) => {
    if (!relay.alive || ws.readyState !== WebSocket.OPEN) return;
    ws.send(chunk, { binary: true });
    applyWsBackpressure(tcp, ws, relay);
  });

  // TCP 写缓冲回落：恢复读 WS
  tcp.on('drain', () => {
    if (wsInboundPaused) {
      wsInboundPaused = false;
      resumeWs(ws);
    }
  });

  tcp.on('close', () => {
    log?.('upstream closed');
    teardown();
  });
  tcp.on('error', (err) => {
    log?.(`upstream error: ${err.message}`);
    teardown();
  });

  return relay;
}

/** 升级握手拒绝（握手未完成、无 res 可用）：裸写状态行并断开 */
function rejectUpgrade(socket, statusLine) {
  socket.write(`HTTP/1.1 ${statusLine}\r\nConnection: close\r\n\r\n`);
  socket.destroy();
}

/**
 * 启动网关。
 * @param {{ port?: number, upstreamHost?: string, upstreamPort?: number, staticDir?: string,
 *           log?: (msg: string) => void }} [opts] 缺省经 resolveConfig(process.env) 解析
 * @returns {Promise<{ port: number, close: () => Promise<void>, relays: Set<object> }>}
 */
export function startGateway(opts = {}) {
  const cfg = {
    ...resolveConfig(typeof process !== 'undefined' ? process.env : {}),
    ...opts,
  };
  const log = opts.log;
  const httpServer = http.createServer((req, res) => {
    try {
      serveStatic(cfg.staticDir, req, res);
    } catch (err) {
      // 兜底：静态服务同步异常（如异常请求路径）不得穿透为 uncaughtException 击穿进程
      log?.(`static handler error: ${err?.message ?? err}`);
      if (!res.headersSent) {
        res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' }).end('internal error');
      } else {
        res.destroy();
      }
    }
  });
  // 协议单记录上限 8MiB（MAX_RECORD_BYTES），加记录头余量即可；坏流量不必在网关放大内存占用
  const wss = new WebSocketServer({ noServer: true, maxPayload: 9 * 1024 * 1024 });
  const relays = new Set();
  const wsConnsByIp = new Map(); // ip → { active, windowStart, windowCount }：并发与新建速率记账

  httpServer.on('upgrade', (req, socket, head) => {
    const { pathname } = new URL(req.url ?? '/', 'http://localhost');
    if (pathname !== '/ws') {
      rejectUpgrade(socket, '404 Not Found');
      return;
    }
    // 同源校验：浏览器一定携带 Origin，跨站页面（与 Host 不符）直接拒绝；
    // 非浏览器客户端（如 npm ws）不带 Origin，放行——认证语义仍由源端 token 裁决。
    const origin = req.headers.origin;
    if (origin) {
      let originHost = null;
      try {
        originHost = new URL(origin).host;
      } catch {
        originHost = null;
      }
      if (!originHost || originHost !== req.headers.host) {
        rejectUpgrade(socket, '403 Forbidden');
        return;
      }
    }
    // 每 IP 并发上限 + 新建速率限制：防连接洪水 / 把网关当免费中继对源端做 token 爆破
    const ip = req.socket.remoteAddress ?? 'unknown';
    const bucket = wsConnsByIp.get(ip) ?? { active: 0, windowStart: 0, windowCount: 0 };
    wsConnsByIp.set(ip, bucket);
    const now = Date.now();
    if (now - bucket.windowStart >= 1000) {
      bucket.windowStart = now;
      bucket.windowCount = 0;
    }
    if (bucket.active >= WS_MAX_CONNS_PER_IP || bucket.windowCount >= WS_MAX_NEW_CONNS_PER_SECOND) {
      rejectUpgrade(socket, '429 Too Many Requests');
      return;
    }
    bucket.windowCount += 1;
    bucket.active += 1;
    let slotReleased = false;
    const releaseSlot = () => {
      if (slotReleased) return;
      slotReleased = true;
      bucket.active -= 1;
    };
    wss.handleUpgrade(req, socket, head, (ws) => {
      const relay = relayConnection(ws, cfg.upstreamHost, cfg.upstreamPort, log);
      relays.add(relay);
      ws.on('close', () => {
        relays.delete(relay);
        releaseSlot();
      });
    });
    // 握手中途夭折（未完成升级）也释放并发名额：socket 关闭兜底
    socket.once('close', releaseSlot);
  });

  return new Promise((resolve, reject) => {
    httpServer.once('error', reject);
    httpServer.listen(cfg.port, () => {
      const actualPort = httpServer.address().port;
      log?.(`gateway listening on ${actualPort} → upstream ${cfg.upstreamHost}:${cfg.upstreamPort}`);
      resolve({
        port: actualPort,
        cfg,
        relays,
        close: () =>
          new Promise((done) => {
            for (const ws of wss.clients) ws.terminate();
            // 回收非 WS 的 keep-alive 空闲连接：否则 httpServer.close 要等 keepAliveTimeout 自然到期
            httpServer.closeIdleConnections?.();
            httpServer.close(() => done());
          }),
      });
    });
  });
}

/** CLI 入口：node server.js */
export function main() {
  const cfg = resolveConfig(process.env);
  startGateway({
    log: (msg) => console.log(`[gateway] ${msg}`),
  }).then((gw) => {
    console.log(
      `[gateway] ready: http://0.0.0.0:${gw.port}/ (static: ${cfg.staticDir}) → ws://…:${gw.port}/ws → ${cfg.upstreamHost}:${cfg.upstreamPort}`,
    );
  }).catch((err) => {
    // 启动失败（端口被占用/无监听权限等）：给出明确指引，避免裸崩为未处理 Promise rejection
    if (err && (err.code === 'EADDRINUSE' || err.code === 'EACCES')) {
      console.error(
        `[gateway] 监听端口 ${cfg.port} 失败（${err.code}）：端口被占用或无监听权限，请改用 GATEWAY_PORT 指定其他端口`,
      );
    } else {
      console.error(`[gateway] 启动失败：${err?.message ?? err}`);
    }
    process.exitCode = 1;
  });
}

const isMain = process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
if (isMain) main();
