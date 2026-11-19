<script setup>
import { onMounted, ref } from 'vue'

const props = defineProps({
  title: { type: String, default: '输入内容' },
  message: { type: String, required: true },
  label: { type: String, default: '' },
  defaultValue: { type: [String, Number], default: '' },
  confirmText: { type: String, default: '确定' },
  min: { type: Number, default: 0 },
  max: { type: Number, default: 10080 },
})
const emit = defineEmits(['confirm', 'close'])

const value = ref(String(props.defaultValue ?? ''))
const error = ref('')
const input = ref(null)

onMounted(() => {
  input.value?.focus()
  input.value?.select()
})

function submit() {
  const number = Number(value.value)
  if (!Number.isInteger(number) || number < props.min || number > props.max) {
    error.value = `请输入 ${props.min}~${props.max} 的整数`
    return
  }
  emit('confirm', number)
}
</script>

<template>
  <Teleport to="body">
    <div class="dialog-backdrop" @click.self="emit('close')">
      <section class="dialog prompt-dialog" role="dialog" aria-modal="true" aria-labelledby="prompt-title">
        <h2 id="prompt-title">{{ title }}</h2>
        <p class="dialog-subtitle confirm-message">{{ message }}</p>
        <form class="prompt-form" @submit.prevent="submit">
          <label v-if="label" class="prompt-label" for="prompt-input">{{ label }}</label>
          <input
            id="prompt-input"
            ref="input"
            v-model="value"
            type="number"
            inputmode="numeric"
            :min="min"
            :max="max"
            step="1"
          />
          <p v-if="error" class="form-error prompt-error">{{ error }}</p>
          <div class="dialog-actions">
            <button class="btn-secondary" type="button" @click="emit('close')">取消</button>
            <button class="btn-primary" type="submit">{{ confirmText }}</button>
          </div>
        </form>
      </section>
    </div>
  </Teleport>
</template>
