<script setup>
defineProps({
  user: { type: Object, required: true },
})
</script>

<template>
  <span class="avatar">
    <img v-if="user.avatar" :src="user.avatar" :alt="`${user.displayName}的头像`" />
    <template v-else>{{ user.displayName.slice(0, 1).toUpperCase() }}</template>
  </span>
</template>
