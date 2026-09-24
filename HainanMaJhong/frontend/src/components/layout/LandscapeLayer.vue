<script setup>
/*
 * 1280x720 设计分辨率的固定框（.scale-layer）。
 *
 * 牌桌的坐标是像素级排好的（牌河 4xN、四家、副露），用 transform: scale 等比缩放
 * 能保证任何屏幕上的相对位置与设计稿完全一致。
 *
 * ⚠️ 这里【不】负责旋转。
 * 旋转由 #game-app 统一承担（见 styles/landscape.css + useLandscapeScale.js）：
 * 旧版把旋转做在页面里层，结果"旋转的是内容、背景不转"，还会让 fixed 元素
 * 参照的盒子跟屏幕对不上。现在整个应用（#game-app）一起转，坐标体系只有一套。
 * 缩放层只需要把 --scale 交给 CSS 即可，尺寸/居中/溢出裁剪都在 landscape.css 里。
 */
import { computed } from 'vue'
import { useLandscapeScale } from '../../composables/useLandscapeScale.js'

const { scale } = useLandscapeScale()

/* --scale 由 JS 写入；同时写在根元素上，兜底 CSS 里的 var(--scale, 1) */
const layerStyle = computed(() => ({ '--scale': scale.value }))
</script>

<template>
  <div class="scale-layer" :style="layerStyle">
    <slot />
  </div>
</template>
