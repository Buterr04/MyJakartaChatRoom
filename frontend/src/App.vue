<script setup>
import { onMounted, ref } from 'vue'
import LoginView from './components/LoginView.vue'
import ChatView from './components/ChatView.vue'

// me 为 null 表示未登录；刷新页面时先问一次 /api/me 恢复会话
const me = ref(null)
const checking = ref(true)

onMounted(async () => {
  try {
    const res = await fetch('/api/me')
    if (res.ok) {
      me.value = await res.json()
    }
  } catch {
    // 后端没起来，照常显示登录页
  } finally {
    checking.value = false
  }
})

async function handleLogin(username) {
  const res = await fetch('/api/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username }),
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) {
    throw new Error(data.error || '登录失败，请稍后再试')
  }
  me.value = data
}

async function handleLogout() {
  me.value = null
}
</script>

<template>
  <div v-if="checking" class="boot">正在进入聊天室…</div>
  <LoginView v-else-if="!me" :login="handleLogin" />
  <ChatView v-else :me="me" @logout="handleLogout" />
</template>
