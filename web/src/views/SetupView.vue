<template>
  <div class="setup">
    <!-- 顶部概览：一眼看清当前连的是哪个库 -->
    <div class="card overview">
      <div class="stat">
        <label>运行状态</label>
        <div>
          <span class="chip" :class="status?.initialized ? 'ok' : 'warn'">
            <span class="dot" :class="status?.initialized ? 'ok' : 'warn'"></span>
            {{ status?.initialized ? '已就绪' : '未初始化' }}
          </span>
        </div>
      </div>
      <div class="stat">
        <label>数据库类型</label>
        <div class="mono">{{ status?.dbType || '-' }}</div>
      </div>
      <div class="stat grow">
        <label>存储位置</label>
        <div class="mono break">{{ status?.path || '-' }}</div>
      </div>
      <div class="stat">
        <label>日志保留</label>
        <div>{{ rform.months ? '最近 ' + rform.months + ' 个月' : '不限制' }}</div>
      </div>
      <div class="stat">
        <label>数据源记录</label>
        <div>{{ dsList.length }} 条</div>
      </div>
      <div class="stat">
        <label>全文索引</label>
        <div>
          <span v-if="!fts?.supported" class="muted">不支持</span>
          <span v-else-if="!fts?.enabled" class="muted">已关闭</span>
          <span v-else-if="fts.ready" class="ok-text">已就绪</span>
          <span v-else class="warn-text">构建中 {{ fts.pct }}%</span>
        </div>
      </div>
    </div>

    <el-tabs v-model="tab" class="setup-tabs">
      <!-- ==================== 数据源 ==================== -->
      <el-tab-pane name="ds">
        <template #label>
          <span class="tab-label">
            <el-icon><Coin /></el-icon>
            数据源
          </span>
        </template>

        <div class="grid">
          <div class="card">
            <div class="card-h">
              <b>切换数据源</b>
              <span class="sub">热生效，无需重启服务</span>
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
                    SQLite 会自动追加
                    <span class="mono">journal_mode=WAL&amp;synchronous=NORMAL&amp;busy_timeout=5000</span>
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

                <div class="btn-row">
                  <el-button type="primary" :loading="saving" @click="save">保存并切换</el-button>
                  <el-button @click="load">重新读取</el-button>
                </div>
              </el-form>
            </div>
          </div>

          <div class="card">
            <div class="card-h">
              <b>数据源记录</b>
              <span class="sub">切换过的库都留档，点「切换」即可切回，不用再填一遍地址</span>
              <el-button size="small" class="head-btn" :loading="dsLoading" @click="loadDs">刷新</el-button>
            </div>
            <div class="card-b">
              <el-table
                v-if="dsList.length"
                :data="dsList"
                size="small"
                style="width: 100%"
                :row-class-name="dsRowClass"
              >
                <el-table-column label="名称" min-width="150">
                  <template #default="{ row }">
                    <div class="cell-strong">{{ row.label || '-' }}</div>
                  </template>
                </el-table-column>
                <el-table-column label="类型" width="100">
                  <template #default="{ row }">
                    <span class="mono">{{ row.db_type || '-' }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="地址" min-width="240">
                  <template #default="{ row }">
                    <span class="mono break">{{ row.url }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="96">
                  <template #default="{ row }">
                    <el-tag v-if="row.is_current == 1" type="success" size="small" effect="dark">当前</el-tag>
                    <span v-else class="muted small">{{ fmtTime(row.last_switch_time) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="184" align="right">
                  <template #default="{ row }">
                    <el-button
                      v-if="row.is_current != 1"
                      size="small"
                      type="primary"
                      :loading="dsSwitching === row.id"
                      @click="switchDatasource(row)"
                    >
                      切换
                    </el-button>
                    <el-button size="small" @click="renameDatasource(row)">改名</el-button>
                    <el-button
                      v-if="row.is_current != 1"
                      size="small"
                      type="danger"
                      plain
                      @click="removeDatasource(row)"
                    >
                      删除
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
              <div v-else class="empty">
                <el-icon class="empty-ico"><Coin /></el-icon>
                <div>暂无记录</div>
                <span class="muted small">切换一次数据源后，这里会自动留档</span>
              </div>
              <div class="hint">
                记录存在当前库的 <span class="mono">xt_datasource</span> 表里，会随迁移一起被拷到目标库，
                所以在目标库里也能一键切回源库。密码只保存在服务端，列表里不下发。
              </div>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- ==================== 数据迁移 ==================== -->
      <el-tab-pane name="mig">
        <template #label>
          <span class="tab-label">
            <el-icon><Switch /></el-icon>
            数据迁移
            <span v-if="migrating" class="tab-dot"></span>
          </span>
        </template>

        <div class="grid">
          <div class="card">
            <div class="card-h">
              <b>发起迁移</b>
              <span class="sub">把当前库全部数据拷到另一个数据库</span>
            </div>
            <div class="card-b">
              <el-form :model="mform" label-position="top">
                <el-form-item label="目标数据库类型">
                  <el-radio-group v-model="mform.dbType" @change="mDbTypeChange">
                    <el-radio-button value="sqlite">SQLite</el-radio-button>
                    <el-radio-button value="mysql">MySQL</el-radio-button>
                    <el-radio-button value="postgresql">PostgreSQL</el-radio-button>
                  </el-radio-group>
                </el-form-item>

                <el-form-item label="目标 JDBC URL">
                  <el-input
                    v-model="mform.url"
                    class="mono"
                    placeholder="jdbc:sqlite:E:/data/xtlog-migrated.db"
                  />
                  <div class="hint" v-if="mIsSqlite">
                    SQLite 会自动追加
                    <span class="mono">journal_mode=WAL&amp;synchronous=NORMAL&amp;busy_timeout=5000</span>
                  </div>
                </el-form-item>

                <div class="grid2">
                  <el-form-item label="用户名">
                    <el-input v-model="mform.user" :disabled="mIsSqlite" placeholder="仅 MySQL / PostgreSQL 需要" />
                  </el-form-item>
                  <el-form-item label="密码">
                    <el-input
                      v-model="mform.password"
                      type="password"
                      show-password
                      :disabled="mIsSqlite"
                      placeholder="仅 MySQL / PostgreSQL 需要"
                    />
                  </el-form-item>
                </div>

                <div class="btn-row">
                  <el-button type="primary" :loading="migrating" @click="startMigrate">
                    {{ migrating ? '迁移中…' : '开始迁移' }}
                  </el-button>
                </div>
              </el-form>

              <!-- 实时进度：离开页面再回来仍在（后端有任务记录） -->
              <div v-if="migrating" class="run-box">
                <div class="run-top">
                  <span class="run-stage">{{ migProgress.stage || '准备中' }}</span>
                  <span class="mono small">{{ migProgress.copied || 0 }} 行</span>
                </div>
                <el-progress
                  :percentage="100"
                  :show-text="false"
                  :stroke-width="6"
                  indeterminate
                  :duration="2"
                />
                <div v-if="migProgress.table" class="muted small mt6">
                  正在拷贝 <span class="mono">{{ migProgress.table }}</span>
                </div>
              </div>

              <el-alert v-if="migrateError" type="error" :closable="false" class="mt">
                <template #title>{{ migrateError }}</template>
              </el-alert>

              <el-alert v-if="migrateResult" type="success" :closable="false" class="mt">
                <template #title>迁移完成 · 目标 {{ migrateResult.target }}</template>
                <div class="mig-summary">
                  <div v-for="s in migrateResult.summary" :key="s.table" class="mig-row">
                    <span class="mono">{{ s.table }}</span>
                    <span class="muted small">{{ s.rows }} 行</span>
                  </div>
                </div>
                <div class="mig-actions">
                  <el-button size="small" @click="closeMigrate">关闭</el-button>
                  <el-button size="small" type="primary" :loading="switching" @click="switchToMigrated">
                    切换到新迁移的库
                  </el-button>
                </div>
              </el-alert>
            </div>
          </div>

          <div class="card">
            <div class="card-h">
              <b>迁移记录</b>
              <span class="sub">离开页面也不会丢，回来仍能看到进度与结果</span>
              <el-button size="small" class="head-btn" :loading="jobsLoading" @click="loadJobs">刷新</el-button>
            </div>
            <div class="card-b">
              <el-table v-if="jobs.length" :data="jobs" size="small" style="width: 100%">
                <el-table-column label="开始时间" width="148">
                  <template #default="{ row }">
                    <span class="small">{{ fmtDateTime(row.startTime) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="目标" min-width="220">
                  <template #default="{ row }">
                    <span class="mono break">{{ row.target }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="150">
                  <template #default="{ row }">
                    <el-tag v-if="row.running" type="warning" size="small">
                      {{ row.stage }}
                      <span v-if="row.table">· {{ row.table }}</span>
                    </el-tag>
                    <el-tag v-else-if="row.ok" type="success" size="small">完成</el-tag>
                    <el-tag v-else type="danger" size="small">{{ row.stage || '失败' }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="已拷行数" width="96" align="right">
                  <template #default="{ row }">
                    <span class="mono">{{ (row.copied || 0).toLocaleString() }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="耗时" width="92">
                  <template #default="{ row }">
                    <span class="small">{{ duration(row) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="结果" min-width="150">
                  <template #default="{ row }">
                    <span v-if="row.running" class="muted small">进行中…</span>
                    <el-tooltip
                      v-else-if="row.ok && (row.summary || []).length"
                      placement="top"
                      :show-after="200"
                    >
                      <template #content>
                        <div v-for="s in row.summary" :key="s.table" class="small">
                          {{ s.table }} — {{ s.rows }} 行
                        </div>
                      </template>
                      <span class="ok-text small">成功 · 共 {{ row.copied || 0 }} 行</span>
                    </el-tooltip>
                    <span v-else-if="row.ok" class="ok-text small">成功 · 共 {{ row.copied || 0 }} 行</span>
                    <el-tooltip v-else-if="row.error" :content="row.error" placement="top" :show-after="200">
                      <span class="err-text small ellipsis">{{ row.error }}</span>
                    </el-tooltip>
                    <span v-else class="muted small">-</span>
                  </template>
                </el-table-column>
              </el-table>
              <div v-else class="empty">
                <el-icon class="empty-ico"><Switch /></el-icon>
                <div>还没有迁移记录</div>
                <span class="muted small">发起一次迁移后，进度与结果会一直留在这里</span>
              </div>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- ==================== 日志保留 ==================== -->
      <el-tab-pane name="ret">
        <template #label>
          <span class="tab-label">
            <el-icon><Clock /></el-icon>
            日志策略
          </span>
        </template>

        <div class="grid">
          <div class="card">
            <div class="card-h">
              <b>写入与保留策略</b>
              <span class="sub">单条上限、按整月保留</span>
            </div>
            <div class="card-b">
              <el-form :model="rform" label-position="top">
                <el-form-item label="单条日志字符上限">
                  <el-input-number v-model="rform.maxChars" :min="0" :max="2097152" :step="4096" />
                  <div class="hint">
                    默认 <b>65536</b>（64KB）。应用偶尔会把整个大对象或超长堆栈打进一行
                    （本库实测单条最大 7.6MB），这类行会让索引虚胖、检索变慢、界面渲染卡死。
                    超过上限的行<b>不会截断丢内容</b>，而是拆成多条连续记录：共享同一个
                    <span class="mono">cont_group</span>、<span class="mono">cont_seq</span> 递增，
                    列表中带「续 N」标记，双击日志可在详情里一键拼回完整原文。<br />
                    填 <b>0</b> = 不限制（下限 4096）。改动只影响之后写入的日志，已有数据不变。
                  </div>
                </el-form-item>

                <el-form-item label="保留最近多少个月">
                  <el-input-number v-model="rform.months" :min="0" :max="120" :step="1" />
                  <div class="hint">
                    填 <b>0</b> 或不设置 = <b>不限制</b>，永不自动删除；填 <b>6</b> = 只保留最近 6 个月，
                    第 7 个月及更早的整张月表会被删除（保存后立即执行，之后每 6 小时巡检、跨月自动清理）。
                  </div>
                </el-form-item>

                <div class="btn-row">
                  <el-button type="primary" :loading="rsaving" @click="saveRetention">保存保留策略</el-button>
                  <el-button @click="loadRetention">重新读取</el-button>
                </div>
              </el-form>

              <div v-if="rResult" class="mt">
                <el-alert v-if="rResult.dropped && rResult.dropped.length" type="warning" :closable="false">
                  <template #title>已删除 {{ rResult.dropped.length }} 张过期月表</template>
                  <div class="mig-summary">
                    <div v-for="t in rResult.dropped" :key="t" class="mig-row">
                      <span class="mono">{{ t }}</span>
                    </div>
                  </div>
                </el-alert>
                <el-alert v-else type="success" :closable="false">
                  <template #title>保留策略已生效，当前没有需要删除的月表</template>
                </el-alert>
              </div>
            </div>
          </div>

          <div class="card">
            <div class="card-h">
              <b>当前库中的月表</b>
              <span class="sub">{{ rTables.length }} 张</span>
            </div>
            <div class="card-b">
              <div v-if="rTables.length" class="mig-summary">
                <div v-for="t in rTables" :key="t" class="mig-row">
                  <span class="mono">{{ t }}</span>
                </div>
              </div>
              <div v-else class="empty">
                <el-icon class="empty-ico"><Clock /></el-icon>
                <div>暂无月表</div>
                <span class="muted small">有日志写入后会自动创建 xt_log_entry_YYYYMM</span>
              </div>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <!-- ==================== 全文索引 ==================== -->
      <el-tab-pane name="fts">
        <template #label>
          <span class="tab-label">
            <el-icon><Search /></el-icon>
            全文索引
            <span v-if="fts?.enabled && !fts.ready" class="tab-dot"></span>
          </span>
        </template>

        <div class="grid">
          <div class="card">
            <div class="card-h">
              <b>关键词检索加速</b>
              <span class="sub">SQLite FTS5 · trigram 倒排索引</span>
            </div>
            <div class="card-b">
              <div v-if="!fts?.supported" class="empty">
                <el-icon class="empty-ico"><Search /></el-icon>
                <div>当前数据源不支持</div>
                <span class="muted small">
                  全文索引基于 SQLite 的 FTS5，MySQL / PostgreSQL 仍是普通 LIKE 检索
                </span>
              </div>

              <template v-else>
                <div class="fts-switch">
                  <el-switch
                    :model-value="!!fts?.enabled"
                    :loading="ftsSaving"
                    active-text="已开启"
                    inactive-text="已关闭"
                    @change="onFtsToggle"
                  />
                  <span class="muted small">
                    关闭会立即删除索引表并释放磁盘，重新开启需要再构建一次
                  </span>
                </div>

                <div v-if="fts?.enabled" class="mt">
                  <div class="fts-progress">
                    <div class="fts-bar">
                      <div class="fts-bar-in" :style="{ width: (fts.pct || 0) + '%' }"></div>
                    </div>
                    <span class="mono small">{{ fts.pct || 0 }}%</span>
                  </div>
                  <div class="hint">
                    已索引 <b>{{ fts.totalIndexed || 0 }}</b> / {{ fts.totalRows || 0 }} 行
                    <span v-if="fts.building" class="warn-text"> · 正在后台构建…</span>
                    <span v-else-if="fts.ready" class="ok-text"> · 已覆盖全部日志</span>
                  </div>
                </div>

                <div v-if="fts?.enabled && (fts.tables || []).length" class="mig-summary mt">
                  <div v-for="t in fts.tables" :key="t.table" class="mig-row">
                    <span class="mono">{{ t.table }}</span>
                    <span class="muted small">
                      {{ t.indexed }} / {{ t.rows }}
                      <span v-if="t.done" class="ok-text">✓</span>
                      <span v-else>（{{ t.pct }}%）</span>
                    </span>
                  </div>
                </div>
              </template>
            </div>
          </div>

          <div class="card">
            <div class="card-h">
              <b>原理与代价</b>
              <span class="sub">开启前先看清楚</span>
            </div>
            <div class="card-b">
              <div class="hint">
                <p>
                  <b>为什么慢：</b>日志正文平均几十 KB，<span class="mono">LIKE '%关键词%'</span>
                  用不上任何索引，每次检索都要把整月正文通读一遍。
                </p>
                <p>
                  <b>怎么加速：</b>trigram 分词把正文切成连续 3 字符的倒排项，
                  关键词检索从「全文扫描」变成「倒排查表」，实测
                  <b>8.3s → 0.006s</b>。
                </p>
                <p>
                  <b>磁盘代价：</b>索引体积约为正文的 <b>1.65 倍</b>，只存倒排、不复制正文。
                </p>
                <p>
                  <b>两个限制：</b>
                  ① 关键词少于 <b>3 个字符</b>（如两字中文「异常」）切不出 token，会自动回退普通检索；
                  ② 索引是后台增量构建的，构建期间未覆盖的新日志仍走普通检索——
                  结果始终完整，只是那部分慢一些。
                </p>
              </div>
            </div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { setupApi, migrateApi, configApi, datasourceApi, ftsApi } from '../api'

const tab = ref('ds')

// 全文索引
const fts = ref(null)
const ftsSaving = ref(false)
let ftsTimer = null

async function loadFts() {
  try {
    fts.value = await ftsApi.status()
  } catch (e) {
    // 状态接口失败不弹错，避免干扰其它 Tab
  }
}

async function onFtsToggle(val) {
  if (!val) {
    try {
      await ElMessageBox.confirm(
        '关闭后会立即删除全部索引表、释放磁盘；再次开启需要重新构建一遍。确定关闭吗？',
        '关闭全文索引',
        { type: 'warning' }
      )
    } catch (e) {
      return
    }
  }
  ftsSaving.value = true
  try {
    await ftsApi.toggle(val)
    await loadFts()
    ElMessage.success(val ? '已开启，正在后台构建索引' : '已关闭，索引表已删除')
  } catch (e) {
    ElMessage.error(e.message)
    await loadFts()
  } finally {
    ftsSaving.value = false
  }
}

const status = ref(null)
const saving = ref(false)
const form = ref({ dbType: 'sqlite', url: '', user: '', password: '' })

// 日志保留策略
const rform = ref({ months: 0, maxChars: 65536 })
const rsaving = ref(false)
const rResult = ref(null)
const rTables = ref([])

async function loadRetention() {
  try {
    const d = await configApi.get()
    rform.value.months = d.retentionMonths || 0
    rform.value.maxChars = d.maxChars == null ? 65536 : d.maxChars
    rTables.value = d.logTables || []
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function saveRetention() {
  rsaving.value = true
  rResult.value = null
  try {
    const d = await configApi.save({
      retentionMonths: rform.value.months || 0,
      maxChars: rform.value.maxChars || 0
    })
    rResult.value = d
    rTables.value = d.logTables || []
    if (d.maxChars != null) {
      rform.value.maxChars = d.maxChars
    }
    ElMessage.success('策略已保存并生效')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    rsaving.value = false
  }
}

// 数据迁移表单与状态
const mform = ref({ dbType: 'sqlite', url: '', user: '', password: '' })
const migrating = ref(false)
const migrateResult = ref(null)
const migrateError = ref('')
const migProgress = ref({ stage: '准备中', table: '', copied: 0 })
const switching = ref(false)

const isSqlite = computed(() => form.value.dbType === 'sqlite')
const mIsSqlite = computed(() => mform.value.dbType === 'sqlite')

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
    ElMessage.success('数据源已切换，正在刷新…')
    setTimeout(() => location.reload(), 900)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

function mDbTypeChange(v) {
  if (v === 'sqlite') {
    mform.value.url = 'jdbc:sqlite:E:/workspace/xt/xtlog-server/data/xtlog-migrated.db'
  } else if (v === 'mysql') {
    mform.value.url = 'jdbc:mysql://127.0.0.1:3306/xtlog?useUnicode=true&characterEncoding=utf8'
  } else {
    mform.value.url = 'jdbc:postgresql://127.0.0.1:5432/xtlog'
  }
}

async function startMigrate() {
  if (!mform.value.url) {
    ElMessage.warning('请填写目标 JDBC URL')
    return
  }
  migrating.value = true
  migrateResult.value = null
  migrateError.value = ''
  migProgress.value = { stage: '准备中', table: '', copied: 0 }
  try {
    // 后端是异步任务：这里只负责启动，随后由全局轮询（loadJobs / startPolling）跟进进度。
    // 中途跳到别的页面再回来，onMounted 会重新 loadJobs 并恢复轮询，不会丢状态。
    const res = await migrateApi.migrate({ ...mform.value })
    if (!res || !res.ok) {
      migrating.value = false
      migrateError.value = (res && res.error) || '迁移启动失败'
      return
    }
    ElMessage.success('迁移已启动')
    await loadJobs()
  } catch (e) {
    migrating.value = false
    migrateError.value = e.message || '迁移失败'
  }
}

// ==================== 迁移记录（可离开页面，回来继续看） ====================

const jobs = ref([])
const jobsLoading = ref(false)
let pollTimer = null

async function loadJobs() {
  jobsLoading.value = true
  try {
    jobs.value = (await migrateApi.jobs()) || []
  } catch (e) {
    ElMessage.error(e.message)
    return
  } finally {
    jobsLoading.value = false
  }
  const running = jobs.value.find((j) => j.running)
  if (running) {
    migrating.value = true
    migProgress.value = {
      stage: running.stage || '',
      table: running.table || '',
      copied: running.copied || 0
    }
    startPolling()
  } else {
    stopPolling()
    if (migrating.value) {
      // 说明是本次会话启动的任务刚结束
      const last = jobs.value[0]
      migrating.value = false
      if (last && last.ok) {
        migrateResult.value = { target: last.target, summary: last.summary || [] }
        ElMessage.success('迁移完成')
        loadDs() // 迁移成功后目标库被登记进数据源记录
      } else {
        migrateError.value = (last && last.error) || '迁移失败'
      }
    }
  }
}

function startPolling() {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    try {
      const list = (await migrateApi.jobs()) || []
      jobs.value = list
      const running = list.find((j) => j.running)
      if (running) {
        migProgress.value = {
          stage: running.stage || '',
          table: running.table || '',
          copied: running.copied || 0
        }
      } else {
        await loadJobs()
      }
    } catch (e) {
      stopPolling()
      migrating.value = false
      migrateError.value = e.message || '查询迁移进度失败'
    }
  }, 1500)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

// ==================== 数据源记录 ====================

const dsList = ref([])
const dsSwitching = ref(0)
const dsLoading = ref(false)

async function loadDs() {
  dsLoading.value = true
  try {
    dsList.value = (await datasourceApi.list()) || []
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    dsLoading.value = false
  }
}

function dsRowClass({ row }) {
  return row.is_current == 1 ? 'row-current' : ''
}

async function switchDatasource(row) {
  try {
    await ElMessageBox.confirm(
      `确定切换到「${row.label}」？\n${row.url}\n\n切换后服务会热替换数据源，页面将自动刷新。`,
      '切换数据源',
      { type: 'warning', confirmButtonText: '确定切换', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  dsSwitching.value = row.id
  try {
    await datasourceApi.switchTo(row.id)
    ElMessage.success('已切换，正在刷新…')
    setTimeout(() => location.reload(), 900)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    dsSwitching.value = 0
  }
}

async function renameDatasource(row) {
  try {
    const { value } = await ElMessageBox.prompt('给这条记录起个好认的名字', '重命名', {
      inputValue: row.label || '',
      confirmButtonText: '保存',
      cancelButtonText: '取消'
    })
    await datasourceApi.rename(row.id, (value || '').trim())
    ElMessage.success('已重命名')
    loadDs()
  } catch (e) {
    if (e && e.message) ElMessage.error(e.message)
  }
}

async function removeDatasource(row) {
  try {
    await ElMessageBox.confirm(`删除记录「${row.label}」？只删除记录，不会动数据库本身。`, '删除记录', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await datasourceApi.remove(row.id)
    ElMessage.success('已删除')
    loadDs()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// ==================== 显示辅助 ====================

function pad(n) {
  return n < 10 ? '0' + n : '' + n
}

function fmtDateTime(ts) {
  if (!ts) return '-'
  const d = new Date(ts)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes()
  )}:${pad(d.getSeconds())}`
}

function fmtTime(ts) {
  if (!ts) return '-'
  const d = new Date(ts)
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function duration(row) {
  const end = row.running ? Date.now() : row.endTime || 0
  if (!row.startTime) return '-'
  const ms = Math.max(0, (end || row.startTime) - row.startTime)
  const s = Math.floor(ms / 1000)
  if (s < 60) return s + ' 秒'
  const m = Math.floor(s / 60)
  return m + ' 分 ' + (s % 60) + ' 秒'
}

function closeMigrate() {
  migrateResult.value = null
  migrateError.value = ''
}

async function switchToMigrated() {
  switching.value = true
  try {
    await migrateApi.switchTo({ ...mform.value })
    ElMessage.success('已切换到新数据库，正在刷新…')
    setTimeout(() => location.reload(), 900)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    switching.value = false
  }
}

onMounted(() => {
  load()
  loadRetention()
  loadDs()
  loadJobs() // 若上次任务仍在跑，这里会自动恢复轮询
  loadFts()
  // 索引是后台逐步构建的，构建期间每 5 秒刷新一次进度
  ftsTimer = setInterval(() => {
    if (fts.value?.enabled && !fts.value.ready) {
      loadFts()
    }
  }, 5000)
})

onUnmounted(() => {
  stopPolling()
  if (ftsTimer) {
    clearInterval(ftsTimer)
    ftsTimer = null
  }
})
</script>

<style scoped>
/* 铺满主内容区，不再固定 760px 让右侧空一大块 */
.setup {
  display: flex;
  flex-direction: column;
  gap: 14px;
  width: 100%;
  min-width: 0;
}

/* ---------- 顶部概览条 ---------- */
.overview {
  display: flex;
  flex-wrap: wrap;
}
.overview .stat {
  flex: 1 1 150px;
  min-width: 0;
  padding: 11px 18px;
  border-right: 1px solid var(--line-2);
}
.overview .stat:last-child {
  border-right: none;
}
.overview .stat.grow {
  flex: 2 1 300px;
}
.overview .stat > label {
  display: block;
  font-size: 11.5px;
  color: var(--text-3);
  margin-bottom: 5px;
}
.overview .stat > div {
  font-size: 13px;
  font-weight: 600;
}

/* ---------- tab ---------- */
.setup-tabs :deep(.el-tabs__header) {
  margin-bottom: 14px;
}
.setup-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--line-2);
}
.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 2px;
}
.tab-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-color-warning);
  animation: blink 1.2s ease-in-out infinite;
}
@keyframes blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.25;
  }
}

/* ---------- 两栏：左表单 / 右列表 ---------- */
.grid {
  display: grid;
  grid-template-columns: minmax(330px, 390px) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}
@media (max-width: 1180px) {
  .grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

.hint {
  font-size: 11.5px;
  color: var(--text-3);
  margin-top: 5px;
  line-height: 1.6;
}
.grid2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.btn-row {
  display: flex;
  gap: 10px;
  margin-top: 4px;
}
.break {
  word-break: break-all;
}
.mt {
  margin-top: 14px;
}
.mt6 {
  margin-top: 6px;
}

/* ---------- 运行中进度 ---------- */
.run-box {
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface-2);
}
.run-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}
.run-stage {
  font-size: 13px;
  font-weight: 600;
}

/* ---------- 列表 ---------- */
.mig-summary {
  max-height: 260px;
  overflow: auto;
  margin: 8px 0 12px;
  border: 1px solid var(--line-2);
  border-radius: 8px;
  padding: 4px 0;
}
.mig-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 5px 12px;
  font-size: 12.5px;
}
.mig-row:nth-child(odd) {
  background: var(--surface-2);
}
.mig-actions {
  display: flex;
  gap: 10px;
}
.head-btn {
  margin-left: auto;
}
.cell-strong {
  font-weight: 600;
}
.ok-text {
  color: var(--green, #35a86a);
}
.err-text {
  color: var(--red, #e3564d);
}
.warn-text {
  color: var(--orange, #d98a1f);
}
.fts-switch {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.fts-progress {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.fts-bar {
  flex: 1;
  height: 8px;
  border-radius: 4px;
  background: var(--surface-3, var(--line-2));
  overflow: hidden;
}
.fts-bar-in {
  height: 100%;
  background: var(--brand, #3a7afe);
  transition: width 0.4s ease;
}
.ellipsis {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
.small {
  font-size: 12px;
}
.muted {
  color: var(--text-3);
}

/* 当前数据源行高亮 */
.setup :deep(.el-table__row.row-current > td.el-table__cell) {
  background: var(--el-color-success-light-9);
}

/* ---------- 空态 ---------- */
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 34px 12px;
  text-align: center;
  font-size: 13px;
  color: var(--text-2);
  border: 1px dashed var(--line);
  border-radius: 8px;
  background: var(--surface-2);
}
.empty-ico {
  font-size: 26px;
  color: var(--text-3);
  margin-bottom: 4px;
}
</style>
