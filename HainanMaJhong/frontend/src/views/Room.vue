<script setup>
/*
 * 房间页（Vue 版）：等待房。
 *
 * 用 WebSocket /room，四种消息（与后端 MultiPlayerRoomServiceImpl.onMessage 一致）：
 *   createRoom { config }   建房（config 可选，房主可配番型门槛等）
 *   joinRoom   { code }     加入
 *   fillBots                空位补电脑（房主）
 *   startNow                开始对局（房主，需 4 人）
 *
 * 服务端回 room_info（code/hostUserId/state/members[{userId,nickname,seat}]）。
 * 开局时服务端向等待房连接推 start {seat}，前端据此跳到牌桌并带上自己的座位。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { GameSocket } from '../game/socket.js'
import { isLoggedIn, profile, handleKicked } from '../api/api.js'
import { onPreloadProgress, preloadAssets } from '../game/preload.js'
import { SEATS, SEAT_CN } from '../game/tiles.js'

const route = useRoute()
const router = useRouter()

const socket = ref(null)
const connState = ref('idle')
const room = ref(null) // room_info 内容
const error = ref('')
const toast = ref('')
const busy = ref(false)

const action = computed(() => (route.query.action || '').toString())
const wantCode = computed(() => (route.query.code || '').toString().trim().toUpperCase())

const myUserId = computed(() => {
  const p = profile()
  return p && p.id ? Number(p.id) : Number(localStorage.getItem('userId') || 0)
})
const isHost = computed(() => !!room.value && Number(room.value.hostUserId) === myUserId.value)
const members = computed(() => (room.value && room.value.members) || [])
const canStart = computed(() => isHost.value && members.value.length >= 4 && room.value.state === 'WAITING')

/**
 * 是否已经开局。
 *
 * 「本房战绩」入口只在开局后给：等待房里点进去必然是空的（这一场还没有任何小局），
 * 点了只会看到一个"本房间还没有完成的小局"，纯属误导。
 */
const inGame = computed(() => !!room.value && room.value.state === 'PLAYING')

/* ---------------- 资源预加载 ---------------- */

/** 预加载进度快照（见 game/preload.js）；进对局前要下完 */
const pre = ref(null)
/** 资源就绪才让房主开局；没有快照（极端情况）也放行，别把人锁死 */
const resourcesReady = computed(() => !pre.value || pre.value.done)
let stopPreloadWatch = null

/** 后端机器人昵称：userId -1/-2/-3 对应 东/南/西（RoomSupport.botNickname） */
function isBot(m) {
  return Number(m.userId) < 0
}

/** 本房间号（战绩入口用）：优先取服务端 room_info，其次取 URL 上的 code */
const roomCodeForRecords = computed(() => {
  const c = (room.value && room.value.code) || wantCode.value
  return c ? String(c).trim() : ''
})

/** 二级战绩：跳到战绩页并带上本房间号 */
function openRoomRecords() {
  const c = roomCodeForRecords.value
  if (!c) return
  router.push({ path: '/records', query: { roomId: c } })
}

/** 把 4 个座位补齐展示：空位也要画出来，房主才知道缺几个 */
const seats = computed(() =>
  SEATS.map((s) => {
    const m = members.value.find((x) => x.seat === s)
    return { seat: s, seatCn: SEAT_CN[s], member: m || null }
  }),
)

let toastTimer = null
function showToast(msg) {
  toast.value = msg
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    toast.value = ''
  }, 2200)
}

function handle(msg) {
  switch (msg.type) {
    // 被顶下线（同一账号在别处登录）：清登录态 + 提示 + 回登录页
    case 'kick':
      handleKicked(msg.reason)
      return
    case 'room_info':
      room.value = msg
      return
    case 'start': {
      // 开局：服务端告诉我的座位，跳牌桌
      const code = (room.value && room.value.code) || wantCode.value
      const seat = msg.seat || 'EAST'
      router.replace({ path: '/game', query: { code, seat } })
      return
    }
    case 'join_denied':
      error.value = msg.reason || '无法加入房间'
      busy.value = false
      return
    case 'room_closed':
      error.value = msg.reason || '房间已解散'
      return
    default:
      return
  }
}

onMounted(() => {
  if (!isLoggedIn()) {
    router.replace('/')
    return
  }

  // 等待房里就把牌面/音效下完（大厅已经先热身过，这里通常只是接着看进度）
  stopPreloadWatch = onPreloadProgress((s) => {
    pre.value = s
  })
  preloadAssets()

  const s = new GameSocket({
    path: '/room',
    onMessage: handle,
    onState: (v) => {
      connState.value = v
      // 连上后按 action 发第一条消息
      if (v === 'open') {
        if (action.value === 'create') {
          let cfg = null
          try {
            cfg = JSON.parse(localStorage.getItem('hnRoomConfig') || 'null')
          } catch (e) {
            cfg = null
          }
          s.send({ type: 'createRoom', config: cfg && typeof cfg === 'object' ? cfg : {} })
        } else if (wantCode.value) {
          s.send({ type: 'joinRoom', code: wantCode.value })
        } else {
          error.value = '缺少房间号'
        }
      }
    },
    onAuthFailed: (reason) => {
      error.value = reason
    },
  })
  socket.value = s
  s.connect()
})

onBeforeUnmount(() => {
  if (socket.value) socket.value.close()
  if (toastTimer) clearTimeout(toastTimer)
  if (stopPreloadWatch) stopPreloadWatch()
})

/* ---------------- 房主操作 ---------------- */

function fillBots() {
  if (!socket.value) return
  busy.value = true
  if (!socket.value.send({ type: 'fillBots' })) {
    busy.value = false
    showToast('连接未就绪')
    return
  }
  showToast('已请求补电脑')
  setTimeout(() => {
    busy.value = false
  }, 600)
}

function startNow() {
  if (!socket.value) return
  if (members.value.length < 4) {
    showToast('还差 ' + (4 - members.value.length) + ' 人，先补电脑')
    return
  }
  socket.value.send({ type: 'startNow' })
}

function leave() {
  if (socket.value) {
    socket.value.send({ type: 'leaveRoom' })
    socket.value.close()
  }
  router.replace('/lobby')
}
</script>

<template>
  <div class="room">
    <header class="room__head">
      <button class="btn btn--sm btn--ghost" type="button" @click="leave">← 离开</button>
      <h1 class="room__code">
        <span v-if="room">房间 <b>{{ room.code }}</b></span>
        <span v-else>连接中…</span>
      </h1>
      <div class="room__headbtn">
        <!--
          二级战绩入口：带着 roomId 跳到战绩页，那边会走 /api/records/byRoom
          展示【本房间从开始到现在】的完整明细。

          ⚠️ 只在【开局后】才出现（inGame）：等待房里这一场还没有任何小局，
          摆着这个按钮点进去只会看到"本房间还没有完成的小局"，是纯粹的误导。
        -->
        <button
          v-if="inGame"
          class="btn btn--sm btn--ghost"
          type="button"
          title="查看本房间战绩"
          @click="openRoomRecords"
        >
          🏆 本房战绩
        </button>
        <span class="chip" :class="connState === 'open' ? 'chip--ok' : 'chip--warn'">
          {{ connState === 'open' ? '已连接' : '连接中…' }}
        </span>
      </div>
    </header>

    <main class="room__main">
      <div class="seats">
        <div v-for="s in seats" :key="s.seat" class="seatcard panel" :class="{ 'is-empty': !s.member }">
          <div class="seatcard__seat">{{ s.seatCn }}</div>
          <div class="seatcard__name">
            <template v-if="s.member">
              {{ s.member.nickname || '玩家' + s.member.userId }}
              <span v-if="isBot(s.member)" class="tag tag--bot">电脑</span>
              <span v-if="Number(s.member.userId) === Number(room.hostUserId)" class="tag tag--host">房主</span>
            </template>
            <template v-else>
              <span class="seatcard__empty">空位</span>
            </template>
          </div>
        </div>
      </div>

      <!--
        进对局前先把牌面图/音效下完（见 game/preload.js）：
        否则进桌那一瞬间牌是现下的（先白一下），而音效更麻烦 ——
        浏览器要等用户手势才让播，现下现放就错过第一次吃碰杠了。
      -->
      <div v-if="pre && !pre.done" class="preload panel">
        <div class="preload__bar"><i :style="{ width: pre.percent + '%' }"></i></div>
        <span class="preload__txt">
          正在加载牌面与音效… {{ pre.percent }}%（{{ pre.loaded }}/{{ pre.total }}）
        </span>
      </div>
      <p v-else-if="pre && pre.failed.length" class="preload__warn">
        有 {{ pre.failed.length }} 个资源没下下来，不影响开局
      </p>

      <div class="room__actions">
        <button v-if="isHost" class="btn" type="button" :disabled="busy || members.length >= 4" @click="fillBots">
          补电脑
        </button>
        <button
          v-if="isHost"
          class="btn btn--primary"
          type="button"
          :disabled="!canStart || !resourcesReady"
          @click="startNow"
        >
          {{ resourcesReady ? '开始对局' : '资源加载中…' }}
        </button>
        <p v-if="!isHost" class="room__hint">等待房主开始对局…</p>
        <p v-else-if="!resourcesReady" class="room__hint">正在加载牌面与音效，加载完就能开局</p>
        <p v-else-if="members.length < 4" class="room__hint">
          还差 {{ 4 - members.length }} 人，可点「补电脑」立刻开局
        </p>
      </div>

      <p v-if="error" class="room__error">{{ error }}</p>
    </main>

    <div v-if="toast" class="toast">{{ toast }}</div>
  </div>
</template>

<style scoped>
.room {
  /* 高度由 #game-app 的 flex 撑满；内容超出时在本层滚动（理由同上） */
  min-height: 0;
  overflow-y: auto;
  padding: 18px;
  background: radial-gradient(ellipse at 50% 20%, var(--felt-600) 0%, var(--felt-800) 55%, var(--felt-900) 100%);
}
.room__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 20px;
}
.room__code {
  margin: 0;
  font-size: 19px;
  color: var(--gold-300);
  letter-spacing: 2px;
}
.room__code b {
  font-size: 24px;
  letter-spacing: 4px;
}
.room__headbtn {
  display: flex;
  align-items: center;
  gap: 8px;
}
.room__main {
  max-width: 860px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* ---------- 资源预加载进度 ---------- */
.preload {
  padding: 10px 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.preload__bar {
  height: 6px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.4);
  overflow: hidden;
}
.preload__bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, var(--gold-400), var(--gold-500));
  transition: width 0.2s ease;
}
.preload__txt {
  font-size: 12px;
  color: var(--ink-300);
}
.preload__warn {
  margin: 0;
  font-size: 12px;
  color: var(--warn);
}
.seats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}
.seatcard {
  padding: 14px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  min-height: 96px;
  justify-content: center;
}
.seatcard.is-empty {
  border-style: dashed;
  opacity: 0.7;
}
.seatcard__seat {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: linear-gradient(180deg, var(--gold-400), var(--gold-500));
  color: #2a1f06;
  font-weight: 700;
}
.seatcard__name {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 14px;
  flex-wrap: wrap;
  justify-content: center;
}
.seatcard__empty {
  color: var(--ink-400);
  font-size: 13px;
}
.tag {
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 700;
}
.tag--bot {
  background: rgba(31, 95, 168, 0.3);
  color: #9fc7f5;
}
.tag--host {
  background: var(--danger);
  color: #fff;
}
.room__actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  flex-wrap: wrap;
}
.room__hint {
  margin: 0;
  color: var(--ink-300);
  font-size: 14px;
}
.room__error {
  margin: 0;
  text-align: center;
  color: #ff8b83;
}
.toast {
  position: fixed;
  left: 50%;
  bottom: 40px;
  transform: translateX(-50%);
  padding: 9px 18px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.82);
  border: 1px solid var(--panel-border);
  color: var(--ink-100);
  font-size: 14px;
  z-index: 30;
}
</style>
