/*
 * 牌模型：海南麻将 42 种牌（含 8 张花牌），与后端 HuLib 严格一致。
 *
 * 后端常量（HuLib.java）：
 *   TILE_TYPES  = 34   // 万筒条字
 *   HUA         = 34   // 花牌起始下标
 *   TOTAL_TILES = 42   // 34 + 8 花牌
 *
 * 索引布局（这是前后端唯一的"线协议"，改后端就必须同步改这里）：
 *   0..8    万  一万 ~ 九万
 *   9..17   筒  一筒 ~ 九筒
 *   18..26  条  一条 ~ 九条
 *   27..33  字  东 南 西 北 中 发 白
 *   34..41  花  春 夏 秋 冬 梅 兰 竹 菊   ← 只计番，不参与成牌
 *
 * 注意：整副牌 144 张 = 34 种正牌 × 4 + 8 张花牌 × 1。
 * 花牌每种只有 1 张，所以不能按"每种 4 张"推。
 */

export const TILE_TYPES = 34
export const HUA = 34
export const TOTAL_TILES = 42

/** 一门牌 9 张时，第 n 张（0 基）的中文数字。 */
const CN_NUM = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十']

/** 花色定义：起点下标 + 后缀字。万筒条各 9 张，可吃。 */
export const SUITS = [
  { key: 'wan', start: 0, size: 9, suffix: '万' },
  { key: 'tong', start: 9, size: 9, suffix: '筒' },
  { key: 'tiao', start: 18, size: 9, suffix: '条' },
]

/** 字牌：27..33，只能碰不能吃。 */
export const HONORS = ['东', '南', '西', '北', '中', '发', '白']
export const HONOR_START = 27

/** 花牌：34..41，每种 1 张，只计番。 */
export const FLOWER_NAMES = ['春', '夏', '秋', '冬', '梅', '兰', '竹', '菊']

/**
 * 40 种牌面元数据（表驱动，避免在组件里写 40 个分支）。
 * 每项：{ id, key, name, kind, suit, num, honor, flower, color }
 *   kind:   'suit' | 'honor' | 'flower'
 *   color:  'red' | 'green' | 'blue' | 'black'  —— 用于牌面文字配色
 */
export const TILES = (() => {
  const out = []

  for (const s of SUITS) {
    for (let n = 1; n <= s.size; n++) {
      const id = s.start + n - 1
      out.push({
        id,
        key: `${s.key}${n}`,
        name: `${CN_NUM[n - 1]}${s.suffix}`,
        kind: 'suit',
        suit: s.key,
        num: n,
        honor: null,
        flower: false,
        // 筒/条用图形渲染（点和条），万用汉字
        color: 'black',
      })
    }
  }

  HONORS.forEach((name, i) => {
    const id = HONOR_START + i
    // 中=红 发=绿 白=蓝，其余风牌黑
    const color = name === '中' ? 'red' : name === '发' ? 'green' : name === '白' ? 'blue' : 'black'
    out.push({
      id,
      key: `honor${i}`,
      name,
      kind: 'honor',
      suit: null,
      num: 0,
      honor: name,
      flower: false,
      color,
    })
  })

  FLOWER_NAMES.forEach((name, i) => {
    const id = HUA + i
    out.push({
      id,
      key: `flower${i}`,
      name,
      kind: 'flower',
      suit: null,
      num: 0,
      honor: null,
      flower: true,
      color: 'green',
    })
  })

  return out
})()

/** 索引 → 元数据。越界返回 null（防御脏数据，渲染时当作未知牌处理）。 */
export function tileOf(id) {
  if (typeof id !== 'number' || !Number.isInteger(id) || id < 0 || id >= TOTAL_TILES) {
    return null
  }
  return TILES[id]
}

/** 索引 → 中文名。未知返回 '?'，绝不抛异常（服务端可能传来脏值）。 */
export function tileName(id) {
  const t = tileOf(id)
  return t ? t.name : '?'
}

/** 是否花牌（后端 TurnUtils.isFlower 的等价实现）。 */
export function isFlower(id) {
  return typeof id === 'number' && id >= HUA && id < TOTAL_TILES
}

/** 是否字牌（27..33）。 */
export function isHonor(id) {
  return typeof id === 'number' && id >= HONOR_START && id < HUA
}

/** 是否序数牌（0..26），序数牌可吃。 */
export function isSuited(id) {
  return typeof id === 'number' && id >= 0 && id < HONOR_START
}

/**
 * 排序：万 → 筒 → 条 → 字 → 花，同花色按点数。
 * 引擎下发的手牌本身已排序，但前端做过合并/插入后需要重新排。
 */
export function sortTiles(ids) {
  if (!Array.isArray(ids)) return []
  return ids.slice().sort((a, b) => a - b)
}

/**
 * 把牌按花色分组，供"手牌分堆显示"用。
 * 返回 [{ suit: 'wan'|'tong'|'tiao'|'honor'|'flower', tiles: [id...] }]，空的组不返回。
 */
export function groupBySuit(ids) {
  const order = ['wan', 'tong', 'tiao', 'honor', 'flower']
  const bucket = { wan: [], tong: [], tiao: [], honor: [], flower: [] }
  for (const id of Array.isArray(ids) ? ids : []) {
    const t = tileOf(id)
    if (!t) continue
    bucket[t.kind === 'suit' ? t.suit : t.kind].push(id)
  }
  return order
    .filter((k) => bucket[k].length > 0)
    .map((k) => ({ suit: k, tiles: sortTiles(bucket[k]) }))
}

/** 座位顺序（东南西北），与后端 Seat 枚举一致。 */
export const SEATS = ['EAST', 'SOUTH', 'WEST', 'NORTH']

/** 座位中文名。 */
export const SEAT_CN = { EAST: '东', SOUTH: '南', WEST: '西', NORTH: '北' }

// ==================== 牌面绘制（SVG 字符串） ====================
//
// 牌面坐标系固定为 34 x 46（宽 x 高），外层容器用 CSS 缩放。
// 这样同一套几何数据在桌面/手机上只差一个 transform，不会出现半像素毛边。
//
// 约定：
//   万 —— 上面中文数字、下面「万」字（传统写法，最易识别）
//   筒 —— 画圆饼，按 1..9 的常见排布排列；1筒画大圆，传统上带同心环
//   条 —— 画竹条；1条传统是一只鸟，这里用竖条 + 红头近似，避免自绘复杂图案
//   字 —— 单个汉字，中/发/白按传统配色（红/绿/蓝）
//   花 —— 汉字 + 角标，说明只计番不成牌
//
// 颜色沿用传统麻将：条 1-5 绿、6-9 红；筒以蓝黑为主、1筒红。

const TONG_COLOR = '#1f5fa8'
const TIAO_GREEN = '#1f7a3d'
const TIAO_RED = '#b3232a'

/** 筒子的点位排布（9 个点以内的经典布局），坐标为 34x46 内的相对位置。 */
const TONG_LAYOUT = {
  1: [[17, 23]],
  2: [[17, 13], [17, 33]],
  3: [[9, 11], [17, 23], [25, 35]],
  4: [[11, 13], [23, 13], [11, 33], [23, 33]],
  5: [[11, 12], [23, 12], [17, 23], [11, 34], [23, 34]],
  6: [[11, 10], [23, 10], [11, 23], [23, 23], [11, 36], [23, 36]],
  7: [[9, 9], [17, 9], [25, 9], [11, 20], [23, 20], [11, 31], [23, 31]],
  8: [[11, 8], [23, 8], [11, 18], [23, 18], [11, 28], [23, 28], [11, 38], [23, 38]],
  9: [[9, 11], [17, 11], [25, 11], [9, 23], [17, 23], [25, 23], [9, 35], [17, 35], [25, 35]],
}

/** 画一枚筒（圆饼）。1筒画同心双环，其余画实心环。 */
function tongDot(cx, cy, r) {
  return (
    `<circle cx="${cx}" cy="${cy}" r="${r}" fill="#fff" stroke="${TONG_COLOR}" stroke-width="1.4"/>` +
    `<circle cx="${cx}" cy="${cy}" r="${r * 0.42}" fill="${TONG_COLOR}"/>`
  )
}

/**
 * 条子的排布：用竖竹条表示，1条单独画成带红头的样式。
 * 与筒不同，条按"列"排布更像传统牌面。
 */
function tiaoSticks(n, color) {
  // 每种数量的列/行布局（列数, 每列根数）
  const layout = {
    1: [1, 1],
    2: [1, 2],
    3: [1, 3],
    4: [2, 2],
    5: [2, 3],
    6: [2, 3],
    7: [3, 3],
    8: [3, 3],
    9: [3, 3],
  }
  const [cols] = layout[n] || [1, 1]
  const per = Math.ceil(n / cols)
  let out = ''
  let drawn = 0
  const colW = 26 / cols
  for (let c = 0; c < cols && drawn < n; c++) {
    const cx = 17 - 13 + colW * (c + 0.5)
    for (let r = 0; r < per && drawn < n; r++) {
      const cy = 23 - ((per - 1) * 9) / 2 + r * 9
      out += `<rect x="${cx - 1.6}" y="${cy - 3.6}" width="3.2" height="7.2" rx="1.4" fill="${color}"/>`
      out += `<rect x="${cx - 1.6}" y="${cy - 1.2}" width="3.2" height="2.4" fill="#fff" opacity="0.55"/>`
      drawn++
    }
  }
  return out
}

/** 万子：上中文数字、下「万」。 */
function wanFace(n, color) {
  return (
    `<text x="17" y="21" text-anchor="middle" font-size="17" font-weight="700" fill="${color}">${CN_NUM[n - 1]}</text>` +
    `<text x="17" y="40" text-anchor="middle" font-size="17" font-weight="700" fill="${color}">万</text>`
  )
}

/** 生成某个牌索引的内部 SVG 图形（不含牌身与高光）。 */
export function tileFaceSvg(id) {
  const t = tileOf(id)
  if (!t) return ''

  if (t.kind === 'suit' && t.suit === 'wan') {
    return wanFace(t.num, '#1a1a1a')
  }

  if (t.kind === 'suit' && t.suit === 'tong') {
    if (t.num === 1) {
      // 1筒：大圆 + 同心环，传统上最醒目
      return (
        `<circle cx="17" cy="23" r="12" fill="#fff" stroke="#b3232a" stroke-width="1.8"/>` +
        `<circle cx="17" cy="23" r="7.6" fill="none" stroke="${TONG_COLOR}" stroke-width="1.6"/>` +
        `<circle cx="17" cy="23" r="3.2" fill="${TONG_COLOR}"/>`
      )
    }
    const pts = TONG_LAYOUT[t.num] || TONG_LAYOUT[1]
    const r = t.num >= 7 ? 3.4 : t.num >= 5 ? 4 : 4.6
    return pts.map(([cx, cy]) => tongDot(cx, cy, r)).join('')
  }

  if (t.kind === 'suit' && t.suit === 'tiao') {
    if (t.num === 1) {
      // 1条：竹节 + 红头，近似传统的"幺鸡"而不必画鸟
      return (
        `<rect x="15.4" y="8" width="3.2" height="30" rx="1.6" fill="${TIAO_GREEN}"/>` +
        `<rect x="15.4" y="12" width="3.2" height="2.6" fill="#fff" opacity="0.6"/>` +
        `<rect x="15.4" y="21" width="3.2" height="2.6" fill="#fff" opacity="0.6"/>` +
        `<rect x="15.4" y="30" width="3.2" height="2.6" fill="#fff" opacity="0.6"/>` +
        `<circle cx="17" cy="7" r="3.2" fill="${TIAO_RED}"/>`
      )
    }
    return tiaoSticks(t.num, t.num >= 6 ? TIAO_RED : TIAO_GREEN)
  }

  if (t.kind === 'honor') {
    const fill =
      t.honor === '中' ? '#b3232a' : t.honor === '发' ? '#1f7a3d' : t.honor === '白' ? '#1f5fa8' : '#1a1a1a'
    // 白板传统上画空框，但为了可读性仍写「白」字并加内框
    if (t.honor === '白') {
      return (
        `<rect x="7" y="9" width="20" height="28" rx="2" fill="none" stroke="${fill}" stroke-width="1.6"/>` +
        `<text x="17" y="30" text-anchor="middle" font-size="15" font-weight="700" fill="${fill}">白</text>`
      )
    }
    return `<text x="17" y="32" text-anchor="middle" font-size="24" font-weight="700" fill="${fill}">${t.honor}</text>`
  }

  if (t.kind === 'flower') {
    return (
      `<text x="17" y="27" text-anchor="middle" font-size="19" font-weight="700" fill="#1f7a3d">${t.name}</text>` +
      `<text x="17" y="40" text-anchor="middle" font-size="8" fill="#7a8b7f">花</text>`
    )
  }

  return ''
}


/**
 * 以"我"为视角重排座位：我永远在最下方，其余逆时针排开。
 *
 * 后端广播里所有座位都是服务端视角（EAST/SOUTH/WEST/NORTH 固定），
 * 前端必须按自己的座位做一次旋转，否则会看到"别人是我"。
 *
 * @returns {string[]} 长度 4，[0]=我(下) [1]=右 [2]=对家 [3]=左
 */
export function seatRing(mySeat) {
  const i = SEATS.indexOf(mySeat)
  if (i < 0) return SEATS.slice()
  // 服务端座位是逆时针（东→南→西→北），屏幕上方依次是 右(下家)→对家→左(上家)
  return [0, 1, 2, 3].map((k) => SEATS[(i + k) % 4])
}

/**
 * 我这张牌在"以我为庄"的风位：用于牌面角标显示东南西北圈风。
 * 与后端 DealerFlow.windIdx 的算法保持一致：以庄家为东，逆时针递增。
 */
export function windIndexOf(seat, dealer) {
  const si = SEATS.indexOf(seat)
  const di = SEATS.indexOf(dealer)
  if (si < 0 || di < 0) return 0
  return (si - di + 4) % 4
}
