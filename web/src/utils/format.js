const pad = (n, w = 2) => String(n).padStart(w, '0')

/** 毫秒时间戳 -> YYYY-MM-DD HH:mm:ss.SSS */
export function fmtTime(ms) {
  if (ms === null || ms === undefined || ms === '') return ''
  const d = new Date(Number(ms))
  if (Number.isNaN(d.getTime())) return String(ms)
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`
  )
}

/** 只要时分秒毫秒，列表里更紧凑 */
export function fmtTimeShort(ms) {
  if (!ms && ms !== 0) return ''
  const d = new Date(Number(ms))
  if (Number.isNaN(d.getTime())) return String(ms)
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
