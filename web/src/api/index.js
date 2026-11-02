import axios from 'axios'

/**
 * xtboot 统一响应：{ code, message, data }
 * 这里把 data 直接剖出来，业务层拿到的就是纯数据；非 200 直接 reject。
 */
const http = axios.create({ baseURL: '/api', timeout: 60000 })

/** 业务会话 token（框架 StpUtil 默认名 satoken） */
export const TOKEN_KEY = 'satoken'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}
export function setToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

// 请求带上会话 token
http.interceptors.request.use((config) => {
  const t = getToken()
  if (t) config.headers[TOKEN_KEY] = t
  return config
})

/** 会话失效时通知上层（App 监听后做退出跳登录） */
export const onUnauthorized = { handler: null }

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && Object.prototype.hasOwnProperty.call(body, 'code')) {
      if (body.code === 200) {
        return body.data
      }
      if (body.code === 401 && onUnauthorized.handler) {
        onUnauthorized.handler()
      }
      const err = new Error(body.message || '请求失败')
      err.code = body.code
      return Promise.reject(err)
    }
    return body
  },
  (err) => {
    const status = err?.response?.status
    const code = err?.response?.data?.code
    if ((status === 401 || code === 401) && onUnauthorized.handler) {
      onUnauthorized.handler()
    }
    const msg = err?.response?.data?.message || err.message || '网络错误'
    const e = new Error(msg)
    e.code = status || code
    return Promise.reject(e)
  }
)

export const authApi = {
  login: (payload) => http.post('/auth/login', payload),
  logout: () => http.post('/auth/logout'),
  profile: () => http.get('/auth/profile'),
  updateProfile: (payload) => http.put('/auth/profile', payload)
}

export const statsApi = {
  overview: () => http.get('/stats/overview')
}

export const setupApi = {
  status: () => http.get('/setup/status'),
  save: (payload) => http.post('/setup', payload)
}

export const migrateApi = {
  migrate: (payload) => http.post('/migrate', payload),
  status: () => http.get('/migrate/status'),
  jobs: () => http.get('/migrate/jobs'),
  switchTo: (payload) => http.post('/migrate/switch', payload)
}

/** 数据源记录（历史 + 当前），支持一键切回 */
export const datasourceApi = {
  list: () => http.get('/datasources'),
  switchTo: (id) => http.post('/datasources/switch', { id }),
  rename: (id, label) => http.post('/datasources/rename', { id, label }),
  remove: (id) => http.post('/datasources/remove', { id })
}

/** 日志分析：概览 / 趋势 / 维度排名 / 错误聚类 */
export const analysisApi = {
  overview: (params) => http.get('/analysis/overview', { params }),
  trend: (params) => http.get('/analysis/trend', { params }),
  breakdown: (params) => http.get('/analysis/breakdown', { params }),
  patterns: (params) => http.get('/analysis/patterns', { params })
}

/** 全文索引（FTS5）：开关与回填进度 */
export const ftsApi = {
  status: () => http.get('/fts/status'),
  toggle: (enabled) => http.post('/fts/toggle', null, { params: { enabled: enabled ? 1 : 0 } })
}

export const configApi = {
  get: () => http.get('/config'),
  save: (payload) => http.post('/config', payload)
}

export const categoryApi = {
  list: () => http.get('/categories'),
  create: (payload) => http.post('/categories', payload),
  update: (id, payload) => http.put(`/categories/${id}`, payload),
  remove: (id) => http.delete(`/categories/${id}`)
}

export const appApi = {
  list: (params) => http.get('/apps', { params }),
  options: () => http.get('/apps/options'),
  create: (payload) => http.post('/apps', payload),
  update: (id, payload) => http.put(`/apps/${id}`, payload),
  remove: (id) => http.delete(`/apps/${id}`),
  setEnabled: (id, enabled) => http.put(`/apps/${id}/enable`, { enabled: enabled ? 1 : 0 })
}

export const logApi = {
  query: (params) => http.get('/logs', { params }),
  ingest: (payload) => http.post('/logs', payload),
  /** 超长日志被拆成多段后，按 cont_group 取回所有片段（grp 为组标识，ts 为该组业务时间毫秒） */
  group: (params) => http.get('/logs/group', { params })
}

/** 告警规则与事件 */
export const alertApi = {
  // 规则
  listRules: (params) => http.get('/alert/rules', { params }),
  createRule: (payload) => http.post('/alert/rules', payload),
  updateRule: (id, payload) => http.put(`/alert/rules/${id}`, payload),
  removeRule: (id) => http.delete(`/alert/rules/${id}`),
  toggleRule: (id, enabled) => http.post(`/alert/rules/${id}/toggle`, { enabled: enabled ? 1 : 0 }),
  testRule: (id) => http.get(`/alert/rules/${id}/test`),
  // 事件
  listEvents: (params) => http.get('/alert/events', { params }),
  ackEvent: (id, user) => http.post(`/alert/events/${id}/ack`, { user }),
  resolveEvent: (id) => http.post(`/alert/events/${id}/resolve`)
}

export default http
