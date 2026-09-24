<script setup>
/*
 * 战绩页（Vue 版）。
 *
 * ── 两级结构 ──
 *   一级：房间/场次列表     GET /api/records?pageNum=&pageSize=
 *   二级：某一场的完整明细   GET /api/records/{sessionId}
 *
 * ── 为什么二级要做成"视图"而不是弹层 ──
 *   明细内容很多（每把四家的杠/花/番型/得分），弹层在手机横屏上会变成一个
 *   内部滚动的窄条。做成独立视图 + 返回按钮，滚动的是页面本身，体验好得多。
 *
 * ── 直接进二级的入口 ──
 *   房间页的「战绩」按钮会带 ?roomId=xxx 过来，走 GET /api/records/byRoom，
 *   展示【本房间从开始到现在】的完整明细（不看分页，只看这一间）。
 *   这就是用户说的"点进房间后的二级战绩"。
 *
 * ⚠️ pageSize 上限 20（后端 PageQueryDTO 上是 @Max(20)）。传大于 20 会 400，
 *    请求到不了数据库，页面表现为"一条战绩都没有"。api.js 里已经做了钳制。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isLoggedIn, records as fetchRecords, recordsByRoom, recordDetail } from '../api/api.js'
import RecordDetail from '../components/records/RecordDetail.vue'

const route = useRoute()
const router = useRouter()

/* ---------------- 一级：场次列表 ---------------- */

const list = ref([])
const total = ref(0)
const pages = ref(0)
const pageNum = ref(1)
const pageSize = 10
const loading = ref(false)
const error = ref('')

const canPrev = computed(() => pageNum.value > 1)
const canNext = computed(() => pages.value > 0 && pageNum.value < pages.value)

async function load(p) {
  loading.value = true
  error.value = ''
  try {
    const body = await fetchRecords(p || pageNum.value, pageSize)
    const data = body.data || {}
    list.value = data.records || []
    total.value = data.total || 0
    pages.value = data.pages || 0
    pageNum.value = Number(data.pageNum || p || 1)
  } catch (e) {
    error.value = (e && e.message) || '加载失败'
    list.value = []
  } finally {
    loading.value = false
  }
}

function go(p) {
  if (p < 1) return
  if (pages.value > 0 && p > pages.value) return
  load(p)
}

/* ---------------- 二级：明细 ---------------- */

const detail = ref(null)
const detailLoading = ref(false)
/** 'room' = 来自 ?roomId=（本房间）；'session' = 从列表点进来的某一场 */
const detailFrom = ref('session')

/** 从 URL 读 roomId（本房间入口） */
const queryRoomId = computed(() => (route.query.roomId || '').toString().trim())

async function loadByRoom(roomId) {
  detailFrom.value = 'room'
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await recordsByRoom(roomId)
  } catch (e) {
    detail.value = { notFound: true, error: (e && e.message) || '加载失败' }
  } finally {
    detailLoading.value = false
  }
}

async function openSession(sessionId) {
  detailFrom.value = 'session'
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await recordDetail(sessionId)
  } catch (e) {
    detail.value = { notFound: true, error: (e && e.message) || '加载失败' }
  } finally {
    detailLoading.value = false
  }
}

/** 返回一级：如果是 ?roomId= 进来的，就退回大厅（本房间没有"上一级列表"） */
function back() {
  if (detailFrom.value === 'room') {
    router.push('/lobby')
  } else {
    detail.value = null
  }
}

/* ---------------- 展示辅助 ---------------- */

function fmtTime(v) {
  if (!v) return '—'
  // 后端是 LocalDateTime 序列化的 ISO 串，直接截到分钟即可
  return String(v).replace('T', ' ').slice(0, 16)
}

function fmtRange(a, b) {
  const s = fmtTime(a)
  const e = fmtTime(b)
  if (s === '—' && e === '—') return '—'
  return `${s.slice(5)} ~ ${e.slice(5)}`
}

/*
 * 二级明细的渲染与辅助函数都搬到了 components/records/RecordDetail.vue ——
 * 牌桌右下角的「局内战绩」弹窗用的是同一个组件，两处必须长得一模一样。
 * 这里只留一级列表需要的东西。
 */
function scoreCls(v) {
  return v > 0 ? 'is-up' : v < 0 ? 'is-down' : ''
}

function fmtScore(v) {
  const n = Number(v) || 0
  return n > 0 ? `+${n}` : String(n)
}

/**
 * 一级列表每行的四家名字。
 *
 * 后端按 player_ids 的顺序给（座位 东→南→西→北），这里只做防御：
 * 老数据 / 旧接口可能没有 players 字段，返回空数组让模板不渲染那一段即可，
 * 不能因为一个缺字段把整页搞白。
 */
function playersOf(r) {
  const p = r && r.players
  return Array.isArray(p) ? p.filter((x) => !!x) : []
}

/* ---------------- 生命周期 ---------------- */

onMounted(() => {
  if (!isLoggedIn()) {
    router.replace('/')
    return
  }
  if (queryRoomId.value) {
    loadByRoom(queryRoomId.value)
  } else {
    load(1)
  }
})

/** 支持在页面内切换 roomId（例如从牌桌再来一次），不重挂组件也能生效 */
watch(queryRoomId, (v) => {
  if (v) loadByRoom(v)
  else {
    detail.value = null
    load(1)
  }
})
</script>

<template>
  <div class="records">
    <header class="records__head">
      <button class="btn btn--sm btn--ghost" type="button" @click="detail ? back() : router.push('/lobby')">
        ← {{ detail ? '返回' : '回大厅' }}
      </button>
      <h1>{{ detail ? '对局明细' : '我的战绩' }}</h1>
      <span v-if="!detail" class="chip">共 {{ total }} 场</span>
      <span v-else class="chip chip--gold">房间 {{ detail.roomId || queryRoomId }}</span>
    </header>

    <!-- ==================== 一级：场次列表 ==================== -->
    <template v-if="!detail">
      <p v-if="error" class="records__msg records__msg--bad">{{ error }}</p>
      <p v-if="loading" class="records__msg">加载中…</p>

      <div v-if="!loading && !list.length && !error" class="records__empty panel">
        还没有战绩。打完一场就会出现在这里。
      </div>

      <ul class="records__list">
        <li v-for="r in list" :key="r.sessionId" class="recrow panel" @click="openSession(r.sessionId)">
          <div class="recrow__main">
            <div class="recrow__line">
              <span class="recrow__room">房间 {{ r.roomId }}</span>
              <!--
                房号后面直接列四家名字（按座位 东→南→西→北，后端按 player_ids 顺序给）。
                名字可能很长，所以这一行允许收缩 + 省略号，不能把右侧分数挤出去。
              -->
              <span v-if="playersOf(r).length" class="recrow__players" :title="playersOf(r).join('、')">
                {{ playersOf(r).join('、') }}
              </span>
            </div>
            <span class="recrow__time">{{ fmtRange(r.startTime, r.endTime) }}</span>
          </div>
          <div class="recrow__score">
            <span class="score" :class="scoreCls(r.totalScore)">{{ fmtScore(r.totalScore) }}</span>
            <span class="rank">第 {{ r.rank }} 名</span>
          </div>
          <span class="recrow__go">详情 ›</span>
        </li>
      </ul>

      <div v-if="pages > 1" class="pager">
        <button class="btn btn--sm" type="button" :disabled="!canPrev" @click="go(pageNum - 1)">上一页</button>
        <span class="pager__info">{{ pageNum }} / {{ pages }}</span>
        <button class="btn btn--sm" type="button" :disabled="!canNext" @click="go(pageNum + 1)">下一页</button>
      </div>
    </template>

    <!-- ==================== 二级：明细 ==================== -->
    <template v-else>
      <p v-if="detailLoading" class="records__msg">加载中…</p>
      <div v-else-if="detail.notFound" class="records__empty panel">
        <template v-if="detailFrom === 'room'">本房间还没有完成的小局。</template>
        <template v-else>这场对局还没有完成的小局。</template>
      </div>

      <template v-else>
      <!-- 概览 + 每一把：交给共用组件渲染（牌桌里点「局内战绩」用的是同一个组件，
           保证两处看到的明细完全一致） -->
      <RecordDetail :detail="detail" />
      </template>
    </template>
  </div>
</template>

<script>
/*
 * 这里刻意【没有】第二个 <script> 块。
 * Vue 3 允许 <script setup> 与普通 <script> 共存，但普通块里的 computed/this
 * 拿不到 setup 里的 ref —— 写成 options API 的 computed 去读 detail 只会拿到
 * undefined。所有需要 detail 的派生值都必须在 setup 里算（见上面的 myTotal）。
 */
export default { name: 'RecordsView' }
</script>

<style scoped>
.records {
  /* 高度由 #game-app 的 flex 撑满；内容超出时在本层滚动（理由同上） */
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
  background: radial-gradient(ellipse at 50% 15%, var(--felt-600) 0%, var(--felt-800) 55%, var(--felt-900) 100%);
}
.records__head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}
.records__head h1 {
  margin: 0;
  flex: 1 1 auto;
  font-size: 19px;
  letter-spacing: 2px;
  color: var(--gold-300);
}
.records__msg {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--ink-300);
}
.records__msg--bad {
  color: #ff8b83;
}
.records__empty {
  padding: 22px;
  text-align: center;
  color: var(--ink-300);
  font-size: 14px;
}
.records__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 720px;
  margin: 0 auto;
}
.recrow {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  cursor: pointer;
}
.recrow:hover {
  border-color: var(--gold-500);
}
.recrow__main {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
/* 房号 + 四家名字在同一行：房号不收缩，名字占剩下的宽度并省略 */
.recrow__line {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}
.recrow__room {
  flex: 0 0 auto;
  font-size: 15px;
  font-weight: 700;
  color: var(--ink-100);
}
.recrow__players {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  font-size: 13px;
  color: var(--ink-200);
}
.recrow__time {
  font-size: 12px;
  color: var(--ink-400);
}
.recrow__score {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}
.score {
  font-size: 18px;
  font-weight: 800;
}
.rank {
  font-size: 11px;
  color: var(--ink-400);
}
.recrow__go {
  font-size: 12px;
  color: var(--ink-400);
}
.is-up {
  color: #8ee0b4;
}
.is-down {
  color: #ff8b83;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-top: 14px;
}
.pager__info {
  font-size: 13px;
  color: var(--ink-300);
}

</style>
