<script setup>
/*
 * 牌桌页（重写自旧的 static/game.html —— 1744 行单文件）。
 *
 * 设计分辨率 1280x720，用 LandscapeLayer 等比缩放。
 *
 * 座位方位：服务端一律用 EAST/SOUTH/WEST/NORTH 广播，前端按 mySeat 旋转。
 *   ring[0] = 我（屏幕下方）
 *   ring[1] = 下家（右侧）
 *   ring[2] = 对家（上方）
 *   ring[3] = 上家（左侧）
 *
 * 布局约定（重要，改动前先读）：
 *   · 每一家的东西都是【横放】的，包括左右两家的手牌与牌河。
 *     给左右两家额外 rotate(90deg) 会让牌面更难辨认，反而更糟。
 *   · 每一家的【副露 + 花牌】必须摆在他自己那一侧（"放在面前"），
 *     并且被吃碰杠来的那张按来源方位横放（见 MeldRow）。
 *   · 对手的手牌只显示牌背，且【不加半透明容器】—— 直接铺在桌布上。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'

import LandscapeLayer from '../components/layout/LandscapeLayer.vue'
import Tile from '../components/tile/Tile.vue'
import SeatPanel from '../components/table/SeatPanel.vue'
import DiscardRiver from '../components/table/DiscardRiver.vue'
import MeldRow from '../components/table/MeldRow.vue'
import ActionBar from '../components/table/ActionBar.vue'
import TurnTimer from '../components/table/TurnTimer.vue'
import RoundHistory from '../components/table/RoundHistory.vue'

import { useGameStore } from '../stores/game.js'
import { useLandscapeScale } from '../composables/useLandscapeScale.js'
import { onPreloadProgress, preloadAssets } from '../game/preload.js'
import { SEAT_CN, sortTiles } from '../game/tiles.js'
import { isLoggedIn, me } from '../api/api.js'
import {
  bindAudioUnlock,
  pauseBgm,
  isSfxOn,
  isMusicOn,
  toggleSfx,
  toggleMusic,
  noticeSoundOf,
  isBaoNotice,
  playNoticeVoice,
  VOICE_PHRASES,
} from '../game/audio.js'

const route = useRoute()
const router = useRouter()
const store = useGameStore()

const {
  connState,
  authError,
  roomCode,
  mySeat,
  hand,
  drawnTile,
  myMelds,
  myFlowers,
  boardMelds,
  boardFlowers,
  boardDiscards,
  counts,
  coins,
  chats,
  wall,
  players,
  turnSeat,
  lastDiscard,
  pending,
  timeoutMs,
  handNo,
  dealer,
  windIdx,
  windName,
  bottom,
  ended,
  huInfo,
  roundHistory,
} = storeToRefs(store)

const chatText = ref('')
const sfxOn = ref(isSfxOn())
const musicOn = ref(isMusicOn())
const voiceOpen = ref(false)
/** 房间消息列表面板是否展开（默认收起，点按钮才看） */
const logOpen = ref(false)

/* ---------------- 手机竖屏：手牌照大一号 ---------------- */

/*
 * 牌桌是 1280 宽的设计稿等比缩放的，手机上"我的手牌"只用 md（44x60）就显得小。
 * 用户在手机竖屏（虚拟横屏）下放大到 lg（58x79）：14 张 ≈ 851px 宽，仍在 1280 内，
 * 但会顶到右下角聊天框，所以 .table.is-phone 里把整行手牌左移一点让开（见样式）。
 * 桌面端保持 md —— 桌面那套布局有逐像素断言，不动。
 */
const { virtualLandscape, portrait } = useLandscapeScale()
const phoneBigHand = computed(() => virtualLandscape.value && portrait.value)
const handTileSize = computed(() => (phoneBigHand.value ? 'lg' : 'md'))

/*
 * 资源就绪前的遮罩。
 *
 * 正常路径上大厅已经热身、房间页也看着进度下完了，这里几乎是一闪而过；
 * 但如果用户是【直接进牌桌】（比如从"返回房间"回来、或者刷新了牌桌页），
 * 就得在这里兜住 —— 否则又回到"牌先白一下、第一次吃碰杠没声"。
 */
const pre = ref(null)
const preReady = computed(() => !pre.value || pre.value.done)
let stopPreloadWatch = null

/*
 * 胡牌者本把的得分变化。
 *
 * 后端顺序是 coins（结算后）→ hu，所以 store 在 hu 时先记下"结算前金币"(coinBefore)，
 * coins 到了才把差额写进【同一条】战绩记录。这里就从最后一条带 coinBefore 的记录里取，
 * 拿不到就先不显示（下一帧 coins 到了会自动补上）。
 */
const huScore = computed(() => {
  const info = huInfo.value
  if (!info) return null
  for (let i = roundHistory.value.length - 1; i >= 0; i--) {
    const r = roundHistory.value[i]
    if (!r || !r.coinBefore) continue
    const d = r.delta ? Number(r.delta[info.seat]) : NaN
    return Number.isFinite(d) ? d : null
  }
  return null
})

/* ---------------- 音效开关 ---------------- */

function onToggleSfx() {
  sfxOn.value = toggleSfx()
}

function onToggleMusic() {
  musicOn.value = toggleMusic()
}

function sendVoice(phrase) {
  store.chat(phrase)
  voiceOpen.value = false
}

/* ---------------- 初始化 ---------------- */

onMounted(async () => {
  if (!isLoggedIn()) {
    router.replace('/')
    return
  }

  // 资源预加载：正常已在大厅/房间页下完，这里是直接进桌时的兜底
  stopPreloadWatch = onPreloadProgress((s) => {
    pre.value = s
  })
  preloadAssets()

  // 音频解锁：浏览器要求先有用户交互才允许播放，这里注册一次性监听
  bindAudioUnlock()

  const code = (route.query.code || '').toString().trim()
  const seat = (route.query.seat || 'EAST').toString().toUpperCase()
  if (!code) {
    router.replace('/lobby')
    return
  }

  let userId = Number(localStorage.getItem('userId') || 0)
  try {
    const u = await me()
    if (u && u.id) userId = Number(u.id)
  } catch (e) {
    /* 忽略 */
  }

  store.connect(code, seat, userId)
})

/**
 * 每秒推进一次"当前时间"，让聊天气泡到期后自动消失。
 * 只在挂载期间跑，卸载时清掉，避免离开牌桌后还在空转。
 */
onMounted(() => {
  nowTimer = setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  store.disconnect()
  pauseBgm()
  if (stopPreloadWatch) stopPreloadWatch()
  if (nowTimer) {
    clearInterval(nowTimer)
    nowTimer = null
  }
  // 结算提示的定时器（逐条错开播报 + 自动隐藏）
  clearNoticeTimers()
})
/* ---------------- 派生数据 ---------------- */

const mySeatCn = computed(() => SEAT_CN[mySeat.value] || '')

/** 三个对手：[右(下家), 上(对家), 左(上家)] */
const opponentSeats = computed(() => store.opponents)
const rightSeat = computed(() => opponentSeats.value[0])
const topSeat = computed(() => opponentSeats.value[1])
const leftSeat = computed(() => opponentSeats.value[2])

/**
 * 我自己的名字。
 *
 * 三个对手的名字由各自的 SeatPanel 渲染，而"我"没有 SeatPanel（我的手牌是
 * 正面、直接铺在底部），所以原来整张桌子上看不到自己的名字 —— 用户报过。
 * 这里单独在"局内战绩"按钮旁边补一个。
 */
const myNickname = computed(() => store.nickname || '我')

/**
 * 本把里"值得出声"的结算事件：包牌 + 花分。
 *
 * 数据来源是后端结算 notes（随 coins 广播），例如：
 *   包牌 "包三道·代付三家" / "包四道·代付三家"
 *   花分 "真花·四季、真对花"（注意一条 note 里可能用「、」拼了多件事）
 */
const noticeEvents = computed(() => {
  const list = roundHistory.value || []
  if (!list.length) return []
  const notes = list[list.length - 1].notes
  if (!notes) return []
  const out = []
  for (const seat of Object.keys(notes)) {
    for (const raw of notes[seat] || []) {
      // 一条 note 可能是"真花·四季、真对花"这样拼接的，按「、」拆开逐条判断
      for (const tag of String(raw).split('、')) {
        const sound = noticeSoundOf(tag)
        if (sound) {
          out.push({ seat, sound, tag, isBao: isBaoNotice(tag), text: `${SEAT_CN[seat] || seat}家 ${tag}` })
        }
      }
    }
  }
  return out
})

/**
 * 要显示成文字的只有【包牌】。
 *
 * 花分（真花/假花/对花）照旧版是"只读语音、屏幕不显示"—— 花是每局都可能出现的小分，
 * 每把都往中间糊一条金字会把牌桌弄得很吵；而包牌是"一个人赔三家"，
 * 必须让人一眼看见是谁、因为哪条规则。
 */
const noticeTextEvents = computed(() => noticeEvents.value.filter((e) => e.isBao))

/** 事件列表的稳定指纹，用于 watch 去重（computed 每次返回新数组） */
const noticeKey = computed(() =>
  noticeEvents.value.map((e) => `${e.text}:${e.sound}`).join('|'),
)

const noticeToast = ref('')
let noticeTimers = []

function clearNoticeTimers() {
  noticeTimers.forEach((t) => clearTimeout(t))
  noticeTimers = []
}

watch(noticeKey, () => {
  const evs = noticeEvents.value
  clearNoticeTimers()
  if (!evs.length) return
  // 文字只给包牌；花分只有声音
  noticeToast.value = noticeTextEvents.value.map((e) => e.text).join('　')
  /*
   * 逐条错开 1 秒播报：一局里可能同时有"包三道"和"真花"，
   * 两个音效叠在一起就都听不清了。
   */
  evs.forEach((e, i) => {
    noticeTimers.push(setTimeout(() => playNoticeVoice(e.sound, e.tag), i * 1000))
  })
  noticeTimers.push(
    setTimeout(() => {
      noticeToast.value = ''
    }, 4500 + evs.length * 1000),
  )
})

/* ---------------- 聊天 ---------------- */

/**
 * 当前时间（每秒跳一次）。
 *
 * 用途：消息气泡要"闪一下就消失"。直接依赖 chats 数组不会让模板重新求值，
 * 所以用一个每秒递增的 now 触发重算，气泡到期自然隐藏。
 */
const now = ref(Date.now())
let nowTimer = null
const BUBBLE_MS = 6000

/** 最近一条消息（用于消息列表和"我的气泡"） */
const lastChat = computed(() => {
  const list = chats.value || []
  return list.length ? list[list.length - 1] : null
})

/** 我的气泡：只有最近这条是我发的、且还没过期才显示 */
const myBubble = computed(() => {
  const m = lastChat.value
  if (!m || m.seat !== mySeat.value) return ''
  return now.value - m.at < BUBBLE_MS ? m.text : ''
})

/**
 * 每个座位当前该显示的气泡文本。
 * 同一家连续发言时只显示最新那条，过期就消失。
 */
const bubblesBySeat = computed(() => {
  const out = {}
  for (const m of chats.value || []) {
    if (now.value - m.at < BUBBLE_MS) out[m.seat] = m.text
    else delete out[m.seat]
  }
  return out
})

/** 消息列表：把 seat 换成昵称，最新的在最后 */
const chatLog = computed(() =>
  (chats.value || []).map((m) => ({
    key: `${m.at}-${m.seat}`,
    seat: m.seat,
    seatCn: SEAT_CN[m.seat] || '',
    nickname: nicknameOf(m.seat),
    text: m.text,
    mine: m.seat === mySeat.value,
  })),
)

function seatInfo(seat) {
  return {
    seat,
    seatCn: SEAT_CN[seat] || '',
    nickname: nicknameOf(seat),
    count: counts.value[seat] ?? 0,
    coins: coins.value[seat] ?? 0,
    isTurn: turnSeat.value === seat,
    isDealer: dealer.value === seat,
    flowersCount: ((boardFlowers.value && boardFlowers.value[seat]) || []).length,
  }
}

function nicknameOf(seat) {
  const p = (players.value || []).find((x) => x.seat === seat)
  if (p && p.nickname) return p.nickname
  if (seat === mySeat.value) return store.nickname || '我'
  return SEAT_CN[seat] + '家'
}

/** 某家副露 / 花牌（board 消息里按座位名索引） */
function meldsOf(seat) {
  return (boardMelds.value && boardMelds.value[seat]) || []
}
function flowersOf(seat) {
  return (boardFlowers.value && boardFlowers.value[seat]) || []
}
/*
 * 注意：这里【没有】"副露旋转补偿"之类的计算了。
 * 上一版为了把"整块转 ±90°"的内容对齐到手牌，需要按副露长度算一个偏移量，
 * 还得乘标定系数 —— 那套东西已经整体删掉，改由 MeldRow 内部
 * "每张牌各自横放 + 副露排成一列"实现，布局盒永远等于内容，不需要补偿。
 */

/** 我的手牌：把刚摸的那张单独抽出来放在最右（传统摆法） */
const handMain = computed(() => {
  const all = sortTiles(hand.value || [])
  if (drawnTile.value < 0) return all
  const i = all.indexOf(drawnTile.value)
  if (i < 0) return all
  const copy = all.slice()
  copy.splice(i, 1)
  return copy
})

const handDrawn = computed(() => (drawnTile.value >= 0 ? drawnTile.value : -1))
const banned = computed(() => (pending.value && pending.value.ban) || [])
const canDiscard = computed(() => store.canDiscard)

function riverOf(seat) {
  return (boardDiscards.value && boardDiscards.value[seat]) || []
}

function riverHighlightIndex(seat) {
  const arr = riverOf(seat)
  if (!lastDiscard.value || lastDiscard.value.seat !== seat) return -1
  return arr.length - 1
}

/* ---------------- 交互 ---------------- */

function onPickTile(tile) {
  store.discardTile(tile)
}

function onQuit() {
  store.leaveRoom()
  setTimeout(() => router.replace('/lobby'), 150)
}

function onRematch() {
  store.rematch()
}

function sendChat() {
  const t = chatText.value.trim()
  if (!t) return
  if (store.chat(t)) chatText.value = ''
}

const isMyTurnSafe = computed(() => turnSeat.value === mySeat.value)

/** 当前询问类型的中文，用于回合提示 */
const pendingKindCn = computed(() => {
  const k = pending.value && pending.value.kind
  if (k === 'discard') return '请出牌'
  if (k === 'action') return '可吃碰杠胡'
  if (k === 'draw') return '请选择动作'
  return ''
})

/**
 * 手牌区在"点不动"时该显示的原因。
 * 不显示的话玩家只看到牌却不能点，只能得出"卡住了"的结论。
 */
const handHint = computed(() => {
  if (ended.value) return '本把已结束'
  if (pending.value) return ''
  if (isMyTurnSafe.value) return '等待服务端下发出牌许可…'
  return '还没轮到你出牌'
})
</script>

<template>
  <LandscapeLayer>
    <div class="table" :class="{ 'is-phone': phoneBigHand }">
      <!-- 顶部信息条 -->
      <header class="table__top">
        <div class="table__top-left">
          <span class="chip chip--gold">房间 {{ roomCode }}</span>
          <span class="chip">第 {{ handNo }} 把</span>
          <span v-if="windName" class="chip">{{ windName }}令 · 底 {{ bottom }}</span>
          <span v-if="wall >= 0" class="chip" :class="{ 'chip--danger': wall <= 20 }">
            剩 {{ wall }} 张
          </span>
        </div>
        <div class="table__top-right">
          <span class="chip" :class="connState === 'open' ? 'chip--ok' : 'chip--warn'">
            {{ connState === 'open' ? '已连接' : connState === 'connecting' ? '连接中…' : '已断开' }}
          </span>
          <span class="chip">我 · {{ mySeatCn }}位</span>
          <button class="btn btn--ghost btn--sm" type="button" :title="sfxOn ? '关闭音效' : '开启音效'" @click="onToggleSfx">
            {{ sfxOn ? '🔊' : '🔇' }}
          </button>
          <button class="btn btn--ghost btn--sm" type="button" :title="musicOn ? '关闭背景音乐' : '开启背景音乐'" @click="onToggleMusic">
            {{ musicOn ? '🎵' : '🔕' }}
          </button>
          <button class="btn btn--ghost btn--sm" type="button" @click="onQuit">退出</button>
        </div>
      </header>

      <!--
        ===== 对家（上）=====
        从桌边往中心：手牌 → 副露（→ 牌河在中央），名字条是浮层不占位。

        朝向规则：每一家的东西都要在【那一家自己的视角】下可读。
        对家坐在对面看牌，所以他眼里的"正"在我屏幕上是倒的 → 整块转 180°。
      -->
      <div class="seat seat--top">
        <SeatPanel :info="seatInfo(topSeat)" orientation="top" backs-only overlay />
        <MeldRow
          :melds="meldsOf(topSeat)"
          :flowers="flowersOf(topSeat)"
          :owner-seat="topSeat"
          orientation="top"
          size="xs"
          align="center"
        />
        <SeatPanel
          :info="seatInfo(topSeat)"
          orientation="top"
          meta-only
          overlay
          :chat="bubblesBySeat[topSeat] || ''"
        />
      </div>

      <!--
        ===== 上家（左）=====
        从桌边往中心：手牌（最左）→ 副露 → 牌河；名字条贴在座位左边线上。
        每一家看自己的区域都是正的：上家在我屏幕上是转 90°。
      -->
      <div class="seat seat--left">
        <SeatPanel :info="seatInfo(leftSeat)" orientation="left" backs-only overlay />
        <MeldRow
          :melds="meldsOf(leftSeat)"
          :flowers="flowersOf(leftSeat)"
          :owner-seat="leftSeat"
          orientation="left"
          size="xs"
          align="center"
        />
        <SeatPanel
          :info="seatInfo(leftSeat)"
          orientation="left"
          meta-only
          overlay
          :chat="bubblesBySeat[leftSeat] || ''"
        />
      </div>

      <!-- ===== 下家（右）：与上家镜像，转 -90° ===== -->
      <div class="seat seat--right">
        <SeatPanel :info="seatInfo(rightSeat)" orientation="right" backs-only overlay />
        <MeldRow
          :melds="meldsOf(rightSeat)"
          :flowers="flowersOf(rightSeat)"
          :owner-seat="rightSeat"
          orientation="right"
          size="xs"
          align="center"
        />
        <SeatPanel
          :info="seatInfo(rightSeat)"
          orientation="right"
          meta-only
          overlay
          :chat="bubblesBySeat[rightSeat] || ''"
        />
      </div>

      <!-- ===== 中央牌河 ===== -->
      <div class="table__center">
        <DiscardRiver
          :top="riverOf(topSeat)"
          :right="riverOf(rightSeat)"
          :bottom="riverOf(mySeat)"
          :left="riverOf(leftSeat)"
          :highlight="{
            top: riverHighlightIndex(topSeat),
            right: riverHighlightIndex(rightSeat),
            bottom: riverHighlightIndex(mySeat),
            left: riverHighlightIndex(leftSeat),
          }"
        />

        <div class="center-pop">
          <TurnTimer v-if="pending && timeoutMs > 0" :key="pending.reqId" :ms="timeoutMs" :urgent="true" />

          <div v-if="huInfo" class="hu-banner">
            <b>{{ SEAT_CN[huInfo.seat] }}家 胡牌</b>
            <span>{{ (huInfo.fans || []).join('、') || '平胡' }}</span>
            <!--
              赢家本把的得分变化：光有"谁胡了 + 番型"看不出赢了多少，这里补上。
              数据来自 store：hu 时先记下结算前金币（coinBefore），coins 到了才把差额
              写进同一条战绩记录，所以这里读"本把那条记录"的 delta（见 huScore）。
            -->
            <span v-if="huScore !== null" class="hu-banner__score" :class="huScore > 0 ? 'is-up' : huScore < 0 ? 'is-down' : ''">
              {{ huScore > 0 ? `+${huScore}` : huScore }}
            </span>
            <span v-if="huInfo.selfDraw" class="hu-banner__tag">自摸</span>
            <span v-if="huInfo.ganKai" class="hu-banner__tag">杠开</span>
            <!--
              包牌/花分的文字【不放这里】：横幅已经有"谁胡了 + 番型 + 自摸/杠开"，
              再塞一条长文案会把横幅撑宽、并且和番型挤在一起（用户要求不要重叠）。
              这些提示改由下面独立的 .bao-toast 显示，位置在横幅之下，互不遮挡。
            -->
          </div>

          <div v-else-if="!ended" class="turn-hint" :class="{ 'is-me': isMyTurnSafe }">
            <!--
              "轮到你 · 可吃碰杠胡" 不再显示：这时操作栏已经列出了具体按钮，
              中间再挂一句同样的提示是冗余的（用户明确要求去掉）。
              出牌/摸牌这两个提示保留 —— 它们说的是"该干什么"，操作栏里没有。
            -->
            <template v-if="pending && pending.kind !== 'action'">轮到你 · {{ pendingKindCn }}</template>
            <template v-else-if="!pending && isMyTurnSafe">轮到你，正在等服务端下发可出牌状态…</template>
            <template v-else-if="turnSeat">等待 {{ SEAT_CN[turnSeat] }}家 出牌…</template>
            <template v-else>等待开局…</template>
          </div>

          <!--
            包牌/花分提示：放在胡牌横幅【下面】（同一个 flex 列，自然错开），
            不会和番型挤在一起。局内一发生就闪一下，配播报语音。
          -->
          <div v-if="noticeToast" class="bao-toast">
            <span class="bao-toast__icon">💰</span>
            <span class="bao-toast__text">{{ noticeToast }}</span>
          </div>
        </div>
      </div>

      <!-- ===== 局内战绩（按钮，点开看本房间的完整明细）+ 我自己的名字 ===== -->
      <div class="table__history">
        <RoundHistory :room-code="roomCode" :rounds="roundHistory" :my-seat="mySeat" :seat-cn="SEAT_CN" />
        <!--
          我自己的名字 + 金币。三个对手的名字由各自的 SeatPanel 渲染，
          而"我"没有 SeatPanel（我的手牌是正面、直接铺在底部），
          所以原来整张桌子看不到我自己的名字。
        -->
        <span class="selfname">
          <i class="selfname__seat">{{ SEAT_CN[mySeat] }}</i>
          <b class="selfname__nick">{{ myNickname }}</b>
          <span class="selfname__num">币 {{ coins[mySeat] ?? 0 }}</span>
          <!-- 轮到我坐庄时也要标"庄"：三个对手由 SeatPanel 标，我自己这里原来漏了 -->
          <span v-if="dealer === mySeat" class="selfname__dealer">庄</span>
          <span class="selfname__tag">我</span>
        </span>
      </div>

      <!-- ===== 操作栏 ===== -->
      <div class="table__actions">
        <ActionBar
          :pending="pending"
          :disabled="ended"
          @act="store.actOption($event)"
          @pass="store.pass()"
          @baoting="store.baoTing()"
        />
      </div>

      <!-- ===== 我自己的消息气泡（贴在我手牌上方）===== -->
      <div v-if="myBubble" class="table__mybubble">
        <span class="bubble bubble--me">{{ myBubble }}</span>
      </div>

      <!-- ===== 消息列表（收在按钮里，点开才看）===== -->
      <div v-if="logOpen && chatLog.length" class="table__chatlog">
        <div class="chatlog__head">
          <span>房间消息 · 共 {{ chatLog.length }} 条</span>
          <button class="chatlog__close" type="button" @click="logOpen = false">关闭</button>
        </div>
        <ul class="chatlog__list">
          <li v-for="c in chatLog" :key="c.key" class="chatlog__item" :class="{ 'is-mine': c.mine }">
            <span class="chatlog__seat">{{ c.seatCn }}</span>
            <b class="chatlog__nick">{{ c.nickname }}</b>
            <span class="chatlog__text">{{ c.text }}</span>
          </li>
        </ul>
      </div>

      <!-- ===== 我的副露 + 花牌（摆在我手牌前面）===== -->
      <!--
        没有副露/花牌时整块【不渲染】—— 原来它是固定 min-height 的空容器，
        没牌时就成了手牌上方一个突兀的黑框。
      -->
      <div v-if="myMelds.length || myFlowers.length" class="table__mymelds">
        <MeldRow
          :melds="myMelds"
          :flowers="myFlowers"
          :owner-seat="mySeat"
          size="sm"
          align="center"
          mine
        />
      </div>

      <!-- ===== 我的手牌 ===== -->
      <div class="table__hand">
        <div class="hand">
          <Tile
            v-for="(t, i) in handMain"
            :key="`h${i}-${t}`"
            :tile="t"
            :size="handTileSize"
            :clickable="canDiscard"
            :banned="canDiscard && banned.includes(t)"
            :highlight="t === lastDiscard?.tile && lastDiscard?.seat === mySeat"
            @pick="onPickTile"
          />
          <span v-if="handDrawn >= 0" class="hand__sep" aria-hidden="true" />
          <Tile
            v-if="handDrawn >= 0"
            :tile="handDrawn"
            :size="handTileSize"
            class="hand__drawn"
            :clickable="canDiscard"
            :banned="canDiscard && banned.includes(handDrawn)"
            @pick="onPickTile"
          />
          <span v-if="!handMain.length && handDrawn < 0" class="hand__empty">等待发牌…</span>
        </div>

        <div v-if="handHint" class="hand__hint">{{ handHint }}</div>
      </div>

      <!-- ===== 底部：聊天 ===== -->
      <div class="table__bottom">
        <!--
          常用语 / 房间消息两个按钮放在【发送按钮上方】。
          它们不是高频操作，占着输入框左右会挤压打字空间；挪到上一行后
          输入框可以更宽，也不会误点。
        -->
        <div class="chat__tools">
          <button class="btn btn--sm" type="button" title="常用语" @click="voiceOpen = !voiceOpen">💬 常用语</button>
          <button
            class="btn btn--sm"
            type="button"
            :title="`房间消息（${chatLog.length}）`"
            @click="logOpen = !logOpen"
          >
            📋 消息<span v-if="chatLog.length" class="btn__badge">{{ chatLog.length }}</span>
          </button>
        </div>

        <div class="chat">
          <input
            v-model="chatText"
            class="chat__input"
            type="text"
            maxlength="80"
            placeholder="说点什么…"
            @keyup.enter="sendChat"
          />
          <button class="btn btn--sm" type="button" @click="sendChat">发送</button>
        </div>

        <div v-if="voiceOpen" class="voices panel">
          <button v-for="p in VOICE_PHRASES" :key="p" class="voices__item" type="button" @click="sendVoice(p)">
            {{ p }}
          </button>
        </div>

        <button
          v-if="ended && !store.playing && store.isHost && !store.disbanded"
          class="btn btn--primary"
          type="button"
          @click="onRematch"
        >
          再来一局
        </button>
        <span v-else-if="ended && !store.playing && !store.isHost" class="ended-hint">
          {{ store.disbanded ? '房间已解散' : '等待房主开始下一局…' }}
        </span>
      </div>

      <!-- 连接状态横幅：不拦截点击，绝不挡住牌桌 -->
      <div
        v-if="connState !== 'open' || authError"
        class="connbar"
        :class="authError ? 'connbar--bad' : 'connbar--warn'"
      >
        <span class="connbar__text">
          <template v-if="authError">{{ authError }}</template>
          <template v-else-if="connState === 'connecting'">正在连接牌局…</template>
          <template v-else>与服务器的连接已断开，正在重连…</template>
        </span>
        <button v-if="authError" class="btn btn--sm btn--primary" type="button" @click="router.replace('/')">
          重新登录
        </button>
      </div>

      <!--
        资源遮罩：牌面/音效没下完就先盖住牌桌（直接刷新进桌、或从"返回房间"回来时用到）。
        正常路径（大厅 → 房间页 → 牌桌）几乎看不见它。
      -->
      <div v-if="!preReady" class="resveil">
        <div class="resveil__box">
          <b>正在加载牌面与音效</b>
          <div class="resveil__bar"><i :style="{ width: (pre ? pre.percent : 0) + '%' }"></i></div>
          <span>{{ pre ? `${pre.percent}%（${pre.loaded}/${pre.total}）` : '' }}</span>
        </div>
      </div>
    </div>
  </LandscapeLayer>
</template>

<style scoped>
.table {
  position: relative;
  width: 1280px;
  /* 高度跟随固定框（--design-h）：手机竖屏时固定框会压到 640，整体就能等比放大；
     桌面/横屏时 --design-h 是 720，与原来完全一致 */
  height: var(--design-h, 720px);
  background: radial-gradient(ellipse at 50% 42%, var(--felt-600) 0%, var(--felt-800) 55%, var(--felt-900) 100%);
  overflow: hidden;
}

/* ---------- 顶部信息条 ---------- */
.table__top {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 14px;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.42), transparent);
  z-index: 10;
}
.table__top-left,
.table__top-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.chip {
  padding: 3px 10px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.34);
  border: 1px solid rgba(255, 255, 255, 0.12);
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-200);
}
.chip--gold {
  border-color: var(--gold-500);
  color: var(--gold-300);
}
.chip--ok {
  border-color: rgba(53, 176, 111, 0.7);
  color: #8ee0b4;
}
.chip--warn {
  border-color: rgba(224, 167, 44, 0.7);
  color: #f0cd74;
}
.chip--danger {
  border-color: rgba(217, 83, 79, 0.85);
  color: #ffb3ad;
}
.ended-hint {
  font-size: 13px;
  color: var(--ink-300);
}
.btn--sm {
  padding: 4px 12px;
  font-size: 12px;
}

/* ---------- 四家位置 ---------- */
/*
 * 网格定位（三家的 DOM 顺序都一样，不再靠 row-reverse 翻）：
 *
 *   对家（纵向）  行1 手牌 / 行2 副露
 *   上家（横向）  列1 副露 / 列2 手牌      ← 手牌贴桌边，副露朝桌心
 *   下家（横向）  列1 副露 / 列2 手牌      ← 同上，镜像由列号表达
 *
 * 名字那一块（is-meta 的 SeatPanel）完全脱离栅格，见下面 .seat > .seatpanel.is-meta。
 */
.seat {
  position: absolute;
  display: grid;
  gap: 2px;
}

/*
 * 四家的朝向（核心规则）
 * =====================
 * 要求：每一家的手牌 / 名字 / 副露 / 牌河，在【那一家自己的视角】下都可读。
 * 也就是每一位玩家看自己的区域都是正的。以我（下方）为 0°：
 *
 *   我（下）   0°
 *   对家（上） 180°
 *   上家（左） +90°
 *   下家（右） -90°
 *
 * 实现要点：旋转量通过 --el-rot 传给【各个元素自己】（SeatPanel 的牌背/名字、
 * MeldRow 里每张牌），不旋转外层容器 —— 旋转不改变布局包围盒，
 * 转外层会让它按未旋转的尺寸占位而溢出。
 *
 * 位置（用户定的顺序，三家统一）：
 *   从【屏幕最外侧】往【桌心】：名字 → 手牌 → 副露
 *   也就是名字贴屏幕边，手牌紧挨名字，副露紧挨手牌（副露最靠近牌河）。
 *   三家都靠"名字独占一行/一列"来同时满足"贴边"和"紧挨手牌"。
 */
.seat--top {
  top: 40px;
  left: 50%;
  transform: translateX(-50%);
  /* 三行（自上而下 = 从屏幕外侧到桌心）：名字 → 手牌 → 副露 */
  grid-template-columns: minmax(0, 1fr);
  grid-template-rows: auto auto auto;
  justify-items: center;
  align-content: start;
  --el-rot: 180deg;
}

.seat--left,
.seat--right {
  top: 0;
  bottom: 0;
  width: 720px;
  /* 绝对坐标定位，不用栅格轨道 —— 见下面 .seat--left/right > * 的说明 */
  display: block;
}

/*
 * 侧边两家：三块全部【绝对定位】，不再让栅格给它们分配轨道。
 *
 * 为什么放弃栅格定尺（踩了很久）：
 *   栅格子项的 min-width 默认是 auto，内容（13 张牌背约 402px）会把轨道撑开，
 *   写死的列宽根本不起作用 —— 实测给出 `grid-template-columns: 41px 125px`，
 *   浏览器确实解析成 41px/125px，但手牌实际占了 317px 并从列外开始画，
 *   副露位置随之全错。min-width: 0 也压不住（flex 子项本身有固定宽度）。
 *
 * 绝对定位之后：每块的尺寸只由自己决定，位置由 inset 直接给定。
 *
 * 顺序与间距（从屏幕外侧到桌心，用户要求"贴屏幕边缘、副露紧密"）：
 *   名字（贴屏幕边） → 手牌 → 副露（紧贴手牌）
 *
 * 下面的数字是实测量出来的（docs/browser-probe.py 会报"离屏幕边距离"）：
 *   名字视觉条占 design 4~24；手牌那一列的【布局】宽 30，但每张牌转 90° 后
 *   视觉宽 41（左右各多 5.5）；副露同理。所以 left 要给足，
 *   让三者的【视觉边缘】依次相接，而不是让布局盒相接。
 */
.seat--left > .seatpanel.is-backs {
  position: absolute;
  left: 28px;
  top: 50%;
  transform: translateY(-50%);
}
.seat--left > .meldrow {
  position: absolute;
  left: 72px;
  top: 50%;
  transform: translateY(-50%);
}
.seat--right > .seatpanel.is-backs {
  position: absolute;
  right: 28px;
  top: 50%;
  transform: translateY(-50%);
}
.seat--right > .meldrow {
  position: absolute;
  right: 72px;
  top: 50%;
  transform: translateY(-50%);
}
.seat--left {
  left: 4px;
  --el-rot: 90deg;
}
.seat--right {
  right: 4px;
  --el-rot: -90deg;
}

/*
 * 名字块：零尺寸 + 绝对定位，钉在座位最外侧的那条边线上 —— 它是一个"锚点"。
 *
 * 为什么这样做（三种写法都试过，前两种都会坏）：
 *   1. 名字和手牌同格再往里挪 → 名字永远骑在手牌或副露上；
 *   2. 名字正常参与栅格（独占一条 auto 轨道）→ 名字条是 absolute、不参与主流，
 *      名字块自身量成 0x0，auto 轨道跟着塌成 0，整行/整列尺寸全错
 *      （实测 cols 解析成 `0px 402px 125px`，手牌和副露全跑到屏幕外）；
 *   3. 给名字固定轨道尺寸 → 名字条比轨道大，又得靠猜边距。
 *
 * 现在：名字块尺寸为 0（含 padding/border），栅格不必为它留空间；
 * 它自己 absolute 钉在座位边线上，名字条再在这个锚点上按座位旋转平移。
 */
.seat > .seatpanel.is-meta {
  position: absolute;
  top: 0;
  left: 0;
  width: 0;
  height: 0;
  padding: 0;
  border: 0;
  overflow: visible;
}
.seat--top > .seatpanel.is-backs {
  grid-column: 1;
  grid-row: 2;
}
.seat--top > .meldrow {
  grid-column: 1;
  grid-row: 3;
  justify-self: center;
}
/* 对家锚点：座位顶边中点（屏幕最上方，手牌正上方） */
.seat--top > .seatpanel.is-meta {
  left: 50%;
  top: 0;
}
/* 上家锚点：座位左边线（屏幕最左） */
.seat--left > .seatpanel.is-meta {
  left: 0;
  top: 50%;
}
/* 下家锚点：座位右边线（屏幕最右） */
.seat--right > .seatpanel.is-meta {
  left: auto;
  right: 0;
  top: 50%;
}

/* ---------- 中央 ---------- */
.table__center {
  position: absolute;
  left: 50%;
  /*
   * 【下对齐】而不是 top: 232px —— 两者在桌面下位置完全一样（720 高的框里
   * 720-168-320 = 232），但手机上设计框会压到 640：
   *   牌河跟着一起上移，与"我的副露/手牌"（同样是下对齐）保持原来那 18px 间距。
   *   写 top: 232px 的话牌河不动，会被上移 80px 的副露/手牌压住 —— 实测撞上了。
   */
  bottom: 168px;
  transform: translateX(-50%);
  width: 500px;
  height: 320px;
}
.center-pop {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  pointer-events: none;
}
.turn-hint {
  padding: 5px 14px;
  border-radius: 999px;
  background: rgba(4, 22, 16, 0.72);
  border: 1px solid rgba(255, 255, 255, 0.14);
  color: var(--ink-200);
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
}
.turn-hint.is-me {
  border-color: var(--gold-500);
  color: var(--gold-300);
  box-shadow: 0 0 14px rgba(215, 178, 90, 0.3);
}
.hu-banner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 10px 18px;
  border-radius: 10px;
  background: rgba(8, 33, 26, 0.92);
  border: 1px solid var(--gold-500);
  color: var(--gold-300);
  box-shadow: 0 8px 26px rgba(0, 0, 0, 0.5);
}
.hu-banner__tag {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--danger);
  color: #fff;
}
/* 赢家本把得分：比番型更醒目一点（绿涨红跌，与战绩页同一套颜色） */
.hu-banner__score {
  font-size: 17px;
  font-weight: 800;
}
.is-up {
  color: #8ee0b4;
}
.is-down {
  color: #ff8b83;
}
/* 包牌提示：比普通番型标签更醒目，因为它是"为什么一个人赔三家"的解释 */
.hu-banner__bao {
  margin-top: 2px;
  padding: 2px 10px;
  border-radius: 999px;
  background: linear-gradient(180deg, #ffd66b, #e0972a);
  color: #3a2405;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

/* ---------- 包牌/花分提示 ---------- */
/*
 * 跟着 .center-pop 的 flex 列排，自然落在胡牌横幅【下方】——
 * 不用写死坐标，横幅几行都不影响，两者永远不会叠在一起。
 * pointer-events: none —— 它只是提示，绝不能挡住牌桌上的点击。
 */
.bao-toast {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  padding: 8px 20px;
  border-radius: 999px;
  background: linear-gradient(180deg, rgba(255, 214, 107, 0.98), rgba(224, 151, 42, 0.98));
  color: #3a2405;
  font-size: 17px;
  font-weight: 800;
  white-space: nowrap;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.5);
  pointer-events: none;
  animation: baoPop 0.3s ease-out;
}
.bao-toast__icon {
  font-size: 19px;
}
@keyframes baoPop {
  from {
    opacity: 0;
    transform: scale(0.85);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}

/* ---------- 局内战绩 + 我自己的名字 ---------- */
.table__history {
  position: absolute;
  left: 10px;
  bottom: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
}
/*
 * 我自己的名字：和"局内战绩"按钮同一行并排。
 * 朝向固定 0°（我就是屏幕下方那一家，本来就不用转）。
 */
.selfname {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.38);
  border: 1px solid rgba(215, 178, 90, 0.45);
  font-size: 11px;
  white-space: nowrap;
}
.selfname__seat {
  width: 15px;
  height: 15px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  background: linear-gradient(180deg, var(--gold-400), var(--gold-500));
  color: #2a1f06;
  font-style: normal;
  font-weight: 700;
  font-size: 10px;
}
.selfname__nick {
  max-width: 88px;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--ink-100);
  font-weight: 600;
}
.selfname__num {
  color: var(--ink-300);
}
/* 轮到我坐庄：和三个对手的"庄"标记同一个红底样式 */
.selfname__dealer {
  padding: 0 4px;
  border-radius: 3px;
  background: var(--danger);
  color: #fff;
  font-weight: 700;
  font-size: 10px;
}

/* ---------- 消息气泡 ---------- */
/*
 * 气泡贴着说话那一家的名字条显示（对手的在 SeatPanel 里），
 * 我自己的贴在底部手牌上方。6 秒后由 GameTable 的 now 计时自动隐藏。
 */
.bubble {
  display: inline-block;
  max-width: 280px;
  padding: 3px 9px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.35;
  color: #2a1f06;
  background: linear-gradient(180deg, #ffe6a8, var(--gold-400));
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.45);
  word-break: break-word;
}
.bubble--me {
  color: #06251a;
  background: linear-gradient(180deg, #b7f0d2, #6fd3a2);
}

/* 我自己的气泡：手牌上方居中 */
.table__mybubble {
  position: absolute;
  left: 50%;
  bottom: 152px;
  transform: translateX(-50%);
  z-index: 12;
  pointer-events: none;
}

/* ---------- 消息列表 ---------- */
/*
 * 收在按钮里的面板：从底部按钮那一行往上展开。
 * 位置在聊天输入框左侧上方，不压牌河也不挡手牌。
 */
.table__chatlog {
  position: absolute;
  right: 10px;
  /* 抬到底部那两行之上 */
  bottom: 82px;
  width: 300px;
  max-height: 210px;
  padding: 6px 9px;
  border-radius: 10px;
  background: rgba(4, 22, 16, 0.94);
  border: 1px solid var(--panel-border);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.5);
  overflow-y: auto;
  z-index: 20;
}
.chatlog__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: var(--ink-300);
  margin-bottom: 4px;
}
.chatlog__close {
  border: 0;
  border-radius: 5px;
  padding: 1px 7px;
  background: rgba(255, 255, 255, 0.1);
  color: var(--ink-200);
  font-size: 10px;
  cursor: pointer;
}
.chatlog__close:hover {
  background: rgba(215, 178, 90, 0.28);
  color: var(--gold-300);
}
.chatlog__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.chatlog__item {
  display: flex;
  align-items: baseline;
  gap: 4px;
  font-size: 11px;
  color: var(--ink-200);
}
.chatlog__item.is-mine {
  color: var(--gold-300);
}
.chatlog__seat {
  flex: 0 0 auto;
  width: 14px;
  height: 14px;
  line-height: 14px;
  text-align: center;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.14);
  font-size: 9px;
}
.chatlog__nick {
  flex: 0 0 auto;
  max-width: 64px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chatlog__text {
  flex: 1 1 auto;
  word-break: break-word;
}
/* 消息按钮上的未读数角标 */
.btn__badge {
  margin-left: 4px;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--gold-500);
  color: #2a1f06;
  font-size: 10px;
  font-weight: 700;
}
.selfname__tag {
  padding: 0 4px;
  border-radius: 3px;
  background: var(--gold-500);
  color: #2a1f06;
  font-size: 9px;
  font-weight: 700;
}

/* ---------- 操作栏 ---------- */
.table__actions {
  position: absolute;
  left: 50%;
  /* 往上挪，给"副露 + 手牌"留出空间：
     副露在手牌的正上方，操作栏又在副露之上 */
  bottom: 214px;
  transform: translateX(-50%);
}

/* ---------- 手牌、操作栏等：手机竖屏的纵向重排见上面的 .table.is-phone 块 ---------- */

/* ---------- 资源加载遮罩 ---------- */
.resveil {
  position: absolute;
  inset: 0;
  z-index: 40;
  display: grid;
  place-items: center;
  background: rgba(6, 24, 18, 0.92);
}
.resveil__box {
  width: 320px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  color: var(--ink-200);
  font-size: 13px;
}
.resveil__bar {
  width: 100%;
  height: 6px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.45);
  overflow: hidden;
}
.resveil__bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, var(--gold-400), var(--gold-500));
  transition: width 0.2s ease;
}

/* ============================================================
   手机竖屏（虚拟横屏）：重新分配纵向预算
   ------------------------------------------------------------
   设计高被压到 --design-h（见 useLandscapeScale 的 COMPACT_DESIGN_H），
   竖直方向已经排满。这里把顶栏压薄、牌河框压矮，腾出的空间全部给
   【我的手牌】：手牌从 md(44x60) 放大到 lg(58x79)。
   预算（设计 px，总高 590）：
     顶栏 30 + 对家手牌 78 + 14 + 牌河 278 + 18 + 我的副露 44 + 15 + 手牌 103 + 10 = 590
   桌面端命中不了这个类，布局一个像素都不动（桌面有逐像素断言）。
   ============================================================ */
.table.is-phone .table__top {
  height: 30px;
}
.table.is-phone .seat--top {
  top: 30px;
}
.table.is-phone .table__center {
  bottom: 190px;
  height: 278px;
}
.table.is-phone .table__mymelds {
  bottom: 128px;
}
/*
 * 手牌放到 lg 之后整行约 851px 宽，居中会顶到右下角聊天框（那个盒子从 x≈1044 开始），
 * 所以整行左移 45px 让开 —— 相对桌面居中只偏约 3.5%，肉眼看不出来。
 */
.table.is-phone .table__hand {
  left: calc(50% - 45px);
}
/*
 * 操作栏：放在"屏幕中间靠右下、聊天框左上角"那一块空当里。
 * 横排 4~5 个按钮要 370+ 宽，右下角根本没有这么宽的空位（左边是我的手牌和副露，
 * 右边是下家的手牌），所以手机上改成【竖排一列】，正好卡在
 * 下家手牌（x≳1100）与我的副露（x≲960）之间的空当，且不压聊天框（v≥519）。
 */
.table.is-phone .table__actions {
  left: auto;
  right: 214px;
  bottom: 108px;
  transform: none;
}
.table.is-phone .table__actions :deep(.actionbar) {
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
  padding: 6px 8px;
}
/*
 * 操作按钮整体放大；并且让「过 / 返回」和 吃碰杠胡 一样宽。
 *
 * 为什么用"整列定宽 + 按钮 width:100%"而不是给 min-width 加大：
 *   按钮宽度本来是内容撑的（碰/杠 里带小牌，比纯文字的"过"宽一截），
 *   只加 min-width 仍然参差不齐。手机上这列是竖排，直接定列宽、按钮铺满，
 *   所有键一律等宽（实测 102 设计 px，原来是 50~70 不等）。
 */
.table.is-phone .table__actions :deep(.actionbar) {
  flex-direction: column;
  align-items: stretch;
  gap: 6px;
  padding: 6px 8px;
  width: 118px;
}
.table.is-phone .table__actions :deep(.actbtn) {
  width: 100%;
  min-width: 0;
  padding: 9px 10px;
  font-size: 17px;
}
.table.is-phone .table__actions :deep(.actbtn--pass) {
  width: 100%;
  min-width: 0;
}

/* ---------- 我的副露：紧贴手牌上方（"在手牌前面"）---------- */
.table__mymelds {
  position: absolute;
  left: 50%;
  bottom: 106px;
  transform: translateX(-50%);
  max-width: 640px;
}
.table__hand {
  position: absolute;
  left: 50%;
  bottom: 10px;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
/*
 * 手牌区
 *
 * ⚠️ 两个坑（用户报过"手牌上方有个黑框 + 两张牌浮在上面"）：
 *   1. align-items 必须是 flex-end 且容器高度贴合内容 —— 否则摸到的那张
 *      （单独放在最右）会把整行顶高，看起来像"浮在手牌上方"。
 *   2. 分隔条只有与牌等同的高度，且【不能有会露底的宽度】：
 *      原来 width:14px 且无高度，渲染成一个独立的小黑条。
 */
.hand {
  display: flex;
  align-items: flex-end;
  gap: 3px;
}
/* 摸到的那张与其余手牌之间的分隔：一条等高的细线，不是一块黑条 */
.hand__sep {
  align-self: stretch;
  width: 0;
  margin: 0 5px;
  border-left: 1px dashed rgba(255, 255, 255, 0.28);
}
.hand__drawn {
  margin-left: 2px;
}
.hand__empty {
  color: var(--ink-400);
  font-size: 13px;
}
.hand__hint {
  font-size: 12px;
  color: var(--ink-300);
  background: rgba(0, 0, 0, 0.34);
  padding: 2px 10px;
  border-radius: 999px;
}

/* ---------- 底部聊天 ---------- */
.table__bottom {
  position: absolute;
  right: 10px;
  bottom: 10px;
  /* 竖排：上面一行是工具按钮，下面一行是输入框 + 发送 */
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
}
.chat__tools {
  display: flex;
  gap: 6px;
}
.chat {
  display: flex;
  gap: 6px;
}
.chat__input {
  width: 170px;
  padding: 6px 10px;
  border-radius: 8px;
  border: 1px solid var(--panel-border);
  background: rgba(0, 0, 0, 0.4);
  color: var(--ink-100);
  font-size: 13px;
  outline: none;
}
.chat__input:focus {
  border-color: var(--gold-500);
}
.voices {
  position: absolute;
  right: 0;
  /* 底部现在是两行（工具行 + 输入行），面板要抬到它们之上 */
  bottom: 82px;
  width: 220px;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  z-index: 8;
}
.voices__item {
  padding: 6px 9px;
  border: 0;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.06);
  color: var(--ink-200);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}
.voices__item:hover {
  background: rgba(215, 178, 90, 0.22);
  color: var(--gold-300);
}

/* ---------- 连接状态横幅（不拦截点击）---------- */
.connbar {
  position: absolute;
  top: 42px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 14px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 600;
  pointer-events: none;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.45);
}
.connbar > .btn {
  pointer-events: auto;
}
.connbar--warn {
  background: rgba(224, 167, 44, 0.92);
  color: #2a1f06;
}
.connbar--bad {
  background: rgba(184, 69, 63, 0.94);
  color: #fff;
}
.connbar__text {
  white-space: nowrap;
}
</style>
