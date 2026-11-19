<script setup>
import { onMounted, ref, watch } from 'vue'
import RoomIcon from './RoomIcon.vue'
import SpotlightCard from './SpotlightCard.vue'

const props = defineProps({
  login: { type: Function, required: true },
  register: { type: Function, required: true },
  initialError: { type: String, default: '' },
})
const emit = defineEmits(['clear-error'])

const mode = ref('login')
const room = ref({ name: '聊天室', icon: '💬' })
const username = ref('')
const displayName = ref('')
const password = ref('')
const confirmPassword = ref('')
const error = ref(props.initialError)
const success = ref('')
const loading = ref(false)
const conflict = ref(false)
const showPassword = ref(false)
const showConfirmPassword = ref(false)

watch([username, password], () => {
  conflict.value = false
})

onMounted(async () => {
  try {
    const res = await fetch('/api/room')
    if (!res.ok) return
    const data = await res.json()
    room.value = {
      name: data.room?.name || '聊天室',
      icon: data.room?.icon || '💬',
    }
    document.title = room.value.name
  } catch {
    // Login still works with default branding.
  }
})

watch(() => props.initialError, (value) => {
  if (value) {
    error.value = value
    mode.value = 'login'
    success.value = ''
  }
}, { immediate: true })

watch(error, (value) => {
  if (!value) emit('clear-error')
})

function switchMode(nextMode) {
  mode.value = nextMode
  error.value = ''
  success.value = ''
  conflict.value = false
  showPassword.value = false
  showConfirmPassword.value = false
}

async function submit() {
  if (loading.value) return
  error.value = ''
  success.value = ''
  const name = username.value.trim()
  const display = displayName.value.trim()
  const force = conflict.value

  if (name.length < 2 || name.length > 20) {
    error.value = '用户名长度需要在 2~20 个字符之间'
    return
  }
  if (mode.value === 'register') {
    if (password.value.length < 8 || password.value.length > 128) {
      error.value = '密码长度需要在 8~128 个字符之间'
      return
    }
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
      await props.login(name, password.value, { force })
      return
    }
    await props.register(name, display, password.value)
    password.value = ''
    confirmPassword.value = ''
    mode.value = 'login'
    success.value = '注册成功，请使用新账户登录'
  } catch (e) {
    if (e.code === 'already_online') {
      conflict.value = true
      error.value = '该账户已在其他设备登录；再次点击按钮将强制下线旧会话'
    } else {
      conflict.value = false
      error.value = e.message
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-glass glass-panel">
      <SpotlightCard class="login-spotlight" spotlight-color="rgba(255, 255, 255, 0.58)">
        <form class="login-card" @submit.prevent="submit">
          <RoomIcon :icon="room.icon" class="login-logo" />
          <h1>{{ room.name }}</h1>
          <p class="login-subtitle">和在线的伙伴打个招呼</p>

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
            <div class="password-field">
              <input
                v-model="password"
                :type="showPassword ? 'text' : 'password'"
                maxlength="128"
                :placeholder="mode === 'login' ? '密码' : '密码（8~128 个字符）'"
                :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
              />
              <button
                type="button"
                class="password-toggle"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                :aria-pressed="showPassword"
                @click="showPassword = !showPassword"
              >
                <svg v-if="!showPassword" viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M2.5 12s3.4-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.4 6.5-9.5 6.5S2.5 12 2.5 12Z" />
                  <circle cx="12" cy="12" r="3" />
                </svg>
                <svg v-else viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M2.5 12s3.4-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.4 6.5-9.5 6.5S2.5 12 2.5 12Z" />
                  <circle cx="12" cy="12" r="3" />
                  <path d="m4 4 16 16" />
                </svg>
              </button>
            </div>
            <div v-if="mode === 'register'" class="password-field">
              <input
                v-model="confirmPassword"
                :type="showConfirmPassword ? 'text' : 'password'"
                maxlength="128"
                placeholder="确认密码"
                autocomplete="new-password"
              />
              <button
                type="button"
                class="password-toggle"
                :aria-label="showConfirmPassword ? '隐藏密码' : '显示密码'"
                :aria-pressed="showConfirmPassword"
                @click="showConfirmPassword = !showConfirmPassword"
              >
                <svg v-if="!showConfirmPassword" viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M2.5 12s3.4-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.4 6.5-9.5 6.5S2.5 12 2.5 12Z" />
                  <circle cx="12" cy="12" r="3" />
                </svg>
                <svg v-else viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M2.5 12s3.4-6.5 9.5-6.5 9.5 6.5 9.5 6.5-3.4 6.5-9.5 6.5S2.5 12 2.5 12Z" />
                  <circle cx="12" cy="12" r="3" />
                  <path d="m4 4 16 16" />
                </svg>
              </button>
            </div>
            <button type="submit" :disabled="loading">
              {{ loading ? '处理中…' : conflict ? '强制登录并挤下线' : mode === 'login' ? '进入聊天室' : '创建账户' }}
            </button>
          </div>

          <p v-if="error" class="form-error">{{ error }}</p>
          <p v-else-if="success" class="form-success">{{ success }}</p>
          <p class="login-hint">
            {{ mode === 'login' ? '使用已注册账户进入聊天室' : '用户名与显示名称均为 2~20 个字符' }}
          </p>
        </form>
      </SpotlightCard>
    </div>
  </div>
</template>
