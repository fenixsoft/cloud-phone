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
STAGE_NAME=""
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

# ── 段实现（后续任务填充）─────────────────────────────────────────────
stage_push()   { stage_end skip "（待实现）"; return 0; }   # 1.2
stage_spawn()  { stage_end skip "（待实现）"; return 0; }   # 2.1
stage_ping()   { stage_end skip "（待实现）"; return 0; }   # 2.1
stage_create() { stage_end skip "（待实现）"; return 0; }   # 2.2
collect_diagnostics()  { : ; }                              # 3.1
classify_failure()     { : ; }                              # 3.1
write_json()           { : ; }                              # 3.2
print_report()         { : ; }                              # 3.2/3.3/4.1

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
  stage_begin PUSH "$T_PUSH";   stage_push   || ok=0
  if [[ $ok -eq 1 ]]; then stage_begin SPAWN "$T_SPAWN"; stage_spawn  || ok=0; fi
  if [[ $ok -eq 1 ]]; then stage_begin PING  "$T_PING";  stage_ping   || ok=0; fi
  if [[ $ok -eq 1 ]]; then stage_begin CREATE "$T_CREATE"; stage_create || ok=0; fi
  if [[ $ok -eq 1 && "$VERDICT" == "UNKNOWN" ]]; then VERDICT="OK"; fi
  if [[ $ok -eq 0 ]]; then collect_diagnostics; classify_failure; fi
  write_json
  print_report
  cleanup
  exit "$(exit_code_for "$VERDICT")"
}

main "$@"
