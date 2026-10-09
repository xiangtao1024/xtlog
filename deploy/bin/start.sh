#!/bin/sh
# ------------------------------------------------------------------
#  xtlog-server 启动脚本（Linux / macOS，前台阻塞运行，Ctrl+C 结束）
#  后台运行：nohup bin/start.sh > /dev/null 2>&1 &
# ------------------------------------------------------------------
APP_HOME=$(cd "$(dirname "$0")/.." && pwd)
cd "$APP_HOME" || exit 1

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=java
fi

# 数据目录：默认安装目录下的 data，可用环境变量 XTLOG_DATA_DIR 覆盖
XTLOG_DATA_DIR=${XTLOG_DATA_DIR:-"$APP_HOME/data"}

mkdir -p "$APP_HOME/logs" "$XTLOG_DATA_DIR"

# 额外 JVM 参数可通过环境变量 JAVA_OPTS 追加（例如 JAVA_OPTS="-Xmx1g"）
JVM_OPTS="-Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Dxtlog.data.dir=$XTLOG_DATA_DIR"

echo "[xtlog] APP_HOME = $APP_HOME"
echo "[xtlog] DATA_DIR = $XTLOG_DATA_DIR"
echo "[xtlog] starting ... (Ctrl+C to stop)"

# 注意 drivers/* 通配符要交给 JVM 展开，所以 -cp 必须加引号
exec "$JAVA" $JVM_OPTS $JAVA_OPTS \
    -cp "$APP_HOME/config:$APP_HOME/xtlog-server.jar:$APP_HOME/drivers/*" \
    top.xtcoder.xtlog.XtLogApplication 2>&1 | tee -a "$APP_HOME/logs/console.log"
