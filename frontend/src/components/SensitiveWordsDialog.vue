<script setup>
import { onMounted, ref } from 'vue'
import ConfirmDialog from './ConfirmDialog.vue'

const emit = defineEmits(['close'])

const words = ref([])
const newWord = ref('')
const loading = ref(true)
const saving = ref(false)
const removing = ref('')
const deleteConfirm = ref('')
const error = ref('')
const notice = ref('')

onMounted(loadWords)

async function loadWords() {
  error.value = ''
  try {
    const res = await fetch('/api/super-admin/sensitive-words')
    const data = await res.json().catch(() => ({}))
    if (!res.ok) {
      error.value = data.error || '敏感词加载失败'
      return
    }
    words.value = data.words || []
  } catch {
    error.value = '网络异常，请稍后再试'
  } finally {
    loading.value = false
  }
}

async function addWord() {
  const word = newWord.value.trim()
  if (!word || saving.value) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const res = await fetch('/api/super-admin/sensitive-words', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ word }),
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) {
      error.value = data.error || '添加失败'
      return
    }
    words.value = data.words || words.value
    newWord.value = ''
    notice.value = `已添加「${word}」`
  } catch {
    error.value = '网络异常，请稍后再试'
  } finally {
    saving.value = false
  }
}

function removeWord(word) {
  if (removing.value) return
  deleteConfirm.value = word
}

async function confirmRemoveWord() {
  const word = deleteConfirm.value
  deleteConfirm.value = ''
  if (!word) return
  removing.value = word
  error.value = ''
  notice.value = ''
  try {
    const res = await fetch(`/api/super-admin/sensitive-words?word=${encodeURIComponent(word)}`, {
      method: 'DELETE',
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) {
      error.value = data.error || '删除失败'
      return
    }
    words.value = data.words || words.value
    notice.value = `已删除「${word}」`
  } catch {
    error.value = '网络异常，请稍后再试'
  } finally {
    removing.value = ''
  }
}
</script>

<template>
  <div class="dialog-backdrop" @click.self="emit('close')">
    <section class="dialog admin-dialog sensitive-dialog" role="dialog" aria-modal="true" aria-labelledby="sensitive-title">
      <button class="dialog-close" aria-label="关闭" @click="emit('close')">×</button>
      <div class="admin-heading">
        <div>
          <h2 id="sensitive-title">敏感词管理</h2>
          <p class="dialog-subtitle">修改后立即生效，历史消息不会被重新过滤</p>
        </div>
        <span class="count">{{ words.length }} 个词</span>
      </div>

      <form class="sensitive-add" @submit.prevent="addWord">
        <input
          v-model="newWord"
          maxlength="50"
          placeholder="输入敏感词，例如：广告词"
          aria-label="新增敏感词"
        />
        <button class="btn-primary" type="submit" :disabled="saving || !newWord.trim()">
          {{ saving ? '添加中…' : '添加' }}
        </button>
      </form>

      <p v-if="error" class="form-error">{{ error }}</p>
      <p v-else-if="notice" class="form-success">{{ notice }}</p>

      <div v-if="loading" class="admin-empty">正在加载敏感词…</div>
      <div v-else-if="words.length === 0" class="admin-empty">还没有敏感词，添加后立即生效。</div>
      <div v-else class="word-list">
        <span v-for="word in words" :key="word" class="word-chip">
          {{ word }}
          <button
            type="button"
            class="word-remove"
            :disabled="removing === word"
            :aria-label="`删除 ${word}`"
            @click="removeWord(word)"
          >
            ×
          </button>
        </span>
      </div>

      <p class="admin-note">敏感词会按大小写、全角和常见分隔符归一化后匹配。</p>
    </section>
    <ConfirmDialog
      v-if="deleteConfirm"
      title="删除敏感词"
      :message="`确定删除敏感词「${deleteConfirm}」吗？`"
      confirm-text="删除"
      danger
      @confirm="confirmRemoveWord"
      @close="deleteConfirm = ''"
    />
  </div>
</template>
