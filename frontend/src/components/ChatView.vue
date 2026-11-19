<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import AdminUsersDialog from './AdminUsersDialog.vue'
import AiSettingsDialog from './AiSettingsDialog.vue'
import ConfirmDialog from './ConfirmDialog.vue'
import PasswordDialog from './PasswordDialog.vue'
import ProfileDialog from './ProfileDialog.vue'
import PromptDialog from './PromptDialog.vue'
import RoomIcon from './RoomIcon.vue'
import RoomSettingsDialog from './RoomSettingsDialog.vue'
import SensitiveWordsDialog from './SensitiveWordsDialog.vue'
import SpotlightCard from './SpotlightCard.vue'
import StatisticsDialog from './StatisticsDialog.vue'
import UserAvatar from './UserAvatar.vue'

const props = defineProps({
  me: { type: Object, required: true },
})
const emit = defineEmits(['logout', 'account-updated'])

const messages = ref([])
const users = ref([])
const avatarByUsername = ref({})
const room = ref({ name: '聊天室', icon: '💬' })
const aiEnabled = ref(false)
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
const sensitiveOpen = ref(false)
const statsOpen = ref(false)
const roomSettingsOpen = ref(false)
const aiSettingsOpen = ref(false)
const accountRevision = ref(0)
const confirmState = ref(null)
const muteState = ref(null)
const emojiOpen = ref(false)
const EMOJIS = [
  '😀', '😄', '😁', '😂', '😊', '😍', '🥰', '😘',
  '😎', '🤔', '😅', '😭', '😡', '😴', '🤯', '🥳',
  '😇', '🤗', '🤩', '😳', '😱', '🙃', '😉', '😌',
  '👍', '👎', '👏', '🙌', '🤝', '🙏', '💪', '👌',
  '✌️', '🤞', '🫶', '❤️', '🧡', '💛', '💚', '💙',
  '💜', '🖤', '🤍', '💔', '💕', '💖', '✨', '🔥',
  '🎉', '🎊', '🌹', '🌸', '🍀', '☀️', '🌈', '⭐',
  '🍔', '🍰', '🎮', '🎵', '🚀', '🐱', '🐶', '🐼',
]
const listEl = ref(null)
const imageInput = ref(null)
const messageInput = ref(null)
const hasMoreOlder = ref(false)
const loadingOlder = ref(false)
const historyLoaded = ref(false)
const deletedMessageIds = new Set()
let historyCursor = 0
const PAGE_SIZE = 50
const isAdmin = computed(() => props.me.role === 'ADMIN')
const isSuperAdmin = computed(() => isAdmin.value && props.me.username.toLowerCase() === 'admin')
const isMuted = computed(() => Number(props.me.mutedUntil || 0) > now.value)
const visibleUsers = computed(() => (
  isSuperAdmin.value
    ? users.value
    : users.value.filter((user) => user.username.toLowerCase() !== 'admin')
))
const visibleOnlineCount = computed(() => visibleUsers.value.length)

function canMuteUser(user) {
  if (!isAdmin.value || !user || user.username === props.me.username) return false
  if (user.role === 'USER') return true
  return isSuperAdmin.value && user.username.toLowerCase() !== 'admin'
}

let ws = null
let reconnectTimer = null
let clockTimer = null
let retries = 0
// 主动退出/被请出时置位，阻止自动重连
let leaving = false

onMounted(() => {
  connectWs()
  loadRoom()
  loadAiStatus()
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
  const socket = new WebSocket(wsUrl())
  ws = socket

  socket.onopen = async () => {
    connected.value = true
    retries = 0
    // 先建立 WS 再拉历史，两条消息之间没有空窗；重连时只补 lastId 之后的消息
    await Promise.all([syncHistory(), loadUsers()])
  }

  socket.onmessage = (event) => {
    const packet = JSON.parse(event.data)
    if (packet.type === 'message') {
      appendMessage(packet.data)
    } else if (packet.type === 'messageDeleted') {
      removeMessage(packet.data.id)
    } else if (packet.type === 'accountUpdated') {
      applyAccount(packet.data)
    } else if (packet.type === 'roomUpdated') {
      applyRoom(packet.data)
    } else if (packet.type === 'aiStatus') {
      aiEnabled.value = Boolean(packet.data?.enabled)
    } else if (packet.type === 'online') {
      users.value = packet.data.users
      onlineCount.value = packet.data.count
      cacheUserAvatars(packet.data.users)
    }
    // pong：保活心跳，无需处理
  }

  socket.onclose = async (event) => {
    if (ws !== socket) return
    connected.value = false
    // Intentional logout already handled by logout(); do not query the session again.
    if (leaving) return
    // 1008 = 服务端拒绝（未登录/会话过期），回到登录页而不是无限重连
    if (event.code === 1008) {
      leaving = true
      emit('logout', event.reason || '连接已被服务端关闭，请重新登录')
      return
    }
    // Tomcat/browser combinations may expose an on-open policy rejection as
    // 1006 with no reason. Ask the authenticated HTTP session for its reason.
    const rejection = await webSocketRejection()
    if (rejection) {
      leaving = true
      emit('logout', rejection)
      return
    }
    if (!leaving) {
      retries += 1
      const delay = Math.min(1000 * retries, 5000)
      reconnectTimer = setTimeout(connectWs, delay)
    }
  }

  socket.onerror = () => socket.close()
}

async function webSocketRejection() {
  try {
    const res = await fetch('/api/websocket-status')
    if (res.status !== 409 && res.status !== 401) return ''
    const data = await res.json().catch(() => ({}))
    return data.error || '连接已被服务端关闭，请重新登录'
  } catch {
    return ''
  }
}

function mergeMessages(...lists) {
  const byId = new Map()
  for (const list of lists) {
    for (const message of list) {
      if (!deletedMessageIds.has(message.id)) byId.set(message.id, message)
    }
  }
  return [...byId.values()].sort((left, right) => left.id - right.id)
}

function historyMessages(list) {
  return (list || []).filter((message) => message.type !== 'system' && !deletedMessageIds.has(message.id))
}

async function loadInitialHistory() {
  if (loadingOlder.value) return
  loadingOlder.value = true
  const el = listEl.value
  const previousHeight = el ? el.scrollHeight : 0
  const hadMessages = messages.value.length > 0
  try {
    const res = await fetch(`/api/messages?limit=${PAGE_SIZE}`)
    if (!res.ok) return
    const data = await res.json()
    const rows = data.messages || []
    historyCursor = rows.length ? rows[0].id : 0
    // 历史记录只显示聊天消息；system 消息只在在线时通过 WS 实时显示。
    messages.value = mergeMessages(historyMessages(rows), messages.value)
    hasMoreOlder.value = Boolean(data.hasMore)
    historyLoaded.value = true
    await loadAvatars(rows)
    await nextTick()
    if (hadMessages) {
      if (el) el.scrollTop = el.scrollHeight - previousHeight
    } else {
      scrollBottom()
    }
  } finally {
    loadingOlder.value = false
  }
}

/** Reconnect catch-up: only after the user has explicitly loaded history. */
async function syncHistory() {
  if (!historyLoaded.value) return
  if (messages.value.length === 0) {
    await loadInitialHistory()
    return
  }
  let after = messages.value[messages.value.length - 1].id
  for (let page = 0; page < 10; page += 1) {
    const res = await fetch(`/api/messages?after=${after}&limit=200`)
    if (!res.ok) break
    const data = await res.json()
    const rows = data.messages || []
    if (rows.length === 0) break
    const incoming = historyMessages(rows)
    if (incoming.length > 0) messages.value = mergeMessages(messages.value, incoming)
    await loadAvatars(rows)
    // 用原始行推进游标，避免整页都是 system 消息时提前停止。
    after = rows[rows.length - 1].id
    if (!data.hasMore) break
  }
  scrollBottom()
}

async function loadOlder() {
  if (!historyLoaded.value) {
    await loadInitialHistory()
    return
  }
  if (loadingOlder.value || !hasMoreOlder.value) return
  const before = historyCursor || (messages.value.length ? messages.value[0].id : 0)
  if (!before) return
  loadingOlder.value = true
  const el = listEl.value
  const previousHeight = el ? el.scrollHeight : 0
  try {
    const res = await fetch(`/api/messages?before=${before}&limit=${PAGE_SIZE}`)
    if (!res.ok) return
    const data = await res.json()
    const rows = data.messages || []
    historyCursor = rows.length ? rows[0].id : before
    messages.value = mergeMessages(historyMessages(rows), messages.value)
    hasMoreOlder.value = Boolean(data.hasMore)
    await loadAvatars(rows)
    await nextTick()
    if (el) el.scrollTop = el.scrollHeight - previousHeight
  } finally {
    loadingOlder.value = false
  }
}

function onScroll() {
  const el = listEl.value
  if (!el || el.scrollTop >= 40) return
  // Initial history is explicit (wheel/touch/button); scroll only pages older history.
  if (historyLoaded.value) loadOlder()
}

let touchStartY = 0

function onWheel(event) {
  const el = listEl.value
  if (el && el.scrollTop > 0) return
  if (!historyLoaded.value) {
    loadInitialHistory()
  } else if (hasMoreOlder.value) {
    loadOlder()
  }
}

function loadHistory() {
  if (loadingOlder.value) return
  if (!historyLoaded.value) {
    loadInitialHistory()
  } else if (hasMoreOlder.value) {
    loadOlder()
  }
}

function onTouchStart(event) {
  touchStartY = event.touches[0]?.clientY ?? 0
}

function onTouchMove(event) {
  const el = listEl.value
  if (el && el.scrollTop > 0) return
  const currentY = event.touches[0]?.clientY ?? touchStartY
  // 聊天室常见两种手势：手指下滑（拉出旧消息）和上滑（列表到顶后继续滑）。
  if (Math.abs(currentY - touchStartY) <= 30) return
  if (!historyLoaded.value) {
    loadInitialHistory()
  } else if (hasMoreOlder.value) {
    loadOlder()
  }
}

async function loadUsers() {
  const res = await fetch('/api/users')
  if (res.ok) {
    const data = await res.json()
    users.value = data.users
    onlineCount.value = data.count
    cacheUserAvatars(data.users)
  }
}

function avatarKey(username) {
  return String(username || '').toLowerCase()
}

function cacheAvatar(username, avatar) {
  if (!username) return
  avatarByUsername.value = {
    ...avatarByUsername.value,
    [avatarKey(username)]: avatar || null,
  }
}

function cacheUserAvatars(list) {
  for (const user of list || []) {
    cacheAvatar(user.username, user.avatar)
  }
}

function messageAvatar(message) {
  return {
    username: message.username,
    displayName: message.displayName,
    avatar: avatarByUsername.value[avatarKey(message.username)] ?? null,
  }
}

async function loadAvatars(list) {
  const names = []
  const seen = new Set()
  for (const message of list || []) {
    if (!message.username) continue
    const key = avatarKey(message.username)
    if (!seen.has(key) && !(key in avatarByUsername.value)) {
      seen.add(key)
      names.push(message.username)
    }
  }
  if (names.length === 0) return

  try {
    const res = await fetch(`/api/avatars?usernames=${encodeURIComponent(names.join(','))}`)
    if (!res.ok) return
    const data = await res.json()
    const avatars = data.avatars || {}
    const keys = Object.keys(avatars)
    const next = { ...avatarByUsername.value }
    for (const name of names) {
      const key = avatarKey(name)
      const matched = keys.find((candidate) => avatarKey(candidate) === key)
      next[key] = matched ? avatars[matched] : null
    }
    avatarByUsername.value = next
  } catch {
    // Fall back to the initial-letter avatar; history remains usable.
  }
}

async function loadRoom() {
  try {
    const res = await fetch('/api/room')
    if (!res.ok) return
    const data = await res.json()
    applyRoom(data.room)
  } catch {
    // Keep the default branding; the chat itself is unaffected.
  }
}

async function loadAiStatus() {
  try {
    const res = await fetch('/api/ai/status')
    if (!res.ok) return
    const data = await res.json()
    aiEnabled.value = Boolean(data.enabled)
  } catch {
    aiEnabled.value = false
  }
}

function applyRoom(branding) {
  if (!branding) return
  room.value = {
    name: branding.name || '聊天室',
    icon: branding.icon || '💬',
  }
  document.title = room.value.name
}

function appendMessage(message) {
  // WS 重连后与 REST 拉取可能重叠，按消息 id 去重
  if (deletedMessageIds.has(message.id) || messages.value.some((m) => m.id === message.id)) return
  const el = listEl.value
  const stickToBottom = !el || el.scrollHeight - el.scrollTop - el.clientHeight < 120
  messages.value.push(message)
  if (stickToBottom) scrollBottom()
}

function removeMessage(id) {
  deletedMessageIds.add(id)
  messages.value = messages.value.filter((message) => message.id !== id)
}

function applyAccount(account) {
  accountRevision.value += 1
  cacheAvatar(account.username, account.avatar)
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
  emojiOpen.value = false
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
  emojiOpen.value = false
  sendError.value = ''
  imageInput.value?.click()
}

function insertEmoji(emoji) {
  const el = messageInput.value
  const start = el?.selectionStart ?? draft.value.length
  const end = el?.selectionEnd ?? start
  const next = draft.value.slice(0, start) + emoji + draft.value.slice(end)
  draft.value = next.slice(0, 500)
  nextTick(() => {
    if (!el) return
    el.focus()
    const position = Math.min(start + emoji.length, draft.value.length)
    el.setSelectionRange(position, position)
  })
}

function prepareAiPrompt() {
  if (!aiEnabled.value || sending.value || !connected.value || isMuted.value) return
  emojiOpen.value = false
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

function deleteMessage(message) {
  if (!isSuperAdmin.value) return
  confirmState.value = {
    title: '删除消息',
    message: '确定删除这条消息吗？删除后所有人都看不到。',
    confirmText: '删除',
    messageId: message.id,
  }
}

async function confirmDeleteMessage() {
  const messageId = confirmState.value?.messageId
  confirmState.value = null
  if (!messageId) return
  actionError.value = ''
  try {
    const res = await fetch(`/api/super-admin/messages/${messageId}`, { method: 'DELETE' })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) {
      actionError.value = data.error || '删除失败，请稍后再试'
      return
    }
    removeMessage(messageId)
  } catch {
    actionError.value = '网络异常，请稍后再试'
  }
}

function muteUser(user) {
  if (!canMuteUser(user)) return
  if (isUserMuted(user)) {
    void applyMute(user, 0)
    return
  }
  muteState.value = {
    user,
    title: '禁言用户',
    minutes: '10',
  }
}

async function confirmMute(minutes) {
  const user = muteState.value?.user
  muteState.value = null
  if (!user) return
  await applyMute(user, minutes)
}

async function applyMute(user, minutes) {
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
  if (leaving) return
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
          <div class="brand"><RoomIcon :icon="room.icon" class="logo" /><span class="brand-name">{{ room.name }}</span></div>
          <div class="header-right">
            <span class="conn" :class="connected ? 'on' : 'off'">
              {{ connected ? '已连接' : '连接中…' }}
            </span>
            <span v-if="isSuperAdmin" class="role-chip">超级管理员</span>
            <span v-else-if="isAdmin" class="role-chip">聊天室管理员</span>
            <button v-if="isSuperAdmin" class="btn-manage" @click="aiSettingsOpen = true">AI 设置</button>
            <button v-if="isAdmin" class="btn-manage" @click="adminOpen = true">用户管理</button>
            <button v-if="isAdmin" class="btn-manage" @click="roomSettingsOpen = true">房间设置</button>
            <button v-if="isSuperAdmin" class="btn-manage" @click="statsOpen = true">统计</button>
            <button v-if="isSuperAdmin" class="btn-manage" @click="sensitiveOpen = true">敏感词</button>
            <button class="profile-trigger" title="打开个人主页" @click="profileOpen = true">
              <UserAvatar :user="me" />
              <span>{{ me.displayName }}</span>
            </button>
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
              在线用户 <span class="count">{{ visibleOnlineCount }}</span>
            </div>
            <ul>
              <li v-for="u in visibleUsers" :key="u.username">
                <UserAvatar :user="u" :class="{ self: u.username === me.username }" />
                <span class="name">
                  {{ u.displayName }}<i v-if="u.username === me.username">（我）</i>
                  <i
                    v-if="u.role === 'ADMIN' && u.username.toLowerCase() === 'admin'"
                    class="role-mark super-admin-mark"
                    title="超级管理员"
                    aria-label="超级管理员"
                  >
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <path d="M3 18h18l-1.7-9-4.6 3.2L12 6l-2.7 6.2L4.7 9 3 18Z" />
                      <path d="M5 20h14" />
                    </svg>
                  </i>
                  <i
                    v-else-if="u.role === 'ADMIN'"
                    class="role-mark admin-mark"
                    title="管理员"
                    aria-label="管理员"
                  >
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <path d="M12 3 19 6v5c0 4.4-3 8.2-7 10-4-1.8-7-5.6-7-10V6l7-3Z" />
                      <path d="m9 12 2 2 4-4" />
                    </svg>
                  </i>
                  <i
                    v-if="isUserMuted(u)"
                    class="role-mark mute-mark"
                    title="已禁言"
                    aria-label="已禁言"
                  >
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <path d="M4 9v6h4l5 4V5L8 9H4Z" />
                      <path d="m16 9 5 6M21 9l-5 6" />
                    </svg>
                  </i>
                </span>
                <button
                  v-if="canMuteUser(u)"
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
        <div
          ref="listEl"
          class="msg-list"
          @scroll="onScroll"
          @wheel="onWheel"
          @touchstart.passive="onTouchStart"
          @touchmove.passive="onTouchMove"
        >
          <div class="load-older" @click="loadHistory">
            <span v-if="loadingOlder" class="history-note">加载中…</span>
            <span v-else-if="historyLoaded && !hasMoreOlder && messages.length > 0" class="history-note">
              没有更多消息
            </span>
            <span v-else class="history-note">
              <span class="hint-touch">下滑加载更多消息</span>
              <span class="hint-mouse">向下滚动加载更多消息</span>
            </span>
          </div>
          <template v-for="m in messages" :key="m.id">
            <div v-if="m.type === 'system'" class="msg sys">
              <span class="sys-pill">{{ m.content }}</span>
            </div>
            <div v-else class="msg" :class="[isOwn(m) ? 'own' : 'other', { ai: isAi(m) }]">
              <div class="meta">
                <UserAvatar v-if="m.username" :user="messageAvatar(m)" class="msg-avatar" />
                <span class="who">{{ m.displayName }}</span>
                <span class="time">{{ formatTime(m.createdAt) }}</span>
                <button v-if="isSuperAdmin" class="msg-action" @click="deleteMessage(m)">删除</button>
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
          <div v-if="messages.length === 0 && historyLoaded" class="empty">
            还没有消息，向在线的伙伴打个招呼吧 👋
          </div>
        </div>

        <div class="composer-glass glass-panel">
          <SpotlightCard class="composer-spotlight" spotlight-color="rgba(255, 255, 255, 0.46)">
            <footer class="composer">
              <p v-if="sendError" class="send-error">{{ sendError }}</p>
              <div v-if="emojiOpen" class="emoji-panel" @keydown.esc="emojiOpen = false">
                <div class="emoji-panel-head">
                  <span>表情</span>
                  <button type="button" aria-label="关闭表情" @click="emojiOpen = false">×</button>
                </div>
                <div class="emoji-grid">
                  <button
                    v-for="(emoji, index) in EMOJIS"
                    :key="index"
                    type="button"
                    :aria-label="`表情 ${emoji}`"
                    @click="insertEmoji(emoji)"
                  >{{ emoji }}</button>
                </div>
              </div>
              <div class="composer-row">
                <input
                  ref="imageInput"
                  class="visually-hidden"
                  type="file"
                  accept="image/png,image/jpeg,image/gif,image/webp"
                  @change="imageSelected"
                />
                <button
                  class="btn-emoji"
                  title="表情"
                  aria-label="打开表情"
                  :disabled="!connected || sending || isMuted"
                  @click="emojiOpen = !emojiOpen"
                >
                  😊
                </button>
                <button
                  class="btn-image"
                  :disabled="!connected || sending || isMuted"
                  @click="chooseImage"
                >
                  {{ sending ? '发送中…' : '图片' }}
                </button>
                <button
                  v-if="aiEnabled"
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
      @logout="logout"
    />
    <PasswordDialog v-if="passwordOpen" @close="passwordOpen = false" />
    <AdminUsersDialog
      v-if="adminOpen"
      :me="me"
      :online-users="users"
      :refresh-token="accountRevision"
      :is-super-admin="isSuperAdmin"
      @close="adminOpen = false"
      @updated="applyAccount"
      @mute="muteUser"
    />
    <AiSettingsDialog v-if="aiSettingsOpen" @close="aiSettingsOpen = false" />
    <SensitiveWordsDialog v-if="sensitiveOpen" @close="sensitiveOpen = false" />
    <StatisticsDialog v-if="statsOpen" @close="statsOpen = false" />
    <RoomSettingsDialog
      v-if="roomSettingsOpen"
      :room="room"
      @close="roomSettingsOpen = false"
      @saved="applyRoom"
    />
    <ConfirmDialog
      v-if="confirmState"
      :title="confirmState.title"
      :message="confirmState.message"
      :confirm-text="confirmState.confirmText"
      danger
      @confirm="confirmDeleteMessage"
      @close="confirmState = null"
    />
    <PromptDialog
      v-if="muteState"
      :title="muteState.title"
      :message="`${muteState.user.displayName}：设置禁言时长`"
      label="分钟（0 表示解禁，范围 0~10080）"
      :default-value="muteState.minutes"
      :min="0"
      :max="10080"
      confirm-text="确定"
      @confirm="confirmMute"
      @close="muteState = null"
    />
  </div>
</template>
