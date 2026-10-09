import { ref } from 'vue'
import { authApi, getToken, setToken } from '../api'

/** 当前登录用户（未登录为 null） */
export const user = ref(null)
export const authReady = ref(false)

export function isLogin() {
  return !!getToken()
}

export async function loadProfile() {
  try {
    user.value = await authApi.profile()
  } catch (e) {
    user.value = null
  } finally {
    authReady.value = true
  }
  return user.value
}

export async function login(payload) {
  const data = await authApi.login(payload)
  setToken(data?.token)
  user.value = data?.user || null
  authReady.value = true
  return data
}

export async function logout() {
  try {
    await authApi.logout()
  } catch (e) {
    // 会话本就可能已失效，忽略
  }
  setToken(null)
  user.value = null
}

export async function updateProfile(payload) {
  user.value = await authApi.updateProfile(payload)
  return user.value
}

/** 头像里显示的文字：优先昵称首字，退回账号首字 */
export function initials(u) {
  const name = String(u?.nickname || u?.username || '?')
  return name.slice(0, 1).toUpperCase()
}
