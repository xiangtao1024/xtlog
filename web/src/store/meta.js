import { ref } from 'vue'
import { categoryApi, appApi } from '../api'

/** 模块级单例状态：分类 / 应用元数据，供导航、筛选器、表单共用 */
export const categories = ref([])
export const apps = ref([])
export const loadingMeta = ref(false)

export async function loadCategories() {
  categories.value = (await categoryApi.list()) || []
  return categories.value
}

export async function loadApps() {
  // 列表改为分页返回 { list, total, page, pageSize }
  const data = await appApi.list({ pageSize: 100 })
  apps.value = data?.list || []
  return apps.value
}

export async function loadMeta() {
  loadingMeta.value = true
  try {
    await Promise.all([loadCategories(), loadApps()])
  } finally {
    loadingMeta.value = false
  }
}

export function categoryName(id) {
  if (id === null || id === undefined || Number(id) === 0) return '未分类'
  const hit = categories.value.find((c) => Number(c.id) === Number(id))
  return hit ? hit.name : '未分类'
}

export function appName(id) {
  const hit = apps.value.find((a) => Number(a.id) === Number(id))
  return hit ? hit.name : `#${id}`
}
