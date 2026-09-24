/*
 * 音效与语音。
 *
 * 资源来自 static/music/：
 *   background.mp3          背景音乐（循环）
 *   sound_effects/<名>.mp3  牌局音效：吃 碰 杠 补花 报听 自摸 杠开 平胡
 *                           以及番型：天胡 十三幺 龙七 七对 碰碰胡 清一色 混一色
 *   tiles/<牌名>.mp3        出牌报牌音：一牌一文件，共 34 个（见 tileVoiceName）
 *   voices/<话>.mp3         快捷语音（聊天里发的固定短语，收到时播出来）
 *
 * ⚠️ 浏览器自动播放策略（这是最容易踩的坑）：
 *   页面加载后未经用户交互就调 play() 会被拒绝（NotAllowedError），
 *   BGM 会静默失败。所以这里不主动播 BGM，而是等第一次用户交互
 *   （点击/按键）后再启动 —— 牌桌上玩家必然要点牌，届时自然解锁。
 *   所有 play() 都挂 catch，失败只 warn，绝不让它冒泡成页面错误。
 *
 * 文件名含中文，必须用 encodeURIComponent 拼 URL，否则某些浏览器/代理会 404。
 */
import { SUITS, HONOR_START } from './tiles.js'

const MUSIC_KEY = 'sndMusic'
const SFX_KEY = 'sndSfx'
/** 开关状态从 localStorage 读，默认开（与原实现一致）。 */
const state = {
  music: localStorage.getItem(MUSIC_KEY) !== '0',
  sfx: localStorage.getItem(SFX_KEY) !== '0',
  unlocked: false,
}

let bgm = null
let unlockBound = false

function flag(key, on) {
  localStorage.setItem(key, on ? '1' : '0')
}

/* ============================================================
   播放核心：复用 + 解锁补播 + 并发限流
   ------------------------------------------------------------
   原来每次播音都是 new Audio(url)，在手机上会出两个真实问题：
     1. 一局下来创建几百个媒体元素，GC 和主线程抖动正好落在"出牌"那一帧
        （用户报的"出牌会卡"）；
     2. iOS/安卓对【同时解码的媒体元素数】有上限（一般 4~6），
        连续动作（出牌 + 碰 + 结算）同时来几声时，超出的那几声 play() 直接失败
        （用户报的"音效有时候没有"）。
   现在：同一个 URL 复用一个 <audio>（LRU 池），同时最多 MAX_CONCURRENT 路，
   被自动播放策略拒了就挂起最近一次请求、等首次用户手势后补播。
   ============================================================ */

/** url -> HTMLAudioElement，LRU：命中时挪到队尾（Map 保持插入顺序） */
const pool = new Map()
/** 池子上限：音效 20 + 报牌音 34 = 54 个文件，不必全留，够覆盖常规节奏即可 */
const MAX_POOL = 24
/** 同时最多几路；超过就丢掉最新的一声（比整片哑掉好） */
const MAX_CONCURRENT = 3
/** 已经确认能正常加载的 URL：之后直接走池子，不再做"缺失探测" */
const knownGood = new Set()
/** 未解锁时挂起的最近一次播放请求（首次手势后补播） */
let pendingAfterUnlock = null

function takeFromPool(url, vol) {
  let a = pool.get(url)
  if (a) {
    pool.delete(url) // 挪到队尾 = 最近使用
  } else {
    if (pool.size >= MAX_POOL) {
      const oldest = pool.keys().next().value
      const dead = pool.get(oldest)
      try {
        dead.pause()
      } catch (e) {
        /* 忽略 */
      }
      pool.delete(oldest)
    }
    a = new Audio(url)
    a.preload = 'auto'
  }
  if (vol) a.volume = vol
  pool.set(url, a)
  return a
}

function playingCount() {
  let n = 0
  for (const a of pool.values()) {
    if (!a.paused && !a.ended && a.currentTime > 0) n++
  }
  return n
}

/**
 * 播一声（内部用）。
 *
 * @returns {boolean} 是否真的发起了播放
 */
function playOnce(url, vol) {
  if (!state.sfx) return false
  try {
    if (playingCount() >= MAX_CONCURRENT) {
      // 并发已经打满：再塞只会解码失败/互相抢，直接跳过
      return false
    }
    const a = takeFromPool(url, vol)
    try {
      a.currentTime = 0 // 复用同一个元素：必须回到开头，否则第二次没声
    } catch (e) {
      /* 还没元数据时可能抛，忽略 */
    }
    const p = a.play()
    if (p && typeof p.catch === 'function') {
      p.catch((e) => {
        const name = e && e.name
        console.warn('[audio] 播放失败', name || e, url)
        /*
         * NotAllowedError = 还没有用户手势（自动播放策略）。
         * 这时【不能就这么丢了】—— 记下来，等首次 pointerdown 解锁后补播，
         * 否则用户听到的就是"音效加载不出来"。
         */
        if (name === 'NotAllowedError') {
          state.unlocked = false
          pendingAfterUnlock = { url, vol }
        }
      })
    }
    return true
  } catch (e) {
    console.warn('[audio] 播放异常', url, e)
    return false
  }
}

/** 播一个牌局音效，名如 '碰' / '自摸' / '平胡'。 */
export function playEffect(name) {
  if (!name) return
  playOnce(`/music/sound_effects/${encodeURIComponent(name)}.mp3`, 0.9)
}

/** 播一条语音，名如 '快点啊~等得我花儿都谢了'。 */
export function playVoice(name) {
  if (!name) return
  playOnce(`/music/voices/${encodeURIComponent(name)}.mp3`, 0.9)
}

/** 副露音效：吃 / 碰 / 杠（暗杠、补杠都归到「杠」）。 */
export function playMeldSound(type) {
  if (type === 'CHI') return playEffect('吃')
  if (type === 'PENG') return playEffect('碰')
  if (type === 'GANG' || type === 'AN_GANG' || type === 'BU_GANG') return playEffect('杠')
}

/** 已知缺失的素材：只试一次，失败后不再重试也不再告警。 */
const missing = new Set()

/**
 * 浏览器语音合成兜底。
 *
 * 用途：包三道/包四道这类"局内必须立刻听到"的播报，如果还没有录音素材，
 * 就用 Web Speech API 直接念出来 —— 总比完全没有声音好。
 * 各家浏览器/系统对中文的发音质量不一，只作为兜底；一旦放了 mp3 素材就优先用素材。
 */
function speak(text) {
  if (!state.sfx || !text) return
  try {
    const synth = window.speechSynthesis
    if (!synth || typeof window.SpeechSynthesisUtterance !== 'function') return
    const u = new SpeechSynthesisUtterance(text)
    u.lang = 'zh-CN'
    u.rate = 1
    u.volume = 0.9
    synth.speak(u)
  } catch (e) {
    /* 语音合成不可用就算了，不要影响牌局 */
  }
}

/**
 * 播一个"可能不存在"的音效。
 *
 * 与 playEffect 的区别：文件缺失时【不刷警告】，记住结果不再重试；
 * 可选在缺失时走一段兜底（比如语音合成）。
 *
 * @param name      文件名（不含扩展名）
 * @param dir       子目录，'sound_effects' 或 'tiles'
 * @param onMissing 素材不存在时的兜底动作
 */
function playEffectIfPresent(name, dir, onMissing) {
  if (!state.sfx || !name) return
  const key = `${dir}/${name}`
  if (missing.has(key)) {
    if (onMissing) onMissing()
    return
  }
  const url = `/music/${dir}/${encodeURIComponent(name)}.mp3`
  /*
   * 已经确认过这个素材能加载（第一次 canplaythrough 之后就记进 knownGood）：
   * 之后直接走音频池，不再每次都建新元素做"缺失探测" —— 那种做法在连续动作时
   * 会同时开好几个媒体元素，正好撞上移动端的并发解码上限。
   */
  if (knownGood.has(key)) {
    playOnce(url, 0.9)
    return
  }
  try {
    const a = new Audio(url)
    a.volume = 0.9
    let settled = false
    /**
     * @param permanent true 表示"确认素材不存在"，以后不再尝试；
     *                  false 表示"只是这次没就绪"（比如加载慢），下次还要试。
     */
    const fallback = (permanent) => {
      if (settled) return
      settled = true
      // 兜底发声前先把可能正在缓冲的音频停掉，避免"语音念完素材又响一遍"
      try {
        a.pause()
      } catch (e) {
        /* 忽略 */
      }
      if (permanent) missing.add(key)
      if (onMissing) onMissing()
    }
    const ok = () => {
      settled = true
      knownGood.add(key)
      // 探测用的这个是"新鲜"元素，正式播放走池子（它是同一个 URL，命中缓存）
      try {
        a.pause()
      } catch (e) {
        /* 忽略 */
      }
      playOnce(url, 0.9)
    }
    // 只有真正的加载错误才算"素材不存在"
    a.addEventListener('error', () => fallback(true), { once: true })
    a.addEventListener('canplaythrough', ok, { once: true })
    /*
     * 超时兜底：素材加载慢、或自动播放策略把 play() 悬着不 resolve 时，
     * 不能就这么一声不吭 —— 提示音"该响没响"等于没提示。
     *
     * ⚠️ 这里【不能】把素材标记为缺失：文件其实是好的，只是这次慢。
     *   标记了的话往后每一把都退化成语音合成，一次慢加载换来永久降级。
     */
    setTimeout(() => fallback(false), 1200)
    const p = a.play()
    if (p && typeof p.catch === 'function') {
      // play() 被拒也可能是自动播放策略，同样不能据此判定素材缺失
      p.catch(() => fallback(false))
    }
  } catch (e) {
    missing.add(key)
    if (onMissing) onMissing()
  }
}

/** 当前缺少哪些素材（调试/自检用）。 */
export function missingEffects() {
  return Array.from(missing)
}

/**
 * 结算明细里的一条 tag → 对应的播报音效名。
 *
 * 后端 HainanScore 会把这些写进 notes（随 coins 一起广播）：
 *   · 包牌："包三道·代付三家" / "包四道·代付三家" / "包尾墙·代付三家" / "包抢杠·代付三家"
 *   · 花分："真花·四季" "真花·四君子" "真对花" "假花" "假对花·2"
 * 素材在 static/music/sound_effects/ 下按【这里返回的名字】命名。
 *
 * ⚠️ 注意"对花"：真对花和假对花共用同一个音效「对花」，不区分真假
 *   （素材只有 真花/假花/对花 三个）。要改成一音一档就得先加素材。
 *
 * 返回空串表示这条 tag 不需要单独播报（比如胡牌那条"自摸(碰碰胡)"，
 * 胡牌本身已经有自己的音效了，再播一次就重了）。
 */
export function noticeSoundOf(tag) {
  const s = String(tag || '')
  if (!s) return ''
  // 包牌：取「·」前那段，正好就是素材名（包三道/包四道/包尾墙/包抢杠）
  if (s.indexOf('包') === 0) return s.split('·')[0]
  // 对花优先判断：真对花/假对花 里都含"对花"两个字，要在真花/假花之前拦下来
  if (s.indexOf('对花') >= 0) return '对花'
  if (s.indexOf('真花') >= 0) return '真花'
  if (s.indexOf('假花') >= 0) return '假花'
  return ''
}

/**
 * 这条 tag 是不是"包牌"。
 *
 * 包牌要出声【也要出字】（谁一个人赔三家必须让人看见）；
 * 花分只出声不出字 —— 照旧版行为，花是每局都可能有的小分，
 * 屏幕不显示，避免每把都往牌桌中间糊一条提示。
 */
export function isBaoNotice(tag) {
  return String(tag || '').indexOf('包') === 0
}

/**
 * 结算提示播报：包牌 / 花分。
 *
 * 局内一发生就要听到，所以不能依赖"素材是不是已经准备好"：
 * 有 static/music/sound_effects/<音效名>.mp3 就用它，
 * 没有（比如包尾墙/包抢杠还没录音）就用语音合成念出来。
 */
export function playNoticeVoice(sound, spokenText) {
  if (!sound) return
  playEffectIfPresent(sound, 'sound_effects', () => speak(spokenText || sound))
}

/*
 * 出牌报牌音。
 *
 * 素材在 static/music/tiles/，一牌一文件，共 34 个（= 34 种正牌）：
 *   一万..九万 / 一筒..九筒 / 幺鸡 二条..九条 / 东风 南风 西风 北风 红中 发财 白板
 *
 * ⚠️ 文件名和牌面显示名【不完全一样】，这是最容易接错的地方：
 *   · 一条 的素材叫「幺鸡」（牌面也常这么叫），不是「一条」
 *   · 字牌要带后缀：牌面显示「东」，素材是「东风」；中/发/白 是「红中/发财/白板」
 *   · 花牌（春夏秋冬梅兰竹菊）没有报牌音，出花是另一套（补花.mp3）
 * 所以这里单列一张映射表，不要去套 tileName()。
 */
const CN_NUM = ['一', '二', '三', '四', '五', '六', '七', '八', '九']
/** 27..33 的报牌音文件名（牌面只显示单字，素材是两字/三字） */
const HONOR_VOICE = ['东风', '南风', '西风', '北风', '红中', '发财', '白板']
/** 18 = 一条，素材名特殊 */
const TIAO_ONE_VOICE = '幺鸡'

/**
 * 牌索引 → 报牌音文件名（不含扩展名）。非正牌（花牌/脏值）返回空串。
 * 与 static/music/tiles/ 下的文件一一对应，tools/check-audio.mjs 会逐个核对。
 */
export function tileVoiceName(id) {
  // 正牌是 0..33（HONOR_START 是 27，字牌占 27..33 共 7 张）
  if (typeof id !== 'number' || !Number.isFinite(id) || id < 0 || id >= HONOR_START + HONOR_VOICE.length) {
    return ''
  }
  if (id >= HONOR_START) {
    // 字牌：牌面只显示单字（东/中），报牌音是「东风」「红中」
    return HONOR_VOICE[id - HONOR_START] || ''
  }
  for (const s of SUITS) {
    if (id >= s.start && id < s.start + s.size) {
      const n = id - s.start + 1
      // 一条的素材叫「幺鸡」，不是「一条」
      if (s.key === 'tiao' && n === 1) return TIAO_ONE_VOICE
      return `${CN_NUM[n - 1]}${s.suffix}`
    }
  }
  return ''
}

/**
 * 出牌报牌音：有人打出一张牌时调一次（服务端对每张打出的牌都会单独广播）。
 * 花牌没有对应素材，静默跳过。
 */
export function playTileSound(id) {
  const name = tileVoiceName(id)
  if (name) playEffectIfPresent(name, 'tiles')
}

/*
 * 胡牌音效优先级（逐条照搬原实现，改顺序会改变听感）：
 *   特殊番型 > 杠开 > 自摸 > 平胡（点炮也按平胡）
 * 注意「龙七对」的音频文件名是「龙七」，不是番型全名。
 */
export function huSoundName(msg) {
  const fans = (msg && msg.fans) || []
  const prio = ['天胡', '十三幺', '龙七对', '七对', '碰碰胡', '清一色', '混一色']
  for (const f of prio) {
    if (fans.indexOf(f) >= 0) return f === '龙七对' ? '龙七' : f
  }
  if (msg && msg.ganKai) return '杠开'
  if (msg && msg.selfDraw) return '自摸'
  return '平胡'
}

/** 可发送的快捷语音短语，必须与 music/voices/ 下的文件名一一对应。 */
export const VOICE_PHRASES = [
  '快点啊~等得我花儿都谢了',
  '注意啦！',
  '窃听风云！',
  '不必理会',
  '闹麻',
  '气不气',
  '很气很气',
]

/** 聊天文本若是快捷短语则返回它（用于播语音），否则返回 null。 */
export function chatVoiceOf(text) {
  return VOICE_PHRASES.indexOf(text) >= 0 ? text : null
}

/* ---------------- 背景音乐 ---------------- */

export function playBgm() {
  if (!state.music) return
  try {
    if (!bgm) {
      bgm = new Audio('/music/background.mp3')
      bgm.loop = true
      bgm.volume = 0.45
    }
    if (bgm.paused) {
      const p = bgm.play()
      if (p && typeof p.catch === 'function') {
        p.catch((e) => console.warn('[audio] BGM 播放被拦截，等用户交互后重试', e && e.name))
      }
    }
  } catch (e) {
    console.warn('[audio] BGM 失败', e)
  }
}

export function pauseBgm() {
  try {
    if (bgm && !bgm.paused) bgm.pause()
  } catch (e) {
    /* 忽略 */
  }
}

/**
 * 绑定"首次用户交互解锁音频"。
 *
 * 必须在牌桌挂载时调用一次：在此之前 play() 会被浏览器拒绝，
 * 所以先注册一次性监听，玩家第一次点牌/按键时立刻补播 BGM。
 */
export function bindAudioUnlock() {
  if (unlockBound) return
  unlockBound = true
  const onFirst = () => {
    state.unlocked = true
    playBgm()
    /*
     * 补播：进桌到第一次点击之间可能已经来过几声（别人的出牌、碰杠），
     * 那时被自动播放策略挡掉了。用户一旦有了手势，把最近那一声补上，
     * 免得玩家以为"音效坏了"。
     */
    if (pendingAfterUnlock) {
      const p = pendingAfterUnlock
      pendingAfterUnlock = null
      playOnce(p.url, p.vol)
    }
    window.removeEventListener('pointerdown', onFirst)
    window.removeEventListener('keydown', onFirst)
  }
  window.addEventListener('pointerdown', onFirst, { once: false })
  window.addEventListener('keydown', onFirst, { once: false })
}

/* ---------------- 开关 ---------------- */

export function isSfxOn() {
  return state.sfx
}

export function isMusicOn() {
  return state.music
}

export function toggleSfx() {
  state.sfx = !state.sfx
  flag(SFX_KEY, state.sfx)
  return state.sfx
}

export function toggleMusic() {
  state.music = !state.music
  flag(MUSIC_KEY, state.music)
  if (state.music) playBgm()
  else pauseBgm()
  return state.music
}
