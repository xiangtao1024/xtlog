<template>
  <div class="card">
    <div class="card-h">
      <b>分类列表</b>
      <span class="sub">共 {{ rows.length }} 个</span>
      <div class="spacer"></div>
      <el-button size="small" @click="load"><el-icon><Refresh /></el-icon>刷新</el-button>
      <el-button size="small" type="primary" @click="openCreate"><el-icon><Plus /></el-icon>新建分类</el-button>
    </div>

    <el-table :data="rows" v-loading="loading" style="width: 100%">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="名称" min-width="180">
        <template #default="{ row }">
          <span class="chip pri sq">{{ row.name }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="220">
        <template #default="{ row }">
          <span class="muted">{{ row.remark || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="所属应用" width="110">
        <template #default="{ row }">
          <span class="num">{{ appCount(row.id) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" align="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <div class="empty">
          <el-icon><Collection /></el-icon>
          还没有分类，先建一个把应用归拢起来
        </div>
      </template>
    </el-table>
  </div>

  <el-dialog v-model="dialog" :title="editing ? '编辑分类' : '新建分类'" width="440px">
    <el-form :model="form" label-position="top">
      <el-form-item label="分类名称" required>
        <el-input v-model="form.name" placeholder="例如：订单中心 / 支付网关" maxlength="100" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" show-word-limit />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { categoryApi } from '../api'
import { categories, apps, loadCategories } from '../store/meta'

const rows = computed(() => categories.value)
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const editing = ref(null)
const form = ref({ name: '', remark: '' })

const appCount = (id) => apps.value.filter((a) => Number(a.category_id) === Number(id)).length

async function load() {
  loading.value = true
  try {
    await loadCategories()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = { name: '', remark: '' }
  dialog.value = true
}

function openEdit(row) {
  editing.value = row
  form.value = { name: row.name, remark: row.remark || '' }
  dialog.value = true
}

async function submit() {
  if (!form.value.name || !form.value.name.trim()) {
    ElMessage.warning('请填写分类名称')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await categoryApi.update(editing.value.id, { ...form.value })
    } else {
      await categoryApi.create({ ...form.value })
    }
    ElMessage.success('已保存')
    dialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确定删除分类「${row.name}」？该操作不可撤销。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await categoryApi.remove(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(load)
</script>

<style scoped>
.spacer {
  flex: 1;
}
.muted {
  color: var(--text-3);
}
</style>
