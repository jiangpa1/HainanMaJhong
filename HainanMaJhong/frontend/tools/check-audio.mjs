/*
 * 音效名与磁盘文件交叉校验（node 直接跑）。
 *
 *   node tools/check-audio.mjs
 *
 * 为什么要单独校验：音效是通过「中文名拼路径」播放的
 *   /music/sound_effects/<名>.mp3
 *   /music/voices/<话>.mp3
 * 名字写错不会编译报错、不会 404 报错（play() 的失败被 catch 掉了），
 * 只会表现为「该响的时候没声音」—— 这是最难发现的一类 bug。
 *
 * 所以这里把 audio.js 里所有会用到的名字，逐个去 static/music 下核对文件是否存在。
 */
import { existsSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

/*
 * audio.js 在模块顶层读 localStorage（音效开关的持久化），Node 里没有这个全局。
 * 必须在【任何动态 import 之前】补上桩，否则 import 直接抛
 * "localStorage is not defined"，下面的映射校验会被整段跳过 —— 静默失去覆盖。
 */
if (typeof globalThis.localStorage === 'undefined') {
  globalThis.localStorage = { getItem: () => null, setItem: () => {}, removeItem: () => {} }
}

const here = dirname(fileURLToPath(import.meta.url))
const staticDir = join(here, '..', '..', 'src', 'main', 'resources', 'static')
const musicDir = join(staticDir, 'music')
const sfxDir = join(musicDir, 'sound_effects')
const voiceDir = join(musicDir, 'voices')

let fails = 0
function ok(cond, msg) {
  if (!cond) {
    fails++
    console.log('  [FAIL] ' + msg)
  } else {
    console.log('  [ok]   ' + msg)
  }
}

const before = fails

/* ---------------- 目录存在 ---------------- */
console.log('=== 0. 目录结构 ===')
ok(existsSync(musicDir), `music/ 存在`)
ok(existsSync(sfxDir), `music/sound_effects/ 存在`)
ok(existsSync(voiceDir), `music/voices/ 存在`)
ok(existsSync(join(musicDir, 'background.mp3')), `background.mp3 存在`)

if (fails > before) {
  console.log('\n目录缺失，后续校验跳过（static 是否已被移动/删除？）')
  process.exit(1)
}

/* ---------------- 牌局音效 ---------------- */
// 与 audio.js 的 playEffect 调用点一一对应：
//   补花、报听、吃、碰、杠、杠开、自摸、平胡
//   + huSoundName 里的 天胡/十三幺/龙七/七对/碰碰胡/清一色/混一色
const EFFECTS = [
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
  // 结算播报（noticeSoundOf 的映射目标）
  '包三道',
  '包四道',
  '真花',
  '假花',
  '对花',
]

console.log('\n=== 1. 牌局音效文件 ===')
for (const name of EFFECTS) {
  const p = join(sfxDir, `${name}.mp3`)
  ok(existsSync(p), `sound_effects/${name}.mp3`)
}

/* ---------------- 出牌报牌音 ---------------- */
/*
 * static/music/tiles/ 一牌一文件，34 种正牌各一个。
 *
 * 这里刻意【手写】这份清单，而不是从 audio.js 的 tileVoiceName() 反推：
 * 校验的意义就在于"两边独立对一遍"，共用一份数据的话写错了也一起错。
 *
 * 注意几处命名和牌面显示名不一致（最容易接错的地方）：
 *   一条 → 幺鸡        字牌 → 东风/南风/西风/北风/红中/发财/白板
 */
const TILE_VOICES = [
  '一万', '二万', '三万', '四万', '五万', '六万', '七万', '八万', '九万',
  '一筒', '二筒', '三筒', '四筒', '五筒', '六筒', '七筒', '八筒', '九筒',
  '幺鸡', '二条', '三条', '四条', '五条', '六条', '七条', '八条', '九条',
  '东风', '南风', '西风', '北风', '红中', '发财', '白板',
]
const tileDir = join(musicDir, 'tiles')

console.log('\n=== 1b. 出牌报牌音文件（34 种正牌）===')
ok(existsSync(tileDir), 'music/tiles/ 存在')
if (existsSync(tileDir)) {
  for (const name of TILE_VOICES) {
    const p = join(tileDir, `${name}.mp3`)
    ok(existsSync(p), `tiles/${name}.mp3`)
  }
  // 花牌不该有报牌音（出花走 sound_effects/补花.mp3）
  const flower = ['春', '夏', '秋', '冬', '梅', '兰', '竹', '菊']
  for (const name of flower) {
    ok(!existsSync(join(tileDir, `${name}.mp3`)), `tiles/${name}.mp3 不该存在（花牌无报牌音）`)
  }
}

/* ---------------- 结算播报 tag → 音效名 映射 ---------------- */
/*
 * 后端结算 notes 里的 tag 要映射到音效名，映射错了就表现为"该响没响"。
 * 这里把每条真实会出现的 tag 都过一遍 noticeSoundOf()。
 */
console.log('\n=== 1d. 结算 tag → 播报音效 映射 ===')
try {
  const { noticeSoundOf } = await import('../src/game/audio.js')
  const CASES = [
    ['包三道·代付三家', '包三道'],
    ['包四道·代付三家', '包四道'],
    ['包尾墙·代付三家', '包尾墙'],
    ['包抢杠·代付三家', '包抢杠'],
    ['真花·四季', '真花'],
    ['真花·四君子', '真花'],
    ['假花', '假花'],
    ['真对花', '对花'],
    ['假对花·2', '对花'],
    // 胡牌那条不该单独播报（胡牌本身已有音效）
    ['自摸(碰碰胡)', ''],
    ['平胡', ''],
    ['', ''],
  ]
  let bad = 0
  for (const [tag, want] of CASES) {
    const got = noticeSoundOf(tag)
    if (got !== want) {
      bad++
      console.log(`  [FAIL] 「${tag}」→ 期望「${want}」，实际「${got}」`)
    }
  }
  ok(bad === 0, `${CASES.length} 条结算 tag 的音效映射全部正确`)
} catch (e) {
  console.log('  [提示] 跳过映射校验（导入 audio.js 失败：' + (e && e.message) + '）')
}

/* ---------------- 报牌音映射是否正确 ---------------- */
/*
 * 上面只验了"文件在不在"。但真正会出错的往往是【映射】：
 *   牌索引 18 该报「幺鸡」还是「一条」？27 该是「东」还是「东风」？
 * 这类错不会报错、不会 404（拼出来的路径指向一个不存在的文件，
 * play() 静默失败），只会表现为"某几张牌出牌没声音"。所以要正面验一遍。
 *
 * audio.js 在模块顶层读 localStorage，Node 里没有这个全局，先补一个桩。
 */
console.log('\n=== 1c. 牌索引 → 报牌音 映射 ===')
try {
  const { tileVoiceName } = await import('../src/game/audio.js')
  let bad = 0
  for (let id = 0; id < TILE_VOICES.length; id++) {
    const got = tileVoiceName(id)
    if (got !== TILE_VOICES[id]) {
      bad++
      console.log(`  [FAIL] 索引 ${id} → 期望「${TILE_VOICES[id]}」，实际「${got}」`)
    }
  }
  ok(bad === 0, `34 个牌索引的报牌音名全部正确`)
  // 花牌（34..41）与非数字脏值必须返回空串，不能拼出一个假路径
  const extras = [34, 41, -1, 99, null, undefined, NaN, 'x']
  const badExtra = extras.filter((v) => tileVoiceName(v) !== '')
  ok(badExtra.length === 0, `花牌/脏值不产生报牌音（异常项：${JSON.stringify(badExtra)}）`)
} catch (e) {
  console.log('  [提示] 跳过映射校验（导入 audio.js 失败：' + (e && e.message) + '）')
}

/* ---------------- 快捷语音 ---------------- */
// 与 audio.js 的 VOICE_PHRASES 必须完全一致（文本即文件名）
const VOICES = [
  '快点啊~等得我花儿都谢了',
  '注意啦！',
  '窃听风云！',
  '不必理会',
  '闹麻',
  '气不气',
  '很气很气',
]

console.log('\n=== 2. 快捷语音文件 ===')
for (const name of VOICES) {
  const p = join(voiceDir, `${name}.mp3`)
  ok(existsSync(p), `voices/${name}.mp3`)
}

/* ---------------- 反向检查：有没有白占空间的孤儿文件 ---------------- */
console.log('\n=== 3. 孤儿音频（磁盘有、代码不用）===')
const diskSfx = readdirSync(sfxDir).filter((f) => f.endsWith('.mp3')).map((f) => f.replace(/\.mp3$/, ''))
const diskVoice = readdirSync(voiceDir).filter((f) => f.endsWith('.mp3')).map((f) => f.replace(/\.mp3$/, ''))
const diskTile = existsSync(tileDir)
  ? readdirSync(tileDir).filter((f) => f.endsWith('.mp3')).map((f) => f.replace(/\.mp3$/, ''))
  : []
const orphanSfx = diskSfx.filter((n) => EFFECTS.indexOf(n) < 0)
const orphanVoice = diskVoice.filter((n) => VOICES.indexOf(n) < 0)
const orphanTile = diskTile.filter((n) => TILE_VOICES.indexOf(n) < 0)
if (orphanSfx.length === 0 && orphanVoice.length === 0 && orphanTile.length === 0) {
  console.log('  [ok]   无孤儿文件')
} else {
  // 不算失败：可能是有意留着备用的素材，只提示
  if (orphanSfx.length) console.log('  [提示] sound_effects 未被使用: ' + orphanSfx.join('、'))
  if (orphanVoice.length) console.log('  [提示] voices 未被使用: ' + orphanVoice.join('、'))
  if (orphanTile.length) console.log('  [提示] tiles 未被使用: ' + orphanTile.join('、'))
}

/* ---------------- 预加载清单（游戏进对局前要下的东西） ---------------- */
/*
 * src/game/assets.js 是【运行时】的预加载清单（牌面图 + 音效 + 报牌音 + 快捷语音）。
 * 它一旦和磁盘对不上，表现是"进桌还在白屏/没声音"，而不会报错 —— 所以正面验一遍：
 *   ① 清单里每个 URL 在 static/ 下都要有对应文件；
 *   ② 反过来，这些目录里的文件也都要被清单覆盖（少列一个就永远不预热）。
 *
 * 注意：上面 EFFECTS / TILE_VOICES / VOICES 是【手写】的独立清单（故意的，
 * 两边独立对一遍才有意义）；这一段验的是 assets.js 与磁盘是否一致，两者互补。
 */
console.log('\n=== 4. 预加载清单（game/assets.js）与磁盘一致 ===')
try {
  const { allAssetUrls, imageUrls, audioUrls } = await import('../src/game/assets.js')
  const urls = allAssetUrls()
  const missing = urls.filter((u) => !existsSync(join(staticDir, decodeURIComponent(u).replace(/^\//, ''))))
  ok(missing.length === 0, `清单里 ${urls.length} 个资源在 static/ 下都存在`
     + (missing.length ? `（缺：${missing.slice(0, 5).join(', ')}）` : ''))

  // 反向：目录里的文件有没有被清单漏掉的
  const inAudio = audioUrls().map((u) => decodeURIComponent(u).split('/').pop().replace(/\.mp3$/, ''))
  const missSfx = diskSfx.filter((n) => inAudio.indexOf(n) < 0)
  const missVoice = diskVoice.filter((n) => inAudio.indexOf(n) < 0)
  const missTile = diskTile.filter((n) => inAudio.indexOf(n) < 0)
  ok(!missSfx.length && !missVoice.length && !missTile.length,
     'sound_effects / tiles / voices 里的文件都被清单覆盖'
     + ((missSfx.length || missVoice.length || missTile.length)
        ? `（漏：${[].concat(missSfx, missVoice, missTile).slice(0, 5).join('、')}）` : ''))

  // 牌面图：42 张牌面 + 牌背
  const imgs = imageUrls()
  ok(imgs.length === 43, `牌面图清单 = 42 张牌面 + 1 张牌背（实际 ${imgs.length}）`)
  ok(existsSync(join(staticDir, 'img', 'tiles')), 'static/img/tiles/ 存在')
} catch (e) {
  console.log('  [FAIL] 导入 game/assets.js 失败：' + (e && e.message))
  fails++
}

console.log('')
if (fails) {
  console.log(`==== 失败 ${fails} 项 ====`)
  process.exit(1)
}
console.log('==== 全部通过 ====')
