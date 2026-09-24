<script setup>
/*
 * 回合倒计时。
 *
 * ms 来自后端 request/turn 消息里的 timeoutMs（出牌 30000 / 响应 15000），
 * 所以前端倒计时与服务端的强制处理是同一个时间源，不会出现
 * "这边显示还有 10 秒，那边已经超时强制出牌了"。
 *
 * 用 key=reqId 让每次新询问都重建组件，计时自然归零，
 * 不需要在父组件里手工重置。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  ms: { type: Number, required: true },
  /** 是否在剩余不足 1/3 时变红闪烁 */
  urgent: { type: Boolean, default: true },
})

const remain = ref(props.ms)

let timer = null

onMounted(() => {
  const started = Date.now()
  timer = setInterval(() => {
    const left = props.ms - (Date.now() - started)
    remain.value = left > 0 ? left : 0
    if (left <= 0) {
      clearInterval(timer)
      timer = null
    }
  }, 100)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  timer = null
})

/** 剩余秒数（向上取整，避免显示 0 秒却还没超时） */
const seconds = computed(() => Math.max(0, Math.ceil(remain.value / 1000)))

/** 进度 0..1，用于圆环 */
const progress = computed(() => {
  if (!props.ms) return 0
  return Math.max(0, Math.min(1, remain.value / props.ms))
})

const isUrgent = computed(() => props.urgent && remain.value <= props.ms / 3)

/** 圆环：r=20 的周长 */
const CIRC = 2 * Math.PI * 20
const dash = computed(() => `${CIRC * progress.value} ${CIRC}`)
</script>

<template>
  <div class="timer" :class="{ 'is-urgent': isUrgent }" role="timer" :aria-label="`剩余 ${seconds} 秒`">
    <svg viewBox="0 0 48 48" class="timer__ring">
      <circle cx="24" cy="24" r="20" class="timer__track" />
      <circle
        cx="24"
        cy="24"
        r="20"
        class="timer__bar"
        :stroke-dasharray="dash"
        transform="rotate(-90 24 24)"
      />
    </svg>
    <span class="timer__num">{{ seconds }}</span>
  </div>
</template>

<style scoped>
.timer {
  position: relative;
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
}
.timer__ring {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}
.timer__track {
  fill: rgba(0, 0, 0, 0.45);
  stroke: rgba(255, 255, 255, 0.14);
  stroke-width: 4;
}
.timer__bar {
  fill: none;
  stroke: var(--gold-400);
  stroke-width: 4;
  stroke-linecap: round;
  transition: stroke-dasharray 0.1s linear, stroke 0.2s ease;
}
.timer__num {
  position: relative;
  font-size: 17px;
  font-weight: 700;
  color: var(--ink-100);
  font-variant-numeric: tabular-nums;
}

/* 剩余不足 1/3：变红并呼吸，提醒快点决定 */
.timer.is-urgent .timer__bar {
  stroke: #ff5f52;
}
.timer.is-urgent .timer__num {
  color: #ffb3ad;
  animation: timerBlink 0.9s ease-in-out infinite;
}

@keyframes timerBlink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.45;
  }
}

@media (prefers-reduced-motion: reduce) {
  .timer.is-urgent .timer__num {
    animation: none;
  }
}
</style>
