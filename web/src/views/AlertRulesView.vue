<template>
  <div class="col">
    <!-- 筛选 + 新建 -->
    <div class="card">
      <div class="card-b toolbar">
        <el-input
          v-model="filters.keyword"
          placeholder="关键词：名称 / 备注"
          clearable
          style="width: 230px"
          @keyup.enter="reload"
          @clear="reload"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>

        <el-select
          v-model="filters.enabled"
          placeholder="全部状态"
          clearable
          style="width: 130px"
          @change="reload"
        >
          <el-option label="全部状态" value="" />
          <el-option label="已启用" value="1" />
          <el-option label="已停用" value="0" />
        </el-select>

        <el-button type="primary" :loading="loading" @click="reload">查询</el-button>
        <el-button @click="reset">重置</el-button>

        <div class="spacer"></div>

        <el-button type="primary" @click="openCreate">
          <el-icon style="margin-right: 5px"><Plus /></el-icon>新建规则
        </el-button>
      </div>
    </div>

    <!-- 表格 -->
    <div class="card">
      <div class="card-h">
        <b>告警规则</b>
        <span class="sub muted small">共 {{ total }} 条</span>
      </div>

      <el-table :data="rows" v-loading="loading" style="width: 100%">
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column label="名称" min-width="160">
          <template #default="{ row }">
            <b class="nm">{{ row.name }}</b>
            <div class="muted small">{{ row.remark || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="应用" width="140">
          <template #default="{ row }">
            <span class="chip info sq">{{ row.app_name || '全部应用' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <span class="chip sq" :class="typeClass(row.rule_type)">{{ row.rule_type }}</span>
          </template>
        </el-table-column>
        <el-table-column label="表达式" min-width="180">
          <template #default="{ row }">
            <span class="mono small expr">{{ row.rule_expr || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="窗口" width="84" align="right">
          <template #default="{ row }">{{ row.window_sec }}s</template>
        </el-table-column>
        <el-table-column label="阈值" width="70" align="right">
          <template #default="{ row }">{{ row.threshold }}</template>
        </el-table-column>
        <el-table-column label="静默" width="84" align="right">
          <template #default="{ row }">{{ row.silence_sec }}s</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled === 1"
              @change="(v) => toggle(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :loading="row._testing" @click="test(row)">测试</el-button>
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="doDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <span class="muted small">还没有告警规则，点右上角「新建规则」开始配置</span>
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

    <!-- 新建 / 编辑 -->
    <el-dialog v-model="dialog" :title="editing ? '编辑规则' : '新建规则'" width="560">
      <el-form :model="form" label-width="96px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="例如：订单服务异常告警" />
        </el-form-item>
        <el-form-item label="作用应用">
          <el-select v-model="form.appId" placeholder="全部应用" clearable style="width: 100%">
            <el-option label="全部应用" :value="0" />
            <el-option v-for="a in apps" :key="a.id" :value="a.id" :label="a.name" />
          </el-select>
          <div class="muted small" style="line-height: 1.6; margin-top: 4px">
            选「全部应用」时跨所有应用聚合统计；选具体应用则只评估该应用的日志。
          </div>
        </el-form-item>
        <el-form-item label="规则类型">
          <el-radio-group v-model="form.ruleType">
            <el-radio label="LEVEL">级别</el-radio>
            <el-radio label="KEYWORD">关键词</el-radio>
            <el-radio label="REGEX">正则</el-radio>
            <el-radio label="RATE">速率</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="表达式" required>
          <el-input
            v-model="form.ruleExpr"
            :placeholder="exprHint"
            type="textarea"
            :rows="2"
          />
          <div class="muted small" style="line-height: 1.6; margin-top: 4px" v-html="exprTip"></div>
        </el-form-item>
        <el-form-item label="评估窗口">
          <el-input-number v-model="form.windowSec" :min="10" :max="86400" :step="60" />
          <span class="muted small" style="margin-left: 8px">秒（默认 300）</span>
        </el-form-item>
        <el-form-item label="触发阈值">
          <el-input-number v-model="form.threshold" :min="1" :max="100000" :step="1" />
          <span class="muted small" style="margin-left: 8px">窗口内命中数 ≥ 该值即触发</span>
        </el-form-item>
        <el-form-item label="静默时长">
          <el-input-number v-model="form.silenceSec" :min="0" :max="86400" :step="60" />
          <span class="muted small" style="margin-left: 8px">秒（默认 600）触发后在该时长内不重复告警</span>
        </el-form-item>
        <el-form-item label="Webhook">
          <el-input v-model="form.webhookUrl" placeholder="https://hooks.example.com/alert（留空则只入库不通知）" />
          <div class="muted small" style="line-height: 1.6; margin-top: 4px">
            触发时以 POST JSON 通知该地址，payload 含 event_id / rule_name / hit_count / sample_message 等。
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">
          {{ editing ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { alertApi } from '../api'
import { apps, loadMeta } from '../store/meta'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(false)

const dialog = ref(false)
const editing = ref(null)
const saving = ref(false)

const filters = reactive({ keyword: '', enabled: '' })
const form = reactive({
  name: '',
  appId: 0,
  ruleType: 'LEVEL',
  ruleExpr: '',
  windowSec: 300,
  threshold: 1,
  silenceSec: 600,
  webhookUrl: '',
  remark: ''
})

const exprHint = computed(() => {
  switch (form.ruleType) {
    case 'LEVEL': return 'ERROR 或 ERROR,WARN（多个用逗号分隔）'
    case 'KEYWORD': return '文本关键词，如 超时'
    case 'REGEX': return 'Java 正则，如 \\bTimeoutException\\b'
    case 'RATE': return '级别词，如 ERROR（配合短窗口 + 高阈值表达速率）'
    default: return ''
  }
})

const exprTip = computed(() => {
  switch (form.ruleType) {
    case 'LEVEL': return '匹配日志级别词（ERROR / WARN / INFO / DEBUG / TRACE），多级逗号分隔。'
    case 'KEYWORD': return '对 message 做 LIKE 模糊匹配，不区分大小写由数据库决定。'
    case 'REGEX': return 'Java 正则；为兼容各库，先按时间窗拉候选行（最多 1000 条），再用 Pattern 过滤。'
    case 'RATE': return '与「级别」同语义，靠短窗口（如 60s）+ 高阈值表达「N 次/分钟」。'
    default: return ''
  }
})

function typeClass(t) {
  switch (t) {
    case 'LEVEL': return 'danger'
    case 'KEYWORD': return 'warn'
    case 'REGEX': return 'info'
    case 'RATE': return 'ok'
    default: return ''
  }
}

async function reload() {
  loading.value = true
  try {
    const data = await alertApi.listRules({
      keyword: filters.keyword || undefined,
      enabled: filters.enabled || undefined,
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
  filters.keyword = ''
  filters.enabled = ''
  page.value = 1
  reload()
}

function openCreate() {
  editing.value = null
  Object.assign(form, {
    name: '',
    appId: 0,
    ruleType: 'LEVEL',
    ruleExpr: '',
    windowSec: 300,
    threshold: 1,
    silenceSec: 600,
    webhookUrl: '',
    remark: ''
  })
  dialog.value = true
}

function openEdit(row) {
  editing.value = row
  Object.assign(form, {
    name: row.name || '',
    appId: Number(row.app_id || 0),
    ruleType: row.rule_type || 'LEVEL',
    ruleExpr: row.rule_expr || '',
    windowSec: Number(row.window_sec || 300),
    threshold: Number(row.threshold || 1),
    silenceSec: Number(row.silence_sec || 600),
    webhookUrl: extractWebhook(row.channels || ''),
    remark: row.remark || ''
  })
  dialog.value = true
}

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  if (!form.ruleExpr.trim()) {
    ElMessage.warning('请填写表达式')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      appId: form.appId || 0,
      ruleType: form.ruleType,
      ruleExpr: form.ruleExpr.trim(),
      windowSec: form.windowSec,
      threshold: form.threshold,
      silenceSec: form.silenceSec,
      channels: form.webhookUrl ? JSON.stringify({ webhook: form.webhookUrl }) : '',
      remark: form.remark
    }
    if (editing.value) {
      await alertApi.updateRule(editing.value.id, payload)
      ElMessage.success('已保存')
    } else {
      await alertApi.createRule(payload)
      ElMessage.success('已创建')
    }
    dialog.value = false
    await reload()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggle(row, enabled) {
  try {
    await alertApi.toggleRule(row.id, enabled)
    row.enabled = enabled ? 1 : 0
    ElMessage.success(enabled ? '已启用' : '已停用')
  } catch (e) {
    ElMessage.error(e.message || '切换失败')
  }
}

async function test(row) {
  row._testing = true
  try {
    const r = await alertApi.testRule(row.id)
    const hit = Number(r?.hitCount || 0)
    const threshold = Number(r?.threshold || row.threshold || 1)
    const sample = r?.sample || ''
    const willFire = hit >= threshold ? '<b style="color:var(--danger)">会触发</b>' : '不会触发'
    const sampleHtml = sample
      ? `<div class="muted small" style="margin-top:8px;max-height:200px;overflow:auto;background:var(--surface-2);padding:8px;border-radius:6px;white-space:pre-wrap;word-break:break-all;font-family:var(--font-mono,monospace)">${escapeHtml(sample)}</div>`
      : ''
    await ElMessageBox.alert(
      `<div>最近一个评估窗口内命中 <b>${hit}</b> 条（阈值 ${threshold}）→ ${willFire}</div>${sampleHtml}`,
      `测试：${row.name}`,
      { dangerouslyUseHTMLString: true, confirmButtonText: '知道了' }
    )
  } catch (e) {
    ElMessage.error(e.message || '测试失败')
  } finally {
    row._testing = false
  }
}

async function doDelete(row) {
  try {
    await ElMessageBox.confirm(`删除规则「${row.name}」？该操作不可恢复。`, '确认删除', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await alertApi.removeRule(row.id)
    ElMessage.success('已删除')
    if (rows.value.length === 1 && page.value > 1) page.value--
    await reload()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

function extractWebhook(channels) {
  if (!channels) return ''
  const m = channels.match(/"webhook"\s*:\s*"([^"]+)"/i)
  return m ? m[1] : ''
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

onMounted(async () => {
  if (!apps.value.length) {
    await loadMeta().catch(() => {})
  }
  await reload()
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
.expr {
  color: var(--text-2);
  word-break: break-all;
}
.foot {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
  border-top: 1px solid var(--line-2);
}
</style>
