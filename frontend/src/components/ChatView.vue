<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  me: { type: Object, required: true },
})
const emit = defineEmits(['logout'])

const messages = ref([])
const users = ref([])
const onlineCount = ref(0)
const connected = ref(false)
const draft = ref('')
const sending = ref(false)
const sendError = ref('')
const listEl = ref(null)

let ws = null
let reconnectTimer = null
let retries = 0
// 主动退出/被请出时置位，阻止自动重连
let leaving = false

onMounted(connectWs)

onBeforeUnmount(() => {
  leaving = true
  clearTimeout(reconnectTimer)
  ws?.close()
})

function wsUrl() {
  const proto = location.protocol === 'https:' ? 'wss://' : 'ws://'
  return proto + location.host + '/ws/chat'
}

function connectWs() {
  ws = new WebSocket(wsUrl())

  ws.onopen = async () => {
    connected.value = true
    retries = 0
    // 先建立 WS 再拉历史，两条消息之间没有空窗：拉取之后产生的新消息都会从 WS 推过来
    await Promise.all([loadHistory(), loadUsers()])
  }

  ws.onmessage = (event) => {
    const packet = JSON.parse(event.data)
    if (packet.type === 'message') {
      appendMessage(packet.data)
    } else if (packet.type === 'online') {
      users.value = packet.data.users
      onlineCount.value = packet.data.count
    }
    // pong：保活心跳，无需处理
  }

  ws.onclose = (event) => {
    connected.value = false
    // 1008 = 服务端拒绝（未登录/会话过期），回到登录页而不是无限重连
    if (event.code === 1008) {
      leaving = true
      emit('logout')
      return
    }
    if (!leaving) {
      retries += 1
      const delay = Math.min(1000 * retries, 5000)
      reconnectTimer = setTimeout(connectWs, delay)
    }
  }

  ws.onerror = () => ws?.close()
}

async function loadHistory() {
  const res = await fetch('/api/messages')
  if (res.ok) {
    messages.value = (await res.json()).messages
    scrollBottom()
  }
}

async function loadUsers() {
  const res = await fetch('/api/users')
  if (res.ok) {
    const data = await res.json()
    users.value = data.users
    onlineCount.value = data.count
  }
}

function appendMessage(message) {
  // WS 重连后与 REST 拉取可能重叠，按消息 id 去重
  if (messages.value.some((m) => m.id === message.id)) return
  messages.value.push(message)
  scrollBottom()
}

async function send() {
  const content = draft.value.trim()
  if (!content || sending.value || !connected.value) return
  sending.value = true
  sendError.value = ''
  try {
    const res = await fetch('/api/messages', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content }),
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) {
      sendError.value = data.error || '发送失败，请稍后再试'
      return
    }
    draft.value = ''
    // 自己的消息不在这里插入：服务端会通过 WS 广播回来，保证所有人看到一致的顺序
  } catch {
    sendError.value = '网络异常，请稍后再试'
  } finally {
    sending.value = false
  }
}

async function logout() {
  leaving = true
  clearTimeout(reconnectTimer)
  ws?.close()
  await fetch('/api/logout', { method: 'POST' })
  emit('logout')
}

function scrollBottom() {
  nextTick(() => {
    const el = listEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function formatTime(ts) {
  return new Date(ts).toLocaleTimeString('zh-CN', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
  })
}

function isOwn(message) {
  return message.username === props.me.username
}
</script>

<template>
  <div class="chat-page">
    <header class="chat-header">
      <div class="brand"><span class="logo">💬</span>Final 聊天室</div>
      <div class="header-right">
        <span class="conn" :class="connected ? 'on' : 'off'">
          {{ connected ? '已连接' : '连接中…' }}
        </span>
        <span class="me-chip">{{ me.username }}</span>
        <button class="btn-ghost" @click="logout">退出</button>
      </div>
    </header>

    <div v-if="!connected" class="reconnect-banner">
      实时连接已断开，正在自动重连…
    </div>

    <div class="chat-body">
      <aside class="sidebar">
        <div class="side-title">
          在线用户 <span class="count">{{ onlineCount }}</span>
        </div>
        <ul>
          <li v-for="u in users" :key="u.username">
            <span class="avatar" :class="{ self: u.username === me.username }">
              {{ u.username.slice(0, 1).toUpperCase() }}
            </span>
            <span class="name">
              {{ u.username }}<i v-if="u.username === me.username">（我）</i>
            </span>
          </li>
        </ul>
      </aside>

      <main class="chat-main">
        <div ref="listEl" class="msg-list">
          <template v-for="m in messages" :key="m.id">
            <div v-if="m.type === 'system'" class="msg sys">
              <span class="sys-pill">{{ m.content }}</span>
            </div>
            <div v-else class="msg" :class="isOwn(m) ? 'own' : 'other'">
              <div class="meta">
                <span class="who">{{ m.username }}</span>
                <span class="time">{{ formatTime(m.createdAt) }}</span>
              </div>
              <div class="bubble">{{ m.content }}</div>
            </div>
          </template>
          <div v-if="messages.length === 0" class="empty">
            还没有消息，向在线的伙伴打个招呼吧 👋
          </div>
        </div>

        <footer class="composer">
          <p v-if="sendError" class="send-error">{{ sendError }}</p>
          <div class="composer-row">
            <input
              v-model="draft"
              maxlength="500"
              placeholder="输入消息，回车发送…"
              :disabled="!connected"
              @keyup.enter="send"
            />
            <button
              class="btn-send"
              :disabled="!connected || sending || !draft.trim()"
              @click="send"
            >
              发送
            </button>
          </div>
        </footer>
      </main>
    </div>
  </div>
</template>
