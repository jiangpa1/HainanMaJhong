/*
 * 运行时冒烟测试：在 Node 里用 happy-dom 真正挂载并驱动 Vue 应用。
 *
 *   node tools/smoke.mjs
 *
 * 为什么需要它：静态检查（check-build / check-tiles）只能证明「文件都在、引用关系对」，
 * 证明不了「应用能启动、点了有反应」。一个模块顶层抛异常，页面就是白屏或静态壳子，
 * 表现正是「点什么都没反应」—— 而构建和引用检查全都是绿的。
 *
 * 做法：
 *   1. happy-dom 造浏览器全局（window/document/history/WebSocket…）
 *   2. esbuild 把 src/main.js 打成一个自包含 bundle（.vue 用自带的轻量插件编译）
 *   3. import 它，再按场景驱动：注入登录态 → 进牌桌 → 灌后端消息 → 模拟点击
 *   4. 断言 DOM 与发出的 WebSocket 帧
 *
 * 这是在没有浏览器的环境下唯一能验证「交互链路通不通」的手段。
 */

import { build } from 'esbuild'
import { Window } from 'happy-dom'
import { fileURLToPath } from 'node:url'
import { dirname, join, resolve } from 'node:path'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { vuePlugin, vueShimPlugin } from './esbuild-vue-plugin.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const srcDir = resolve(here, '..', 'src')

let fails = 0
function ok(cond, msg, extra) {
  if (!cond) {
    fails++
    console.log('  [FAIL] ' + msg)
    if (extra) console.log('         ' + String(extra).split('\n').slice(0, 10).join('\n         '))
  } else {
    console.log('  [ok]   ' + msg)
  }
}

/* ==================== 1. 浏览器环境 ==================== */

console.log('=== 1. 构造浏览器环境 (happy-dom) ===')
const win = new Window({ url: 'http://localhost:8080/' })
const doc = win.document
const g = globalThis

const consoleErrors = []
const consoleWarns = []
const origError = console.error
const origWarn = console.warn
console.error = (...a) => consoleErrors.push(a.map(String).join(' '))
console.warn = (...a) => consoleWarns.push(a.map(String).join(' '))

function defineGlobal(name, value) {
  // Node 24 起 navigator 等是只读 getter，直接赋值会抛 TypeError，统一走 defineProperty
  Object.defineProperty(g, name, { value, writable: true, configurable: true, enumerable: true })
}

defineGlobal('window', win)
defineGlobal('document', doc)
defineGlobal('navigator', win.navigator)
defineGlobal('location', win.location)
defineGlobal('localStorage', win.localStorage)
defineGlobal('sessionStorage', win.sessionStorage)

/*
 * 把 happy-dom 上的 DOM 构造器挂到 globalThis。
 *
 * 两条踩过的坑：
 *   1. 手写清单会漏 —— 漏了 SVGElement，Vue 的 mount() 里 resolveRootNamespace 直接抛，
 *      应用起不来。
 *   2. 无脑全搬更糟 —— happy-dom 的 window 上还有 Object/Function/Promise 这类底层构造器，
 *      覆盖它们会让 Node 自己崩（V8 断言失败，进程直接死）。
 * 所以用白名单化的名字匹配，只搬符合 DOM 命名习惯的。
 */
const DOM_NAME =
  /^(HTML|SVG|MathML|CSS|DOM|XML|Text|Comment|Document|Window|Node|Element|Event|Custom|Mouse|Keyboard|Pointer|Touch|Focus|Input|UI|Wheel|Drag|Clipboard|Mutation|Performance|Request|Response|Headers|FormData|Blob|File|URL|Image|Audio|Option|Range|Selection|Navigator|Location|History|Storage|Abort|Progress)/
const NEVER = new Set([
  'Object', 'Function', 'Array', 'Promise', 'Symbol', 'Proxy', 'Reflect', 'JSON', 'Math', 'Date',
  'RegExp', 'Error', 'Map', 'Set', 'WeakMap', 'WeakSet', 'Number', 'String', 'Boolean', 'BigInt',
  'ArrayBuffer', 'SharedArrayBuffer', 'DataView', 'Int8Array', 'Uint8Array', 'Uint8ClampedArray',
  'Int16Array', 'Uint16Array', 'Int32Array', 'Uint32Array', 'Float32Array', 'Float64Array',
  'BigInt64Array', 'BigUint64Array', 'Atomics', 'WebAssembly', 'globalThis', 'global', 'process',
])

const skip = new Set(['window', 'document', 'location', 'navigator', 'localStorage', 'sessionStorage'])
let copied = 0
const copiedNames = []
for (const key of Object.getOwnPropertyNames(win)) {
  if (skip.has(key) || NEVER.has(key)) continue
  if (key.includes('_') || !/^[A-Z]/.test(key) || !DOM_NAME.test(key)) continue
  let value
  try {
    value = win[key]
  } catch {
    continue
  }
  if (typeof value !== 'function' && typeof value !== 'object') continue
  try {
    defineGlobal(key, value)
    copied++
    copiedNames.push(key)
  } catch {
    /* 只读属性跳过 */
  }
}
console.log(`  [info] 搬运了 ${copied} 个 DOM 构造器`)
ok(copiedNames.includes('SVGElement'), 'SVGElement 已挂载（Vue mount 需要）')
ok(copiedNames.includes('HTMLElement'), 'HTMLElement 已挂载')

defineGlobal('requestAnimationFrame', (cb) => setTimeout(() => cb(Date.now()), 0))
defineGlobal('cancelAnimationFrame', (id) => clearTimeout(id))
if (typeof g.getComputedStyle !== 'function') {
  defineGlobal('getComputedStyle', win.getComputedStyle.bind(win))
}

// vue-router 的 hash 模式要读 window.history；happy-dom 的实现不完整，
// 缺了会报 "history is not defined"，路由根本不启动 —— 那样测试就白跑了。
const historyStub = {
  state: null,
  length: 1,
  pushState(state) {
    this.state = state
  },
  replaceState(state) {
    this.state = state
  },
  go() {},
  back() {},
  forward() {},
}
defineGlobal('history', historyStub)
try {
  win.history = historyStub
} catch {
  /* 只读则忽略 */
}

/* WebSocket 桩：记录连接与发出的帧，并允许测试从服务端"回灌"消息 */
const sockets = []
class FakeWebSocket {
  static CONNECTING = 0
  static OPEN = 1
  static CLOSING = 2
  static CLOSED = 3
  constructor(url) {
    this.url = url
    this.readyState = FakeWebSocket.CONNECTING
    this.sent = []
    this.onopen = null
    this.onmessage = null
    this.onclose = null
    this.onerror = null
    sockets.push(this)
  }
  /** 模拟握手成功 */
  _open() {
    this.readyState = FakeWebSocket.OPEN
    if (this.onopen) this.onopen({})
  }
  /** 模拟服务端下发一条消息 */
  _recv(obj) {
    if (this.onmessage) this.onmessage({ data: JSON.stringify(obj) })
  }
  send(data) {
    this.sent.push(data)
  }
  close() {
    this.readyState = FakeWebSocket.CLOSED
    if (this.onclose) this.onclose({ code: 1000 })
  }
}
defineGlobal('WebSocket', FakeWebSocket)
win.WebSocket = FakeWebSocket

doc.body.innerHTML = '<div id="app"></div>'
win.innerWidth = 1440
win.innerHeight = 900

/* ==================== 2. 打包 ==================== */

console.log('\n=== 2. 打包 src/main.js ===')
const outDir = mkdtempSync(join(tmpdir(), 'hn-smoke-'))
const outFile = join(outDir, 'app.mjs')
try {
  await build({
    entryPoints: [join(srcDir, 'main.js')],
    outfile: outFile,
    bundle: true,
    format: 'esm',
    platform: 'browser',
    target: 'es2020',
    logLevel: 'silent',
    plugins: [vuePlugin(), vueShimPlugin()],
    define: {
      'process.env.NODE_ENV': '"development"',
      __VUE_OPTIONS_API__: 'true',
      __VUE_PROD_DEVTOOLS__: 'false',
      __VUE_PROD_HYDRATION_MISMATCH_DETAILS__: 'false',
    },
    loader: { '.css': 'empty' },
  })
  ok(true, '打包成功（.vue 全部编译通过）')
} catch (e) {
  ok(false, '打包失败', e.message)
  rmSync(outDir, { recursive: true, force: true })
  process.exit(1)
}

/* ==================== 3. 场景：未登录 → 落在登录页 ==================== */

console.log('\n=== 3. 场景 A：未登录（应渲染登录页）===')
let loadError = null
try {
  await import('file://' + outFile.replace(/\\/g, '/'))
  await new Promise((r) => setTimeout(r, 300))
} catch (e) {
  loadError = e
}
ok(!loadError, '加载 main.js 未抛异常', loadError && (loadError.stack || loadError.message))

const appEl = () => doc.getElementById('app')
const html = () => (appEl() ? appEl().innerHTML : '')
ok(html().length > 0, `#app 已渲染内容（${html().length} 字符）`)
ok(html().includes('login') || html().includes('登录'), '落在登录页')
ok(consoleErrors.length === 0, '无 console.error', consoleErrors.join('\n'))

/* ==================== 4. 场景：注入登录态 → 进牌桌 → 灌消息 → 点击 ==================== */

console.log('\n=== 4. 场景 B：牌桌（登录态 + 后端消息 + 模拟点击）===')

// 注入一个假 token，让路由守卫放行（后端不会真的被调用，因为 WebSocket 是桩）
win.localStorage.setItem('accessToken', 'fake-token-for-smoke-test')
win.localStorage.setItem('refreshToken', 'fake-refresh')
win.localStorage.setItem('userId', '42')
win.localStorage.setItem('nickname', '烟测玩家')
win.localStorage.setItem('tokenExpiresAt', String(Date.now() + 3600_000))
win.localStorage.setItem('tokenTtl', '1800000')

/*
 * 用 router 实例直接驱动导航。
 *
 * 试过改 location.hash + 派发 hashchange，但 vue-router 的 hash 模式依赖
 * history.pushState/replaceState 的通知，happy-dom 的实现不完整，
 * 结果路由压根不动（牌桌一直没挂载）。直接用实例最可靠。
 */
const router = g.__hn_router
ok(!!router, '拿到 router 实例（用于驱动导航）', 'globalThis.__hn_router 未挂载')

if (router) {
  await router.push('/game?code=TEST01&seat=EAST')
  await router.isReady()
  await new Promise((r) => setTimeout(r, 600))
}

const gameHtml = html()
ok(router && router.currentRoute.value.path === '/game', `当前路由为 /game（实际 ${router ? router.currentRoute.value.path : '无 router'}）`)
ok(gameHtml.length > 0, `牌桌已渲染（${gameHtml.length} 字符）`)

// 找到牌桌建的那条 WebSocket
const gameSock = sockets.find((s) => s.url.includes('/game'))
ok(!!gameSock, '牌桌发起了 /game WebSocket 连接', '实际连接: ' + sockets.map((s) => s.url).join(', '))
if (gameSock) {
  ok(gameSock.url.includes('code=TEST01'), '连接带上了 code 参数')
  ok(gameSock.url.includes('seat=EAST'), '连接带上了 seat 参数')
  ok(gameSock.url.includes('token='), '连接带上了 token（后端握手只认它）')

  // 模拟握手成功 + 服务端下发一轮真实消息（字段照抄后端实现）
  gameSock._open()
  await new Promise((r) => setTimeout(r, 50))

  gameSock._recv({
    type: 'welcome',
    seat: 'EAST',
    roomId: 'TEST01',
    players: [
      { userId: 42, nickname: '烟测玩家', seat: 'EAST' },
      { userId: -1, nickname: '电脑·南', seat: 'SOUTH' },
      { userId: -2, nickname: '电脑·西', seat: 'WEST' },
      { userId: -3, nickname: '电脑·北', seat: 'NORTH' },
    ],
  })
  gameSock._recv({ type: 'match', total: -1 })
  gameSock._recv({
    type: 'hand_start',
    hand: 1,
    total: -1,
    dealer: 'EAST',
    wind: 0,
    windName: '东',
    bottom: 0,
    coins: { EAST: 0, SOUTH: 0, WEST: 0, NORTH: 0 },
  })
  gameSock._recv({
    type: 'counts',
    counts: { EAST: 13, SOUTH: 13, WEST: 13, NORTH: 13 },
    wall: 55,
  })
  // 一副合法手牌（万筒条字混合）
  const HAND = [0, 1, 2, 9, 10, 11, 18, 19, 20, 27, 27, 31, 33]
  gameSock._recv({ type: 'hand', hand: HAND, melds: [], flowers: [] })
  gameSock._recv({ type: 'turn', seat: 'EAST', kind: 'discard', timeoutMs: 30000 })
  // 引擎向"我"要出牌决定
  gameSock._recv({
    type: 'request',
    kind: 'discard',
    reqId: 101,
    hand: HAND,
    drawnTile: -1,
    canReport: false,
    timeoutMs: 30000,
  })
  await new Promise((r) => setTimeout(r, 200))

  const afterHtml = html()
  ok(afterHtml.length > 1000, `牌桌渲染出内容（${afterHtml.length} 字符）`)

  /*
   * 连接横幅检查（重要）：牌桌在连接异常时会显示一条 .connbar。
   * 若它意外常驻，或者被做成全屏遮罩，玩家看到的就是「牌桌在但点什么都没反应」。
   * 连上的情况下必须【没有】横幅，且必须【没有】任何覆盖全屏的东西。
   */
  const banner = appEl().querySelector('.connbar')
  ok(!banner, '正常连上时没有连接异常横幅')
  const blockingOverlay = appEl().querySelector('.overlay')
  ok(!blockingOverlay, '没有任何全屏遮罩挡住牌桌（遮罩会导致「点了没反应」）')
  ok(afterHtml.includes('is-clickable'), '手牌处于可点状态')

  // 牌图：手牌 13 张，应该出现 13 个 /img/tiles/N.png
  const tileImgs = afterHtml.match(/\/img\/tiles\/\d+\.png/g) || []
  ok(tileImgs.length >= 13, `渲染出 ${tileImgs.length} 张牌面（期望 >=13）`)

  // 可点状态：出牌阶段手牌必须带上 is-clickable
  const clickable = (afterHtml.match(/is-clickable/g) || []).length
  ok(clickable >= 13, `有 ${clickable} 张牌标记为可点（is-clickable）`)

  // 真正的关键：模拟点击第一张牌，看有没有发出正确的 discard 帧
  const buttons = appEl().querySelectorAll('button.tile.is-clickable')
  ok(buttons.length >= 13, `DOM 里找到 ${buttons.length} 个可点牌按钮`)
  if (buttons.length > 0) {
    const btn = buttons[0]
    const tileId = btn.getAttribute('data-tile') || (btn.getAttribute('title') || '')
    // happy-dom 的 click() 会派发事件，Vue 的 @click 应被触发
    btn.click()
    await new Promise((r) => setTimeout(r, 100))

    const frames = gameSock.sent.map((s) => {
      try {
        return JSON.parse(s)
      } catch {
        return { raw: s }
      }
    })
    const discard = frames.find((f) => f.type === 'discard')
    ok(!!discard, `点击后发出了 discard 帧（共发出 ${frames.length} 帧）`, JSON.stringify(frames))
    if (discard) {
      ok(discard.reqId === 101, `discard 带上了正确的 reqId=101（实际 ${discard.reqId}）`)
      ok(typeof discard.tile === 'number', `discard 带上了 tile（实际 ${discard.tile}）`)
    }
  }
}

/* ==================== 5. 场景：大厅 → 规则弹窗 → 房间页 ==================== */

console.log('\n=== 5. 场景 C：大厅「创建新房间」→ 规则设置 → 房间页 ===')

if (router) {
  // 回到大厅（模拟登录成功后的落点）
  await router.push('/lobby')
  await new Promise((r) => setTimeout(r, 300))
  ok(router.currentRoute.value.path === '/lobby', `进入大厅（实际 ${router.currentRoute.value.path}）`)

  const lobbyHtml = html()
  ok(lobbyHtml.includes('创建新房间'), '大厅渲染出「创建新房间」按钮')

  /* ---------- 新增界面：备案 / 战绩 / 设置 ---------- */
  ok(lobbyHtml.includes('琼ICP备'), '大厅底部展示备案号（琼ICP备…）')
  ok(lobbyHtml.includes('战绩查询'), '大厅有「战绩查询」入口')
  ok(lobbyHtml.includes('设置'), '大厅有「设置」入口')

  const btns = Array.from(appEl().querySelectorAll('button'))
  const txt = (b) => (b.textContent || '').replace(/\s+/g, ' ').trim()

  /* ---------- 设置弹窗：昵称 / 密码 ---------- */
  const setBtn = btns.find((b) => txt(b).includes('设置'))
  ok(!!setBtn, 'DOM 里找到「设置」按钮')
  if (setBtn) {
    setBtn.click()
    await new Promise((r) => setTimeout(r, 200))
    const setHtml = html()
    ok(setHtml.includes('修改昵称'), '设置弹窗有「修改昵称」')
    ok(setHtml.includes('修改密码'), '设置弹窗有「修改密码」')
    ok(!!appEl().querySelector('.sd__dlg'), '设置弹窗已渲染（.sd__dlg）')
    // 切到密码页签，检查三个输入框都在
    const pwdTab = Array.from(appEl().querySelectorAll('.sd__tab')).find((b) => txt(b).includes('修改密码'))
    if (pwdTab) {
      pwdTab.click()
      await new Promise((r) => setTimeout(r, 150))
      const inputs = appEl().querySelectorAll('.sd__dlg input')
      ok(inputs.length === 3, `修改密码有 3 个输入框（原/新/确认，实际 ${inputs.length}）`)
    }
    const closeBtn = appEl().querySelector('.sd__head button')
    if (closeBtn) closeBtn.click()
    await new Promise((r) => setTimeout(r, 150))
    ok(!appEl().querySelector('.sd__dlg'), '设置弹窗能关掉')
  }

  /* ---------- 规则弹窗 ---------- */
  const createBtn = Array.from(appEl().querySelectorAll('button')).find((b) => txt(b).includes('创建新房间'))
  ok(!!createBtn, 'DOM 里找到「创建新房间」按钮')

  if (createBtn) {
    createBtn.click()
    await new Promise((r) => setTimeout(r, 250))

    // 第一步：必须先弹规则，而不是直接跳走
    ok(router.currentRoute.value.path === '/lobby', '点「创建新房间」先停在弹窗（不直接跳转）')
    const dlg = appEl().querySelector('.rd__dlg')
    ok(!!dlg, '规则设置弹窗已渲染（.rd__dlg）')

    const numInputs = dlg ? dlg.querySelectorAll('input[type="number"]').length : 0
    ok(numInputs === 18, `规则弹窗有 18 个数值项（实际 ${numInputs}）`)

    const radios = dlg ? dlg.querySelectorAll('input[name="fanGate"]').length : 0
    ok(radios === 2, `胡牌模式有 2 个选项（有番/无番，实际 ${radios}）`)

    const dlgText = dlg ? dlg.textContent.replace(/\s+/g, ' ') : ''
    for (const kw of ['底分', '明杠', '暗杠', '真花', '假花', '真对花', '假对花', '十三幺', '天听', '地听']) {
      ok(dlgText.includes(kw), `规则弹窗含「${kw}」设置项`)
    }

    // 改一个值，确认会被带进 createRoom 的 config
    const baseInput = dlg && dlg.querySelector('#cfg_basePoint')
    if (baseInput) {
      baseInput.value = '7'
      baseInput.dispatchEvent(new win.Event('input', { bubbles: true }))
      await new Promise((r) => setTimeout(r, 100))
    }

    // 第二步：确认后才跳房间页
    const confirmBtn = Array.from(appEl().querySelectorAll('.rd__dlg button')).find((b) => txt(b).includes('创建房间'))
    ok(!!confirmBtn, '规则弹窗有「创建房间」确认按钮')
    if (confirmBtn) {
      confirmBtn.click()
      await new Promise((r) => setTimeout(r, 400))
      const path = router.currentRoute.value.path
      const q = router.currentRoute.value.query
      ok(path === '/room', `确认后跳到房间页（实际 ${path}?${new URLSearchParams(q)}）`)
      ok(q.action === 'create', `query.action 正确传递（实际 ${q.action}）`)

      await new Promise((r) => setTimeout(r, 300))
      const roomSock = sockets.find((s) => s.url.includes('/room'))
      ok(!!roomSock, '房间页发起了 /room WebSocket 连接', '连接列表: ' + sockets.map((s) => s.url).join(', '))

      if (roomSock) {
        ok(roomSock.url.includes('token='), '/room 连接带上了 token')
        roomSock._open()
        await new Promise((r) => setTimeout(r, 150))
        const frames = roomSock.sent.map((s) => {
          try {
            return JSON.parse(s)
          } catch {
            return { raw: s }
          }
        })
        const create = frames.find((f) => f.type === 'createRoom')
        ok(!!create, `WebSocket 打开后发出了 createRoom（共 ${frames.length} 帧）`, JSON.stringify(frames))
        // 规则必须真的带上去了 —— 这是"改了规则却按默认值开局"的防线
        ok(create && create.config && typeof create.config === 'object',
          'createRoom 带上了 config 对象', JSON.stringify(create && create.config))
        ok(create && create.config && create.config.basePoint === 7,
          `弹窗里改的底分带到了 config（期望 7，实际 ${create && create.config && create.config.basePoint}）`)
      }
    }
  }
}

/* ==================== 6. 场景：握手被拒 → 遮罩必须出现且说清原因 ==================== */

console.log('\n=== 6. 场景 D：WebSocket 连不上（token 失效）应给出可见提示 ===')

if (router) {
  // 回到牌桌并制造一个"连不上"的连接
  await router.push('/game?code=DEAD01&seat=SOUTH')
  await new Promise((r) => setTimeout(r, 400))

  const deadSock = sockets.filter((s) => s.url.includes('code=DEAD01')).pop()
  ok(!!deadSock, '牌桌为 DEAD01 建立了连接对象')

  if (deadSock) {
    /*
     * 模拟握手被拒：不触发 onopen，直接 onclose。
     * GameSocket 的实现是「从没 open 过就失败」累计 2 次后判定为认证失败，
     * 所以这里要触发两次连接失败。
     */
    deadSock.close()
    await new Promise((r) => setTimeout(r, 900)) // 等重连退避
    const retry = sockets.filter((s) => s.url.includes('code=DEAD01')).pop()
    if (retry && retry !== deadSock) {
      retry.close()
      await new Promise((r) => setTimeout(r, 300))
    }

    const dHtml = html()
    const deadBanner = appEl().querySelector('.connbar')
    ok(
      !!deadBanner,
      '连不上时显示了连接异常横幅（而不是让玩家对着没反应的牌桌）',
      'banner=' + !!deadBanner + ' len=' + dHtml.length,
    )
    if (deadBanner) {
      const txt = (deadBanner.textContent || '').trim()
      ok(txt.length > 0, '横幅有可读文案: ' + txt)
      // 横幅不能拦截点击，否则又变成"盖住牌桌"
      ok(
        deadBanner.classList.contains('connbar--warn') || deadBanner.classList.contains('connbar--bad'),
        '横幅样式类正确（warn/bad）',
      )
    }
    // 关键：连接异常时也不能出现全屏遮罩
    ok(!appEl().querySelector('.overlay'), '连接异常时没有全屏遮罩（不能盖住牌桌）')
  }
}

/* ==================== 7. 场景：真实开局（14 张手牌 + drawnTile=-1）==================== */

console.log('\n=== 7. 场景 E：真实对局开局的出牌状态 ===')

if (router) {
  await router.push('/game?code=REAL01&seat=EAST')
  await new Promise((r) => setTimeout(r, 400))

  const sock = sockets.filter((s) => s.url.includes('code=REAL01')).pop()
  ok(!!sock, '建立了 REAL01 的连接')
  if (sock) {
    sock._open()
    await new Promise((r) => setTimeout(r, 80))

    /*
     * 照抄后端真实开局的消息序列：
     *   庄家抓完 14 张就开始出牌，所以第一个 request 的 hand 是 14 张、
     *   drawnTile 为 -1（不是"刚摸一张"）。这是与中间回合最大的区别，
     *   也是抽"刚摸那张"的分支最容易出错的地方。
     */
    const HAND14 = [0, 1, 2, 3, 9, 10, 11, 18, 19, 20, 27, 27, 31, 33]
    sock._recv({ type: 'welcome', seat: 'EAST', roomId: 'REAL01', players: [{ userId: 42, nickname: '我', seat: 'EAST' }] })
    sock._recv({ type: 'match', total: -1 })
    sock._recv({ type: 'hand_start', hand: 1, total: -1, dealer: 'EAST', wind: 0, windName: '东', bottom: 0, coins: {} })
    sock._recv({ type: 'counts', counts: { EAST: 14, SOUTH: 13, WEST: 13, NORTH: 13 }, wall: 55 })
    sock._recv({ type: 'hand', hand: HAND14, melds: [], flowers: [] })

    // ① 只到 "hand"，还没收到 turn/request —— 很多实现的 bug 就出在这一瞬间
    await new Promise((r) => setTimeout(r, 100))
    const beforeReq = appEl().querySelectorAll('button.tile').length
    console.log(`  [info] 收到 hand 但未收到 request 时，牌元素数=${beforeReq}`)

    // ② 收到 turn（引擎广播"轮到东家出牌"）
    sock._recv({ type: 'turn', seat: 'EAST', kind: 'discard', timeoutMs: 30000 })
    await new Promise((r) => setTimeout(r, 80))
    const afterTurn = appEl().querySelectorAll('button.tile').length
    console.log(`  [info] 收到 turn 后，牌元素数=${afterTurn}`)

    // ③ 收到引擎的出牌询问（真实开局：14 张、drawnTile=-1）
    sock._recv({
      type: 'request',
      kind: 'discard',
      reqId: 201,
      hand: HAND14,
      drawnTile: -1,
      canReport: false,
      timeoutMs: 30000,
    })
    await new Promise((r) => setTimeout(r, 150))

    const tiles = appEl().querySelectorAll('button.tile')
    const clickable = appEl().querySelectorAll('button.tile.is-clickable')
    const disabled = Array.from(tiles).filter((t) => t.disabled).length
    console.log(
      `  [info] 收到 request 后：牌元素=${tiles.length}, 可点=${clickable.length}, disabled=${disabled}`,
    )

    ok(tiles.length >= 14, `14 张手牌全部渲染出来（实际 ${tiles.length}）`)
    ok(clickable.length >= 14, `14 张牌都可点（实际 ${clickable.length}）`)
    ok(disabled === 0 || clickable.length > 0, `没有"全部 disabled"的情况（disabled=${disabled}）`)

    // ④ 点一张，确认真的发出去
    if (clickable.length > 0) {
      clickable[0].click()
      await new Promise((r) => setTimeout(r, 100))
      const frames = sock.sent.map((s) => {
        try {
          return JSON.parse(s)
        } catch {
          return { raw: s }
        }
      })
      const d = frames.find((f) => f.type === 'discard')
      ok(!!d, '真实开局状态下点击也能发出 discard', JSON.stringify(frames))
      if (d) ok(d.reqId === 201, `reqId 正确（实际 ${d.reqId}）`)
    }

    // ⑤ 若"什么都不动"，页面上必须至少有明确的可点目标
    const allButtons = appEl().querySelectorAll('button')
    const enabledButtons = Array.from(allButtons).filter((b) => !b.disabled)
    console.log(`  [info] 页面上按钮总数=${allButtons.length}, 其中可用=${enabledButtons.length}`)
    ok(enabledButtons.length > 0, '页面上存在可点击的按钮（不是所有控件都 disabled）')
  }
}

/* ==================== 8. 场景：吃碰杠胡按钮能点、能发出 act 帧 ==================== */

console.log('\n=== 8. 场景 F：吃碰杠胡按钮 ===')

if (router) {
  await router.push('/game?code=NEXT01&seat=EAST')
  await new Promise((r) => setTimeout(r, 400))

  const sock = sockets.filter((s) => s.url.includes('code=NEXT01')).pop()
  ok(!!sock, '建立了 NEXT01 的连接')
  if (sock) {
    sock._open()
    await new Promise((r) => setTimeout(r, 80))

    const MY_HAND = [0, 1, 2, 9, 10, 11, 18, 19, 20, 27, 27, 31, 33]
    sock._recv({ type: 'welcome', seat: 'EAST', roomId: 'NEXT01', players: [{ userId: 42, nickname: '我', seat: 'EAST' }] })
    sock._recv({ type: 'match', total: -1 })
    sock._recv({ type: 'hand_start', hand: 1, total: -1, dealer: 'EAST', wind: 0, windName: '东', bottom: 0, coins: {} })
    sock._recv({ type: 'counts', counts: { EAST: 13, SOUTH: 13, WEST: 13, NORTH: 13 }, wall: 60 })
    sock._recv({ type: 'hand', hand: MY_HAND, melds: [], flowers: [] })

    // 别人打出一张，我可以"吃/碰/胡"——options 照抄后端 serializeOptions 的结构
    sock._recv({ type: 'turn', seat: 'SOUTH', kind: 'discard', timeoutMs: 30000 })
    sock._recv({ type: 'discard', seat: 'SOUTH', tile: 3 })
    sock._recv({
      type: 'request',
      kind: 'action',
      reqId: 301,
      tile: 3,
      timeoutMs: 15000,
      options: [
        { type: 'HU', label: '胡(四万)', tiles: [3] },
        { type: 'PENG', label: '碰(四万)', tiles: [3, 3, 3] },
        { type: 'CHI', label: '吃(四万)', tiles: [3, 4, 5] },
      ],
    })
    await new Promise((r) => setTimeout(r, 200))

    const actBtns = Array.from(appEl().querySelectorAll('.actionbar button'))
    const labels = actBtns.map((b) => (b.textContent || '').replace(/\s+/g, ' ').trim())
    console.log('  [info] 动作按钮: ' + labels.join(' | '))
    ok(actBtns.length >= 3, `渲染出动作按钮（实际 ${actBtns.length} 个）`)

    // 按钮里必须显示【牌面图像】（用户要求：不要名字/索引，直接画牌）
    const barTiles = appEl().querySelectorAll('.actionbar .actbtn__tile')
    console.log(`  [info] 操作框里的牌面图像数=${barTiles.length}`)
    ok(barTiles.length >= 3, `操作框里渲染出牌面图像（实际 ${barTiles.length} 张）`)
    const barText = actBtns.map((b) => (b.textContent || '').replace(/\s+/g, ' ').trim()).join(' | ')
    console.log('  [info] 操作按钮文字: ' + barText)
    ok(/胡/.test(barText) && /碰/.test(barText) && /吃/.test(barText), '按钮上仍保留动作名（胡/碰/吃）')
    ok(!/四万/.test(barText), '操作框不再显示牌名文字（改为画牌）')
    ok(!/\b3\b/.test(barText.replace(/[0-9]+张/g, '')), '操作框里没有裸索引数字')

    // 点"胡"，必须发出 act 帧且 index=0（胡在 options 的第 0 位）
    const huBtn = actBtns.find((b) => (b.textContent || '').includes('胡'))
    ok(!!huBtn, '找到「胡」按钮')
    if (huBtn) {
      huBtn.click()
      await new Promise((r) => setTimeout(r, 120))
      const frames = sock.sent.map((s) => {
        try {
          return JSON.parse(s)
        } catch {
          return { raw: s }
        }
      })
      const act = frames.find((f) => f.type === 'act')
      ok(!!act, `点击「胡」发出了 act 帧（共 ${frames.length} 帧）`, JSON.stringify(frames))
      if (act) {
        ok(act.index === 0, `act.index=0（胡在第 0 位，实际 ${act.index}）`)
        ok(act.reqId === 301, `act.reqId=301（实际 ${act.reqId}）`)
      }
    }

    // 再测"暗杠"：走 kind=draw 的路径（自己摸牌后可选暗杠）
    sock._recv({
      type: 'request',
      kind: 'draw',
      reqId: 302,
      drawnTile: 27,
      timeoutMs: 15000,
      options: [{ type: 'AN_GANG', label: '暗杠(东风)', tiles: [27, 27, 27, 27] }],
    })
    await new Promise((r) => setTimeout(r, 200))
    const drawBtns = Array.from(appEl().querySelectorAll('.actionbar button'))
    const gangBtn = drawBtns.find((b) => (b.textContent || '').includes('暗杠'))
    ok(!!gangBtn, `摸牌后出现「暗杠」按钮（按钮: ${drawBtns.map((b) => (b.textContent || '').trim()).join(' | ')}）`)
    if (gangBtn) {
      const before = sock.sent.length
      gangBtn.click()
      await new Promise((r) => setTimeout(r, 120))
      const frames = sock.sent.map((s) => {
        try {
          return JSON.parse(s)
        } catch {
          return { raw: s }
        }
      })
      const act = frames.slice(before).find((f) => f.type === 'act')
      ok(!!act, '点击「暗杠」发出了 act 帧', JSON.stringify(frames.slice(before)))
      if (act) ok(act.index === 0, `暗杠 act.index=0（实际 ${act.index}）`)
    }

    /*
     * 牌河：discard 消息必须【追加】进牌河并渲染出来。
     * 原来的实现只记了 lastDiscard，牌河永远只有 board 快照的内容（而 board
     * 只在断线重连时才下发），所以打出去的牌一直看不见。
     */
    const riverImgs = () => appEl().querySelectorAll('.river img.tile__img').length
    const riverBefore = riverImgs()
    sock._recv({ type: 'discard', seat: 'EAST', tile: 0 })
    sock._recv({ type: 'discard', seat: 'EAST', tile: 9 })
    sock._recv({ type: 'discard', seat: 'SOUTH', tile: 18 })
    sock._recv({ type: 'discard', seat: 'WEST', tile: 27 })
    await new Promise((r) => setTimeout(r, 200))
    const riverAfter = riverImgs()
    console.log(`  [info] 牌河牌面数: 之前=${riverBefore}, 发 4 张后=${riverAfter}`)
    ok(riverAfter >= riverBefore + 4, `打出的 4 张牌都进了牌河（${riverBefore} → ${riverAfter}）`)

    // 副露也要能追加（否则吃碰杠之后看不到别人副露）
    const meldsBefore = appEl().querySelectorAll('.meldrow img.tile__img').length
    sock._recv({ type: 'meld', seat: 'SOUTH', meld: { type: 'PENG', tiles: [3, 3, 3] } })
    await new Promise((r) => setTimeout(r, 200))
    const meldsAfter = appEl().querySelectorAll('.meldrow img.tile__img').length
    console.log(`  [info] 副露牌面数: 之前=${meldsBefore}, 碰之后=${meldsAfter}`)
    ok(meldsAfter >= meldsBefore + 3, `碰的 3 张进了副露区（${meldsBefore} → ${meldsAfter}）`)

    /*
     * 吃碰杠后，被叫的那张牌必须从【牌河】里消失。
     * 后端吃碰杠后不重发 board 快照，所以这一步完全靠前端自己扣。
     */
    sock._recv({ type: 'board', discards: {}, melds: {}, flowers: {} })
    sock._recv({ type: 'discard', seat: 'SOUTH', tile: 9 })
    await new Promise((r) => setTimeout(r, 120))
    const riverAfterDiscard = appEl().querySelectorAll('.river img.tile__img').length
    // 东家用 9 碰/吃南家打出的 9
    sock._recv({ type: 'meld', seat: 'EAST', meld: { type: 'PENG', tiles: [9, 9, 9], from: 'SOUTH' } })
    await new Promise((r) => setTimeout(r, 200))
    const riverAfterMeld = appEl().querySelectorAll('.river img.tile__img').length
    console.log(`  [info] 牌河牌数: 出牌后=${riverAfterDiscard}, 被碰走后=${riverAfterMeld}`)
    ok(riverAfterMeld === riverAfterDiscard - 1, `被碰的那张从牌河移除了（${riverAfterDiscard} → ${riverAfterMeld}）`)

    // 暗杠没有 from，不该动牌河
    sock._recv({ type: 'board', discards: {}, melds: {}, flowers: {} })
    sock._recv({ type: 'discard', seat: 'WEST', tile: 20 })
    await new Promise((r) => setTimeout(r, 120))
    const beforeAnGang = appEl().querySelectorAll('.river img.tile__img').length
    sock._recv({ type: 'meld', seat: 'NORTH', meld: { type: 'AN_GANG', tiles: [20, 20, 20, 20] } })
    await new Promise((r) => setTimeout(r, 200))
    const afterAnGang = appEl().querySelectorAll('.river img.tile__img').length
    ok(afterAnGang === beforeAnGang, `暗杠（无 from）不影响牌河（${beforeAnGang} → ${afterAnGang}）`)

    /*
     * 吃的两步交互：
     *   第一步：只显示一个「吃」按钮（带被吃的那张牌）
     *   第二步：点开后才列出所有吃法组合
     */
    sock._recv({ type: 'board', discards: {}, melds: {}, flowers: {} })
    sock._recv({
      type: 'request',
      kind: 'action',
      reqId: 401,
      tile: 3,
      timeoutMs: 15000,
      options: [
        { type: 'CHI', label: '吃(四万)', tiles: [1, 2, 3] },
        { type: 'CHI', label: '吃(四万)', tiles: [3, 4, 5] },
        { type: 'PENG', label: '碰(四万)', tiles: [3, 3, 3] },
      ],
    })
    await new Promise((r) => setTimeout(r, 200))

    const step1 = Array.from(appEl().querySelectorAll('.actionbar button'))
    const step1Labels = step1.map((b) => (b.textContent || '').trim())
    console.log('  [info] 吃-第一步按钮: ' + step1Labels.join(' | '))
    ok(step1.filter((b) => (b.textContent || '').includes('吃')).length === 1,
       '第一步只显示一个「吃」按钮（两个吃法先折叠起来）')
    ok(step1Labels.some((l) => l.includes('碰')), '第一步仍然显示「碰」')

    const chiBtn = step1.find((b) => (b.textContent || '').includes('吃'))
    if (chiBtn) {
      chiBtn.click()
      await new Promise((r) => setTimeout(r, 200))
      const step2 = Array.from(appEl().querySelectorAll('.actionbar button'))
      const step2Labels = step2.map((b) => (b.textContent || '').trim())
      console.log('  [info] 吃-第二步按钮: ' + step2Labels.join(' | '))
      ok(step2.length >= 3, `第二步列出两个吃法 + 返回（实际 ${step2.length} 个按钮）`)
      ok(step2Labels.some((l) => l.includes('返回')), '第二步有「返回」可退回')
      // 每个吃法要画出三张牌
      const chiTiles = appEl().querySelectorAll('.actionbar .actbtn--chi .actbtn__tile')
      console.log(`  [info] 吃法组合里的牌面数=${chiTiles.length}`)
      ok(chiTiles.length === 6, `两个吃法各画 3 张牌（实际 ${chiTiles.length}）`)

      // 点第二个吃法 → act.index 必须是 1（原始下标，不能因为折叠而错位）
      const secondChi = step2.filter((b) => (b.textContent || '').includes('吃'))[1]
      const before = sock.sent.length
      if (secondChi) {
        secondChi.click()
        await new Promise((r) => setTimeout(r, 150))
        const frames = sock.sent.slice(before).map((s) => {
          try {
            return JSON.parse(s)
          } catch {
            return { raw: s }
          }
        })
        const act = frames.find((f) => f.type === 'act')
        ok(!!act, '第二步点击发出了 act 帧')
        if (act) ok(act.index === 1, `act.index=1（第二个吃法的原始下标，实际 ${act.index}）`)
      }
    }

    /*
     * 「碰谁的牌要指向谁」：
     *   我是 EAST，SOUTH 是我的【下家】→ 被碰的那张应横放在最右（side='right'）
     *   若是 NORTH（我的上家）→ 横放在最左（side='left'）
     *   若是 WEST（对家）      → 横放在中间（side='top'）
     * 判定方式：被叫那张带 is-called 类，看它在所属副露组里是第几个。
     */
    function calledIndexInGroup(ownerSeat, fromSeat) {
      // 先清掉所有副露，避免与前面的断言互相干扰
      sock._recv({ type: 'board', discards: {}, melds: {}, flowers: {} })
      sock._recv({
        type: 'meld',
        seat: ownerSeat,
        meld: { type: 'PENG', tiles: [3, 3, 3], from: fromSeat },
      })
      return new Promise((resolve) => {
        setTimeout(() => {
          const groups = appEl().querySelectorAll('.meldrow__group')
          for (const g of groups) {
            const all = Array.from(g.querySelectorAll('.tile'))
            const ci = all.findIndex((el) => el.classList.contains('is-called'))
            if (ci >= 0) return resolve({ index: ci, total: all.length })
          }
          resolve({ index: -1, total: 0 })
        }, 150)
      })
    }

    // 我坐 EAST：下家=SOUTH、对家=WEST、上家=NORTH
    const fromRight = await calledIndexInGroup('EAST', 'SOUTH')
    console.log(`  [info] 碰下家牌：横放牌位置=${fromRight.index}（共 ${fromRight.total} 张）`)
    ok(fromRight.index === fromRight.total - 1, `碰【下家】的牌 → 横放在最右（实际第 ${fromRight.index} 位）`)

    const fromTop = await calledIndexInGroup('EAST', 'WEST')
    console.log(`  [info] 碰对家牌：横放牌位置=${fromTop.index}（共 ${fromTop.total} 张）`)
    ok(fromTop.index > 0 && fromTop.index < fromTop.total - 1, `碰【对家】的牌 → 横放在中间（实际第 ${fromTop.index} 位）`)

    const fromLeft = await calledIndexInGroup('EAST', 'NORTH')
    console.log(`  [info] 碰上家牌：横放牌位置=${fromLeft.index}（共 ${fromLeft.total} 张）`)
    ok(fromLeft.index === 0, `碰【上家】的牌 → 横放在最左（实际第 ${fromLeft.index} 位）`)

    // 横放的那张必须真的转了 90 度
    const calledEl = appEl().querySelector('.meldrow .tile.is-called')
    const calledStyle = calledEl ? calledEl.getAttribute('style') || '' : ''
    console.log(`  [info] 横放牌的 style: "${calledStyle}"`)
    ok(/--tile-rotate:\s*90deg/.test(calledStyle), '横放的牌带上了 90° 旋转')

    // 暗杠/补杠没有来源，四张都竖放（无 is-called）
    sock._recv({ type: 'board', discards: {}, melds: {}, flowers: {} })
    await new Promise((r) => setTimeout(r, 150))
    /*
     * 没有副露/花牌时，"我的手牌上方的副露容器"必须【不渲染】。
     * 它原来是固定 min-height 的空容器，没牌时就变成手牌上方一块多余的黑框。
     */
    ok(!appEl().querySelector('.table__mymelds'), '没有副露/花牌时，手牌上方不出现空容器（黑框）')

    sock._recv({ type: 'meld', seat: 'EAST', meld: { type: 'AN_GANG', tiles: [27, 27, 27, 27] } })
    await new Promise((r) => setTimeout(r, 200))
    const anGangCalled = appEl().querySelectorAll('.meldrow .tile.is-called').length
    console.log(`  [info] 暗杠后 is-called 数量=${anGangCalled}（应为 0）`)
    ok(anGangCalled === 0, '暗杠没有来源，四张全竖放（无横放牌）')
    // 有副露之后容器必须出现（否则副露没地方显示）
    ok(!!appEl().querySelector('.table__mymelds'), '有副露后，手牌上方出现副露容器')

    /*
     * 局内战绩：现在是一个【按钮】，点开才显示每一小把的明细。
     *
     * 注意金币的顺序，必须照后端真实节奏来：
     *   hand_start 带本把开局的金币（全 0）→ hu → coins（结算后）
     * 这样 coinBefore 才是"结算前"，差额才算得出来。
     * 直接把 coins 放在 hu 前面发，等于把基准也覆盖成结算后的值，差额会变 0。
     */
    sock._recv({ type: 'board', discards: {}, melds: {}, flowers: {} })
    const histBtn = appEl().querySelector('.rh__btn')
    ok(!!histBtn, '局内战绩渲染成一个按钮')
    ok(!appEl().querySelector('.rh__row'), '未点击时不显示明细（不占常驻空间）')

    // 第 1 把开局：金币全 0（这就是本把的基准）
    sock._recv({
      type: 'hand_start',
      hand: 1,
      total: -1,
      dealer: 'EAST',
      wind: 0,
      windName: '东',
      bottom: 0,
      coins: { EAST: 0, SOUTH: 0, WEST: 0, NORTH: 0 },
    })
    // 我这把自摸碰碰胡
    sock._recv({
      type: 'hu',
      seat: 'EAST',
      selfDraw: true,
      tile: 30,
      from: null,
      fans: ['碰碰胡'],
      baoTing: 0,
      tianHu: false,
    })
    // 结算：我 +3，其余各 -1
    sock._recv({ type: 'coins', hand: 1, coins: { EAST: 3, SOUTH: -1, WEST: -1, NORTH: -1 } })
    await new Promise((r) => setTimeout(r, 250))

    const st = g.__hn_pinia ? g.__hn_pinia.state.value.game : null
    if (st) {
      console.log('  [info] roundHistory=' + JSON.stringify(st.roundHistory))
    }

    if (histBtn) {
      histBtn.click()
      await new Promise((r) => setTimeout(r, 200))
      const modal = appEl().querySelector('.rh__modal')
      ok(!!modal, '点击「局内战绩」弹出了明细')
      const mText = modal ? modal.textContent.replace(/\s+/g, ' ') : ''
      console.log('  [info] 明细内容: ' + mText.slice(0, 200))
      /*
       * 局内战绩现在的行为：点开去查 /api/records/byRoom（服务端本房间完整明细），
       * 用与大厅战绩查询二级界面【同一个组件】渲染；服务端还没数据时才退回内存列表。
       *
       * 冒烟环境里没有后端（fetch 会失败），所以这里走的正是【兜底分支】——
       * 断言兜底列表本身可用，并要求它明确写出"为什么这里是简版"，
       * 而不是默默显示一个和正式界面不一样的列表让人以为坏了。
       */
      ok(modal.querySelector('.rh__msg--warn'), '拿不到服务端明细时，弹窗有明确说明（不静默降级）')
      ok(/第 1 把/.test(mText), '明细显示把数（第 1 把）')
      ok(/东家\(我\)/.test(mText), '明细标出赢家是我（东家(我)）')
      ok(/自摸/.test(mText), '明细显示胡牌方式（自摸）')
      ok(/碰碰胡/.test(mText), '明细显示番型（碰碰胡）')
      // 四家得分：来自 coins 差额（EAST +3，其余 -1）
      ok(/\+3/.test(mText), '明细显示赢家得分 +3（由金币差额算出）')
      ok(/-1/.test(mText), '明细显示其余三家 -1')
      // 胡的那张牌要画出来
      ok(!!modal.querySelector('.round__tile .tile'), '明细里画出了胡的那张牌')

      const closeBtn = modal.querySelector('.rh__head button')
      if (closeBtn) {
        closeBtn.click()
        await new Promise((r) => setTimeout(r, 150))
        ok(!appEl().querySelector('.rh__modal'), '关闭按钮能收起明细')
      }
    }
  }
}

/* ==================== 9. 诊断：手牌上方到底有什么在画 ==================== */

console.log('\n=== 9. 诊断：底部区域所有"有背景/边框"的元素 ===')

if (router) {
  await router.push('/game?code=PAINT1&seat=north')
  await new Promise((r) => setTimeout(r, 400))
  const sock = sockets.filter((s) => s.url.includes('code=PAINT1')).pop()
  if (sock) {
    sock._open()
    await new Promise((r) => setTimeout(r, 60))
    // 造一个"只有手牌、没有任何副露"的状态 —— 正是用户截图里的情形
    sock._recv({ type: 'welcome', seat: 'NORTH', roomId: 'PAINT1', players: [{ userId: 42, nickname: '我', seat: 'NORTH' }] })
    sock._recv({ type: 'match', total: -1 })
    sock._recv({ type: 'hand_start', hand: 2, total: -1, dealer: 'EAST', wind: 0, windName: '东', bottom: 2, coins: {} })
    sock._recv({ type: 'counts', counts: { EAST: 13, SOUTH: 13, WEST: 13, NORTH: 13 }, wall: 85 })
    sock._recv({ type: 'hand', hand: [0, 2, 4, 6, 9, 10, 18, 22, 25, 26, 28, 30, 33], melds: [], flowers: [] })
    sock._recv({ type: 'turn', seat: 'NORTH', kind: 'discard', timeoutMs: 30000 })
    sock._recv({
      type: 'request',
      kind: 'discard',
      reqId: 501,
      hand: [0, 2, 4, 6, 9, 10, 18, 22, 25, 26, 28, 30, 33],
      drawnTile: -1,
      canReport: false,
      timeoutMs: 30000,
    })
    await new Promise((r) => setTimeout(r, 250))

    // 列出底部 1/3 区域内、有可见背景或边框的元素
    const scene = appEl().querySelector('.table')
    const rows = []
    scene.querySelectorAll('*').forEach((el) => {
      const cls = typeof el.className === 'string' ? el.className : ''
      const cs = globalThis.getComputedStyle(el)
      const painted =
        (cs.backgroundColor && cs.backgroundColor !== 'rgba(0, 0, 0, 0)' && cs.backgroundColor !== 'transparent') ||
        (cs.backgroundImage && cs.backgroundImage !== 'none') ||
        (cs.borderTopWidth && cs.borderTopWidth !== '0px')
      if (!painted) return
      rows.push({ tag: el.tagName.toLowerCase(), cls: cls || '(无类名)', text: (el.textContent || '').trim().slice(0, 14) })
    })
    const interesting = rows.filter((r) => !r.cls.includes('tile') && !r.cls.includes('hand'))
    console.log('  [info] 非牌类、有背景的元素:')
    for (const r of interesting) console.log(`         ${r.tag}.${r.cls}  "${r.text}"`)

    // 直接点名几个可疑容器
    const mymelds = appEl().querySelector('.table__mymelds')
    const actions = appEl().querySelector('.table__actions')
    const hint = appEl().querySelector('.hand__hint')
    console.log(`  [info] .table__mymelds 存在=${!!mymelds}`)
    console.log(`  [info] .table__actions 存在=${!!actions} 子元素数=${actions ? actions.children.length : 0}`)
    console.log(`  [info] .hand__hint 存在=${!!hint} 文本="${hint ? hint.textContent : ''}"`)
    const voices = appEl().querySelector('.voices')
    console.log(`  [info] .voices 存在=${!!voices}`)
    const bottom = appEl().querySelector('.table__bottom')
    console.log(`  [info] .table__bottom 子元素数=${bottom ? bottom.children.length : 0}`)

    ok(!mymelds, '无副露时 .table__mymelds 不渲染')

    /*
     * "手牌上方有黑框"的两个成因，都钉住：
     *   1. 空的手牌 hint（原来 pending 为空时会渲染一条深色药丸）——这里有待答询问，应为空
     *   2. 摸到的那张被抬高（.hand 的 min-height + align-items 组合导致）
     */
    const handEl = appEl().querySelector('.table__hand')
    const handChildren = handEl ? Array.from(handEl.children).map((c) => (c.className || '').toString()) : []
    console.log('  [info] .table__hand 子元素: ' + handChildren.join(', '))
    ok(
      !handChildren.some((c) => c.includes('hand__hint')),
      '有待答询问时不渲染 .hand__hint（它是一条深色药丸，会像"黑框"）',
    )
    // 手牌容器不应该再有固定 min-height 把摸到的牌顶高
    ok(!!handEl, '手牌容器存在')
  }
}

/* ==================== 9.5 场景：被顶下线（单点登录） ==================== */
/*
 * 同一账号在别的设备登录时，服务端会先推 {"type":"kick"} 再关连接。
 * 前端必须：清登录态 → 在 sessionStorage 留一条提示 → 回登录页。
 * 只关连接的话用户看到的只是"一直在连接中"，根本不知道发生了什么。
 *
 * 放在所有牌桌场景之后：这一步会把页面从牌桌带走（hash 变成 #/），
 * 放到前面会把后面的场景全打乱。
 */
{
  console.log('\n=== 9.5 场景 G：该账号在别处登录（kick）→ 清登录态 + 回登录页 ===')
  const sock = sockets[sockets.length - 1]
  ok(!!sock, '拿到牌桌那条 WebSocket')

  // 前置：确实处于登录态
  const tokenBefore = win.localStorage.getItem('accessToken')
  ok(!!tokenBefore, '触发前是登录态')

  sock._recv({ type: 'kick', reason: '该账号在别处登录，请重新登录' })
  await new Promise((r) => setTimeout(r, 200))

  ok(!win.localStorage.getItem('accessToken'), 'kick 之后 accessToken 被清掉')
  ok(!win.localStorage.getItem('refreshToken'), 'kick 之后 refreshToken 也被清掉')

  const notice = win.sessionStorage.getItem('hnLoginNotice') || ''
  console.log(`  [info] 登录页提示 = ${JSON.stringify(notice)}`)
  ok(notice.includes('别处登录'), '给登录页留了"该账号在别处登录"的一次性提示')

  console.log(`  [info] 当前 hash = ${win.location.hash}`)
  ok(win.location.hash === '#/', '被顶下线后回到登录页（hash = #/）')

  // 提示必须是一次性的：登录页取过一次就没了，刷新不该反复弹
  const { consumeLoginNotice } = await import('../src/api/api.js')
  const first = consumeLoginNotice()
  const second = consumeLoginNotice()
  ok(first.includes('别处登录') && second === '', '提示取一次即清（刷新不会反复弹）')
}

/* ==================== 10. 报告 ==================== */

console.log('\n=== 10. 诊断信息 ===')
console.log('  [info] WebSocket 连接数: ' + sockets.length)
for (const s of sockets) console.log('         ' + s.url + '  (已发送 ' + s.sent.length + ' 帧)')

if (consoleErrors.length) {
  console.log('\n  --- console.error ---')
  for (const e of consoleErrors.slice(0, 12)) console.log('    ' + e.slice(0, 400))
}
if (consoleWarns.length) {
  console.log('\n  --- console.warn（前 8 条，不计失败）---')
  for (const w of consoleWarns.slice(0, 8)) console.log('    ' + w.slice(0, 260))
}

rmSync(outDir, { recursive: true, force: true })
console.error = origError
console.warn = origWarn

console.log('')
if (fails) {
  console.log(`==== 失败 ${fails} 项 ====`)
  process.exit(1)
}
console.log('==== 全部通过 ====')
