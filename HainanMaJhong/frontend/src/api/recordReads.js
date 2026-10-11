// 有界快照缓存 + 同一请求合并。权限由 API 校验，账户凭据作为缓存键的一部分。
const cache = new Map()
const flights = new Map()
const listeners = new Set()
const committed = new Map()
const MAX_CACHE = 128
const TTL_MS = 30_000
let revision = 0

export function readRecord(key, read, { cacheable = false, sessionId = null } = {}) {
  const saved = cache.get(key)
  if (saved && Date.now() - saved.at < TTL_MS) {
    cache.delete(key); cache.set(key, saved)
    return Promise.resolve(saved.data)
  }
  cache.delete(key)
  const epoch = revision
  const flightKey = `${epoch}:${key}`
  if (flights.has(flightKey)) return flights.get(flightKey)
  const promise = Promise.resolve().then(read).then(data => {
    if (cacheable && epoch === revision) {
      cache.set(key, { data, at: Date.now(), sessionId })
      while (cache.size > MAX_CACHE) cache.delete(cache.keys().next().value)
    }
    return data
  }).finally(() => flights.delete(flightKey))
  flights.set(flightKey, promise)
  return promise
}

export function invalidateRecordReads(sessionId) {
  revision++
  for (const [key, entry] of cache) if (sessionId == null || entry.sessionId === sessionId) cache.delete(key)
}

export function resetRecordReads() {
  invalidateRecordReads()
  committed.clear()
}

export function subscribeRecordUpdates(listener) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function committedRecordRound(sessionId) { return committed.get(Number(sessionId)) || 0 }

export function notifyRecordCommit(sessionId, roundNum, roomId) {
  if (!Number.isSafeInteger(sessionId) || sessionId <= 0 || !Number.isSafeInteger(roundNum) || roundNum <= 0) return
  if ((committed.get(sessionId) || 0) >= roundNum) return
  committed.delete(sessionId); committed.set(sessionId, roundNum)
  while (committed.size > MAX_CACHE) committed.delete(committed.keys().next().value)
  invalidateRecordReads(sessionId)
  for (const listener of listeners) listener({ kind: 'commit', sessionId, roundNum, roomId })
}

export function notifyRecordReconnect(roomId) {
  invalidateRecordReads()
  for (const listener of listeners) listener({ kind: 'reconnect', roomId })
}
