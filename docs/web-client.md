# 浏览器观看端部署手册（web-browser-client）

> 适用对象：`client-web/gateway`（WS↔TCP 字节中继 + 静态托管）与 `client-web/app`
> （WebCodecs 浏览器观看端）。协议语义见 `docs/protocol.md` §8 对接注记。

## 1. 构建与自测（开发机）

```bash
# 网关（Node ≥18，零运行时依赖，仅 ws 一项 npm 依赖）
cd client-web/gateway && npm install && npm test   # 8 例中继集成测试

# 浏览器端（构建产物 dist/ 由网关静态托管）
cd client-web/app && npm install && npm run build  # = tsc --noEmit + vite build
npx vitest run                                     # 单测（协议 golden / 管线 / 会话 / 输入 / HUD）
```

## 2. 网关部署（宿主机 systemd）

网关为单进程 Node 服务，env 三项：

| env | 默认 | 说明 |
|---|---|---|
| `GATEWAY_PORT` | `8090` | 对外 HTTP/WS 端口（静态页面 + `/ws` 反代） |
| `UPSTREAM` | `127.0.0.1:22333` | 源端地址（本机 frp 转发或 adb forward 的 TCP 流记录端口） |
| `STATIC_DIR` | `../app/dist` | 浏览器端构建产物目录（`GET/HEAD` 静态托管，`/ws` 以外路径全部走静态） |

`/etc/systemd/system/cloudphone-web-gateway.service`：

```ini
[Unit]
Description=CloudPhone Web Gateway (WS<->TCP relay + static)
After=network.target frpc.service

[Service]
WorkingDirectory=/opt/cloudphone/client-web/gateway
Environment=GATEWAY_PORT=8090
Environment=UPSTREAM=127.0.0.1:22333
Environment=STATIC_DIR=/opt/cloudphone/client-web/app/dist
ExecStart=/usr/bin/node server.js
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now cloudphone-web-gateway
curl -sI http://127.0.0.1:8090/ | head -1   # 期望 HTTP/1.1 200
```

> 纯字节中继：网关无 TLS 终结、无鉴权逻辑；传输保密性与 frp 通道一致（frpc 侧 token）。
> 对外暴露时如需 HTTPS，在 frp/nginx 层终结后回源 `http://127.0.0.1:8090`。

## 3. frpc 透传（新增 proxy 条目）

宿主机 `frpc`（与源端 22333 现有条目并列）新增：

```ini
[[proxies]]
name = "cloudphone-web-gateway"
type = "tcp"
localIP = "127.0.0.1"
localPort = 8090
remotePort = 8090   # frp 服务器侧对外端口；与现有 22333 条目互不影响
```

重启 `frpc` 后，公网入口 = `http://<frp 服务器>:8090/`。

## 4. 交付与配对（token 书签）

1. 源端 App「配对 token」默认 `cloudphone-m0`（StreamService `DEFAULT_TOKEN`，可在 App 内修改，持久化于 prefs）。
2. 浏览器端首次进入建议直接用带 token 链接建立书签：
   `http://<frp 服务器>:8090/?token=<配对token>`
   —— 页面把 token 存入 `localStorage`（键 `cloudphone.web.token`）并从地址栏移除，之后书签保留根路径即可。
3. 「清除记忆的 Token」按钮可随时抹掉本机 token（换源端/换机时使用）。
4. 观看端占用：同一时刻仅一个观看端通道在役（传输层占用闸门，第二个浏览器的 HELLO 收
   `REJECT_BUSY` + 占用原因）；现役标签页关闭后新标签页即可连上，挂机会话可被恢复。

## 5. 交付自检（运维口径）

| 检查 | 命令/操作 | 期望 |
|---|---|---|
| 网关存活 | `curl -sI http://127.0.0.1:8090/` | 200 |
| WS 通路 | 浏览器打开页面 → 连接 | 应用网格出现（HELLO/START_SESSION/APP_LIST 全通） |
| 源端通路 | `adb forward tcp:22333 tcp:22333` 后连接 | 同上 |
| 不支持降级 | 用不支持 WebCodecs 的浏览器打开 | 含浏览器名的显式错误页 |
| 占用闸门 | 开两个标签页先后连接 | 第二个收到 REJECT_BUSY 原因提示 |

## 6. 真机单应用人工验收清单

> 模拟器镜像级不支持 SINGLE_APP（device-notes §2.1），单应用直达口径与下列真机项
> 一律人工复测（与 client-ux D6 验收口径一致）。模拟器上自动冒烟仅覆盖
> 「失败带原因回连接页」回退（`client-web/app` e2e/smoke.spec.ts）。

### 6.1 单应用进入 / 切换

- [ ] 应用网格点选单应用 → 3s 内进入该应用画面（非整屏），画面裁剪为虚拟屏内容
- [ ] 工具条 BACK → 返回上一界面（源端 dispatchKey 语义）
- [ ] 再次点选另一单应用 → 直接切换（同一会话 START_SESSION 重入，不回连接页）
- [ ] 单应用中切「整屏」入口 → 回整屏镜像

### 6.2 ATTACH 恢复

- [ ] 会话中直接关闭标签页（挂机）→ 30s 内重新打开书签连接 → 挂机入口出现 → 恢复 ≤1s 出画（附着自动 IDR）
- [ ] 挂机超 30s 后连接 → 提示重连窗口已过、回连接页带原因

### 6.3 音频

- [ ] 进入会话 1s 内出声（120ms 起播水位）；静音按钮生效/恢复
- [ ] 源端静音 ≥3s（静音抑制）→ 无爆音断续；恢复发声 → 自动续播
- [ ] 用不支持 `AudioDecoder` 的浏览器打开 → 音频按钮置灰 + 原因提示，视频/触控正常

### 6.4 剪贴板

- [ ] 源端复制文本 → 浏览器「读剪贴板」→ 本地剪贴板获得该文本 + toast
- [ ] 本地复制文本 → 浏览器「写剪贴板」→ 源端 App 内粘贴得到该文本
- [ ] 源端不支持（多窗口/无 IME 焦点）→ auxNotice → 剪贴板按钮置灰 + 原因 toast

### 6.5 文本注入

- [ ] 「文本」展开输入框 → 输入中文 → 回车 → 源端焦点输入框上屏，toast「注入成功」
- [ ] 无焦点输入框时注入 → ACK 失败 + 原因 toast

### 6.6 PC 键鼠与平板触屏

- [ ] PC 鼠标按下/拖动/抬起 → 源端对应单点触摸（letterbox 内映射精确到边缘）
- [ ] letterbox 黑边区点击 → clamp 到视频边缘不越界、不误触
- [ ] Esc → 等价 BACK
- [ ] 平板双指并发触摸 → 源端合成两点（v1 源端取单点时取先到点，不崩溃）
- [ ] HUD：fps/码率/RTT/延迟 P50/P95/解码队列/丢帧 数值随负载变化合理（fps≈档位、队列 <5）
- [ ] 「采样」开关 → HUD 徽标计数增长；停止后下载 `web-latency-samples.json`（口径注明浏览器端）
