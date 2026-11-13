<script setup>
import { ref } from 'vue'

const props = defineProps({
  login: { type: Function, required: true },
  register: { type: Function, required: true },
})

const mode = ref('login')
const username = ref('')
const displayName = ref('')
const password = ref('')
const confirmPassword = ref('')
const error = ref('')
const success = ref('')
const loading = ref(false)

function switchMode(nextMode) {
  mode.value = nextMode
  error.value = ''
  success.value = ''
}

async function submit() {
  if (loading.value) return
  error.value = ''
  success.value = ''
  const name = username.value.trim()
  const display = displayName.value.trim()

  if (name.length < 2 || name.length > 20) {
    error.value = '用户名长度需要在 2~20 个字符之间'
    return
  }
  if (password.value.length < 8 || password.value.length > 128) {
    error.value = '密码长度需要在 8~128 个字符之间'
    return
  }
  if (mode.value === 'register') {
    if (display.length < 2 || display.length > 20) {
      error.value = '显示名称长度需要在 2~20 个字符之间'
      return
    }
    if (password.value !== confirmPassword.value) {
      error.value = '两次输入的密码不一致'
      return
    }
  }

  loading.value = true
  try {
    if (mode.value === 'login') {
      await props.login(name, password.value)
      return
    }
    await props.register(name, display, password.value)
    password.value = ''
    confirmPassword.value = ''
    mode.value = 'login'
    success.value = '注册成功，请使用新账户登录'
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <form class="login-card" @submit.prevent="submit">
      <div class="login-logo">💬</div>
      <h1>聊天室</h1>

      <div class="auth-tabs" role="tablist" aria-label="账户操作">
        <button type="button" :class="{ active: mode === 'login' }" @click="switchMode('login')">登录</button>
        <button type="button" :class="{ active: mode === 'register' }" @click="switchMode('register')">注册</button>
      </div>

      <div class="auth-fields">
        <input v-model="username" maxlength="20" placeholder="用户名" autocomplete="username" autofocus />
        <input
          v-if="mode === 'register'"
          v-model="displayName"
          maxlength="20"
          placeholder="聊天室显示名称"
          autocomplete="nickname"
        />
        <input
          v-model="password"
          type="password"
          maxlength="128"
          placeholder="密码（8~128 个字符）"
          :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
        />
        <input
          v-if="mode === 'register'"
          v-model="confirmPassword"
          type="password"
          maxlength="128"
          placeholder="确认密码"
          autocomplete="new-password"
        />
        <button type="submit" :disabled="loading">
          {{ loading ? '处理中…' : mode === 'login' ? '进入聊天室' : '创建账户' }}
        </button>
      </div>

      <p v-if="error" class="form-error">{{ error }}</p>
      <p v-else-if="success" class="form-success">{{ success }}</p>
      <p class="login-hint">
        {{ mode === 'login' ? '使用已注册账户进入聊天室' : '用户名与显示名称均为 2~20 个字符' }}
      </p>
    </form>
  </div>
</template>
