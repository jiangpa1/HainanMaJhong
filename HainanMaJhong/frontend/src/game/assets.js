/*
 * 静态资源清单 —— 进对局前要预加载的东西。
 *
 * 为什么要这份清单：
 *   手机第一次进对局时，牌面图和音效都还没下载。表现是"牌是白的/闪一下才出来"、
 *   "第一次吃碰杠没有声音"（音频要等用户手势后现下，往往就错过了那一下）。
 *   所以在【进对局之前】把这些文件先拉一遍，进桌时全部命中缓存。
 *
 * ⚠️ 这份清单必须与 static/ 下的真实文件一致：
 *    tools/check-audio.mjs 里有一段专门核对它（少一个文件、多一个文件都会报错），
 *    改这里的名字/数量后跑 `pnpm check` 就能立刻发现。
 */

import { VOICE_PHRASES, isMusicOn } from './audio.js'

/** 牌面图：0..41 共 42 张（34 种正牌 + 8 张花牌），命名就是牌的下标 */
export const TILE_FACE_COUNT = 42

/** 牌背（对手的手牌、暗杠都用它） */
export const TILE_BACK_URL = '/img/tile_back.png'

/** 背景音乐 */
export const BGM_URL = '/music/background.mp3'

/** 牌局音效（吃碰杠、补花、报听、各种番型） */
export const SOUND_EFFECTS = [
  '补花',
  '报听',
  '吃',
  '碰',
  '杠',
  '杠开',
  '自摸',
  '平胡',
  '天胡',
  '十三幺',
  '龙七',
  '七对',
  '碰碰胡',
  '清一色',
  '混一色',
  '包三道',
  '包四道',
  '真花',
  '假花',
  '对花',
]

/**
 * 出牌报牌音：34 种正牌一牌一个文件。
 *
 * 几处命名和牌面显示名不一致（最容易接错的地方）：
 *   一条 → 幺鸡；字牌 → 东风/南风/西风/北风/红中/发财/白板
 * 花牌没有报牌音（出花走 sound_effects/补花.mp3）。
 */
export const TILE_VOICES = [
  '一万', '二万', '三万', '四万', '五万', '六万', '七万', '八万', '九万',
  '一筒', '二筒', '三筒', '四筒', '五筒', '六筒', '七筒', '八筒', '九筒',
  '幺鸡', '二条', '三条', '四条', '五条', '六条', '七条', '八条', '九条',
  '东风', '南风', '西风', '北风', '红中', '发财', '白板',
]

/** 牌面图 URL 列表（牌背放最前：它最大、也最先要用到） */
export function imageUrls() {
  const list = [TILE_BACK_URL]
  for (let i = 0; i < TILE_FACE_COUNT; i++) {
    list.push(`/img/tiles/${i}.png`)
  }
  return list
}

/**
 * 音频 URL 列表，**按重要性排序**：小音效 → 报牌音 → 快捷语音 → 背景音乐。
 *
 * 预加载是顺序取队列的（并发 4），所以顺序就是优先级：
 * 背景音乐 2.4MB 放最后，别让它挡住"出牌就有声"这件事。
 */
export function audioUrls() {
  const list = []
  for (const n of SOUND_EFFECTS) {
    list.push(`/music/sound_effects/${encodeURIComponent(n)}.mp3`)
  }
  for (const n of TILE_VOICES) {
    list.push(`/music/tiles/${encodeURIComponent(n)}.mp3`)
  }
  for (const n of VOICE_PHRASES) {
    list.push(`/music/voices/${encodeURIComponent(n)}.mp3`)
  }
  /*
   * 背景音乐 2.4MB，是全部资源里最大的一块。用户关掉了音乐就别下它了
   * （省一趟 2.4MB 的手机流量）；哪天他再打开，playBgm 会按需加载。
   */
  if (isMusicOn()) {
    list.push(BGM_URL)
  }
  return list
}

/** 全部要预加载的资源 */
export function allAssetUrls() {
  return imageUrls().concat(audioUrls())
}
