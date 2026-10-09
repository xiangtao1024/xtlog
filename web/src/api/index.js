import axios from 'axios'

/**
 * xtboot 统一响应：{ code, message, data }
 * 这里把 data 直接剖出来，业务层拿到的就是纯数据；非 200 直接 reject。
 */
const http = axios.create({ baseURL: '/api', timeout: 30000 })

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
  remove: (id) => http.delete(`/apps/${id}`)
}

export const logApi = {
  query: (params) => http.get('/logs', { params }),
  ingest: (payload) => http.post('/logs', payload)
}

export default http
