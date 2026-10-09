<template>
  <div class="col">
    <!-- 未带 appId 进来：提示回到应用列表 -->
    <div v-if="!appId" class="card empty-card">
      <el-empty description="请从「应用管理」里选择应用后点「开发配置」">
        <el-button type="primary" @click="router.push('/apps')">去应用管理</el-button>
      </el-empty>
    </div>

    <template v-else>
      <div class="card">
        <!-- 返回按钮与应用名已由页头提供，这里只留页面内操作 -->
        <div class="card-b toolbar">
          <div class="spacer"></div>
          <el-button size="small" @click="goLogs">
            <el-icon style="margin-right: 4px"><VideoPlay /></el-icon>查看日志
          </el-button>
        </div>
      </div>

      <div class="card">
        <div class="card-h">
          <b>HTTP 上报</b>
          <span class="sub muted small">应用主动推送日志到服务端</span>
        </div>
        <div class="card-b">
          <el-form label-width="96px">
            <el-form-item label="目标应用">
              <span class="mono small">{{ currentApp?.name || '应用 #' + appId }}（id={{ appId }}）</span>
            </el-form-item>
            <el-form-item label="接入 Token">
              <span class="mono small tk">{{ token }}</span>
              <el-icon class="cp" title="复制" @click="copy(token)"><CopyDocument /></el-icon>
            </el-form-item>
            <el-form-item label="接口">
              <code class="code-box">POST {{ origin }}/api/logs</code>
            </el-form-item>
            <el-form-item label="请求头">
              <code class="code-box">Content-Type: application/json</code>
            </el-form-item>
            <el-form-item label="认证">
              <span class="muted small">
                请求体里带 <code class="inline">token</code> 字段，值为上面这个应用的接入 Token
              </span>
            </el-form-item>
          </el-form>

          <div class="snippet">
            <div class="snippet-h">
              <span>curl 示例</span>
              <el-button link type="primary" size="small" @click="copy(curlCmd)">复制</el-button>
            </div>
            <pre class="snippet-b">{{ curlCmd }}</pre>
          </div>

          <div class="snippet">
            <div class="snippet-h">
              <span>单条 / 批量请求体</span>
              <el-button link type="primary" size="small" @click="copy(bodyText)">复制</el-button>
            </div>
            <pre class="snippet-b">{{ bodyText }}</pre>
          </div>

          <p class="muted small mt">
            <b class="ip-tip">来源 IP（srcIp）：</b>
            微服务多实例部署时，建议每台机器上报自己的 IP（如
            <code class="inline">10.20.30.41</code>），这样在日志里就能区分这条日志来自哪台机器；不传则该字段为空。
            文件采集 / 命令采集由 xtlog 自动填它所在机器的 IP，HTTP 上报需要你自己带上来。
          </p>
        </div>
      </div>

      <div class="card">
        <div class="card-h"><b>字段说明</b></div>
        <el-table :data="FIELDS" size="small" style="width: 100%">
          <el-table-column prop="name" label="字段" width="140" />
          <el-table-column prop="type" label="类型" width="100" />
          <el-table-column prop="req" label="必填" width="80">
            <template #default="{ row }">
              <span :class="row.req === '是' ? 'req-yes' : 'muted'">{{ row.req }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="desc" label="说明" min-width="220" />
        </el-table>
      </div>

      <div class="card">
        <div class="card-h">
          <b>实时订阅（SSE）</b>
          <span class="sub muted small">浏览器原生 EventSource 无法自定义请求头，token 走查询参数</span>
        </div>
        <div class="card-b">
          <code class="code-box">GET {{ origin }}/api/logs/stream?appId={{ appId }}&amp;satoken={{ '{登录会话token}' }}</code>
          <p class="muted small mt">
            想直接看效果：<router-link class="link" :to="{ path: '/logs', query: { appId } }">
              查看日志
            </router-link>
            页打开「实时接收中」开关。
          </p>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { apps, loadMeta } from '../store/meta'

const route = useRoute()
const router = useRouter()

// 与日志页一致：开发配置也是应用级页面，只能从应用列表点进来，页面里不做应用选择
const appId = route.query.appId ? Number(route.query.appId) : null
// 返回入口统一在页头（App.vue 的 stage-head），这里不再重复放一个返回按钮
const goLogs = () => router.push({ path: '/logs', query: { appId } })

const origin = computed(() => window.location.origin)

const currentApp = computed(() => apps.value.find((a) => Number(a.id) === Number(appId)))
const token = computed(() => currentApp.value?.token || '<TOKEN>')

const curlCmd = computed(() => `curl -X POST '${origin.value}/api/logs' \\
  -H 'Content-Type: application/json' \\
  -d '${JSON.stringify(sampleBody(appId ?? 1, token.value))}'`)

const bodyText = computed(() =>
  JSON.stringify(
    {
      token: token.value,
      logs: [sampleBody(appId ?? 1, token.value).logs[0]]
    },
    null,
    2
  )
)

const FIELDS = [
  { name: 'token', type: 'string', req: '是', desc: '应用接入令牌，在应用管理页生成' },
  { name: 'appId', type: 'number', req: '否', desc: '不传时由 token 推导所属应用' },
  { name: 'level', type: 'string', req: '否', desc: 'ERROR / WARN / INFO / DEBUG / TRACE，默认 INFO' },
  { name: 'logger', type: 'string', req: '否', desc: '类名或 logger 名' },
  { name: 'thread', type: 'string', req: '否', desc: '线程名' },
  { name: 'message', type: 'string', req: '是', desc: '日志正文' },
  { name: 'traceId', type: 'string', req: '否', desc: '链路追踪 ID' },
  { name: 'timestamp', type: 'number', req: '否', desc: '毫秒时间戳（日志发生时间），不传则用服务端接收时间' },
  {
    name: 'srcIp',
    type: 'string',
    req: '否',
    desc: '来源 IP（示例 10.20.30.41）：微服务多实例时传本机 IP，用于区分日志来自哪台机器；不传则为空'
  }
]

function sampleBody(id, tk) {
  return {
    token: tk,
    logs: [
      {
        appId: Number(id) || 1,
        level: 'INFO',
        logger: 'com.example.DemoService',
        thread: 'http-nio-8080-exec-1',
        message: '这是一条测试日志',
        traceId: 'trace-demo-001',
        // 来源 IP：微服务多实例时用来区分是哪台机器；不传则为空
        srcIp: '10.20.30.41'
      }
    ]
  }
}

async function copy(text) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('浏览器不允许复制，请手动选择')
  }
}

onMounted(async () => {
  if (appId && !apps.value.length) {
    await loadMeta().catch(() => {})
  }
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
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.app-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border: 1px solid var(--line);
  border-radius: 99px;
  background: var(--surface-2);
  font-size: 13px;
}
.mt {
  margin-top: 10px;
}
.link {
  color: var(--accent);
  text-decoration: none;
}
.code-box {
  display: inline-block;
  padding: 6px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface-2);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12.5px;
  word-break: break-all;
}
code.inline {
  padding: 1px 5px;
  border: 1px solid var(--line);
  border-radius: 4px;
  background: var(--surface-2);
  font-size: 12px;
}
.snippet {
  margin-top: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
}
.snippet-h {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: var(--surface-2);
  border-bottom: 1px solid var(--line);
  font-size: 12.5px;
  color: var(--text-2);
}
.snippet-b {
  margin: 0;
  padding: 12px;
  background: #0f1115;
  color: #d1d5db;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.req-yes {
  color: var(--danger);
}
.ip-tip {
  color: var(--text-2);
  font-weight: 600;
}
.empty-card {
  padding: 24px;
}
</style>
