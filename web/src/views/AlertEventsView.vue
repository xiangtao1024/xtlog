<template>
  <div class="col">
    <!-- 筛选 -->
    <div class="card">
      <div class="card-b toolbar">
        <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 140px" @change="reload">
          <el-option label="全部状态" value="" />
          <el-option label="触发中" value="1" />
          <el-option label="已恢复" value="2" />
          <el-option label="已认领" value="3" />
        </el-select>

        <el-input
          v-model="filters.ruleId"
          placeholder="规则 ID"
          clearable
          style="width: 140px"
          @keyup.enter="reload"
          @clear="reload"
        />

        <el-button type="primary" :loading="loading" @click="reload">查询</el-button>
        <el-button @click="reset">重置</el-button>

        <div class="spacer"></div>

        <el-button @click="reload" title="每 30 秒自动刷新一次">
          <el-icon style="margin-right: 5px"><Refresh /></el-icon>刷新
        </el-button>
      </div>
    </div>

    <!-- 事件列表 -->
    <div class="card">
      <div class="card-h">
        <b>告警事件</b>
        <span class="sub muted small">共 {{ total }} 条</span>
      </div>

      <el-table :data="rows" v-loading="loading" style="width: 100%" row-key="id" :expand-row-keys="expanded" @expand-change="onExpand">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand">
              <div class="muted small">样本消息（最多前 2000 字）：</div>
              <pre class="sample">{{ row.sample_message || '—' }}</pre>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column label="规则 / 应用" min-width="200">
          <template #default="{ row }">
            <b class="nm">{{ row.rule_name || '—' }}</b>
            <div class="muted small">{{ row.app_name || '全部应用' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="级别" width="84">
          <template #default="{ row }">
            <span class="chip sq" :class="levelClass(row.level)">{{ row.level || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="摘要" min-width="280">
          <template #default="{ row }">
            <span class="small">{{ row.summary || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="命中" width="74" align="right">
          <template #default="{ row }">
            <span class="mono num cnt">{{ row.hit_count }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="chip sq" :class="statusClass(row.status)">{{ statusText(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="首次触发" width="180">
          <template #default="{ row }">
            <span class="mono num ts">{{ fmtTime(row.first_time) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最后更新" width="180">
          <template #default="{ row }">
            <span class="mono num ts">{{ fmtTime(row.last_time) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 1">
              <el-button link type="primary" size="small" @click="ack(row)">认领</el-button>
              <el-button link type="warning" size="small" @click="resolve(row)">标记恢复</el-button>
            </template>
            <span v-else class="muted small">
              {{ row.ack_user ? '认领人 ' + row.ack_user : '—' }}
            </span>
          </template>
        </el-table-column>
        <template #empty>
          <span class="muted small">没有匹配的事件</span>
        </template>
      </el-table>

      <div class="foot">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :page-sizes="[20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="reload"
          @size-change="onSizeChange"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { alertApi } from '../api'
import { user } from '../store/user'
import { fmtTime } from '../utils/format'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const expanded = ref([])

const filters = reactive({ status: '1', ruleId: '' })

let timer = null

async function reload() {
  loading.value = true
  try {
    const data = await alertApi.listEvents({
      status: filters.status || undefined,
      ruleId: filters.ruleId || undefined,
      page: page.value,
      pageSize: pageSize.value
    })
    rows.value = data?.list || []
    total.value = Number(data?.total || 0)
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function onSizeChange() {
  page.value = 1
  reload()
}

function reset() {
  filters.status = '1'
  filters.ruleId = ''
  page.value = 1
  reload()
}

function onExpand(row, expandedRows) {
  expanded.value = expandedRows.map((r) => String(r.id))
}

async function ack(row) {
  const ackUser = user.value?.username || user.value?.nickname || ''
  try {
    await ElMessageBox.confirm(`认领告警事件 #${row.id}？`, '提示', {
      confirmButtonText: '认领',
      cancelButtonText: '取消',
      type: 'info'
    })
  } catch {
    return
  }
  try {
    await alertApi.ackEvent(row.id, ackUser)
    ElMessage.success('已认领')
    await reload()
  } catch (e) {
    ElMessage.error(e.message || '认领失败')
  }
}

async function resolve(row) {
  try {
    await ElMessageBox.confirm(`把告警事件 #${row.id} 标记为已恢复？`, '提示', {
      confirmButtonText: '标记恢复',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await alertApi.resolveEvent(row.id)
    ElMessage.success('已标记为已恢复')
    await reload()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

function levelClass(lv) {
  if (!lv) return ''
  switch (String(lv).toUpperCase()) {
    case 'ERROR':
    case 'FATAL':
      return 'danger'
    case 'WARN':
      return 'warn'
    case 'INFO':
      return 'info'
    default:
      return ''
  }
}

function statusClass(s) {
  switch (Number(s)) {
    case 1:
      return 'danger'
    case 2:
      return 'ok'
    case 3:
      return 'info'
    default:
      return ''
  }
}

function statusText(s) {
  switch (Number(s)) {
    case 1:
      return '触发中'
    case 2:
      return '已恢复'
    case 3:
      return '已认领'
    default:
      return String(s)
  }
}

onMounted(() => {
  reload()
  // 事件流自动刷新：30s 一次，让「触发中」的事件状态实时滚动
  timer = setInterval(reload, 30000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
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
.nm {
  font-size: 13.5px;
  font-weight: 600;
}
.mono {
  font-family: var(--font-mono, monospace);
}
.ts {
  font-size: 12px;
  color: var(--text-2);
  white-space: nowrap;
}
.cnt {
  font-weight: 600;
  color: var(--text-1);
}
.num {
  font-variant-numeric: tabular-nums;
}
.expand {
  padding: 12px 18px;
  background: var(--surface-2);
}
.sample {
  margin: 6px 0 0;
  padding: 10px 12px;
  background: var(--surface, #fff);
  border: 1px solid var(--line-2);
  border-radius: 6px;
  font-family: var(--font-mono, monospace);
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 260px;
  overflow: auto;
}
.foot {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
  border-top: 1px solid var(--line-2);
}
</style>
