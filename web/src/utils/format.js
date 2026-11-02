const pad = (n, w = 2) => String(n).padStart(w, '0')

/**
 * 时间字段归一成 Date。
 * 后端数据源可能换成 MySQL / PostgreSQL：SQLite 里时间是 Long（epoch 毫秒），
 * MySQL 驱动给的是 "2026-10-09T14:48:55" 这样的 LocalDateTime 串。
 * 两种情况都要能显示，不能只认数字。
 */
export function toDate(v) {
  if (v === null || v === undefined || v === '') return null
  if (v instanceof Date) return Number.isNaN(v.getTime()) ? null : v
  const s = String(v).trim()
  if (/^-?\d+$/.test(s)) {
    const d = new Date(Number(s))
    return Number.isNaN(d.getTime()) ? null : d
  }
  // ISO / "yyyy-MM-dd HH:mm:ss[.SSS]"：补个 T 让各浏览器都按本地时区解析
  const d = new Date(s.indexOf('T') >= 0 ? s : s.replace(' ', 'T'))
  return Number.isNaN(d.getTime()) ? null : d
}

/** 毫秒时间戳 / 日期串 -> YYYY-MM-DD HH:mm:ss.SSS */
export function fmtTime(ms) {
  if (ms === null || ms === undefined || ms === '') return ''
  const d = toDate(ms)
  if (!d) return String(ms)
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`
  )
}

/** 只要时分秒毫秒，列表里更紧凑 */
export function fmtTimeShort(ms) {
  if (!ms && ms !== 0) return ''
  const d = toDate(ms)
  if (!d) return String(ms)
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`
}

export const LEVEL_TAG = {
  ERROR: 'dg',
  WARN: 'warn',
  INFO: 'pri',
  DEBUG: 'dbg',
  TRACE: 'info'
}

export function levelClass(level) {
  return LEVEL_TAG[String(level || '').toUpperCase()] || 'info'
}

export const LEVEL_OPTIONS = ['ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE']
