<script setup>
import { ref } from 'vue'
import RoomIcon from './RoomIcon.vue'

const props = defineProps({
  room: { type: Object, required: true },
})
const emit = defineEmits(['close', 'saved'])

const name = ref(props.room.name || '聊天室')
const icon = ref(props.room.icon || '💬')
const fileInput = ref(null)
const saving = ref(false)
const error = ref('')
const MAX_ICON_BYTES = 1024 * 1024

function chooseIcon() {
  fileInput.value?.click()
}

function iconSelected(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!['image/png', 'image/jpeg', 'image/gif', 'image/webp'].includes(file.type)) {
    error.value = '只支持 PNG、JPEG、GIF、WebP'
    return
  }
  if (file.size > MAX_ICON_BYTES) {
    error.value = '图标不能超过 1MB'
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    icon.value = String(reader.result)
    error.value = ''
  }
  reader.onerror = () => {
    error.value = '读取图片失败'
  }
  reader.readAsDataURL(file)
}

async function save() {
  let trimmed = name.value.trim()
  if (!trimmed) {
    trimmed = '聊天室'
    name.value = trimmed
  }
  if (trimmed.length > 30) {
    error.value = '聊天室名称不能超过 30 个字符'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const res = await fetch('/api/admin/room', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: trimmed, icon: icon.value }),
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) {
      error.value = data.error || '保存失败'
      return
    }
    emit('saved', data.room)
    emit('close')
  } catch {
    error.value = '网络异常，请稍后再试'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="dialog-backdrop" @click.self="emit('close')">
      <section class="dialog room-settings-dialog" role="dialog" aria-modal="true" aria-labelledby="room-settings-title">
        <button class="dialog-close" aria-label="关闭" @click="emit('close')">×</button>
        <h2 id="room-settings-title">聊天室设置</h2>
        <p class="dialog-subtitle">修改后立即同步给所有在线用户</p>

        <div class="room-settings-preview">
          <RoomIcon :icon="icon" class="room-settings-icon" />
          <div>
            <strong>{{ name || '聊天室' }}</strong>
            <span class="room-settings-hint">聊天室图标预览</span>
          </div>
        </div>

        <label class="room-settings-field">
          <span>聊天室名称</span>
          <input v-model="name" maxlength="30" placeholder="留空则使用「聊天室」" />
        </label>

        <div class="room-settings-actions">
          <input
            ref="fileInput"
            class="visually-hidden"
            type="file"
            accept="image/png,image/jpeg,image/gif,image/webp"
            @change="iconSelected"
          />
          <button type="button" class="btn-secondary" @click="chooseIcon">上传图标</button>
          <button type="button" class="btn-secondary" @click="icon = '💬'">恢复默认图标</button>
        </div>

        <p v-if="error" class="form-error">{{ error }}</p>

        <div class="dialog-actions">
          <button type="button" class="btn-secondary" @click="emit('close')">取消</button>
          <button type="button" class="btn-primary" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </section>
    </div>
  </Teleport>
</template>
