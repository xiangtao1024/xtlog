<template>
  <!-- 登录页裸奔：不带侧栏 -->
  <router-view v-if="route.meta?.bare" />

  <div v-else class="shell">
    <aside class="nav">
      <div class="brand">
        <span class="mark">
          <el-icon :size="17"><Files /></el-icon>
        </span>
        <div>
          <b>xtlog</b>
          <small>统一日志平台</small>
        </div>
      </div>

      <div class="nav-scroll">
        <router-link class="nav-item" :class="{ on: isOn('/dashboard') }" to="/dashboard">
          <el-icon><Odometer /></el-icon>
          <span>首页</span>
        </router-link>

        <div class="nav-group">运营</div>
        <!--
          日志页 / 开发配置页都不进菜单：两者都是应用级页面，只能从应用列表点进去
          （带 appId），所以页面里不需要再选应用；高亮归并到「应用管理」，
          避免出现「没有任何菜单高亮」的割裂感。
        -->
        <router-link
          class="nav-item"
          :class="{ on: isOn('/apps') || isOn('/logs') || isOn('/dev-config') }"
          to="/apps"
        >
          <el-icon><Grid /></el-icon>
          <span>应用管理</span>
          <span class="nav-badge" v-if="apps.length">{{ apps.length }}</span>
        </router-link>
        <router-link class="nav-item" :class="{ on: isOn('/categories') }" to="/categories">
          <el-icon><Collection /></el-icon>
          <span>分类管理</span>
          <span class="nav-badge" v-if="categories.length">{{ categories.length }}</span>
        </router-link>
      </div>

      <!-- 左下角用户：点击弹出 个人中心 / 设置 / 退出登录 -->
      <el-popover
        v-model:visible="popOpen"
        placement="top-start"
        :width="216"
        trigger="click"
        popper-class="user-pop"
      >
        <template #reference>
          <div class="nav-foot user-btn" :class="{ active: popOpen }">
            <span class="avatar">{{ initials(user) }}</span>
            <div class="u">
              <b>{{ user?.nickname || user?.username || '未登录' }}</b>
              <span class="mono">{{ user?.role === 'admin' ? '管理员' : '本地实例' }}</span>
            </div>
            <el-icon class="caret"><ArrowUp /></el-icon>
          </div>
        </template>

        <div class="pop-card">
          <div class="pop-head">
            <span class="avatar lg">{{ initials(user) }}</span>
            <div class="pop-id">
              <b>{{ user?.nickname || user?.username }}</b>
              <span class="mono muted small">{{ user?.email || '未设置邮箱' }}</span>
            </div>
          </div>
          <div class="pop-menu">
            <div class="pop-item" @click="go('/profile')">
              <el-icon><User /></el-icon><span>个人中心</span>
            </div>
            <div class="pop-item" @click="go('/settings')">
              <el-icon><Setting /></el-icon><span>设置</span>
            </div>
            <div class="pop-item danger" @click="doLogout">
              <el-icon><SwitchButton /></el-icon><span>退出登录</span>
            </div>
          </div>
        </div>
      </el-popover>
    </aside>

    <main class="stage">
      <div class="stage-head">
        <div class="head-l">
          <el-button
            v-if="route.meta.back"
            class="head-back"
            size="small"
            title="返回应用管理"
            @click="backToApps"
          >
            <el-icon><Back /></el-icon>
          </el-button>
          <div>
            <h1>{{ pageTitle }}</h1>
            <p v-if="pageSub">{{ pageSub }}</p>
          </div>
        </div>
        <span class="chip" :class="dbChip.cls">
          <span class="dot" :class="dbChip.dot"></span>{{ dbChip.text }}
        </span>
      </div>

      <div class="stage-body">
        <router-view />
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { categories, apps, loadMeta } from './store/meta'
import { user, initials, loadProfile, logout } from './store/user'
import { setupApi, onUnauthorized } from './api'

const route = useRoute()
const router = useRouter()
const dbStatus = ref(null)
const popOpen = ref(false)

const isOn = (p) => route.path === p || route.path.startsWith(p + '/')

/**
 * 应用级页面（/logs、/dev-config）的标题显示「当前应用名」而不是写死的页面名——
 * 这两个页面都是从应用列表点进来的，标题就该是「你点的那个应用」。
 */
const scopedApp = computed(() => {
  if (!route.meta.appScoped) return null
  const id = Number(route.query.appId)
  if (!id) return null
  return apps.value.find((a) => Number(a.id) === id) || null
})

const pageTitle = computed(() => scopedApp.value?.name || route.meta.title || 'xtlog')
const pageSub = computed(() => route.meta.sub || '')

const backToApps = () => router.push('/apps')

const dbChip = computed(() => {
  if (!dbStatus.value) return { cls: 'info', dot: 'off', text: '状态未知' }
  const ok = dbStatus.value.initialized
  return {
    cls: ok ? 'ok' : 'warn',
    dot: ok ? 'ok' : 'warn',
    text: `${dbStatus.value.dbType || 'db'} · ${ok ? '已就绪' : '未初始化'}`
  }
})

function go(path) {
  popOpen.value = false
  router.push(path)
}

async function doLogout() {
  popOpen.value = false
  try {
    await ElMessageBox.confirm('确认退出登录？', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  await logout()
  ElMessage.success('已退出登录')
  router.replace({ path: '/login' })
}

// 任意接口返回 401 → 清理状态并回登录页
onUnauthorized.handler = async () => {
  await logout()
  if (route.meta?.bare !== true) {
    router.replace({ path: '/login', query: { redirect: route.fullPath } })
  }
}

onMounted(async () => {
  if (!user.value) {
    await loadProfile()
  }
  try {
    dbStatus.value = await setupApi.status()
    await loadMeta()
  } catch (e) {
    // 首次未初始化时静默，交给页面处理
  }
})

defineExpose({ refreshDbStatus: async () => (dbStatus.value = await setupApi.status()) })
</script>

<style scoped>
.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-radius: 10px;
  color: var(--text-2);
  text-decoration: none;
  font-size: 13.5px;
  transition: background 0.15s, color 0.15s;
}
.nav-item:hover {
  background: var(--surface-2);
  color: var(--text-1);
}
.nav-item.on {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 600;
}
.nav-item .nav-badge {
  margin-left: auto;
}
.user-btn {
  cursor: pointer;
  border-top: 1px solid var(--line-2);
}
.user-btn:hover,
.user-btn.active {
  background: var(--surface-2);
}
.caret {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-3);
}
.pop-card {
  padding: 4px;
}
.pop-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 8px 12px;
  border-bottom: 1px solid var(--line-2);
}
.pop-id {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.avatar.lg {
  width: 34px;
  height: 34px;
  font-size: 13px;
}
.pop-menu {
  padding-top: 6px;
}
.pop-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: 8px;
  font-size: 13.5px;
  color: var(--text-1);
  cursor: pointer;
}
.pop-item:hover {
  background: var(--surface-2);
}
.pop-item.danger {
  color: var(--danger);
}
.pop-item.danger:hover {
  background: rgba(239, 68, 68, 0.08);
}
.muted {
  color: var(--text-3);
}
.small {
  font-size: 12px;
}
.head-l {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.head-back {
  width: 32px;
  height: 32px;
  padding: 0;
  border-radius: 9px;
  flex-shrink: 0;
}
</style>
