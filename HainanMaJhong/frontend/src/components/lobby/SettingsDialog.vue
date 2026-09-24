<script setup>
/*
 * 设置弹窗：修改昵称 / 修改密码。
 *
 * 两个 tab 共用一个弹窗，因为它们的交互形状一样（几个输入框 + 一个提交按钮），
 * 拆成两个组件会多出一份遮罩/关闭/错误显示。
 *
 * 校验放在前端只为了【少跑一趟网络】，真正的规则由后端定：
 *   昵称 1~16 字（AuthController /updateNickname）
 *   密码 ≥6 位且两次一致（AuthController /password）
 * 后端的报错照样要显示出来 —— 前端过得了不代表后端认。
 */
import { computed, ref, watch } from 'vue'
import { updateNickname, updatePassword } from '../../api/api.js'

const props = defineProps({
  open: { type: Boolean, default: false },
  /** 当前昵称，用于预填 */
  nickname: { type: String, default: '' },
})
const emit = defineEmits(['close', 'saved'])

const tab = ref('nick') // 'nick' | 'pwd'
const busy = ref(false)
const error = ref('')
const okText = ref('')

const nick = ref('')
const oldPwd = ref('')
const newPwd = ref('')
const newPwd2 = ref('')

/** 每次打开都重置：上次的报错和密码不能留到下一次 */
watch(
  () => props.open,
  (v) => {
    if (!v) return
    tab.value = 'nick'
    error.value = ''
    okText.value = ''
    busy.value = false
    nick.value = props.nickname || ''
    oldPwd.value = ''
    newPwd.value = ''
    newPwd2.value = ''
  },
)

const canSubmit = computed(() => {
  if (busy.value) return false
  if (tab.value === 'nick') return nick.value.trim().length > 0 && nick.value.trim().length <= 16
  return oldPwd.value.length >= 6 && newPwd.value.length >= 6 && newPwd.value === newPwd2.value
})

async function submit() {
  if (!canSubmit.value) return
  error.value = ''
  okText.value = ''
  busy.value = true
  try {
    if (tab.value === 'nick') {
      const nickname = nick.value.trim()
      await updateNickname(nickname)
      // 本地也要更新：Lobby 的头部昵称、牌桌上的座位名都读这里
      localStorage.setItem('nickname', nickname)
      okText.value = '昵称已更新'
      emit('saved', { nickname })
    } else {
      await updatePassword(oldPwd.value, newPwd.value, newPwd2.value)
      okText.value = '密码已修改'
      oldPwd.value = ''
      newPwd.value = ''
      newPwd2.value = ''
      emit('saved', { password: true })
    }
  } catch (e) {
    error.value = (e && e.message) || '提交失败'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div v-if="open" class="sd" @click.self="emit('close')">
    <div class="sd__dlg panel">
      <header class="sd__head">
        <h2>账号设置</h2>
        <button class="btn btn--sm btn--ghost" type="button" @click="emit('close')">✕</button>
      </header>

      <div class="sd__tabs">
        <button class="sd__tab" :class="{ 'is-on': tab === 'nick' }" type="button" @click="tab = 'nick'">
          修改昵称
        </button>
        <button class="sd__tab" :class="{ 'is-on': tab === 'pwd' }" type="button" @click="tab = 'pwd'">
          修改密码
        </button>
      </div>

      <div class="sd__body">
        <template v-if="tab === 'nick'">
          <label class="sd__field">
            <span>新昵称</span>
            <input v-model="nick" maxlength="16" placeholder="1~16 个字" @keyup.enter="submit" />
          </label>
          <p class="sd__note">昵称会显示在牌桌的座位名和战绩里。</p>
        </template>

        <template v-else>
          <label class="sd__field">
            <span>原密码</span>
            <input v-model="oldPwd" type="password" autocomplete="current-password" placeholder="输入原密码" />
          </label>
          <label class="sd__field">
            <span>新密码</span>
            <input v-model="newPwd" type="password" autocomplete="new-password" placeholder="至少 6 位" />
          </label>
          <label class="sd__field">
            <span>确认新密码</span>
            <input
              v-model="newPwd2"
              type="password"
              autocomplete="new-password"
              placeholder="再输一次"
              @keyup.enter="submit"
            />
          </label>
          <p v-if="newPwd && newPwd2 && newPwd !== newPwd2" class="sd__note sd__note--bad">
            两次输入的新密码不一致
          </p>
        </template>

        <p v-if="error" class="sd__msg sd__msg--bad">{{ error }}</p>
        <p v-else-if="okText" class="sd__msg sd__msg--ok">{{ okText }}</p>
      </div>

      <footer class="sd__foot">
        <button class="btn btn--sm" type="button" @click="emit('close')">关闭</button>
        <button class="btn btn--sm btn--primary" type="button" :disabled="!canSubmit" @click="submit">
          {{ busy ? '提交中…' : tab === 'nick' ? '保存昵称' : '修改密码' }}
        </button>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.sd {
  position: fixed;
  inset: 0;
  z-index: 60;
  /* flex 居中：弹窗的百分比 max-height 对着遮罩的确定高度解析（见 .sd__dlg 注释） */
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 12px;
  background: rgba(0, 0, 0, 0.62);
  overflow-y: auto;
}
.sd__dlg {
  width: min(400px, 100%);
  /* 不能写 vh：竖屏虚拟横屏下 vh 是物理视口高，弹窗会被撑出可见框上下被切 */
  max-height: min(100%, var(--frame-h, 100vh));
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}
.sd__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--panel-border);
}
.sd__head h2 {
  margin: 0;
  font-size: 16px;
  color: var(--gold-300);
}
.sd__tabs {
  display: flex;
  gap: 6px;
  padding: 10px 16px 0;
}
.sd__tab {
  flex: 1 1 0;
  padding: 7px 10px;
  border: 1px solid var(--panel-border);
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.28);
  color: var(--ink-300);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.sd__tab.is-on {
  border-color: var(--gold-500);
  background: rgba(215, 178, 90, 0.18);
  color: var(--gold-300);
}
.sd__body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 16px;
}
.sd__field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--ink-300);
}
.sd__field input {
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid var(--panel-border);
  background: rgba(0, 0, 0, 0.35);
  color: var(--ink-100);
  font-size: 14px;
  outline: none;
}
.sd__field input:focus {
  border-color: var(--gold-500);
}
.sd__note {
  margin: 0;
  font-size: 11px;
  color: var(--ink-400);
}
.sd__note--bad {
  color: #ff8b83;
}
.sd__msg {
  margin: 0;
  font-size: 12px;
}
.sd__msg--bad {
  color: #ff8b83;
}
.sd__msg--ok {
  color: #8ee0b4;
}
.sd__foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 10px 16px;
  border-top: 1px solid var(--panel-border);
  background: rgba(0, 0, 0, 0.2);
}
</style>
