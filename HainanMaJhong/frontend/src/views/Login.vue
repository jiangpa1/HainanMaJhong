<script setup>
/*
 * 登录页（Vue 版）。
 *
 * 能登录、能注册、能跳转，视觉沿用整体设计令牌。
 *
 * ── 记住用户名 / 记住密码 ──
 *   用户名【默认就记住】：登录成功后写进 localStorage，下次进来自动预填。
 *   密码是【可选】的（勾「记住密码」才存），默认不存。
 *   ⚠️ 存的是明文 —— 这是"记住密码"的固有代价（本机任何脚本都能读出来），
 *      所以默认关掉；这是游戏账号，能用，但别拿这里的密码去复用别的站点。
 *   存储键单独用一个（hnLoginRemember），api.js 的 clearAuth() 是按具体键删的，
 *   所以【退出登录不会把记住的用户名一起清掉】。
 */
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { login, register, isLoggedIn, consumeLoginNotice } from '../api/api.js'
import { enterGameMode } from '../composables/useLandscapeScale.js'

const router = useRouter()

const mode = ref('login') // login | register
const username = ref('')
const password = ref('')
const nickname = ref('')
const busy = ref(false)
const error = ref('')
/** 勾上才把密码也存起来；用户名无论如何都记 */
const rememberPassword = ref(false)

/** 记住的用户名/密码存这里（与 api.js 的登录态键分开，退出登录不影响它） */
const REMEMBER_KEY = 'hnLoginRemember'

function readRemember() {
  try {
    const raw = localStorage.getItem(REMEMBER_KEY)
    if (!raw) return
    const saved = JSON.parse(raw)
    if (!saved || typeof saved !== 'object') return
    if (typeof saved.username === 'string') username.value = saved.username
    if (saved.rememberPassword === true && typeof saved.password === 'string') {
      rememberPassword.value = true
      password.value = saved.password
    }
  } catch (e) {
    // 脏数据或隐私模式：忽略，不能因为"记住我"把登录页搞白
  }
}

function writeRemember() {
  try {
    const saved = { username: username.value.trim() }
    if (rememberPassword.value) {
      saved.rememberPassword = true
      saved.password = password.value
    }
    localStorage.setItem(REMEMBER_KEY, JSON.stringify(saved))
  } catch (e) {
    /* 忽略 */
  }
}

/** 取消勾选时立刻抹掉已存的密码，别留在本机 */
function dropSavedPassword() {
  try {
    const raw = localStorage.getItem(REMEMBER_KEY)
    const saved = raw ? JSON.parse(raw) : {}
    delete saved.password
    delete saved.rememberPassword
    saved.username = username.value.trim()
    localStorage.setItem(REMEMBER_KEY, JSON.stringify(saved))
  } catch (e) {
    /* 忽略 */
  }
}

// 先读出来再判断登录态：两个顺序无关，但先读能让预填更早生效
readRemember()

/*
 * 被顶下线时后端/前端会在 sessionStorage 留一条一次性提示，
 * 这里取出来显示（取完即清，刷新页面不会反复弹）。
 * 典型文案："该账号在别处登录，请重新登录"。
 */
const kickedNotice = consumeLoginNotice()
if (kickedNotice) {
  error.value = kickedNotice
}

watch(rememberPassword, (on) => {
  if (!on) dropSavedPassword()
})

if (isLoggedIn()) {
  router.replace('/lobby')
}

async function submit() {
  error.value = ''
  if (!username.value || !password.value) {
    error.value = '请填写用户名和密码'
    return
  }
  busy.value = true
  // 旧版把 enterGameMode 绑在「进入游戏」按钮上（data-landscape）。这里同样是
  // 用户手势时机：补一次重算，iOS 地址栏收起后布局才不会算错。
  enterGameMode()
  try {
    if (mode.value === 'login') {
      await login(username.value.trim(), password.value)
    } else {
      await register(username.value.trim(), password.value, nickname.value.trim() || undefined)
      // 注册成功后直接登录，省一步
      await login(username.value.trim(), password.value)
    }
    // 只在【登录成功】后才记，避免把打错的用户名也存下来
    writeRemember()
    router.replace('/lobby')
  } catch (e) {
    error.value = e && e.message ? e.message : '操作失败，请重试'
  } finally {
    busy.value = false
  }
}

function toggleMode() {
  mode.value = mode.value === 'login' ? 'register' : 'login'
  error.value = ''
}
</script>

<template>
  <div class="login">
    <!-- 卡片居中区：录屏/小屏时这里自己滚，备案号永远钉在底部 -->
    <div class="login__body">
      <div class="login__card panel">
        <h1 class="login__title">海南麻将</h1>
        <p class="login__sub">{{ mode === 'login' ? '登录后开始对局' : '注册一个新账号' }}</p>

        <form class="login__form" @submit.prevent="submit">
          <label class="field">
            <span>用户名</span>
            <input
              v-model="username"
              type="text"
              autocomplete="username"
              maxlength="20"
              placeholder="4-20 位"
            />
          </label>

          <label v-if="mode === 'register'" class="field">
            <span>昵称（可选）</span>
            <input v-model="nickname" type="text" maxlength="32" placeholder="牌桌上显示的名字" />
          </label>

          <label class="field">
            <span>密码</span>
            <input
              v-model="password"
              type="password"
              :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
              placeholder="至少 6 位"
            />
          </label>

          <label class="login__remember">
            <input v-model="rememberPassword" type="checkbox" />
            <span>记住密码</span>
            <em>用户名已自动记住</em>
          </label>

          <p v-if="error" class="login__error">{{ error }}</p>

          <button class="btn btn--primary login__submit" type="submit" :disabled="busy">
            {{ busy ? '处理中…' : mode === 'login' ? '登录' : '注册并登录' }}
          </button>
        </form>

        <button class="login__switch" type="button" @click="toggleMode">
          {{ mode === 'login' ? '没有账号？去注册' : '已有账号？去登录' }}
        </button>
      </div>
    </div>

    <!-- 页脚备案：与大厅底部同一个号，登录页也要露出（工信部要求） -->
    <footer class="login__foot">
      <a
        class="beian"
        href="https://beian.miit.gov.cn/"
        target="_blank"
        rel="noopener noreferrer"
      >琼ICP备2026013323号</a>
    </footer>
  </div>
</template>

<style scoped>
.login {
  /* 不能用 100vh：物理视口高度 ≠ 旋转后容器高度（见 styles/landscape.css 注释）。
     作为 #game-app 的 flex 子项自动撑满，超出时在本层滚动。 */
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: radial-gradient(ellipse at 50% 35%, var(--felt-600) 0%, var(--felt-800) 55%, var(--felt-900) 100%);
}
/* 卡片区：占满剩余高度并居中；内容比屏幕高时只滚这里，底部备案号不会被顶走 */
.login__body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  display: grid;
  place-items: center;
  padding: 20px;
}
.login__foot {
  flex: 0 0 auto;
  text-align: center;
  padding: 6px 0 8px;
}
.beian {
  font-size: 12px;
  color: var(--ink-400);
  text-decoration: none;
}
.beian:hover {
  color: var(--gold-300);
  text-decoration: underline;
}
.login__card {
  width: 100%;
  max-width: 380px;
  padding: 28px 26px;
  text-align: center;
}
.login__title {
  margin: 0 0 4px;
  font-size: 26px;
  letter-spacing: 4px;
  color: var(--gold-300);
}
.login__sub {
  margin: 0 0 20px;
  color: var(--ink-300);
  font-size: 13px;
}
.login__form {
  display: flex;
  flex-direction: column;
  gap: 12px;
  text-align: left;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 13px;
  color: var(--ink-200);
}
.field input {
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid var(--panel-border);
  background: rgba(0, 0, 0, 0.35);
  color: var(--ink-100);
  font-size: 15px;
  outline: none;
}
.field input:focus {
  border-color: var(--gold-500);
}
/* 记住密码：左勾选框 + 右侧一句灰字说明（用户名不用勾，本来就记） */
.login__remember {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  color: var(--ink-200);
  cursor: pointer;
  user-select: none;
  -webkit-tap-highlight-color: transparent;
}
.login__remember input {
  width: 15px;
  height: 15px;
  margin: 0;
  accent-color: var(--gold-500);
  cursor: pointer;
}
.login__remember em {
  margin-left: auto;
  font-style: normal;
  font-size: 11px;
  color: var(--ink-400);
}
.login__error {
  margin: 0;
  color: #ff8b83;
  font-size: 13px;
}
.login__submit {
  margin-top: 4px;
  padding: 11px;
}
.login__switch {
  margin-top: 14px;
  background: none;
  border: 0;
  color: var(--gold-400);
  cursor: pointer;
  font-size: 13px;
}
.login__switch:hover {
  text-decoration: underline;
}
</style>
