<script setup>
import { ref } from 'vue'

const props = defineProps({
  // 父组件传入的登录函数，失败时抛出错误信息
  login: { type: Function, required: true },
})

const username = ref('')
const error = ref('')
const loading = ref(false)

async function submit() {
  if (loading.value) return
  error.value = ''
  const name = username.value.trim()
  if (!name) {
    error.value = '请输入用户名'
    return
  }
  if (name.length < 2 || name.length > 20) {
    error.value = '用户名长度需要在 2~20 个字符之间'
    return
  }
  loading.value = true
  try {
    await props.login(name)
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
      <h1>Final 聊天室</h1>
      <p class="sub">Jakarta EE 课程收官之作 · 实时聊天</p>

      <div class="field">
        <input
          v-model="username"
          maxlength="20"
          placeholder="输入你的用户名"
          autocomplete="off"
          autofocus
        />
        <button type="submit" :disabled="loading">{{ loading ? '进入中…' : '进入' }}</button>
      </div>

      <p class="form-error">{{ error }}</p>
      <p class="login-hint">用户名 2~20 个字符，进入后即可与在线的伙伴实时聊天</p>
    </form>
  </div>
</template>
