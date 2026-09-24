import { defineStore } from 'pinia'
import { GameSocket } from '../game/socket.js'
import { handleKicked } from '../api/api.js'
import { seatRing } from '../game/tiles.js'
import { playEffect, playMeldSound, playVoice, chatVoiceOf, huSoundName, playTileSound, playNoticeVoice } from '../game/audio.js'

/*
 * 牌局状态机（pinia store）。
 *
 * 所有字段都直接对应后端 GameEngineServiceImpl / MultiPlayerRoomServiceImpl
 * 发出的 WebSocket 消息，注释里标了来源方法，改后端时按图索骥。
 *
 * 服务端广播一律用【服务端视角】的座位名（EAST/SOUTH/WEST/NORTH）。
 * 前端必须自己按 mySeat 旋转（见 game/tiles.js 的 seatRing），
 * 否则每个玩家看到的"我"都是东家。
 */

/** 把服务端座位映射成以我为视角的屏幕方位：self/bottom, right, top, left */
function emptySeat() {
  return { discards: [], melds: [], flowers: [] }
}

/** 副露类型 → 中文，用于日志与操作框。与后端 Meld.Type 一致。 */
export function meldTypeCn(type) {
  switch (type) {
    case 'CHI':
      return '吃'
    case 'PENG':
      return '碰'
    case 'GANG':
      return '明杠'
    case 'AN_GANG':
      return '暗杠'
    case 'BU_GANG':
      return '补杠'
    default:
      return type || ''
  }
}

/** 从后往前找第一个满足条件的下标；找不到返回 -1。 */
function findLastIndex(arr, pred) {
  for (let i = arr.length - 1; i >= 0; i--) {
    if (pred(arr[i], i)) return i
  }
  return -1
}

export const useGameStore = defineStore('game', {
  state: () => ({
    // ---- 连接 ----
    socket: null,
    connState: 'idle',
    authError: '',

    // ---- 身份 ----
    roomCode: '',
    mySeat: 'EAST',
    myUserId: 0,
    nickname: '',

    // ---- 对局元信息 ----
    playing: false,
    ended: false,
    handNo: 0,
    total: -1,
    dealer: null,
    windIdx: 0,
    windName: '',
    bottom: 0,
    coins: {},
    counts: {},
    /** 牌墙剩余张数（counts 消息里的 wall 字段）。海南规则剩 15 张即荒庄，所以这个值会直接显示给玩家 */
    wall: -1,
    /** match_finish 里的 isHost：服务端按连接逐个下发，只有房主能点「再来一轮」 */
    isHost: false,
    /** match_finish 的 disband=true：房间已解散，不能再开下一轮 */
    disbanded: false,
    /** end 消息里的结果文案（如「东 胡牌…」/「流局」） */
    endResult: '',
    /**
     * 局内战绩：本局每一把的结果。
     * 每项 { hand, dealer, draw, winner, from, selfDraw, tile, fans }
     * 在收到 hu 消息时就记一条（不必等到 end），这样玩家能立刻看到
     * "谁胡了、什么番"，而不是等大字动画播完。
     */
    roundHistory: [],
    /** 上一把结束时的金币，用来算"本把四家各赢/输多少" */
    lastCoins: null,
    players: [], // welcome 里的 [{userId,nickname,seat}]

    // ---- 我的牌 ----
    hand: [], // 手牌（含刚摸的那张）
    drawnTile: -1,
    /*
     * 我自己的副露/花牌【不做独立字段】，由 getters 从 boardMelds/boardFlowers
     * 里按 mySeat 取。原来存了两份副本，board 快照一变就不一致 ——
     * 表现为"手里没副露，手牌上方却挂着一个空的黑框"。
     */

    // ---- 全场副露与花牌（board 消息 / meld 广播）----
    boardMelds: {},
    boardFlowers: {},
    boardDiscards: {},

    // ---- 回合与询问 ----
    turnSeat: null,
    lastDiscard: null, // { seat, tile } 用于"最新那张"高亮
    pending: null, // { kind, reqId, options, canReport, ban, tile, drawnTile }
    timeoutMs: 0,

    // ---- 记录 ----
    logs: [],
    huInfo: null,
    chats: [],
    /** coins 比 hu 先到时，临时挂起本把的明细 notes（见 onCoins） */
    pendingNotes: null,
  }),

  getters: {
    /** 以我为视角的四个方位：[我(下), 右, 对家, 左] */
    ring(state) {
      return seatRing(state.mySeat)
    },
    /** 三个对手（不含我），按 [下家(右), 对家, 上家(左)] 排列 */
    opponents(state) {
      const r = seatRing(state.mySeat)
      return [r[1], r[2], r[3]]
    },
    /** 是否轮到我出牌 */
    isMyTurn(state) {
      return state.turnSeat === state.mySeat
    },
    /** 我现在能点牌打出去吗 */
    canDiscard(state) {
      return !!state.pending && state.pending.kind === 'discard' && !state.ended
    },
    /** 禁打的牌（吃后不能打同一张） */
    bannedTiles(state) {
      return (state.pending && state.pending.ban) || []
    },
    /**
     * 我自己的副露 / 花牌。
     *
     * 从 boardMelds / boardFlowers 派生，而不是存独立字段：
     * 我自己的吃碰杠也是通过 meld 广播进来的（onMeld 会写进 boardMelds[mySeat]），
     * 存两份副本必然出现不同步（board 快照清了、副本还在 → 空黑框）。
     */
    myMelds(state) {
      return (state.boardMelds && state.boardMelds[state.mySeat]) || []
    },
    myFlowers(state) {
      return (state.boardFlowers && state.boardFlowers[state.mySeat]) || []
    },
  },

  actions: {
    /* ==================== 连接 ==================== */

    connect(roomCode, mySeat, myUserId) {
      this.roomCode = roomCode
      this.mySeat = mySeat || 'EAST'
      this.myUserId = myUserId || 0
      this.authError = ''

      /*
       * 进房间时清空局内战绩。
       *
       * 清空点是【进房间】而不是【每轮 match】—— 房主「再来一轮」时服务端沿用同一个
       * session（金币、把数、落库战绩都延续），局内战绩当然也要延续。
       * 原来写在 onMatch 里，导致每开一轮就把前面打的全清掉。
       */
      this.roundHistory = []
      this.pendingNotes = null

      /*
       * 聊天记录和局内日志也必须清。
       *
       * 这个 store 是单例，切房间时 GameTable 会重新挂载，但 store 里的 chats 不会自己没。
       * 结果：开新房间时点开「📋 消息」，看到的还是上一个房间的聊天记录（用户实测报过）。
       * 消息列表的口径是"本房间发过的消息"，所以进房间清零才正确。
       */
      this.chats = []
      this.logs = []

      this.socket = new GameSocket({
        path: '/game',
        params: { code: roomCode, seat: this.mySeat },
        onMessage: (msg) => this.handle(msg),
        onState: (s) => {
          this.connState = s
        },
        onAuthFailed: (reason) => {
          this.authError = reason
          this.pushLog('连接被拒绝：' + reason)
        },
      })
      this.socket.connect()
    },

    disconnect() {
      if (this.socket) this.socket.close()
      this.socket = null
      this.connState = 'closed'
    },

    send(obj) {
      return this.socket ? this.socket.send(obj) : false
    },

    /* ==================== 出牌 / 吃碰杠胡 ==================== */

    discardTile(tile) {
      if (!this.pending || this.pending.kind !== 'discard') return false
      const ok = this.send({ type: 'discard', reqId: this.pending.reqId, tile })
      if (ok) {
        // 立刻收起可点状态，等服务端回新消息；若服务端没接住，store 会靠新 request 或超时提示自救
        this.pending = null
        this.drawnTile = -1
        this.pushLog('已打出 ' + tile)
      }
      return ok
    },

    /**
     * 执行吃碰杠胡里的第 index 个选项（后端按 options 下标应答）。
     *
     * ⚠️ 必须同时接受 kind='action' 和 kind='draw'：
     *   action —— 别人打出牌后我可以吃/碰/杠/胡（HumanPlayerController.onActionChance）
     *   draw   —— 我自己摸牌后可以自摸/暗杠/补杠（onDrawChance）
     * 只允许 'action' 会让【暗杠、补杠、自摸】三个按钮点了完全没反应。
     */
    actOption(index) {
      const k = this.pending && this.pending.kind
      if (k !== 'action' && k !== 'draw') return false
      return this.send({ type: 'act', reqId: this.pending.reqId, index })
    },

    /** 过。同样要覆盖 action 与 draw 两种询问。 */
    pass() {
      const k = this.pending && this.pending.kind
      if (k !== 'action' && k !== 'draw') return false
      return this.send({ type: 'pass', reqId: this.pending.reqId })
    },

    baoTing() {
      if (!this.pending) return false
      return this.send({ type: 'baoting', reqId: this.pending.reqId })
    },

    chat(text) {
      const t = String(text || '').trim()
      if (!t || t.length > 80) return false
      return this.send({ type: 'chat', text: t })
    },

    rematch() {
      return this.send({ type: 'rematch' })
    },

    leaveRoom() {
      return this.send({ type: 'leaveRoom' })
    },

    /* ==================== 消息分发 ==================== */

    handle(msg) {
      switch (msg.type) {
        /*
         * 被顶下线：同一账号在别的设备登录时服务端推这条，然后关连接。
         * 必须在这里处理掉 —— 只关连接的话前端会当普通掉线，一直重连却连不上，
         * 用户根本不知道发生了什么。走统一出口：清登录态 + 提示 + 回登录页。
         */
        case 'kick':
          handleKicked(msg.reason)
          return
        // 服务端 MultiPlayerRoomServiceImpl.bindGameSeat
        case 'welcome':
          return this.onWelcome(msg)
        case 'match':
          return this.onMatch(msg)
        case 'hand_start':
          return this.onHandStart(msg)
        case 'board':
          return this.onBoard(msg)
        case 'counts':
          return this.onCounts(msg)
        case 'hand':
          return this.onHand(msg)
        case 'dealer':
          return this.onDealer(msg)
        case 'turn':
          return this.onTurn(msg)
        case 'draw':
          return this.onDraw(msg)
        case 'flower':
          return this.onFlower(msg)
        case 'discard':
          return this.onDiscard(msg)
        case 'meld':
          return this.onMeld(msg)
        case 'coins':
          return this.onCoins(msg)
        case 'report': {
          // 报听：任何人报听都播提示音
          playEffect('报听')
          const modeCn = msg.mode === 1 ? '天听' : msg.mode === 2 ? '地听' : '报听'
          return this.pushLog(`${msg.seat} ${modeCn}`)
        }
        case 'hu':
          return this.onHu(msg)
        case 'end':
          return this.onEnd(msg)
        case 'match_finish':
          return this.onMatchFinish(msg)
        case 'room_closed':
          return this.onRoomClosed(msg)
        case 'join_denied':
          return this.onDenied(msg)
        case 'request':
          return this.onRequest(msg)
        case 'chat':
          return this.onChat(msg)
        case 'log':
          return this.pushLog(msg.text)
        default:
          console.warn('[game] 未知消息类型', msg)
      }
    },

    /* ---------------- 各消息处理 ---------------- */

    onWelcome(m) {
      this.players = m.players || []
      if (m.seat) this.mySeat = m.seat
      const me = this.players.find((p) => Number(p.userId) === Number(this.myUserId))
      if (me) this.nickname = me.nickname || ''
      this.pushLog(`已进入房间 ${m.roomId}，座位 ${m.seat}`)
    },

    onMatch(m) {
      this.total = m.total ?? -1
      this.playing = true
      this.ended = false
      /*
       * ⚠️ 这里【不】清 roundHistory。
       *
       * 房主「再来一轮」时服务端发 match 但沿用同一 session（金币/把数/落库战绩延续），
       * 局内战绩也必须延续。清空动作已挪到 connect()（进房间时）。
       * 顺带解决了另一个问题：上一轮结束时 match_finish 把 playing 置 false、ended 置 true，
       * 没有这一条 match 把它们复位的话，「再来一轮」按钮会在整个新一轮里一直挂着。
       */
    },

    onHandStart(m) {
      this.handNo = m.hand ?? 0
      this.total = m.total ?? -1
      this.dealer = m.dealer || null
      this.windIdx = m.wind ?? 0
      this.windName = m.windName || ''
      this.bottom = m.bottom ?? 0
      if (m.coins) this.coins = { ...this.coins, ...m.coins }
      // 播种"本把起始金币"：必须在 hand_start 就记下来，
      // 否则【第一把】结束时没有基准，四家得分变化会算不出来 ——
      // coins 消息要到第一把打完才第一次到达（踩过这个坑）。
      this.lastCoins = { ...this.coins }
      // 新一把：清掉上一把的临时状态
      this.ended = false
      this.pending = null
      this.huInfo = null
      this.lastDiscard = null
      this.turnSeat = null
      this.drawnTile = -1
      // 牌河/副露/花牌必须清空，否则上一把的牌会留在桌上。
      // 注意：这些字段平时靠 discard/meld/flower 消息增量追加，
      // 只有断线重连时才会收到服务端的 board 全量快照。
      this.boardDiscards = {}
      this.boardMelds = {}
      this.boardFlowers = {}
      // 本把起始金币：用来在结束时算四家得分变化
      this.lastCoins = { ...this.coins }
      this.pushLog(`第 ${this.handNo} 把开始，庄家 ${this.dealer || ''}`)
    },

    /**
     * board：整场快照（断线重连/服务重启续跑时下发）
     * { discards: {seat: [tile]}, melds: {seat: [{type,tiles,from}]}, flowers: {seat: [tile]} }
     */
    onBoard(m) {
      this.boardDiscards = m.discards || {}
      this.boardMelds = m.melds || {}
      this.boardFlowers = m.flowers || {}
    },

    /** counts：各家手牌张数（对手用来显示牌背数量）+ 牌墙剩余 */
    onCounts(m) {
      this.counts = m.counts || {}
      // wall 只有中后段（sendCounts）才带；早期 onDeal/onDraw 分支不带，别把已知值冲成 -1
      if (typeof m.wall === 'number') this.wall = m.wall
    },

    /** hand：只发给"我"的私有手牌（别人收不到） */
    onHand(m) {
      this.hand = m.hand || []
      /*
       * hand 消息对"我"这个座位是【权威】的：它带的 melds/flowers
       * 一定和我实际状态一致（meld 广播有可能因为断线丢过）。
       * 所以把它同步进 board* 的 mySeat 槽位 —— getters 会自动跟着更新。
       */
      if (Array.isArray(m.melds)) this.boardMelds[this.mySeat] = m.melds
      if (Array.isArray(m.flowers)) this.boardFlowers[this.mySeat] = m.flowers
      // 手牌里若含刚摸的牌，drawnTile 由 request/draw 消息决定，这里不擅自改
    },

    onDealer(m) {
      this.dealer = m.dealer || null
    },

    /** turn：全场广播"轮到谁"，kind 为 discard 时高亮该家 */
    onTurn(m) {
      this.turnSeat = m.seat || null
      this.timeoutMs = m.timeoutMs || 0
    },

    /** draw：某家摸牌。别人的摸牌只知道"摸了一张"，牌面不下发 */
    onDraw(m) {
      if (m.seat === this.mySeat) {
        this.drawnTile = typeof m.tile === 'number' ? m.tile : -1
      } else {
        this.drawnTile = -1
      }
    },

    onFlower(m) {
      // 补花音效：任何人摸到花都播（与旧版一致）
      playEffect('补花')
      // 花牌也要记进桌面，否则别人的花牌区永远是空的
      if (m.seat && typeof m.tile === 'number') {
        if (!this.boardFlowers[m.seat]) this.boardFlowers[m.seat] = []
        if (this.boardFlowers[m.seat].indexOf(m.tile) < 0) {
          this.boardFlowers[m.seat].push(m.tile)
        }
      }
      if (m.seat === this.mySeat && typeof m.tile === 'number') {
        this.pushLog(`摸到花牌 #${m.tile}`)
      }
    },

    /**
     * discard：全场广播某家打出某张。
     *
     * ⚠️ 必须把它【追加进牌河】。原来的实现只记了 lastDiscard，
     * 结果牌河永远只有 board 快照里的内容（而 board 只在断线重连/续跑时才下发），
     * 于是"打出去的牌看得见吗"→ 看不见，牌池一直是空的。
     */
    onDiscard(m) {
      this.lastDiscard = { seat: m.seat, tile: m.tile }
      if (!m.seat) return
      if (!this.boardDiscards[m.seat]) this.boardDiscards[m.seat] = []
      this.boardDiscards[m.seat].push(m.tile)
      /*
       * 出牌报牌音：服务端对【每一张】打出的牌都会单独广播一条 discard，
       * 所以这里每收到一条就按牌面报一次牌名（"九万"/"东风"…），四家出牌都听得到。
       * 素材在 static/music/tiles/；花牌没有报牌音，内部会静默跳过。
       */
      playTileSound(m.tile)
    },

    /**
     * meld：某家吃碰杠。
     *
     * 同 onDiscard 的道理：必须写进 boardMelds，否则副露区永远不更新
     * （board 只在重连时下发一次）。暗杠也要记，否则别人看不到他家副露数量。
     *
     * ⚠️ 不能无脑 push：补杠是【把原来的碰换成杠】而不是新增一副。
     *   后端 RoomManager.applyDrawAction 里就是 `p.melds.set(i, 新杠)` —— 位置原样替换。
     *   前端如果 push，界面上就会同时留着"碰 3 张"和"杠 4 张"两副，
     *   等于凭空多出一副副露（用户报过："碰后补杠不要留着原来的碰副露"）。
     *   所以这里要按后端的语义做替换。
     */
    onMeld(m) {
      // 吃/碰/杠各自的音效（后端 Meld.Type：CHI/PENG/GANG/AN_GANG/BU_GANG）
      playMeldSound(m.meld && m.meld.type)

      /*
       * 三道/四道是【吃碰杠的那一刻】成立的，后端当场判定并带上 notice。
       * 用户要求这一步【只给语音、不弹文字】：牌桌正打到紧要处，
       * 再糊一条文字提示反而挡视线；听到"包三道"就够警醒了。
       * 结算时的文字提示走另一条路（coins 的 notes → 战绩/toast）。
       */
      if (m.notice) {
        playNoticeVoice(m.notice, m.notice)
        this.pushLog(`${m.notice}：${m.seat} 与 ${m.meld && m.meld.from} 之间`)
      }

      if (m.seat && m.meld) {
        if (!this.boardMelds[m.seat]) this.boardMelds[m.seat] = []
        const list = this.boardMelds[m.seat]

        /*
         * 补杠：找到同牌的那副【碰】，原地替换成杠（和后端 set(i, ...) 一一对应）。
         * 找不到对应碰时（比如中途断线重连漏了消息）就退回 push，至少不丢信息。
         */
        const tile = Array.isArray(m.meld.tiles) ? m.meld.tiles[0] : null
        const pengIdx =
          m.meld.type === 'BU_GANG' && tile !== null
            ? list.findIndex((x) => x && x.type === 'PENG' && Array.isArray(x.tiles) && x.tiles[0] === tile)
            : -1
        if (pengIdx >= 0) {
          // 杠要指向"原来点碰的那家"：后端 BU_GANG 的 from 是 null，
          // 但被碰的那副带着 from，这里继承过来，界面上那张横放的牌才指得对。
          const inherited = m.meld.from || list[pengIdx].from || null
          list.splice(pengIdx, 1, { ...m.meld, from: inherited })
        } else {
          list.push(m.meld)
        }

        /*
         * 把被吃/碰/杠的那张牌从【牌河】里拿掉。
         *
         * 后端在有人吃碰杠后不会重发 board 快照，只在 meld 消息里带 from（谁打出的）。
         * 所以必须在这里自己把那张牌从对方的牌河里扣掉，否则它会一直留在牌河上
         * ——"这张牌被人拿走了却还在桌上"。
         *
         * 暗杠/补杠没有来源可扣（暗杠是自己手里的牌；补杠对应的那张早在碰的时候就
         * 已经扣过了），所以补杠这里不能再扣一次，否则会把牌河里别的牌误删。
         */
        const from = m.meld.type === 'BU_GANG' ? null : m.meld.from
        const river = from ? this.boardDiscards[from] : null
        if (river && river.length && Array.isArray(m.meld.tiles)) {
          const idx = findLastIndex(river, (t) => m.meld.tiles.indexOf(t) >= 0)
          if (idx >= 0) {
            river.splice(idx, 1)
          }
        }
      }
      // 自己的副露不用单独存：myMelds 是 getter，读的就是 boardMelds[mySeat]
      this.pushLog(`${m.seat} ${meldTypeCn(m.meld && m.meld.type)}`)
    },

    /**
     * coins：每把结束后服务端广播的金币。
     *
     * ⚠️ 顺序坑：后端是【先广播 coins（结算后），再广播 hu】。
     * 所以 coins 到达时，本把的战绩记录还没建出来 —— 在这里直接算差额是算不出来的。
     * 改成：hu 时先记下"结算前的金币"（coinBefore），coins 到达时再和它相减。
     */
    onCoins(m) {
      if (!m.coins) return
      const now = m.coins
      // 本把战果已记录但还没算分 → 用记录里的基准算差额
      let attached = false
      for (let i = this.roundHistory.length - 1; i >= 0; i--) {
        const r = this.roundHistory[i]
        if (!r.delta && r.coinBefore) {
          const delta = {}
          for (const s of Object.keys(now)) {
            delta[s] = (Number(now[s]) || 0) - (Number(r.coinBefore[s]) || 0)
          }
          r.delta = delta
          // notes 跟着这条战绩走：里面会有"包三道·代付三家"这类关键说明
          if (m.notes) r.notes = m.notes
          attached = true
          break
        }
      }
      /*
       * coins 比 hu 先到时（服务端顺序不保证），本把的战绩条目还没建出来。
       * 先把它挂起，等 onHu 建条目时再补上 —— 直接丢掉的话
       * "包三道"这一条提示就在界面上彻底看不到了。
       */
      if (!attached && m.notes) this.pendingNotes = m.notes
      this.lastCoins = { ...now }
      // 合并而不是替换：早期 hand_start 也会带 coins，直接覆盖会把之前的值丢掉
      this.coins = { ...this.coins, ...now }
    },

    onHu(m) {
      this.huInfo = {
        seat: m.seat,
        selfDraw: !!m.selfDraw,
        tile: m.tile,
        from: m.from || null,
        tianHu: !!m.tianHu,
        baoTing: m.baoTing || 0,
        fans: m.fans || [],
        ganKai: !!m.ganKai,
      }
      this.pushLog(`${m.seat} 胡牌：${(m.fans || []).join('、') || '平胡'}`)
      // 胡牌音效：按番型优先级选（天胡/十三幺/龙七对… > 杠开 > 自摸 > 平胡）
      playEffect(huSoundName(m))

      // 局内战绩：收到 hu 就记一条，玩家立刻能看到本把结果
      this.roundHistory.push({
        hand: this.handNo,
        dealer: this.dealer,
        draw: false,
        winner: m.seat,
        from: m.from || null,
        selfDraw: !!m.selfDraw,
        tile: typeof m.tile === 'number' ? m.tile : -1,
        fans: m.fans || [],
        tianHu: !!m.tianHu,
        ganKai: !!m.ganKai,
        baoTing: m.baoTing || 0,
        // 结算前的金币快照：coins 消息（结算后）到达时用它算四家得分变化。
        // 不能等到那时再取，因为那时 this.coins 已经被结算值覆盖了。
        coinBefore: { ...this.coins },
        delta: null,
        // 若 coins（含 notes）比 hu 先到，这里把挂起的那份补上
        notes: this.pendingNotes || null,
      })
      this.pendingNotes = null
    },

    onEnd(m) {
      this.ended = true
      this.pending = null
      this.endResult = m.result || ''
      this.pushLog('本把结束：' + this.endResult)

      /*
       * 流局补记：流局不会发 hu 消息，只有一条 text="流局" 的 log。
       * 如果本把还没被记进 roundHistory（最后一条的 hand 不是本把），
       * 就补一条流局记录，保证局内战绩每一把都有。
       */
      const last = this.roundHistory[this.roundHistory.length - 1]
      if (!last || last.hand !== this.handNo) {
        this.roundHistory.push({
          hand: this.handNo,
          dealer: this.dealer,
          draw: true,
          winner: null,
          from: null,
          selfDraw: false,
          tile: -1,
          fans: [],
          // 流局也要算分（流局同样有金币结算）
          coinBefore: { ...this.coins },
          delta: null,
        })
      }
    },

    onMatchFinish(m) {
      this.playing = false
      this.ended = true
      this.pending = null
      if (m.coins) this.coins = m.coins
      // 服务端按连接逐个下发 isHost，所以要覆盖而不是沿用旧值
      this.isHost = !!m.isHost
      // disband=true 表示房间已解散（有人中途未归），此时不能再点「再来一轮」
      this.disbanded = !!m.disband
      this.pushLog('对局结束' + (m.reason ? '：' + m.reason : ''))
    },

    onRoomClosed(m) {
      this.playing = false
      this.ended = true
      this.pending = null
      this.pushLog('房间已解散' + (m.reason ? '：' + m.reason : ''))
    },

    onDenied(m) {
      this.pushLog('被拒绝：' + (m.reason || ''))
    },

    /**
     * request：引擎向"我"要一个决定（HumanPlayerController 发出）。
     *   kind='discard' { hand, drawnTile, canReport, ban, timeoutMs }
     *   kind='action'  { tile, options[], timeoutMs }
     *   kind='draw'    { drawnTile, options[], timeoutMs }
     *
     * 注意：kind='discard' 时后端会把完整手牌再带一次，用它覆盖本地 hand，
     * 避免"本地与服务端不一致"导致点了一张服务端认为不在手里的牌。
     */
    onRequest(m) {
      const prevReqId = this.pending ? this.pending.reqId : null
      this.pending = {
        kind: m.kind,
        reqId: m.reqId,
        options: m.options || [],
        canReport: !!m.canReport,
        ban: m.ban || [],
        tile: typeof m.tile === 'number' ? m.tile : -1,
      }
      this.timeoutMs = m.timeoutMs || 0

      if (m.kind === 'discard') {
        if (Array.isArray(m.hand)) this.hand = m.hand
        this.drawnTile = typeof m.drawnTile === 'number' ? m.drawnTile : -1
        this.turnSeat = this.mySeat
        if (prevReqId !== null && prevReqId !== m.reqId) {
          // 服务端判定上次应答没被接住、主动重发了请求：提示玩家再来一次
          this.pushLog('服务端重发了出牌请求，请重新选择')
        }
      } else if (m.kind === 'action') {
        // 注意：action 类 request 里【没有】from 字段（后端只发 tile/options/timeoutMs）。
        // 谁打出的这张牌，要靠紧邻其前的 turn 消息里的 seat 推断——
        // 引擎固定先 onTurnStart 再问动作，所以 turnSeat 就是打牌那家。
        if (typeof m.tile === 'number') {
          this.lastDiscard = { seat: this.turnSeat, tile: m.tile }
        }
      } else if (m.kind === 'draw') {
        this.drawnTile = typeof m.drawnTile === 'number' ? m.drawnTile : this.drawnTile
      }
    },

    /**
     * chat：房间内的一条发言。
     *
     * 广播是发给全桌的，所以【自己发的那条也会从这里回来】——
     * 不要再在 send 那边本地补一条，否则自己的话会出现两次。
     *
     * 保留上限 200 条：消息列表要展示"本房间所有发过的消息"，
     * 原来只留 20 条，打久了前面的就看不到了。留 200 是为了给长局兜底，
     * 避免无限增长（一条消息只是一个短对象，200 条的体量可以忽略）。
     */
    onChat(m) {
      this.chats.push({ seat: m.seat, text: m.text, at: Date.now() })
      if (this.chats.length > 200) this.chats.splice(0, this.chats.length - 200)
      // 快捷短语聊天要播语音；自由文本没有对应音频，跳过
      const v = chatVoiceOf(m.text)
      if (v) playVoice(v)
    },

    pushLog(text) {
      if (!text) return
      this.logs.push({ text, at: Date.now() })
      if (this.logs.length > 200) this.logs.splice(0, this.logs.length - 200)
    },

    /** 进入牌桌时清空，避免上一局残留 */
    reset() {
      this.$reset()
    },
  },
})
