/*
 * 后端调用封装（REST）。
 *
 * 移植自原 static/api.js，逻辑保留，改为 ES module 以便被 Vue 与 socket.js 共用。
 *
 * 四件事：
 *   1. 带 token —— 每个请求自动加 Authorization: Bearer <accessToken>
 *   2. 拆响应 —— 后端统一返回 {code, message, data}，这里把 data 拆出来给调用方
 *   3. 401 处理 —— 用 refreshToken 换新 token 后重试一次；仍失败则清登录态
 *   4. 端点与字段集中在一处 —— 改后端接口只改这个文件
 *
 * 后端约定（见 GlobalExceptionHandler / JwtInterceptor）：
 *   HTTP 状态码恒为 200，业务码在响应体的 code 里：
 *     200 成功 / 400 参数 / 401 未登录 / 403 无权 / 404 不存在 / 500 服务端错误
 *
 * 注意：WebSocket 握手是例外，它返回真实的 401（见 socket.js 的说明）。
 */

const TOKEN_KEY = 'accessToken'
const REFRESH_KEY = 'refreshToken'
const PROFILE_KEY = 'profile'
const EXPIRES_AT_KEY = 'tokenExpiresAt'
const TTL_KEY = 'tokenTtl'

/*
 * 续期策略：
 *   主动 —— accessToken 用到 USE_RATIO 寿命就先换一对新的（避免请求打到一半过期）
 *   被动 —— 万一还是 401，用 refreshToken 换一次再重试一次（只重试一次）
 *   单飞 —— 并发请求只触发一次刷新，其余等同一个 Promise
 *
 * 后端 refresh 会【轮转】两个 token，所以每次都必须把新的一对都存下来，
 * 否则旧 refreshToken 立即可废；并发刷新时第二个用旧值的请求必然失败，这就是必须单飞的原因。
 */
const USE_RATIO = 0.8
let refreshPromise = null

/* ---------------- token ---------------- */

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function getRefreshToken() {
  return localStorage.getItem(REFRESH_KEY) || ''
}

export function setTokens(pair) {
  if (!pair) return
  if (pair.accessToken) localStorage.setItem(TOKEN_KEY, pair.accessToken)
  if (pair.refreshToken) localStorage.setItem(REFRESH_KEY, pair.refreshToken)
  if (typeof pair.expiresIn === 'number' && pair.expiresIn > 0) {
    localStorage.setItem(TTL_KEY, String(pair.expiresIn))
    localStorage.setItem(EXPIRES_AT_KEY, String(Date.now() + pair.expiresIn))
  }
}

export function clearAuth() {
  for (const k of [TOKEN_KEY, REFRESH_KEY, PROFILE_KEY, EXPIRES_AT_KEY, TTL_KEY, 'userId', 'nickname', 'username']) {
    localStorage.removeItem(k)
  }
}

export function isLoggedIn() {
  return !!getToken()
}

function expiresAt() {
  return Number(localStorage.getItem(EXPIRES_AT_KEY) || 0)
}

function ttl() {
  return Number(localStorage.getItem(TTL_KEY) || 0)
}

/** 是否需要提前续期：已用寿命超过 USE_RATIO 阈值。 */
function needsRefresh() {
  const exp = expiresAt()
  const total = ttl()
  if (!exp || !total) return false
  const left = exp - Date.now()
  if (left <= 0) return true
  return left < total * (1 - USE_RATIO)
}

/* ---------------- 底层请求 ---------------- */

async function doRefresh() {
  const rt = getRefreshToken()
  if (!rt) throw new Error('没有 refreshToken，请重新登录')

  const res = await fetch('/api/refresh', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json;charset=UTF-8' },
    body: JSON.stringify({ refreshToken: rt }),
  })
  const body = await res.json().catch(() => null)
  if (!body || body.code !== 200 || !body.data) {
    throw new Error((body && body.message) || '登录已过期，请重新登录')
  }
  setTokens(body.data)
  return body.data
}

/**
 * 发一个请求。
 *
 * @param {string} url
 * @param {object} [opts]
 * @param {string} [opts.method]
 * @param {object} [opts.body]   会被 JSON 序列化
 * @param {boolean} [opts.raw]   true 则返回整个响应体（需要 total/pages 等分页字段时用）
 * @param {boolean} [opts.skipAuth]
 * @returns {Promise<any>}
 */
export async function request(url, opts = {}) {
  if (!opts.skipAuth && needsRefresh() && getRefreshToken()) {
    try {
      await refreshOnce()
    } catch (e) {
      // 主动续期失败不直接报错，交给下面正常请求去触发 401 分支
    }
  }

  const retry = await fetchOnce(url, opts)

  // 被顶下线：不续期、直接退出登录（续期必然失败，白跑一趟）
  if (retry.code === KICK_CODE) {
    handleKicked(retry.message)
    const err = new Error(retry.message || '该账号在别处登录，请重新登录')
    err.code = KICK_CODE
    throw err
  }

  if (retry.code === 401 && !opts.skipAuth && getRefreshToken() && !opts._retried) {
    try {
      await refreshOnce()
    } catch (e) {
      clearAuth()
      redirectToLogin()
      throw new Error('登录已过期，请重新登录')
    }
    return fetchOnce(url, { ...opts, _retried: true }).then(unwrap(opts))
  }

  return unwrap(opts)(retry)
}

function unwrap(opts) {
  return (body) => {
    if (!body) throw new Error('服务端返回空响应')
    if (body.code !== 200) {
      const err = new Error(body.message || `请求失败（${body.code}）`)
      err.code = body.code
      throw err
    }
    return opts.raw ? body : body.data
  }
}

/** 只发一次，返回响应体；不抛业务错误，交给调用方判断（401 需要重试）。 */
async function fetchOnce(url, opts) {
  const headers = { ...(opts.headers || {}) }
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json;charset=UTF-8'
  if (!opts.skipAuth) {
    const t = getToken()
    if (t) headers.Authorization = `Bearer ${t}`
  }

  let res
  try {
    res = await fetch(url, {
      method: opts.method || 'GET',
      headers,
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    })
  } catch (e) {
    throw new Error('网络异常，请检查连接')
  }

  let body = null
  try {
    body = await res.json()
  } catch (e) {
    throw new Error('服务端返回格式错误')
  }
  return body || { code: res.status, message: `HTTP ${res.status}` }
}

/** 单飞：并发调用共用同一个刷新 Promise。 */
function refreshOnce() {
  if (!refreshPromise) {
    refreshPromise = doRefresh().finally(() => {
      refreshPromise = null
    })
  }
  return refreshPromise
}

/**
 * 该账号在别处登录（后端 Result.CODE_KICKED）。
 *
 * 与 401 的区别：401 是"过期了，去续期"，这个续期必然失败，所以直接退出登录。
 */
export const KICK_CODE = 4011

/** 一次性提示：存起来给登录页显示，读完就删（不用 localStorage，刷新后不该再弹） */
const NOTICE_KEY = 'hnLoginNotice'

function takeNotice() {
  try {
    return sessionStorage.getItem(NOTICE_KEY) || ''
  } catch (e) {
    return ''
  }
}

function putNotice(text) {
  try {
    sessionStorage.setItem(NOTICE_KEY, text)
  } catch (e) {
    /* 隐私模式忽略 */
  }
}

/**
 * 被顶下线的统一处理：清登录态 → 留一条提示给登录页 → 回登录页。
 *
 * 三个入口都要走它，保证行为一致：
 *   1. 任何接口返回 4011（见 request）
 *   2. WebSocket 收到 {"type":"kick"}（房间页 / 牌桌）
 *   3. WS 握手被拒后由业务侧决定（store 的 authError）
 */
export function handleKicked(reason) {
  clearAuth()
  putNotice(reason || '该账号在别处登录，请重新登录')
  redirectToLogin()
}

/** 登录页取一次性提示（取完即清，避免反复弹）。 */
export function consumeLoginNotice() {
  const t = takeNotice()
  if (t) {
    try {
      sessionStorage.removeItem(NOTICE_KEY)
    } catch (e) {
      /* 忽略 */
    }
  }
  return t
}

export function redirectToLogin() {
  // 用 hash 路由，登录页是 /#/
  if (!location.hash.startsWith('#/')) {
    location.href = 'index.html'
    return
  }
  location.hash = '#/'
}

/* ---------------- 当前用户 ---------------- */

export function profile() {
  try {
    return JSON.parse(localStorage.getItem(PROFILE_KEY) || 'null')
  } catch (e) {
    return null
  }
}

function setProfile(u) {
  localStorage.setItem(PROFILE_KEY, JSON.stringify(u || {}))
  if (u) {
    localStorage.setItem('userId', u.id == null ? '' : String(u.id))
    if (u.nickname) localStorage.setItem('nickname', u.nickname)
    if (u.username) localStorage.setItem('username', u.username)
  }
  return u
}

export async function me() {
  const u = await request('/api/user/me')
  return setProfile(u)
}

/* ---------------- 认证 ---------------- */

export async function login(username, password) {
  const pair = await request('/api/login', {
    method: 'POST',
    body: { username, password },
    skipAuth: true,
  })
  setTokens(pair)
  // 登录只返回 token 对，不含 userId/nickname，所以要再拉一次"我是谁"
  try {
    await me()
  } catch (e) {
    /* 拉取失败不阻断登录 */
  }
  return pair
}

export function register(username, password, nickname) {
  return request('/api/register', {
    method: 'POST',
    body: nickname ? { username, password, nickname } : { username, password },
    skipAuth: true,
  })
}

export async function logout() {
  try {
    await request('/api/logout', { method: 'POST' })
  } catch (e) {
    /* 拉黑失败也要让本地退出，不能卡住用户 */
  }
  clearAuth()
}

export function updateNickname(nickname) {
  return request('/api/user/nickname', { method: 'POST', body: { nickname } }).then(() => {
    const p = profile() || {}
    p.nickname = nickname
    localStorage.setItem(PROFILE_KEY, JSON.stringify(p))
    localStorage.setItem('nickname', nickname)
    return nickname
  })
}

export function updatePassword(oldPassword, newPassword, confirmNewPassword) {
  return request('/api/password', {
    method: 'PUT',
    body: {
      oldPassword,
      newPassword,
      confirmNewPassword: confirmNewPassword || newPassword,
    },
  })
}

/* ---------------- 业务 ---------------- */

/**
 * 战绩列表。
 *
 * ⚠️ pageSize 上限是 20（后端 PageQueryDTO 上是 @Max(20)）。
 * 传超过 20 会被参数校验直接打回 400，请求到不了数据库，页面表现为"一条战绩都没有"。
 * 这个坑真实发生过，别改成 50。
 */
export function records(pageNum = 1, pageSize = 20) {
  const size = Math.max(1, Math.min(20, Number(pageSize) || 20))
  return request(`/api/records?pageNum=${Number(pageNum) || 1}&pageSize=${size}`, { raw: true })
}

export function recordsByRoom(roomId) {
  return request(`/api/records/byRoom?roomId=${encodeURIComponent(roomId)}`)
}

export function recordDetail(sessionId) {
  return request(`/api/records/${encodeURIComponent(sessionId)}`)
}

export function pendingReturn() {
  return request('/api/room/pending')
}

/**
 * 加入房间前的预检（大厅用）。
 *
 * 不通过时后端返回非 200 的 message（"房间码应为 6 位数字" / "房间不存在或已解散" /
 * "对局进行中…" / "房间已满…"），这里会抛成 Error，调用方直接显示 e.message 即可，
 * 【不要】再跳到房间页 —— 跳过去只会看到同一个错误，而且人已经离开大厅了。
 */
export function checkRoom(code) {
  return request(`/api/room/check?code=${encodeURIComponent(code)}`)
}

/** 拼 WebSocket 地址用的工具（socket.js 内部也用同样的规则）。 */
export function wsUrl(path, params = {}) {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  const qs = new URLSearchParams(params)
  const token = getToken()
  if (token) qs.set('token', token)
  const q = qs.toString()
  return `${proto}://${location.host}${path}${q ? `?${q}` : ''}`
}
