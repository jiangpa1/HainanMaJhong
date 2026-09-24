/*
 * 房间规则（创建房间弹窗可设项）。
 *
 * 字段名必须与后端 rules/HainanConfig.java 的 public 字段【逐字一致】——
 * 建房时这份对象会被原样 JSON 序列化发过去
 *   { type: 'createRoom', config: {...} }
 * 后端 MultiPlayerRoomServiceImpl.create 里用 Jackson convertValue 直接映射成
 * HainanConfig，名字对不上不会报错，只会【静默用默认值】，也就是"我明明改了
 * 暗杠分，打完还是 2 分"——这是最难查的一类问题。所以这里和 HainanConfig 一一对照。
 *
 * 取值也与后端字段默认值保持一致（HainanConfig 里的初始值）。
 */

/** 默认规则（与 HainanConfig 的字段初始值逐项对应）。 */
export const ROOM_CFG_DEFAULTS = Object.freeze({
  basePoint: 1, // 底分
  fanGate: true, // 有番才能胡

  gangMing: 1, // 明杠
  gangBu: 1, // 补杠
  gangAn: 2, // 暗杠

  flowerTrue: 1, // 真花（春夏秋冬 或 梅兰竹菊 一套）
  flowerFake: 0, // 假花（数字 1~4 凑齐）
  pairTrueFlower: 0, // 真对花（摸齐本人两个位置花）
  pairFakeFlower: 0, // 假对花（同号一对但非本人位置）

  multPing: 1, // 平胡
  multPeng: 3, // 碰碰胡
  multHun: 2, // 混一色
  multQing: 4, // 清一色
  multQiDui: 3, // 七对
  multLongQi: 5, // 龙七对
  multShiSanYao: 13, // 十三幺
  multTianHu: 5, // 天胡
  multTianTing: 4, // 天听
  multDiTing: 2, // 地听
})

/**
 * 弹窗分组（顺序即显示顺序）。
 * 每组：{ title, hint?, rows: [[key, 标签, 说明?]] }
 * 说明文案照抄规则原文，避免玩家看不懂"真对花"和"假对花"的区别。
 */
export const ROOM_CFG_GROUPS = [
  {
    title: '底分',
    rows: [['basePoint', '底分', '连庄时按倍数累加，非庄胡下庄后重置']],
  },
  {
    title: '杠分值',
    hint: '三种杠均三家各付一份，含庄的那几笔再乘底分',
    rows: [
      ['gangMing', '明杠'],
      ['gangBu', '补杠'],
      ['gangAn', '暗杠'],
    ],
  },
  {
    title: '花杠分',
    hint: '每局结算的额外花分',
    rows: [
      ['flowerTrue', '真花', '春夏秋冬 或 梅兰竹菊 凑齐一套'],
      ['flowerFake', '假花', '数字 1~4 凑齐'],
      ['pairTrueFlower', '真对花', '抓到本人两个位置花'],
      ['pairFakeFlower', '假对花', '同号一对但不是本人位置'],
    ],
  },
  {
    title: '番型倍数',
    hint: '命中多个相加；天听/地听需在可报听时点「报听」，报听后锁手',
    rows: [
      ['multPing', '平胡'],
      ['multPeng', '碰碰胡'],
      ['multHun', '混一色'],
      ['multQing', '清一色'],
      ['multQiDui', '七对'],
      ['multLongQi', '龙七对'],
      ['multShiSanYao', '十三幺'],
      ['multTianHu', '天胡', '当局从未出牌，摸牌即胡'],
      ['multTianTing', '天听', '自己只出过 1 张后，可报听'],
      ['multDiTing', '地听', '庄首张起、庄第 4 张前无人吃碰杠，可报听'],
    ],
  },
]

/** localStorage 键：与旧实现、Room.vue 三处必须一致，否则改了规则建房时读不到。 */
export const ROOM_CFG_KEY = 'hnRoomConfig'

/**
 * 读本地保存的规则，缺失/损坏时回退默认值。
 *
 * 逐字段做类型校正：localStorage 里的东西可能是任何形状（旧版本留下的、
 * 手工改过的），直接丢给后端会被 convertValue 变成一堆 0。
 */
export function loadRoomCfg() {
  let raw = null
  try {
    raw = JSON.parse(localStorage.getItem(ROOM_CFG_KEY) || 'null')
  } catch (e) {
    raw = null
  }
  const src = raw && typeof raw === 'object' ? raw : {}
  const out = { ...ROOM_CFG_DEFAULTS }
  for (const k of Object.keys(ROOM_CFG_DEFAULTS)) {
    if (typeof ROOM_CFG_DEFAULTS[k] === 'boolean') {
      // 兼容旧实现：fanGate 可能存成 0/1
      out[k] = src[k] === false || src[k] === 0 ? false : true
    } else {
      const v = parseInt(src[k], 10)
      out[k] = Number.isFinite(v) && v >= 0 ? v : ROOM_CFG_DEFAULTS[k]
    }
  }
  return out
}

/** 保存规则。 */
export function saveRoomCfg(cfg) {
  try {
    localStorage.setItem(ROOM_CFG_KEY, JSON.stringify(cfg))
  } catch (e) {
    /* 隐私模式下写不了，忽略：本次建房仍然用内存里的这份 */
  }
}

/** 一份给"创建房间"用的纯配置对象（只含后端认识的字段）。 */
export function roomCfgPayload(cfg) {
  const src = cfg || {}
  const out = {}
  for (const k of Object.keys(ROOM_CFG_DEFAULTS)) {
    out[k] = src[k] === undefined ? ROOM_CFG_DEFAULTS[k] : src[k]
  }
  out.enabled = true
  return out
}
