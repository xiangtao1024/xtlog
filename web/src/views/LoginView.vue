<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <span class="mark">
          <el-icon :size="18"><Files /></el-icon>
        </span>
        <div>
          <b>xtlog</b>
          <small>统一日志平台</small>
        </div>
      </div>

      <h2>登录</h2>
      <!-- 默认凭据提示只在开发态出现（打包后 devHint 恒为 false，整段会被 tree-shaking 移除） -->
      <p v-if="devHint" class="tip">开发环境已预填默认管理员账号与密码，直接点登录即可</p>
      <p v-else class="tip">请使用管理员账号登录</p>

      <el-form :model="form" size="large" @keyup.enter="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="账号" autofocus>
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" show-password>
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-button class="submit" type="primary" size="large" :loading="loading" @click="submit">
          登录
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login } from '../store/user'

const router = useRouter()
const route = useRoute()
const loading = ref(false)

/**
 * 默认管理员只在「开发模式」预填：凭据来自 web/.env.development 的 VITE_DEV_LOGIN_*，
 * 该文件 Vite 在 build（mode=production）时不加载，两个变量替换为未定义 → 打包产物里
 * 不会出现默认账号密码，登录页两个输入框为空、提示语也不再显示（devHint 恒 false）。
 */
const devHint = !!import.meta.env.VITE_DEV_LOGIN_PASS
const form = reactive({
  username: import.meta.env.VITE_DEV_LOGIN_USER || '',
  password: import.meta.env.VITE_DEV_LOGIN_PASS || ''
})

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    await login({ username: form.username, password: form.password })
    const redirect = route.query.redirect
    router.replace(redirect ? String(redirect) : '/dashboard')
  } catch (e) {
    ElMessage.error(e.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: radial-gradient(1200px 600px at 50% -10%, #eaf0ff 0%, var(--bg) 55%);
}
.login-card {
  width: 380px;
  padding: 30px 30px 34px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-l);
  box-shadow: var(--sh-l);
}
.brand {
  display: flex;
  align-items: center;
  gap: 11px;
  margin-bottom: 22px;
}
.brand .mark {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  display: grid;
  place-items: center;
  background: var(--accent-tint);
  color: var(--accent);
}
.brand b {
  display: block;
  font-size: 15px;
  letter-spacing: 0.2px;
}
.brand small {
  display: block;
  font-size: 11.5px;
  color: var(--text-3);
}
h2 {
  margin: 0 0 6px;
  font-size: 19px;
}
.tip {
  margin: 0 0 20px;
  font-size: 12.5px;
  color: var(--text-3);
}
.tip code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--text-2);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 1px 5px;
}
.submit {
  width: 100%;
  margin-top: 4px;
}
</style>
