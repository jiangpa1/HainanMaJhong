import { getToken } from '../api/api.js'

/*
 * 牌局 WebSocket 客户端。
 *
 * 三个必须遵守的后端约定（写错任何一条都会"能连上但收不到消息"）：
 *
 * 1. 路径与参数
 *      /room                      等待房：createRoom / joinRoom / fillBots / startNow / leaveRoom
 *      /game?code=<房号>&seat=<座位>  牌局：discard / act / pass / baoting / chat / rematch
 *
 * 2. 身份只能靠 token
 *      后端 WsAuthHandshakeInterceptor 在握手阶段校验 accessToken，
 *      然后把它自己的 userId 写进会话属性。URL 上的 ?userId= 已被服务端忽略。
 *      浏览器原生 WebSocket 不能加请求头，所以 token 只能放查询参数。
 *
 * 3. 连接被拒 = 握手返回 401
 *      浏览器只给一个笼统的 error 事件，拿不到状态码。所以这里靠
 *      "从未 open 过就 close/error" 来推断认证失败，并跳登录页。
 */

const STATE = {
  IDLE: 'idle',
  CONNECTING: 'connecting',
  OPEN: 'open',
  CLOSED: 'closed',
}

/** 重连退避：连续失败时逐渐拉长，封顶 10 秒；一旦连上就重置。 */
const RECONNECT_BASE_MS = 600
const RECONNECT_MAX_MS = 10_000

export class GameSocket {
  /**
   * @param {object} opts
   * @param {string} opts.path          '/room' 或 '/game'
   * @param {object} [opts.params]      额外查询参数，如 { code, seat }
   * @param {(msg: object) => void} opts.onMessage
   * @param {(state: string) => void} [opts.onState]
   * @param {(reason: string) => void} [opts.onAuthFailed] 握手被拒（token 失效）
   * @param {boolean} [opts.autoReconnect]
   */
  constructor({ path, params = {}, onMessage, onState, onAuthFailed, autoReconnect = true }) {
    this.path = path
    this.params = params
    this.onMessage = onMessage
    this.onState = onState
    this.onAuthFailed = onAuthFailed
    this.autoReconnect = autoReconnect

    this.ws = null
    this.state = STATE.IDLE
    this.attempts = 0
    this.reconnectTimer = null
    this.manualClose = false
    /** 是否成功 open 过；没 open 过就失败 → 判定为握手被拒 */
    this.opened = false
  }

  _setState(s) {
    if (this.state === s) return
    this.state = s
    this.onState?.(s)
  }

  _url() {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const qs = new URLSearchParams(this.params)
    const token = getToken()
    if (token) qs.set('token', token)
    const q = qs.toString()
    return `${proto}://${location.host}${this.path}${q ? `?${q}` : ''}`
  }

  connect() {
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return
    }
    this.manualClose = false
    this.opened = false
    this._setState(STATE.CONNECTING)

    let ws
    try {
      ws = new WebSocket(this._url())
    } catch (e) {
      // 构造就会抛的情况：URL 非法（几乎只会是 bug）
      console.error('[GameSocket] 无法建立连接', e)
      this._scheduleReconnect()
      return
    }
    this.ws = ws

    ws.onopen = () => {
      this.opened = true
      this.attempts = 0
      this._setState(STATE.OPEN)
    }

    ws.onmessage = (ev) => {
      let msg
      try {
        msg = JSON.parse(ev.data)
      } catch (e) {
        console.warn('[GameSocket] 收到非 JSON 消息，已忽略', ev.data)
        return
      }
      this.onMessage?.(msg)
    }

    ws.onerror = () => {
      // 浏览器出于安全不会给错误细节，这里不做判断，交给 onclose 统一处理
    }

    ws.onclose = () => {
      this.ws = null
      this._setState(STATE.CLOSED)
      if (this.manualClose) return

      if (!this.opened) {
        // 从没连上过：最可能是握手 401（token 过期/被拉黑）或服务未启动
        this.attempts += 1
        if (this.attempts >= 2) {
          this.onAuthFailed?.('连接被拒绝，可能登录已过期')
          return
        }
      }
      this._scheduleReconnect()
    }
  }

  _scheduleReconnect() {
    if (!this.autoReconnect || this.manualClose) return
    if (this.reconnectTimer) return
    const delay = Math.min(RECONNECT_BASE_MS * 2 ** this.attempts, RECONNECT_MAX_MS)
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, delay)
  }

  /** 连接可用（已 open）才发送；返回是否真的发出。 */
  send(obj) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      console.warn('[GameSocket] 连接未就绪，消息未发送', obj)
      return false
    }
    try {
      this.ws.send(JSON.stringify(obj))
      return true
    } catch (e) {
      console.warn('[GameSocket] 发送失败', e)
      return false
    }
  }

  close() {
    this.manualClose = true
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    if (this.ws) {
      try {
        this.ws.close()
      } catch (e) {
        /* 忽略：关闭失败不影响流程 */
      }
      this.ws = null
    }
    this._setState(STATE.CLOSED)
  }
}

export { STATE as SOCKET_STATE }
