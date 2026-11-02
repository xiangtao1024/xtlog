<template>
  <div class="col ana">
    <!-- 筛选条 -->
    <div class="card">
      <div class="card-b toolbar">
        <el-select
          v-model="appId"
          filterable
          placeholder="选择应用"
          style="width: 230px"
          @change="reload"
        >
          <el-option label="全部应用" value="all" />
          <el-option v-for="a in apps" :key="a.id" :label="a.name" :value="String(a.id)" />
        </el-select>

        <el-radio-group v-model="quick" size="small" @change="applyQuick">
          <el-radio-button value="24h">近 24 小时</el-radio-button>
          <el-radio-button value="today">今天</el-radio-button>
          <el-radio-button value="7d">近 7 天</el-radio-button>
          <el-radio-button value="30d">近 30 天</el-radio-button>
        </el-radio-group>

        <el-date-picker
          v-model="range"
          type="datetimerange"
          range-separator="→"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          :default-time="defaultTime"
          style="width: 350px"
          @change="onRangeChange"
        />

        <el-select
          v-model="levels"
          multiple
          collapse-tags
          collapse-tags-tooltip
          clearable
          placeholder="全部级别"
          style="width: 190px"
          @change="reload"
        >
          <el-option v-for="l in LEVEL_OPTIONS" :key="l" :value="l" :label="l" />
        </el-select>

        <el-input
          v-model="keyword"
          placeholder="内容关键字"
          clearable
          style="width: 200px"
          @keyup.enter="reload"
          @clear="reload"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>

        <el-button type="primary" :loading="loading" @click="reload">分析</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
    </div>

    <template v-if="!appId">
      <div class="card">
        <el-empty description="请先选择要分析的应用" />
      </div>
    </template>

    <template v-else>
      <!-- 概览指标 -->
      <div class="metrics">
        <div class="metric">
          <label>日志总数</label>
          <div class="metric-v">{{ fmtNum(ov.total) }}</div>
          <span class="muted small">{{ ov.appName || '-' }}</span>
        </div>
        <div class="metric">
          <label>错误（ERROR）</label>
          <div class="metric-v err">{{ fmtNum(ov.errorCount) }}</div>
          <span class="muted small">警告 {{ fmtNum(ov.warnCount) }}</span>
        </div>
        <div class="metric">
          <label>错误率</label>
          <div class="metric-v" :class="rateClass">{{ pct(ov.errorRate) }}</div>
          <span class="muted small">ERROR / 总数</span>
        </div>
        <div class="metric">
          <label>来源 IP</label>
          <div class="metric-v">{{ fmtNum(ov.ipCount) }}</div>
          <span class="muted small">logger {{ fmtNum(ov.loggerCount) }} 个</span>
        </div>
        <div class="metric">
          <label>时间跨度</label>
          <div class="metric-v sm">{{ spanText }}</div>
          <span class="muted small">扫描 {{ ov.scannedTables || 0 }} 张月表</span>
        </div>
      </div>

      <!-- 趋势 + 级别分布 -->
      <div class="grid2">
        <div class="card">
          <div class="card-h">
            <b>时间趋势</b>
            <span class="sub">{{ bucket === 'day' ? '按天' : '按小时' }}聚合</span>
          </div>
          <div class="card-b">
            <div v-loading="loading" class="bars">
              <div v-for="d in trend" :key="d.label" class="bar-wrap">
                <div class="bar-box">
                  <div class="bar-stack" :style="{ height: barHeight(d.total) }">
                    <span class="seg err" :style="{ height: segPct(d.error, d.total) }"></span>
                    <span class="seg warn" :style="{ height: segPct(d.warn, d.total) }"></span>
                    <span class="seg ok"></span>
                  </div>
                </div>
                <div class="bar-v">{{ d.total }}</div>
                <div class="bar-x muted small">{{ shortLabel(d.label) }}</div>
              </div>
              <div v-if="!loading && !trend.length" class="muted small">该时间范围内没有日志</div>
            </div>
            <div class="legend">
              <span><i class="sw err"></i>ERROR</span>
              <span><i class="sw warn"></i>WARN</span>
              <span><i class="sw ok"></i>其它</span>
            </div>
          </div>
        </div>

        <div class="card">
          <div class="card-h"><b>级别分布</b></div>
          <div class="card-b">
            <div v-loading="loading" class="lv-list">
              <div v-for="l in ov.levelStats" :key="l.level" class="lv-item">
                <div class="lv-head">
                  <span class="mono">{{ l.level }}</span>
                  <span class="muted small">{{ fmtNum(l.count) }}</span>
                </div>
                <div class="lv-bar">
                  <div :class="'lv-fill ' + levelClass(l.level)" :style="{ width: levelPct(l.count) }"></div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Top 维度 -->
      <div class="card">
        <div class="card-h">
          <b>热点排名</b>
          <span class="sub">按出现次数排序，点击行可下钻到日志</span>
          <el-radio-group v-model="dim" size="small" class="head-btn" @change="loadDim">
            <el-radio-button value="logger">logger</el-radio-button>
            <el-radio-button value="ip">来源 IP</el-radio-button>
            <el-radio-button value="thread">线程</el-radio-button>
          </el-radio-group>
        </div>
        <div class="card-b">
          <div v-loading="dimLoading" class="rank">
            <div
              v-for="(it, i) in dimItems"
              :key="it.key"
              class="rank-row"
              @click="drill(it.key)"
            >
              <span class="rank-no">{{ i + 1 }}</span>
              <div class="rank-main">
                <div class="rank-top">
                  <span class="mono break ellipsis">{{ clean(it.key) }}</span>
                  <span class="muted small">{{ fmtNum(it.count) }}</span>
                </div>
                <div class="rank-bar">
                  <div class="rank-fill" :style="{ width: rankPct(it.count) }"></div>
                  <div v-if="it.error" class="rank-err" :style="{ width: rankPct(it.error) }"></div>
                </div>
              </div>
              <div v-if="!dimLoading && !dimItems.length" class="muted small">没有数据</div>
            </div>
          </div>
        </div>
      </div>

      <!-- 错误聚类 -->
      <div class="card">
        <div class="card-h">
          <b>错误聚类</b>
          <span class="sub">抹掉数字 / 时间 / ID 后聚合，同类错误归到一起</span>
          <el-button size="small" class="head-btn" :loading="patLoading" @click="loadPatterns">
            刷新
          </el-button>
        </div>
        <div class="card-b">
          <div v-if="patSummary" class="muted small mb">
            已扫描最近 {{ fmtNum(patSummary.scanned) }} 条 ERROR / WARN，范围内共
            {{ fmtNum(patSummary.totalErrorWarn) }} 条，聚出 {{ patterns.length }} 类
          </div>
          <el-table
            v-if="patterns.length"
            :data="patterns"
            size="small"
            style="width: 100%"
            row-key="template"
          >
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="pat-detail">
                  <div class="pat-line">
                    <span class="muted small">来源 logger</span>
                    <span class="mono break">{{ clean((row.loggers || []).join('、')) || '-' }}</span>
                  </div>
                  <div class="pat-line">
                    <span class="muted small">来源 IP</span>
                    <span class="mono break">{{ (row.ips || []).join('、') || '-' }}</span>
                  </div>
                  <div class="pat-line">
                    <span class="muted small">首次 / 末次</span>
                    <span class="mono">{{ fmtDateTime(row.firstTime) }} → {{ fmtDateTime(row.lastTime) }}</span>
                  </div>
                  <div class="pat-msg">
                    <div class="muted small mb">最近一次原文</div>
                    <pre class="mono pre">{{ row.sample?.message }}</pre>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="次数" width="86" align="right">
              <template #default="{ row }">
                <b class="mono">{{ fmtNum(row.count) }}</b>
              </template>
            </el-table-column>
            <el-table-column label="级别" width="96">
              <template #default="{ row }">
                <span class="chip" :class="levelClass(row.errorCount ? 'ERROR' : 'WARN')">
                  <span class="dot" :class="levelClass(row.errorCount ? 'ERROR' : 'WARN')"></span>
                  {{ row.errorCount ? 'ERROR' : 'WARN' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="错误模板" min-width="320">
              <template #default="{ row }">
                <span class="mono break">{{ row.template }}</span>
              </template>
            </el-table-column>
            <el-table-column label="末次出现" width="150">
              <template #default="{ row }">
                <span class="small">{{ fmtDateTime(row.lastTime) }}</span>
            </template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="drillPattern(row)">查看</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-else-if="!patLoading" class="empty">
            <el-icon class="empty-ico"><DataAnalysis /></el-icon>
            <div>该范围内没有 ERROR / WARN 日志</div>
            <span class="muted small">换个时间范围或应用试试</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { analysisApi } from '../api'
import { apps, loadApps } from '../store/meta'
import { LEVEL_OPTIONS, levelClass } from '../utils/format'

const router = useRouter()

const appId = ref('all')
const quick = ref('24h')
// 绑定 Date 对象而不是时间戳字符串：Element Plus 对 value-format="x"
// 回填的毫秒字符串会按日期文本解析（"1791938929000" → 1798-11-29），
// 显示成 1798 年。Date 对象是组件原生支持的形态，无此问题。
const range = ref(null)
const levels = ref([])
const keyword = ref('')
const loading = ref(false)

// 选日期时的默认时分秒（开始取 00:00:00，结束取 23:59:59）
const defaultTime = [new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]

const ov = ref({ total: 0, errorCount: 0, warnCount: 0, errorRate: 0, ipCount: 0, loggerCount: 0, levelStats: [] })
const trend = ref([])
const bucket = ref('hour')

const dim = ref('logger')
const dimItems = ref([])
const dimLoading = ref(false)

const patterns = ref([])
const patLoading = ref(false)
const patSummary = ref(null)

function startOfDay(ts) {
  const d = new Date(ts)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

function applyQuick() {
  const now = Date.now()
  if (quick.value === 'today') {
    range.value = [new Date(startOfDay(now)), new Date(now)]
  } else {
    const ms = quick.value === '7d' ? 7 * 86400000 : quick.value === '30d' ? 30 * 86400000 : 86400000
    range.value = [new Date(now - ms), new Date(now)]
  }
  reload()
}

function onRangeChange() {
  quick.value = ''
  reload()
}

function query() {
  const r = range.value
  const from = r && r.length === 2 && r[0] ? new Date(r[0]).getTime() : Date.now() - 86400000
  const to = r && r.length === 2 && r[1] ? new Date(r[1]).getTime() : Date.now()
  return {
    appId: appId.value,
    from: String(from),
    to: String(to),
    levels: levels.value && levels.value.length ? levels.value.join(',') : undefined,
    keyword: keyword.value || undefined
  }
}

async function reload() {
  if (!appId.value) return
  loading.value = true
  const q = query()
  // 范围超过 3 天自动按天聚合，避免小时桶过多
  const spanDays = (Number(q.to) - Number(q.from)) / 86400000
  bucket.value = spanDays > 3 ? 'day' : 'hour'
  try {
    const [o, t] = await Promise.all([
      analysisApi.overview(q),
      analysisApi.trend({ ...q, bucket: bucket.value })
    ])
    ov.value = o || ov.value
    trend.value = t?.trend || []
  } catch (e) {
    ElMessage.error(e.message || '分析失败')
  } finally {
    loading.value = false
  }
  loadDim()
  loadPatterns()
}

async function loadDim() {
  dimLoading.value = true
  try {
    const d = await analysisApi.breakdown({ ...query(), dim: dim.value, limit: 10 })
    dimItems.value = d?.items || []
  } catch (e) {
    ElMessage.error(e.message || '加载排名失败')
  } finally {
    dimLoading.value = false
  }
}

async function loadPatterns() {
  patLoading.value = true
  try {
    const d = await analysisApi.patterns({ ...query(), limit: 10 })
    patterns.value = d?.items || []
    patSummary.value = { scanned: d?.scanned || 0, totalErrorWarn: d?.totalErrorWarn || 0 }
  } catch (e) {
    ElMessage.error(e.message || '加载聚类失败')
  } finally {
    patLoading.value = false
  }
}

/** 从归一化模板里挑一段有辨识度的关键词，用于下钻到日志检索 */
function keywordOf(row) {
  const parts = String(row.template || '')
    .split(/\s+/)
    .filter((t) => t && t.length > 2 && !/^[#<>]+$/.test(t) && !/^<.*>$/.test(t))
  return parts.slice(0, 5).join(' ')
}

function drill(key) {
  router.push({
    path: '/logs',
    query: {
      appId: appId.value, // 'all' 也带上，日志页支持全部应用模式
      keyword: String(key).replace(/^\(|\)$/g, '') || undefined,
      startTime: String(new Date(range.value[0]).getTime()),
      endTime: String(new Date(range.value[1]).getTime())
    }
  })
}

function drillPattern(row) {
  const kw = keywordOf(row)
  router.push({
    path: '/logs',
    query: {
      appId: appId.value,
      keyword: kw || undefined,
      level: row.errorCount ? 'ERROR' : 'WARN',
      startTime: String(new Date(range.value[0]).getTime()),
      endTime: String(new Date(range.value[1]).getTime())
    }
  })
}

function reset() {
  appId.value = 'all'
  quick.value = '24h'
  levels.value = []
  keyword.value = ''
  applyQuick()
}

/** 绘图区固定高度，柱高按最大值折算成像素。
 *  不能用百分比：.bar-box 的高度来自 flex 撑开，百分比在这种父级上会解析成 0，
 *  柱子渲染了但高度 0px，看起来就是「图没出来」。 */
const PLOT_H = 108

function barHeight(c) {
  const max = trend.value.reduce((m, d) => Math.max(m, d.total || 0), 0)
  if (!max) return '0px'
  return Math.max(2, Math.round(((c || 0) / max) * PLOT_H)) + 'px'
}

function segPct(part, total) {
  if (!total) return '0%'
  return (part / total) * 100 + '%'
}

function shortLabel(l) {
  const s = String(l || '')
  return s.length > 10 ? s.slice(11) : s.slice(5)
}

function levelPct(c) {
  const max = (ov.value.levelStats || []).reduce((m, d) => Math.max(m, d.count || 0), 0)
  if (!max) return '0%'
  return Math.round(((c || 0) / max) * 100) + '%'
}

function rankPct(c) {
  const max = dimItems.value.reduce((m, d) => Math.max(m, d.count || 0), 0)
  if (!max) return '0%'
  return Math.round(((c || 0) / max) * 100) + '%'
}

const rateClass = computed(() => {
  const r = Number(ov.value.errorRate || 0)
  if (r >= 0.1) return 'err'
  if (r >= 0.03) return 'warn'
  return 'ok'
})

const spanText = computed(() => {
  if (!ov.value.firstTime || !ov.value.lastTime) return '-'
  const ms = Math.max(0, ov.value.lastTime - ov.value.firstTime)
  const h = Math.floor(ms / 3600000)
  if (h < 24) return h + ' 小时'
  return Math.floor(h / 24) + ' 天 ' + (h % 24) + ' 小时'
})

function pad(n) {
  return n < 10 ? '0' + n : '' + n
}

function fmtDateTime(ts) {
  if (!ts) return '-'
  const d = new Date(Number(ts))
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes()
  )}:${pad(d.getSeconds())}`
}

const fmtNum = (n) => Number(n || 0).toLocaleString()

/** 采集上来的 logger 常带 ANSI 颜色码（Spring Boot 彩色输出），显示前剥掉 */
function clean(s) {
  if (s === null || s === undefined) return ''
  return String(s).replace(/\u001b\[[0-9;]*m/g, '')
}

/** 0.0321 -> "3.21%"；错误率通常很小，保留两位更可读 */
function pct(r) {
  const v = Number(r || 0) * 100
  if (!isFinite(v)) return '0%'
  return (v < 10 ? v.toFixed(2) : v.toFixed(1)) + '%'
}

onMounted(async () => {
  if (!apps.value.length) {
    try {
      await loadApps()
    } catch (e) {
      ElMessage.error(e.message || '加载应用列表失败')
    }
  }
  applyQuick()
})
</script>

<style scoped>
.ana {
  max-width: 1320px;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(158px, 1fr));
  gap: 12px;
}
.metric {
  background: var(--surface-2);
  border-radius: 8px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.metric > label {
  font-size: 11.5px;
  color: var(--text-3);
}
.metric-v {
  font-size: 22px;
  font-weight: 500;
  line-height: 1.25;
}
.metric-v.sm {
  font-size: 15px;
  padding-top: 5px;
}
.metric-v.err {
  color: #d9483b;
}
.metric-v.warn {
  color: #c98218;
}
.metric-v.ok {
  color: #35a86a;
}

.grid2 {
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}
@media (max-width: 1180px) {
  .grid2 {
    grid-template-columns: minmax(0, 1fr);
  }
}

.bars {
  display: flex;
  /* 必须 stretch（默认值）：bar-wrap 要撑满 148px，bar-box 才有确定高度可分。
     之前写的 align-items: flex-end 让每根柱子容器塌成内容高度（≈0），
     柱子全都渲染了但高度为 0，肉眼看就是空白。 */
  gap: 3px;
  height: 148px;
  min-height: 148px;
}
.bar-wrap {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
}
.bar-box {
  width: 100%;
  flex: 1;
  display: flex;
  align-items: flex-end;
}
.bar-stack {
  width: 100%;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  border-radius: 3px 3px 0 0;
  overflow: hidden;
  min-height: 2px;
}
.seg {
  display: block;
  width: 100%;
}
.seg.err {
  background: #d9483b;
}
.seg.warn {
  background: #e0a13c;
}
.seg.ok {
  flex: 1;
  background: #b9c4d4;
}
.bar-v {
  font-size: 10.5px;
  color: var(--text-2);
  height: 13px;
  line-height: 13px;
}
.bar-x {
  height: 14px;
  line-height: 14px;
  white-space: nowrap;
  overflow: hidden;
  font-size: 10.5px;
}
.legend {
  display: flex;
  gap: 14px;
  font-size: 11.5px;
  color: var(--text-2);
}
.legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.sw {
  width: 9px;
  height: 9px;
  border-radius: 2px;
  display: inline-block;
}
.sw.err {
  background: #d9483b;
}
.sw.warn {
  background: #e0a13c;
}
.sw.ok {
  background: #b9c4d4;
}

.lv-list {
  display: flex;
  flex-direction: column;
  gap: 11px;
}
.lv-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  font-size: 12.5px;
}
.lv-bar {
  height: 7px;
  border-radius: 4px;
  background: var(--line-2);
  overflow: hidden;
  margin-top: 4px;
}
.lv-fill {
  height: 100%;
  border-radius: 4px;
  background: var(--accent, #3f7fd0);
}
.lv-fill.dg {
  background: #d9483b;
}
.lv-fill.warn {
  background: #e0a13c;
}
.lv-fill.pri {
  background: #3f7fd0;
}
.lv-fill.dbg {
  background: #8a93a3;
}
.lv-fill.info {
  background: #6a8fb5;
}

.rank {
  display: flex;
  flex-direction: column;
}
.rank-row {
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr);
  gap: 10px;
  padding: 9px 2px;
  border-bottom: 1px solid var(--line-2);
  cursor: pointer;
}
.rank-row:last-child {
  border-bottom: none;
}
.rank-row:hover {
  background: var(--surface-2);
}
.rank-no {
  font-size: 12px;
  color: var(--text-3);
  padding-top: 1px;
}
.rank-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  font-size: 12.5px;
}
.rank-bar {
  position: relative;
  height: 6px;
  border-radius: 3px;
  background: var(--line-2);
  overflow: hidden;
  margin-top: 5px;
}
.rank-fill {
  position: absolute;
  inset: 0 auto 0 0;
  background: #b9c4d4;
  border-radius: 3px;
}
.rank-err {
  position: absolute;
  inset: 0 auto 0 0;
  background: #d9483b;
  border-radius: 3px;
}

.pat-detail {
  padding: 6px 10px 10px;
}
.pat-line {
  display: flex;
  gap: 12px;
  font-size: 12px;
  padding: 3px 0;
}
.pat-line > span:first-child {
  width: 92px;
  flex-shrink: 0;
  color: var(--text-3);
}
.pat-msg {
  margin-top: 8px;
}
.pre {
  margin: 0;
  padding: 9px 11px;
  background: var(--surface-2);
  border: 1px solid var(--line-2);
  border-radius: 6px;
  font-size: 11.5px;
  line-height: 1.55;
  max-height: 260px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.mb {
  margin-bottom: 8px;
}
.muted {
  color: var(--text-3);
}
.small {
  font-size: 12px;
}
.mono {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace);
}
.break {
  word-break: break-all;
}
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.head-btn {
  margin-left: auto;
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 30px 12px;
  text-align: center;
  font-size: 13px;
  color: var(--text-2);
  border: 1px dashed var(--line);
  border-radius: 8px;
  background: var(--surface-2);
}
.empty-ico {
  font-size: 24px;
  color: var(--text-3);
  margin-bottom: 4px;
}
</style>
