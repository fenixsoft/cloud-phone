#!/usr/bin/env bash
# natlab_setup.sh — natlab 网络实验床（p2p-crossnet-traversal 4.1）
#
# 拓扑（纯 netns，无 adb/模拟器依赖）：
#
#   [natlab-cl ns] 10.200.1.2 ── vcl-host/vcl-peer ── [host root: 10.200.1.1]  ← STUN/FRP 服务可达
#   [natlab-srv ns] 10.200.2.2 ── vsrv-host/vsrv-peer ─ [host root: 10.200.2.1]
#
# 两台 JVM 对端各居一个 netns（宿主侧 veth IP 即其 NAT 网关/WAN 出口），root ns 承载
# STUN（natlab 对端内置 stun 模式）与 FRP 信令端口静态转发。三档 NAT（可按端独立启停）：
#
#   ei         端点无关映射+过滤（full-cone）：SNAT --to-source 网关IP（端口保持）
#              + PREROUTING UDP 动态段 DNAT 透传（映射端口=私网端口）→ 双向探针可通，打洞可成
#   ap-rr      端点无关映射+地址/端口相关过滤：SNAT 端口保持，无静态透传，
#              conntrack 严格应答元组过滤（实验室口径：比真实 restricted-cone 更严，
#              打洞不成 → 走 FRP 回落 degraded 路径）
#   symmetric  对称型：MASQUERADE --random-fully（per-destination 随机端口），无透传
#              → 映射不可预测且过滤严格 → 打洞不成（验证端口预测装配 + FRP 兜底）
#   sym-seq    对称型·有界池化：per-destination 随机限位端口段（40000-40001），
#              映射随目标变化不可精确外推但窗口可与池相交 → 预测命中路径实验档
#
# 静态基线（所有档位恒有，模拟"源端侧 NAT 对 FRP 端口做映射"的现实部署）：
#   srv 网关 22333/tcp 静态 DNAT → 10.200.2.2（客户端可达源端 FRP 信令/TCP 回落路径；
#   UDP 22334 不放行——那是源端 P2P socket 端口，静态洞会旁路全部档位过滤）
#
# 用法：
#   natlab_setup.sh up                                  # 建/重建拓扑（幂等）
#   natlab_setup.sh profile <ei|ap-rr|symmetric|sym-seq|mixed> [cl|srv|both]  # 应用档位（幂等）
#   natlab_setup.sh clear [cl|srv|both]                 # 恢复基线（仅静态转发）
#   natlab_setup.sh down                                # 拆拓扑
#   natlab_setup.sh status                              # 查看拓扑/规则
set -euo pipefail

NS_CL="natlab-cl"; NS_SRV="natlab-srv"; NS_WAN="natlab-wan"
IF_CL_H="vcl-host"; IF_CL_P="vcl-peer"
IF_SRV_H="vsrv-host"; IF_SRV_P="vsrv-peer"
IF_WAN_H="wwan-host"; IF_WAN_P="wwan-peer"
CL_NET="10.200.1.0/24"; CL_GW="10.200.1.1"; CL_PEER="10.200.1.2"
SRV_NET="10.200.2.0/24"; SRV_GW="10.200.2.1"; SRV_PEER="10.200.2.2"
FRP_TCP=22333; FRP_UDP=22334
DYN_RANGE="1024:65535"
# "公网"区（独立 netns，模拟互联网侧）：STUN 服务驻留。peer→STUN 的包必须"穿过"网关
# （FORWARD 路径）SNAT/MASQUERADE 才生效——网关本地地址走 INPUT 不做 NAT，不可用作 STUN 目标
WAN_NET="10.200.100.0/24"
WAN_HOST_IP="10.200.100.254/24"
WAN_IP="10.200.100.1"

need_root() { [ "$(id -u)" = "0" ] || { echo "[natlab] 需要 root（ip/iptables）" >&2; exit 2; }; }

ipt() { iptables "$@"; }

# ── 链与跳转（幂等：-C 已存在则跳过）─────────────────────────────────────────
ensure_chain() { # $1=表 $2=链
    ipt -t "$1" -L "$2" >/dev/null 2>&1 || ipt -t "$1" -N "$2"
}
ensure_jump() { # $1=表 $2=链 $3..=跳转匹配
    local table="$1" chain="$2"; shift 2
    ipt -t "$table" -C "$chain" "$@" >/dev/null 2>&1 || ipt -t "$table" -A "$chain" "$@"
}

ensure_jump_top() { # 插到链首（宿主可能已有 libvirt/docker FORWARD 规则 REJECT 非自身网段流量）
    local table="$1" chain="$2"; shift 2
    ipt -t "$table" -C "$chain" "$@" >/dev/null 2>&1 || ipt -t "$table" -I "$chain" 1 "$@"
}

setup_chains() {
    for c in NATLAB-CL-PRE NATLAB-SRV-PRE NATLAB-CL-POST NATLAB-SRV-POST \
             NATLAB-CL-FWD NATLAB-SRV-FWD NATLAB-WAN-FWD; do
        ensure_chain filter "$c"
        ensure_chain nat "$c"
    done
    # 先清除内建链中的历史 natlab 跳转（匹配方式可能已变，残留会截胡转发路径）
    for base in PREROUTING POSTROUTING; do
        while read -r rule; do
            [ -n "$rule" ] && ipt -t nat -D "$base" ${rule#"-A $base "} 2>/dev/null || true
        done < <(ipt -t nat -S "$base" 2>/dev/null | grep -- '-j NATLAB-')
    done
    while read -r rule; do
        [ -n "$rule" ] && ipt -D FORWARD ${rule#"-A FORWARD "} 2>/dev/null || true
    done < <(ipt -S FORWARD 2>/dev/null | grep -- '-j NATLAB-')
    # 按"目的地址"跳转（peer 互探的入包可能从对端网关接口进来，in-interface 匹配会漏）
    ensure_jump nat PREROUTING -d "$CL_GW" -j NATLAB-CL-PRE
    ensure_jump nat PREROUTING -d "$SRV_GW" -j NATLAB-SRV-PRE
    ensure_jump nat POSTROUTING -s "$CL_NET" -j NATLAB-CL-POST
    ensure_jump nat POSTROUTING -s "$SRV_NET" -j NATLAB-SRV-POST
    # filter 转发按"目的网段"条件跳转（无条件跳转会截胡宿主其它 FORWARD 流量；
    # 按目的分段后 cl→srv 的裁决落在 SRV 链、srv→cl 落在 CL 链，双向过滤各自生效）
    ensure_jump_top filter FORWARD -d "$CL_NET" -j NATLAB-CL-FWD
    ensure_jump_top filter FORWARD -d "$SRV_NET" -j NATLAB-SRV-FWD
    ensure_jump_top filter FORWARD -d "$WAN_NET" -j NATLAB-WAN-FWD
}

# ── 档位规则 ────────────────────────────────────────────────────────────────
apply_profile_rules() { # $1=peer(cl|srv) $2=profile
    local peer="$1" profile="$2"
    local pre post fwd gw peerip net ifh
    if [ "$peer" = "cl" ]; then
        pre=NATLAB-CL-PRE; post=NATLAB-CL-POST; fwd=NATLAB-CL-FWD
        gw="$CL_GW"; peerip="$CL_PEER"; net="$CL_NET"; ifh="$IF_CL_H"
    else
        pre=NATLAB-SRV-PRE; post=NATLAB-SRV-POST; fwd=NATLAB-SRV-FWD
        gw="$SRV_GW"; peerip="$SRV_PEER"; net="$SRV_NET"; ifh="$IF_SRV_H"
    fi
    # 动态解析当前 ns 主地址（迁移场景 IP 变更后 DNAT/放行规则跟随新地址）
    local cur
    cur=$(ip netns exec "$([ "$peer" = "cl" ] && echo "$NS_CL" || echo "$NS_SRV")"         ip -4 addr show "$([ "$peer" = "cl" ] && echo "$IF_CL_P" || echo "$IF_SRV_P")" 2>/dev/null \
        | awk '/inet /{split($2,a,"/"); print a[1]; exit}')
    [ -n "$cur" ] && peerip="$cur"
    case "$profile" in
        ei)
            # 端点无关映射（端口保持 SNAT）+ 动态段全透传（映射端口=私网端口）= full-cone
            ipt -t nat -A "$post" -p udp -j SNAT --to-source "$gw"
            ipt -t nat -A "$pre" -p udp --dport "$DYN_RANGE" -j DNAT --to-destination "$peerip"
            ipt -A "$fwd" -d "$peerip" -p udp -j ACCEPT
            ;;
        ap-rr)
            # 端点无关映射 + conntrack 严格过滤（实验室口径：无动态透传）
            ipt -t nat -A "$post" -p udp -j SNAT --to-source "$gw"
            ipt -A "$fwd" -d "$peerip" -p udp -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
            ;;
        symmetric)
            # 对称型：per-destination 随机端口 + 严格过滤（预测装配/FRP 兜底验证档）
            ipt -t nat -A "$post" -p udp -j MASQUERADE --random-fully
            ipt -A "$fwd" -d "$peerip" -p udp -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
            ;;
        sym-seq)
            # 对称型·微型池化档：per-destination 随机分配限位端口段（40000-40001，
            # 运营商 CGNAT 微池化形态——映射随目标变化、双向外推窗口可覆盖池，
            # 预测命中路径可在实验中真实发生），过滤同 symmetric
            ipt -t nat -A "$post" -p udp -j SNAT --to-source "$gw:40000-40001" --random-fully
            ipt -A "$fwd" -d "$peerip" -p udp -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
            ;;
        *)
            echo "[natlab] 未知档位：$profile（ei|ap-rr|symmetric|sym-seq）" >&2; exit 2 ;;
    esac
    # 链尾 DROP：未显式放行的转发一律丢弃——档位过滤语义真实生效
    # （容器 FORWARD 默认策略 ACCEPT，无此兜底则 ap-rr/symmetric 严格过滤形同虚设）
    ipt -A "$fwd" -j DROP
}

# 链重写（flush + 静态基线 + 档位规则）：幂等可重复执行
apply_profile() { # $1=peer(cl|srv|both) $2=profile(ei|ap-rr|symmetric|mixed)
    need_root
    setup_chains
    local profile="$2"
    local peers=()
    case "$1" in
        cl) peers=(cl) ;;
        srv) peers=(srv) ;;
        both) peers=(cl srv) ;;
        *) echo "[natlab] peer 须为 cl|srv|both" >&2; exit 2 ;;
    esac
    for p in "${peers[@]}"; do
        local prof="$profile"
        if [ "$profile" = "mixed" ]; then
            # 混合档：cl=EI（cone 打洞方）、srv=symmetric（被探方对称）——必回落对照场景
            if [ "$p" = "cl" ]; then prof="ei"; else prof="symmetric"; fi
        fi
        if [ "$p" = "cl" ]; then
            ipt -t nat -F NATLAB-CL-PRE; ipt -t nat -F NATLAB-CL-POST; ipt -F NATLAB-CL-FWD
            write_static_cl
            apply_profile_rules cl "$prof"
            echo "[natlab] cl 档位=$prof"
        else
            ipt -t nat -F NATLAB-SRV-PRE; ipt -t nat -F NATLAB-SRV-POST; ipt -F NATLAB-SRV-FWD
            write_static_srv
            apply_profile_rules srv "$prof"
            echo "[natlab] srv 档位=$prof"
        fi
    done
}

# 静态基线按端拆分（clear/写档共用）。filter 链只放行"入向到本端网段"的既定回程
# 与端口映射；出向裁决由目的网段链（对端链/WAN 链）负责，链尾 DROP 收口。
write_static_cl() {
    # 按网段匹配（而非固定主机）：迁移场景 ns IP 变更（10.200.1.2→.3）后规则仍成立
    ipt -A NATLAB-CL-FWD -s "$WAN_NET" -d "$CL_NET" -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT  # STUN 应答回程
    ipt -A NATLAB-CL-FWD -d "$CL_NET" -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
}
write_static_srv() {
    ipt -A NATLAB-SRV-FWD -s "$WAN_NET" -d "$SRV_NET" -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
    ipt -A NATLAB-SRV-FWD -d "$SRV_NET" -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
    # FRP TCP 端口静态基线（源端侧 NAT 端口映射的现实等价物：控制信令+TCP 回落路径）
    # 注意不放行 UDP $FRP_UDP：源端 P2P socket 恰绑同一端口，静态放行会为所有档位
    # 开永久透传洞（symmetric 过滤被旁路，矩阵失真），natlab 回落视频走 TCP 控制通道。
    ipt -t nat -A NATLAB-SRV-PRE -p tcp --dport "$FRP_TCP" -j DNAT --to-destination "$SRV_PEER:$FRP_TCP"
    ipt -A NATLAB-SRV-FWD -d "$SRV_PEER" -p tcp --dport "$FRP_TCP" -j ACCEPT
}
write_static_wan() {
    # 公网区中转：对端出向 STUN 探测 + STUN 应答发出（无 DROP，纯中转）
    ipt -A NATLAB-WAN-FWD -s "$WAN_NET" -j ACCEPT
    ipt -A NATLAB-WAN-FWD -s "$CL_NET" -j ACCEPT
    ipt -A NATLAB-WAN-FWD -s "$SRV_NET" -j ACCEPT
}

clear_rules() { # $1=peer(cl|srv|both)
    need_root
    local peers=()
    case "$1" in
        cl) peers=(cl) ;;
        srv) peers=(srv) ;;
        both) peers=(cl srv) ;;
        *) echo "[natlab] peer 须为 cl|srv|both" >&2; exit 2 ;;
    esac
    setup_chains
    for p in "${peers[@]}"; do
        if [ "$p" = "cl" ]; then
            ipt -t nat -F NATLAB-CL-PRE; ipt -t nat -F NATLAB-CL-POST; ipt -F NATLAB-CL-FWD
            write_static_cl
            ipt -A NATLAB-CL-FWD -j DROP   # 基线封口：无档位即无透传
        else
            ipt -t nat -F NATLAB-SRV-PRE; ipt -t nat -F NATLAB-SRV-POST; ipt -F NATLAB-SRV-FWD
            write_static_srv
            ipt -A NATLAB-SRV-FWD -j DROP
        fi
    done
    echo "[natlab] 已恢复基线（仅 FRP 静态转发，入向封口）：${peers[*]}"
}

create_topology() {
    need_root
    # 重建（幂等）：先拆旧 ns 与残留公网地址（v1 遗留 lo 地址会截胡路由到 INPUT）
    down_topology_quiet
    ip addr del "$WAN_IP/32" dev lo 2>/dev/null || true
    for ns in "$NS_CL" "$NS_SRV" "$NS_WAN"; do ip netns add "$ns"; done
    ip link add "$IF_CL_H" type veth peer name "$IF_CL_P"
    ip link add "$IF_SRV_H" type veth peer name "$IF_SRV_P"
    ip link set "$IF_CL_P" netns "$NS_CL" 2>/dev/null || true
    ip link set "$IF_SRV_P" netns "$NS_SRV" 2>/dev/null || true
    ip addr add "$CL_GW/24" dev "$IF_CL_H"; ip link set "$IF_CL_H" up
    ip addr add "$SRV_GW/24" dev "$IF_SRV_H"; ip link set "$IF_SRV_H" up
    ip netns exec "$NS_CL" ip link set lo up
    ip netns exec "$NS_SRV" ip link set lo up
    ip netns exec "$NS_CL" ip link set "$IF_CL_P" up
    ip netns exec "$NS_SRV" ip link set "$IF_SRV_P" up
    ip netns exec "$NS_CL" ip addr add "$CL_PEER/24" dev "$IF_CL_P"
    ip netns exec "$NS_SRV" ip addr add "$SRV_PEER/24" dev "$IF_SRV_P"
    ip netns exec "$NS_CL" ip route add default via "$CL_GW"
    ip netns exec "$NS_SRV" ip route add default via "$SRV_GW"
    # 公网区 netns（STUN 驻留）
    ip link add "$IF_WAN_H" type veth peer name "$IF_WAN_P"
    ip link set "$IF_WAN_P" netns "$NS_WAN"
    ip addr add "$WAN_HOST_IP" dev "$IF_WAN_H"; ip link set "$IF_WAN_H" up
    ip netns exec "$NS_WAN" ip link set lo up
    ip netns exec "$NS_WAN" ip link set "$IF_WAN_P" up
    ip netns exec "$NS_WAN" ip addr add "$WAN_IP/24" dev "$IF_WAN_P"
    ip netns exec "$NS_WAN" ip route add default via "${WAN_HOST_IP%/*}"  # 回程路由（STUN 应答）
    # 路由/NAT 前置：转发开、rp_filter 关（跨网段实验室路径防误丢）
    sysctl -qw net.ipv4.ip_forward=1
    for ifc in "$IF_CL_H" "$IF_SRV_H" "$IF_WAN_H"; do
        sysctl -qw "net.ipv4.conf.$ifc.rp_filter=0" 2>/dev/null || true
    done
    sysctl -qw net.ipv4.conf.all.rp_filter=0 2>/dev/null || true
    setup_chains
    # 基线规则（仅静态转发；档位由 profile 显式应用）
    ipt -t nat -F NATLAB-CL-PRE; ipt -t nat -F NATLAB-CL-POST; ipt -F NATLAB-CL-FWD
    ipt -t nat -F NATLAB-SRV-PRE; ipt -t nat -F NATLAB-SRV-POST; ipt -F NATLAB-SRV-FWD
    ipt -F NATLAB-WAN-FWD
    write_static_cl
    write_static_srv
    write_static_wan
    ipt -A NATLAB-CL-FWD -j DROP    # 基线封口
    ipt -A NATLAB-SRV-FWD -j DROP
    echo "[natlab] 拓扑就绪：$NS_CL($CL_PEER gw $CL_GW) / $NS_SRV($SRV_PEER gw $SRV_GW)"
}

down_topology_quiet() {
    ip netns del "$NS_CL" 2>/dev/null || true
    ip netns del "$NS_SRV" 2>/dev/null || true
    ip netns del "$NS_WAN" 2>/dev/null || true
    ip link del "$IF_CL_H" 2>/dev/null || true
    ip link del "$IF_SRV_H" 2>/dev/null || true
    ip link del "$IF_WAN_H" 2>/dev/null || true
}

down_topology() {
    need_root
    down_topology_quiet
    echo "[natlab] 拓扑已拆除（NATLAB-* 链保留可手工清理：iptables -t nat -F NATLAB-*-PRE ...）"
}

show_status() {
    echo "── netns ──"; ip netns list 2>/dev/null | grep natlab || echo "（无）"
    echo "── 公网区 ──"; ip netns exec "$NS_WAN" ip -4 addr show "$IF_WAN_P" 2>/dev/null | grep inet || echo "（wan 未建）"
    echo "── 链 ──"
    for c in NATLAB-CL-PRE NATLAB-CL-POST NATLAB-CL-FWD NATLAB-SRV-PRE NATLAB-SRV-POST NATLAB-SRV-FWD; do
        echo "[$c/filter]"; ipt -S "$c" 2>/dev/null | head -12
        echo "[$c/nat]"; ipt -t nat -S "$c" 2>/dev/null | head -12
    done
}

cmd="${1:-}"
case "$cmd" in
    up) create_topology ;;
    profile) apply_profile "${3:-both}" "${2:?usage: profile <ei|ap-rr|symmetric|mixed> [cl|srv|both]}" ;;
    clear) clear_rules "${2:-both}" ;;
    down) down_topology ;;
    status) show_status ;;
    *) sed -n '2,32p' "$0"; exit 1 ;;
esac
