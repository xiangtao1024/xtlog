<template>
  <div class="col">
    <!-- 未带 appId 进来：提示回到应用列表 -->
    <div v-if="!appId" class="card empty-card">
      <el-empty description="请从「应用管理」里选择应用后点「查看日志」">
        <el-button type="primary" @click="router.push('/apps')">去应用管理</el-button>
      </el-empty>
    </div>

    <template v-else>
      <!-- 检索条件（应用已由入口锁定，页头有返回按钮与应用名，这里不再重复） -->
      <div class="card">
        <div class="card-b toolbar">
          <el-select
            v-model="filters.level"
            placeholder="全部级别"
            clearable
            style="width: 132px"
            @change="reloadAll"
          >
            <el-option v-for="l in LEVEL_OPTIONS" :key="l" :value="l" :label="l" />
          </el-select>

          <el-date-picker
            v-model="range"
            type="datetimerange"
            range-separator="→"
            start-placeholder="日志开始时间"
            end-placeholder="日志结束时间"
            :default-time="defaultTime"
            style="width: 360px"
            @change="reloadAll"
          />

          <el-input
            v-model="filters.keyword"
            placeholder="内容关键字"
            clearable
            style="width: 220px"
            @keyup.enter="reloadAll"
            @clear="reloadAll"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>

          <el-input
            v-model="filters.srcIp"
            placeholder="来源 IP"
            clearable
            style="width: 150px"
            @keyup.enter="reloadAll"
            @clear="reloadAll"
          >
            <template #prefix><el-icon><Monitor /></el-icon></template>
          </el-input>

          <el-button type="primary" :loading="loading" @click="reloadAll">查询</el-button>
          <el-button @click="reset">重置</el-button>

          <div class="spacer"></div>

          <!-- 关键词没走成全文索引时给个明确说法，避免用户以为系统卡死 -->
          <el-tooltip
            v-if="ftsSkipped"
            placement="bottom"
            :show-after="150"
            content="全文索引按连续 3 字符切词，关键词太短时无法命中索引，只能逐条扫描正文。输入 3 个字符以上即可秒回。"
          >
            <span class="chip warn sq">
              <el-icon><Warning /></el-icon>
              关键词过短，未走索引
            </span>
          </el-tooltip>

          <div class="live">
            <span class="dot" :class="live ? 'ok' : 'off'"></span>
            <span class="live-txt">{{ live ? '实时接收中' : '实时已关闭' }}</span>
            <el-switch v-model="live" size="small" />
          </div>
        </div>
      </div>

      <el-tabs v-model="tab" class="tabs">
        <!-- 视图一：实时日志（tail -f 尾随） -->
        <el-tab-pane label="实时日志" name="console">
          <LogConsole
            ref="liveConsoleRef"
            mode="live"
            title="控制台输出"
            :app-id="appId"
            :filters="consoleFilters"
            :size="50"
          />
        </el-tab-pane>

        <!-- 视图二：全部日志（同类控制台，向上滚动懒加载更早） -->
        <el-tab-pane label="全部日志" name="all">
          <LogConsole
            ref="allConsoleRef"
            mode="all"
            title="全部日志"
            :app-id="appId"
            :filters="consoleFilters"
            :size="50"
          />
        </el-tab-pane>

        <!-- 视图三：表格记录（分页） -->
        <el-tab-pane label="表格记录" name="table">
          <div class="card">
            <div class="card-h">
              <b>日志记录</b>
              <span class="sub muted small">共 {{ total }} 条</span>
              <div class="spacer"></div>
              <span v-for="t in scannedTables" :key="t" class="chip info sq mono">{{ t }}</span>
            </div>

            <!-- 分页放表格上方：翻页时不用滚到底部 -->
            <div class="page-bar">
              <el-pagination
                v-model:current-page="page"
                v-model:page-size="pageSize"
                :page-sizes="[10, 20, 50, 100]"
                :total="total"
                layout="total, sizes, prev, pager, next, jumper"
                @current-change="reloadTable"
                @size-change="onSizeChange"
              />
              <div class="spacer"></div>
              <span class="muted small" v-if="liveCount">实时新增 {{ liveCount }} 条</span>
            </div>

            <el-table
              :data="rows"
              v-loading="loading"
              row-key="_key"
              :row-class-name="rowClass"
              style="width: 100%"
            >
              <el-table-column label="日志时间" width="176">
                <template #default="{ row }">
                  <span class="mono num ts">{{ fmtTime(row.log_time) }}</span>
                  <div class="muted small mono num">入库 {{ fmtTime(row.receive_time) || '—' }}</div>
                </template>
              </el-table-column>
              <el-table-column label="级别" width="86">
                <template #default="{ row }">
                  <span class="chip sq" :class="levelClass(row.level)">{{ row.level }}</span>
                </template>
              </el-table-column>
              <el-table-column label="Logger" width="200">
                <template #default="{ row }">
                  <span class="mono muted small">{{ row.logger || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="线程" width="150">
                <template #default="{ row }">
                  <span class="mono muted small">{{ row.thread || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="来源 IP" width="140">
                <template #default="{ row }">
                  <span class="mono muted small">{{ row.src_ip || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="内容" min-width="320">
                <template #default="{ row }">
                  <span
                    v-if="row.cont_seq > 0"
                    class="chip sq cont-chip"
                    :title="'这条是上一条的继续 · 原日志第 ' + (row.cont_seq + 1) + ' 段'"
                  >续 {{ row.cont_seq + 1 }}</span>
                  <span class="msg mono">{{ row.message }}</span>
                </template>
              </el-table-column>
              <template #empty>
                <span class="muted small">没有匹配的日志</span>
              </template>
            </el-table>
          </div>
        </el-tab-pane>
      </el-tabs>
    </template>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { logApi, getToken } from '../api'
import LogConsole from '../components/LogConsole.vue'
import { apps, loadMeta } from '../store/meta'
import { fmtTime, levelClass, LEVEL_OPTIONS } from '../utils/format'

const route = useRoute()
const router = useRouter()

// 日志页不占侧栏菜单：从应用列表或「日志分析」下钻进来。
// appId 支持 'all'（分析页「全部应用」下钻），此时不向后端传 appId。
const rawAppId = route.query.appId ? String(route.query.appId) : null
const appId = rawAppId === 'all' ? 'all' : rawAppId ? Number(rawAppId) : null

const tab = ref('console')

// 表格视图（分页）
const rows = ref([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const scannedTables = ref([])
/** 上一次查询的关键词没能走全文索引（通常是少于 3 个字符） */
const ftsSkipped = ref(false)
const live = ref(false)
const liveCount = ref(0)

// 两个控制台视图
const liveConsoleRef = ref(null)
const allConsoleRef = ref(null)

const defaultTime = [new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]

// 支持从「日志分析」下钻过来：带上 keyword / level / srcIp / 时间范围。
// query 里是毫秒时间戳字符串，转成 Date 给日期组件（组件对时间戳字符串解析不可靠）
const filters = reactive({
  level: route.query.level || null,
  keyword: route.query.keyword || '',
  srcIp: route.query.srcIp || ''
})
const range = ref(
  route.query.startTime && route.query.endTime
    ? [new Date(Number(route.query.startTime)), new Date(Number(route.query.endTime))]
    : null
)

let seq = 0
const keyOf = () => `r${++seq}`

const currentApp = computed(() =>
  appId === 'all' ? null : apps.value.find((a) => Number(a.id) === Number(appId))
)

/** 页头标题：全部应用模式下显示「全部应用」 */
const appTitle = computed(() => (appId === 'all' ? '全部应用' : currentApp.value?.name || ''))

/** Date / 时间戳统一转毫秒数，供接口与 SSE 使用 */
function toMs(v) {
  if (v instanceof Date) return v.getTime()
  return v
}

/** 传给控制台组件的筛选条件（含时间范围） */
const consoleFilters = computed(() => {
  const f = { level: filters.level || undefined, keyword: filters.keyword || undefined, srcIp: filters.srcIp || undefined }
  if (range.value && range.value.length === 2) {
    f.startTime = toMs(range.value[0])
    f.endTime = toMs(range.value[1])
  }
  return f
})

/**
 * SSE 地址：EventSource 不能自定义请求头，所以把会话 token 拼在查询串上。
 * 框架 StpUtil 支持「请求头 → Cookie → 查询参数」三种读取方式，这里正好用得上。
 */
const streamUrl = computed(() => {
  const parts = []
  if (appId && appId !== 'all') parts.push(`appId=${appId}`)
  const token = getToken()
  if (token) parts.push(`satoken=${encodeURIComponent(token)}`)
  return `/api/logs/stream${parts.length ? '?' + parts.join('&') : ''}`
})

function commonParams() {
  const p = {}
  if (appId && appId !== 'all') p.appId = appId
  if (filters.level) p.level = filters.level
  if (filters.keyword) p.keyword = filters.keyword
  if (filters.srcIp && filters.srcIp.trim()) p.srcIp = filters.srcIp.trim()
  if (range.value && range.value.length === 2) {
    p.startTime = toMs(range.value[0])
    p.endTime = toMs(range.value[1])
  }
  return p
}

function decorate(list) {
  return (list || []).map((r) => ({ ...r, _key: keyOf() }))
}

/** 表格：按页码拉当前页 */
async function reloadTable() {
  loading.value = true
  try {
    const data = await logApi.query({ ...commonParams(), page: page.value, pageSize: pageSize.value })
    rows.value = decorate(data?.list)
    total.value = Number(data?.total || 0)
    scannedTables.value = data?.scannedTables || []
    ftsSkipped.value = !!data?.ftsSkipped
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function reloadConsoles() {
  const jobs = []
  if (liveConsoleRef.value) jobs.push(liveConsoleRef.value.reload())
  if (allConsoleRef.value) jobs.push(allConsoleRef.value.reload())
  return Promise.all(jobs)
}

function reloadAll() {
  liveCount.value = 0
  page.value = 1
  return Promise.all([reloadTable(), reloadConsoles()])
}

function onSizeChange() {
  page.value = 1
  reloadTable()
}

function reset() {
  filters.level = null
  filters.keyword = ''
  filters.srcIp = ''
  ftsSkipped.value = false
  range.value = null
  reloadAll()
}

function rowClass({ row }) {
  return row._live ? 'row-live' : ''
}

/* ---------------- SSE 实时订阅 ---------------- */
let es = null
let pending = [] // SSE 缓冲：避免逐条触发整列表重渲染风暴
let flushTimer = null
let paused = false // 标签页隐藏时暂停 flush，节省后台 CPU

function stopStream() {
  if (es) {
    es.close()
    es = null
  }
}

function scheduleFlush() {
  if (flushTimer || paused) return
  flushTimer = setTimeout(flush, 200)
}

/** 每 ~200ms 把缓冲的日志成批送进视图：无论日志多快，DOM 更新 ≤5 次/秒 */
function flush() {
  flushTimer = null
  if (paused || !pending.length) return
  const batch = pending
  pending = []
  // 两个控制台各推一份：实时视图尾随，全量视图贴在底部时才跟随
  liveConsoleRef.value?.pushMany(batch)
  allConsoleRef.value?.pushMany(batch)
  // 表格只在首页把实时数据插到顶部（保持分页语义）
  if (page.value === 1) {
    const pre = batch.slice().reverse() // 最新在前
    rows.value = pre.concat(rows.value)
    if (rows.value.length > pageSize.value) rows.value.length = pageSize.value
  }
}

function startStream() {
  stopStream()
  es = new EventSource(streamUrl.value)
  es.onmessage = (ev) => {
    let raw
    try {
      raw = JSON.parse(ev.data)
    } catch {
      return
    }
    if (!matches(raw)) return
    raw._key = keyOf()
    raw._live = true
    liveCount.value++
    pending.push(raw)
    // 极端后台积压时丢弃最旧，防止内存无限增长
    if (pending.length > 5000) pending.shift()
    scheduleFlush()
  }
  es.onerror = () => {
    /* EventSource 会自动重连 */
  }
}

function onVisibility() {
  paused = document.hidden
  if (!paused) scheduleFlush()
}

function matches(r) {
  if (appId && Number(r.app_id) !== Number(appId)) return false
  if (filters.level && String(r.level).toUpperCase() !== filters.level) return false
  if (filters.keyword && !String(r.message || '').includes(filters.keyword)) return false
  if (filters.srcIp && filters.srcIp.trim() && !String(r.src_ip || '').includes(filters.srcIp.trim())) return false
  return true
}

onMounted(async () => {
  document.addEventListener('visibilitychange', onVisibility)
  if (!apps.value.length) {
    try {
      await loadMeta()
    } catch {
      /* 忽略 */
    }
  }
  if (appId) {
    await reloadTable()
    // 控制台组件挂载后再拉，避免组件还没就绪白等一次
    await reloadConsoles()
  }
})

watch(live, (on) => (on ? startStream() : stopStream()))
watch(streamUrl, () => {
  if (live.value) startStream()
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', onVisibility)
  if (flushTimer) clearTimeout(flushTimer)
  flushTimer = null
  stopStream()
})
</script>

<style scoped>
.col {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.spacer {
  flex: 1;
}
.muted {
  color: var(--text-3);
}
.small {
  font-size: 12px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.num {
  font-variant-numeric: tabular-nums;
}
.ts {
  font-size: 12px;
  color: var(--text-2);
}
.msg {
  font-size: 12.5px;
  white-space: pre-wrap;
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
/* 分片续段标记：这条日志是上一条的继续 */
.cont-chip {
  margin-right: 6px;
  border-color: rgba(251, 191, 36, 0.5);
  background: rgba(251, 191, 36, 0.14);
  color: #d99a1f;
  vertical-align: middle;
}
.live {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 4px 12px 4px 10px;
  border: 1px solid var(--line);
  border-radius: 99px;
  background: var(--surface-2);
}
.live-txt {
  font-size: 12px;
  color: var(--text-2);
}
.page-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 18px;
  border-bottom: 1px solid var(--line-2);
}
.tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}
.empty-card {
  padding: 24px;
}
</style>

<style>
.el-table .row-live td:first-child {
  box-shadow: inset 3px 0 0 var(--accent);
}
.el-table .row-live {
  background: #fbfcff;
}
</style>
