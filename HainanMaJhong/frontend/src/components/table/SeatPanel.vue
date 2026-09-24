<script setup>
/*
 * 一个对手的信息：座位名 + 昵称 + 手牌张数 + 金币 + 庄家/当前回合标识。
 *
 * 手牌只显示牌背（服务端不下发别人的手牌）。
 *
 * ── 为什么拆成「名字」和「手牌」两块 ──
 * 对家与我的关系是"面对面"：他的手牌朝向他自己，在我屏幕上是倒的（180°）。
 * 但名字要能读，不能倒。所以：
 *   · 名字单独一块，始终正向
 *   · 手牌另一块，由父组件按需要旋转
 * 参考真实牌桌：对家的牌倒着摆，但记分牌是正的。
 *
 * ── 侧边两家的朝向 ──
 * 上家/下家整块由父组件旋转 ±90°，所以他们的手牌在这里排成【横排】：
 * 旋转之后这一横排就变成屏幕上的一条【竖列】，沿屏幕高边铺开。
 * 排成竖排反而会转出一条横线（上一版就是这个问题）。
 */
import { computed } from 'vue'
import Tile from '../tile/Tile.vue'

const props = defineProps({
  info: { type: Object, required: true },
  /** 'top' | 'left' | 'right' | 'bottom' */
  orientation: { type: String, default: 'top' },
  /**
   * 只渲染手牌牌背，不渲染名字。
   * 用于"手牌要跟着旋转、名字不转"的场景（对家的手牌倒 180°）。
   */
  backsOnly: { type: Boolean, default: false },
  /** 只渲染名字，不渲染牌背（与 backsOnly 互补） */
  metaOnly: { type: Boolean, default: false },
  /**
   * 名字条改为【绝对定位浮层】：不再在 flex 主流里占位。
   *
   * 为什么必须这样（用户要求"名字和副露贴着手牌"）：
   *   侧边两家的整块要旋转 90°，名字条旋转后的包围盒在【屏幕 X 方向】
   *   占的就是它原本的长边（"西 玩家3 13 张 币 100 花 1" ≈ 143px 设计像素）。
   *   它一旦留在主流里，副露就被推到离手牌 145px 之外 —— 截图里肉眼可见的大空档。
   *   改成浮层后，主流里只剩 手牌 → 副露 两个元素，间距就是父组件的 gap。
   *   名字飘在手牌外侧下方，仍然紧贴手牌，且不挤占副露的位置。
   */
  overlay: { type: Boolean, default: false },
  /** 这一家当前要显示的消息气泡文本（过期后父组件传空串） */
  chat: { type: String, default: '' },
})

/** 牌背最多画 14 个（手牌上限，含摸牌） */
const backs = computed(() => {
  const n = Math.max(0, Math.min(14, props.info.count || 0))
  return Array.from({ length: n }, (_, i) => i)
})

/**
 * 侧边两家：牌背排成【竖列】，每张牌自己横过来。
 *
 * ⚠️ 不要改回"整排 rotate 90°"——那是踩了很久的坑：
 *   整排旋转时，旋转中心在那一排的【几何中心】，而这一排有 13 张牌（约 402px）宽，
 *   旋转后视觉上的牌条只会出现在"这个 402px 盒子的正中间"。
 *   于是无论怎么调 left/top，牌条都离屏幕边有一百多像素，永远贴不到边；
 *   而且手牌张数一变（402 变 372…），居中点就跟着移，位置又不固定。
 *   （实测：面板 left:8px，牌条却落在 design 193~233，差 185px。）
 *
 * 现在：和 MeldRow 一样，让"布局盒 = 视觉盒"——排成竖列（沿局部 Y），
 * 每张牌自己转 ±90°。竖列宽度固定 = 牌宽 30px，不随张数变化，
 * 位置完全由父组件的 left/right 决定。
 */
const isSide = computed(() => props.orientation === 'left' || props.orientation === 'right')

/** 侧边两家的牌背朝向：上家 +90°、下家 −90°（与我下家/上家的视角一致） */
const sideAngle = computed(() => {
  if (props.orientation === 'left') return 90
  if (props.orientation === 'right') return -90
  return 0
})

const showBacks = computed(() => !props.metaOnly)
const showMeta = computed(() => !props.backsOnly)
</script>

<template>
  <div
    class="seatpanel"
    :class="[
      `seatpanel--${orientation}`,
      { 'is-turn': info.isTurn, 'is-overlay': overlay, 'is-backs': showBacks && !showMeta, 'is-meta': showMeta && !showBacks },
    ]"
  >
    <!-- 手牌（牌背）。名字改成浮层后，这一块就是主流里的第一个元素 -->
    <div v-if="showBacks" class="seatpanel__backs" :class="{ 'is-col': isSide }">
      <Tile
        v-for="i in backs"
        :key="i"
        :tile="0"
        size="xs2"
        back
        :rotate="sideAngle"
        class="seatpanel__back"
      />
    </div>

    <div v-if="showMeta" class="seatpanel__meta">
      <span class="seatpanel__seat">{{ info.seatCn }}</span>
      <span class="seatpanel__name" :title="info.nickname">{{ info.nickname }}</span>
      <span v-if="info.isDealer" class="seatpanel__dealer">庄</span>
      <span class="seatpanel__num">{{ info.count }} 张</span>
      <span class="seatpanel__num">币 {{ info.coins }}</span>
      <span v-if="info.flowersCount" class="seatpanel__num">花 {{ info.flowersCount }}</span>
      <!--
        消息气泡：直接长在名字条上。
        好处是它天然继承了这一家的朝向（名字条本来就按座位转了 ±90°/180°），
        所以每一家看自己收到的气泡都是正的，不需要再单独算坐标。
      -->
      <span v-if="chat" class="seatpanel__bubble">{{ chat }}</span>
    </div>
  </div>
</template>

<style scoped>
.seatpanel {
  display: flex;
  flex-direction: column;
  align-items: center;
  /* 名字条紧贴牌背下方（父组件另给 2px，合计 3px） */
  gap: 1px;
  /* 刻意没有 background / border —— 牌背不套容器 */
}

/* 牌背：横向紧凑排列，一张挨一张但【不重叠】 */
.seatpanel__backs {
  display: flex;
  align-items: flex-end;
  gap: 1px;
}
.seatpanel__back {
  /* 去掉牌背自带投影：十几张并排时投影会把画面压脏 */
  filter: none !important;
}
/*
 * 用户要求"其他人的手牌（牌背）不要重叠"。
 *
 * 原来用负 margin-left 叠牌（-16px），本意是模仿真实牌桌的紧凑感，
 * 但这里不值得：牌背是纯色绿面，重叠会在每两张之间留出一条梯形缝隙，
 * 看起来就像"每个牌背外面套了一个透明框"。
 * 现在改为 gap: 1px 的平铺 —— 相邻绿面之间只留 1px 暗缝，
 * 整排读起来是一条连续的牌墙，且每张牌的宽度/位置都能数清楚。
 */
/*
 * 侧边两家：排成【竖列】（每张牌自己已经转了 ±90°，见 script 的 sideAngle）。
 *
 * ⚠️ 不要改回"横排 + 整排 rotate 90°"：
 *   整排旋转的中心是那一排的几何中心，而这一排有 13 张牌约 402px 宽，
 *   旋转后的视觉牌条只会出现在这个 402px 盒子的正中间 ——
 *   无论怎么调 left/top 都贴不到屏幕边（实测差 185px），
 *   而且张数一变居中点就移，位置不固定。
 * 竖列之后：布局盒宽度 = 牌宽 30px，与张数无关，位置完全由父组件决定。
 */
.seatpanel__backs.is-col {
  flex-direction: column;
  align-items: center;
  gap: 1px;
}
/*
 * 侧边两家的牌背：相邻两张往上叠 11px。
 *
 * 为什么：一张牌布局盒 30(宽)x41(高)，转 90° 后【视觉】是 41x30。
 * 竖列排布时浏览器按【布局盒】的 41px 步进叠加，而视觉上每张只占 30px 高，
 * 于是每两张之间多出 11px 空档（实测间距 9~10px，牌看起来是散的）。
 * 41 − 30 = 11，用负 margin 抵消掉，步进就与视觉高度一致。
 *
 * ⚠️ 不要试图改 --tw/--th 互换：那样布局盒变 41x30，旋转后视觉变成 30x41，
 *   步进 30 < 视觉 41，反而变成重叠（实测重叠 12 处）。
 */
.seatpanel__backs.is-col .seatpanel__back + .seatpanel__back {
  margin-top: -11px;
}
/*
 * 只有对家（top）需要整体转 180°。
 * 侧边两家的朝向已经由【每张牌自己】的 rotate 表达，这里不能再转一次，
 * 否则会转到与手牌不一致的方向（早期"对家副露方向反了"就是漏了这一点）。
 */
.seatpanel--top .seatpanel__backs,
.seatpanel--top .seatpanel__meta {
  transform: rotate(180deg);
}

/* 信息条：很轻的底衬，只为可读 */
.seatpanel__meta {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  padding: 2px 7px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.38);
  white-space: nowrap;
}

.seatpanel__seat {
  width: 15px;
  height: 15px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  background: linear-gradient(180deg, var(--gold-400), var(--gold-500));
  color: #2a1f06;
  font-weight: 700;
  font-size: 10px;
}
.seatpanel__name {
  max-width: 76px;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--ink-100);
  font-weight: 600;
}
.seatpanel__dealer {
  padding: 0 4px;
  border-radius: 3px;
  background: var(--danger);
  color: #fff;
  font-size: 9px;
  font-weight: 700;
}
.seatpanel__num {
  color: var(--ink-300);
}
/*
 * 消息气泡：挂在名字条右侧（局部 X 方向）。
 * 名字条整条按座位转了朝向，所以气泡跟着转，每家看自己的都是正的。
 */
.seatpanel__bubble {
  max-width: 150px;
  padding: 2px 7px;
  border-radius: 9px;
  background: linear-gradient(180deg, #ffe6a8, var(--gold-400));
  color: #2a1f06;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 当前回合：只给信息条描金边，不去框牌背 */
.seatpanel.is-turn .seatpanel__meta {
  background: rgba(215, 178, 90, 0.22);
  box-shadow: 0 0 0 1px var(--gold-500), 0 0 12px rgba(215, 178, 90, 0.35);
  color: var(--gold-300);
}

/*
 * 名字条的各座位位置。
 *
 * 机制：父组件把名字块做成【零尺寸 + absolute】并钉在座位外侧的边线上，
 * 所以 `left:0 / top:50%` 就是那条边线上的锚点。
 *
 * ⚠️ 平移量必须按"rotate 之后的坐标系"来推，不能凭感觉写：
 *   transform 列表是【先写的先作用在局部坐标】。rotate(θ) 把局部位移向量
 *   (tx, ty) 映射到屏幕：
 *     θ=+90°  → 屏幕位移 = (−ty, tx)
 *     θ=−90°  → 屏幕位移 = ( ty, −tx)
 *     θ=180°  → 屏幕位移 = (−tx, −ty)
 *   所以"想往屏幕左移 82px"在 +90° 下要写 ty=+82，在 −90° 下要写 ty=−82。
 *   凭感觉写会正好反方向，表现为"名字跑到桌心那边去了"。
 */
.seatpanel.is-overlay {
  position: relative;
  overflow: visible;
}
.seatpanel.is-overlay .seatpanel__meta {
  position: absolute;
  margin: 0;
  max-width: 260px;
  white-space: nowrap;
}
/* 上家（贴屏幕最左）：把旋转锚点放在牌的【左中】，锚在座位左边线上 */
.seatpanel--left.is-overlay .seatpanel__meta {
  /* left = 半个牌高(10.5) → 旋转后牌条的左边缘正好落在座位左边线上 */
  left: 10.5px;
  top: 50%;
  /* 牌条长度约 150px，往上提一半让它在座位纵向上大致居中 */
  margin-top: -75px;
  transform-origin: 0 50%;
  transform: rotate(90deg);
}
/* 下家（贴屏幕最右）：锚点放在牌的【右中】，镜像 */
.seatpanel--right.is-overlay .seatpanel__meta {
  right: 10.5px;
  top: 50%;
  margin-top: -75px;
  transform-origin: 100% 50%;
  transform: rotate(-90deg);
}
/*
 * 对家（贴屏幕最上）：这一家用"中心旋转 + 平移"就够（不需要换锚点）。
 * 180° 下屏幕位移 = (−tx, −ty)，所以 translate(50%, 100%) 把牌子
 * 往左上各推半个宽/一个高，正好落在锚点上方并水平居中。
 */
.seatpanel--top.is-overlay .seatpanel__meta {
  left: 50%;
  top: 0;
  transform: rotate(180deg) translate(50%, 100%);
}
</style>
