import { ref, onUnmounted } from 'vue'

/** 只在可见页面请求；瞬时错误最多重试四次，无周期轮询。切换任务和卸载均丢弃旧响应。 */
export function useRecordTask() {
  const busy = ref(false)
  const error = ref('')
  const delays = [500, 1000, 2000, 4000]
  let current = null
  let timer = null
  let disposed = false
  const visible = () => document.visibilityState !== 'hidden'
  const clearTimer = () => { if (timer != null) clearTimeout(timer); timer = null }

  function cancel() {
    clearTimer(); current = null; busy.value = false; error.value = ''
  }

  function apply(job, data) {
    if (current !== job || disposed) return
    job.done = true
    error.value = ''
    job.onSuccess(data)
  }

  function attempt(job) {
    if (disposed || current !== job || job.done) return Promise.resolve()
    if (job.flight) return job.flight
    if (!visible()) return Promise.resolve()
    if (job.ready) { apply(job, job.data); return Promise.resolve() }
    busy.value = true
    job.flight = Promise.resolve().then(() => {
      if (!visible()) throw Object.assign(new Error(), { code: 'RECORD_PAUSED' })
      return job.read()
    }).then(data => {
      if (current !== job || disposed) return
      if (visible()) apply(job, data)
      else { job.ready = true; job.data = data }
    }).catch(e => {
      if (current !== job || disposed) return
      if (e.code === 'RECORD_PAUSED') return
      error.value = e.message || '加载失败'
      job.onFailure?.(e)
      const retryable = ![400, 401, 4011, 403, 404].includes(e.code)
      if (retryable && job.attempts < delays.length) {
        const delay = delays[job.attempts++]
        if (visible()) timer = setTimeout(() => { timer = null; attempt(job) }, delay)
      } else job.done = true
    }).finally(() => {
      job.flight = null
      if (current === job && !disposed) busy.value = false
    })
    return job.flight
  }

  function run(job) {
    if (current?.key === job.key && !current.done) return current.flight || Promise.resolve()
    cancel()
    current = { ...job, attempts: 0, flight: null, done: false, ready: false }
    return attempt(current)
  }

  function retry() {
    if (!current || current.flight) return
    clearTimer()
    current.done = false; current.ready = false; current.attempts = 0
    error.value = ''
    return attempt(current)
  }

  function onVisibility() {
    if (!visible()) { clearTimer(); busy.value = false }
    else if (current && !current.done) attempt(current)
  }
  document.addEventListener('visibilitychange', onVisibility)
  onUnmounted(() => {
    disposed = true
    cancel()
    document.removeEventListener('visibilitychange', onVisibility)
  })
  return { busy, error, run, retry, cancel }
}
