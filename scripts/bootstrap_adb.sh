#!/usr/bin/env bash
# bootstrap_adb.sh — M0 双模拟器环境拉起（健康检查/安装/转发/授权自动化）。
# 源端=emulator-5554，客户端=emulator-5556（--source-serial/--client-serial 可覆盖）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_SERIAL="${SOURCE_SERIAL:-emulator-5554}"
CLIENT_SERIAL="${CLIENT_SERIAL:-emulator-5556}"
SOURCE_HOST_PORT="${SOURCE_HOST_PORT:-22333}"
SOURCE_UDP_HOST_PORT="${SOURCE_UDP_HOST_PORT:-22334}"
SHOTS_DIR="$ROOT/docs/bootstrap-screenshots"
mkdir -p "$SHOTS_DIR"

say() { echo "[bootstrap] $*"; }
die() { echo "[bootstrap][FATAL] $*" >&2; exit 1; }

need_adb() { command -v adb >/dev/null || die "adb 不在 PATH"; }

# ── 1. 双模拟器健康检查 ──────────────────────────────────────────────
health_check() {
  need_adb
  adb devices | grep -q "$SOURCE_SERIAL" || die "源端 $SOURCE_SERIAL 未连接（adb devices）"
  adb devices | grep -q "$CLIENT_SERIAL" || die "客户端 $CLIENT_SERIAL 未连接"
  adb -s "$SOURCE_SERIAL" wait-for-device
  adb -s "$CLIENT_SERIAL" wait-for-device
  say "健康检查通过：$SOURCE_SERIAL / $CLIENT_SERIAL"
}

# ── 2. 构建 + 四产物安装（source APK / loadgen APK / client APK / helper dex）──
build_and_install() {
  say "构建 APK（增量）…"
  (cd "$ROOT/source" && ./gradlew assembleDebug -q)
  (cd "$ROOT/source/loadgen" && ./gradlew assembleDebug -q)
  (cd "$ROOT/client" && ./gradlew assembleDebug -q)
  bash "$ROOT/scripts/build_shell_helper.sh" >/dev/null

  adb -s "$SOURCE_SERIAL" install -r -t "$ROOT/source/app/build/outputs/apk/debug/app-debug.apk"
  adb -s "$SOURCE_SERIAL" install -r -t "$ROOT/source/loadgen/app/build/outputs/apk/debug/app-debug.apk"
  adb -s "$CLIENT_SERIAL" install -r -t "$ROOT/client/app/build/outputs/apk/debug/app-debug.apk"
  adb -s "$SOURCE_SERIAL" push "$ROOT/source/shellhelper/build/shellhelper.dex" /data/local/tmp/shellhelper.dex
  say "安装完成：source/loadgen → $SOURCE_SERIAL；client → $CLIENT_SERIAL；helper dex → /data/local/tmp"
}

# ── 3. adb root + 端口转发（TCP forward + UDP redir/relay）───────────
setup_forwarding() {
  adb -s "$SOURCE_SERIAL" root >/dev/null || say "（源端 adb root 失败——shell 助手将不可用）"
  sleep 1
  adb -s "$CLIENT_SERIAL" root >/dev/null || true
  sleep 1

  # LAN 直连：客户端 10.0.2.2:22333 → 主机环回 → 源端模拟器 22333
  adb -s "$SOURCE_SERIAL" forward --remove tcp:"$SOURCE_HOST_PORT" >/dev/null 2>&1 || true
  adb -s "$SOURCE_SERIAL" forward "tcp:$SOURCE_HOST_PORT" "tcp:$SOURCE_HOST_PORT"
  # 帧流/控制辅助端口（shell 助手 22340/22341 经源端本地；客户端无需）

  # UDP：adb 无 UDP 转发 → 模拟器控制台 redir（adb emu 自动携带控制台 token）
  adb -s "$SOURCE_SERIAL" emu redir del udp:"$SOURCE_UDP_HOST_PORT" >/dev/null 2>&1 || true
  if adb -s "$SOURCE_SERIAL" emu redir add udp:"$SOURCE_UDP_HOST_PORT":"$SOURCE_UDP_HOST_PORT" | grep -q OK; then
    say "UDP redir 就绪（host $SOURCE_UDP_HOST_PORT → 源端模拟器）"
  elif adb -s "$SOURCE_SERIAL" emu redir list | grep -q "udp:$SOURCE_UDP_HOST_PORT"; then
    say "UDP redir 已存在（复用）"
  else
    say "（UDP redir 失败——客户端视频将自动降级 TCP，功能不受损）"
  fi
  say "转发就绪：tcp $SOURCE_HOST_PORT；udp $SOURCE_UDP_HOST_PORT（redir）"
}

# ── 4. shell 助手启动（app_process，scrcpy 范式）────────────────────
start_shell_helper() {
  adb -s "$SOURCE_SERIAL" shell "CLASSPATH=/data/local/tmp/shellhelper.dex nohup app_process /system/bin --nice-name=cloudphone-helper cloudphone.shell.ShellHelper >/dev/null 2>&1 &" || true
  sleep 1
  if adb -s "$SOURCE_SERIAL" shell "command -v nc >/dev/null && echo PING | nc 127.0.0.1 22340 -w 1" 2>/dev/null | grep -q PONG; then
    say "shell 助手就绪（127.0.0.1:22340）"
  else
    say "（shell 助手应答校验跳过——nc 缺失或未启动；源端 App 将自行检测并降级提示）"
  fi
}

# ── 5. 云剪贴板输入法启用（M3 aux-channels-m3：ime enable/set + 状态自检）──────────
# 剪贴板双向与中文输入（TEXT_INPUT commitText）的合法焦点代理；未启用时源端走降级路径
# （ASCII 兜底 input text / 错误回执），客户端按钮置灰提示。
setup_ime() {
  need_adb
  local ime_id="cloudphone.source/.ime.CloudImeService"
  adb -s "$SOURCE_SERIAL" shell ime list -s 2>/dev/null | grep -q "CloudImeService" || \
    adb -s "$SOURCE_SERIAL" shell ime enable "$ime_id" 2>/dev/null || \
    say "（ime enable 失败——源端 APK 可能未含 IME 组件，重新安装 APK 后重试）"
  adb -s "$SOURCE_SERIAL" shell ime set "$ime_id" 2>/dev/null || \
    say "（ime set 失败——保持系统默认输入法，剪贴板反向/中文注入不可用）"
  if adb -s "$SOURCE_SERIAL" shell ime list -s 2>/dev/null | grep -q "CloudImeService"; then
    say "云剪贴板输入法已启用（$ime_id）"
  else
    say "（云剪贴板输入法未启用——剪贴板/中文输入将降级；无 adb 时经源端设置页手动启用）"
  fi
}

# ── 6. MediaProjection 授权自动化（uiautomator 找"开始/START NOW"按钮，3 次重试+截图）──
# 解析 uiautomator dump 中匹配文本节点的中心坐标（python 实现，输出 "x y" 或空）
center_of_text() { # $1=xml路径 $2=正则
  python3 - "$1" "$2" <<'PYEOF'
import re, sys
xml, pattern = sys.argv[1], sys.argv[2]
try:
    content = open(xml, encoding="utf-8", errors="replace").read()
except OSError:
    sys.exit(0)
for m in re.finditer(r'<node[^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', content):
    text = m.group(1)
    x1, y1, x2, y2 = (int(m.group(i)) for i in range(2, 6))
    if re.search(pattern, text):
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PYEOF
}

# 投影活性单查：正向判活——dumpsys 列出 TYPE_SCREEN_CAPTURE 投影条目才算活。
# 勿用反向判死（grep "Media Projection: null"）：本机死态输出实为两行
# "Media Projection: \nnull"，该模式永不命中 → 永远误报存活（曾致 watch 沉默）。
projection_alive() {
  adb -s "$SOURCE_SERIAL" shell dumpsys media_projection 2>/dev/null | grep -q "TYPE_SCREEN_CAPTURE"
}

wait_projection_alive() { # $1=超时秒（每秒一查）
  local timeout="${1:-15}" i
  for ((i = 0; i < timeout; i++)); do
    if projection_alive; then return 0; fi
    sleep 1
  done
  return 1
}

# 授权落地即建共享采集管线（capture-lifecycle）：凭据一次性+闲置 ~60s 失效，
# 只授权不建管线会让凭据死在等待窗口里（wire-e2e-credential-lifecycle 缺口①的根治面）。
# 管线常驻后任意客户端档位经 adaptTo 零令牌挂接，不再有时效窗口。
build_capture_pipeline() {
  adb -s "$SOURCE_SERIAL" shell am start-foreground-service -n cloudphone.source/.StreamService \
    -a cloudphone.source.START_CAPTURE \
    --ei width 1280 --ei height 720 --ei fps 60 --ei bitrateKbps 8000 >/dev/null 2>&1
}

# 管线就绪确认：读 tap 时刻之后的 logcat（-T 设备时刻标记，避免命中本轮之前的 stale ready 行）
# 注意：adb shell 会重组参数吃掉引号，时间串含空格必须整条命令打包 + 内层单引号
capture_ready_since() { # $1=起点 "MM-DD HH:MM:SS.mmm" $2=超时秒
  local tmark="$1" timeout="${2:-20}" i
  for ((i = 0; i < timeout; i++)); do
    if adb -s "$SOURCE_SERIAL" shell "logcat -d -T '$tmark' -s cloudphone-source" 2>/dev/null |
      grep -q "shared capture ready"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

grant_projection() {
  # 前置复位：HOME 清遮挡——实测同任务其他 Activity（LatencyTestActivity 形态）会挡住授予
  # 按钮，tap 命中的是别的东西（台账 todo: bootstrap-grant-confirmation ②）
  adb -s "$SOURCE_SERIAL" shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
  sleep 1
  adb -s "$SOURCE_SERIAL" shell am start -n cloudphone.source/.MainActivity >/dev/null
  sleep 2
  local try x y tmark
  for try in 1 2 3; do
    # 应用内"授予"按钮
    adb -s "$SOURCE_SERIAL" shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || true
    adb -s "$SOURCE_SERIAL" shell "cat /sdcard/window_dump.xml" > /tmp/window_dump.xml 2>/dev/null || true
    read -r x y <<< "$(center_of_text /tmp/window_dump.xml '授予')"
    if [[ -n "${x:-}" ]]; then
      adb -s "$SOURCE_SERIAL" shell input tap "$x" "$y"
      sleep 2
    fi
    # 系统授权确认弹窗（中文"立即开始"/英文"START NOW"）
    adb -s "$SOURCE_SERIAL" shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || true
    adb -s "$SOURCE_SERIAL" shell "cat /sdcard/window_dump.xml" > /tmp/window_dump.xml 2>/dev/null || true
    read -r x y <<< "$(center_of_text /tmp/window_dump.xml '立即开始|开始采集|Start [Nn]ow|Share screen|Cast')"
    if [[ -n "${x:-}" ]]; then
      # 时刻标记须在 tap 前取：后续 ready 行必须晚于它才算本轮新产出
      # （adb shell 引号重组陷阱：格式串必须整条命令打包传设备 shell）
      tmark="$(adb -s "$SOURCE_SERIAL" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')"
      adb -s "$SOURCE_SERIAL" shell input tap "$x" "$y"
      # 活性确认（台账 todo: bootstrap-grant-confirmation ①）：
      # ① dumpsys 投影非 null ② logcat 本轮 fresh "shared capture ready"（授权即建管线）
      if wait_projection_alive 15 && build_capture_pipeline && capture_ready_since "$tmark" 20; then
        adb -s "$SOURCE_SERIAL" exec-out screencap -p > "$SHOTS_DIR/projection_grant_try${try}.png"
        say "MediaProjection 授权+管线就绪（活性确认通过，截图 $SHOTS_DIR/projection_grant_try${try}.png）"
        return 0
      fi
      say "授权已点击但活性确认未过（try=$try：投影未落地或管线未就绪）——重试"
    fi
    adb -s "$SOURCE_SERIAL" exec-out screencap -p > "$SHOTS_DIR/projection_grant_try${try}.png"
    sleep 1
  done
  adb -s "$SOURCE_SERIAL" exec-out screencap -p > "$SHOTS_DIR/projection_grant_failed.png"
  say "授权自动化 3 次未确认成功——请人工在源端完成一次授权（截图 $SHOTS_DIR/projection_grant_failed.png）"
  return 1
}

# grant 前置（与 all 分支对齐，台账 todo: bootstrap-grant-confirmation ③）：
# freezer 关闭防 tap 延迟 13-21s→重复授权互杀；SAW 登记供设备侧自愈后台拉起；
# 先起 FGS（顺序铁律：getMediaProjection 时必须已有 mediaProjection 型前台服务）
grant_prereqs() {
  adb -s "$SOURCE_SERIAL" shell svc power stayon true >/dev/null
  adb -s "$SOURCE_SERIAL" shell device_config put activity_manager_native_boot cached_apps_freezer_enabled false >/dev/null 2>&1 || true
  adb -s "$SOURCE_SERIAL" shell appops set cloudphone.source SYSTEM_ALERT_WINDOW allow
  adb -s "$SOURCE_SERIAL" shell am start-foreground-service -n cloudphone.source/.StreamService >/dev/null
  sleep 1
}

# ── main ────────────────────────────────────────────────────────────
case "${1:-all}" in
  all)
    health_check
    build_and_install
    setup_forwarding
    start_shell_helper
    setup_ime
    # 屏幕常亮：虚拟显示镜像依赖宿主屏持续合成，熄屏会让编码器断粮
    adb -s "$SOURCE_SERIAL" shell svc power stayon true >/dev/null
    adb -s "$CLIENT_SERIAL" shell svc power stayon true >/dev/null
    # 关闭 cached-app freezer（admin-web-browser-client-1794415508 事故固化）：
    # freezer 冻结进程会使授权/控件 input tap 延迟 13-21s 才生效，grant 自动化在
    # 「对话框仍在」的假象下重复 tap 授予 → 同 UID 第二个投影落地时系统收回第一个
    # 活跃投影（一投影一应用强制）→ shared capture lost 级联清闸。关闭后 tap 即时
    # 生效，单次授权序列稳定；device_config 持久化，幂等重放无害。
    adb -s "$SOURCE_SERIAL" shell device_config put activity_manager_native_boot cached_apps_freezer_enabled false >/dev/null 2>&1 || true
    adb -s "$CLIENT_SERIAL" shell device_config put activity_manager_native_boot cached_apps_freezer_enabled false >/dev/null 2>&1 || true
    # 触摸延迟修复（SPEC 5.3）：输入窗口合成驱动需要 SYSTEM_ALERT_WINDOW（透明 1×1 悬窗
    # 以 5fps 失效自身，强制 display 0 逐次合成）。属设备侧一次性登记；未授权时源端降级运行并 WARN。
    # 同时是 capture-lifecycle 设备侧自愈的前置（后台拉起 MainActivity 需 SAW）。
    adb -s "$SOURCE_SERIAL" shell appops set cloudphone.source SYSTEM_ALERT_WINDOW allow
    say "SYSTEM_ALERT_WINDOW 已登记（InputComposer 输入窗口合成驱动 + 自愈后台拉起）"
    # 顺序关键：先起服务（Android 14 要求 getMediaProjection 时已有 mediaProjection FGS），再授权；
    # grant_projection 成功后立即经 ACTION_START_CAPTURE 建共享采集管线（授权即消费，
    # 消灭凭据闲置失效窗口），客户端此后任意时刻连接都可零令牌挂接。
    adb -s "$SOURCE_SERIAL" shell am start-foreground-service -n cloudphone.source/.StreamService >/dev/null
    sleep 1
    grant_projection
    say "环境就绪：共享采集管线已常驻，客户端 App 连 $SOURCE_SERIAL 的 $SOURCE_HOST_PORT 即可观看（无需人工启动服务）"
    say "长时运行保障：投影失活时源端自愈（SAW+helper）自动重授权；宿主可另起 '$0 watch' 兜底巡检"
    ;;
  health) health_check ;;
  install) health_check && build_and_install ;;
  forward) setup_forwarding ;;
  helper) start_shell_helper ;;
  ime) setup_ime ;;
  grant)
    # 对齐 all 分支前置（台账 todo: bootstrap-grant-confirmation ③）：
    # freezer 关闭防 tap 延迟互杀；SAW 登记供自愈；先起 FGS（顺序铁律）
    need_adb
    adb -s "$SOURCE_SERIAL" wait-for-device
    grant_prereqs
    grant_projection
    ;;
  watch)
    # 宿主侧长驻看门狗（capture-lifecycle 第三层兜底）：巡检投影活性，
    # 失活即自动重授权+活性确认。覆盖设备侧自愈无能为力的形态
    # （进程被杀/helper 未拉起/重启后 SAW 丢失等）。设备侧自愈为主，本路径兜底。
    need_adb
    health_check
    WATCH_INTERVAL="${WATCH_INTERVAL:-10}"
    say "watch 启动：每 ${WATCH_INTERVAL}s 巡检投影活性（WATCH_INTERVAL 环境变量可调，Ctrl-C 退出）"
    while true; do
      # 设备掉线（模拟器关闭/adb 断开）时不误报，仅等待恢复
      if adb -s "$SOURCE_SERIAL" get-state 2>/dev/null | grep -q device; then
        if ! projection_alive; then
          say "$(date '+%F %T') 投影失活 → 自动重授权+重建管线"
          start_shell_helper
          grant_projection || say "$(date '+%F %T') 重授权未确认成功（下轮巡检继续）"
        fi
      fi
      sleep "$WATCH_INTERVAL"
    done
    ;;
  *) echo "用法: $0 [all|health|install|forward|helper|ime|grant|watch]" >&2; exit 2 ;;
esac
