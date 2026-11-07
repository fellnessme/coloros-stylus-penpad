#!/system/bin/sh
# penkeyd.sh —— 笔键转发守护
#
# 背景：很多系统（如 ColorOS）在手势层就把手写笔按键消费掉了，
# 事件在 interceptKeyBeforeQueueing 阶段就消失，普通 App 和无障碍服务
# 都收不到。只有以 shell/root 身份直接读 /dev/input 才能拿到。
#
# 本脚本做三件事：
#   1. 找到笔的「Consumer Control」输入节点（按键走这个设备）
#   2. 监听 MSC_SCAN，识别 0x000c0600/0601/0602/0604
#   3. 转发成本应用内的广播
#
# 用法：
#   sh penkeyd.sh [device_name_pattern] [package] [receiver_component]
#
# 默认：
#   pattern = "Consumer Control"
#   package = com.aclaniakea.penpad
#   comp    = com.aclaniakea.penpad/.PenKeyReceiver
#
# 可选环境变量：
#   PENKEY_LOG=/data/local/tmp/penkeyd.log   日志文件

PATTERN="${1:-Consumer Control}"
PKG="${2:-com.aclaniakea.penpad}"
COMP="${3:-com.aclaniakea.penpad/.PenKeyReceiver}"

ACTION="com.aclaniakea.penpad.PENKEY"
LOG="${PENKEY_LOG:-/data/local/tmp/penkeyd.log}"
PIDFILE=/data/local/tmp/penkeyd.pid

log() { echo "$(date '+%H:%M:%S') $*" >> "$LOG"; }

# ---- 单实例：已有守护在跑就退出 -----------------------------------------
if [ -f "$PIDFILE" ]; then
    OLD=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$OLD" ] && kill -0 "$OLD" 2>/dev/null; then
        log "another instance ($OLD) running, exit"
        exit 0
    fi
fi
echo $$ > "$PIDFILE"

# ---- 找节点 -------------------------------------------------------------
# getevent -pl 是 shell 可读的，用它按名字匹配
find_node() {
    /system/bin/getevent -pl 2>/dev/null | awk -v pat="$PATTERN" '
        /^add device/ { dev=$4 }
        index($0, "name:") && index($0, pat) { print dev; exit }
    '
}

NODE=""
i=0
while [ $i -lt 15 ]; do
    NODE=$(find_node)
    [ -n "$NODE" ] && break
    i=$((i+1))
    sleep 2
done

if [ -z "$NODE" ]; then
    log "device '$PATTERN' not found; waiting forever"
    while [ -z "$NODE" ]; do
        sleep 5
        NODE=$(find_node)
    done
fi

log "=== daemon start pid=$$ uid=$(id -u) node=$NODE ==="

# ---- 主循环 -------------------------------------------------------------
# 用 getevent -c 1：每次只读一条事件就退出，避开管道缓冲导致的延迟。
# （直接把 getevent 管道到 while read 会因为 stdio 块缓冲而读不到）
while true; do
    line=$(/system/bin/getevent -c 1 "$NODE" 2>/dev/null)
    if [ -z "$line" ]; then
        # 读空 = 设备被拔掉/重连，稍等并重新找节点
        sleep 1
        NEW=$(find_node)
        if [ -z "$NEW" ] || [ "$NEW" != "$NODE" ]; then
            NODE=""
            while [ -z "$NODE" ]; do
                sleep 3
                NODE=$(find_node)
            done
            log "rebound to $NODE"
        fi
        continue
    fi

    # 笔按键的 HID usage 是 0x000c06xx，内核报成 MSC_SCAN
    case "$line" in
        *000c0600*) am broadcast -n "$COMP" -a "$ACTION" --ei code 0 >/dev/null 2>&1 ;;
        *000c0601*) am broadcast -n "$COMP" -a "$ACTION" --ei code 1 >/dev/null 2>&1 ;;
        *000c0602*) am broadcast -n "$COMP" -a "$ACTION" --ei code 2 >/dev/null 2>&1 ;;
        *000c0604*) am broadcast -n "$COMP" -a "$ACTION" --ei code 4 >/dev/null 2>&1 ;;
    esac
done
