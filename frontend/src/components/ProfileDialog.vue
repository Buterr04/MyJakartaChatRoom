<script setup>
import { ref } from 'vue'
import UserAvatar from './UserAvatar.vue'

const props = defineProps({
  me: { type: Object, required: true },
})
const emit = defineEmits(['close', 'updated', 'change-password', 'logout'])

const fileInput = ref(null)
const saving = ref(false)
const avatarError = ref('')
const avatarSuccess = ref('')

function chooseAvatar() {
  avatarError.value = ''
  avatarSuccess.value = ''
  fileInput.value?.click()
}

async function avatarSelected(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!['image/png', 'image/jpeg', 'image/gif', 'image/webp'].includes(file.type)) {
    avatarError.value = '请选择 PNG、JPEG、GIF 或 WebP 图片'
    return
  }
  if (file.size > 1024 * 1024) {
    avatarError.value = '头像文件不能超过 1MB'
    return
  }
  const reader = new FileReader()
  reader.onload = () => saveAvatar(reader.result)
  reader.onerror = () => { avatarError.value = '读取图片失败，请重新选择' }
  reader.readAsDataURL(file)
}

async function saveAvatar(avatar) {
  if (saving.value) return
  saving.value = true
  avatarError.value = ''
  avatarSuccess.value = ''
  try {
    const res = await fetch('/api/me/avatar', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ avatar }),
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) {
      avatarError.value = data.error || '头像保存失败，请稍后再试'
      return
    }
    emit('updated', data)
    avatarSuccess.value = avatar ? '头像已更新' : '已恢复默认头像'
  } catch {
    avatarError.value = '网络异常，请稍后再试'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="dialog-backdrop" @click.self="emit('close')">
    <section class="dialog profile-dialog" role="dialog" aria-modal="true" aria-labelledby="profile-title">
      <button class="dialog-close" aria-label="关闭" @click="emit('close')">×</button>
      <h2 id="profile-title">个人主页</h2>
      <p class="dialog-subtitle">查看账户资料、设置头像并修改登录密码</p>

      <div class="profile-summary">
        <button
          type="button"
          class="profile-avatar-button"
          :disabled="saving"
          title="更改头像"
          aria-label="更改头像"
          @click="chooseAvatar"
        >
          <UserAvatar :user="me" class="profile-avatar" />
          <span class="profile-avatar-overlay">{{ saving ? '保存中…' : '更改头像' }}</span>
        </button>
        <div>
          <h3>{{ me.displayName }}</h3>
          <span class="role-chip">{{ me.role === 'ADMIN' ? '管理员' : '用户' }}</span>
        </div>
      </div>

      <dl class="profile-details">
        <div><dt>用户名</dt><dd>{{ me.username }}</dd></div>
        <div><dt>显示名称</dt><dd>{{ me.displayName }}</dd></div>
        <div><dt>账户权限</dt><dd>{{ me.role === 'ADMIN' ? '管理员' : '普通用户' }}</dd></div>
      </dl>

      <div class="avatar-editor">
        <input
          ref="fileInput"
          class="visually-hidden"
          type="file"
          accept="image/png,image/jpeg,image/gif,image/webp"
          @change="avatarSelected"
        />
        <button v-if="me.avatar" class="btn-secondary" :disabled="saving" @click="saveAvatar(null)">
          恢复默认
        </button>
        <p>点击头像更换；支持 PNG、JPEG、GIF、WebP，文件不超过 1MB。</p>
      </div>
      <p v-if="avatarError" class="form-error">{{ avatarError }}</p>
      <p v-else-if="avatarSuccess" class="form-success">{{ avatarSuccess }}</p>

      <div class="profile-security">
        <div>
          <h3>账户安全</h3>
          <p>验证当前密码后，可设置新的登录密码。</p>
        </div>
        <button class="btn-secondary" @click="emit('change-password')">修改密码</button>
      </div>

      <div class="profile-logout">
        <div>
          <h3>退出登录</h3>
          <p>退出后立即离线，下次需重新登录。</p>
        </div>
        <button class="btn-secondary" @click="emit('logout')">退出</button>
      </div>
    </section>
  </div>
</template>
