<script setup>
/*
 * 中央牌河：四家打出的牌，全部分区摆放，**所有牌一律横放**。
 *
 * 为什么不再旋转左右两家：牌桌上的牌本来就是统一朝上的，
 * 之前给左右两家加 rotate(±90deg) 反而让牌面歪着、看不清是什么牌。
 * 现在四家都横放，只靠"位置"区分是谁打的。
 *
 * 布局（以我为中心）：
 *        对家
 *    上家  中央  下家
 *         我
 * 每家的牌按每行 6 张换行，超出往远离中心的方向继续排。
 */
import { computed } from 'vue'
import Tile from '../tile/Tile.vue'

const props = defineProps({
  top: { type: Array, default: () => [] },
  right: { type: Array, default: () => [] },
  bottom: { type: Array, default: () => [] },
  left: { type: Array, default: () => [] },
  highlight: { type: Object, default: () => ({ top: -1, right: -1, bottom: -1, left: -1 }) },
})

/** 每行 6 张，多余的换行 */
function rowsOf(list) {
  const out = []
  for (let i = 0; i < list.length; i += 6) {
    out.push({ start: i, tiles: list.slice(i, i + 6) })
  }
  return out
}

const topRows = computed(() => rowsOf(props.top))
const bottomRows = computed(() => rowsOf(props.bottom))
const leftRows = computed(() => rowsOf(props.left))
const rightRows = computed(() => rowsOf(props.right))
</script>

<template>
  <div class="river">
    <!-- 对家（上） -->
    <div class="river__zone river__zone--top">
      <div v-for="(row, ri) in topRows" :key="`t${ri}`" class="river__row">
        <Tile
          v-for="(t, i) in row.tiles"
          :key="`t${ri}-${i}`"
          :tile="t"
          size="xs"
          :highlight="highlight.top === row.start + i"
        />
      </div>
    </div>

    <!-- 上家（左） -->
    <div class="river__zone river__zone--left">
      <div v-for="(row, ri) in leftRows" :key="`l${ri}`" class="river__row">
        <Tile
          v-for="(t, i) in row.tiles"
          :key="`l${ri}-${i}`"
          :tile="t"
          size="xs"
          :highlight="highlight.left === row.start + i"
        />
      </div>
    </div>

    <!-- 下家（右） -->
    <div class="river__zone river__zone--right">
      <div v-for="(row, ri) in rightRows" :key="`r${ri}`" class="river__row">
        <Tile
          v-for="(t, i) in row.tiles"
          :key="`r${ri}-${i}`"
          :tile="t"
          size="xs"
          :highlight="highlight.right === row.start + i"
        />
      </div>
    </div>

    <!-- 我（下） -->
    <div class="river__zone river__zone--bottom">
      <div v-for="(row, ri) in bottomRows" :key="`b${ri}`" class="river__row">
        <Tile
          v-for="(t, i) in row.tiles"
          :key="`b${ri}-${i}`"
          :tile="t"
          size="xs"
          :highlight="highlight.bottom === row.start + i"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
/*
 * 3x3 网格把四家牌河摆进中央：
 *   [空]   [对家]  [空]
 *   [上家] [中央]  [下家]
 *   [空]   [我]    [空]
 * 中央那一格留给倒计时 / 回合提示 / 本把战绩。
 *
 * ⚠️ 每个区都要按【那一家的视角】旋转，保证四家看自己的牌河都是正的。
 * 旋转作用在区自己身上（不转网格容器）——旋转不改变布局包围盒，
 * 转容器会让它按未旋转尺寸占位并溢出。
 */
.river {
  position: absolute;
  inset: 0;
  display: grid;
  grid-template-columns: 1fr 1.9fr 1fr;
  grid-template-rows: 1fr 1.9fr 1fr;
  gap: 1px;
}
.river__zone {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  min-height: 0;
  transform-origin: center center;
}
.river__zone--top {
  grid-area: 1 / 2 / 2 / 3;
  align-items: center;
  justify-content: flex-start;
  /* 对家在对面看牌，他的牌河在他视角下是正的 → 相对我转 180° */
  transform: rotate(180deg);
}
.river__zone--bottom {
  grid-area: 3 / 2 / 4 / 3;
  align-items: center;
  justify-content: flex-end;
  /* 我自己的牌河：正常朝向 */
  transform: rotate(0deg);
}
.river__zone--left {
  grid-area: 2 / 1 / 3 / 2;
  align-items: flex-start;
  justify-content: center;
  /* 上家在左：+90°（与 GameTable 的 --el-rot 保持一致，用户标图确认） */
  transform: rotate(90deg);
}
.river__zone--right {
  grid-area: 2 / 3 / 3 / 4;
  align-items: flex-end;
  justify-content: center;
  /* 下家在右：-90° */
  transform: rotate(-90deg);
}

.river__row {
  display: flex;
  gap: 1px;
}
</style>
