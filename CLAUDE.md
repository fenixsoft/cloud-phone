# CLAUDE.md — Cloud-Phone 项目约定

## 原型访问地址（FRP 隧道）

- fenix-prototype 生成原型交付评审时，对外访问地址一律使用 `http://home.icyfenix.cn:18787` 代替 `http://127.0.0.1:8787`（frp 隧道透传到本机 dashboard 8787，**纯 HTTP 无 TLS，禁用 https**；路径与 `?proto_round=N` 参数不变），用户免端口转发直接访问。`proto_serve.py` 输出的 127.0.0.1 地址仅作服务器本机自检，MUST NOT 直接交给用户；交付前用该公网地址端到端探测一次（HTTP 200）。
