<script setup>
import { computed, onMounted, reactive, ref } from 'vue'

const emit = defineEmits(['close'])

const form = reactive({
  baseUrl: '',
  model: '',
  apiKey: '',
  systemPrompt: '',
  enabled: false,
  clearApiKey: false,
})
const loading = ref(true)
const saving = ref(false)
const testing = ref(false)
const hasApiKey = ref(false)
const error = ref('')
const success = ref('')
const busy = computed(() => loading.value || saving.value || testing.value)

onMounted(loadSettings)

async function loadSettings() {
  loading.value = true
  error.value = ''
  try {
    const res = await fetch('/api/super-admin/ai/settings')
    const data = await res.json().catch(() => ({}))
    if (!res.ok) {
      error.value = data.error || 'AI 配置加载失败'
      return
    }
    applySettings(data)
  } catch {
    error.value = '网络异常，请稍后再试'
  } finally {
    loading.value = false
  }
}

function applySettings(settings) {
  form.baseUrl = settings.baseUrl
  form.model = settings.model
  form.systemPrompt = settings.systemPrompt
  form.enabled = settings.enabled
  form.apiKey = ''
  form.clearApiKey = false
  hasApiKey.value = settings.hasApiKey
}

async function save() {
  if (busy.value) return
  saving.value = true
  error.value = ''
  success.value = ''
  try {
    const res = await fetch('/api/super-admin/ai/settings', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(form),
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) {
      error.value = data.error || 'AI 配置保存失败'
      return
    }
    applySettings(data)
    success.value = '配置已保存，测试连接会使用这份已保存的配置'
  } catch {
    error.value = '网络异常，请稍后再试'
  } finally {
    saving.value = false
  }
}

async function testConnection() {
  if (busy.value) return
  testing.value = true
  error.value = ''
  success.value = ''
  try {
    const res = await fetch('/api/super-admin/ai/settings/test', { method: 'POST' })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) {
      error.value = data.error || '连接测试失败'
      return
    }
    success.value = data.reply ? `连接成功，AI 回复：${data.reply}` : '连接成功'
  } catch {
    error.value = '网络异常，请稍后再试'
  } finally {
    testing.value = false
  }
}
</script>

<template>
  <div class="dialog-backdrop" @click.self="emit('close')">
    <section class="dialog ai-settings-dialog" role="dialog" aria-modal="true" aria-labelledby="ai-settings-title">
      <button class="dialog-close" aria-label="关闭" @click="emit('close')">×</button>
      <h2 id="ai-settings-title">AI 助手设置</h2>
      <p class="dialog-subtitle">仅超级管理员 Admin 可维护，密钥不会返回到浏览器</p>

      <form class="ai-settings-form" autocomplete="off" @submit.prevent="save">
        <label>
          <span>接口地址</span>
          <input
            v-model="form.baseUrl"
            :disabled="busy"
            maxlength="500"
            placeholder="https://api.openai.com/v1"
            required
          />
          <small>支持兼容 chat/completions 的服务，可填写到 /v1 或完整接口地址。</small>
        </label>
        <label>
          <span>模型名称</span>
          <input
            v-model="form.model"
            :disabled="busy"
            maxlength="100"
            placeholder="例如 gpt-4.1-mini"
            required
          />
        </label>
        <label>
          <span>API Key <i v-if="hasApiKey" class="key-status">已保存</i></span>
          <input
            v-model="form.apiKey"
            :disabled="busy || form.clearApiKey"
            type="password"
            maxlength="1000"
            autocomplete="new-password"
            :placeholder="hasApiKey ? '留空表示保持原密钥' : '本地无认证接口可留空'"
          />
        </label>
        <label v-if="hasApiKey" class="check-row danger-check">
          <input v-model="form.clearApiKey" :disabled="busy" type="checkbox" />
          <span>清除已保存的 API Key</span>
        </label>
        <label>
          <span>系统提示词</span>
          <textarea
            v-model="form.systemPrompt"
            :disabled="busy"
            maxlength="4000"
            rows="4"
            placeholder="设定 AI 助手的回答风格"
          ></textarea>
        </label>
        <label class="check-row">
          <input v-model="form.enabled" :disabled="busy" type="checkbox" />
          <span>启用 AI 助手（用户在消息开头输入 @AI 即可提问）</span>
        </label>

        <p v-if="error" class="form-error">{{ error }}</p>
        <p v-else-if="success" class="form-success">{{ success }}</p>
        <div class="ai-settings-actions">
          <button class="btn-secondary" type="button" :disabled="busy" @click="testConnection">
            {{ testing ? '测试中…' : '测试已保存配置' }}
          </button>
          <button class="btn-primary" type="submit" :disabled="busy">
            {{ saving ? '保存中…' : '保存配置' }}
          </button>
        </div>
      </form>
    </section>
  </div>
</template>
