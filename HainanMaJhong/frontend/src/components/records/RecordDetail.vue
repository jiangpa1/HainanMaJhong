<script setup>
/*
 * 对局明细（二级战绩）的共用渲染。
 *
 * 两处入口都用它，保证"局内点开"和"大厅战绩查询里点进房间"看到的是同一套东西：
 *   · Records.vue   —— 战绩页的二级视图（?roomId= 或从列表点进某一场）
 *   · RoundHistory  —— 牌桌右下角「局内战绩」按钮点开的弹窗
 *
 * 数据形状 = 后端 RecordDetailVO：
 *   { roomId, notFound, overview: {winCount, highestFan, players[]}, rounds[] }
 * 其中每把的 participants[].detail = { notes[], flowers[], gangs[] }。
 *
 * ⚠️ overview.players 是 PlayerTotalVO，**没有 isMe 字段**（只有每把的 participant 有）。
 *    "我"必须自己拿本地 userId 去比，否则"本场我的总分"永远是 0。
 */
import { computed } from 'vue'
import Tile from '../tile/Tile.vue'
import { profile } from '../../api/api.js'

const props = defineProps({
  /** RecordDetailVO */
  detail: { type: Object, default: null },
})

const SEAT_CN = ['东', '南', '西', '北']
const WIND_CN = ['东', '南', '西', '北']
const MELD_LABEL = { CHI: '吃', PENG: '碰', GANG: '杠', AN_GANG: '暗杠', BU_GANG: '补杠' }

/** 当前登录用户 id（不能用 isMe，见文件头说明） */
const myUserId = computed(() => {
  const p = profile()
  if (p && p.id != null) return Number(p.id)
  const n = Number(localStorage.getItem('userId'))
  return Number.isFinite(n) ? n : 0
})

const players = computed(() => (props.detail && props.detail.overview && props.detail.overview.players) || [])
const rounds = computed(() => (props.detail && props.detail.rounds) || [])

function isMeRow(p) {
  return Number(p.userId) === myUserId.value
}

const myTotal = computed(() => {
  const me = players.value.find(isMeRow)
  return me ? Number(me.totalScore) || 0 : 0
})

const winCount = computed(() => {
  const ov = (props.detail && props.detail.overview) || {}
  return ov.winCount == null ? 0 : ov.winCount
})

const highestFan = computed(() => {
  const ov = (props.detail && props.detail.overview) || {}
  return ov.highestFan || '无'
})

/**
 * 一把牌里"要画出来的玩家行"。
 * 只画有内容的：赢家一行；其余人只有当他有杠或有明细（花分/包牌）时才画 ——
 * 否则每把都会多出三行"什么都没发生"，一屏全是空白。
 */
function rowsOf(round) {
  const ps = round.participants || []
  const out = []
  for (const p of ps.filter((x) => x.isWinner)) out.push({ p, kind: 'hu' })
  for (const p of ps.filter((x) => !x.isWinner)) {
    const d = p.detail || {}
    if ((d.gangs || []).length || (d.notes || []).length) out.push({ p, kind: '' })
  }
  return out
}

/** 只有凑成花杠（真花/假花/对花）时才画花牌 —— 否则满屏散花没有信息量 */
function flowersShown(p) {
  const notes = ((p.detail || {}).notes) || []
  return notes.some((n) => /花/.test(String(n)))
}

function notesText(p) {
  return (((p.detail || {}).notes) || []).join('、')
}

function scoreCls(v) {
  return v > 0 ? 'is-up' : v < 0 ? 'is-down' : ''
}

function fmtScore(v) {
  const n = Number(v) || 0
  return n > 0 ? `+${n}` : String(n)
}

function roundMeta(r) {
  const bits = []
  bits.push('庄 ' + (SEAT_CN[r.dealerSeat] ? SEAT_CN[r.dealerSeat] + '家' : '?'))
  if (r.dealerNickname) bits.push(r.dealerNickname)
  if (r.windIdx != null) bits.push('令 ' + (WIND_CN[r.windIdx] || '?'))
  if (!r.isDraw && r.winType === 1 && r.loser) bits.push(`点炮 ${r.loser}`)
  return bits.join(' · ')
}
</script>

<template>
  <div class="rd-wrap">
    <!-- 概览：我的总分 + 胡牌次数 + 最高番 + 四家总分 -->
    <section class="ov panel">
      <div class="ov__hero">
        <div class="ov__me">
          <span class="ov__label">本场我的总分</span>
          <b class="ov__total" :class="scoreCls(myTotal)">{{ fmtScore(myTotal) }}</b>
        </div>
        <div class="ov__stats">
          <span class="ov__stat">胡牌 <b>{{ winCount }}</b> 次</span>
          <span class="ov__stat">最高番 <b>{{ highestFan }}</b></span>
        </div>
      </div>
      <div class="ov__players">
        <span v-for="p in players" :key="p.userId" class="ptag" :class="{ 'is-me': isMeRow(p) }">
          {{ p.nickname || '玩家' + p.userId }}
          <b :class="scoreCls(p.totalScore)">{{ fmtScore(p.totalScore) }}</b>
          <i v-if="isMeRow(p)" class="ptag__me">我</i>
        </span>
      </div>
    </section>

    <!-- 每一把 -->
    <div class="rounds">
      <article v-for="rd in rounds" :key="rd.roundNum" class="round panel">
        <header class="round__head">
          <span class="round__no">第 {{ rd.roundNum }} 把</span>
          <span class="round__meta">{{ roundMeta(rd) }}</span>
          <span v-if="rd.isDraw" class="round__draw">荒庄</span>
          <template v-else>
            <span class="round__winner">{{ rd.winner || '—' }} 胡</span>
            <span class="round__fans">{{ (rd.fanTypes || []).join('、') || '平胡' }}</span>
          </template>
          <span v-if="!rd.isDraw && rd.winTile != null && rd.winTile >= 0" class="round__tile">
            <Tile :tile="rd.winTile" size="sm" />
          </span>
        </header>

        <div v-if="!rd.isDraw" class="round__rows">
          <div
            v-for="row in rowsOf(rd)"
            :key="row.p.userId"
            class="prow"
            :class="{ 'is-win': row.kind === 'hu' }"
          >
            <b class="prow__name">
              {{ row.kind === 'hu' ? '胡·' : '' }}{{ row.p.nickname || '玩家' + row.p.userId
              }}<i v-if="row.p.isMe">（我）</i>
            </b>
            <span v-if="notesText(row.p)" class="prow__notes">{{ notesText(row.p) }}</span>

            <!-- 胡牌者：暗牌手牌 + 副露（杠已含在副露里，不重复画） -->
            <span v-if="row.kind === 'hu'" class="prow__tiles">
              <Tile v-for="(t, i) in rd.winHand || []" :key="`h${i}`" :tile="t" size="xs" />
              <span v-for="(m, i) in rd.winMelds || []" :key="`m${i}`" class="meld">
                <em>{{ MELD_LABEL[m.type] || m.type }}</em>
                <Tile v-for="(t, j) in m.tiles || []" :key="`mt${j}`" :tile="t" size="xs" />
              </span>
            </span>
            <!-- 其余人：只画杠 -->
            <span v-else-if="(row.p.detail?.gangs || []).length" class="prow__tiles">
              <span v-for="(g, i) in row.p.detail.gangs" :key="`g${i}`" class="meld">
                <em>{{ MELD_LABEL[g.type] || g.type }}</em>
                <Tile v-for="(t, j) in g.tiles || []" :key="`gt${j}`" :tile="t" size="xs" />
              </span>
            </span>

            <!-- 花：只有凑成花杠才画 -->
            <span v-if="flowersShown(row.p)" class="prow__tiles">
              <Tile v-for="(f, i) in row.p.detail?.flowers || []" :key="`f${i}`" :tile="f" size="xs" />
            </span>

            <span class="prow__score" :class="scoreCls(row.p.scoreChange)">
              {{ fmtScore(row.p.scoreChange) }}
            </span>
          </div>
        </div>
      </article>

      <p v-if="!rounds.length" class="rd-empty panel">这一场还没有完成的小局。</p>
    </div>
  </div>
</template>

<style scoped>
.rd-wrap {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* ---------- 概览 ---------- */
.ov {
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.ov__hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
}
.ov__me {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.ov__label {
  font-size: 11px;
  color: var(--ink-400);
}
.ov__total {
  font-size: 26px;
  font-weight: 900;
}
.ov__stats {
  display: flex;
  gap: 14px;
  font-size: 13px;
  color: var(--ink-300);
}
.ov__stat b {
  color: var(--gold-300);
}
.ov__players {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.ptag {
  display: inline-flex;
  align-items: baseline;
  gap: 5px;
  padding: 3px 9px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.06);
  font-size: 12px;
  color: var(--ink-200);
}
.ptag.is-me {
  background: rgba(215, 178, 90, 0.2);
  color: var(--gold-300);
}
.ptag__me {
  font-style: normal;
  padding: 0 4px;
  border-radius: 3px;
  background: var(--gold-500);
  color: #2a1f06;
  font-size: 9px;
  font-weight: 700;
}
.is-up {
  color: #8ee0b4;
}
.is-down {
  color: #ff8b83;
}

/* ---------- 每一把 ---------- */
.rounds {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.round {
  padding: 10px 14px;
}
.round__head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding-bottom: 8px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
}
.round__no {
  font-size: 14px;
  font-weight: 800;
  color: var(--gold-300);
}
.round__meta {
  font-size: 11px;
  color: var(--ink-400);
}
.round__winner {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink-100);
}
.round__fans {
  font-size: 12px;
  color: var(--gold-300);
}
.round__draw {
  font-size: 13px;
  color: var(--ink-300);
}
.round__tile {
  margin-left: auto;
  display: inline-flex;
}
.round__rows {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-top: 8px;
}
.prow {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--ink-200);
}
.prow.is-win .prow__name {
  color: var(--gold-300);
}
.prow__name i {
  font-style: normal;
  color: var(--gold-300);
}
.prow__notes {
  padding: 1px 7px;
  border-radius: 999px;
  background: rgba(215, 178, 90, 0.18);
  color: var(--gold-300);
  font-size: 11px;
}
.prow__tiles {
  display: inline-flex;
  align-items: flex-end;
  gap: 1px;
  flex-wrap: wrap;
}
.meld {
  display: inline-flex;
  align-items: flex-end;
  gap: 1px;
  margin-left: 4px;
}
.meld em {
  font-style: normal;
  font-size: 10px;
  color: var(--ink-400);
  align-self: center;
  margin-right: 1px;
}
.prow__score {
  margin-left: auto;
  font-size: 14px;
  font-weight: 800;
}
.rd-empty {
  padding: 18px;
  text-align: center;
  color: var(--ink-300);
  font-size: 13px;
}
</style>
