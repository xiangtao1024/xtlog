<template>
  <div class="col">
    <div class="card">
      <div class="card-h"><b>基本资料</b></div>
      <div class="card-b profile">
        <span class="avatar lg">{{ initials(user) }}</span>
        <div class="pf-id">
          <b>{{ user?.nickname || '—' }}</b>
          <span class="muted small mono">@{{ user?.username }}</span>
          <span class="chip sq ok mt">{{ user?.role === 'admin' ? '管理员' : '普通用户' }}</span>
        </div>
        <div class="spacer"></div>
        <div class="pf-meta">
          <div><span class="muted small">注册时间</span><b>{{ user?.createTime ? fmtTime(user.createTime) : '—' }}</b></div>
          <div><span class="muted small">最近更新</span><b>{{ user?.updateTime ? fmtTime(user.updateTime) : '—' }}</b></div>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-h"><b>修改资料</b></div>
      <div class="card-b">
        <el-form :model="form" label-width="88px" style="max-width: 460px">
          <el-form-item label="昵称">
            <el-input v-model="form.nickname" placeholder="显示名称" />
          </el-form-item>
          <el-form-item label="邮箱">
            <el-input v-model="form.email" placeholder="用于通知" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="savingProfile" @click="saveProfile">保存</el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>

    <div class="card">
      <div class="card-h">
        <b>修改密码</b>
        <span class="sub muted small">修改成功后需要重新登录</span>
      </div>
      <div class="card-b">
        <el-form :model="pwd" label-width="88px" style="max-width: 460px">
          <el-form-item label="当前密码">
            <el-input v-model="pwd.oldPassword" type="password" show-password />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input v-model="pwd.newPassword" type="password" show-password />
            <div class="hint muted small">至少 6 位</div>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="savingPwd" @click="savePwd">修改密码</el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { user, initials, loadProfile, updateProfile, logout } from '../store/user'
import { fmtTime } from '../utils/format'

const form = reactive({ nickname: '', email: '' })
const pwd = reactive({ oldPassword: '', newPassword: '' })
const savingProfile = ref(false)
const savingPwd = ref(false)

function fill() {
  form.nickname = user.value?.nickname || ''
  form.email = user.value?.email || ''
}

async function saveProfile() {
  savingProfile.value = true
  try {
    await updateProfile({ nickname: form.nickname, email: form.email })
    ElMessage.success('资料已更新')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    savingProfile.value = false
  }
}

async function savePwd() {
  if (!pwd.oldPassword || !pwd.newPassword) {
    ElMessage.warning('请填写完整')
    return
  }
  savingPwd.value = true
  try {
    // 改密也走同一个资料接口，后端会先校验旧密码
    await updateProfile({ oldPassword: pwd.oldPassword, newPassword: pwd.newPassword })
    ElMessage.success('密码已修改，请重新登录')
    pwd.oldPassword = ''
    pwd.newPassword = ''
    await logout()
    window.location.href = '/login'
  } catch (e) {
    ElMessage.error(e.message || '修改失败')
  } finally {
    savingPwd.value = false
  }
}

onMounted(async () => {
  if (!user.value) await loadProfile()
  fill()
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
.mt {
  margin-top: 4px;
}
.profile {
  display: flex;
  align-items: center;
  gap: 14px;
}
.avatar.lg {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  font-size: 20px;
}
.pf-id {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.pf-id b {
  font-size: 16px;
}
.pf-meta {
  display: flex;
  gap: 32px;
  text-align: right;
}
.pf-meta span {
  display: block;
}
.pf-meta b {
  font-size: 13px;
}
.hint {
  margin-top: 2px;
}
</style>
