<template>
  <div>
    <div class="card console-card" ref="cardRef" :class="{ 'css-fs': fs }">
      <div class="card-h console-h">
        <b>{{ title }}</b>
        <span class="sub muted small">{{ tip }}</span>
        <div class="spacer"></div>
        <el-checkbox v-if="mode === 'live'" v-model="autoScroll" size="small">自动滚动</el-checkbox>
        <el-button size="small" :loading="loading" @click="reload">
          <el-icon style="margin-right: 4px"><Refresh /></el-icon>重新拉取
        </el-button>
        <el-button size="small" @click="clear">
          <el-icon style="margin-right: 4px"><Delete /></el-icon>清屏
        </el-button>
        <el-button size="small" @click="toggleFs">
          <el-icon style="margin-right: 4px">
            <Aim v-if="!fs" /><Close v-else />
          </el-icon>
          {{ fs ? '退出全屏' : '全屏' }}
        </el-button>
      </div>

      <div class="console" ref="scrollRef" @scroll="onScroll">
        <!-- 向上懒加载：滚到顶部时提示 / 加载中 -->
        <div v-if="mode === 'all'" class="older">
          <span v-if="loadingOlder" class="older-loading">正在加载更早的日志…</span>
          <span v-else-if="hasMore" class="older-hint">↑ 向上滚动加载更早的日志</span>
          <span v-else class="older-end">— 已到最早一条 —</span>
        </div>

        <p v-if="!rows.length && !loading" class="console-empty muted">
          <span class="cursor">$</span>
          {{ mode === 'live' ? '等待日志推送…（右上角打开「实时接收中」开关）' : '暂无日志' }}
        </p>

        <div
          v-for="r in rows"
          :key="r._k"
          class="line"
          :class="levelClass(r.level)"
          :title="'双击查看详情 · 日志时间 ' + fmtTime(r.log_time) + ' · 入库时间 ' + fmtTime(r.receive_time)"
          @dblclick="openDetail(r)"
        >
          <div class="l-head">
            <span class="l-ts">{{ fmtTime(r.log_time) }}</span>
            <span class="l-lv">{{ pad(r.level) }}</span>
            <span class="l-app" :title="r.logger || '—'">{{ r.logger || '—' }}</span>
            <span
              v-if="r.src_ip"
              class="l-ip"
              :title="'来源 IP · ' + r.src_ip"
            >{{ r.src_ip }}</span>
          </div>
          <div class="l-body">
            <span class="l-msg mono">{{ fullText(r.message) }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 双击某一行：完整日志详情 -->
    <el-dialog
      v-model="detail.visible"
      title="日志详情"
      width="760px"
      top="8vh"
      class="log-detail-dialog"
    >
      <div v-if="detail.row" class="detail">
        <div class="d-grid">
          <div class="d-item">
            <label>日志时间</label>
            <span class="mono num">{{ fmtTime(detail.row.log_time) || '—' }}</span>
          </div>
          <div class="d-item">
            <label>入库时间</label>
            <span class="mono num">{{ fmtTime(detail.row.receive_time) || '—' }}</span>
          </div>
          <div class="d-item">
            <label>级别</label>
            <span class="chip sq" :class="levelClass(detail.row.level)">{{ detail.row.level || '—' }}</span>
          </div>
          <div class="d-item">
            <label>应用</label>
            <span class="mono">{{ appName || '—' }}</span>
          </div>
          <div class="d-item">
            <label>Logger</label>
            <span class="mono">{{ detail.row.logger || '—' }}</span>
          </div>
          <div class="d-item">
            <label>线程</label>
            <span class="mono">{{ detail.row.thread || '—' }}</span>
          </div>
          <div class="d-item">
            <label>TraceId</label>
            <span class="mono">{{ detail.row.trace_id || '—' }}</span>
          </div>
          <div class="d-item">
            <label>来源 IP</label>
            <span class="mono">{{ detail.row.src_ip || '—' }}</span>
          </div>
        </div>

        <div class="d-msg">
          <div class="d-msg-h">
            <label>完整内容</label>
            <el-button size="small" text type="primary" @click="copyMsg">复制</el-button>
          </div>
          <pre class="d-pre mono">{{ detail.row.message }}</pre>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { logApi } from '../api'
import { apps } from '../store/meta'
import { fmtTime, levelClass } from '../utils/format'

const props = defineProps({
  appId: { type: [Number, String], default: null },
  /** { level, keyword, startTime, endTime } */
  filters: { type: Object, default: () => ({}) },
  /** live = 实时尾随（tail -f）；all = 全量回溯（向上懒加载更早） */
  mode: { type: String, default: 'live' },
  title: { type: String, default: '控制台输出' },
  /** 首屏条数 / 每次懒加载条数 */
  size: { type: Number, default: 50 },
  /** 行数上限（超出从顶部丢弃） */
  max: { type: Number, default: 1000 }
})

const MAX_LINES = props.max
/** 全量视图（all）渲染上限：向上懒加载只增不减，必须封顶，否则 DOM 无限增长撑爆浏览器 */
const ALL_CAP = 2000

const rows = ref([])
const loading = ref(false)
const loadingOlder = ref(false)
const hasMore = ref(false)
const cursor = ref(null)
const autoScroll = ref(true)
/** 是否贴在底部（决定实时新行要不要自动滚动） */
const stickBottom = ref(true)

const scrollRef = ref(null)
const cardRef = ref(null)
const fs = ref(false)

const detail = ref({ visible: false, row: null })

let seq = 0
const keyOf = () => `c${++seq}`

const appName = computed(() => {
  const a = apps.value.find((x) => Number(x.id) === Number(props.appId))
  return a?.name || ''
})

const tip = computed(() => {
  if (props.mode === 'all') {
    const capped = rows.value.length >= ALL_CAP
    return `${rows.value.length} 行 · 已加载${hasMore.value ? '部分' : '全部'}${hasMore.value ? '（向上滚动继续加载）' : ''}${capped ? ' · 已达渲染上限，更早请用时间范围筛选' : ''}`
  }
  return `${rows.value.length} 行 · 首屏最新 ${props.size} 条 · 上限 ${MAX_LINES}`
})

function params(extra) {
  const p = { appId: props.appId }
  const f = props.filters || {}
  if (f.level) p.level = f.level
  if (f.keyword) p.keyword = f.keyword
  if (f.startTime) p.startTime = f.startTime
  if (f.endTime) p.endTime = f.endTime
  return Object.assign(p, extra || {})
}

function decorate(list) {
  return (list || []).map((r) => ({ ...r, _k: keyOf() }))
}

function scrollToBottom() {
  const el = scrollRef.value
  if (el) el.scrollTop = el.scrollHeight
}

/** 首屏 / 重新拉取：拿最新 size 条，reverse 成「旧 → 新」 */
async function reload() {
  loading.value = true
  try {
    const data = await logApi.query(params({ limit: props.size }))
    rows.value = decorate(data?.list).reverse()
    cursor.value = data?.nextCursor || null
    hasMore.value = data?.hasMore !== false && !!data?.nextCursor
    stickBottom.value = true
    await nextTick()
    scrollToBottom()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

/**
 * 向上懒加载更早日志。
 * 接口按 log_time DESC 返回，reverse 后是「更早 → 更晚」，整体 prepend 到现有行之前。
 * 关键：prepend 会顶掉滚动位置，必须按高度差把 scrollTop 补回去，否则视图会跳。
 */
async function loadOlder() {
  if (!hasMore.value || loadingOlder.value || !cursor.value) return
  loadingOlder.value = true
  const el = scrollRef.value
  const prevHeight = el ? el.scrollHeight : 0
  const prevTop = el ? el.scrollTop : 0
  try {
    const data = await logApi.query(params({ cursor: cursor.value, limit: props.size }))
    const older = decorate(data?.list).reverse()
    let next = older.concat(rows.value)
    // 渲染封顶：超出丢弃最旧（顶部）部分，避免无限增长撑爆浏览器
    if (next.length > ALL_CAP) next = next.slice(0, ALL_CAP)
    rows.value = next
    cursor.value = data?.nextCursor || null
    hasMore.value = data?.hasMore !== false && !!data?.nextCursor
    await nextTick()
    if (el) {
      el.scrollTop = prevTop + (el.scrollHeight - prevHeight)
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loadingOlder.value = false
  }
}

function onScroll() {
  const el = scrollRef.value
  if (!el) return
  stickBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 48
  if (props.mode === 'all' && el.scrollTop < 48) {
    loadOlder()
  }
}

/** SSE 推来一条：追加到底部。全量视图下只有贴在底部时才自动跟随 */
async function push(r) {
  rows.value.push({ ...r, _k: keyOf() })
  if (rows.value.length > MAX_LINES) {
    rows.value.splice(0, rows.value.length - MAX_LINES)
  }
  const follow = props.mode === 'live' ? autoScroll.value : stickBottom.value
  if (follow) {
    await nextTick()
    scrollToBottom()
  }
}

/**
 * 批量推送（LogView 已按 ~200ms 节流成批）。一次性合并追加 + 封顶 + 滚动一次，
 * 避免逐条 push 在高日志频率下产生整列表重渲染风暴。
 */
function pushMany(list) {
  if (!list || !list.length) return
  const added = list.map((r) => ({ ...r, _k: keyOf() }))
  let next = rows.value.concat(added)
  const cap = props.mode === 'live' ? MAX_LINES : ALL_CAP
  if (next.length > cap) next = next.slice(next.length - cap)
  rows.value = next
  const follow = props.mode === 'live' ? autoScroll.value : stickBottom.value
  if (follow) nextTick(scrollToBottom)
}

function clear() {
  rows.value = []
  cursor.value = null
  hasMore.value = false
}

function pad(level) {
  const s = String(level || 'INFO').toUpperCase()
  return s.length >= 5 ? s : (s + '     ').slice(0, 5)
}

/**
 * 控制台原样完整输出：不做任何截断、不追加省略号。
 * 多行（如异常堆栈）靠 CSS 的 white-space: pre-wrap 整体换行显示，
 * 续行自动对齐到 message 列起点；超长行按字符折行，不产生横向滚动条。
 */
function fullText(msg) {
  return String(msg == null ? '' : msg)
}

/* ---------------- 详情弹窗 ---------------- */
function openDetail(r) {
  detail.value = { visible: true, row: r }
}

async function copyMsg() {
  const text = String(detail.value?.row?.message || '')
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('浏览器拒绝了剪贴板访问，请手动选中复制')
  }
}

/* ---------------- 全屏（浏览器视口内铺满，不调用原生 Fullscreen API） ---------------- */
/**
 * 只做「铺满浏览器窗口」：固定定位覆盖视口。
 * 不用 requestFullscreen() 是刻意的——那是操作系统级全屏（会盖住任务栏、脱离页面布局），
 * 这里要的是「内容区放大到占满浏览器可视区域」，且行为在任何环境都一致、不会被浏览器拒绝。
 */
function toggleFs() {
  fs.value = !fs.value
}

function onKeydown(e) {
  if (e.key === 'Escape' && fs.value) fs.value = false
}

onMounted(() => {
  document.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
})

defineExpose({ reload, push, pushMany, clear })
</script>

<style scoped>
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
.console-h {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
/* 高度自适应：铺满到页面底部，不再留一大段空白。
   视口高度减去上方固定占用的高度（外壳/页头/工具条/标签页头/卡片头 ≈ 312px），
   窄屏工具条换行时再额外多减一点（见下方 media query）。 */
.console {
  height: calc(100vh - 312px);
  min-height: 420px;
  overflow: auto;
  padding: 12px 14px;
  background: #0f1115;
  border-bottom-left-radius: var(--r);
  border-bottom-right-radius: var(--r);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.65;
}
/* 窄屏：检索工具条换行成两行，上方占用更高，多减一点避免页面多出滚动条 */
@media (max-width: 1024px) {
  .console {
    height: calc(100vh - 372px);
  }
}
/* 全屏：铺满浏览器视口（非系统级全屏） */
.console-card.css-fs {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  /* 必须低于 el-dialog 的层级（Element Plus 从 2000 起递增），
     否则全屏状态下双击行弹出的详情框会被这张卡片盖住 */
  z-index: 1000;
  border-radius: 0;
  display: flex;
  flex-direction: column;
  background: var(--surface);
}
.console-card.css-fs .console {
  flex: 1;
  height: auto;
  min-height: 0;
  border-radius: 0;
}
.console-empty {
  margin: 0;
  font-family: inherit;
  color: #6b7280;
}
.cursor {
  color: #16a34a;
  margin-right: 6px;
}
.older {
  text-align: center;
  padding: 4px 0 8px;
  font-size: 12px;
  font-family: inherit;
}
.older-loading {
  color: #fbbf24;
}
.older-hint {
  color: #6b7280;
}
.older-end {
  color: #4b5563;
}
/* 每条日志是一个独立「块」：头部一行（时间/级别/Logger）+ 下方换行的内容，
   靠左边彩色竖条 + 头部分隔线区分不同条目，颜色按级别区分 */
.line {
  display: block;
  padding: 7px 12px 8px;
  margin: 0 0 8px;
  border-radius: 7px;
  border-left: 3px solid var(--lv, #4b5563);
  background: rgba(255, 255, 255, 0.03);
  color: #d1d5db;
  cursor: default;
  transition: background 0.12s ease;
}
.line:hover {
  background: rgba(255, 255, 255, 0.07);
}
.l-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding-bottom: 4px;
  margin-bottom: 4px;
  border-bottom: 1px dashed rgba(255, 255, 255, 0.08);
  font-size: 11.5px;
}
.l-ts {
  flex: none;
  width: 172px;
  color: #8b93a7;
  font-variant-numeric: tabular-nums;
}
.l-lv {
  flex: none;
  width: 46px;
  font-weight: 700;
  color: #93c5fd;
}
.l-app {
  /* 不撑开（grow:0）：否则会吃掉整行剩余空间、把后面的 IP 顶到最右端。
     仍允许收缩（shrink:1）+ 省略号，超长 logger 不会把 IP 挤出可视区。 */
  flex: 0 1 auto;
  min-width: 0;
  color: #a5b4fc;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 来源 IP：区分这条日志来自哪台机器（文件/命令采集 = 本机 IP，HTTP = 上报端自带）
   紧跟在 log_time / level / logger 之后显示（不要再 margin-left:auto 顶到行尾） */
.l-ip {
  flex: none;
  padding: 0 7px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 99px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 10.5px;
  line-height: 16px;
  color: #a3a9bb;
  white-space: nowrap;
}
.l-body {
  padding-left: 2px;
}
.l-msg {
  display: block;
  white-space: pre-wrap;
  word-break: break-word;
  color: #e5e7eb;
  font-size: 12.5px;
  line-height: 1.62;
  /* 单条日志再长也只占用有限高度：完整内容仍在（可内部滚动查看），
     避免一条几千行的堆栈把整个块撑成几千米高、拖爆布局 */
  max-height: 360px;
  overflow: auto;
  overscroll-behavior: contain;
}
/* 按级别着色：左边框 + 级别标签，一眼区分条目类型 */
.line.dg {
  border-left-color: #ef4444;
}
.line.dg .l-lv,
.line.dg .l-msg {
  color: #f87171;
}
.line.warn {
  border-left-color: #f59e0b;
}
.line.warn .l-lv {
  color: #fbbf24;
}
.line.pri {
  border-left-color: #3b82f6;
}
.line.pri .l-lv {
  color: #60a5fa;
}
.line.dbg {
  border-left-color: #8b5cf6;
}
.line.dbg .l-lv {
  color: #a78bfa;
}
.line.info {
  border-left-color: #6b7280;
}
.line.info .l-lv {
  color: #9ca3af;
}

/* 详情弹窗 */
.detail {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.d-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 18px;
}
.d-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.d-item label {
  flex: none;
  width: 68px;
  font-size: 12px;
  color: var(--text-3);
}
.d-item span {
  font-size: 13px;
  color: var(--text-1);
  word-break: break-all;
}
.d-msg-h {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.d-msg-h label {
  font-size: 12px;
  color: var(--text-3);
}
.d-pre {
  margin: 0;
  padding: 12px 14px;
  max-height: 320px;
  overflow: auto;
  background: #0f1115;
  color: #e5e7eb;
  border-radius: 10px;
  font-size: 12.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>

<!-- 非 scoped：el-dialog 会 teleport 到 body，scoped 属性拿不到它的根节点 -->
<style>
/* 详情弹窗要压在全屏控制台之上（全屏卡片 z-index: 1000） */
.log-detail-dialog {
  z-index: 3200 !important;
}
</style>
