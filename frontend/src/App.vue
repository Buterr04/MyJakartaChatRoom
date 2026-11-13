<script setup>
import { onMounted, ref } from 'vue'
import LoginView from './components/LoginView.vue'
import ChatView from './components/ChatView.vue'

const me = ref(null)
const checking = ref(true)

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

async function handleLogin(username, password) {
  const res = await fetch('/api/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(data.error || '登录失败，请稍后再试')
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
</script>

<template>
  <div v-if="checking" class="boot">正在进入聊天室…</div>
  <LoginView v-else-if="!me" :login="handleLogin" :register="handleRegister" />
  <ChatView v-else :me="me" @logout="handleLogout" />
</template>
