/*
 * 清理上一次构建的哈希产物。
 *
 * 为什么需要它：vite.config.js 里 emptyOutDir 必须是 false
 * （因为 static/ 下还有 img/、music/、apk/ 这些不归前端构建管的东西，
 *  一旦清空就是数据丢失）。代价就是旧的 hash 文件名会一直累积，
 *  24 个 chunk 越堆越多。所以每次构建前只清 assets/ 这一层。
 *
 * 只删 assets/ 下的文件，不动 assets/ 目录本身，也不碰 static/ 的其它内容。
 */
import { existsSync, readdirSync, rmSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const assetsDir = join(here, '..', '..', 'src', 'main', 'resources', 'static', 'assets')

if (!existsSync(assetsDir)) {
  console.log('[clean] assets/ 不存在，跳过')
  process.exit(0)
}

let removed = 0
for (const name of readdirSync(assetsDir)) {
  const p = join(assetsDir, name)
  try {
    rmSync(p, { recursive: statSync(p).isDirectory(), force: true })
    removed++
  } catch (e) {
    console.warn('[clean] 删除失败 ' + name + ': ' + e.message)
  }
}

// 顺带清掉旧入口的残留（index.html 会被 vite 覆盖，这里只是保证一致）
console.log(`[clean] 已清理 assets/ 下 ${removed} 项`)
