/*
 * 资源预加载：进对局之前把牌面图、音效、报牌音、快捷语音全部拉一遍。
 *
 * 目的（都是实测会遇到的问题）：
 *   1. 手机第一次进桌，牌面图是现下的 —— 会看到"牌先白一下再出图"；
 *   2. 音效更惨：浏览器要求先有用户手势才允许播放，现下现放往往就错过了那一响；
 *   3. 断断续续的请求还会和牌局消息抢带宽。
 * 先把它们放进 HTTP 缓存，进桌时全部命中缓存，等于"秒出牌、准时有声"。
 *
 * 设计要点：
 *   · 模块级单例：整个应用只跑一次（大厅先热身、房间页看进度、牌桌兜底都是同一个 promise）。
 *   · 并发 4：手机带宽有限，一次几十个请求会把首屏挤住。
 *   · 单项超时 10s：某个文件缺失/网络抖动不能把"开始对局"永远卡住 —— 记进 failed 继续走。
 *   · 失败【不阻塞】开局：缺一张图是降级（Tile.vue 会退回矢量牌面），不是不能玩。
 */

import { allAssetUrls } from './assets.js'

/*
 * 并发压到 3：手机上同时开 4~6 个连接会把首屏和牌局消息的带宽都挤掉，
 * 而这里要下的是一百多个小文件，少一路并发几乎不增加总时长。
 */
const CONCURRENCY = 3
const ITEM_TIMEOUT_MS = 6000

const listeners = new Set()

const state = {
  started: false,
  done: false,
  loaded: 0,
  total: 0,
  failed: [],
}

/** 这个 promise 就是"预加载完成"本身；重复调用拿到同一个 */
let running = null

function snapshot() {
  return {
    started: state.started,
    done: state.done,
    loaded: state.loaded,
    total: state.total,
    failed: state.failed.slice(),
    percent: state.total ? Math.round((state.loaded / state.total) * 100) : 0,
  }
}

function emit() {
  const s = snapshot()
  for (const fn of listeners) {
    try {
      fn(s)
    } catch (e) {
      /* 订阅者的错不该影响加载 */
    }
  }
}

/** 订阅进度：立即回调一次当前状态，返回取消订阅的函数。 */
export function onPreloadProgress(fn) {
  listeners.add(fn)
  fn(snapshot())
  return () => listeners.delete(fn)
}

export function preloadSnapshot() {
  return snapshot()
}

export function isPreloaded() {
  return state.done
}

/** 用 Image 拉图：顺带解码，进桌时不会再"先白一下"。 */
function loadImage(url) {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.decoding = 'async'
    img.onload = () => resolve(url)
    img.onerror = () => reject(new Error('image ' + url))
    img.src = url
  })
}

/** 音频用 fetch 灌进 HTTP 缓存（之后 new Audio(url) 直接命中缓存）。 */
function loadAudio(url) {
  return fetch(url, { cache: 'default' }).then((res) => {
    if (!res.ok) throw new Error('http ' + res.status + ' ' + url)
    // 必须把 body 读掉，否则请求可能被取消、进不了缓存
    return res.arrayBuffer().then(() => url)
  })
}

function withTimeout(promise, url) {
  let timer = null
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error('timeout ' + url)), ITEM_TIMEOUT_MS)
  })
  return Promise.race([promise, timeout]).finally(() => {
    if (timer) clearTimeout(timer)
  })
}

/**
 * 开始（或复用）预加载。
 *
 * @returns {Promise<{loaded:number,total:number,failed:string[]}>}
 */
export function preloadAssets() {
  if (running) return running
  const urls = allAssetUrls()
  state.started = true
  state.total = urls.length
  emit()

  let next = 0
  /*
   * 一旦有一项【超时】，剩下的就不再发了。
   *
   * 理由：超时说明网络/静态资源这一层现在就是不通的，后面 100 个文件挨个等超时
   * 只会把"开始对局"卡好几分钟（happy-dom/无网环境下实测就是这样）。
   * 缺图是降级（Tile.vue 会退回矢量牌面），不是不能玩 —— 快速放行更重要。
   */
  let aborted = false

  async function worker() {
    while (next < urls.length) {
      if (aborted) {
        // ⚠️ 必须一次把剩余项全部"标记完成"再退出循环。
        //    这里如果只 loaded++ 而不推进 next，while 条件永远成立 →
        //    同步死循环，事件循环再也不会让出去（整个页面卡死）。
        //    smoke 跑场景 F 时就是这么抓出来的。
        state.loaded = urls.length
        emit()
        return
      }
      const url = urls[next++]
      const isAudio = /\.mp3(\?|$)/.test(url)
      try {
        await withTimeout(isAudio ? loadAudio(url) : loadImage(url), url)
      } catch (e) {
        state.failed.push(url)
        if (String(e && e.message).indexOf('timeout') === 0) {
          aborted = true
          console.warn('[preload] 资源加载超时，放弃剩余预热：' + url)
        } else {
          console.warn('[preload] 资源加载失败：' + url)
        }
      }
      state.loaded++
      emit()
    }
  }

  const workers = []
  for (let i = 0; i < Math.min(CONCURRENCY, urls.length); i++) {
    workers.push(worker())
  }

  running = Promise.all(workers)
    .then(() => {
      state.done = true
      emit()
      return snapshot()
    })
    .catch((e) => {
      // worker 内部已经兜住所有异常，走到这里说明出了意料之外的问题：
      // 仍然标记完成，别把用户永远挡在加载页
      state.done = true
      emit()
      console.warn('[preload] 预加载流程异常：' + (e && e.message))
      return snapshot()
    })

  return running
}
