<script setup>
/*
 * 局内战绩：牌桌右下角一个按钮，点开看【本房间】的完整明细。
 *
 * ── 为什么改成拉服务端而不是用内存里的 roundHistory ──
 *
 * 内存里那份只能记"我收到 hu 的那一刻"这一层信息（谁胡了什么牌、四家分数变化），
 * 拿不到杠、花、包牌这些结算明细 —— 而那些恰恰是玩家点开战绩最想看的东西。
 * 服务端每打完一把就把整把（含四家 detail）落库，所以这里直接查
 *   GET /api/records/byRoom?roomId=<房号>
 * 再交给 RecordDetail 渲染 —— 和「大厅 → 战绩查询 → 点进这个房间」看到的是同一个界面。
 *
 * 服务端还没落库时（本把正打着、一把都还没结束）接口会返回 notFound，
 * 这时退回到内存里的简易列表，至少不是一片空白。
 *
 * ⚠️ 房主「再来一轮」沿用同一个 session，所以这里查到的会把前面几轮一起带出来 ——
 *    这正是"局内战绩也要继承"想要的。
 */
import { computed, ref, watch } from 'vue'
import { recordsByRoom } from '../../api/api.js'
import RecordDetail from '../records/RecordDetail.vue'
import Tile from '../tile/Tile.vue'

const props = defineProps({
  /** 本房间号（房号还没拿到时传空串，按钮置灰） */
  roomCode: { type: String, default: '' },
  /** 内存里的简易战绩，仅在服务端还没数据时兜底 */
  rounds: { type: Array, default: () => [] },
  mySeat: { type: String, default: '' },
  seatCn: { type: Object, default: () => ({}) },
})

const open = ref(false)
const loading = ref(false)
/** 服务端返回的完整明细（RecordDetailVO） */
const detail = ref(null)
/** 拉取失败/尚无数据时置 true，走内存兜底 */
const fallback = ref(false)
const error = ref('')

const hasSomething = computed(() => {
  if (detail.value && !detail.value.notFound) return true
  return (props.rounds || []).length > 0
})

/** 按钮上的小计数：优先用服务端把数，没有就用内存里的 */
const count = computed(() => {
  if (detail.value && Array.isArray(detail.value.rounds)) return detail.value.rounds.length
  return (props.rounds || []).length
})

async function load() {
  const code = (props.roomCode || '').trim()
  if (!code) {
    fallback.value = true
    return
  }
  loading.value = true
  error.value = ''
  try {
    const d = await recordsByRoom(code)
    detail.value = d
    // notFound 表示这个房间还没完成的小局
    fallback.value = !d || d.notFound === true
  } catch (e) {
    fallback.value = true
    error.value = (e && e.message) || '加载失败'
  } finally {
    loading.value = false
  }
}

/** 打开时拉一次；开着的时候房间号变了（换房）也重拉 */
watch(open, (v) => {
  if (v) load()
})

async function openModal() {
  open.value = true
  await load()
}

/* ---------------- 内存兜底渲染 ---------------- */

const SEATS = ['EAST', 'SOUTH', 'WEST', 'NORTH']

/** 最新一把在最上 */
const rows = computed(() => (props.rounds || []).slice().reverse())

function seatLabel(seat) {
  if (!seat) return '—'
  const cn = (props.seatCn && props.seatCn[seat]) || seat
  return seat === props.mySeat ? `${cn}家(我)` : `${cn}家`
}

function deltaOf(r, seat) {
  if (!r.delta) return null
  return typeof r.delta[seat] === 'number' ? r.delta[seat] : null
}

function fmtDelta(v) {
  if (v === null || v === undefined) return '—'
  return v > 0 ? `+${v}` : String(v)
}

function howText(r) {
  if (r.draw) return '流局'
  if (r.selfDraw) return '自摸'
  return r.from ? `点炮 · ${seatLabel(r.from)}放的` : '点炮'
}
</script>

<template>
  <div class="rh">
    <button class="rh__btn panel" type="button" :disabled="!roomCode" @click="openModal">
      <span class="rh__btn-title">局内战绩</span>
      <span class="rh__btn-count">{{ count }} 把</span>
    </button>

    <div v-if="open" class="rh__mask" @click.self="open = false">
      <div class="rh__modal panel">
        <header class="rh__head">
          <h2>本房战绩 <span v-if="roomCode" class="rh__room">房间 {{ roomCode }}</span></h2>
          <button class="btn btn--sm btn--ghost" type="button" @click="open = false">关闭</button>
        </header>

        <div class="rh__body">
          <p v-if="loading" class="rh__msg">加载中…</p>

          <template v-else-if="!fallback">
            <!-- 与大堂战绩查询的二级界面用的是同一个组件 -->
            <RecordDetail :detail="detail" />
          </template>

          <!-- 服务端还没有落库（本把正打着）：退回内存里的简易列表 -->
          <template v-else>
            <p v-if="error" class="rh__msg rh__msg--warn">{{ error }}，下面是本局已打过的记录。</p>
            <p v-else-if="rounds.length" class="rh__msg rh__msg--warn">
              本房还没有已结算的小局，下面是本局已打过的记录（打完一把后这里会显示完整明细）。
            </p>
            <p v-if="!rows.length" class="rh__empty">还没有打完一把。</p>

            <article v-for="r in rows" :key="r.hand" class="round">
              <div class="round__head">
                <span class="round__no">第 {{ r.hand }} 把</span>
                <span class="round__dealer">庄 {{ seatLabel(r.dealer) }}</span>
                <template v-if="r.draw">
                  <span class="round__draw">流局</span>
                </template>
                <template v-else>
                  <span class="round__winner">{{ seatLabel(r.winner) }} 胡</span>
                  <span class="round__how">{{ howText(r) }}</span>
                </template>
              </div>

              <div class="round__mid">
                <span v-if="!r.draw && r.tile >= 0" class="round__tile">
                  <em>胡</em>
                  <Tile :tile="r.tile" size="sm" />
                </span>
                <span v-if="!r.draw" class="round__fans">
                  {{ (r.fans || []).length ? r.fans.join('、') : '平胡' }}
                </span>
                <span v-if="r.selfDraw" class="tag">自摸</span>
                <span v-if="r.ganKai" class="tag">杠开</span>
              </div>

              <div class="round__players">
                <span
                  v-for="s in SEATS"
                  :key="s"
                  class="pl"
                  :class="{ 'is-me': s === mySeat, 'is-win': !r.draw && s === r.winner }"
                >
                  <b>{{ seatLabel(s) }}</b>
                  <i :class="(deltaOf(r, s) || 0) > 0 ? 'up' : (deltaOf(r, s) || 0) < 0 ? 'down' : ''">
                    {{ fmtDelta(deltaOf(r, s)) }}
                  </i>
                </span>
              </div>
            </article>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.rh__btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 6px 12px;
  border: 1px solid var(--panel-border);
  cursor: pointer;
}
.rh__btn:disabled {
  opacity: 0.55;
  cursor: default;
}
.rh__btn-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--gold-300);
}
.rh__btn-count {
  font-size: 11px;
  color: var(--ink-300);
}

.rh__mask {
  position: fixed;
  inset: 0;
  z-index: 60;
  background: rgba(0, 0, 0, 0.62);
  /* flex 居中：弹窗的百分比 max-height 对着遮罩的确定高度解析 */
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}
.rh__modal {
  /*
   * 尺寸按"占屏幕 80%"来：弹窗在 .scale-layer 里，所以百分比是相对【设计框】
   * （1280 x --design-h）。设计框刚好铺满可见框的短边、并在长边留白，
   * 因此 80% 宽 + 80% 高 ≈ 用户看到的八成大。
   *
   * max-width/max-height 是给桌面（1280x720）兜底的：桌面下 80% = 1024x576，
   * 已经够大，再宽就只剩一圈窄边了。
   */
  width: 80%;
  max-width: 1060px;
  max-height: 80%;
  /* 内容少（只有一两把）时也别缩成一个小方块：给个下限，始终像个"面板" */
  min-height: 55%;
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}
.rh__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--panel-border);
}
.rh__head h2 {
  margin: 0;
  font-size: 16px;
  color: var(--gold-300);
}
.rh__room {
  margin-left: 8px;
  font-size: 12px;
  color: var(--ink-300);
  font-weight: 400;
}
.rh__body {
  flex: 1 1 auto;
  overflow-y: auto;
  padding: 12px 16px 16px;
}
.rh__msg {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--ink-300);
}
.rh__msg--warn {
  color: var(--gold-300);
}
.rh__empty {
  margin: 0;
  text-align: center;
  color: var(--ink-300);
  font-size: 14px;
}

/* ---------- 内存兜底列表 ---------- */
.round {
  padding: 8px 10px;
  margin-bottom: 8px;
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.28);
  border: 1px solid rgba(255, 255, 255, 0.06);
}
.round__head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
}
.round__no {
  font-weight: 800;
  color: var(--gold-300);
}
.round__dealer,
.round__how {
  color: var(--ink-400);
}
.round__winner {
  font-weight: 700;
  color: var(--ink-100);
}
.round__draw {
  color: var(--ink-300);
}
.round__mid {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-top: 4px;
  font-size: 12px;
}
.round__tile {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
.round__tile em {
  font-style: normal;
  font-size: 10px;
  color: var(--ink-400);
}
.round__fans {
  color: var(--gold-300);
}
.tag {
  padding: 1px 7px;
  border-radius: 999px;
  background: rgba(215, 178, 90, 0.2);
  border: 1px solid rgba(215, 178, 90, 0.5);
  color: var(--gold-300);
  font-size: 10px;
}
.round__players {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}
.pl {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.06);
  font-size: 11px;
  color: var(--ink-200);
}
.pl.is-me {
  background: rgba(215, 178, 90, 0.18);
  color: var(--gold-300);
}
.pl.is-win {
  background: rgba(53, 176, 111, 0.18);
}
.pl .up {
  color: #8ee0b4;
  font-style: normal;
}
.pl .down {
  color: #ff8b83;
  font-style: normal;
}
</style>
