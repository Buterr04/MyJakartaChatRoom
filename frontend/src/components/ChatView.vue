<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import AdminUsersDialog from './AdminUsersDialog.vue'
import AiSettingsDialog from './AiSettingsDialog.vue'
import PasswordDialog from './PasswordDialog.vue'
import ProfileDialog from './ProfileDialog.vue'
import SpotlightCard from './SpotlightCard.vue'
import UserAvatar from './UserAvatar.vue'

const props = defineProps({
  me: { type: Object, required: true },
})
const emit = defineEmits(['logout', 'account-updated'])

const messages = ref([])
const users = ref([])
const onlineCount = ref(0)
const connected = ref(false)
const now = ref(Date.now())
const draft = ref('')
const sending = ref(false)
const sendError = ref('')
const actionError = ref('')
const profileOpen = ref(false)
const passwordOpen = ref(false)
const adminOpen = ref(false)
const aiSettingsOpen = ref(false)
const accountRevision = ref(0)
const listEl = ref(null)
const imageInput = ref(null)
const messageInput = ref(null)
const deletedMessageIds = new Set()
const isAdmin = computed(() => props.me.role === 'ADMIN')
const isSuperAdmin = computed(() => isAdmin.value && props.me.username.toLowerCase() === 'admin')
const isMuted = computed(() => Number(props.me.mutedUntil || 0) > now.value)

let ws = null
let reconnectTimer = null
let clockTimer = null
let retries = 0
// 主动退出/被请出时置位，阻止自动重连
let leaving = false

onMounted(() => {
  connectWs()
  clockTimer = setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  leaving = true
  clearTimeout(reconnectTimer)
  clearInterval(clockTimer)
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
    } else if (packet.type === 'messageDeleted') {
      removeMessage(packet.data.id)
    } else if (packet.type === 'accountUpdated') {
      applyAccount(packet.data)
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
    messages.value = (await res.json()).messages.filter((message) => !deletedMessageIds.has(message.id))
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
  if (deletedMessageIds.has(message.id) || messages.value.some((m) => m.id === message.id)) return
  messages.value.push(message)
  scrollBottom()
}

function removeMessage(id) {
  deletedMessageIds.add(id)
  messages.value = messages.value.filter((message) => message.id !== id)
}

function applyAccount(account) {
  accountRevision.value += 1
  users.value = users.value.map((user) => (
    user.username.toLowerCase() === account.username.toLowerCase() ? { ...user, ...account } : user
  ))
  if (props.me.username.toLowerCase() === account.username.toLowerCase()) {
    emit('account-updated', account)
  }
}

async function send() {
  const content = draft.value.trim()
  if (!content || sending.value || !connected.value || isMuted.value) return
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

function chooseImage() {
  sendError.value = ''
  imageInput.value?.click()
}

function prepareAiPrompt() {
  if (sending.value || !connected.value || isMuted.value) return
  if (!/^@AI(?:\s|$)/i.test(draft.value.trimStart())) {
    draft.value = '@AI ' + draft.value
  }
  nextTick(() => messageInput.value?.focus())
}

async function imageSelected(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file || sending.value || !connected.value || isMuted.value) return
  if (!['image/png', 'image/jpeg', 'image/gif', 'image/webp'].includes(file.type)) {
    sendError.value = '请选择 PNG、JPEG、GIF 或 WebP 图片'
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    sendError.value = '图片不能超过 10MB'
    return
  }
  sending.value = true
  sendError.value = ''
  try {
    const form = new FormData()
    form.append('image', file, file.name)
    const res = await fetch('/api/messages/image', { method: 'POST', body: form })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) {
      sendError.value = data.error || '图片发送失败，请稍后再试'
      return
    }
  } catch {
    sendError.value = '网络异常，请稍后再试'
  } finally {
    sending.value = false
  }
}

async function deleteMessage(message) {
  if (!isAdmin.value || !window.confirm('确定删除这条消息吗？')) return
  actionError.value = ''
  try {
    const res = await fetch(`/api/admin/messages/${message.id}`, { method: 'DELETE' })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) {
      actionError.value = data.error || '删除失败，请稍后再试'
      return
    }
    removeMessage(message.id)
  } catch {
    actionError.value = '网络异常，请稍后再试'
  }
}

async function muteUser(user) {
  if (!isAdmin.value) return
  let minutes = 0
  if (!isUserMuted(user)) {
    const input = window.prompt(`禁言 ${user.displayName} 多少分钟？（0~10080）`, '10')
    if (input === null) return
    minutes = Number(input)
    if (!Number.isInteger(minutes) || minutes < 0 || minutes > 10080) {
      actionError.value = '禁言时长需为 0~10080 的整数分钟'
      return
    }
  }
  actionError.value = ''
  try {
    const res = await fetch(`/api/admin/users/${encodeURIComponent(user.username)}/mute`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ minutes }),
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) {
      actionError.value = data.error || '禁言操作失败，请稍后再试'
      return
    }
    applyAccount(data.account)
  } catch {
    actionError.value = '网络异常，请稍后再试'
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

function formatMuteDeadline(ts) {
  return new Date(ts).toLocaleString('zh-CN', { hour12: false })
}

function isUserMuted(user) {
  return Number(user.mutedUntil || 0) > now.value
}

function isOwn(message) {
  return message.username === props.me.username
}

function openPasswordDialog() {
  profileOpen.value = false
  passwordOpen.value = true
}

function isAi(message) {
  return !message.username && message.displayName === 'AI助手'
}

function imageUrl(image) {
  return `/api/images/${encodeURIComponent(image.id)}`
}
</script>

<template>
  <div class="chat-page">
    <div class="chat-header-glass glass-panel">
      <SpotlightCard class="chat-header-spotlight">
        <header class="chat-header">
          <div class="brand"><span class="logo">💬</span>聊天室</div>
          <div class="header-right">
            <span class="conn" :class="connected ? 'on' : 'off'">
              {{ connected ? '已连接' : '连接中…' }}
            </span>
            <span v-if="isAdmin" class="role-chip">管理员</span>
            <button v-if="isSuperAdmin" class="btn-manage" @click="aiSettingsOpen = true">AI 设置</button>
            <button v-if="isAdmin" class="btn-manage" @click="adminOpen = true">用户管理</button>
            <button class="profile-trigger" title="打开个人主页" @click="profileOpen = true">
              <UserAvatar :user="me" />
              <span>{{ me.displayName }}</span>
            </button>
            <button class="btn-ghost" @click="logout">退出</button>
          </div>
        </header>
      </SpotlightCard>
    </div>

    <div v-if="!connected" class="reconnect-banner">
      实时连接已断开，正在自动重连…
    </div>
    <div v-if="actionError" class="admin-error">{{ actionError }}</div>
    <div v-if="isMuted" class="muted-banner">
      账户已被禁言至 {{ formatMuteDeadline(me.mutedUntil) }}，期间仍可查看聊天。
    </div>

    <div class="chat-body">
      <div class="sidebar-glass glass-panel">
        <SpotlightCard class="sidebar-spotlight" spotlight-color="rgba(255, 255, 255, 0.4)">
          <aside class="sidebar">
            <div class="side-title">
              在线用户 <span class="count">{{ onlineCount }}</span>
            </div>
            <ul>
              <li v-for="u in users" :key="u.username">
                <UserAvatar :user="u" :class="{ self: u.username === me.username }" />
                <span class="name">
                  {{ u.displayName }}<i v-if="u.username === me.username">（我）</i>
                  <i v-if="u.role === 'ADMIN'">（管理员）</i>
                  <i v-if="isUserMuted(u)">（已禁言）</i>
                </span>
                <button
                  v-if="isAdmin && u.username !== me.username"
                  class="user-action"
                  @click="muteUser(u)"
                >
                  {{ isUserMuted(u) ? '解禁' : '禁言' }}
                </button>
              </li>
            </ul>
          </aside>
        </SpotlightCard>
      </div>

      <main class="chat-main">
        <div ref="listEl" class="msg-list">
          <template v-for="m in messages" :key="m.id">
            <div v-if="m.type === 'system'" class="msg sys">
              <span class="sys-pill">{{ m.content }}</span>
            </div>
            <div v-else class="msg" :class="[isOwn(m) ? 'own' : 'other', { ai: isAi(m) }]">
              <div class="meta">
                <span class="who">{{ m.displayName }}</span>
                <span class="time">{{ formatTime(m.createdAt) }}</span>
                <button v-if="isAdmin" class="msg-action" @click="deleteMessage(m)">删除</button>
              </div>
              <div v-if="m.image" class="bubble image-bubble">
                <a :href="imageUrl(m.image)" target="_blank" rel="noopener" class="chat-image-link">
                  <img
                    class="chat-image"
                    :src="imageUrl(m.image)"
                    :alt="m.image.originalName"
                    @load="scrollBottom"
                  />
                </a>
              </div>
              <div v-else class="bubble">{{ m.content }}</div>
            </div>
          </template>
          <div v-if="messages.length === 0" class="empty">
            还没有消息，向在线的伙伴打个招呼吧 👋
          </div>
        </div>

        <div class="composer-glass glass-panel">
          <SpotlightCard class="composer-spotlight" spotlight-color="rgba(255, 255, 255, 0.46)">
            <footer class="composer">
              <p v-if="sendError" class="send-error">{{ sendError }}</p>
              <div class="composer-row">
                <input
                  ref="imageInput"
                  class="visually-hidden"
                  type="file"
                  accept="image/png,image/jpeg,image/gif,image/webp"
                  @change="imageSelected"
                />
                <button
                  class="btn-image"
                  :disabled="!connected || sending || isMuted"
                  @click="chooseImage"
                >
                  {{ sending ? '发送中…' : '图片' }}
                </button>
                <button
                  class="btn-ai"
                  title="在消息开头使用 @AI 提问"
                  :disabled="!connected || sending || isMuted"
                  @click="prepareAiPrompt"
                >
                  @AI
                </button>
                <input
                  ref="messageInput"
                  v-model="draft"
                  maxlength="500"
                  :placeholder="isMuted ? '禁言期间不能发送消息' : '输入消息，或 @AI 提问…'"
                  :disabled="!connected || isMuted"
                  @keyup.enter="send"
                />
                <button
                  class="btn-send"
                  :disabled="!connected || sending || isMuted || !draft.trim()"
                  @click="send"
                >
                  发送
                </button>
              </div>
            </footer>
          </SpotlightCard>
        </div>
      </main>
    </div>

    <ProfileDialog
      v-if="profileOpen"
      :me="me"
      @close="profileOpen = false"
      @updated="applyAccount"
      @change-password="openPasswordDialog"
    />
    <PasswordDialog v-if="passwordOpen" @close="passwordOpen = false" />
    <AdminUsersDialog
      v-if="adminOpen"
      :me="me"
      :online-users="users"
      :refresh-token="accountRevision"
      @close="adminOpen = false"
      @updated="applyAccount"
      @mute="muteUser"
    />
    <AiSettingsDialog v-if="aiSettingsOpen" @close="aiSettingsOpen = false" />
  </div>
</template>
