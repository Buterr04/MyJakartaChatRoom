<template>
  <div
    ref="cardRef"
    class="spotlight-card"
    @pointermove="handlePointerMove"
    @pointerenter="handlePointerEnter"
    @pointerleave="handlePointerLeave"
    @focusin="handleFocusIn"
    @focusout="handleFocusOut"
  >
    <div class="spotlight-card-light" :style="lightStyles" aria-hidden="true" />
    <div class="spotlight-card-content">
      <slot />
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  spotlightColor: { type: String, default: 'rgba(255, 255, 255, 0.48)' },
})

const cardRef = ref(null)
const focused = ref(false)
const opacity = ref(0)
const position = ref({ x: 0, y: 0 })

const lightStyles = computed(() => ({
  opacity: opacity.value,
  background: `radial-gradient(260px circle at ${position.value.x}px ${position.value.y}px, ${props.spotlightColor}, rgba(255, 255, 255, 0.08) 42%, transparent 70%)`,
}))

function updatePosition(event) {
  if (!cardRef.value || focused.value) return
  const rect = cardRef.value.getBoundingClientRect()
  position.value = { x: event.clientX - rect.left, y: event.clientY - rect.top }
}

function handlePointerMove(event) {
  updatePosition(event)
}

function handlePointerEnter(event) {
  updatePosition(event)
  if (!focused.value) opacity.value = 1
}

function handlePointerLeave() {
  if (!focused.value) opacity.value = 0
}

function handleFocusIn() {
  focused.value = true
  const rect = cardRef.value?.getBoundingClientRect()
  if (rect) position.value = { x: rect.width / 2, y: rect.height / 2 }
  opacity.value = 0.6
}

function handleFocusOut(event) {
  if (event.currentTarget.contains(event.relatedTarget)) return
  focused.value = false
  opacity.value = 0
}
</script>
