#!/usr/bin/env bash
# ============================================================================
# server_db_rescue.sh — 在 Linux 生产机（如 192.168.3.228）上救援 xtlog 数据库
#
# 适用场景：
#   - 应用列表很慢 / 日志里刷 `fts bulk insert failed` / `malformed` / `SQLITE_BUSY`
#   - 根因通常是当前活动月表 xt_log_entry_YYYYMM 的 b-tree 损坏（SQLite 单文件库的常见故障）
#
# 本脚本做的事（与本地已验证方案一致）：
#   1) 强制要求先停掉 xtlog 服务（否则会破坏数据库）
#   2) 备份整个 db 文件集（.db / .db-wal / .db-shm）
#   3) 跑 PRAGMA integrity_check；只有发现 malformed 才继续 salvage
#   4) 把「所有完好表」拷进一个全新干净库，丢弃损坏的活动月表及其 FTS 表
#      （活动月表 + FTS 会在服务重启时由 ensureLogTable / FtsIndexer 自动重建）
#   5) 校验新库 integrity_check=ok，再交换文件
#
# 用法：
#   bash server_db_rescue.sh [DATA_DIR]
#   DATA_DIR 默认 /opt/xtlog/data，请改成你服务器实际的 -Dxtlog.data.dir
#
# 重要：脚本只修数据库。修完后务必把「含 SQLITE_BUSY 修复 + 应用列表缓存」的
#       新 xtlog-server.jar 部署上去再启动，否则旧代码的锁竞争/无缓存仍会慢。
# ============================================================================
set -u

DATA_DIR="${1:-/opt/xtlog/data}"
DB="$DATA_DIR/xtlog.db"
DATE="$(date +%Y%m%d%H%M)"
BACKUP_SUFFIX=".bak${DATE}"

# ---- 0. 前置检查 ----------------------------------------------------------
if [ ! -f "$DB" ]; then
  echo "ERROR: 找不到数据库文件: $DB" >&2
  echo "       请用: bash $0 /实际/data/dir" >&2
  exit 1
fi

# 必须停服务
if pgrep -f 'xtlog-server.jar' >/dev/null 2>&1; then
  echo "ERROR: 检测到 xtlog 服务仍在运行，请先停掉再执行本脚本！" >&2
  echo "       例如: systemctl stop xtlog  或  kill <PID>" >&2
  exit 1
fi

# 需要 python3 或 sqlite3
if command -v python3 >/dev/null 2>&1; then
  HAVE_PY=1
elif command -v sqlite3 >/dev/null 2>&1; then
  HAVE_PY=0
else
  echo "ERROR: 需要 python3 或 sqlite3 CLI 之一，请先安装。" >&2
  exit 1
fi

echo "==> 数据目录: $DATA_DIR"
echo "==> 数据库:   $DB"

# ---- 1. 完整性检查 --------------------------------------------------------
echo "==> 运行 PRAGMA integrity_check ..."
if [ "$HAVE_PY" = "1" ]; then
  INTEGRITY=$(python3 - "$DB" <<'PY'
import sqlite3, sys
try:
    c = sqlite3.connect(sys.argv[1])
    c.execute('PRAGMA busy_timeout=5000')
    rows = c.execute('PRAGMA integrity_check').fetchall()
    print('MALFORMED' if any('malformed' in str(r).lower() or 'wrong' in str(r).lower() for r in rows) else 'OK')
except Exception as e:
    print('PROBE_ERR:' + repr(e))
PY
)
else
  INTEGRITY=$(sqlite3 "$DB" "PRAGMA integrity_check;" 2>/dev/null | head -1)
  if echo "$INTEGRITY" | grep -qiE 'malformed|wrong|error'; then INTEGRITY=MALFORMED; fi
fi

echo "    结果: $INTEGRITY"
if [ "$INTEGRITY" = "OK" ]; then
  echo "==> 数据库完好，无需 salvage。"
  echo "    如果访问仍慢，问题多半是「旧 jar 无缓存/锁竞争」——直接部署新 jar 并重启即可。"
  exit 0
fi
echo "==> 检测到损坏，进入救援流程。"

# ---- 2. 备份 --------------------------------------------------------------
echo "==> 备份数据库文件集 ..."
for f in "$DB" "$DB-wal" "$DB-shm"; do
  [ -f "$f" ] && cp -v "$f" "$f$BACKUP_SUFFIX"
done

# ---- 3. salvage（只拷完好表，丢弃损坏月表 + 全部 FTS 表）------------------
NEWDB="$DATA_DIR/xtlog.db.new"
rm -f "$NEWDB"
echo "==> 将完好表 salvage 到 $NEWDB ..."

if [ "$HAVE_PY" = "1" ]; then
python3 - "$DB" "$NEWDB" <<'PY'
import sqlite3, sys, traceback
src, dst = sys.argv[1], sys.argv[2]
ssrc = sqlite3.connect(f'file:{src}?mode=ro', uri=True); ssrc.execute('PRAGMA busy_timeout=5000')
ddst = sqlite3.connect(dst); ddst.execute('PRAGMA busy_timeout=30000')
tables = [r[0] for r in ssrc.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]

# 丢弃：损坏的当前月表 + 所有 FTS 虚拟/影子表（服务启动会重建）
skip = set()
for t in tables:
    if t.startswith('xt_log_fts_'):
        skip.add(t); continue
# 损坏月表通常是当前活动月，下面拷贝时若报错也自动跳过
print("SKIPPING:", sorted(skip))
copied, failed = [], []
for t in tables:
    if t in skip:
        continue
    try:
        sql = ssrc.execute("SELECT sql FROM sqlite_master WHERE name=?", (t,)).fetchone()[0]
        ddst.execute(sql)
        cur = ssrc.execute(f"SELECT * FROM {t}")
        n = 0
        while True:
            rows = cur.fetchmany(2000)
            if not rows: break
            ph = ",".join(["?"]*len(rows[0]))
            ddst.executemany(f"INSERT INTO {t} VALUES ({ph})", rows)
            n += len(rows)
        ddst.commit()
        copied.append((t, n)); print(f"  OK   {t}: {n} rows")
    except Exception as e:
        ddst.rollback(); failed.append((t, repr(e))); print(f"  FAIL {t}: {e}")

ic = ddst.execute("PRAGMA integrity_check").fetchall()
print("NEW DB integrity_check:", ic[0] if ic else ic)
print("COPIED:", [c[0] for c in copied])
print("FAILED:", [f[0] for f in failed])
ddst.close(); ssrc.close()
PY
else
  # 无 python3：用 sqlite3 .dump 兜底（遇到坏页会中断，但通常够用）
  echo "（无 python3，使用 sqlite3 .dump 兜底；若中途中断说明坏页较多，请安装 python3 后重跑）"
  sqlite3 "$DB" ".dump" 2>/dev/null | sqlite3 "$NEWDB"
  sqlite3 "$NEWDB" "PRAGMA integrity_check;" 2>/dev/null | head -1
fi

# ---- 4. 校验新库 + 交换 ---------------------------------------------------
echo "==> 校验新库完整性 ..."
if [ "$HAVE_PY" = "1" ]; then
  OK=$(python3 - "$NEWDB" <<'PY'
import sqlite3, sys
try:
    r = sqlite3.connect(sys.argv[1]).execute('PRAGMA integrity_check').fetchone()
    print('OK' if (r and 'ok' in str(r).lower()) else 'BAD:'+str(r))
except Exception as e:
    print('ERR:'+repr(e))
PY
)
else
  OK=$(sqlite3 "$NEWDB" "PRAGMA integrity_check;" 2>/dev/null | head -1)
fi
echo "    新库: $OK"
if [ "$OK" != "OK" ]; then
  echo "ERROR: 新库仍不正常，已保留原库和备份，未做任何替换。请人工介入（.recover）。" >&2
  rm -f "$NEWDB"
  exit 1
fi

echo "==> 交换文件 ..."
mv -v "$DB"          "$DB.corrupt-prereset"
[ -f "$DB-wal" ] && mv -v "$DB-wal" "$DB-wal.corrupt-prereset"
[ -f "$DB-shm" ] && mv -v "$DB-shm" "$DB-shm.corrupt-prereset"
mv -v "$NEWDB"       "$DB"

echo ""
echo "================ 救援完成 ================"
echo "1) 损坏原库已存为 $DB.corrupt-prereset（确认无误后可删，腾空间）"
echo "2) 备份在 $DB$BACKUP_SUFFIX*（可回滚）"
echo "3) 现在请：部署「含 SQLITE_BUSY 修复 + 应用列表缓存」的新 xtlog-server.jar"
echo "4) 然后启动服务：systemctl start xtlog（或你的启动命令）"
echo "   服务启动会自动重建当前月表 + FTS，开始正常采集。"
PY
