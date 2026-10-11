import { build } from 'esbuild'
import { Window } from 'happy-dom'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import assert from 'node:assert/strict'
import { vuePlugin } from './esbuild-vue-plugin.mjs'

const win = new Window({ url: 'http://localhost:8080/' })
for (const key of ['window', 'document', 'navigator', 'location', 'localStorage', 'sessionStorage', 'HTMLElement', 'Element', 'SVGElement', 'Node', 'Event', 'MouseEvent'])
  Object.defineProperty(globalThis, key, { value: key === 'window' ? win : win[key], configurable: true })
localStorage.setItem('accessToken', 'records-test-only')
localStorage.setItem('profile', '{"id":7}')
const calls = []
const overview = { sessionId:42, readThroughRoundNum:1000, highestFan:'平胡', winCount:100,
  players:[{userId:7,nickname:'测试玩家',totalScore:600}] }
let failPage = false
globalThis.fetch = async (url, options) => {
  const q = new URL(url, win.location.href)
  calls.push(q)
  assert.equal(options.headers.Authorization, 'Bearer records-test-only')
  let data
  if (q.pathname.endsWith('/overview') || q.pathname.endsWith('/byRoom')) data = overview
  else if (q.pathname.endsWith('/rounds')) {
    if (failPage) { failPage = false; return { json: async () => ({ code:500, message:'测试分页失败' }) } }
    const after = Number(q.searchParams.get('afterRoundNum'))
    assert.equal(q.searchParams.get('readThroughRoundNum'), '1000')
    data = {readThroughRoundNum:1000, hasMore:true, nextAfterRoundNum:after+10,
      rounds:Array.from({length:10}, (_,i) => ({roundId:after+i+1,roundNum:after+i+1,
        participants:[7,-1,-2,-3].map((userId,seat) => ({userId,seat,nickname:`玩家${seat}`,scoreChange:seat===0?6:-2}))})) }
  } else if (/\/rounds\/\d+$/.test(q.pathname)) {
    data = {round:{roundNum:Number(q.pathname.split('/').at(-1)),isDraw:false,
      winHand:[1,2,3],participants:[],fanTypes:['测试番型']}}
  } else if (q.pathname === '/api/v2/records') data = {records:[],readThroughSessionId:42}
  else throw new Error(`Unexpected endpoint: ${url}`)
  return {json:async () => ({code:200,data})}
}
const temp = mkdtempSync(join(tmpdir(), 'hainan-records-v2-'))
let app
try {
  const outfile = join(temp, 'test.mjs')
  await build({stdin:{contents:`export {createApp, nextTick} from 'vue';
    export {default as Detail} from './src/components/records/RecordDetailV2.vue';
    export * as api from './src/api/api.js';
    export * as reads from './src/api/recordReads.js';`,resolveDir:process.cwd()},
    outfile,bundle:true,format:'esm',platform:'browser',plugins:[vuePlugin()],
    define:{'import.meta.env.VITE_RECORDS_API_V2':'"true"',__VUE_OPTIONS_API__:'true',
      __VUE_PROD_DEVTOOLS__:'false',__VUE_PROD_HYDRATION_MISMATCH_DETAILS__:'false'},loader:{'.css':'empty'}})
  const mod = await import(pathToFileURL(outfile).href)
  const settle = async () => { await new Promise(r => setTimeout(r, 15)); await mod.nextTick() }
  assert.equal(mod.api.recordsApiV2Enabled, true)
  const initial = await mod.api.recordDetail(42)
  assert.equal(initial._v2, true)
  assert.equal(calls.length, 1, '总览不能偷偷读取历史明细')
  const root = document.createElement('div'); document.body.append(root)
  app = mod.createApp(mod.Detail, {detail:initial}); app.mount(root); await settle()
  assert.equal(root.querySelectorAll('.round-summary').length, 10)
  assert.equal(root.querySelectorAll('.scores span').length, 40, '每页十局必须保留完整四家分数')
  assert.equal(calls.filter(q => /\/rounds\/\d+$/.test(q.pathname)).length, 0, '首屏不能读取大字段')
  const click = text => [...root.querySelectorAll('button')].find(b => b.textContent.trim()===text).click()
  click('查看详情'); await settle()
  assert.equal(calls.filter(q => /\/rounds\/\d+$/.test(q.pathname)).length, 1)
  assert.ok(root.textContent.includes('测试番型'))
  click('下一页'); await settle()
  assert.equal(root.querySelectorAll('.round-summary').length, 10, '翻页不能累计全部历史 DOM')
  assert.ok(root.textContent.includes('第 11 局'))
  assert.ok(!root.textContent.includes('测试番型'))
  click('上一页'); await settle()
  assert.ok(root.textContent.includes('第 1 局'))
  mod.reads.invalidateRecordReads(42)
  failPage = true; click('下一页'); await settle()
  assert.ok(root.textContent.includes('测试分页失败'))
  click('重试'); await settle()
  assert.ok(!root.textContent.includes('测试分页失败'))
  await mod.api.records(2,10,42)
  assert.equal(calls.at(-1).searchParams.get('readThroughSessionId'),'42')
  console.log('V2 战绩冒烟通过：开关、首屏10局、四家摘要、按需详情、稳定截止点、前后翻页和失败重试。')
} finally {
  app?.unmount()
  await win.happyDOM.abort()
  rmSync(temp,{recursive:true,force:true})
}
