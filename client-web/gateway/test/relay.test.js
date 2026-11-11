/**
 * 网关中继集成测试（node:test，零额外测试依赖）。
 *
 * 脚本化假源端（net server）按 specs/web-gateway/spec.md 场景逐条覆盖：
 * 上/下行逐字节透传、REJECT_BAD_TOKEN 透传后随关、慢消费者 8MB 不丢不乱（背压）、
 * 双 WS 并发不串扰、WS 断↔TCP 断联动、upstream 拒连（源端未启动）恢复、
 * env 缺省与覆盖、同端口静态托管。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import WebSocket from 'ws';

import { resolveConfig, startGateway, DEFAULT_PORT, DEFAULT_UPSTREAM } from '../server.js';

// ── 测试基础设施 ──

/** 脚本化假源端：每条连接调用 onConn(conn)，conn 提供 write/end/destroy 与收流记录 */
function startFakeSource(onConn) {
  const connections = [];
  const server = net.createServer((socket) => {
    const conn = {
      socket,
      received: [],
      bytes: 0,
      write: (chunk) => socket.write(chunk),
      end: () => socket.end(),
      destroy: () => socket.destroy(),
    };
    socket.on('data', (chunk) => {
      conn.received.push(chunk);
      conn.bytes += chunk.length;
    });
    connections.push(conn);
    onConn(conn);
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      resolve({
        port: server.address().port,
        connections,
        close: () => new Promise((done) => server.close(() => done())),
        server,
      });
    });
  });
}

async function startTestGateway(upstreamPort, staticDir) {
  return startGateway({ port: 0, upstreamHost: '127.0.0.1', upstreamPort, staticDir });
}

/** 连接 WS（路径 /ws），返回 socket；open 后才 resolve */
function wsConnect(port, pathname = '/ws') {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}${pathname}`, { maxPayload: 256 * 1024 * 1024 });
    const timer = setTimeout(() => reject(new Error('ws open timeout')), 10_000);
    ws.on('open', () => {
      clearTimeout(timer);
      resolve(ws);
    });
    ws.on('error', (err) => {
      clearTimeout(timer);
      reject(err);
    });
  });
}

/** 等待 WS 关闭；返回 { code, reason } */
function wsCloseInfo(ws) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('ws close timeout')), 15_000);
    timer.unref?.();
    ws.on('close', (code, reason) => {
      clearTimeout(timer);
      resolve({ code, reason: reason.toString() });
    });
  });
}

/** 累积 WS 二进制消息直到收满 n 字节（顺序拼接）；超时保护 */
function wsRecv(ws, n) {
  const chunks = [];
  let got = 0;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`ws recv timeout: got ${got}/${n}`)), 20_000);
    timer.unref?.();
    const onMsg = (data) => {
      const buf = Buffer.from(data);
      chunks.push(buf);
      got += buf.length;
      if (got >= n) {
        clearTimeout(timer);
        ws.off('message', onMsg);
        ws.off('close', onClose);
        resolve(Buffer.concat(chunks).subarray(0, n));
      }
    };
    // 连接提前关闭：未凑满立即失败并带进度（与 tcpRecv 口径一致），不再空等超时
    const onClose = () => {
      clearTimeout(timer);
      ws.off('message', onMsg);
      ws.off('close', onClose);
      if (got >= n) resolve(Buffer.concat(chunks).subarray(0, n));
      else reject(new Error(`ws closed early: got ${got}/${n}`));
    };
    ws.on('message', onMsg);
    ws.on('close', onClose);
  });
}

/** WS 客户端读暂停/恢复（优先公开 API pause/resume，旧版本回落底层 socket，与 server.js pauseWs 同款） */
function pauseWs(ws) {
  if (typeof ws.pause === 'function') ws.pause();
  else ws._socket?.pause();
}
function resumeWs(ws) {
  if (typeof ws.resume === 'function') ws.resume();
  else ws._socket?.resume();
}

/** 累积 TCP 收流直到 n 字节 */
function tcpRecv(conn, n) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`tcp recv timeout: got ${conn.bytes}/${n}`)), 20_000);
    timer.unref?.();
    const check = () => {
      if (conn.bytes >= n) {
        clearInterval(poll);
        clearTimeout(timer);
        resolve(Buffer.concat(conn.received).subarray(0, n));
      }
    };
    const poll = setInterval(check, 5);
    conn.socket.on('close', () => {
      clearInterval(poll);
      clearTimeout(timer);
      if (conn.bytes >= n) resolve(Buffer.concat(conn.received).subarray(0, n));
      else reject(new Error(`tcp closed early: got ${conn.bytes}/${n}`));
    });
    check();
  });
}

/** 等待假源端 accept 到 n 条连接（gateway 的 upstream 连接与 WS open 是并发竞态） */
function waitForConnections(source, n, timeoutMs = 10_000) {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const poll = setInterval(() => {
      if (source.connections.length >= n) {
        clearInterval(poll);
        resolve();
      } else if (Date.now() - started > timeoutMs) {
        clearInterval(poll);
        reject(new Error(`fake source only accepted ${source.connections.length}/${n} connections`));
      }
    }, 5);
    poll.unref?.();
  });
}

function expectWithin(promise, ms, label) {
  return Promise.race([
    promise,
    delay(ms).then(() => {
      throw new Error(`${label} not settled within ${ms}ms`);
    }),
  ]);
}

/** §2.1 流记录封帧：[u8 recordType][u32 payloadLen][payload]（u32 长度程序化写出，杜绝手写错位） */
function encodeRecord(recordType, payload) {
  const head = Buffer.alloc(5);
  head.writeUInt8(recordType, 0);
  head.writeUInt32BE(payload.length, 1);
  return Buffer.concat([head, payload]);
}

/** §2.2 视频载荷：21B 帧头 [u32 sequence][u64 ptsUs][u8 flags][u16 fragIdx][u16 fragCnt][u32 totalLen] + 分片字节 */
function videoFramePayload(fragBytes) {
  const header = Buffer.alloc(21);
  header.writeUInt32BE(0, 0); // sequence
  header.writeBigUInt64BE(0n, 4); // ptsUs
  header.writeUInt8(0x01, 12); // flags = 关键帧
  header.writeUInt16BE(0, 13); // fragIdx
  header.writeUInt16BE(1, 15); // fragCnt = 1（单分片帧）
  header.writeUInt32BE(fragBytes.length, 17); // totalLen
  return Buffer.concat([header, fragBytes]);
}

/** §3.1 HELLO 控制载荷（浏览器观看端口径：无加密块、p2pOffer 空；mode 恒合法 WatchMode） */
function helloControlPayload(token) {
  const u16 = (v) => {
    const b = Buffer.alloc(2);
    b.writeUInt16BE(v);
    return b;
  };
  const u32 = (v) => {
    const b = Buffer.alloc(4);
    b.writeUInt32BE(v);
    return b;
  };
  const str = (s) => {
    const bytes = Buffer.from(s, 'utf8');
    return Buffer.concat([u16(bytes.length), bytes]);
  };
  return Buffer.concat([
    Buffer.from([0x01]), // msgType HELLO
    u16(1), // version = 1
    str(token), // token
    Buffer.from([0x01]), // mode = FULLSCREEN（合法 WatchMode）
    str(''), // packageName 空
    u16(0), // width
    u16(0), // height
    Buffer.from([30]), // fps
    u32(8000), // bitrateKbps
    u32(0), // sessionId
    str(''), // p2pOffer 空（浏览器端不打洞）
  ]);
}

// ── 场景用例 ──

test('下行字节透传：假源端字节流（控制+视频记录）逐字节按序到达 WS', async () => {
  // 控制+视频记录按协议程序化构造（u32 长度前缀，参照 test 3 rejectRecord；网关不解析内容）
  const pingPayload = Buffer.alloc(17);
  pingPayload.writeUInt8(0x08, 0); // msgType PING（§3.8）
  pingPayload.writeBigUInt64BE(0x1122334455667788n, 1); // nonce
  pingPayload.writeBigUInt64BE(0x000000003b9aca7bn, 9); // senderUs
  const controlRecord = encodeRecord(0x01, pingPayload);
  const videoRecord = encodeRecord(0x02, videoFramePayload(crypto.randomBytes(0x19)));
  const scripted = Buffer.concat([videoRecord, controlRecord, videoRecord]);

  const source = await startFakeSource((conn) => {
    setImmediate(() => conn.write(scripted));
  });
  let gw = null;
  try {
    gw = await startTestGateway(source.port);
    const ws = await wsConnect(gw.port);
    const received = await expectWithin(wsRecv(ws, scripted.length), 10_000, 'downstream relay');
    assert.ok(received.equals(scripted), 'WS 收到的字节必须与假源端发送的逐字节一致');
    ws.close();
  } finally {
    if (gw) await gw.close();
    await source.close();
  }
});

test('上行字节透传：WS 二进制帧载荷逐字节按序到达假源端', async () => {
  const source = await startFakeSource(() => {});
  let gw = null;
  try {
    gw = await startTestGateway(source.port);
    const ws = await wsConnect(gw.port);
    await waitForConnections(source, 1);
    const payload = crypto.randomBytes(64 * 1024);
    const chunks = [];
    for (let off = 0; off < payload.length; off += 4096) {
      chunks.push(payload.subarray(off, off + 4096));
    }
    // 模拟 WS 消息边界与 TCP 段边界无关的乱切割发送
    for (const c of chunks) ws.send(c);
    const received = await expectWithin(tcpRecv(source.connections[0], payload.length), 10_000, 'upstream relay');
    assert.ok(received.equals(payload), '假源端收到的字节必须与 WS 发送的逐字节一致');
    ws.close();
  } finally {
    if (gw) await gw.close();
    await source.close();
  }
});

test('错误语义透传：REJECT_BAD_TOKEN 字节原样送达 WS 客户端，随后 WS 非正常码关闭', async () => {
  // 假源端：收到 HELLO 记录（首字节 0x01）后回 REJECT 记录并关闭 TCP——全程网关不解析
  const rejectRecord = Buffer.concat([
    Buffer.from('01', 'hex'), // recordType control
    (() => {
      const payload = Buffer.from('02 01 000e 746f6b656e206d69736d61746368 00000000 0000 0000 00 00000000 0000 ffffffff 0000 0000'.replace(/\s+/g, ''), 'hex');
      const len = Buffer.alloc(4);
      len.writeUInt32BE(payload.length);
      return Buffer.concat([len, payload]);
    })(),
  ]);
  const source = await startFakeSource((conn) => {
    conn.socket.once('data', () => {
      conn.write(rejectRecord);
      setImmediate(() => conn.end());
    });
  });
  let gw = null;
  try {
    gw = await startTestGateway(source.port);
    const ws = await wsConnect(gw.port);
    await waitForConnections(source, 1);
    const closed = expectWithin(wsCloseInfo(ws), 10_000, 'ws close');
    // 场景前置：WS 客户端先发出 token 错误的 HELLO（网关不解析、原样上行）
    const helloRecord = encodeRecord(0x01, helloControlPayload('wrong-token'));
    ws.send(helloRecord);
    const received = await wsRecv(ws, rejectRecord.length);
    assert.ok(received.equals(rejectRecord), '拒绝消息字节必须原样送达（网关未解析内容）');
    const info = await closed;
    assert.notStrictEqual(info.code, 1000, '上游断开触发的 WS 关闭不得伪装成正常码');
    ws.close();
  } finally {
    if (gw) await gw.close();
    await source.close();
  }
});

test('慢消费者 8MB：恢复读取后全部字节按序送达，网关出站缓冲有界', async () => {
  const total = 8 * 1024 * 1024;
  const chunkSize = 16 * 1024;
  const pattern = crypto.randomBytes(chunkSize);
  let sent = 0;
  const source = await startFakeSource((conn) => {
    // 假源端持续推送；TCP 写背压自然传导
    const push = () => {
      while (sent < total) {
        const n = Math.min(chunkSize, total - sent);
        const chunk = pattern.subarray(0, n);
        sent += n;
        if (!conn.socket.write(chunk)) {
          conn.socket.once('drain', push);
          return;
        }
      }
    };
    setImmediate(push);
  });
  let gw = null;
  try {
    gw = await startTestGateway(source.port);
    const ws = await wsConnect(gw.port);
    const recvPromise = wsRecv(ws, total);
    // 慢消费者：暂停 WS 客户端读，让网关出站缓冲增长并触发暂停读 upstream
    pauseWs(ws);
    await delay(300);
    const peaks = [...gw.relays].map((r) => r.peakWsBuffered);
    resumeWs(ws);
    const received = await expectWithin(recvPromise, 30_000, '8MB relay');
    // 不丢不乱：8MB 全部按序到达，且内容为同一 pattern 重复（逐字节校验首尾与长度）
    assert.strictEqual(received.length, total);
    for (let off = 0; off < total; off += chunkSize) {
      const n = Math.min(chunkSize, total - off);
      assert.ok(
        pattern.subarray(0, n).equals(received.subarray(off, off + n)),
        `偏移 ${off} 处内容不一致（丢字节或乱序）`,
      );
    }
    // 网关内存有界：暂停读 upstream 后出站缓冲不得线性增长到 8MB
    for (const peak of peaks) {
      assert.ok(peak < 4 * 1024 * 1024, `网关出站缓冲峰值 ${peak} 超界（应为有界缓冲，非无限堆积）`);
    }
    ws.close();
  } finally {
    if (gw) await gw.close();
    await source.close();
  }
});

test('双 WS 并发不串扰：各自 upstream 独立收发', async () => {
  // 假源端：echo——连接收到什么就回什么（每条 WS 一条独立 upstream TCP）
  const source = await startFakeSource((conn) => {
    conn.socket.on('data', (chunk) => conn.socket.write(chunk));
  });
  let gw = null;
  try {
    gw = await startTestGateway(source.port);
    const wsA = await wsConnect(gw.port);
    const wsB = await wsConnect(gw.port);
    const payloadA = Buffer.alloc(128 * 1024, 0x41);
    const payloadB = Buffer.alloc(128 * 1024, 0x42);
    const recvA = wsRecv(wsA, payloadA.length);
    const recvB = wsRecv(wsB, payloadB.length);
    wsA.send(payloadA);
    wsB.send(payloadB);
    const [outA, outB] = await Promise.all([
      expectWithin(recvA, 15_000, 'A echo'),
      expectWithin(recvB, 15_000, 'B echo'),
    ]);
    assert.ok(outA.equals(payloadA), 'A 通道必须只收到自己发送的字节');
    assert.ok(outB.equals(payloadB), 'B 通道必须只收到自己发送的字节');
    wsA.close();
    wsB.close();
  } finally {
    if (gw) await gw.close();
    await source.close();
  }
});

test('生命周期联动：WS 断开→upstream TCP 关闭；TCP 断开→WS 关闭', async () => {
  const source = await startFakeSource(() => {});
  let gw = null;
  try {
    gw = await startTestGateway(source.port);
    // ① WS 主动断开 → 假源端连接随之关闭
    const ws1 = await wsConnect(gw.port);
    await waitForConnections(source, 1);
    const connClosed = new Promise((resolve) => source.connections[0].socket.on('close', resolve));
    ws1.close();
    await expectWithin(connClosed, 10_000, 'tcp close after ws close');

    // ② 假源端断开 TCP → WS 收到非 1000 关闭
    const ws2 = await wsConnect(gw.port);
    await waitForConnections(source, 2);
    const closed = expectWithin(wsCloseInfo(ws2), 10_000, 'ws close after tcp close');
    source.connections[1].destroy();
    const info = await closed;
    assert.notStrictEqual(info.code, 1000);
    ws2.close();
  } finally {
    if (gw) await gw.close();
    await source.close();
  }
});

test('upstream 拒连：源端未监听 → WS 收到 1011 关闭，网关存活且后续连接可恢复服务', async () => {
  // 先占住一个端口再释放：得到当前无监听的 upstream 端口（模拟源端未启动）
  const deadPort = await new Promise((resolve, reject) => {
    const placeholder = net.createServer();
    placeholder.once('error', reject);
    placeholder.listen(0, '127.0.0.1', () => {
      const port = placeholder.address().port;
      placeholder.close(() => resolve(port));
    });
  });
  let gw = null;
  try {
    gw = await startTestGateway(deadPort);
    // ① upstream ECONNREFUSED → 客户端收到非 1000（1011）关闭
    // open 与 close 可能背靠背到达（握手与 upstream 建连并发竞态）：先挂 close 监听再等 open
    const ws = new WebSocket(`ws://127.0.0.1:${gw.port}/ws`, { maxPayload: 256 * 1024 * 1024 });
    const closed = wsCloseInfo(ws);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('ws open timeout')), 10_000);
      ws.on('open', () => {
        clearTimeout(timer);
        resolve();
      });
      ws.on('error', (err) => {
        clearTimeout(timer);
        reject(err);
      });
    });
    const info = await expectWithin(closed, 10_000, 'ws close after upstream refused');
    assert.strictEqual(info.code, 1011, `upstream 拒连应关闭为 1011（WS_CLOSE_UPSTREAM_GONE），实际 ${info.code}`);
    // ② 网关存活：同端口补起源端（echo）后，后续连接可正常完成一轮字节往返
    const echo = await new Promise((resolve, reject) => {
      const server = net.createServer((socket) => {
        socket.on('data', (chunk) => socket.write(chunk));
      });
      server.once('error', reject);
      server.listen(deadPort, '127.0.0.1', () => resolve(server));
    });
    try {
      const ws2 = await wsConnect(gw.port);
      const payload = crypto.randomBytes(4096);
      ws2.send(payload);
      const received = await expectWithin(wsRecv(ws2, payload.length), 10_000, 'recovery echo relay');
      assert.ok(received.equals(payload), '源端补起后网关必须能正常服务后续连接（字节往返一致）');
      ws2.close();
    } finally {
      await new Promise((done) => echo.close(() => done()));
    }
  } finally {
    if (gw) await gw.close();
  }
});

test('env 缺省与覆盖：GATEWAY_PORT/UPSTREAM 缺省 8090/127.0.0.1:22333，可覆盖', async () => {
  // 纯函数口径
  const def = resolveConfig({});
  assert.deepStrictEqual(
    [def.port, def.upstreamHost, def.upstreamPort],
    [DEFAULT_PORT, '127.0.0.1', 22333],
  );
  const ov = resolveConfig({ GATEWAY_PORT: '9000', UPSTREAM: '10.0.0.5:22333' });
  assert.deepStrictEqual([ov.port, ov.upstreamHost, ov.upstreamPort], [9000, '10.0.0.5', 22333]);
  // 非法值回落缺省
  const bad = resolveConfig({ GATEWAY_PORT: 'not-a-port', UPSTREAM: 'no-colon-here' });
  assert.strictEqual(bad.port, DEFAULT_PORT);
  assert.strictEqual(bad.upstreamHost, '127.0.0.1');
  assert.strictEqual(bad.upstreamPort, 22333);
  assert.strictEqual(DEFAULT_UPSTREAM, '127.0.0.1:22333');

  // 集成口径：env 驱动启动，二次监听覆盖端口并指向覆盖的 upstream
  const source = await startFakeSource(() => {});
  // 只记录本用例改动的键，逐键恢复现场（不得整体替换 process.env，环境须保持原生代理语义）
  const savedPort = process.env.GATEWAY_PORT;
  const savedUpstream = process.env.UPSTREAM;
  let gw = null;
  try {
    process.env.GATEWAY_PORT = String(9000);
    process.env.UPSTREAM = `127.0.0.1:${source.port}`;
    gw = await startGateway();
    assert.strictEqual(gw.port, 9000);
    assert.strictEqual(gw.cfg.upstreamPort, source.port);
    // 该端口上真实可完成一轮字节往返（页面/通道同端口）
    const ws = await wsConnect(gw.port);
    ws.close();
  } finally {
    if (gw) await gw.close();
    if (savedPort === undefined) delete process.env.GATEWAY_PORT;
    else process.env.GATEWAY_PORT = savedPort;
    if (savedUpstream === undefined) delete process.env.UPSTREAM;
    else process.env.UPSTREAM = savedUpstream;
    await source.close();
  }
});

test('同端口静态托管：/ 返回入口 HTML、缺文件 404、路径穿越被拒', async () => {
  const staticDir = fs.mkdtempSync(path.join(os.tmpdir(), 'gw-static-'));
  fs.writeFileSync(path.join(staticDir, 'index.html'), '<!doctype html><title>gateway-static-ok</title>');
  fs.mkdirSync(path.join(staticDir, 'assets'));
  fs.writeFileSync(path.join(staticDir, 'assets', 'main.js'), 'export {}');
  const source = await startFakeSource(() => {});
  let gw = null;
  try {
    gw = await startTestGateway(source.port, staticDir);
    const get = (p) =>
      new Promise((resolve, reject) => {
        http
          .get({ host: '127.0.0.1', port: gw.port, path: p }, (res) => {
            const chunks = [];
            res.on('data', (c) => chunks.push(c));
            res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString() }));
          })
          .on('error', reject);
      });
    const root = await get('/');
    assert.strictEqual(root.status, 200);
    assert.ok(root.body.includes('gateway-static-ok'), '入口 HTML 必须从 STATIC_DIR 提供');
    const asset = await get('/assets/main.js');
    assert.strictEqual(asset.status, 200);
    const missing = await get('/no-such-file.js');
    assert.strictEqual(missing.status, 404);
    const traversal = await get('/..%2f..%2fserver.js');
    assert.ok(traversal.status === 403 || traversal.status === 404, `路径穿越必须被拒，实际 ${traversal.status}`);
    // 非 /ws 路径不得触发升级
    const ws = wsConnect(gw.port, '/other');
    await assert.rejects(ws, '非 /ws 路径的升级请求必须被拒绝');
  } finally {
    if (gw) await gw.close();
    await source.close();
    fs.rmSync(staticDir, { recursive: true, force: true });
  }
});
