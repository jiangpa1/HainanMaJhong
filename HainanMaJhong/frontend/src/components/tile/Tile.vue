<script setup>
/*
 * 单张麻将牌。
 *
 * 牌面有两套来源，按优先级自动选择：
 *
 *   1. 真实牌图 /img/tiles/<id>.png  ← 默认
 *      项目里已经有一整套 42 张牌面（181x238，按后端牌索引命名，
 *      传统中国麻将样式：一萬是蓝红书法体、一筒是莲花纹）。
 *      牌图自带圆角与描边，所以容器不再自己画牌身。
 *
 *   2. 自研 SVG 牌面（game/tiles.js 的 tileFaceSvg）
 *      兜底用：牌图加载失败、或显式传 face="svg"。
 *      好处是矢量任意缩放都清晰，且不依赖图片资源。
 *
 * 状态语义：
 *   clickable  轮到我出牌，这张可以点
 *   banned     引擎下发的禁打牌（吃后不能打同一张），置灰且不可点
 *   selected   已被选中（吃碰选择时用）
 *   highlight  刚打出的"最新那张"，加高亮
 *   dim        变暗（结算时非相关牌）
 */
import { computed, ref, watch } from 'vue'
import { tileFaceSvg, tileName, tileOf } from '../../game/tiles.js'

const props = defineProps({
  tile: { type: Number, required: true },
  /** 尺寸档位：与 CSS 里的 --tw 对应。xs2 = 其他三家的手牌，比 xs 大一圈 */
  size: {
    type: String,
    default: 'md',
    validator: (v) => ['xs', 'xs2', 'sm', 'md', 'lg'].includes(v),
  },
  /** 是否显示背面（其他人手牌） */
  back: { type: Boolean, default: false },
  clickable: { type: Boolean, default: false },
  banned: { type: Boolean, default: false },
  selected: { type: Boolean, default: false },
  highlight: { type: Boolean, default: false },
  dim: { type: Boolean, default: false },
  /**
   * 旋转角度（度）。
   * 接受任意数值（左右两家整块要 ±90°，被叫的那张还要再横过来），
   * 也接受 'lying'（横放 90°，用于被吃碰杠的那张牌）。
   */
  rotate: { type: [Number, String], default: 0 },
  /** 'img'（默认，用真实牌图） | 'svg'（用自绘矢量牌面） */
  face: { type: String, default: 'img', validator: (v) => ['img', 'svg'].includes(v) },
})

const emit = defineEmits(['pick'])

const meta = computed(() => tileOf(props.tile))
const svgFace = computed(() => (props.back ? '' : tileFaceSvg(props.tile)))
const label = computed(() => (props.back ? '牌背' : tileName(props.tile)))

/** 牌图加载失败时自动降级到 SVG，避免出现空白牌 */
const imgFailed = ref(false)
watch(
  () => [props.tile, props.face],
  () => {
    imgFailed.value = false
  },
)

const useImg = computed(() => !props.back && props.face === 'img' && !imgFailed.value)

/** 正面牌图：按后端索引命名 */
const faceSrc = computed(() => `/img/tiles/${props.tile}.png`)

/**
 * 背面牌图。
 *
 * 写成变量而不是模板里的字面量 "/img/tile_back.png"：Vite 会把模板里的
 * 静态绝对路径当【模块】去解析并在构建期打包，而这些图是 Spring Boot
 * 在运行时从 static/ 托管的，不归前端构建管。用绑定值即可绕开。
 */
const backSrc = '/img/tile_back.png'

const classes = computed(() => [
  'tile',
  `tile--${props.size}`,
  {
    'is-clickable': props.clickable && !props.banned && !props.back,
    'is-banned': props.banned,
    'is-selected': props.selected,
    'is-highlight': props.highlight,
    'is-dim': props.dim,
    'is-back': props.back,
    'is-img': useImg.value,
  },
])

/**
 * 旋转角度（度）。
 * 'lying' 是语义化的"横放 90°"——麻将桌上被吃碰杠来的那张要横过来。
 * 数值角度用于"整家视角"（上家 +90°、下家 −90°）。
 */
const rotateDeg = computed(() => {
  if (props.rotate === 'lying') return 90
  const n = Number(props.rotate)
  return Number.isFinite(n) ? n : 0
})

const style = computed(() =>
  rotateDeg.value ? { '--tile-rotate': `${rotateDeg.value}deg` } : {},
)

/** 这张牌此刻是不是"可点的牌"（决定用 button 还是 span，见下面模板注释） */
const interactive = computed(() => props.clickable && !props.banned && !props.back)

function onClick() {
  if (props.back || props.banned || !props.clickable) return
  emit('pick', props.tile)
}

function onImgError() {
  // 牌图缺失（比如新加了牌种还没出图）时退回矢量牌面，而不是留一张空牌
  console.warn(`[Tile] 牌图加载失败，降级为矢量牌面: ${faceSrc.value}`)
  imgFailed.value = true
}
</script>

<template>
  <!--
    只有真正可点的牌才渲染成 <button>，其余用 <span>。
    为什么必须区分：牌也会被画在别的按钮【内部】（例如操作栏的"吃(四万)"按钮里
    带牌面预览）。button 嵌 button 是非法 HTML，浏览器会把它拆开，
    导致点击事件绑到错误的元素上、动作按钮点了没反应。
  -->
  <component
    :is="interactive ? 'button' : 'span'"
    :class="classes"
    :style="style"
    :title="label"
    :aria-label="label"
    :type="interactive ? 'button' : undefined"
    :disabled="interactive ? false : undefined"
    @click="onClick"
  >
    <!-- 背面：项目自带的 tile_back.png（绿底云纹） -->
    <img v-if="back" class="tile__img" :src="backSrc" alt="牌背" draggable="false" />

    <!-- 正面：优先用真实牌图 -->
    <img
      v-else-if="useImg"
      class="tile__img"
      :src="faceSrc"
      :alt="label"
      draggable="false"
      @error="onImgError"
    />

    <!-- 兜底：自绘矢量牌面 -->
    <svg v-else class="tile__svg" viewBox="0 0 34 46" v-html="svgFace" />

    <span class="tile__gloss" aria-hidden="true" />
  </component>
</template>

<style scoped>
/*
 * 尺寸由 --tw / --th 控制：牌河 sm、手牌 md、他人副露 xs、放大展示 lg。
 *
 * 用真实牌图时【不】自己画牌身（牌图自带圆角描边），只用 drop-shadow 做投影；
 * 用 SVG 兜底时才给一个象牙白底，避免白底白牌看不清。
 */
.tile {
  --tw: 44px;
  --th: 60px;
  --tile-rotate: 0deg;

  /*
   * ⚠️ display 必须显式声明，不能靠默认值。
   *
   * 牌在没有 interactive 时渲染成 <span>，而 <span> 默认是【行内元素】——
   * 行内元素上写 width/height 是【完全无效】的，牌会按牌图原图大小（181x238）撑开。
   * 在 flex 容器里看不出问题（flex 子项会被块化，尺寸生效），
   * 一旦放进普通行内容器（比如战绩页的 .round__tile）就会炸成一张巨牌。
   * inline-block 在 flex/grid 里会被块化成 block，不影响那些布局。
   */
  display: inline-block;
  position: relative;
  flex: 0 0 auto;
  width: var(--tw);
  height: var(--th);
  padding: 0;
  border: 0;
  border-radius: calc(var(--tw) * 0.13);
  background: transparent;
  cursor: default;
  transform: rotate(var(--tile-rotate));
  transition: transform 0.12s ease, filter 0.12s ease;
  -webkit-tap-highlight-color: transparent;
  /* 牌图是位图，缩放时用平滑插值，避免边缘锯齿 */
  filter: drop-shadow(0 calc(var(--tw) * 0.05) calc(var(--tw) * 0.07) rgba(0, 0, 0, 0.42));
}

/* SVG 兜底时才铺牌身底色 */
.tile:not(.is-img):not(.is-back) {
  background: linear-gradient(168deg, #fffdf6 0%, #f6f0e2 62%, #e6dcc6 100%);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.9) inset,
    0 calc(var(--tw) * 0.05) 0 #b9ad93;
}

.tile--xs { --tw: 24px; --th: 33px; }
.tile--sm { --tw: 32px; --th: 44px; }
.tile--md { --tw: 44px; --th: 60px; }
.tile--lg { --tw: 58px; --th: 79px; }
/* 用于其他三家的手牌：比 xs 明显大一圈 */
.tile--xs2 { --tw: 30px; --th: 41px; }

/*
 * 牌背。
 *
 * 用户的原始诉求："牌背外面不要有透明框"。
 * 成因：/img/tile_back.png 原本是 1536x1024 的方块图，牌本体只占中间，
 * 左右各有 405px 白边。用 object-fit: contain 塞进 24x33 的槽位时，
 * 那圈白边就会被压成"每个牌背外面一个框"。
 * 已把图片裁到牌本体（726x976，左右白边已去），这里再用 cover 兜底，
 * 并铺一层纯绿色底 —— 即使图片缺失也不会出现白框。
 */
.tile.is-back {
  background: linear-gradient(160deg, #2f6b4f 0%, #1f4a37 70%, #16352a 100%);
}
.tile.is-back .tile__img {
  object-fit: cover;
}
/* 牌背不需要高光：那层白色渐变叠在绿底上会显得"发灰" */
.tile.is-back .tile__gloss {
  display: none;
}
/* 牌背也不要投影 —— 十几张叠在一起时投影会把画面压脏 */
.tile.is-back {
  filter: none;
}

.tile__img,
.tile__svg {
  display: block;
  width: 100%;
  height: 100%;
  pointer-events: none;
  border-radius: inherit;
}
/* 牌图原始比例约 181:238，用 contain 保证不变形（比 cover 更安全：
   cover 会裁掉牌面边缘的笔画） */
.tile__img {
  object-fit: contain;
  user-select: none;
  -webkit-user-drag: none;
}

/* 左上角高光，让牌面看起来是凸起的瓷面 */
.tile__gloss {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: linear-gradient(150deg, rgba(255, 255, 255, 0.42) 0%, rgba(255, 255, 255, 0) 40%);
  pointer-events: none;
  mix-blend-mode: screen;
  opacity: 0.55;
}

/* 可点：悬停上浮，明确"这张能打" */
.tile.is-clickable {
  cursor: pointer;
}
.tile.is-clickable:hover {
  transform: rotate(var(--tile-rotate)) translateY(-6px);
  filter: drop-shadow(0 calc(var(--tw) * 0.14) calc(var(--tw) * 0.16) rgba(0, 0, 0, 0.5));
}
.tile.is-clickable:active {
  transform: rotate(var(--tile-rotate)) translateY(-2px);
}

/* 禁打：吃后不能打同一张，压暗并去掉浮起效果 */
.tile.is-banned {
  filter: grayscale(0.85) brightness(0.66);
  cursor: not-allowed;
}

/* 选中 / 最新打出：用金色外发光（不改牌图本身，保证牌面可辨认） */
.tile.is-selected {
  transform: rotate(var(--tile-rotate)) translateY(-10px);
  filter: drop-shadow(0 0 3px #ffcf4d) drop-shadow(0 0 10px rgba(255, 207, 77, 0.9))
    drop-shadow(0 calc(var(--tw) * 0.14) calc(var(--tw) * 0.16) rgba(0, 0, 0, 0.5));
}
.tile.is-highlight {
  filter: drop-shadow(0 0 3px #ffb300) drop-shadow(0 0 12px rgba(255, 179, 0, 0.95))
    drop-shadow(0 calc(var(--tw) * 0.05) calc(var(--tw) * 0.07) rgba(0, 0, 0, 0.42));
}

.tile.is-dim {
  filter: brightness(0.6) saturate(0.7);
}

@media (prefers-reduced-motion: reduce) {
  .tile {
    transition: none;
  }
}
</style>
