<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import UserAvatar from './UserAvatar.vue'

const props = defineProps({
  me: { type: Object, required: true },
  onlineUsers: { type: Array, required: true },
  refreshToken: { type: Number, required: true },
})
const emit = defineEmits(['close', 'updated', 'mute'])

const accounts = ref([])
const loading = ref(true)
const savingUsername = ref('')
const error = ref('')
const onlineNames = computed(() => new Set(props.onlineUsers.map((user) => user.username.toLowerCase())))

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

async function changeRole(account) {
  const nextRole = account.role === 'ADMIN' ? 'USER' : 'ADMIN'
  const action = nextRole === 'ADMIN' ? '任命为管理员' : '取消管理员权限'
  if (!window.confirm(`确定将 ${account.displayName} ${action}吗？`)) return
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
</script>

<template>
  <div class="dialog-backdrop" @click.self="emit('close')">
    <section class="dialog admin-dialog" role="dialog" aria-modal="true" aria-labelledby="admin-title">
      <button class="dialog-close" aria-label="关闭" @click="emit('close')">×</button>
      <div class="admin-heading">
        <div>
          <h2 id="admin-title">用户管理</h2>
          <p class="dialog-subtitle">管理全部注册账户的角色与禁言状态</p>
        </div>
        <span class="count">{{ accounts.length }} 个账户</span>
      </div>

      <p v-if="error" class="form-error">{{ error }}</p>
      <div v-if="loading" class="admin-empty">正在加载用户…</div>
      <div v-else class="account-list">
        <article v-for="account in accounts" :key="account.username" class="account-row">
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
              class="btn-secondary"
              :disabled="roleChangeDisabled(account)"
              :title="account.username.toLowerCase() === 'admin' ? '默认管理员不可取消权限' : ''"
              @click="changeRole(account)"
            >
              {{ account.role === 'ADMIN' ? '取消管理员' : '任命为管理员' }}
            </button>
            <button
              class="btn-secondary"
              :disabled="account.username.toLowerCase() === me.username.toLowerCase()"
              @click="emit('mute', account)"
            >
              {{ Number(account.mutedUntil || 0) > Date.now() ? '解除禁言' : '禁言' }}
            </button>
          </div>
        </article>
      </div>
      <p class="admin-note">默认管理员 Admin 与当前登录账户不能被取消管理员权限。</p>
    </section>
  </div>
</template>
