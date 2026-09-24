/*
 * 牌模型自检（node 直接跑，不依赖浏览器）。
 *
 *   node tools/check-tiles.mjs
 *
 * 校验三件事：
 *   1. 42 种牌的编码与后端 HuLib.java 的约定一致
 *   2. 每种牌的元数据（中文名/花色分类）正确
 *   3. SVG 牌面绘制对全部 42 种都能产出非空内容（兜底渲染不能有空洞）
 *
 * 这是前端唯一能脱离浏览器验证的硬检查，改 tiles.js 后必须跑一遍。
 */
import {
  TILES,
  TOTAL_TILES,
  TILE_TYPES,
  HUA,
  HONOR_START,
  tileOf,
  tileName,
  tileFaceSvg,
  isFlower,
  isHonor,
  isSuited,
  groupBySuit,
  seatRing,
  windIndexOf,
} from '../src/game/tiles.js'
import { existsSync, readdirSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

let fails = 0
function ok(cond, msg) {
  if (!cond) {
    fails++
    console.log('  [FAIL] ' + msg)
  } else {
    console.log('  [ok]   ' + msg)
  }
}

console.log('=== 1. 牌种与索引布局（对齐后端 HuLib）===')
ok(TOTAL_TILES === 42, `TOTAL_TILES = 42（实际 ${TOTAL_TILES}）`)
ok(TILE_TYPES === 34, `TILE_TYPES = 34（实际 ${TILE_TYPES}）`)
ok(HUA === 34, `HUA = 34（实际 ${HUA}）`)
ok(HONOR_START === 27, `HONOR_START = 27（实际 ${HONOR_START}）`)
ok(TILES.length === 42, `TILES 长度 42（实际 ${TILES.length}）`)

console.log('\n=== 2. 各区间首尾牌的命名 ===')
const expect = [
  [0, '一万'],
  [8, '九万'],
  [9, '一筒'],
  [17, '九筒'],
  [18, '一条'],
  [26, '九条'],
  [27, '东'],
  [28, '南'],
  [29, '西'],
  [30, '北'],
  [31, '中'],
  [32, '发'],
  [33, '白'],
  [34, '春'],
  [35, '夏'],
  [36, '秋'],
  [37, '冬'],
  [38, '梅'],
  [39, '兰'],
  [40, '竹'],
  [41, '菊'],
]
for (const [id, name] of expect) {
  ok(tileName(id) === name, `索引 ${id} → ${name}（实际 ${tileName(id)}）`)
}

console.log('\n=== 3. 分类判定 ===')
ok(isFlower(34) && isFlower(41) && !isFlower(33), '花牌判定 34..41')
ok(isHonor(27) && isHonor(33) && !isHonor(26), '字牌判定 27..33')
ok(isSuited(0) && isSuited(26) && !isSuited(27), '序数牌判定 0..26')
ok(isFlower(42) === false, '越界 42 不算花牌')
ok(tileOf(42) === null && tileOf(-1) === null, '越界索引返回 null')

console.log('\n=== 4. SVG 牌面绘制（全部 42 种都要有内容）===')
let emptySvg = []
for (let i = 0; i < TOTAL_TILES; i++) {
  const svg = tileFaceSvg(i)
  if (!svg || svg.length < 10) emptySvg.push(i)
}
ok(emptySvg.length === 0, `42 种牌面均有绘制内容${emptySvg.length ? '（缺: ' + emptySvg.join(',') + '）' : ''}`)
ok(tileFaceSvg(42) === '', '越界索引的牌面为空字符串')

console.log('\n=== 5. 按花色分组 ===')
const groups = groupBySuit([0, 9, 18, 27, 34, 1, 10, 19, 28, 35])
ok(groups.length === 5, `五组（万筒条字花），实际 ${groups.length}`)
ok(
  groups.map((g) => g.suit).join(',') === 'wan,tong,tiao,honor,flower',
  `组顺序为 万筒条字花（实际 ${groups.map((g) => g.suit).join(',')}）`,
)
ok(
  groups.every((g) => g.tiles.every((t, i, a) => i === 0 || a[i - 1] <= t)),
  '每组内部升序',
)

console.log('\n=== 6. 座位旋转（以我为视角）===')
ok(JSON.stringify(seatRing('EAST')) === JSON.stringify(['EAST', 'SOUTH', 'WEST', 'NORTH']), '东家视角不变')
ok(JSON.stringify(seatRing('SOUTH')) === JSON.stringify(['SOUTH', 'WEST', 'NORTH', 'EAST']), '南家视角：我在下，东家到左')
ok(JSON.stringify(seatRing('NORTH')) === JSON.stringify(['NORTH', 'EAST', 'SOUTH', 'WEST']), '北家视角')
ok(seatRing('BAD').length === 4, '非法座位回退为默认顺序')

console.log('\n=== 7. 风位计算（以庄为东）===')
ok(windIndexOf('EAST', 'EAST') === 0, '庄家自己 = 0')
ok(windIndexOf('SOUTH', 'EAST') === 1, '南家对东庄 = 1')
ok(windIndexOf('NORTH', 'EAST') === 3, '北家对东庄 = 3')
ok(windIndexOf('EAST', 'WEST') === 2, '东家对西庄 = 2')

console.log('\n=== 8. 牌面图体积预算（手机首屏）===')
/*
 * 这条检查是有来历的：tile_back.png 曾经是 726x976 / 1.36MB，
 * 而它只显示在 30x41（对手手牌 xs2）的格子里 —— 每边放大 24 倍，
 * 手机上表现为"进场顿一下 + 牌背先绿底后出图"。
 * 缩到 91x123 / 5KB 后加了这条预算，防止以后又塞一张大图回来。
 *
 * 预算只算【真的会被下载的图】：牌背 + 42 张牌面。
 * img/ 下没有引用的历史图（比如 table_bg.png）不进预算，只提示 ——
 * 它们不占用户流量，只是把 jar 撑大。
 */
const staticDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'main', 'resources', 'static')
const imgDir = join(staticDir, 'img')
const tileFaceDir = join(imgDir, 'tiles')

const backPath = join(imgDir, 'tile_back.png')
ok(existsSync(backPath), 'img/tile_back.png 存在')
let backKb = 0
if (existsSync(backPath)) {
  backKb = statSync(backPath).size / 1024
  console.log(`  [info] tile_back.png = ${backKb.toFixed(1)} KB`)
  ok(backKb <= 64, `牌背图 ≤ 64KB（显示尺寸只有 30x41，不需要大图；实际 ${backKb.toFixed(1)}KB）`)
}

let faces = 0
let faceKb = 0
if (existsSync(tileFaceDir)) {
  const files = readdirSync(tileFaceDir).filter((f) => f.endsWith('.png'))
  faces = files.length
  for (const f of files) faceKb += statSync(join(tileFaceDir, f)).size / 1024
  console.log(`  [info] 牌面图 ${faces} 张，合计 ${faceKb.toFixed(1)} KB，平均 ${(faceKb / Math.max(1, faces)).toFixed(1)} KB`)
}
ok(faces === 42, `牌面图 42 张（实际 ${faces}）`)
const referencedKb = backKb + faceKb
console.log(`  [info] 会被下载的图合计 = ${(referencedKb / 1024).toFixed(2)} MB`)
ok(referencedKb <= 512, `牌面 + 牌背 ≤ 512KB（实际 ${referencedKb.toFixed(0)}KB）`)

// 没被引用的历史图：只提示（不进预算，但会白占 jar）
if (existsSync(imgDir)) {
  const loose = readdirSync(imgDir).filter((f) => {
    const p = join(imgDir, f)
    return statSync(p).isFile() && f !== 'tile_back.png'
  })
  for (const f of loose) {
    const kb = statSync(join(imgDir, f)).size / 1024
    console.log(`  [提示] img/${f}（${kb.toFixed(0)}KB）没有被前端引用，旧版遗留，可删`)
  }
}

console.log('')
if (fails) {
  console.log(`==== 失败 ${fails} 项 ====`)
  process.exit(1)
}
console.log('==== 全部通过 ====')
