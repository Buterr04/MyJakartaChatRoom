<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import ConfirmDialog from './ConfirmDialog.vue'
import UserAvatar from './UserAvatar.vue'

const props = defineProps({
  me: { type: Object, required: true },
  onlineUsers: { type: Array, required: true },
  refreshToken: { type: Number, required: true },
  isSuperAdmin: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'updated', 'mute'])

const accounts = ref([])
const loading = ref(true)
const savingUsername = ref('')
const error = ref('')
const roleConfirm = ref(null)
const onlineNames = computed(() => new Set(props.onlineUsers.map((user) => user.username.toLowerCase())))
const visibleAccounts = computed(() => accounts.value.filter((account) => {
  const username = account.username.toLowerCase()
  return username !== 'admin' && username !== props.me.username.toLowerCase()
}))

onMounted(loadAccounts)
watch(() => props.refreshToken, loadAccounts)

async function loadAccounts() {
  error.value = ''
  try {
    const res = await fetch('/api/admin/users')
    const data = await res.json().catch(() => ({}))
    if (!res.ok) {
      error.value = data.error || '用户列表加载失败'
      return
    }
    accounts.value = data.users
  } catch {
    error.value = '网络异常，请稍后再试'
  } finally {
    loading.value = false
  }
}

function changeRole(account) {
  if (roleChangeDisabled(account)) return
  const nextRole = account.role === 'ADMIN' ? 'USER' : 'ADMIN'
  roleConfirm.value = { account, nextRole }
}

async function confirmChangeRole() {
  const pending = roleConfirm.value
  roleConfirm.value = null
  if (!pending) return
  const { account, nextRole } = pending
  savingUsername.value = account.username
  error.value = ''
  try {
    const res = await fetch(`/api/admin/users/${encodeURIComponent(account.username)}/role`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ role: nextRole }),
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) {
      error.value = data.error || '角色更新失败，请稍后再试'
      return
    }
    replaceAccount(data.account)
    emit('updated', data.account)
  } catch {
    error.value = '网络异常，请稍后再试'
  } finally {
    savingUsername.value = ''
  }
}

function replaceAccount(account) {
  accounts.value = accounts.value.map((item) => (
    item.username.toLowerCase() === account.username.toLowerCase() ? { ...item, ...account } : item
  ))
}

function roleChangeDisabled(account) {
  return savingUsername.value === account.username
    || account.username.toLowerCase() === 'admin'
    || account.username.toLowerCase() === props.me.username.toLowerCase()
}

function canMute(account) {
  if (account.username.toLowerCase() === props.me.username.toLowerCase()) return false
  if (account.username.toLowerCase() === 'admin') return false
  if (account.role === 'USER') return true
  return props.isSuperAdmin
}
</script>

<template>
  <div class="dialog-backdrop" @click.self="emit('close')">
    <section class="dialog admin-dialog" role="dialog" aria-modal="true" aria-labelledby="admin-title">
      <button class="dialog-close" aria-label="关闭" @click="emit('close')">×</button>
      <div class="admin-heading">
        <div>
          <h2 id="admin-title">用户管理</h2>
          <p v-if="isSuperAdmin" class="dialog-subtitle">设置聊天室管理员，或禁言普通用户</p>
          <p v-else class="dialog-subtitle">可禁言普通用户</p>
        </div>
        <span class="count">{{ visibleAccounts.length }} 个账户</span>
      </div>

      <p v-if="error" class="form-error">{{ error }}</p>
      <div v-if="loading" class="admin-empty">正在加载用户…</div>
      <div v-else class="account-list">
        <article v-for="account in visibleAccounts" :key="account.username" class="account-row">
          <UserAvatar :user="account" />
          <div class="account-info">
            <div class="account-name">
              <strong>{{ account.displayName }}</strong>
              <span v-if="onlineNames.has(account.username.toLowerCase())" class="online-dot">在线</span>
            </div>
            <span>@{{ account.username }}</span>
          </div>
          <span class="role-chip" :class="{ 'user-role': account.role !== 'ADMIN' }">
            {{ account.role === 'ADMIN' ? '管理员' : '用户' }}
          </span>
          <div class="account-actions">
            <button
              v-if="isSuperAdmin"
              class="btn-secondary"
              :disabled="roleChangeDisabled(account)"
              :title="account.username.toLowerCase() === 'admin' ? '默认管理员不可取消权限' : ''"
              @click="changeRole(account)"
            >
              {{ account.role === 'ADMIN' ? '取消管理员' : '任命为管理员' }}
            </button>
            <button
              v-if="canMute(account)"
              class="btn-secondary"
              @click="emit('mute', account)"
            >
              {{ Number(account.mutedUntil || 0) > Date.now() ? '解除禁言' : '禁言' }}
            </button>
          </div>
        </article>
      </div>
    </section>
    <ConfirmDialog
      v-if="roleConfirm"
      title="角色变更"
      :message="`确定将 ${roleConfirm.account.displayName} ${roleConfirm.nextRole === 'ADMIN' ? '任命为聊天室管理员' : '取消管理员权限'}吗？`"
      :confirm-text="roleConfirm.nextRole === 'ADMIN' ? '任命' : '取消权限'"
      :danger="roleConfirm.nextRole !== 'ADMIN'"
      @confirm="confirmChangeRole"
      @close="roleConfirm = null"
    />
  </div>
</template>
