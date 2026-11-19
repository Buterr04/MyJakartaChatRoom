<script setup>
import { computed } from 'vue'

const props = defineProps({
  icon: { type: String, default: '💬' },
})

const isImage = computed(() => (props.icon || '').startsWith('data:image/'))
</script>

<template>
  <span class="room-icon">
    <img v-if="isImage" :src="icon" alt="" />
    <template v-else>{{ icon || '💬' }}</template>
  </span>
</template>
