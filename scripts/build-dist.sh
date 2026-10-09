#!/bin/sh
# ==================================================================
#  xtlog-server 部署包组装脚本（Linux / macOS）
#
#  前置：已执行过 web 前端构建 + mvn package（产出 target/xtlog-server.jar）
#  产出：dist/xtlog-server-<ver>/       解压即用目录
#        dist/xtlog-server-<ver>.tar.gz 可直接拷到服务器的压缩包
#
#  用法：sh scripts/build-dist.sh
# ==================================================================
set -e

ROOT=$(cd "$(dirname "$0")/.." && pwd)
JAR="$ROOT/target/xtlog-server.jar"
STAGE_ROOT="$ROOT/dist"

if [ ! -f "$JAR" ]; then
    echo "[ERROR] not found: $JAR"
    echo "        请先执行前端构建与打包：cd web && npm run build，然后 mvn clean package"
    exit 1
fi

# 从 pom.xml 取版本号（pom 里第一个 <version> 即 project 版本）
VER=$(sed -n 's/.*<version>\([0-9][^<]*\)<\/version>.*/\1/p' "$ROOT/pom.xml" | head -1)
[ -z "$VER" ] && VER="0.0.1"

STAGE="$STAGE_ROOT/xtlog-server-$VER"
echo "[build] version = $VER"
echo "[build] stage   = $STAGE"

mkdir -p "$STAGE/bin" "$STAGE/config" "$STAGE/drivers" "$STAGE/data" "$STAGE/logs"

cp -f "$JAR" "$STAGE/xtlog-server.jar"
cp -R "$ROOT/deploy/." "$STAGE/"
chmod +x "$STAGE/bin"/*.sh

echo "[build] stage contents:"
ls -1 "$STAGE"

TAR="$STAGE_ROOT/xtlog-server-$VER.tar.gz"
rm -f "$TAR"
tar -czf "$TAR" -C "$STAGE_ROOT" "xtlog-server-$VER"
SIZE=$(du -m "$TAR" | cut -f1)
echo "[build] tar  = $TAR"
echo "[build] size = ${SIZE} MB"
echo "[build] done."
