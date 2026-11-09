#!/usr/bin/env bash
# realdevice_bootstrap.sh — 真机一键接入与分段诊断（single-app-realdevice-retest）。
#
# 分段探针链 PUSH→SPAWN→PING→CREATE，每段独立超时（PUSH 60s / SPAWN 15s / PING 30s / CREATE 40s），
# 前段失败不阻塞输出已有段结论。结论枚举（证据绑定，禁止无证据下结论）：
#   OK               : 四段全部通过（CREATE 后助手存活复验 + 虚拟显示登记核验）
#   MIRROR_LIMIT     : PING 成功 + CREATE 段进程被静默 SIGKILL（无 crash 记录）—— device-notes §2.1 特征
#   SELINUX_DENIED   : 诊断包存在关联助手上下文（su:s0 / u:r:shell）的 avc: denied 记录
#   SHELL_UNAVAILABLE: app_process 拉起即失败（user 版镜像限制特征；单应用模式保留 FULLSCREEN 降级路径）
#   NO_DEVICE        : adb devices 无任何在线设备
#   UNKNOWN          : 其余失败（证据不充分，人工分析诊断包）
#
# 用法:
#   bash scripts/realdevice_bootstrap.sh [--serial <serial>] [--probe-only] [--full] [--json <path>]
#     --serial <serial>  多设备在线时必填；单设备可省略
#     --probe-only       只探不改：不构建、不推送，直接用设备侧已有 dex
#     --full             强制重走构建 + 幂等推送（需要 Android SDK 与 JDK）
#     --json <path>      JSON 摘要落盘路径（默认 docs/realdevice-probe-report-<serial>-<ts>.json）
#   默认（无模式开关）= auto：本地无构建产物或设备侧 hash 不一致时构建并幂等推送
#
# 退出码: 0=OK；1=NO_DEVICE；2=参数/环境错误；3=探针非 OK 结论
# 分工边界：本脚本=真机接入与分段诊断；模拟器环境编排（forward/redir/授权）见 scripts/bootstrap_adb.sh，
# 两者互相不调用。
set -uo pipefail

# ── 常量 ─────────────────────────────────────────────────────────────
DEV_DEX_DIR="/data/local/tmp/cloudphone"
DEV_DEX="$DEV_DEX_DIR/shellhelper.dex"
DEV_LOG="/data/local/tmp/cloudphone-helper-probe.log"
HELPER_CLASS="cloudphone.shell.ShellHelper"
HELPER_NICE="cloudphone-helper"
CTRL_PORT=22340
T_PUSH=60; T_SPAWN=15; T_PING=30; T_CREATE=40
CREATE_W=720; CREATE_H=1280; CREATE_DPI=320
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ── 状态 ─────────────────────────────────────────────────────────────
SERIAL="" MODE="auto" JSON_PATH="" STAGE_DIR=""
VERDICT="UNKNOWN"
STAGE_NAME=""; FAIL_STAGE=""
declare -A STAGE_STATUS STAGE_DETAIL STAGE_MS
EVIDENCE_AVC=() EVIDENCE_KILL=() EVIDENCE_CRASH=() VERDICT_EVIDENCE=()
DIAG_COLLECTED=0
CREATED_DISPLAY=""
FWD_PORT=""

say()  { echo "[realdevice] $*"; }
warn() { echo "[realdevice][WARN] $*" >&2; }
die()  { echo "[realdevice][FATAL] $*" >&2; exit "${2:-2}"; }

usage() { grep -E '^#   (bash|  --)|^# 退出码|^# 分工' "${BASH_SOURCE[0]}" | sed 's/^# \{0,2\}//'; }

# ── 1.1 参数解析 ─────────────────────────────────────────────────────
parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --serial)    [[ $# -ge 2 ]] || die "--serial 需要值"; SERIAL="$2"; shift 2 ;;
      --probe-only) MODE="probe-only"; shift ;;
      --full)      MODE="full"; shift ;;
      --json)      [[ $# -ge 2 ]] || die "--json 需要值"; JSON_PATH="$2"; shift 2 ;;
      -h|--help)   usage; exit 0 ;;
      *) die "未知参数: $1（--help 看用法）" ;;
    esac
  done
  [[ "$MODE" == "probe-only" && "$MODE" == "full" ]] && die "--probe-only 与 --full 互斥"  # 不可达，防御
}

# adb 快捷查询（单行、去 \r、带硬超时）
adb_q() { timeout 10 adb -s "$SERIAL" shell "$1" 2>/dev/null | tr -d '\r'; }

# ── 1.1 设备发现 ─────────────────────────────────────────────────────
pick_device() {
  command -v adb >/dev/null || die "adb 不在 PATH"
  local map n
  map="$(timeout 10 adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')"
  if [[ -z "$map" ]]; then
    echo "[realdevice] 结论: NO_DEVICE（adb devices 无在线设备）"
    exit 1
  fi
  n="$(echo "$map" | wc -l)"
  if [[ -n "$SERIAL" ]]; then
    echo "$map" | grep -qx "$SERIAL" || die "--serial $SERIAL 不在线（在线: $(echo "$map" | tr '\n' ' ')）"
  elif [[ "$n" -gt 1 ]]; then
    echo "[realdevice][FATAL] 多设备在线但未指定 --serial，候选:" >&2
    echo "$map" | sed 's/^/  - /' >&2
    echo "[realdevice] 用法: bash scripts/realdevice_bootstrap.sh --serial <serial>" >&2
    exit 2
  else
    SERIAL="$(echo "$map" | head -1)"
  fi
}

# ── 1.1 环境记录 ─────────────────────────────────────────────────────
ENV_RELEASE=""; ENV_SDK=""; ENV_ABI=""; ENV_TYPE=""
record_env() {
  ENV_RELEASE="$(adb_q "getprop ro.build.version.release")"
  ENV_SDK="$(adb_q "getprop ro.build.version.sdk")"
  ENV_ABI="$(adb_q "getprop ro.product.cpu.abi")"
  ENV_TYPE="$(adb_q "getprop ro.build.type")"
  say "设备环境: serial=$SERIAL android=${ENV_RELEASE:-?}(api${ENV_SDK:-?}) abi=${ENV_ABI:-?} build=${ENV_TYPE:-?}"
}

# ── 段框架（每段独立计时/状态）────────────────────────────────────────
STAGE_T0=0
stage_begin() { STAGE_NAME="$1"; say "── [$STAGE_NAME] 开始（超时 ${2}s）…"; STAGE_T0="$(date +%s%3N)"; }
stage_end() {
  local st="$1" detail="${2:-}"
  STAGE_STATUS[$STAGE_NAME]="$st"
  STAGE_DETAIL[$STAGE_NAME]="$detail"
  STAGE_MS[$STAGE_NAME]=$(( $(date +%s%3N) - STAGE_T0 ))
  say "── [$STAGE_NAME] $st (${STAGE_MS[$STAGE_NAME]}ms)${detail:+: $detail}"
}

# ── 落盘路径（JSON 默认名 + 诊断侧车目录）──────────────────────────────
resolve_paths() {
  local ts fn
  ts="$(date +%Y%m%d-%H%M%S)"
  fn="$(echo "$SERIAL" | tr -c 'A-Za-z0-9._-' '_')"
  if [[ -z "$JSON_PATH" ]]; then
    JSON_PATH="$ROOT/docs/realdevice-probe-report-${fn}-${ts}.json"
  fi
  mkdir -p "$(dirname "$JSON_PATH")"
  STAGE_DIR="${JSON_PATH%.json}-diag"
  rm -rf "$STAGE_DIR"; mkdir -p "$STAGE_DIR"
}

# ── 1.2 PUSH 段：构建 + 幂等推送 ──────────────────────────────────────
LOCAL_DEX="$ROOT/source/shellhelper/build/shellhelper.dex"
stage_push() {
  # probe-only：只探不改，直接用设备侧现有 dex
  if [[ "$MODE" == "probe-only" ]]; then
    if [[ "$(adb_q "[ -f $DEV_DEX ] && echo yes")" == "yes" ]]; then
      stage_end skip "probe-only：使用设备侧现有 dex（$DEV_DEX）"
      return 0
    fi
    stage_end fail "probe-only：设备侧无 dex（$DEV_DEX 不存在）——去掉 --probe-only 重跑"
    VERDICT_EVIDENCE+=("PUSH: --probe-only 且设备侧无 dex，探针无法继续")
    return 1
  fi
  # full：强制重走构建；auto：本地无产物才构建
  if [[ "$MODE" == "full" || ! -f "$LOCAL_DEX" ]]; then
    say "构建 shellhelper.dex（scripts/build_shell_helper.sh，产物 hash 幂等）…"
    if ! timeout 300 bash "$ROOT/scripts/build_shell_helper.sh" >"$STAGE_DIR/build.log" 2>&1; then
      stage_end fail "构建失败（日志 $STAGE_DIR/build.log）"
      VERDICT_EVIDENCE+=("PUSH: 构建失败，尾 3 行: $(tail -3 "$STAGE_DIR/build.log" 2>/dev/null | tr '\n' '|')")
      return 1
    fi
  fi
  [[ -f "$LOCAL_DEX" ]] || { stage_end fail "本地 dex 缺失（$LOCAL_DEX）"; VERDICT_EVIDENCE+=("PUSH: 本地 dex 缺失"); return 1; }
  local lmd5 dmd5
  lmd5="$(md5sum "$LOCAL_DEX" | awk '{print $1}')"
  adb_q "mkdir -p $DEV_DEX_DIR" >/dev/null
  dmd5="$(adb_q "md5sum $DEV_DEX 2>/dev/null" | awk '{print $1}')"
  if [[ -n "$dmd5" && "$dmd5" == "$lmd5" ]]; then
    stage_end skip "设备侧 dex hash 一致（md5=$lmd5），跳过重推（幂等）"
    return 0
  fi
  say "推送 dex → $DEV_DEX（local_md5=$lmd5，device_md5=${dmd5:-无}）…"
  if ! timeout "$T_PUSH" adb -s "$SERIAL" push "$LOCAL_DEX" "$DEV_DEX" >"$STAGE_DIR/push.log" 2>&1; then
    stage_end fail "adb push 失败（日志 $STAGE_DIR/push.log）"
    VERDICT_EVIDENCE+=("PUSH: adb push 失败: $(tail -2 "$STAGE_DIR/push.log" 2>/dev/null | tr '\n' '|')")
    return 1
  fi
  dmd5="$(adb_q "md5sum $DEV_DEX 2>/dev/null" | awk '{print $1}')"
  if [[ "$dmd5" != "$lmd5" ]]; then
    stage_end fail "推送后 hash 校验不一致（local=$lmd5 device=${dmd5:-无}）"
    VERDICT_EVIDENCE+=("PUSH: 推送后 hash 不一致")
    return 1
  fi
  stage_end pass "推送完成（md5=$lmd5）"
  return 0
}
# ── 2.1 SPAWN+PING 探针 ──────────────────────────────────────────────
HELPER_PID=""; PONG_LINE=""; CAPS_LINE=""; SPAWN_LOG=""

helper_alive() {  # 助手进程行（ps NAME 列 = nice-name），空串=不存活
  adb_q "ps -A 2>/dev/null | grep -F '$HELPER_NICE' | grep -v grep | head -1"
}

kill_stale() {  # 清理残留助手（22340 占用会误判新 dex）。注意 --nice-name 使 /proc/<pid>/cmdline
                # 仅剩 nice-name 本身（实测），故按 nice-name 匹配；[r] 技巧规避查询 shell 自匹配
  adb_q "pkill -f 'cloudphone-helpe[r]' 2>/dev/null" >/dev/null
  sleep 1
}

pick_forward_port() {
  python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()'
}

# 经 adb forward + bash /dev/tcp 单次控制命令（不依赖设备侧 nc；连不上/无响应返回空）
ctrl_once() {  # $1=host_port $2=payload $3=read_timeout
  local port="$1" payload="$2" rt="$3" line=""
  adb -s "$SERIAL" forward "tcp:$port" "tcp:$CTRL_PORT" >/dev/null 2>&1 || return 1
  ( exec 3<>"/dev/tcp/127.0.0.1/$port" && printf '%s\n' "$payload" >&3 && read -t "$rt" -r line <&3; printf '%s' "$line" ) 2>/dev/null
}

stage_spawn() {
  kill_stale
  adb_q "rm -f $DEV_LOG" >/dev/null
  say "拉起助手: CLASSPATH=$DEV_DEX app_process /system/bin --nice-name=$HELPER_NICE $HELPER_CLASS（日志 $DEV_LOG）"
  # nohup + 立即退出会话：助手脱离 adb shell 会话存活（bootstrap_adb.sh 同款范式）
  adb -s "$SERIAL" shell "CLASSPATH=$DEV_DEX nohup app_process /system/bin --nice-name=$HELPER_NICE $HELPER_CLASS > $DEV_LOG 2>&1 &" >/dev/null 2>&1 || true
  local waited=0 line=""
  while (( waited < T_SPAWN )); do
    sleep 2; waited=$((waited+2))
    line="$(helper_alive)"
    [[ -n "$line" ]] && break
  done
  SPAWN_LOG="$(adb_q "cat $DEV_LOG 2>/dev/null")"
  if [[ -z "$line" ]]; then
    stage_end fail "app_process 进程在 ${T_SPAWN}s 内未存活（user 版镜像限制特征候选）"
    VERDICT_EVIDENCE+=("SPAWN: ${T_SPAWN}s 窗口内助手进程未存活; 拉起日志: $(echo "$SPAWN_LOG" | tail -3 | tr '\n' '|')")
    return 1
  fi
  HELPER_PID="$(echo "$line" | awk '{print $2}')"   # ps -A 首列 USER、次列 PID
  stage_end pass "助手进程存活 pid=$HELPER_PID（日志: $(echo "$SPAWN_LOG" | tail -1)）"
  return 0
}

stage_ping() {
  FWD_PORT="$(pick_forward_port)"
  local waited=0 reply=""
  while (( waited < T_PING )); do
    reply="$(ctrl_once "$FWD_PORT" "PING" 5)"
    [[ "$reply" == PONG* ]] && break
    sleep 2; waited=$((waited+2))
    if [[ -z "$(helper_alive)" ]]; then
      SPAWN_LOG="$(adb_q "cat $DEV_LOG 2>/dev/null")"
      stage_end fail "PING 等待期间助手进程消失（静默死亡）"
      VERDICT_EVIDENCE+=("PING: 等待窗口内助手进程消失（pid=$HELPER_PID）; 拉起日志尾: $(echo "$SPAWN_LOG" | tail -3 | tr '\n' '|')")
      return 1
    fi
  done
  if [[ "$reply" == PONG* ]]; then
    PONG_LINE="$reply"
    CAPS_LINE="$(ctrl_once "$FWD_PORT" "CAPS" 5)"
    stage_end pass "PING→PONG 往返正常（${PONG_LINE}；CAPS: ${CAPS_LINE:-无响应}）"
    return 0
  fi
  stage_end fail "${T_PING}s 内无 PONG（进程存活但控制口无响应）"
  VERDICT_EVIDENCE+=("PING: 助手存活但 22340 无 PONG（${T_PING}s）")
  return 1
}
# ── 2.2 CREATE 探针（虚拟显示 + MediaCodec 初始化冒烟）─────────────────
CREATED_DISPLAY=""; CREATE_SHAPE=""; VD_NAME="cloudphone-vd-${CREATE_W}x${CREATE_H}"
stage_create() {
  local reply alive_after vd_line
  say "下发 CREATE $CREATE_W $CREATE_H $CREATE_DPI（超时 ${T_CREATE}s）…"
  reply="$(ctrl_once "$FWD_PORT" "CREATE $CREATE_W $CREATE_H $CREATE_DPI" "$T_CREATE")"
  if [[ "$reply" == OK\ * ]]; then
    CREATED_DISPLAY="${reply#OK }"
    # §2.1 特征复验：静默 SIGKILL 常发生在 CREATE 执行/首帧合成窗口——等 3s 再核验进程与 VD 登记
    sleep 3
    alive_after="$(helper_alive)"
    vd_line="$(adb_q "dumpsys display 2>/dev/null | grep -F '$VD_NAME' | head -1")"
    if [[ -z "$alive_after" ]]; then
      CREATE_SHAPE="ok-then-gone"
      stage_end fail "CREATE 应答 OK（displayId=$CREATED_DISPLAY）但助手随后静默消失（§2.1 同型特征）"
      VERDICT_EVIDENCE+=("CREATE: 应答 OK 后进程静默消失（pid=$HELPER_PID, displayId=$CREATED_DISPLAY）")
      return 1
    fi
    if [[ -z "$vd_line" ]]; then
      CREATE_SHAPE="vd-missing"
      stage_end fail "CREATE 应答 OK 但 dumpsys display 未登记 $VD_NAME"
      VERDICT_EVIDENCE+=("CREATE: OK 应答但虚拟显示未登记（displayId=$CREATED_DISPLAY）")
      return 1
    fi
    CREATE_SHAPE="ok"
    stage_end pass "虚拟显示就绪 displayId=$CREATED_DISPLAY（$VD_NAME 已登记 + 助手存活复验通过）"
    return 0
  fi
  alive_after="$(helper_alive)"
  SPAWN_LOG="$(adb_q "cat $DEV_LOG 2>/dev/null")"
  if [[ "$reply" == ERR* ]]; then
    CREATE_SHAPE="err-reply"
    stage_end fail "CREATE 返回错误: $reply"
    VERDICT_EVIDENCE+=("CREATE: ERR 应答: $reply; 助手日志尾: $(echo "$SPAWN_LOG" | tail -3 | tr '\n' '|')")
    return 1
  fi
  if [[ -z "$alive_after" ]]; then
    CREATE_SHAPE="gone-during"
    stage_end fail "CREATE 期间助手进程消失（${T_CREATE}s 无应答且进程不复存在）"
    VERDICT_EVIDENCE+=("CREATE: ${T_CREATE}s 无应答且进程消失（pid=$HELPER_PID 曾于 PING 段存活）; 拉起日志尾: $(echo "$SPAWN_LOG" | tail -3 | tr '\n' '|')")
    return 1
  fi
  CREATE_SHAPE="alive-mute"
  stage_end fail "CREATE 超时无响应但进程仍存活（pid=$HELPER_PID）——与静默 SIGKILL 形态不符，保守判 UNKNOWN"
  VERDICT_EVIDENCE+=("CREATE: ${T_CREATE}s 超时无响应且进程存活（pid=$HELPER_PID）")
  return 1
}
# ── 3.1 诊断包收集器（任一段失败自动收集，D2）──────────────────────────
GETENFORCE=""; ID_LINE=""
collect_diagnostics() {
  [[ "$DIAG_COLLECTED" == 1 ]] && return 0
  DIAG_COLLECTED=1
  say "收集诊断包（logcat 500 行 / dmesg avc|kill / getenforce / build props / id）→ $STAGE_DIR/"
  timeout 15 adb -s "$SERIAL" shell "logcat -d -t 500" >"$STAGE_DIR/logcat-tail-500.txt" 2>&1
  timeout 15 adb -s "$SERIAL" shell "dmesg 2>/dev/null | grep -iE 'avc|kill'" >"$STAGE_DIR/dmesg-avc-kill.txt" 2>&1
  GETENFORCE="$(adb_q getenforce)"
  ID_LINE="$(adb_q id)"
  {
    echo "getenforce: $GETENFORCE"
    echo "id: $ID_LINE"
    timeout 15 adb -s "$SERIAL" shell "getprop ro.build.type ro.build.selinux ro.build.version.release ro.build.version.sdk ro.product.cpu.abi ro.build.fingerprint" 2>&1
  } >"$STAGE_DIR/build-props-selinux.txt"
  [[ -n "$SPAWN_LOG" ]] && printf '%s\n' "$SPAWN_LOG" >"$STAGE_DIR/helper-spawn-log.txt"
  # 证据行提取（原文进报告）：AVC 关联助手上下文 / am_kill 类 / crash 类
  mapfile -t EVIDENCE_AVC < <(grep -hiE 'avc: +denied' "$STAGE_DIR/dmesg-avc-kill.txt" "$STAGE_DIR/logcat-tail-500.txt" 2>/dev/null | grep -iE "$HELPER_CTX_PAT" | sort -u | head -5)
  mapfile -t EVIDENCE_KILL < <(grep -hiE 'am_kill|lmkd|lowmemory|SIGKILL|[Kk]illing' "$STAGE_DIR/logcat-tail-500.txt" 2>/dev/null | grep -iE "$HELPER_NICE|app_process|ShellHel[l]per" | sort -u | head -5)
  mapfile -t EVIDENCE_CRASH < <(grep -hiE 'FATAL EXCEPTION|AndroidRuntime|Force finishing|tombstone|SIGSEGV|SIGABRT' "$STAGE_DIR/logcat-tail-500.txt" 2>/dev/null | head -5)
  say "诊断证据: avc=${#EVIDENCE_AVC[@]} 行, kill=${#EVIDENCE_KILL[@]} 行, crash=${#EVIDENCE_CRASH[@]} 行, getenforce=${GETENFORCE:-?}"
}

# ── 3.1 结论判定（D1 证据绑定；优先级 SELINUX > MIRROR_LIMIT > SHELL_UNAVAILABLE > UNKNOWN）──
classify_failure() {
  if ((${#EVIDENCE_AVC[@]} > 0)); then
    VERDICT="SELINUX_DENIED"
    VERDICT_EVIDENCE+=("判定依据: 关联助手上下文（$HELPER_CTX_PAT）的 avc denied ${#EVIDENCE_AVC[@]} 行，首行: ${EVIDENCE_AVC[0]}")
    return 0
  fi
  case "$FAIL_STAGE" in
    SPAWN)
      VERDICT="SHELL_UNAVAILABLE"
      VERDICT_EVIDENCE+=("判定依据: SPAWN 段失败（app_process 未存活）且无 AVC 证据——user 版镜像限制特征（design D3）")
      ;;
    CREATE)
      case "$CREATE_SHAPE" in
        gone-during|ok-then-gone)
          if ((${#EVIDENCE_CRASH[@]} > 0)); then
            VERDICT="UNKNOWN"
            VERDICT_EVIDENCE+=("判定依据: CREATE 段进程消失但存在 crash 记录（代码级异常而非镜像限制）: ${EVIDENCE_CRASH[0]}")
          else
            VERDICT="MIRROR_LIMIT"
            VERDICT_EVIDENCE+=("判定依据: PING 成功 + CREATE 段进程静默消失（无 crash、无 AVC、getenforce=$GETENFORCE、build=$ENV_TYPE）——device-notes §2.1 特征（CREATE 形态: $CREATE_SHAPE）")
          fi
          ;;
        alive-mute|err-reply|vd-missing)
          VERDICT="UNKNOWN"   # 证据已在 stage 记录：进程存活但无响应 / ERR 应答 / VD 未登记
          ;;
      esac
      ;;
    *)
      VERDICT="UNKNOWN"       # PUSH / PING 段失败：证据已在 stage 记录，无更强枚举依据
      ;;
  esac
}
# ── 3.2 JSON 摘要落盘（python3 组装保证合法转义；缺 python3 时降级最小摘要）──
write_json() {
  local s
  : >"$STAGE_DIR/stages.tsv"
  for s in PUSH SPAWN PING CREATE; do
    [[ -n "${STAGE_STATUS[$s]:-}" ]] && printf '%s\t%s\t%s\t%s\n' "$s" "${STAGE_STATUS[$s]}" "${STAGE_MS[$s]:-0}" "${STAGE_DETAIL[$s]:-}" >>"$STAGE_DIR/stages.tsv"
  done
  printf '%s\n' "${VERDICT_EVIDENCE[@]}" >"$STAGE_DIR/verdict-evidence.txt" 2>/dev/null || : >"$STAGE_DIR/verdict-evidence.txt"
  printf '%s\n' "${EVIDENCE_AVC[@]}"    >"$STAGE_DIR/evidence-avc.txt"    2>/dev/null || : >"$STAGE_DIR/evidence-avc.txt"
  printf '%s\n' "${EVIDENCE_KILL[@]}"   >"$STAGE_DIR/evidence-kill.txt"   2>/dev/null || : >"$STAGE_DIR/evidence-kill.txt"
  printf '%s\n' "${EVIDENCE_CRASH[@]}"  >"$STAGE_DIR/evidence-crash.txt"  2>/dev/null || : >"$STAGE_DIR/evidence-crash.txt"
  if ! command -v python3 >/dev/null; then
    # 降级：最小合法 JSON（verdict/serial 均为受限字符集 token）
    printf '{"schema":"realdevice-probe-report/1","verdict":"%s","serial":"%s","mode":"%s","diagnostics_dir":"%s"}\n' \
      "$VERDICT" "$SERIAL" "$MODE" "$STAGE_DIR" >"$JSON_PATH"
    warn "python3 缺失，JSON 为最小摘要（诊断侧车文件在 $STAGE_DIR/）"
    return 0
  fi
  SERIAL="$SERIAL" MODE="$MODE" VERDICT="$VERDICT" JSON_PATH="$JSON_PATH" STAGE_DIR="$STAGE_DIR" \
  ENV_RELEASE="$ENV_RELEASE" ENV_SDK="$ENV_SDK" ENV_ABI="$ENV_ABI" ENV_TYPE="$ENV_TYPE" \
  GETENFORCE="$GETENFORCE" ID_LINE="$ID_LINE" HELPER_PID="$HELPER_PID" CREATED_DISPLAY="$CREATED_DISPLAY" \
  CREATE_SHAPE="$CREATE_SHAPE" FAIL_STAGE="$FAIL_STAGE" PONG_LINE="$PONG_LINE" CAPS_LINE="$CAPS_LINE" \
  T_PUSH="$T_PUSH" T_SPAWN="$T_SPAWN" T_PING="$T_PING" T_CREATE="$T_CREATE" \
  CREATE_W="$CREATE_W" CREATE_H="$CREATE_H" CREATE_DPI="$CREATE_DPI" \
  python3 - <<'PYEOF'
import datetime, json, os, re
sd = os.environ["STAGE_DIR"]
def read(f):
    p = os.path.join(sd, f)
    if not os.path.exists(p): return []
    return [l.rstrip("\n") for l in open(p, encoding="utf-8", errors="replace") if l.strip()]
def tail(f, n=10):
    ls = read(f)
    return "\n".join(ls[-n:])
stages = {}
for line in read("stages.tsv"):
    parts = line.split("\t")
    if len(parts) >= 4:
        stages[parts[0]] = {"status": parts[1], "duration_ms": int(parts[2] or 0), "detail": parts[3]}
props, getenforce = {}, ""
for l in read("build-props-selinux.txt"):
    if l.startswith("getenforce:"): getenforce = l.split(":", 1)[1].strip()
    else:
        m = re.match(r"\[([^\]]+)\]: \[([^\]]*)\]", l)
        if m: props[m.group(1)] = m.group(2)
report = {
    "schema": "realdevice-probe-report/1",
    "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
    "serial": os.environ["SERIAL"],
    "mode": os.environ["MODE"],
    "verdict": os.environ["VERDICT"],
    "verdict_evidence": read("verdict-evidence.txt"),
    "fail_stage": os.environ.get("FAIL_STAGE", "") or None,
    "stages": stages,
    "timeouts_seconds": {k: int(os.environ[k]) for k in ("T_PUSH", "T_SPAWN", "T_PING", "T_CREATE")},
    "create_probe": {
        "width": int(os.environ["CREATE_W"]), "height": int(os.environ["CREATE_H"]),
        "dpi": int(os.environ["CREATE_DPI"]),
        "display_id": os.environ.get("CREATED_DISPLAY") or None,
        "shape": os.environ.get("CREATE_SHAPE", ""),
    },
    "device": {
        "android_release": os.environ.get("ENV_RELEASE", ""),
        "sdk": os.environ.get("ENV_SDK", ""),
        "abi": os.environ.get("ENV_ABI", ""),
        "build_type": os.environ.get("ENV_TYPE", ""),
        "selinux": os.environ.get("GETENFORCE", "") or getenforce,
        "id": os.environ.get("ID_LINE", ""),
    },
    "build_props": props,
    "helper": {
        "pid": os.environ.get("HELPER_PID", "") or None,
        "pong": os.environ.get("PONG_LINE", ""),
        "caps": os.environ.get("CAPS_LINE", ""),
        "spawn_log_tail": tail("helper-spawn-log.txt"),
    },
    "diagnostics": {
        "dir": sd,
        "files": sorted(os.listdir(sd)) if os.path.isdir(sd) else [],
        "avc_denied": read("evidence-avc.txt"),
        "am_kill_like": read("evidence-kill.txt"),
        "crash_like": read("evidence-crash.txt"),
    },
    "next_steps": "按 docs/realdevice-retest-checklist.md 执行真机重测检查表；执行结果回写 docs/device-notes.md（§4/§8）",
}
with open(os.environ["JSON_PATH"], "w", encoding="utf-8") as f:
    json.dump(report, f, ensure_ascii=False, indent=2)
print(f"[realdevice] JSON 摘要已落盘: {os.environ['JSON_PATH']}（诊断包: {sd}/）")
PYEOF
}

# ── 3.2 人读报告（stdout）+ 3.3 构建形态分叉 + 4.1 网络指引 ────────────
verdict_explains() {
  case "$VERDICT" in
    OK)                say "结论解读: 助手四段全链可用（单应用模式基础就绪）→ 下一步按 docs/realdevice-retest-checklist.md §2『单应用会话』逐项验证" ;;
    MIRROR_LIMIT)      say "结论解读: §2.1 特征——PING 正常、CREATE 段进程被静默杀：镜像/策略限制而非代码缺陷 → 检查表 §1『shell 助手』项，按失败动作抓 sepolicy AVC 复核" ;;
    SELINUX_DENIED)    say "结论解读: sepolicy 拒绝 → 依据上方 AVC 行排查策略域（su:s0 / platform_app 差异），检查表 §1『shell 助手』失败动作" ;;
    SHELL_UNAVAILABLE) say "结论解读: app_process 拉起即失败（user 版限制特征）→ 单应用模式不可用，保留 FULLSCREEN（MediaProjection）降级路径；检查表 §1『shell 助手』" ;;
    NO_DEVICE)         say "结论解读: 无在线设备 → 确认 adb devices 与 USB/WiFi 调试连接后重跑" ;;
    *)                 say "结论解读: 证据不充分 → 人工分析诊断包 $STAGE_DIR/（logcat 尾/dmesg AVC/getenforce/props/id）" ;;
  esac
}

print_report() {
  local s e
  echo ""
  echo "════════ 真机接入分段诊断报告 ════════"
  echo "serial: $SERIAL  mode: $MODE  android: ${ENV_RELEASE:-?}(api${ENV_SDK:-?})  abi: ${ENV_ABI:-?}  build: ${ENV_TYPE:-?}"
  echo "分段结论:"
  for s in PUSH SPAWN PING CREATE; do
    [[ -n "${STAGE_STATUS[$s]:-}" ]] && printf '  %-7s %-5s (%sms) %s\n' "$s" "${STAGE_STATUS[$s]}" "${STAGE_MS[$s]:-0}" "${STAGE_DETAIL[$s]:-}"
  done
  echo "── 总结论: $VERDICT"
  for e in "${VERDICT_EVIDENCE[@]}"; do echo "  证据: $e"; done
  verdict_explains
  build_type_notes
  network_guidance
  echo "════════ 报告结束 ════════"
}

# ── 3.3 user/userdebug 分叉提示（待实现）──────────────────────────────
build_type_notes() { : ; }
# ── 4.1 网络通路指引（待实现）────────────────────────────────────────
network_guidance() { : ; }

cleanup() { [[ -n "$FWD_PORT" ]] && adb -s "$SERIAL" forward --remove "tcp:$FWD_PORT" >/dev/null 2>&1; return 0; }

exit_code_for() {
  case "$1" in
    OK) echo 0 ;;
    *) echo 3 ;;
  esac
}

# ── main ─────────────────────────────────────────────────────────────
main() {
  parse_args "$@"
  resolve_paths
  pick_device                      # NO_DEVICE: 输出结论 + exit 1（无误导性部分结论）
  record_env
  local ok=1
  FAIL_STAGE=""
  stage_begin PUSH "$T_PUSH";   stage_push   || { ok=0; FAIL_STAGE="PUSH"; }
  if [[ $ok -eq 1 ]]; then stage_begin SPAWN "$T_SPAWN"; stage_spawn  || { ok=0; FAIL_STAGE="SPAWN"; }; fi
  if [[ $ok -eq 1 ]]; then stage_begin PING  "$T_PING";  stage_ping   || { ok=0; FAIL_STAGE="PING"; }; fi
  if [[ $ok -eq 1 ]]; then stage_begin CREATE "$T_CREATE"; stage_create || { ok=0; FAIL_STAGE="CREATE"; }; fi
  if [[ $ok -eq 1 && "$VERDICT" == "UNKNOWN" ]]; then VERDICT="OK"; fi
  if [[ $ok -eq 0 ]]; then collect_diagnostics; classify_failure; fi
  write_json
  print_report
  cleanup
  exit "$(exit_code_for "$VERDICT")"
}

main "$@"
