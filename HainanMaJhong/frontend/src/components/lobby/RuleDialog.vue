<script setup>
/*
 * 房间规则选择弹窗（点「创建房间」时出现）。
 *
 * 为什么要先弹一次：底分、杠分、花分、番型倍数都直接影响金币结算，
 * 一局打完十几把才发现规则不对就白打了。规则会存进 localStorage，
 * 下次建房沿用上次的设置。
 *
 * 字段与后端 HainanConfig 一一对应，详见 game/roomConfig.js 的说明。
 */
import { computed, ref, watch } from 'vue'
import { ROOM_CFG_DEFAULTS, ROOM_CFG_GROUPS, loadRoomCfg, saveRoomCfg, roomCfgPayload } from '../../game/roomConfig.js'

const props = defineProps({
  open: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'confirm'])

const cfg = ref(loadRoomCfg())

/** 每次打开都重新读一次本地值：可能是别的标签页刚改过 */
watch(
  () => props.open,
  (v) => {
    if (v) cfg.value = loadRoomCfg()
  },
)

const isDefault = computed(() =>
  Object.keys(ROOM_CFG_DEFAULTS).every((k) => cfg.value[k] === ROOM_CFG_DEFAULTS[k]),
)

/** 数字项统一走这个：空串/负数/非数字都归 0，绝不把 NaN 发给后端 */
function setNum(key, raw) {
  const v = parseInt(raw, 10)
  cfg.value[key] = Number.isFinite(v) && v >= 0 ? v : 0
}

function reset() {
  cfg.value = { ...ROOM_CFG_DEFAULTS }
}

function confirm() {
  const payload = roomCfgPayload(cfg.value)
  saveRoomCfg(payload)
  emit('confirm', payload)
}
</script>

<template>
  <div v-if="open" class="rd" @click.self="emit('close')">
    <div class="rd__dlg panel">
      <header class="rd__head">
        <h2>房间规则设置</h2>
        <button class="btn btn--sm btn--ghost" type="button" @click="emit('close')">✕</button>
      </header>

      <div class="rd__body">
        <!-- 胡牌模式：只有两个选项，单独一行更好点 -->
        <div class="rd__group">胡牌模式</div>
        <div class="rd__modes">
          <label class="rd__mode" :class="{ 'is-on': cfg.fanGate }">
            <input v-model="cfg.fanGate" type="radio" :value="true" name="fanGate" />
            <span>有番</span>
            <em>有番才能胡（原规则）</em>
          </label>
          <label class="rd__mode" :class="{ 'is-on': !cfg.fanGate }">
            <input v-model="cfg.fanGate" type="radio" :value="false" name="fanGate" />
            <span>无番</span>
            <em>结构合法即可胡</em>
          </label>
        </div>

        <template v-for="g in ROOM_CFG_GROUPS" :key="g.title">
          <div class="rd__group">
            {{ g.title }}
            <span v-if="g.hint" class="rd__hint">{{ g.hint }}</span>
          </div>
          <div class="rd__rows">
            <label v-for="r in g.rows" :key="r[0]" class="rd__row">
              <span class="rd__lab">
                {{ r[1] }}
                <em v-if="r[2]" class="rd__sub">{{ r[2] }}</em>
              </span>
              <input
                :id="'cfg_' + r[0]"
                class="rd__input"
                type="number"
                min="0"
                inputmode="numeric"
                :value="cfg[r[0]]"
                @input="setNum(r[0], $event.target.value)"
              />
            </label>
          </div>
        </template>
      </div>

      <footer class="rd__foot">
        <span class="rd__state">{{ isDefault ? '当前是默认规则' : '已自定义（会记住）' }}</span>
        <div class="rd__btns">
          <button class="btn btn--sm" type="button" @click="reset">恢复默认</button>
          <button class="btn btn--sm btn--primary" type="button" @click="confirm">创建房间</button>
        </div>
      </footer>
    </div>
  </div>
</template>

<style scoped>
/* 遮罩 */
.rd {
  position: fixed;
  inset: 0;
  z-index: 60;
  /* 用 flex 居中而不是 grid：flex 项的百分比 max-height 会对着
     容器【确定的高度】解析，弹窗才能被真正压到可见框以内（详见下面的注释） */
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 12px;
  background: rgba(0, 0, 0, 0.62);
  /* 第二道保险：万一弹窗还是比可见框高，遮罩自己可以滚 */
  overflow-y: auto;
}
.rd__dlg {
  width: min(680px, 100%);
  /*
   * ⚠️ 这里原来写的是 max-height: min(88vh, 720px)。
   * vh 是【物理视口】高度、不随 transform 变化：手机竖屏虚拟横屏时可见框高
   * 只有 ~390px，而 88vh ≈ 743px —— 弹窗比可见框高一倍，上下被 #game-app 的
   * overflow: hidden 切掉（用户实测：规则窗口上界和下界都不在屏幕内）。
   * 旧版 lobby.html 用 min(88vh, 92vw, 820px) 绕过（92vw 就是屏幕短边）；
   * 这里直接对着遮罩的高度取 100%（遮罩是 inset:0 = 可见框），更精确且与朝向无关。
   */
  max-height: min(100%, var(--frame-h, 100vh));
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}
.rd__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--panel-border);
}
.rd__head h2 {
  margin: 0;
  font-size: 16px;
  color: var(--gold-300);
}
.rd__body {
  flex: 1 1 auto;
  overflow-y: auto;
  padding: 10px 16px 14px;
}

/* 分组标题 */
.rd__group {
  margin: 14px 0 6px;
  font-size: 13px;
  font-weight: 700;
  color: var(--gold-300);
}
.rd__group:first-child {
  margin-top: 4px;
}
.rd__hint {
  margin-left: 8px;
  font-weight: 400;
  font-size: 11px;
  color: var(--ink-400);
}

/* 胡牌模式两个大按钮 */
.rd__modes {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.rd__mode {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border-radius: 9px;
  border: 1px solid var(--panel-border);
  background: rgba(0, 0, 0, 0.28);
  cursor: pointer;
}
.rd__mode.is-on {
  border-color: var(--gold-500);
  background: rgba(215, 178, 90, 0.18);
}
.rd__mode input {
  display: none;
}
.rd__mode span {
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-100);
}
.rd__mode em {
  font-size: 11px;
  font-style: normal;
  color: var(--ink-400);
}

/* 数值行：两列自适应 */
.rd__rows {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
  gap: 6px 14px;
}
.rd__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 4px 0;
  border-bottom: 1px dashed rgba(255, 255, 255, 0.07);
}
.rd__lab {
  font-size: 13px;
  color: var(--ink-200);
}
.rd__sub {
  display: block;
  font-size: 10px;
  font-style: normal;
  color: var(--ink-400);
}
.rd__input {
  flex: 0 0 72px;
  width: 72px;
  padding: 5px 8px;
  border-radius: 6px;
  border: 1px solid var(--panel-border);
  background: rgba(0, 0, 0, 0.35);
  color: var(--ink-100);
  font-size: 14px;
  text-align: right;
  outline: none;
}
.rd__input:focus {
  border-color: var(--gold-500);
}

.rd__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 16px;
  border-top: 1px solid var(--panel-border);
  background: rgba(0, 0, 0, 0.2);
}
.rd__state {
  font-size: 11px;
  color: var(--ink-400);
}
.rd__btns {
  display: flex;
  gap: 8px;
}
</style>
