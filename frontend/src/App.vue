<script setup>
import { onMounted, ref } from 'vue'
import AmbientBackground from './components/AmbientBackground.vue'
import LoginView from './components/LoginView.vue'
import ChatView from './components/ChatView.vue'

const me = ref(null)
const checking = ref(true)
const logoutReason = ref('')

onMounted(async () => {
  try {
    const res = await fetch('/api/me')
    if (res.ok) me.value = await res.json()
  } catch {
    // The login view remains usable while the backend is unavailable.
  } finally {
    checking.value = false
  }
})

async function handleLogin(username, password, options = {}) {
  const res = await fetch('/api/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, force: options.force === true }),
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) {
    const error = new Error(data.error || '登录失败，请稍后再试')
    error.code = data.code
    throw error
  }
  me.value = data
}

async function handleRegister(username, displayName, password) {
  const res = await fetch('/api/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, displayName, password }),
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(data.error || '注册失败，请稍后再试')
  return data
}

function handleLogout() {
  me.value = null
}

function handleLogoutWithReason(reason) {
  logoutReason.value = reason || ''
  handleLogout()
}

function handleAccountUpdated(account) {
  if (me.value?.username.toLowerCase() === account.username.toLowerCase()) {
    me.value = { ...me.value, ...account }
  }
}
</script>

<template>
  <div class="app-shell">
    <AmbientBackground />
    <Transition name="view-switch" mode="out-in">
      <div v-if="checking" key="boot" class="boot">正在进入聊天室…</div>
      <LoginView
        v-else-if="!me"
        key="login"
        :login="handleLogin"
        :register="handleRegister"
        :initial-error="logoutReason"
        @clear-error="logoutReason = ''"
      />
      <ChatView v-else key="chat" :me="me" @logout="handleLogoutWithReason" @account-updated="handleAccountUpdated" />
    </Transition>
  </div>
</template>
