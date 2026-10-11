<script setup>
import { computed, ref, watch, onUnmounted } from 'vue'
import { recordsV2 } from '../../api/api.js'
import { subscribeRecordUpdates, committedRecordRound } from '../../api/recordReads.js'
import { useRecordTask } from '../../composables/useRecordTask.js'
import RecordDetail from './RecordDetail.vue'

const props = defineProps({ detail: { type: Object, required: true } })
const emit = defineEmits(['overview'])
const overview = ref(props.detail.overview)
const page = ref(null)
const pageTask = useRecordTask()
const detailTask = useRecordTask()
const refreshTask = useRecordTask()
const loading = pageTask.busy
const detailLoading = detailTask.busy
const refreshing = refreshTask.busy
const error = computed(() => pageTask.error.value || detailTask.error.value || refreshTask.error.value)
const selected = ref(null)
const expanding = ref(null)
const starts = ref([0])
const pageIndex = ref(0)
let pageRequest = { after: 0, index: 0 }
let wantedRound = overview.value.readThroughRoundNum

function load(after = 0, index = 0) {
  const sessionId = props.detail.sessionId
  const cutoff = overview.value.readThroughRoundNum
  pageRequest = { after, index }
  detailTask.cancel()
  selected.value = null
  expanding.value = null
  return pageTask.run({ key: `page:${sessionId}:${cutoff}:${after}`,
    read: () => recordsV2.rounds(sessionId, { afterRoundNum: after, pageSize: 10, readThroughRoundNum: cutoff }),
    onSuccess(result) {
      page.value = result
      starts.value[index] = after
      pageIndex.value = index
    },
  })
}

function expand(roundNum) {
  if (selected.value?.roundNum === roundNum) { selected.value = null; detailTask.cancel(); expanding.value = null; return }
  const sessionId = props.detail.sessionId
  const cutoff = overview.value.readThroughRoundNum
  expanding.value = roundNum
  return detailTask.run({ key: `detail:${sessionId}:${cutoff}:${roundNum}`,
    read: () => recordsV2.detail(sessionId, roundNum, cutoff),
    onSuccess(result) { selected.value = result.round; expanding.value = null },
  })
}

function refresh() {
  const sessionId = props.detail.sessionId
  const after = overview.value.readThroughRoundNum
  return refreshTask.run({ key: `new:${sessionId}:${after}`,
    async read() {
      // 即使积累了很多局，也只读取一页增量；其余仍由用户翻页，不追拉全部历史。
      const added = await recordsV2.newRounds(sessionId, after, 10)
      if (document.visibilityState === 'hidden') throw Object.assign(new Error(), { code: 'RECORD_PAUSED' })
      const fresh = await recordsV2.overview(sessionId, added.readThroughRoundNum)
      if (fresh.readThroughRoundNum < wantedRound) {
        const e = new Error('新小局尚未可读，请稍后重试'); e.code = 503; throw e
      }
      return { added, fresh }
    },
    onSuccess({ added, fresh }) {
      const advanced = fresh.readThroughRoundNum > overview.value.readThroughRoundNum
      const pendingPage = pageTask.busy.value || !!pageTask.error.value || !page.value
      overview.value = fresh
      emit('overview', fresh)
      if (!advanced) return
      pageTask.cancel()
      detailTask.cancel(); expanding.value = null
      if (pendingPage) { load(pageRequest.after, pageRequest.index); return }
      const previous = page.value
      const candidates = previous.hasMore ? previous.rounds : [...previous.rounds, ...added.rounds]
      const unique = [...new Map(candidates.map(round => [round.roundId, round])).values()]
      const rounds = unique.slice(0, 10)
      page.value = { ...previous, rounds, readThroughRoundNum: fresh.readThroughRoundNum, version: fresh.version,
        hasMore: previous.hasMore || added.hasMore || unique.length > 10 ||
          (previous.rounds.length === 10 && added.rounds.length > 0),
        nextAfterRoundNum: rounds.at(-1)?.roundNum ?? previous.nextAfterRoundNum,
      }
    },
  })
}

function retry() {
  if (pageTask.error.value) return pageTask.retry()
  if (detailTask.error.value) return detailTask.retry()
  return refreshTask.retry()
}

watch(() => props.detail, () => {
  pageTask.cancel(); detailTask.cancel(); refreshTask.cancel()
  overview.value = props.detail.overview
  wantedRound = Math.max(overview.value.readThroughRoundNum, committedRecordRound(props.detail.sessionId))
  page.value = null
  starts.value = [0]
  pageIndex.value = 0
  if (wantedRound > overview.value.readThroughRoundNum) refresh()
  else load()
}, { immediate: true })

const unsubscribe = subscribeRecordUpdates(update => {
  if (update.kind === 'commit' && update.sessionId === props.detail.sessionId && update.roundNum > overview.value.readThroughRoundNum) {
    wantedRound = Math.max(wantedRound, update.roundNum)
    refresh()
  } else if (update.kind === 'reconnect' && update.roomId === overview.value.roomId && overview.value.status === 0) refresh()
})
function onVisibility() {
  if (document.visibilityState !== 'hidden' && page.value && overview.value.status === 0) refresh()
}
document.addEventListener('visibilitychange', onVisibility)
onUnmounted(() => { unsubscribe(); document.removeEventListener('visibilitychange', onVisibility) })
</script>

<template>
  <div class="v2-records">
    <RecordDetail :detail="{ overview, rounds: [] }" hide-empty />
    <p class="snapshot">
      截至第 {{ overview.readThroughRoundNum }} 局 · 每页 10 局
      <button type="button" class="btn btn--sm" :disabled="refreshing" @click="refresh()">
        {{ refreshing ? '刷新中…' : '检查新小局' }}
      </button>
    </p>
    <p v-if="error" role="alert">
      {{ error }}
      <button type="button" class="btn btn--sm" :disabled="loading || detailLoading || refreshing" @click="retry()">重试</button>
    </p>
    <p v-if="loading">加载中…</p>
    <template v-else>
      <article v-for="round in page?.rounds || []" :key="round.roundId" class="round-summary panel">
        <header>
          <b>第 {{ round.roundNum }} 局</b>
          <span>{{ round.draw ? '荒庄' : round.winType === 0 ? '自摸' : '胡牌' }}</span>
          <button type="button" class="btn btn--sm" :disabled="detailLoading || refreshing" @click="expand(round.roundNum)">
            {{ expanding === round.roundNum ? '加载中…' : selected?.roundNum === round.roundNum ? '收起' : '查看详情' }}
          </button>
        </header>
        <div class="scores">
          <span v-for="p in round.participants" :key="p.userId">
            {{ p.nickname }}{{ p.me ? '（我）' : '' }} {{ p.scoreChange > 0 ? '+' : '' }}{{ p.scoreChange }}
          </span>
        </div>
        <RecordDetail v-if="selected?.roundNum === round.roundNum"
          :detail="{ rounds: [selected] }" hide-overview />
      </article>
      <p v-if="page && !page.rounds.length">这一场还没有完成的小局。</p>
    </template>
    <nav v-if="page" class="pager" aria-label="小局分页">
      <button type="button" class="btn btn--sm" :disabled="loading || refreshing || pageIndex === 0"
        @click="load(starts[pageIndex - 1], pageIndex - 1)">上一页</button>
      <span>第 {{ pageIndex + 1 }} 页</span>
      <button type="button" class="btn btn--sm" :disabled="loading || refreshing || !page.hasMore"
        @click="load(page.nextAfterRoundNum, pageIndex + 1)">下一页</button>
    </nav>
  </div>
</template>

<style scoped>
.snapshot { opacity: .7; margin: 12px 0; }
.round-summary { padding: 12px; margin-bottom: 12px; }
header, .scores, .pager { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
header { justify-content: space-between; }
.scores { margin-top: 12px; }
.pager { justify-content: center; margin: 16px 0; }
</style>
