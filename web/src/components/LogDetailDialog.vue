<template>
  <!-- 完整日志详情：控制台双击行 / 表格「详情」按钮共用 -->
  <el-dialog
    :model-value="modelValue"
    @update:model-value="$emit('update:modelValue', $event)"
    title="日志详情"
    width="760px"
    top="8vh"
    class="log-detail-dialog"
  >
    <div v-if="row" class="detail">
      <div class="d-grid">
        <div class="d-item">
          <label>日志时间</label>
          <span class="mono num">{{ fmtTime(row.log_time) || '—' }}</span>
        </div>
        <div class="d-item">
          <label>入库时间</label>
          <span class="mono num">{{ fmtTime(row.receive_time) || '—' }}</span>
        </div>
        <div class="d-item">
          <label>级别</label>
          <span class="chip sq" :class="levelClass(row.level)">{{ row.level || '—' }}</span>
        </div>
        <div class="d-item">
          <label>应用</label>
          <span class="mono">{{ appName || '—' }}</span>
        </div>
        <div class="d-item">
          <label>Logger</label>
          <span class="mono">{{ row.logger || '—' }}</span>
        </div>
        <div class="d-item">
          <label>线程</label>
          <span class="mono">{{ row.thread || '—' }}</span>
        </div>
        <div class="d-item">
          <label>TraceId</label>
          <span class="mono">{{ row.trace_id || '—' }}</span>
        </div>
        <div class="d-item">
          <label>来源 IP</label>
          <span class="mono">{{ row.src_ip || '—' }}</span>
        </div>
      </div>

      <!-- 超长日志被拆分保存：提供按组拼回原文 -->
      <div v-if="row.cont_group" class="d-cont">
        <span v-if="!grp.loaded" class="d-cont-txt">
          这条日志超过单条上限，已拆成多段保存（本段为第 {{ row.cont_seq + 1 }} 段）
        </span>
        <span v-else class="d-cont-txt">下面是拼接后的完整内容，共 {{ grp.count }} 段</span>
        <el-button
          v-if="!grp.loaded"
          size="small"
          text
          type="primary"
          :loading="grp.loading"
          @click="loadWholeGroup"
        >查看完整日志</el-button>
        <el-button v-else size="small" text @click="grp.loaded = false">只看本段</el-button>
      </div>

      <div class="d-msg">
        <div class="d-msg-h">
          <label>{{ grp.loaded ? '完整内容' : '本段内容' }}</label>
          <el-button size="small" text type="primary" @click="copyMsg">复制</el-button>
        </div>
        <pre class="d-pre mono">{{ shownMsg }}</pre>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { logApi } from '../api'
import { fmtTime, levelClass, toDate } from '../utils/format'

const props = defineProps({
  /** v-model：弹窗是否显示 */
  modelValue: { type: Boolean, default: false },
  /** 当前日志行（/api/logs 返回对象） */
  row: { type: Object, default: null },
  /** 所属应用名，未取到时显示 — */
  appName: { type: String, default: '' }
})
defineEmits(['update:modelValue'])

/** 超长日志的分片段状态：loaded=true 时详情区显示拼接后的全文 */
const grp = reactive({ loading: false, loaded: false, message: '', count: 0 })

// 每次打开都从「本段」重新开始，避免沿用上一次的拼接结果
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      grp.loaded = false
      grp.message = ''
      grp.count = 0
      grp.loading = false
    }
  }
)

/** 详情里当前显示的内容：拼了就用全文，否则是本段 */
const shownMsg = computed(() =>
  grp.loaded ? grp.message : String(props.row?.message ?? '')
)

/** 按 cont_group 拉同一条日志的所有片段并拼起来 */
async function loadWholeGroup() {
  const row = props.row
  if (!row?.cont_group) return
  grp.loading = true
  try {
    const t = toDate(row.log_time)
    const d = await logApi.group({
      g: row.cont_group,
      ...(t ? { ts: t.getTime() } : {})
    })
    grp.message = d?.message ?? ''
    grp.count = d?.count ?? 0
    grp.loaded = true
  } catch (e) {
    ElMessage.error(e?.message || '加载分片失败')
  } finally {
    grp.loading = false
  }
}

async function copyMsg() {
  try {
    await navigator.clipboard.writeText(shownMsg.value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('浏览器拒绝了剪贴板访问，请手动选中复制')
  }
}
</script>

<style scoped>
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
/* 详情弹窗里的「已拆分为多段」提示条 */
.d-cont {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  padding: 7px 10px;
  border: 1px solid rgba(251, 191, 36, 0.35);
  border-radius: var(--radius-md, 8px);
  background: rgba(251, 191, 36, 0.08);
}
.d-cont-txt {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--text-2);
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
