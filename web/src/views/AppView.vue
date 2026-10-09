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
          v-model="filters.categoryId"
          placeholder="全部分类"
          clearable
          style="width: 160px"
          @change="reload"
        >
          <el-option label="全部分类" value="" />
          <el-option v-for="c in categories" :key="c.id" :value="String(c.id)" :label="c.name" />
        </el-select>

        <el-button type="primary" :loading="loading" @click="reload">查询</el-button>
        <el-button @click="reset">重置</el-button>

        <div class="spacer"></div>

        <el-button type="primary" @click="openCreate">
          <el-icon style="margin-right: 5px"><Plus /></el-icon>新建应用
        </el-button>
      </div>
    </div>

    <!-- 表格 -->
    <div class="card">
      <div class="card-h">
        <b>应用列表</b>
        <span class="sub muted small">共 {{ total }} 个</span>
      </div>

      <el-table :data="rows" v-loading="loading" style="width: 100%">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="名称" min-width="160">
          <template #default="{ row }">
            <b class="nm">{{ row.name }}</b>
            <div class="muted small">{{ row.remark || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="分类" width="130">
          <template #default="{ row }">
            <span class="chip info sq">{{ categoryName(row.category_id) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="96">
          <template #default="{ row }">
            <span class="chip sq" :class="row.source_type === 'CMD' ? 'info' : (row.source_type === 'FILE' ? 'warn' : 'ok')">
              {{ row.source_type || 'HTTP' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="Token" min-width="200">
          <template #default="{ row }">
            <span class="mono small tk">{{ row.token }}</span>
            <el-icon class="cp" title="复制" @click="copy(row.token)"><CopyDocument /></el-icon>
          </template>
        </el-table-column>
        <!-- 宽度给足 + 不换行，保证 "2026-10-09 12:34:56.123" 完整显示在一行 -->
        <el-table-column label="创建时间" width="196">
          <template #default="{ row }">
            <span class="mono num ts">{{ fmtTime(row.create_time) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="270" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openLogs(row)">查看日志</el-button>
            <el-button link type="primary" size="small" @click="openDevConfig(row)">开发配置</el-button>
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="doDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <span class="muted small">没有匹配的应用</span>
        </template>
      </el-table>

      <div class="foot">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="reload"
          @size-change="onSizeChange"
        />
      </div>
    </div>

    <!-- 新建 / 编辑 -->
    <el-dialog v-model="dialog" :title="editing ? '编辑应用' : '新建应用'" width="520">
      <el-form :model="form" label-width="92px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="例如：订单中心" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.categoryId" placeholder="未分类" clearable style="width: 100%">
            <el-option v-for="c in categories" :key="c.id" :value="c.id" :label="c.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源类型">
          <el-radio-group v-model="form.sourceType">
            <el-radio label="HTTP">HTTP 上报</el-radio>
            <el-radio label="FILE">文件采集</el-radio>
            <el-radio label="CMD">命令采集</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.sourceType === 'FILE'" label="文件路径">
          <el-input
            v-model="form.filePaths"
            type="textarea"
            :rows="3"
            placeholder="多行填写，一行一个路径"
          />
        </el-form-item>
        <el-form-item v-if="form.sourceType === 'CMD'" label="采集命令">
          <el-input
            v-model="form.cmd"
            type="textarea"
            :rows="3"
            placeholder="例如：journalctl -u game.service -f -o cat"
          />
          <div class="muted small" style="line-height: 1.6; margin-top: 4px">
            在 xtlog 所在机器上以 <code>/bin/sh -c</code>（Windows 用 <code>cmd /c</code>）执行，
            实时读取标准输出作为日志。适合采集 journald、<code>tail -F</code> 等非文件来源。
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
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { appApi } from '../api'
import { categories, categoryName, loadMeta } from '../store/meta'
import { fmtTime } from '../utils/format'

const router = useRouter()

const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const loading = ref(false)

const dialog = ref(false)
const editing = ref(null)
const saving = ref(false)

const filters = reactive({ keyword: '', categoryId: '' })
const form = reactive({
  name: '',
  categoryId: null,
  sourceType: 'HTTP',
  filePaths: '',
  cmd: '',
  remark: ''
})

async function reload() {
  loading.value = true
  try {
    const data = await appApi.list({
      keyword: filters.keyword || undefined,
      categoryId: filters.categoryId || undefined,
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
  filters.categoryId = ''
  page.value = 1
  reload()
}

function openCreate() {
  editing.value = null
  Object.assign(form, {
    name: '',
    categoryId: null,
    sourceType: 'HTTP',
    filePaths: '',
    cmd: '',
    remark: ''
  })
  dialog.value = true
}

function openEdit(row) {
  editing.value = row
  Object.assign(form, {
    name: row.name || '',
    categoryId: row.category_id || null,
    sourceType: row.source_type || 'HTTP',
    filePaths: row.file_paths || '',
    cmd: row.cmd || '',
    remark: row.remark || ''
  })
  dialog.value = true
}

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      categoryId: form.categoryId || 0,
      sourceType: form.sourceType,
      filePaths: form.filePaths,
      cmd: form.cmd,
      remark: form.remark
    }
    if (editing.value) {
      await appApi.update(editing.value.id, payload)
      ElMessage.success('已保存')
    } else {
      await appApi.create(payload)
      ElMessage.success('已创建')
    }
    dialog.value = false
    await Promise.all([reload(), loadMeta()])
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function doDelete(row) {
  try {
    await ElMessageBox.confirm(`删除应用「${row.name}」？该操作不可恢复。`, '确认删除', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await appApi.remove(row.id)
    ElMessage.success('已删除')
    if (rows.value.length === 1 && page.value > 1) page.value--
    await Promise.all([reload(), loadMeta()])
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

/** 日志页与开发配置页都是应用级页面，必须带 appId 进去（页面里不再做应用选择） */
function openLogs(row) {
  router.push({ path: '/logs', query: { appId: row.id } })
}

function openDevConfig(row) {
  router.push({ path: '/dev-config', query: { appId: row.id } })
}

async function copy(text) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('Token 已复制')
  } catch {
    ElMessage.warning('浏览器不允许复制，请手动选择')
  }
}

onMounted(async () => {
  if (!categories.value.length) {
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
.ts {
  font-size: 12px;
  color: var(--text-2);
  /* 时间戳不允许折行，宽度不够时由列宽兜住 */
  white-space: nowrap;
}
.num {
  font-variant-numeric: tabular-nums;
}
.tk {
  color: var(--text-2);
  word-break: break-all;
}
.cp {
  margin-left: 6px;
  cursor: pointer;
  color: var(--text-3);
  vertical-align: -2px;
}
.cp:hover {
  color: var(--accent);
}
.foot {
  display: flex;
  justify-content: flex-end;
  padding: 12px 18px;
  border-top: 1px solid var(--line-2);
}
</style>
