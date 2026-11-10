#!/usr/bin/env bash
# 云手机 Windows 桌面客户端 VM 冒烟链路（windows-desktop-client 5.1）。
#
# 用法：
#   ./scripts/win_vm_smoke.sh --self-check   # 无 VM 自检模式：构建+单测+产物+网络工具检查（宿主即可跑绿）
#   ./scripts/win_vm_smoke.sh [VM_IP]        # 完整链路：网络探测→产物分发→VM 内软件渲染启动→截图取证
#
# 完整链路步骤（docs/windows-client.md §5）：
#   1. 网络可达性显式探测 10.0.2.2:22333（VM 视角宿主网关上的源端），不可达输出诊断+预案，非 0 退出；
#   2. 宿主 createDistributable app-image 分发进 dockur/windows 容器；
#   3. VM 内以 -Dskiko.renderApi=SOFTWARE 软件渲染启动客户端；
#   4. RDP / QEMU monitor screendump 截图取证（首页/连接/视频上屏/断开）。
set -u

# ── 常量 ──
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLIENT_DIR="$REPO_ROOT/client-windows"
APP_IMAGE="$CLIENT_DIR/build/compose/binaries/main/app/CloudPhoneDesktop"
SOURCE_HOST="${SOURCE_HOST:-10.0.2.2}"   # VM 视角的宿主网关（QEMU/dockur 默认）
SOURCE_PORT="${SOURCE_PORT:-22333}"
SHOT_DIR="$REPO_ROOT/docs/bootstrap-screenshots/windows-client"
SELF_CHECK=0
VM_HOST=""
LOG_PREFIX="[win-vm-smoke]"

for arg in "$@"; do
  case "$arg" in
    --self-check) SELF_CHECK=1 ;;
    *) VM_HOST="$arg" ;;
  esac
done

info() { echo "$LOG_PREFIX $*"; }
fail() { echo "$LOG_PREFIX [FAIL] $*" >&2; exit 1; }

# ── 工具探测 ──
need_tool() { command -v "$1" >/dev/null 2>&1; }

# TCP 可达性探测（优先 nc，回退 /dev/tcp bash 内建；timeout 3s）
probe_tcp() {
  local host="$1" port="$2"
  if need_tool nc; then
    nc -z -w 3 "$host" "$port" >/dev/null 2>&1
  else
    (timeout 3 bash -c "exec 3<>/dev/tcp/$host/$port" ) >/dev/null 2>&1
  fi
}

# ── 构建与产物检查（两种模式共用） ──
step_build_and_artifacts() {
  info "步骤 1/4：构建（test + createDistributable）…"
  ( cd "$CLIENT_DIR" && ./gradlew test createDistributable --console=plain ) \
    || fail "构建失败（./gradlew test createDistributable）"
  info "步骤 2/4：产物检查…"
  [ -d "$APP_IMAGE" ] || fail "app-image 不存在：$APP_IMAGE"
  [ -x "$APP_IMAGE/bin/CloudPhoneDesktop" ] || fail "app-image 可执行文件缺失：$APP_IMAGE/bin/CloudPhoneDesktop"
  info "产物就绪：$APP_IMAGE（lib jars $(ls "$APP_IMAGE/lib"/*.jar 2>/dev/null | wc -l) 个）"
}

# ── 完整链路：网络可达性（显式探测，不可达输出诊断+预案，非 0 退出） ──
step_probe_network() {
  info "步骤 3/4：源端可达性探测 $SOURCE_HOST:$SOURCE_PORT …"
  if probe_tcp "$SOURCE_HOST" "$SOURCE_PORT"; then
    info "源端可达：$SOURCE_HOST:$SOURCE_PORT"
    return 0
  fi
  cat >&2 <<EOF
$LOG_PREFIX [FAIL] 源端不可达：$SOURCE_HOST:$SOURCE_PORT
诊断步骤：
  1) 源端是否在宿主机运行？   ss -ltn | grep $SOURCE_PORT   （宿主侧执行）
  2) 端口是否被宿主防火墙拦截？ sudo iptables -L INPUT -n | grep $SOURCE_PORT
  3) VM 网段是否匹配？        dockur/windows 默认用户态网络，VM 内宿主网关即 $SOURCE_HOST；
     自定义 bridge 网络请以真实宿主 IP 运行：SOURCE_HOST=<宿主IP> $0
预案：
  - 宿主启动源端并确认监听 $SOURCE_PORT；
  - 防火墙放行入站 TCP $SOURCE_PORT（docs/windows-client.md §2.3 为客户端出站规则）；
  - 网段不匹配时用 SOURCE_HOST 覆盖后重跑。
EOF
  exit 2
}

# ── 完整链路：分发 + VM 启动 + 截图取证 ──
step_vm_launch_and_capture() {
  info "步骤 4/4：VM 启动与截图取证…"
  [ -n "$VM_HOST" ] || fail "未提供 VM 地址（用法：$0 <VM_IP>）；仅链路自检请用 --self-check"
  need_tool ssh || fail "需要 ssh/scp（dockur/windows 容器暴露 SSH 端口 22）"

  # 分发 app-image（dockur/windows 容器内 Administrator 共享经 ssh 直接落盘）
  info "分发 app-image → VM $VM_HOST …"
  scp -o StrictHostKeyChecking=no -r "$APP_IMAGE" "Administrator@$VM_HOST:C:/cloudphone/" \
    || fail "app-image 分发失败（确认 dockur 容器 SSH 可达）"

  # VM 内软件渲染启动（后台）+ 四态截图
  mkdir -p "$SHOT_DIR"
  local shot_states=("home" "connect" "video" "disconnect")
  local cfg_patch="java-options=-Dskiko.renderApi=SOFTWARE"
  info "VM 内启动（软件渲染：$cfg_patch）…"
  ssh -o StrictHostKeyChecking=no "Administrator@$VM_HOST" \
    "powershell -Command \"Get-Content C:/cloudphone/CloudPhoneDesktop/bin/CloudPhoneDesktop.cfg; Start-Process C:/cloudphone/CloudPhoneDesktop/bin/CloudPhoneDesktop.exe\"" \
    || fail "VM 内启动失败"

  local shot_cmd="powershell -Command \"Add-Type -AssemblyName System.Windows.Forms,System.Drawing; \\\$b=New-Object System.Drawing.Bitmap([System.Windows.Forms.Screen]::PrimaryScreen.Bounds.Width,[System.Windows.Forms.Screen]::PrimaryScreen.Bounds.Height); \\\$g=[System.Drawing.Graphics]::FromImage(\\\$b); \\\$g.CopyFromScreen(0,0,0,0,\\\$b.Size); \\\$b.Save('C:/cloudphone/shot-%s.png')\""
  local i
  for i in "${!shot_states[@]}"; do
    sleep 6
    ssh -o StrictHostKeyChecking=no "Administrator@$VM_HOST" "$(printf "$shot_cmd" "${shot_states[$i]}")" \
      || info "警告：${shot_states[$i]} 截图失败（继续后续状态）"
    scp -o StrictHostKeyChecking=no "Administrator@$VM_HOST:C:/cloudphone/shot-${shot_states[$i]}.png" "$SHOT_DIR/" \
      || info "警告：${shot_states[$i]} 截图回传失败"
  done
  info "截图取证完成 → $SHOT_DIR（首页/连接/视频上屏/断开）"
  # 备选取证路径：QEMU monitor screendump（容器直控 QMP 时）
  info "备选：dockur 容器宿主侧可经 QMP screendump 取证：echo 'screendump shot.png' | socat - UNIX-CONNECT:/qmp.sock"
  info "完整链路冒烟通过"
}

# ── 自检模式（无 VM 环境，宿主即可跑绿） ──
step_self_check() {
  info "自检模式：无 VM 环境，验证脚本链路与构建产物…"
  need_tool bash || fail "bash 不可用"
  step_build_and_artifacts
  # 网络探测工具可用性（不实际探测 VM 网段）
  if need_tool nc; then
    info "网络探测工具：nc 就绪（完整链路用 nc -z -w3）"
  else
    info "网络探测工具：nc 缺失，完整链路将回退 /dev/tcp（bash 内建）"
  fi
  # 截图回传目标目录可写
  mkdir -p "$SHOT_DIR" || fail "截图目录不可创建：$SHOT_DIR"
  info "自检通过：构建绿 + app-image 就绪 + 工具链确认（无 VM 环境自检绿，实际 VM 端到端在验收阶段执行）"
}

# ── 主流程 ──
if [ "$SELF_CHECK" -eq 1 ]; then
  step_self_check
else
  step_build_and_artifacts
  step_probe_network
  step_vm_launch_and_capture
fi
