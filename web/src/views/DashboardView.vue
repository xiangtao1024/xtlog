<template>
  <div class="col">
    <!-- 指标卡 -->
    <div class="grid-4">
      <div class="card stat" v-for="s in cards" :key="s.key">
        <div class="stat-t">
          <span>{{ s.label }}</span>
          <span class="ic" :style="{ background: s.tint, color: s.color }">
            <el-icon :size="15"><component :is="s.icon" /></el-icon>
          </span>
        </div>
        <div class="stat-n">{{ loading ? '—' : formatNum(s.value) }}</div>
        <div class="stat-s muted small">{{ s.sub }}</div>
      </div>
    </div>

    <div class="grid-2-1">
      <!-- 近 7 日趋势 -->
      <div class="card">
        <div class="card-h">
          <b>近 7 日日志量</b>
          <span class="sub muted small">按日聚合</span>
        </div>
        <div class="card-b">
          <div v-loading="loading" class="bars">
            <div class="bar-wrap" v-for="d in trend" :key="d.date">
              <div class="bar-box">
                <div
                  class="bar"
                  :style="{ height: barHeight(d.count) }"
                  :title="`${d.date} · ${d.count} 条`"
                ></div>
              </div>
              <div class="bar-v">{{ d.count }}</div>
              <div class="bar-x muted small">{{ d.date }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 今日级别分布 -->
      <div class="card">
        <div class="card-h">
          <b>今日级别分布</b>
          <span class="sub muted small">共 {{ formatNum(overview.todayCount || 0) }} 条</span>
        </div>
        <div class="card-b">
          <div v-loading="loading" class="lvs">
            <div class="lv" v-for="l in levelStats" :key="l.level">
              <span class="chip sq" :class="levelClass(l.level)">{{ l.level }}</span>
              <div class="lv-bar">
                <div class="lv-fill" :style="{ width: levelPct(l.count) }"></div>
              </div>
              <span class="lv-n num">{{ l.count }}</span>
            </div>
            <p v-if="!levelTotal" class="empty muted small">今天还没有日志</p>
          </div>
        </div>
      </div>
    </div>

    <div class="grid-2-1">
      <!-- 今日 Top 应用 -->
      <div class="card">
        <div class="card-h">
          <b>今日日志 Top 应用</b>
          <div class="spacer"></div>
          <router-link class="link small" to="/apps">应用管理 →</router-link>
        </div>
        <el-table :data="topApps" v-loading="loading" size="small" style="width: 100%">
          <el-table-column label="应用" min-width="180">
            <template #default="{ row }">
              <span>{{ row.appName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="日志数" width="120">
            <template #default="{ row }">
              <span class="num">{{ formatNum(row.count) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openLogs(row.appId)">
                查看日志
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <span class="muted small">今天暂无数据</span>
          </template>
        </el-table>
      </div>

      <!-- 快捷入口 -->
      <div class="card">
        <div class="card-h"><b>快捷入口</b></div>
        <div class="card-b quick">
          <div class="q" v-for="q in quicks" :key="q.to" @click="$router.push(q.to)">
            <el-icon :size="16"><component :is="q.icon" /></el-icon>
            <div>
              <b>{{ q.title }}</b>
              <small class="muted">{{ q.desc }}</small>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { statsApi } from '../api'
import { levelClass } from '../utils/format'

const router = useRouter()
const loading = ref(false)
const overview = ref({})

const cards = computed(() => {
  const o = overview.value || {}
  return [
    {
      key: 'today',
      label: '今日日志',
      value: o.todayCount || 0,
      sub: '0 点至今',
      icon: 'Document',
      color: 'var(--accent)',
      tint: 'var(--accent-tint)'
    },
    {
      key: 'total',
      label: '累计日志',
      value: o.totalCount || 0,
      sub: `扫描 ${o.scannedTables || 0} 张月表`,
      icon: 'Files',
      color: 'var(--ok)',
      tint: 'var(--ok-tint)'
    },
    {
      key: 'apps',
      label: '接入应用',
      value: o.appCount || 0,
      sub: '可上报日志的应用数',
      icon: 'Grid',
      color: 'var(--warn)',
      tint: 'var(--warn-tint)'
    },
    {
      key: 'cats',
      label: '业务分类',
      value: o.categoryCount || 0,
      sub: '应用挂载在分类下',
      icon: 'Collection',
      color: 'var(--debug)',
      tint: 'var(--debug-tint)'
    }
  ]
})

const trend = computed(() => overview.value?.trend || [])
const levelStats = computed(() => overview.value?.levelStats || [])
const topApps = computed(() => overview.value?.topApps || [])

const levelTotal = computed(() =>
  levelStats.value.reduce((sum, l) => sum + Number(l.count || 0), 0)
)

const maxTrend = computed(() => Math.max(1, ...trend.value.map((d) => Number(d.count || 0))))

function barHeight(c) {
  const pct = (Number(c || 0) / maxTrend.value) * 100
  return `${Math.max(4, pct)}%`
}

function levelPct(c) {
  if (!levelTotal.value) return '0%'
  return `${(Number(c || 0) / levelTotal.value) * 100}%`
}

function formatNum(n) {
  const v = Number(n || 0)
  return v.toLocaleString('zh-CN')
}

function openLogs(appId) {
  router.push({ path: '/logs', query: { appId } })
}

const quicks = [
  { to: '/apps', icon: 'Grid', title: '应用管理', desc: '接入应用 / 查看日志 / 开发配置' },
  { to: '/categories', icon: 'Collection', title: '分类管理', desc: '按业务域归类' },
  { to: '/settings', icon: 'Setting', title: '数据源设置', desc: '切换存储位置' }
]

async function load() {
  loading.value = true
  try {
    overview.value = (await statsApi.overview()) || {}
  } catch (e) {
    ElMessage.error(e.message || '总览加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.col {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.grid-4 {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.grid-2-1 {
  display: grid;
  grid-template-columns: 1.6fr 1fr;
  gap: 16px;
}
@media (max-width: 1180px) {
  .grid-4 {
    grid-template-columns: repeat(2, 1fr);
  }
  .grid-2-1 {
    grid-template-columns: 1fr;
  }
}
.stat {
  padding: 16px 18px;
}
.stat-t {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12.5px;
  color: var(--text-2);
}
.stat-t .ic {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  display: grid;
  place-items: center;
}
.stat-n {
  margin-top: 10px;
  font-size: 26px;
  font-weight: 650;
  letter-spacing: -0.4px;
  font-variant-numeric: tabular-nums;
}
.stat-s {
  margin-top: 2px;
}
.muted {
  color: var(--text-3);
}
.small {
  font-size: 12px;
}
.spacer {
  flex: 1;
}
.num {
  font-variant-numeric: tabular-nums;
}
.link {
  color: var(--accent);
  text-decoration: none;
}

/* 柱状趋势 */
.bars {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 10px;
  height: 190px;
}
.bar-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
}
.bar-box {
  flex: 1;
  width: 100%;
  display: flex;
  align-items: flex-end;
}
.bar {
  width: 100%;
  border-radius: 6px 6px 3px 3px;
  background: linear-gradient(180deg, var(--accent) 0%, rgba(37, 99, 235, 0.55) 100%);
  transition: height 0.3s;
}
.bar-v {
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
  color: var(--text-2);
}
.bar-x {
  font-size: 11px;
}

/* 级别分布 */
.lvs {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.lv {
  display: grid;
  grid-template-columns: 62px 1fr 52px;
  align-items: center;
  gap: 10px;
}
.lv-bar {
  height: 8px;
  border-radius: 99px;
  background: var(--surface-2);
  overflow: hidden;
}
.lv-fill {
  height: 100%;
  border-radius: 99px;
  background: var(--accent);
  transition: width 0.3s;
}
.lv-n {
  text-align: right;
  font-size: 12.5px;
  color: var(--text-2);
}
.empty {
  margin: 0;
}

/* 快捷入口 */
.quick {
  display: grid;
  gap: 8px;
}
.q {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 11px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.q:hover {
  border-color: var(--accent-ring);
  background: var(--surface-2);
}
.q b {
  display: block;
  font-size: 13px;
  font-weight: 600;
}
.q small {
  font-size: 11.5px;
}
.q .el-icon {
  color: var(--accent);
}
</style>
