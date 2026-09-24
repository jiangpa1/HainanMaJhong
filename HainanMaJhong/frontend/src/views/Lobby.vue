<script setup>
/*
 * 大厅页（Vue 版）。
 *
 * 职责边界（与原实现一致）：
 *   大厅   —— 展示用户信息、创建/加入房间（只做跳转，不建房间）、查看是否有可返回的对局
 *   房间页 —— 真正的 WebSocket /room：createRoom / joinRoom / fillBots / startNow
 *   牌桌页 —— WebSocket /game
 *
 * 也就是说 "创建房间" 只是【先弹规则设置、再】带着 action=create 跳到房间页，
 * 由房间页发 WebSocket。这样房间的创建/加入只有一处实现，不会两边不一致。
 *
 * 页面结构对照旧版 static/lobby.html：
 *   顶栏  —— 头像 + 昵称 + 设置(⚙) + 关于(?)
 *   主区  —— 创建房间 / 加入房间
 *   底栏  —— 下载APP / 战绩查询
 *   页脚  —— ICP 备案号
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { isLoggedIn, logout, me, pendingReturn, profile, checkRoom } from '../api/api.js'
import RuleDialog from '../components/lobby/RuleDialog.vue'
import SettingsDialog from '../components/lobby/SettingsDialog.vue'
import { preloadAssets } from '../game/preload.js'

const router = useRouter()

const user = ref(profile())
const code = ref('')
const busy = ref(false)
const error = ref('')
/** 有未结束的对局时显示"返回房间" */
const returnInfo = ref(null)

/* 弹窗开关 */
const ruleOpen = ref(false)
const setOpen = ref(false)
/** 关于/版本说明 */
const helpOpen = ref(false)
/** 建房的规则（确认后在跳转前存起来，房间页会读同一份） */
const pendingCfg = ref(null)

onMounted(async () => {
  if (!isLoggedIn()) {
    router.replace('/')
    return
  }
  /*
   * 提前热身：牌面图 + 音效有一百多个文件，等进房间再下就晚了。
   * 这里【不等它】—— 大厅该干嘛干嘛，进度在房间页显示、开局前必须下完。
   */
  preloadAssets()
  // 拉最新用户信息；失败也不阻断（token 可能刚过期，交给请求层处理）
  try {
    user.value = await me()
  } catch (e) {
    /* 忽略 */
  }
  try {
    const info = await pendingReturn()
    if (info && info.exists) returnInfo.value = info
  } catch (e) {
    /* 忽略 */
  }
})

const nickname = computed(() => (user.value && (user.value.nickname || user.value.username)) || '玩家')
/** 头像取昵称首字，和旧版的「雀」一个意思但跟着用户走 */
const avatarText = computed(() => (nickname.value || '雀').slice(0, 1))

/* ---------------- 创建 / 加入 ---------------- */

function openCreate() {
  if (busy.value) return
  ruleOpen.value = true
}

/** 规则弹窗确认：规则已由弹窗写进 localStorage，这里只负责跳转 */
function onRuleConfirm(cfg) {
  pendingCfg.value = cfg
  ruleOpen.value = false
  busy.value = true
  router.push('/room?action=create')
}

function joinRoom() {
  const c = (code.value || '').trim()
  // ① 先做本地格式校验：明显不对的连请求都不用发
  if (!/^\d{6}$/.test(c)) {
    error.value = '房间号应为 6 位数字'
    return
  }
  error.value = ''
  busy.value = true
  // ② 再问后端"这个房间能不能进"（是否存在 / 已解散 / 已开局 / 人满）
  //    不通过就【停在大厅】显示原因，不跳转 —— 跳过去只会看到同一个错误，
  //    而且人已经离开大厅，还得再点回来。
  checkRoom(c)
    .then(() => {
      router.push({ path: '/room', query: { action: 'join', code: c } })
    })
    .catch((e) => {
      error.value = (e && e.message) || '无法加入该房间'
    })
    .finally(() => {
      busy.value = false
    })
}

/** 输入框只留数字（粘贴进来的空格/字母直接过滤掉） */
function onCodeInput() {
  code.value = String(code.value || '').replace(/\D/g, '').slice(0, 6)
  if (error.value) error.value = ''
}

function backToRoom() {
  const info = returnInfo.value
  if (!info) return
  // 对局中直接回牌桌；等待房回房间页
  if (info.state === 'PLAYING') {
    router.push({ path: '/game', query: { code: info.code, seat: info.seat } })
  } else {
    router.push({ path: '/room', query: { action: 'join', code: info.code } })
  }
}

function openRecords() {
  router.push('/records')
}

/** 下载 APP：后端 AppDownloadController 挂的是 /download/app */
function downloadApp() {
  window.location.href = '/download/app'
}

async function doLogout() {
  await logout()
  router.replace('/')
}

/** 昵称改完要把顶栏也刷新（api.js 已写 localStorage，这里同步视图） */
function onSettingsSaved(payload) {
  if (payload && payload.nickname) {
    user.value = { ...(user.value || {}), nickname: payload.nickname }
  }
}
</script>

<template>
  <div class="lobby">
    <!-- 顶栏 -->
    <header class="lobby__top">
      <div class="lobby__user">
        <div class="lobby__avatar">{{ avatarText }}</div>
        <span class="lobby__nick" :title="nickname">{{ nickname }}</span>
      </div>
      <div class="lobby__topbtns">
        <button class="btn btn--sm btn--ghost" type="button" title="设置" @click="setOpen = true">⚙ 设置</button>
        <button class="btn btn--sm btn--ghost" type="button" title="关于" @click="helpOpen = true">? 关于</button>
      </div>
    </header>

    <!-- 主区 -->
    <main class="lobby__main">
      <div v-if="returnInfo" class="card panel card--return">
        <h2>你有一局未结束</h2>
        <p>
          房间 <b>{{ returnInfo.code }}</b> · {{ returnInfo.state === 'PLAYING' ? '对局中' : '等待中' }} ·
          座位 {{ returnInfo.seat }}
        </p>
        <button class="btn btn--primary" type="button" @click="backToRoom">返回房间</button>
      </div>

      <div class="card panel card--create">
        <h2>创建房间</h2>
        <p class="card__hint">先设规则（底分、杠分、花分、番型倍数），再进房间补电脑或邀请好友。</p>
        <button class="btn btn--primary" type="button" :disabled="busy" @click="openCreate">创建新房间</button>
      </div>

      <div class="card panel">
        <h2>加入房间</h2>
        <p class="card__hint">输入房间号。</p>
        <div class="joinrow">
          <input
            v-model="code"
            class="joinrow__input"
            type="text"
            inputmode="numeric"
            maxlength="6"
            placeholder="6 位房间号"
            @input="onCodeInput"
            @keyup.enter="joinRoom"
          />
          <button class="btn" type="button" :disabled="busy" @click="joinRoom">加入</button>
        </div>
        <p v-if="error" class="card__error">{{ error }}</p>
      </div>
    </main>

    <!-- 底栏 -->
    <footer class="lobby__dock">
      <button class="iconbtn" type="button" @click="downloadApp"><span>⬇</span>下载APP</button>
      <button class="iconbtn" type="button" @click="openRecords"><span>🏆</span>战绩查询</button>
      <button class="iconbtn" type="button" @click="doLogout"><span>⎋</span>退出登录</button>
    </footer>

    <!-- 页脚备案：工信部要求游戏页展示，点击跳官方查询站 -->
    <footer class="lobby__foot">
      <a
        class="beian"
        href="https://beian.miit.gov.cn/"
        target="_blank"
        rel="noopener noreferrer"
      >琼ICP备2026013323号</a>
    </footer>

    <!-- 创建房间：规则设置 -->
    <RuleDialog :open="ruleOpen" @close="ruleOpen = false" @confirm="onRuleConfirm" />

    <!-- 设置：修改昵称 / 修改密码 -->
    <SettingsDialog
      :open="setOpen"
      :nickname="nickname"
      @close="setOpen = false"
      @saved="onSettingsSaved"
    />

    <!-- 关于 / 版本说明 -->
    <div v-if="helpOpen" class="help" @click.self="helpOpen = false">
      <div class="help__dlg panel">
        <header class="help__head">
          <h2>关于</h2>
          <button class="btn btn--sm btn--ghost" type="button" @click="helpOpen = false">✕</button>
        </header>
        <div class="help__body">
          <p><b>海南麻将</b> · 四人联网对局</p>
          <ul>
            <li>规则：海南麻将（底分 / 杠分 / 花分 / 番型倍数可在创建房间时设置）</li>
            <li>座位：东 南 西 北，服务端随机定庄</li>
            <li>战绩：每场结束后可在「战绩查询」里查看每一把的四家得分、杠与花</li>
          </ul>
          <p class="help__tip">手机请横屏以获得最佳体验。</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.lobby {
  /* 高度由 #game-app 的 flex 撑满（100vh 在虚拟横屏下是物理高度，会顶出可视区） */
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 16px;
  background: radial-gradient(ellipse at 50% 20%, var(--felt-600) 0%, var(--felt-800) 55%, var(--felt-900) 100%);
}

/* ---------- 顶栏 ---------- */
.lobby__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.lobby__user {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}
.lobby__avatar {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: linear-gradient(180deg, var(--gold-400), var(--gold-500));
  color: #2a1f06;
  font-weight: 800;
  font-size: 16px;
}
.lobby__nick {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-100);
}
.lobby__topbtns {
  display: flex;
  gap: 8px;
}

/* ---------- 主区 ---------- */
.lobby__main {
  flex: 1 1 auto;
  /* 虚拟横屏下可视高度只有手机的短边（≈360px），卡片一多必然超出，
     必须让主区自己滚，否则底部按钮会被 #game-app 的 overflow: hidden 裁掉 */
  min-height: 0;
  overflow-y: auto;
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  align-content: start;
  max-width: 900px;
  width: 100%;
  margin: 22px auto;
}
.card {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.card h2 {
  margin: 0;
  font-size: 17px;
  color: var(--gold-300);
}
.card__hint {
  margin: 0;
  font-size: 13px;
  color: var(--ink-300);
  line-height: 1.5;
}
.card__error {
  margin: 0;
  font-size: 13px;
  color: #ff8b83;
}
.card--return {
  border-color: var(--gold-500);
  box-shadow: 0 0 20px rgba(215, 178, 90, 0.25);
  grid-column: 1 / -1;
}
.card--return p {
  margin: 0;
  color: var(--ink-200);
  font-size: 14px;
}
.joinrow {
  display: flex;
  gap: 8px;
}
.joinrow__input {
  flex: 1;
  padding: 9px 12px;
  border-radius: 8px;
  border: 1px solid var(--panel-border);
  background: rgba(0, 0, 0, 0.35);
  color: var(--ink-100);
  font-size: 15px;
  letter-spacing: 2px;
  outline: none;
}
.joinrow__input:focus {
  border-color: var(--gold-500);
}

/* ---------- 底栏 ---------- */
.lobby__dock {
  display: flex;
  justify-content: center;
  gap: 10px;
  flex-wrap: wrap;
  max-width: 900px;
  width: 100%;
  margin: 0 auto;
}
.iconbtn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: 10px;
  border: 1px solid var(--panel-border);
  background: rgba(0, 0, 0, 0.3);
  color: var(--ink-200);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.iconbtn:hover {
  border-color: var(--gold-500);
  color: var(--gold-300);
}
.iconbtn span {
  font-size: 15px;
}

/* ---------- 备案 ---------- */
.lobby__foot {
  display: flex;
  justify-content: center;
  padding: 14px 0 4px;
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

/* ---------- 关于 ---------- */
.help {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 12px;
  background: rgba(0, 0, 0, 0.62);
  overflow-y: auto;
}
.help__dlg {
  width: min(460px, 100%);
  /* 同 RuleDialog：不能按物理视口(vh)限高，要按可见框 */
  max-height: min(100%, var(--frame-h, 100vh));
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}
.help__body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 12px 16px 16px;
  font-size: 13px;
  color: var(--ink-200);
  line-height: 1.7;
}
.help__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--panel-border);
}
.help__head h2 {
  margin: 0;
  font-size: 16px;
  color: var(--gold-300);
}
.help__body {
  padding: 12px 16px 16px;
  font-size: 13px;
  color: var(--ink-200);
  line-height: 1.7;
}
.help__body ul {
  margin: 6px 0;
  padding-left: 18px;
}
.help__tip {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--ink-400);
}
</style>
