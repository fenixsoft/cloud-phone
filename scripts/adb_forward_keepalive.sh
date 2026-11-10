#!/usr/bin/env bash
# adb_forward_keepalive.sh — 保活源端 adb 端口转发（tcp:22333 forward + udp:22334 redir）。
# 背景：adb server 意外重启/模拟器冷启事件会清空 forward 规则，公网 FRP 最后一跳
# （127.0.0.1:22333）随之 refused，真机报「HELLO 无应答」。本守护每 5s 自检并幂等重建。
set -u
SOURCE_SERIAL="${SOURCE_SERIAL:-emulator-5554}"
TCP_PORT="${SOURCE_HOST_PORT:-22333}"
UDP_PORT="${SOURCE_UDP_HOST_PORT:-22334}"

need_tcp() { timeout 2 bash -c "exec 3<>/dev/tcp/127.0.0.1/${TCP_PORT}" 2>/dev/null; }
need_udp() { adb -s "$SOURCE_SERIAL" emu redir list 2>/dev/null | grep -q "udp:${UDP_PORT}"; }
need_helper() { adb -s "$SOURCE_SERIAL" shell "netstat -tln 2>/dev/null" 2>/dev/null | grep -q ":22340"; }

while true; do
    if adb devices 2>/dev/null | grep -q "${SOURCE_SERIAL}	device"; then
        # 注意：forward 规则的「重建」（re-add）会杀掉既有隧道！所以只有当 127.0.0.1:22333
        # 真正连不上（规则缺失/监听没了）时才重建——空闲探针连接对源端无害（裸连接不踢会话）。
        if ! need_tcp; then
            adb -s "$SOURCE_SERIAL" forward "tcp:${TCP_PORT}" "tcp:${TCP_PORT}" >/dev/null 2>&1 \
                && echo "$(date '+%F %T') [keepalive] 重建 tcp:${TCP_PORT} forward" >&2
        fi
        if ! need_udp; then
            if adb -s "$SOURCE_SERIAL" emu redir add "udp:${UDP_PORT}:${UDP_PORT}" 2>/dev/null | grep -q OK; then
                echo "$(date '+%F %T') [keepalive] 重建 udp:${UDP_PORT} redir" >&2
            fi
        fi
        # shell 助手（输入注入 22340/22341）：adb-server 重启/App 重装后会死，自动拉起
        if ! need_helper; then
            if bash "$(dirname "${BASH_SOURCE[0]}")/bootstrap_adb.sh" helper >/dev/null 2>&1; then
                echo "$(date '+%F %T') [keepalive] 重建 shell 助手（输入注入）" >&2
            fi
        fi
    fi
    sleep 5
done
