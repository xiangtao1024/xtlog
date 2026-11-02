import { createRouter, createWebHistory } from 'vue-router'
import { isLogin } from '../store/user'

/**
 * 注意：构建产物挂在 /static 下（vite base），但路由 base 用 '/'。
 * 因为后端 DispatcherHandler 已开启 SPA 回退（server.spa.enabled=true），
 * 任何不带扩展名的未知路径都会返回 static/index.html，因此 history 路由可直接用裸路径。
 */
const routes = [
  { path: '/', redirect: '/dashboard' },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true, bare: true }
  },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: () => import('../views/DashboardView.vue'),
    meta: { title: '首页', sub: '全站日志总览与关键指标' }
  },
  {
    path: '/apps',
    name: 'apps',
    component: () => import('../views/AppView.vue'),
    meta: { title: '应用管理', sub: '接入应用的增删改查，以及日志查看入口' }
  },
  {
    path: '/logs',
    name: 'logs',
    component: () => import('../views/LogView.vue'),
    // 不入侧栏菜单：只能从应用管理的「查看日志」进入
    // back: 页头左侧渲染返回按钮；appScoped: 标题显示为「当前应用名」而不是写死的页面名
    meta: { title: '查看日志', sub: '', menu: false, back: true, appScoped: true }
  },
  {
    path: '/analysis',
    name: 'analysis',
    component: () => import('../views/AnalysisView.vue'),
    meta: { title: '日志分析', sub: '按应用统计趋势、级别分布、热点排名与错误聚类' }
  },
  {
    path: '/categories',
    name: 'categories',
    component: () => import('../views/CategoryView.vue'),
    meta: { title: '分类管理', sub: '按业务域归类，应用挂载在分类下' }
  },
  {
    path: '/alerts/rules',
    name: 'alert-rules',
    component: () => import('../views/AlertRulesView.vue'),
    meta: { title: '告警规则', sub: '按级别 / 关键词 / 正则 / 速率定义触发条件' }
  },
  {
    path: '/alerts/events',
    name: 'alert-events',
    component: () => import('../views/AlertEventsView.vue'),
    meta: { title: '告警事件', sub: '查看触发记录、认领与恢复' }
  },
  {
    // 与 /logs 一样是应用级页面：不入菜单，只能从应用管理带 appId 进入
    path: '/dev-config',
    name: 'dev-config',
    component: () => import('../views/DevConfigView.vue'),
    meta: {
      title: '开发配置',
      sub: '当前应用的接入方式、上报协议与示例代码',
      menu: false,
      back: true,
      appScoped: true
    }
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('../views/SetupView.vue'),
    meta: { title: '设置', sub: '数据源与存储位置的初始化配置' }
  },
  {
    path: '/profile',
    name: 'profile',
    component: () => import('../views/ProfileView.vue'),
    meta: { title: '个人中心', sub: '维护账号资料与登录密码' }
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHistory('/'),
  routes
})

// 路由守卫：未登录且目标页面非公开 → 去登录页
router.beforeEach((to) => {
  if (to.meta?.public) return true
  if (!isLogin()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
