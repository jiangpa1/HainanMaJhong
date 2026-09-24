<script setup>
/*
 * 副露（吃碰杠）与花牌。
 *
 * 后端 serializeMeld 的结构：{ type, tiles: [tile...], from? }
 *   CHI      吃：3 张顺子（from = 打出该牌的人）
 *   PENG     碰：3 张      （from = 打出该牌的人）
 *   GANG     明杠：4 张    （from = 打出该牌的人）
 *   AN_GANG  暗杠：4 张    （from = null，自己凑的）
 *   BU_GANG  补杠：4 张    （from = null）
 *
 * ── "被吃碰杠的那张要指向谁" ──
 *
 * 桌面惯例：从谁那里吃碰杠来的牌就把它【横放】。横放的位置提示来源：
 *   来自上家 → 横在那副露的最左边
 *   来自对家 → 横在中间
 *   来自下家 → 横在右边
 * 其余几张始终竖放。暗杠 / 补杠没有来源，四张全竖放。
 *
 * 方位本身已经由"横放在哪一位"完整表达，所以【不再】加任何文字角标
 * （用户明确要求：吃碰杠不要显示"上家/对家/下家"文字说明）。
 *
 * ── 朝向：为什么不整块 rotate ──
 *
 * 试过"整块副露转 ±90°"的方案，踩了一个大坑，最后放弃了：
 *   旋转不改变布局盒，而布局盒的尺寸又决定 transform-origin，两者互相牵扯。
 *   实测同一时刻 offsetHeight 报 420px、getBoundingClientRect().height 报 331px，
 *   内容位置怎么调都对不齐（偏差上百像素，且随副露副数变化），
 *   只能靠"标定系数"硬凑 —— 那是不可维护的。
 *
 * 现在的做法是让布局【自洽】：
 *   · 对家：牌竖放、副露横排（跟我一样），整块转 180° 由父组件用 --el-rot 施加在
 *     本组件外层 —— 180° 是各向同性的（宽高互换但尺寸不变），不会打乱排版。
 *   · 上家/下家：容器不转，改成"每张牌自己横过来（rotate ±90°）+ 副露排成一列"。
 *     布局盒永远等于内容尺寸，位置完全由 flex 决定，没有任何旋转半径参与排版。
 *
 * 一句话：能用"每张牌各自转"表达的，就不要用"整块转"。
 */
import { computed } from 'vue'
import Tile from '../tile/Tile.vue'

const props = defineProps({
  melds: { type: Array, default: () => [] },
  flowers: { type: Array, default: [] },
  size: { type: String, default: 'sm' },
  /** 这副露属于哪一家（服务端座位名），用来把 from 换算成左/中/右 */
  ownerSeat: { type: String, default: '' },
  /** 整块副露贴向哪一边，纯粹是布局用 */
  align: { type: String, default: 'center' },
  /** 'me' | 'top' | 'left' | 'right' —— 决定牌是竖放还是横放、副露横排还是竖排 */
  orientation: { type: String, default: 'me' },
  /** 这副露是不是我自己的（决定暗杠怎么显示：自己 3 背 + 1 面，别人 4 背） */
  mine: { type: Boolean, default: false },
})

const SEATS = ['EAST', 'SOUTH', 'WEST', 'NORTH']

/** 侧边两家：副露排成一列，每张牌横放 */
const isSide = computed(() => props.orientation === 'left' || props.orientation === 'right')

/**
 * 每张牌自己的旋转角 —— 让"那一家自己的视角"下牌面是正的。
 *   对家（上）180°：他坐在对面，所以他的牌在我屏幕上是倒的
 *   上家（左） +90°
 *   下家（右） −90°
 *   我（下）    0°
 *
 * ⚠️ 这里【不能】只处理左右两家：
 *   原来 top 返回 0°，结果对家的副露是正立的、他的手牌是 180° 的，
 *   两者朝向相反 —— 用户看到的"对家副露方向反了"就是这个。
 *   每一家的手牌和副露必须用同一个角度。
 */
const tileAngle = computed(() => {
  if (props.orientation === 'left') return 90
  if (props.orientation === 'right') return -90
  if (props.orientation === 'top') return 180
  return 0
})

/** 相对座位偏移 → 来源方位 */
function sourceSideOf(fromSeat) {
  if (!fromSeat) return null
  const oi = SEATS.indexOf(props.ownerSeat)
  const fi = SEATS.indexOf(fromSeat)
  if (oi < 0 || fi < 0) return null
  const rel = (fi - oi + 4) % 4
  if (rel === 1) return 'right' // 下家
  if (rel === 2) return 'top' // 对家
  if (rel === 3) return 'left' // 上家
  return null // rel === 0：自己（不该出现）
}

/**
 * 把一副露展开成带标记的牌序列。
 *
 * 每项 { tile, lying, side }：
 *   lying=true 表示这张要被横放（额外多转 90°，表达"这张是叫来的"）
 *   side 是来源方位，仅横放那张有
 *
 * 横放那张的插入位置由来源决定：
 *   left  → 第 0 位（最左）
 *   top   → 中间位
 *   right → 最后一位
 *
 * 方位以"这家自己的视角"为准（也就是局部布局里的左/中/右）。侧边两家整体
 * 转了 ±90°，所以局部的最"左"在屏幕上是最"上" —— 语义不变，这正是我们要的。
 *
 * 关于"哪一张是被叫的"：后端 tiles 不保证被叫那张在首位。
 *   碰/杠（同牌）：任取一张即可，牌面值一样，取首张。
 *   吃（三张不同）：被吃的是 from 那家打出的牌，但请求里拿不到那张的值，
 *                   取首张当被叫（观感上仍是一横两竖，且方位正确）。
 * 这个取舍只影响"哪一张横"，不影响方位表达，方位才是玩家真正要看的。
 */
function expand(meld) {
  const tiles = Array.isArray(meld.tiles) ? meld.tiles.slice() : []

  /*
   * 暗杠的特殊显示（用户要求）：
   *   · 别人家的暗杠：4 张牌背 —— 牌面对我不可见
   *     （后端对非持有者发的就是 hidden 版，不会把牌值传过来）
   *   · 我自己的暗杠：3 张牌背 + 1 张牌面 —— 自己当然知道自己杠的是什么
   * 暗杠没有来源，四张都不横放，所以直接在这里返回，不走下面的横放逻辑。
   */
  if (meld.type === 'AN_GANG') {
    const n = tiles.length || 4
    return Array.from({ length: n }, (_, i) => ({
      // 自己那副的最后一张亮牌面；其余（含别人家的全部）画牌背
      tile: props.mine && i === n - 1 ? tiles[n - 1] : 0,
      back: !(props.mine && i === n - 1),
      lying: false,
      side: null,
    }))
  }
  // 别人家的暗杠后端只发 hidden 版，tiles 是占位值 —— 一律画 4 张牌背
  if (meld.hidden) {
    const n = tiles.length || 4
    return Array.from({ length: n }, () => ({ tile: 0, back: true, lying: false, side: null }))
  }

  const side = sourceSideOf(meld.from)
  if (tiles.length < 3 || !side) {
    // 补杠 / 无来源 / 张数不合法：全部竖放
    return tiles.map((t) => ({ tile: t, back: false, lying: false, side: null }))
  }

  const [called, ...rest] = tiles
  let insertAt
  if (side === 'left') insertAt = 0
  else if (side === 'right') insertAt = rest.length
  else insertAt = Math.floor(rest.length / 2) // top → 中间

  const out = []
  rest.forEach((t, i) => {
    if (i === insertAt) out.push({ tile: called, back: false, lying: true, side })
    out.push({ tile: t, back: false, lying: false, side: null })
  })
  if (insertAt >= rest.length) out.push({ tile: called, back: false, lying: true, side })
  return out
}

const items = computed(() =>
  (props.melds || []).map((m, i) => ({
    key: `${m.type}-${i}`,
    tiles: expand(m),
  })),
)

const flowerList = computed(() => props.flowers || [])

/** 每张牌最终的角度：被叫的那张要和其余几张【垂直】，所以是"整体角 + 90°" */
function degOf(x) {
  return tileAngle.value + (x.lying ? 90 : 0)
}

/*
 * 这里【没有】"侧边纵向补偿"了，别再往回加。
 *
 * 上一版侧边是把整块副露 rotate(90°)，旋转中心在那一块的中心，而块的尺寸
 * 又等于"旋转前"的尺寸，两者不一致导致内容整体偏下，只好按副露副数算一个
 * 补偿量去抵消。
 * 现在侧边改成"每张牌各自横放 + 副露排成一列"（见下面的 .meldrow.is-side），
 * 容器不转、布局盒就等于视觉盒，补偿量自然为 0。
 */
</script>

<template>
  <div class="meldrow" :class="[`is-${align}`, { 'is-side': isSide }]">
    <div v-for="m in items" :key="m.key" class="meldrow__group">
      <Tile
        v-for="(x, i) in m.tiles"
        :key="i"
        :tile="x.tile"
        :size="size"
        :back="x.back"
        :rotate="degOf(x)"
        :class="{ 'is-called': x.lying }"
      />
      <!-- 被叫的那张已由"横放 + 位置"表达来源，不画文字角标 -->
    </div>

    <div v-if="flowerList.length" class="meldrow__group meldrow__group--flower">
      <Tile v-for="(t, i) in flowerList" :key="`f${i}`" :tile="t" :size="size" :rotate="tileAngle" />
    </div>
  </div>
</template>

<style scoped>
.meldrow {
  display: flex;
  align-items: center;
  gap: 8px;
}
/* 对家/我：副露横排（本来就是一行） */
.meldrow.is-left {
  justify-content: flex-start;
}
.meldrow.is-right {
  justify-content: flex-end;
}
.meldrow.is-center {
  justify-content: center;
}

/*
 * 侧边两家：排成一【列】。
 * 每组内部仍然是横排（4 张沿局部 X 铺开），但每张牌自己转了 ±90°，
 * 所以屏幕上看到的是"一条竖着的牌列"。容器没有任何 rotate，
 * 布局盒 = 内容尺寸，位置完全可预测。
 *
 * 组间留 5px：组内已经贴成一体（见上面 .meldrow.is-side .meldrow__group），
 * 这里再分开一点，才能一眼看出"这是两副不同的副露"。
 */
.meldrow.is-side {
  flex-direction: column;
  gap: 5px;
}

.meldrow__group {
  display: flex;
  align-items: center;
  gap: 1px;
  position: relative;
}
/*
 * 侧边两家：组【内】不要缝隙。
 *
 * ⚠️ 注意区分"组内"和"组间"，这两个是不同的东西：
 *   · 组内 = 一副副露自己的那几张（吃 678 万的三张）—— 用户要求【无缝】，贴成一体
 *   · 组间 = 两副不同副露之间 —— 这层间隔保留，否则两副副露会糊在一起看不出分界
 *   （上一轮我把这两件事搞反了，只改了组间。）
 *
 * 光写 gap: 0 是不够的，因为旋转后"布局盒"和"视觉盒"尺寸不一样：
 *   一张牌布局 30x41，转 90° 后视觉是 41x30 —— 竖列的步进按布局的 41px 走，
 *   而视觉上每张只占 30px 高，于是每两张之间凭空多出 11px 空档。
 *   被叫的那张转 180°（视觉 30x41），与邻居的差额又是 5.5px。
 * 所以按"相邻两张各自的实际视觉高"补偿负 margin，三种组合各给一个值，
 * 补偿后所有相邻对的间距都精确为 0。
 */
.meldrow.is-side .meldrow__group {
  gap: 0;
}
/* 两张都是横放（视觉高 30）：步进 41 → 30，补 -11 */
.meldrow.is-side .meldrow__group .tile + .tile {
  margin-top: -11px;
}
/* 被叫的那张（转 180°，视觉高 41）与邻居：步进应为 (41+30)/2 = 35.5，补 -6
   （理论值 -5.5，多给 0.5 是因为屏幕整体缩放到 0.789 倍后会有亚像素取整，
     实测残留 1px 缝；补到 -6 才是肉眼意义上的"贴死"。） */
.meldrow.is-side .meldrow__group .tile + .tile.is-called,
.meldrow.is-side .meldrow__group .tile.is-called + .tile {
  margin-top: -6px;
}
/* 侧边两家的组内也排成【列】：每张牌已各转 ±90°，沿局部 Y 排下来就是屏幕竖列 */
.meldrow.is-side .meldrow__group {
  flex-direction: column;
}

/* 被叫的那张（横放）略微下沉，和其余几张拉开层次 */
.meldrow__group :deep(.is-called) {
  filter: drop-shadow(0 2px 3px rgba(0, 0, 0, 0.55));
}
</style>
