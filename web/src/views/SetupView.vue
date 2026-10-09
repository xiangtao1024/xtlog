<template>
  <div class="col">
    <div class="card">
      <div class="card-h">
        <b>数据源</b>
        <span class="sub">切换后热生效，无需重启服务</span>
      </div>
      <div class="card-b">
        <el-form :model="form" label-position="top">
          <el-form-item label="数据库类型">
            <el-radio-group v-model="form.dbType" @change="onDbTypeChange">
              <el-radio-button value="sqlite">SQLite</el-radio-button>
              <el-radio-button value="mysql">MySQL</el-radio-button>
              <el-radio-button value="postgresql">PostgreSQL</el-radio-button>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="JDBC URL">
            <el-input v-model="form.url" class="mono" placeholder="jdbc:sqlite:E:/data/xtlog.db" />
            <div class="hint">
              SQLite 会自动追加 <span class="mono">journal_mode=WAL&amp;synchronous=NORMAL&amp;busy_timeout=5000</span>
            </div>
          </el-form-item>

          <div class="grid2">
            <el-form-item label="用户名">
              <el-input v-model="form.user" :disabled="isSqlite" placeholder="仅 MySQL / PostgreSQL 需要" />
            </el-form-item>
            <el-form-item label="密码">
              <el-input
                v-model="form.password"
                type="password"
                show-password
                :disabled="isSqlite"
                placeholder="仅 MySQL / PostgreSQL 需要"
              />
            </el-form-item>
          </div>

          <el-button type="primary" :loading="saving" @click="save">保存并切换</el-button>
          <el-button @click="load">重新读取</el-button>
        </el-form>
      </div>
    </div>

    <div class="card">
      <div class="card-h"><b>当前状态</b></div>
      <div class="card-b">
        <div class="kv">
          <span>初始化状态</span>
          <span class="chip" :class="status?.initialized ? 'ok' : 'warn'">
            <span class="dot" :class="status?.initialized ? 'ok' : 'warn'"></span>
            {{ status?.initialized ? '已就绪' : '未初始化' }}
          </span>
        </div>
        <div class="kv">
          <span>数据库类型</span>
          <b class="mono">{{ status?.dbType || '-' }}</b>
        </div>
        <div class="kv">
          <span>存储位置</span>
          <b class="mono break">{{ status?.path || '-' }}</b>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { setupApi } from '../api'

const status = ref(null)
const saving = ref(false)
const form = ref({ dbType: 'sqlite', url: '', user: '', password: '' })

const isSqlite = computed(() => form.value.dbType === 'sqlite')

async function load() {
  try {
    status.value = await setupApi.status()
    form.value.dbType = status.value?.dbType || 'sqlite'
    form.value.url = status.value?.path ? 'jdbc:sqlite:' + status.value.path : ''
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function onDbTypeChange(v) {
  if (v === 'sqlite') {
    form.value.url = 'jdbc:sqlite:E:/workspace/xt/xtlog-server/data/xtlog.db'
  } else if (v === 'mysql') {
    form.value.url = 'jdbc:mysql://127.0.0.1:3306/xtlog?useUnicode=true&characterEncoding=utf8'
  } else {
    form.value.url = 'jdbc:postgresql://127.0.0.1:5432/xtlog'
  }
}

async function save() {
  if (!form.value.url) {
    ElMessage.warning('请填写 JDBC URL')
    return
  }
  saving.value = true
  try {
    await setupApi.save({ ...form.value })
    ElMessage.success('数据源已切换')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.col {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 760px;
}
.hint {
  font-size: 11.5px;
  color: var(--text-3);
  margin-top: 5px;
  line-height: 1.6;
}
.kv {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px solid var(--line-2);
  font-size: 13px;
}
.kv:last-child {
  border-bottom: none;
}
.kv > span:first-child {
  width: 96px;
  color: var(--text-2);
  flex-shrink: 0;
}
.break {
  word-break: break-all;
}
</style>
