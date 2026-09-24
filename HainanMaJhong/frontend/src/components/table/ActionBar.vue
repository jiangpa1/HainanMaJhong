<script setup>
/*
 * 操作栏：把引擎下发的 request 变成可点的按钮。
 *
 * 三种 kind 对应的界面（严格按后端 HumanPlayerController 的下发内容）：
 *
 *   discard —— 轮到我出牌。可能带 canReport（可报听），此时多一个「报听」按钮。
 *              出牌本身是点手牌，不在这里。
 *   action  —— 别人打出一张我可以吃/碰/杠/胡，options 按优先级已排好
 *              （胡 > 杠 > 碰 > 吃）。必须给「过」。
 *   draw    —— 我摸牌后可以自摸/暗杠/补杠，options 为可选动作。必须给「过」。
 *
 * options 里的下标就是应答 act 时要回传的 index（后端按 p.options.get(index) 取）。
 * 所以这里【不能】对 options 排序或过滤，否则下标错位会打出错误的操作。
 *
 * ── 吃的两步交互 ──
 * 麻将里"吃"往往有多种组合（同一张牌可以用不同的两张来吃），
 * 后端把每一种组合单独放进 options。若一次全列出来，玩家会面对好几个
 * 长得差不多的「吃」按钮。所以拆成两步：
 *   第一步：显示一个「吃」+ 被吃的那张牌
 *   第二步：点开后再列出所有组合（每项画三张牌），再点一次才作答
 */
import { computed, ref, watch } from 'vue'
import Tile from '../tile/Tile.vue'

const props = defineProps({
  pending: { type: Object, default: null },
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['act', 'pass', 'baoting'])

/** 动作类型 → 中文（与后端 Action.Type 枚举一致） */
const ACT_CN = {
  HU: '胡',
  GANG: '杠',
  AN_GANG: '暗杠',
  BU_GANG: '补杠',
  PENG: '碰',
  CHI: '吃',
}

const kind = computed(() => (props.pending ? props.pending.kind : ''))
const options = computed(() => (props.pending && props.pending.options) || [])
const canReport = computed(() => !!(props.pending && props.pending.canReport))

/** 出牌阶段不显示动作按钮（除报听），避免误触 */
const showOptions = computed(() => kind.value === 'action' || kind.value === 'draw')

/** 第二步：是否正在"选吃法"。每次新询问都要重置。 */
const chiPicking = ref(false)
watch(
  () => (props.pending ? props.pending.reqId : null),
  () => {
    chiPicking.value = false
  },
)

/** 非吃的动作（胡/杠/碰…），保留各自的原始下标 */
const plainOptions = computed(() =>
  options.value.map((opt, i) => ({ opt, i })).filter((x) => x.opt.type !== 'CHI'),
)

/** 所有吃法（每项带原始下标） */
const chiOptions = computed(() =>
  options.value.map((opt, i) => ({ opt, i })).filter((x) => x.opt.type === 'CHI'),
)

/**
 * 被吃的那张牌。
 * 后端在 onActionChance 里把被叫的牌放在 pending.tile，直接用它最稳
 * （吃法组合里的三张牌无法反推"哪一张是被吃的"）。
 */
const calledTile = computed(() => {
  const t = props.pending ? props.pending.tile : -1
  return typeof t === 'number' && t >= 0 ? t : -1
})

function typeCn(opt) {
  return ACT_CN[opt && opt.type] || (opt && opt.type) || '?'
}

/**
 * 按钮上要画的牌面。
 *   胡 / 杠 / 暗杠 / 补杠 / 碰 → 一张
 *   吃（第一步）               → 只画被吃的那张
 *   吃（第二步）               → 三张全画（这才是"拿什么吃"的信息）
 */
function tilesOf(opt, forChiStep) {
  const list = opt && Array.isArray(opt.tiles) ? opt.tiles : []
  if (!list.length) return []
  if (forChiStep) return list
  if (opt.type === 'CHI') return calledTile.value >= 0 ? [calledTile.value] : list.slice(0, 1)
  if (
    opt.type === 'PENG' ||
    opt.type === 'GANG' ||
    opt.type === 'AN_GANG' ||
    opt.type === 'BU_GANG'
  ) {
    return [list[0]]
  }
  return list
}
</script>

<template>
  <div v-if="pending" class="actionbar">
    <template v-if="showOptions">
      <!-- 第二步：选择用哪两张吃 -->
      <template v-if="chiPicking">
        <span class="actionbar__tip">选择吃法</span>
        <button
          v-for="x in chiOptions"
          :key="`chi-${x.i}`"
          class="actbtn actbtn--chi"
          type="button"
          :disabled="disabled"
          @click="emit('act', x.i)"
        >
          <span class="actbtn__label">吃</span>
          <span class="actbtn__tiles">
            <Tile v-for="(t, k) in tilesOf(x.opt, true)" :key="k" :tile="t" size="xs" class="actbtn__tile" />
          </span>
        </button>
        <button class="actbtn actbtn--pass" type="button" @click="chiPicking = false">返回</button>
      </template>

      <!-- 第一步：常规动作；吃只出现一个按钮 -->
      <template v-else>
        <button
          v-for="x in plainOptions"
          :key="x.i"
          class="actbtn"
          :class="{ 'actbtn--hu': x.opt.type === 'HU' }"
          type="button"
          :disabled="disabled"
          @click="emit('act', x.i)"
        >
          <span class="actbtn__label">{{ typeCn(x.opt) }}</span>
          <span v-if="tilesOf(x.opt).length" class="actbtn__tiles">
            <Tile v-for="(t, k) in tilesOf(x.opt)" :key="k" :tile="t" size="xs" class="actbtn__tile" />
          </span>
        </button>

        <button
          v-if="chiOptions.length"
          class="actbtn actbtn--chi"
          type="button"
          :disabled="disabled"
          @click="chiPicking = true"
        >
          <span class="actbtn__label">吃</span>
          <span v-if="calledTile >= 0" class="actbtn__tiles">
            <Tile :tile="calledTile" size="xs" class="actbtn__tile" />
          </span>
        </button>

        <button
          v-if="options.length || kind === 'draw'"
          class="actbtn actbtn--pass"
          type="button"
          :disabled="disabled"
          @click="emit('pass')"
        >
          <span class="actbtn__label">过</span>
        </button>
      </template>
    </template>

    <button
      v-if="kind === 'discard' && canReport"
      class="actbtn actbtn--baoting"
      type="button"
      :disabled="disabled"
      @click="emit('baoting')"
    >
      <span class="actbtn__label">报听</span>
    </button>
  </div>
</template>

<style scoped>
.actionbar {
  display: flex;
  align-items: stretch;
  justify-content: center;
  gap: 10px;
  padding: 7px 12px;
  border-radius: 12px;
  background: rgba(4, 22, 16, 0.78);
  border: 1px solid var(--panel-border);
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.45);
}

/* 按钮改成"上动作名、下牌名"的竖排 */
.actbtn {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  min-width: 64px;
  padding: 5px 12px;
  border: 1px solid var(--panel-border);
  border-radius: 9px;
  background: linear-gradient(180deg, rgba(23, 97, 74, 0.92), rgba(13, 59, 42, 0.92));
  color: var(--ink-100);
  font-weight: 700;
  font-size: 15px;
  cursor: pointer;
  transition: filter 0.12s ease, transform 0.12s ease;
  -webkit-tap-highlight-color: transparent;
}
.actbtn:hover:not(:disabled) {
  filter: brightness(1.18);
}
.actbtn:active:not(:disabled) {
  transform: translateY(1px);
}
.actbtn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.actbtn__label {
  line-height: 1.1;
}

/* 牌面（图像）：不投影，否则小尺寸下糊成一团 */
.actbtn__tiles {
  display: flex;
  gap: 1px;
}
.actbtn__tile {
  filter: none !important;
}

/* 胡牌按钮最醒目：红金渐变 + 轻微呼吸，避免玩家漏掉能胡的机会 */
.actbtn--hu {
  background: linear-gradient(180deg, #ffd66b, #e0972a);
  color: #3a2405;
  border-color: #ffe6a8;
  box-shadow: 0 0 14px rgba(255, 200, 80, 0.5);
  animation: huPulse 1.4s ease-in-out infinite;
}
/* 胡牌按钮上的牌名在浅色底上要换深色才看得清 */
.actbtn--hu .actbtn__tiles {
  color: #7a4a06;
}

.actbtn--pass {
  background: rgba(0, 0, 0, 0.42);
  color: var(--ink-300);
  min-width: 50px;
}

/* 吃：绿色系，和胡（金）、过（灰）区分开 */
.actbtn--chi {
  background: linear-gradient(180deg, rgba(31, 122, 61, 0.95), rgba(18, 81, 58, 0.95));
  border-color: rgba(53, 176, 111, 0.6);
}

/* "选择吃法"提示 */
.actionbar__tip {
  align-self: center;
  font-size: 12px;
  color: var(--gold-300);
  white-space: nowrap;
  padding: 0 4px;
}

.actbtn--baoting {
  background: linear-gradient(180deg, var(--gold-400), var(--gold-500));
  color: #2a1f06;
  border-color: var(--gold-400);
}

@keyframes huPulse {
  0%,
  100% {
    box-shadow: 0 0 12px rgba(255, 200, 80, 0.45);
  }
  50% {
    box-shadow: 0 0 22px rgba(255, 200, 80, 0.85);
  }
}

@media (prefers-reduced-motion: reduce) {
  .actbtn--hu {
    animation: none;
  }
}
</style>
