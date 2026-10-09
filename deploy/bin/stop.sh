#!/bin/sh
# ------------------------------------------------------------------
#  xtlog-server 停止脚本（Linux / macOS）：向 data/xtlog.pid 记录的进程发 TERM
#  服务已注册优雅停机钩子，先停止接收新请求再关闭数据源
# ------------------------------------------------------------------
APP_HOME=$(cd "$(dirname "$0")/.." && pwd)
XTLOG_DATA_DIR=${XTLOG_DATA_DIR:-"$APP_HOME/data"}
PID_FILE="$XTLOG_DATA_DIR/xtlog.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "[xtlog] pid file not found: $PID_FILE"
    exit 0
fi

PID=$(cat "$PID_FILE")
if [ -z "$PID" ]; then
    echo "[xtlog] pid file is empty"
    exit 0
fi

if kill -0 "$PID" 2>/dev/null; then
    kill -TERM "$PID"
    echo "[xtlog] stopping pid=$PID ..."
    # 最多等待 15 秒
    i=0
    while kill -0 "$PID" 2>/dev/null && [ $i -lt 15 ]; do
        sleep 1
        i=$((i + 1))
    done
    if kill -0 "$PID" 2>/dev/null; then
        echo "[xtlog] graceful stop timeout, force kill"
        kill -9 "$PID"
    fi
    echo "[xtlog] stopped (pid=$PID)"
else
    echo "[xtlog] process $PID not running"
fi

rm -f "$PID_FILE"
