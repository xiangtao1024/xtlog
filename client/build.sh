#!/usr/bin/env bash
# xtlog-client 构建脚本（Linux / macOS）
#
#   ./build.sh                 # 构建当前平台，产物在 target/release/xtlog-client
#   ./build.sh linux-x64       # 构建 Linux x86_64（静态链接 musl，需要 musl 工具链）
#
# 构建 Windows 版请到 Windows 上执行 build.bat，或在装了 mingw-w64 的机器上：
#   cargo build --release --target x86_64-pc-windows-gnu
set -euo pipefail

TARGET="${1:-}"

echo "[build] xtlog-client"
if [ -n "$TARGET" ]; then
  case "$TARGET" in
    linux-x64)
      rustup target add x86_64-unknown-linux-gnu
      cargo build --release --target x86_64-unknown-linux-gnu
      OUT="target/x86_64-unknown-linux-gnu/release/xtlog-client"
      ;;
    linux-arm64)
      rustup target add aarch64-unknown-linux-gnu
      cargo build --release --target aarch64-unknown-linux-gnu
      OUT="target/aarch64-unknown-linux-gnu/release/xtlog-client"
      ;;
    *)
      echo "未知目标：$TARGET（支持 linux-x64 / linux-arm64）" >&2
      exit 2
      ;;
  esac
else
  cargo build --release
  OUT="target/release/xtlog-client"
fi

chmod +x "$OUT" 2>/dev/null || true
echo "[build] 产物：$OUT"
ls -lh "$OUT"
