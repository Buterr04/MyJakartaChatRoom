<script setup>
import { computed, onMounted, ref } from 'vue'

const emit = defineEmits(['close'])

const loading = ref(true)
const error = ref('')
const granularity = ref('day')
const from = ref(isoDaysAgo(29))
const to = ref(isoDaysAgo(0))
const data = ref({
  today: { date: '', count: 0 },
  total: 0,
  login: { today: 0, total: 0 },
  activeUsers: [],
  series: [],
})

onMounted(load)

function isoDaysAgo(days) {
  const date = new Date()
  date.setDate(date.getDate() - days)
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function shortDate(value) {
  return value ? value.slice(5) : ''
}

function setGranularity(value) {
  granularity.value = value
  load()
}

async function load() {
  if (!from.value || !to.value) return
  if (from.value > to.value) {
    error.value = '开始日期不能晚于结束日期'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const params = new URLSearchParams({
      from: from.value,
      to: to.value,
      granularity: granularity.value,
    })
    const res = await fetch(`/api/super-admin/statistics?${params}`)
    const body = await res.json().catch(() => ({}))
    if (!res.ok) {
      error.value = body.error || '统计加载失败'
      return
    }
    data.value = body
  } catch {
    error.value = '网络异常，请稍后再试'
  } finally {
    loading.value = false
  }
}

function resetRange() {
  from.value = isoDaysAgo(29)
  to.value = isoDaysAgo(0)
  load()
}

const chart = computed(() => {
  const series = data.value.series || []
  const width = 760
  const height = 230
  const left = 44
  const right = 16
  const top = 16
  const bottom = 38
  const innerWidth = width - left - right
  const innerHeight = height - top - bottom
  const max = Math.max(1, ...series.map((item) => Number(item.count) || 0))
  const slot = series.length ? innerWidth / series.length : innerWidth
  const barWidth = Math.max(3, Math.min(36, slot * 0.62))
  const bars = series.map((item, index) => {
    const count = Number(item.count) || 0
    const barHeight = (count / max) * innerHeight
    return {
      x: left + slot * index + (slot - barWidth) / 2,
      y: top + innerHeight - barHeight,
      width: barWidth,
      height: Math.max(count > 0 ? 2 : 0, barHeight),
      count,
      period: item.period,
      label: item.label,
    }
  })
  const yTicks = [0, 0.25, 0.5, 0.75, 1].map((ratio) => ({
    y: top + (1 - ratio) * innerHeight,
    label: Math.round(max * ratio),
  }))
  const labelStep = Math.max(1, Math.ceil(series.length / 8))
  const xLabels = []
  bars
    .filter((bar, index) => index === 0 || index === bars.length - 1 || index % labelStep === 0)
    .forEach((bar) => {
      const x = bar.x + bar.width / 2
      if (!xLabels.length || x - xLabels[xLabels.length - 1].x >= 42) {
        xLabels.push({ x, label: bar.label })
      }
    })
  return { width, height, left, right, top, bottom, max, bars, yTicks, xLabels }
})
</script>

<template>
  <Teleport to="body">
    <div class="dialog-backdrop" @click.self="emit('close')">
      <section class="dialog stats-dialog" role="dialog" aria-modal="true" aria-labelledby="stats-title">
        <button class="dialog-close" aria-label="关闭" @click="emit('close')">×</button>
        <h2 id="stats-title">消息统计</h2>
        <p class="dialog-subtitle">只统计聊天消息，系统消息不计入。</p>

        <p v-if="error" class="form-error">{{ error }}</p>

        <template v-if="!error">
          <div class="stats-cards">
            <div class="stats-card">
              <span>今日消息数</span>
              <strong>{{ data.today?.count ?? 0 }}</strong>
              <small>{{ data.today?.date }}</small>
            </div>
            <div class="stats-card">
              <span>历史消息总数</span>
              <strong>{{ data.total ?? 0 }}</strong>
              <small>不含系统消息</small>
            </div>
            <div class="stats-card">
              <span>今日活跃用户</span>
              <strong>{{ data.login?.today ?? 0 }} / {{ data.login?.total ?? 0 }}</strong>
              <small>今日登录过 / 聊天室总人数</small>
            </div>
          </div>

          <div class="stats-panel">
            <div class="stats-panel-heading">
              <h3>今日活跃用户 TOP10</h3>
              <span>按今日发言数排序</span>
            </div>
            <div v-if="(data.activeUsers || []).length" class="stats-users">
              <div v-for="(user, index) in data.activeUsers" :key="user.username" class="stats-user-row">
                <span class="stats-rank">{{ index + 1 }}</span>
                <div class="stats-user">
                  <strong>{{ user.displayName }}</strong>
                  <span>@{{ user.username }}</span>
                </div>
                <strong class="stats-count">{{ user.count }} 条</strong>
              </div>
            </div>
            <div v-else class="admin-empty">今天还没有用户发言。</div>
          </div>

          <div class="stats-panel">
            <div class="stats-panel-heading">
              <h3>消息数统计</h3>
              <span>{{ from }} ~ {{ to }}</span>
            </div>

            <form class="stats-toolbar stats-toolbar-inline" @submit.prevent="load">
              <label>
                <span>开始</span>
                <input v-model="from" type="date" :max="to" />
              </label>
              <label>
                <span>结束</span>
                <input v-model="to" type="date" :min="from" />
              </label>
              <div class="stats-granularity" role="group" aria-label="统计粒度">
                <button
                  type="button"
                  :class="{ active: granularity === 'day' }"
                  @click="setGranularity('day')"
                >按天</button>
                <button
                  type="button"
                  :class="{ active: granularity === 'week' }"
                  @click="setGranularity('week')"
                >按周</button>
                <button
                  type="button"
                  :class="{ active: granularity === 'month' }"
                  @click="setGranularity('month')"
                >按月</button>
              </div>
              <button class="btn-primary" type="submit" :disabled="loading">
                {{ loading ? '查询中…' : '查询' }}
              </button>
              <button class="btn-secondary" type="button" @click="resetRange">近 30 天</button>
            </form>

            <div v-if="chart.bars.length" class="stats-chart-wrap">
              <svg class="stats-chart" :viewBox="`0 0 ${chart.width} ${chart.height}`" role="img" aria-label="消息数柱状图">
                <g v-for="tick in chart.yTicks" :key="tick.y">
                  <line class="stats-grid-line" :x1="chart.left" :x2="chart.width - chart.right" :y1="tick.y" :y2="tick.y" />
                  <text class="stats-axis-text" :x="chart.left - 10" :y="tick.y + 4" text-anchor="end">{{ tick.label }}</text>
                </g>
                <g v-for="bar in chart.bars" :key="bar.period">
                  <rect
                    class="stats-bar"
                    :x="bar.x"
                    :y="bar.y"
                    :width="bar.width"
                    :height="bar.height"
                    rx="3"
                  >
                    <title>{{ bar.period }}：{{ bar.count }} 条</title>
                  </rect>
                </g>
                <text
                  v-for="label in chart.xLabels"
                  :key="label.x"
                  class="stats-axis-text"
                  :x="label.x"
                  :y="chart.height - 10"
                  text-anchor="middle"
                >{{ label.label }}</text>
              </svg>
            </div>
            <div v-else class="admin-empty">所选范围内没有聊天消息。</div>
          </div>
        </template>
      </section>
    </div>
  </Teleport>
</template>
