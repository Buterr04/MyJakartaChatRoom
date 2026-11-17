<script setup>
import { ref } from 'vue'

const emit = defineEmits(['close'])

const currentPassword = ref('')
const newPassword = ref('')
const confirmNewPassword = ref('')
const saving = ref(false)
const error = ref('')
const success = ref('')

async function submit() {
  if (saving.value) return
  error.value = ''
  success.value = ''
  if (!currentPassword.value) {
    error.value = '请输入当前密码'
    return
  }
  if (newPassword.value.length < 8 || newPassword.value.length > 128) {
    error.value = '新密码长度需要在 8~128 个字符之间'
    return
  }
  if (newPassword.value !== confirmNewPassword.value) {
    error.value = '两次输入的新密码不一致'
    return
  }

  saving.value = true
  try {
    const res = await fetch('/api/me/password', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        currentPassword: currentPassword.value,
        newPassword: newPassword.value,
      }),
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) {
      error.value = data.error || '密码修改失败，请稍后再试'
      return
    }
    currentPassword.value = ''
    newPassword.value = ''
    confirmNewPassword.value = ''
    success.value = '密码已修改，下次登录请使用新密码'
  } catch {
    error.value = '网络异常，请稍后再试'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="dialog-backdrop" @click.self="emit('close')">
    <section class="dialog password-dialog" role="dialog" aria-modal="true" aria-labelledby="password-title">
      <button class="dialog-close" aria-label="关闭" @click="emit('close')">×</button>
      <h2 id="password-title">修改密码</h2>
      <p class="dialog-subtitle">为了账户安全，请先验证当前密码。修改后当前登录仍保持有效。</p>

      <form class="password-form" @submit.prevent="submit">
        <label>
          <span>当前密码</span>
          <input
            v-model="currentPassword"
            type="password"
            maxlength="128"
            autocomplete="current-password"
            autofocus
          />
        </label>
        <label>
          <span>新密码</span>
          <input
            v-model="newPassword"
            type="password"
            minlength="8"
            maxlength="128"
            placeholder="8~128 个字符"
            autocomplete="new-password"
          />
        </label>
        <label>
          <span>确认新密码</span>
          <input
            v-model="confirmNewPassword"
            type="password"
            minlength="8"
            maxlength="128"
            autocomplete="new-password"
          />
        </label>
        <p v-if="error" class="form-error">{{ error }}</p>
        <p v-else-if="success" class="form-success">{{ success }}</p>
        <div class="password-form-actions">
          <button type="button" class="btn-secondary" :disabled="saving" @click="emit('close')">取消</button>
          <button type="submit" class="btn-primary" :disabled="saving">
            {{ saving ? '修改中…' : '更新密码' }}
          </button>
        </div>
      </form>
    </section>
  </div>
</template>
