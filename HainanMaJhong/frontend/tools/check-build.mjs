/*
 * 递归校验构建产物：从 index.html 出发，把所有能被引用到的资源全部走一遍，
 * 确认每一个都能取到（200），且 chunk 之间的 import 路径真实存在。
 *
 *   node tools/check-build.mjs [baseUrl]
 *
 * 为什么需要它：Vite 把路由做成动态 import，会产生 Login/Lobby/Room/GameTable/Records
 * 等独立 chunk。只检查 index.html 里那两个入口文件是不够的 ——
 * 某个 chunk 404 或路径写错时，页面会「能打开但点了没反应」（路由懒加载失败），
 * 这正是最难靠肉眼发现的一类问题。
 *
 * 不传 baseUrl 时只做静态分析（校验 dist 内的引用关系）；
 * 传了则同时发 HTTP 请求验证服务端真的能返回。
 */

import { readFileSync, existsSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join, resolve } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const staticDir = resolve(here, '..', '..', 'src', 'main', 'resources', 'static')
const baseUrl = (process.argv[2] || '').replace(/\/$/, '')

let fails = 0
function ok(cond, msg) {
  if (!cond) {
    fails++
    console.log('  [FAIL] ' + msg)
  } else {
    console.log('  [ok]   ' + msg)
  }
}

/* ---------------- 1. 静态分析：从 index.html 递归收集引用 ---------------- */

const indexPath = join(staticDir, 'index.html')
console.log('=== 1. index.html ===')
if (!existsSync(indexPath)) {
  console.log('  [FAIL] 找不到 ' + indexPath + '（先跑 pnpm build）')
  process.exit(1)
}
const indexHtml = readFileSync(indexPath, 'utf8')
ok(indexHtml.includes('id="app"'), 'index.html 含 #app 挂载点')
ok(indexHtml.includes('type="module"'), 'index.html 用 ES module 加载入口')

/* 收集 index.html 直接引用的 /assets/* */
const seen = new Set()
const queue = []
for (const m of indexHtml.matchAll(/(?:src|href)="(\/assets\/[^"]+)"/g)) {
  const p = m[1]
  if (!seen.has(p)) {
    seen.add(p)
    queue.push(p)
  }
}
ok(seen.size >= 2, `index.html 引用了 ${seen.size} 个入口资源（js + css）`)

/* 递归解析每个 chunk 里的相对 import / import() */
const missing = []
const visitedFiles = new Set()
while (queue.length) {
  const urlPath = queue.shift()
  const fileName = urlPath.replace(/^\/assets\//, '')
  const abs = join(staticDir, 'assets', fileName)
  if (!existsSync(abs)) {
    missing.push(urlPath)
    continue
  }
  if (visitedFiles.has(fileName)) continue
  visitedFiles.add(fileName)

  const src = readFileSync(abs, 'utf8')
  // Vite 产物里对其它 chunk 有两种引用形式，两种都要扫：
  //   1. 普通静态 import：  from"./Tile-xxxx.js"
  //   2. 预加载清单：       ["assets/Login-xxxx.css","assets/Lobby-xxxx.js"] —— 注意【没有】./ 前缀
  // 只扫第 1 种会把路由 CSS 误判成"孤儿产物"。
  const patterns = [
    /["'(]\.\/([A-Za-z0-9_.\-]+\.(?:js|css))["')]/g,   // ./X.js
    /["']assets\/([A-Za-z0-9_.\-]+\.(?:js|css))["']/g, // "assets/X.css"
  ]
  for (const re of patterns) {
    for (const m of src.matchAll(re)) {
      const dep = '/assets/' + m[1]
      if (!seen.has(dep)) {
        seen.add(dep)
        queue.push(dep)
      }
    }
  }
}

console.log('\n=== 2. 递归引用关系 ===')
ok(missing.length === 0, missing.length ? `有 ${missing.length} 个引用指向不存在的文件: ${missing.join(', ')}` : '所有 chunk 引用都能解析到实际文件')
console.log(`  [info] 共解析到 ${seen.size} 个资源，其中 ${visitedFiles.size} 个文件已扫描内部引用`)

/* 每个路由 chunk 是否都在（懒加载失败会导致"点了没反应"） */
console.log('\n=== 3. 路由 chunk 完整性 ===')
for (const view of ['Login', 'Lobby', 'Room', 'GameTable', 'Records']) {
  const hit = [...seen].some((p) => p.includes(`/${view}-`) && p.endsWith('.js'))
  ok(hit, `${view}.vue 的 chunk 已被引用到`)
}

/* 磁盘上的 assets 是否有多余残留 */
console.log('\n=== 4. assets 目录与引用一致 ===')
const onDisk = readdirSync(join(staticDir, 'assets')).filter((f) => /\.(js|css)$/.test(f))
const referenced = [...seen].map((p) => p.replace(/^\/assets\//, ''))
const orphans = onDisk.filter((f) => !referenced.includes(f))
ok(orphans.length === 0, orphans.length ? `有 ${orphans.length} 个孤儿产物: ${orphans.join(', ')}` : '磁盘产物与引用完全一致，无孤儿')

/* ---------------- 2. 若给了 baseUrl，实际发请求验证 ---------------- */

if (baseUrl) {
  console.log(`\n=== 5. HTTP 实测 (${baseUrl}) ===`)
  for (const p of [...seen].sort()) {
    let code = 'ERR'
    try {
      const res = await fetch(baseUrl + p)
      code = String(res.status)
    } catch (e) {
      code = 'ERR:' + e.message
    }
    ok(code === '200', `${p} -> ${code}`)
  }
} else {
  console.log('\n[info] 未传 baseUrl，跳过 HTTP 实测。用法: node tools/check-build.mjs http://127.0.0.1:8080')
}

console.log('')
if (fails) {
  console.log(`==== 失败 ${fails} 项 ====`)
  process.exit(1)
}
console.log('==== 全部通过 ====')
