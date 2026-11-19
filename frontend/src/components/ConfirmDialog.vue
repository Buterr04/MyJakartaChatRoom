<script setup>
defineProps({
  title: { type: String, default: '请确认' },
  message: { type: String, required: true },
  confirmText: { type: String, default: '确定' },
  cancelText: { type: String, default: '取消' },
  danger: { type: Boolean, default: false },
})
const emit = defineEmits(['confirm', 'close'])
</script>

<template>
  <Teleport to="body">
    <div class="dialog-backdrop" @click.self="emit('close')">
      <section class="dialog confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="confirm-title">
        <h2 id="confirm-title">{{ title }}</h2>
        <p class="dialog-subtitle confirm-message">{{ message }}</p>
        <div class="dialog-actions">
          <button class="btn-secondary" type="button" @click="emit('close')">{{ cancelText }}</button>
          <button :class="danger ? 'btn-danger' : 'btn-primary'" type="button" @click="emit('confirm')">
            {{ confirmText }}
          </button>
        </div>
      </section>
    </div>
  </Teleport>
</template>
