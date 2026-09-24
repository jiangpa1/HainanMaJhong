import { onMounted, ref } from 'vue'

/*
 * 虚拟横屏统一管理（移植自旧版 static/landscape.js 的 LandscapeManager）。
 *
 * 做什么：
 *   1. 移动端给 <html> 加 virtual-landscape 类，配合 landscape.css 把 #game-app
 *      整体旋转 90°，让竖屏持握也能横屏玩；
 *   2. 算 --scale，把 1280x720 的设计稿等比缩放居中装进当前逻辑视口；
 *   3. 竖屏时用【显式像素】再写一遍 #game-app 的尺寸/旋转（sizeVirtualApp）。
 *
 * 为什么要第 3 步：
 *   光靠 CSS 的 100dvh/100dvw，旧版 iOS Safari 不支持 dvh，会只显示部分画面，
 *   而且刷新也不恢复。显式像素是旧版反复调试出来的兜底，必须保留。
 *
 * 为什么不用 CSS @media 判断要不要旋转：
 *   旋转本身是 CSS @media 做的（物理转回横屏自动撤销，不需要 JS）；
 *   但"是否移动端"必须靠 JS —— 桌面窗口拉窄也会命中 portrait，那时不该转。
 *
 * 模块级单例：整个应用共用一份状态与监听，只初始化一次。
 */

/** 设计稿基准尺寸，与 landscape.css 的 --design-w/--design-h 保持一致。 */
export const DESIGN_W = 1280
export const DESIGN_H = 720

/**
 * 手机竖屏（虚拟横屏）时用的【紧凑设计高度】。
 *
 * 为什么需要它：牌桌是按 1280x720 等比缩放装进"固定框"的，而竖屏手机的固定框
 * 比 16:9 宽得多（真机 ≈ 844x390，即 2.16:1），所以缩放系数是被
 * 【设计高度】卡住的：scale = 框高 / 设计高。
 * 于是 720 这 18% 的横向留白就永远浪费掉了，牌面看着就小。
 *
 * 牌桌本身的内容（四家手牌/副露/牌河/按钮）在竖直方向留了余量 —— 实测原始分布：
 *   顶部信息条 0-40、对家手牌 40-118、（空 114）、中央牌河 232-552、
 *   我的副露 570-614、我的手牌 624-710。
 * 把那 114 的空白和中央区收一收，再把牌河框的高度从 320 收到 290（只改手机，
 * 见 landscape.css 的 --river-h），设计高度压到 590 就能让整体等比放大 22%
 * （相对最初的 720：720/590 ≈ 1.22），**不裁切、不变形**（牌还是方的）。
 *
 * 竖直方向已经排满，再往下压就会撞（对家手牌 ↔ 牌河、牌河 ↔ 我的副露），
 * 所以这个值基本是上限；要继续放大只能重排四家的纵向位置，不是调一个常量的事。
 *
 * ⚠️ 只在【手机 + 竖屏虚拟横屏】时启用，桌面端保持 720 —— 桌面窗口的比例和
 *    手机不同，而且 docs/browser-probe.py 对 1280x720 的桌面布局有逐像素断言。
 * 改这个值时务必跑 docs/mobile-probe.py 看内容有没有被压重叠。
 */
const COMPACT_DESIGN_H = 590

function designHeight(compact) {
  return compact ? COMPACT_DESIGN_H : DESIGN_H
}

/** 缩放上下限：太小看不清，太大在超宽屏上会过分放大。 */
const MIN_SCALE = 0.3
const MAX_SCALE = 1.5

/** 旧版用过的标记位；保留读写，方便将来做"记住横屏偏好"。 */
const STORAGE_KEY = 'landscape-mode'

/* ---------------- 设备判定 ---------------- */

const ua = (typeof navigator !== 'undefined' && navigator.userAgent) || ''
const platform = (typeof navigator !== 'undefined' && navigator.platform) || ''
const maxTouch = (typeof navigator !== 'undefined' && navigator.maxTouchPoints) || 0

const isIOS = /iPhone|iPad|iPod/i.test(ua) || (platform === 'MacIntel' && maxTouch > 1)
const isAndroid = /Android/i.test(ua)

export function isMobileDevice() {
  if (isIOS || isAndroid) return true
  if (/Mobile|Android|Silk|webOS|BlackBerry/i.test(ua)) return true
  // 兜底：有触摸且短边小于 1024 的设备（触屏平板/未知 UA）
  if (typeof window === 'undefined' || !('ontouchstart' in window)) return false
  const scr = window.screen || {}
  const minSide = Math.min(scr.width || 0, scr.height || 0)
  return minSide > 0 && minSide < 1024
}

export const isIOSDevice = () => isIOS

function isPortrait() {
  if (typeof window === 'undefined') return false
  return window.innerHeight > window.innerWidth
}

/** 当前"横屏模式"下的逻辑视口（竖屏时旋转 90°，宽高互换）。 */
function logicalViewport() {
  const vv = window.visualViewport
  const w = Math.round(vv ? vv.width : window.innerWidth)
  const h = Math.round(vv ? vv.height : window.innerHeight)
  if (isPortrait()) return { w: h, h: w }
  return { w, h }
}

/* ---------------- 响应式状态（组件可直接用） ---------------- */

/** 当前缩放系数（写进 --scale 的同一个值） */
const scale = ref(1)
/** 是否处于虚拟横屏（旋转 90°） */
const rotate = ref(0)
/** 逻辑视口尺寸 */
const viewport = ref({ w: DESIGN_W, h: DESIGN_H })
/** 是否移动端 */
const mobile = ref(false)
/** 是否物理竖屏 */
const portrait = ref(false)
/** 是否已开启虚拟横屏 */
const virtualLandscape = ref(false)

/* ---------------- 核心计算 ---------------- */

export function applyScale() {
  const v = logicalViewport()
  const compact = mobile.value && virtualLandscape.value && isPortrait()
  const designH = designHeight(compact)
  let s = Math.min(v.w / DESIGN_W, v.h / designH)
  s = Math.max(MIN_SCALE, Math.min(s, MAX_SCALE))
  const fixed = Number(s.toFixed(4))

  scale.value = fixed
  viewport.value = v
  portrait.value = isPortrait()
  rotate.value = virtualLandscape.value && portrait.value ? 90 : 0

  if (typeof document === 'undefined') return
  // 写两处：:root 让任意元素可用，.scale-layer 上写一份兼容旧版选择器
  const rootStyle = document.documentElement.style
  rootStyle.setProperty('--scale', fixed.toFixed(4))
  // 固定框的高度跟着走：手机上用紧凑高度，桌面/横屏用 720（.scale-layer 和 .table 都读它）
  rootStyle.setProperty('--design-h', designH + 'px')
  /*
   * --frame-w/--frame-h = 【可见框】的逻辑尺寸（旋转后的可视宽高）。
   *
   * 为什么必须由 JS 给：vh/vw 描述的是【物理视口】，不随 transform 变化。
   * 竖屏虚拟横屏时可见框高 = 物理宽(vw 那一侧)，拿 88vh 当上限的弹窗会高出近一倍，
   * 上下都被 #game-app 的 overflow:hidden 切掉（用户实测："规则窗口上界和下界都不在屏幕内"）。
   * 弹窗只要写 max-height: min(100%, var(--frame-h)) 就与朝向无关。
   */
  rootStyle.setProperty('--frame-w', v.w + 'px')
  rootStyle.setProperty('--frame-h', v.h + 'px')
  const layer = document.querySelector('.scale-layer')
  if (layer) layer.style.setProperty('--scale', fixed.toFixed(4))
}

/**
 * 竖屏虚拟横屏时用像素显式给出 #game-app 的尺寸/旋转。
 * 横屏或桌面端清空内联样式，交回 CSS（@media + 100dvw/dvh）处理。
 */
export function sizeVirtualApp() {
  if (typeof document === 'undefined') return
  const app = document.getElementById('game-app')
  if (!app) return

  const shouldRotate = mobile.value && virtualLandscape.value && isPortrait()
  if (shouldRotate) {
    const vv = window.visualViewport
    const w = Math.round(vv ? vv.height : window.innerHeight)
    const h = Math.round(vv ? vv.width : window.innerWidth)
    app.style.left = '50%'
    app.style.top = '50%'
    app.style.width = w + 'px'
    app.style.height = h + 'px'
    setTransform(app, 'translate(-50%, -50%) rotate(90deg)', 'center')
  } else {
    app.style.left = ''
    app.style.top = ''
    app.style.width = ''
    app.style.height = ''
    setTransform(app, '', '')
  }
}

/** 同时写带前缀与不带前缀的 transform；老 WebKit 只认前者的场景下不至于失效。 */
function setTransform(el, value, origin) {
  el.style.transform = value
  el.style.transformOrigin = origin
  try {
    el.style.setProperty('-webkit-transform', value)
    el.style.setProperty('-webkit-transform-origin', origin)
  } catch (e) {
    /* 个别环境（happy-dom / 老内核）不认识带前缀属性，忽略即可 */
  }
}

/** 统一重算：缩放 + 显式尺寸。 */
export function refreshLayout() {
  applyScale()
  sizeVirtualApp()
}

/* ---------------- 全屏 / 方向锁定（可选增强，失败无妨） ---------------- */

export function isFullscreen() {
  if (typeof document === 'undefined') return false
  return !!(
    document.fullscreenElement ||
    document.webkitFullscreenElement ||
    document.mozFullScreenElement ||
    document.msFullscreenElement
  )
}

export function requestFullscreen(el) {
  const node = el || document.documentElement
  const fn =
    node.requestFullscreen ||
    node.webkitRequestFullscreen ||
    node.webkitRequestFullScreen ||
    node.mozRequestFullScreen ||
    node.msRequestFullscreen
  if (!fn) return Promise.reject(new Error('fullscreen unsupported'))
  try {
    return Promise.resolve(fn.call(node))
  } catch (e) {
    return Promise.reject(e)
  }
}

export function exitFullscreen() {
  if (typeof document === 'undefined') return Promise.resolve()
  const fn =
    document.exitFullscreen ||
    document.webkitExitFullscreen ||
    document.webkitCancelFullScreen ||
    document.mozCancelFullScreen ||
    document.msExitFullscreen
  if (!fn) return Promise.resolve()
  try {
    return Promise.resolve(fn.call(document))
  } catch (e) {
    return Promise.resolve()
  }
}

export function lockLandscape() {
  const scr = (typeof screen !== 'undefined' && screen) || {}
  const so = scr.orientation || scr.msOrientation || scr.mozOrientation
  try {
    if (so && typeof so.lock === 'function') return Promise.resolve(so.lock('landscape'))
    if (typeof scr.lockOrientation === 'function') return Promise.resolve(scr.lockOrientation('landscape'))
    if (typeof scr.mozLockOrientation === 'function') return Promise.resolve(scr.mozLockOrientation('landscape'))
  } catch (e) {
    return Promise.reject(e)
  }
  return Promise.reject(new Error('orientation lock unsupported'))
}

export function unlockOrientation() {
  const scr = (typeof screen !== 'undefined' && screen) || {}
  const so = scr.orientation || scr.msOrientation || scr.mozOrientation
  try {
    if (so && typeof so.unlock === 'function') so.unlock()
    if (typeof scr.unlockOrientation === 'function') scr.unlockOrientation()
    if (typeof scr.mozUnlockOrientation === 'function') scr.mozUnlockOrientation()
  } catch (e) {
    /* 忽略 */
  }
}

/* ---------------- 模式开关 ---------------- */

function hasMode() {
  try {
    return localStorage.getItem(STORAGE_KEY) === '1'
  } catch (e) {
    return false
  }
}

function setMode(on) {
  try {
    if (on) localStorage.setItem(STORAGE_KEY, '1')
    else localStorage.removeItem(STORAGE_KEY)
  } catch (e) {
    /* 隐私模式下 localStorage 可能直接抛 */
  }
}

function markRoot(on) {
  virtualLandscape.value = on
  if (typeof document === 'undefined') return
  const root = document.documentElement
  if (!root) return
  if (on) root.classList.add('virtual-landscape')
  else root.classList.remove('virtual-landscape')
}

/**
 * 进入游戏模式（旧版绑在「进入游戏 / 游客登录」按钮上）。
 *
 * ⚠️ 这里刻意【不】申请全屏、也不锁横屏：
 * 旧版在用户手势里先 requestFullscreen 再跳转，部分安卓会卡住不跳转、
 * 新页面也不刷新。旋转靠 CSS 就够，所以只补一次重算。
 */
export function enterGameMode() {
  if (!isMobileDevice()) return
  markRoot(true)
  setMode(true)
  refreshLayout()
  requestAnimationFrame(refreshLayout)
  setTimeout(refreshLayout, 300)
}

/** 退出虚拟横屏（桌面调试/用户主动关闭时用）。 */
export function resetLandscape() {
  markRoot(false)
  unlockOrientation()
  exitFullscreen()
  setMode(false)
  refreshLayout()
  requestAnimationFrame(() => window.dispatchEvent(new Event('resize')))
}

/* ---------------- 初始化（模块级，只跑一次） ---------------- */

let started = false

export function initLandscape() {
  if (started || typeof window === 'undefined') return
  started = true

  mobile.value = isMobileDevice()
  if (mobile.value) {
    // 移动端强制开启虚拟横屏（旧版一致：不需要用户点任何按钮）
    markRoot(true)
  } else {
    markRoot(false)
  }

  window.addEventListener('resize', refreshLayout)
  window.addEventListener('orientationchange', refreshLayout)
  if (window.visualViewport) {
    // scroll 也要听：iOS 地址栏伸缩时先变 visualViewport 再发 resize
    window.visualViewport.addEventListener('resize', refreshLayout)
    window.visualViewport.addEventListener('scroll', refreshLayout)
  }
  window.addEventListener('load', refreshLayout)
  window.addEventListener('fullscreenchange', refreshLayout)
  window.addEventListener('webkitfullscreenchange', refreshLayout)

  refreshLayout()
  // 首次进页面时布局还没稳定（字体/图片/地址栏），多跑几次避免"要刷新才正常"
  requestAnimationFrame(refreshLayout)
  setTimeout(refreshLayout, 250)
  setTimeout(refreshLayout, 800)
}

/**
 * 组件入口：注册初始化并返回共享状态。
 * 状态是模块级单例，多处调用不会重复挂监听。
 */
export function useLandscapeScale() {
  onMounted(initLandscape)
  return {
    scale,
    rotate,
    viewport,
    mobile,
    portrait,
    virtualLandscape,
    remeasure: refreshLayout,
    enterGameMode,
    reset: resetLandscape,
  }
}

/** 非组件场景（比如普通 .js 模块里）也能启动。 */
export const LandscapeManager = {
  init: initLandscape,
  applyScale,
  refreshLayout,
  sizeVirtualApp,
  enterGameMode,
  reset: resetLandscape,
  isMobile: isMobileDevice,
  isIOS: isIOSDevice,
  isPortrait,
  isFullscreen,
  hasMode,
}
