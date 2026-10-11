import { build } from 'esbuild'
import { Window } from 'happy-dom'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import assert from 'node:assert/strict'
import { vuePlugin } from './esbuild-vue-plugin.mjs'

const win = new Window({ url:'http://localhost:8080/' })
for (const key of ['window','document','navigator','location','history','localStorage','sessionStorage','HTMLElement','Element','SVGElement','Node','Event','MouseEvent'])
  Object.defineProperty(globalThis,key,{value:key==='window'?win:win[key],configurable:true})
localStorage.setItem('accessToken','record-refresh-test-only')
localStorage.setItem('profile','{"id":7}')
let visibility = 'visible'
Object.defineProperty(document,'visibilityState',{get:()=>visibility,configurable:true})
const setVisible = value => { visibility=value?'visible':'hidden'; document.dispatchEvent(new win.Event('visibilitychange')) }
const calls = []
let latest = 5
let failPages = false
let fastRetries = false
let hideAfterIncrement = false
let blockedOverview = null
const scheduled = []
const nativeTimeout = globalThis.setTimeout
globalThis.setTimeout = (fn,delay,...args) => {
  if ([500,1000,2000,4000].includes(delay)) { scheduled.push(delay); if (fastRetries) delay/=100 }
  return nativeTimeout(fn,delay,...args)
}
const pause = ms => new Promise(resolve=>nativeTimeout(resolve,ms))
const players = (n,total) => [7,-1,-2,-3].map((userId,seat)=>({userId,seat,nickname:`玩家${seat}`,totalScore:seat===0?total:-2*n}))
globalThis.fetch = async url => {
  const q = new URL(url,win.location.href); calls.push(q)
  const after=Number(q.searchParams.get('afterRoundNum')||0)
  const cutoff=q.searchParams.has('readThroughRoundNum')?Number(q.searchParams.get('readThroughRoundNum')):latest
  let data
  if (q.pathname.endsWith('/overview') || q.pathname.endsWith('/byRoom')) {
    if (q.pathname.endsWith('/overview') && blockedOverview) await blockedOverview
    data={sessionId:42,roomId:'123456',status:0,readThroughRoundNum:cutoff,version:`42:${cutoff}`,
      players:players(cutoff,6*cutoff),totalScore:6*cutoff,winCount:cutoff,highestFan:'平胡'}
  } else if (q.pathname.endsWith('/rounds') || q.pathname.endsWith('/rounds/new')) {
    if (failPages && q.pathname.endsWith('/rounds')) return {json:async()=>({code:500,message:'测试服务器故障'})}
    const n=Math.min(10,cutoff-after)
    const rounds=Array.from({length:n},(_,i)=>({roundId:after+i+1,roundNum:after+i+1,
      participants:[7,-1,-2,-3].map((userId,seat)=>({userId,seat,nickname:`玩家${seat}`,scoreChange:seat===0?6:-2}))}))
    data={sessionId:42,readThroughRoundNum:cutoff,version:`42:${cutoff}`,rounds,
      hasMore:after+n<cutoff,nextAfterRoundNum:after+n}
    if (q.pathname.endsWith('/rounds/new') && hideAfterIncrement) { hideAfterIncrement=false; setVisible(false) }
  } else if (/\/rounds\/\d+$/.test(q.pathname)) {
    data={round:{roundNum:Number(q.pathname.split('/').at(-1)),isDraw:false,participants:[],fanTypes:['缓存番型']}}
  } else if (q.pathname === '/api/v2/records') {
    data={records:[{sessionId:42,roomId:'123456',players:['玩家0','玩家1','玩家2','玩家3'],totalScore:latest*6,rank:1}],
      total:1,pages:1,pageNum:1,pageSize:10,readThroughSessionId:42}
  } else throw new Error(`Unexpected request ${url}`)
  return {json:async()=>({code:200,data})}
}
const temp=mkdtempSync(join(tmpdir(),'hainan-record-refresh-'))
const apps=[]
try {
  const outfile=join(temp,'test.mjs')
  await build({stdin:{contents:`export {createApp,nextTick,h,ref} from 'vue';
    export {createPinia,setActivePinia} from 'pinia';
    export {createRouter,createMemoryHistory} from 'vue-router';
    export {useGameStore} from './src/stores/game.js';
    export {default as Detail} from './src/components/records/RecordDetailV2.vue';
    export {default as Popup} from './src/components/table/RoundHistory.vue';
    export {default as Records} from './src/views/Records.vue';
    export {useRecordTask} from './src/composables/useRecordTask.js';
    export * as api from './src/api/api.js';
    export * as reads from './src/api/recordReads.js';`,resolveDir:process.cwd()},outfile,bundle:true,
    format:'esm',platform:'browser',plugins:[vuePlugin()],loader:{'.css':'empty'},
    define:{__VUE_OPTIONS_API__:'true',__VUE_PROD_DEVTOOLS__:'false',__VUE_PROD_HYDRATION_MISMATCH_DETAILS__:'false'}})
  const mod=await import(pathToFileURL(outfile).href)
  const settle=async()=>{ await pause(20); await mod.nextTick() }
  const mount=(component,props)=>{
    const root=document.createElement('div'); document.body.append(root)
    const app=mod.createApp(component,props); app.mount(root); apps.push(app)
    return {app,root}
  }
  assert.equal(mod.api.recordsApiV2Enabled,true,'4.2 默认启用 V2')
  const [initial]=await Promise.all([mod.api.recordDetail(42),mod.api.recordDetail(42)])
  assert.equal(calls.length,1,'并发总览合并为一次 HTTP 请求')
  const {app,root}=mount(mod.Detail,{detail:initial}); await settle()
  const click=text=>[...root.querySelectorAll('button')].find(b=>b.textContent.trim()===text).click()
  assert.equal(root.querySelectorAll('.round-summary').length,5)
  click('查看详情'); await settle(); click('收起'); await settle(); click('查看详情'); await settle()
  assert.equal(calls.filter(q=>/\/rounds\/\d+$/.test(q.pathname)).length,1,'重复展开命中快照详情缓存')
  mod.setActivePinia(mod.createPinia())
  const store=mod.useGameStore(); store.roomCode='123456'
  const beforeCommit=calls.length
  store.handle({type:'coins',hand:6,coins:{EAST:36}}); await settle()
  assert.equal(calls.length,beforeCommit,'无已提交版本的金币消息不能刷新数据库战绩')
  latest=6
  const event={type:'coins',sessionId:42,committedRoundNum:6,coins:{EAST:36}}
  store.handle(event); store.handle(event); await settle()
  assert.equal(calls.length,beforeCommit+2,'重复提交通知仅触发一次增量和同版本总览')
  assert.equal(root.querySelectorAll('.round-summary').length,6,'末页合并新增小局而不重拉历史')
  assert.equal(root.querySelectorAll('.scores span').length,24)
  assert.ok(root.textContent.includes('截至第 6 局'))
  const newestOverview=calls.filter(q=>q.pathname.endsWith('/overview')).at(-1)
  assert.equal(newestOverview.searchParams.get('readThroughRoundNum'),'6')
  const beforeMidHide=calls.length
  const beforePausedRetries=scheduled.length
  hideAfterIncrement=true; latest=7
  store.handle({...event,committedRoundNum:7}); await settle()
  assert.equal(calls.length,beforeMidHide+1,'增量返回时已隐藏，不能继续发总览请求')
  latest=8; store.handle({...event,committedRoundNum:8}); await settle()
  assert.equal(calls.length,beforeMidHide+1)
  setVisible(true); await settle()
  assert.ok(root.textContent.includes('截至第 8 局'))
  assert.equal(scheduled.length,beforePausedRetries,'可见性暂停不消耗失败重试预算')
  const beforeHide=calls.length
  setVisible(false); latest=25
  store.handle({...event,committedRoundNum:25}); await settle()
  assert.equal(calls.length,beforeHide,'隐藏标签页不发刷新请求')
  setVisible(true); await settle()
  assert.equal(calls.length,beforeHide+2,'恢复可见只读一页新增摘要和总览，不追拉17局历史')
  assert.equal(root.querySelectorAll('.round-summary').length,10)
  assert.ok(root.textContent.includes('截至第 25 局'))
  assert.equal(calls.at(-2).searchParams.get('afterRoundNum'),'8')
  const beforeIdle=calls.length; await pause(80)
  assert.equal(calls.length,beforeIdle,'无固定周期轮询')

  // 分页失败后隐藏，暂停退避；恢复时延续重试预算，不因总览无变化重置预算。
  mod.reads.invalidateRecordReads(42); failPages=true
  const pageCalls=()=>calls.filter(q=>q.pathname.endsWith('/rounds')).length
  const beforeFailure=pageCalls()
  click('下一页'); await settle(); setVisible(false)
  await pause(550); assert.equal(pageCalls(),beforeFailure+1,'隐藏时取消后续重试定时器')
  fastRetries=true; setVisible(true); await pause(200); await mod.nextTick()
  assert.equal(pageCalls(),beforeFailure+5,'初次失败后最多重试四次')
  assert.deepEqual(scheduled.slice(-4),[500,1000,2000,4000])
  const afterExhaustion=pageCalls(); await pause(80)
  assert.equal(pageCalls(),afterExhaustion,'耗尽预算停止重试')
  failPages=false; click('重试'); await settle()
  assert.ok(root.textContent.includes('第 11 局'))
  assert.equal(root.querySelectorAll('.round-summary').length,10)
  app.unmount()

  // 关闭弹窗后没有监听刷新；重新打开只加载总览和第一页，重复点击不重复请求。
  const popup=mount(mod.Popup,{roomCode:'123456',rounds:[]})
  const beforeOpen=calls.length
  popup.root.querySelector('.rh__btn').click(); popup.root.querySelector('.rh__btn').click(); await settle()
  assert.equal(calls.slice(beforeOpen).filter(q=>q.pathname.endsWith('/byRoom')).length,1)
  popup.root.querySelector('.rh__head button').click(); await settle()
  const afterClose=calls.length; latest=26
  store.handle({...event,committedRoundNum:26}); await settle()
  assert.equal(calls.length,afterClose,'关闭弹窗停止增量刷新')
  popup.app.unmount()

  const beforeRace=calls.length
  const stale=mount(mod.Detail,{detail:{...initial,overview:{...initial.overview,readThroughRoundNum:25,version:'42:25'}}})
  await settle()
  assert.ok(stale.root.textContent.includes('截至第 26 局'),'挂载前的提交通知不能漏掉')
  assert.equal(calls.length,beforeRace+3,'旧总览挂载时只补增量、总览和当前第一页')
  stale.app.unmount()

  // 大厅战绩页真实路由：连点场次只请求一次，返回后旧总览不能重新打开详情。
  const router=mod.createRouter({history:mod.createMemoryHistory(),routes:[{path:'/',redirect:'/records'},{path:'/records',component:mod.Records}]})
  const recordsRoot=document.createElement('div'); document.body.append(recordsRoot)
  const recordsApp=mod.createApp(mod.Records); recordsApp.use(router); apps.push(recordsApp)
  await router.push('/records'); await router.isReady(); recordsApp.mount(recordsRoot); await settle()
  assert.equal(recordsRoot.querySelectorAll('.recrow').length,1)
  let unblockOverview
  blockedOverview=new Promise(resolve=>{unblockOverview=resolve})
  const beforeRow=calls.length
  const row=recordsRoot.querySelector('.recrow'); row.click(); row.click(); await settle()
  assert.equal(calls.length,beforeRow+1,'大厅战绩重复点击合并总览')
  assert.ok(recordsRoot.textContent.includes('加载中'))
  recordsRoot.querySelector('.records__head button').click(); await settle()
  unblockOverview(); blockedOverview=null; await settle()
  assert.equal(recordsRoot.querySelectorAll('.recrow').length,1,'返回后晚到的详情不能覆盖场次列表')
  assert.equal(recordsRoot.querySelector('.v2-records'),null)
  recordsApp.unmount()

  // 切换目标及卸载时，旧响应不能覆盖新页面；不可重试的权限错误只尝试一次。
  let task, value=''
  const harness=mount({setup(){task=mod.useRecordTask(); return()=>mod.h('div',value)}})
  let resolveOld
  const oldResult=new Promise(resolve=>{resolveOld=resolve})
  task.run({key:'old',read:()=>oldResult,onSuccess:data=>{value=data}})
  await task.run({key:'new',read:async()=>'new',onSuccess:data=>{value=data}})
  resolveOld('stale'); await settle(); assert.equal(value,'new')
  let forbiddenCalls=0
  await task.run({key:'forbidden',read:async()=>{forbiddenCalls++; throw Object.assign(new Error('无权限'),{code:403})},onSuccess(){}})
  await pause(100); assert.equal(forbiddenCalls,1)
  let closedCalls=0
  fastRetries=false
  await task.run({key:'close',read:async()=>{closedCalls++; throw new Error('临时故障')},onSuccess(){}})
  harness.app.unmount(); await pause(550); assert.equal(closedCalls,1)

  // 失效时已经在途的结果不能重新填回缓存。
  let resolveCached
  const pending=mod.reads.readRecord('pending',()=>new Promise(resolve=>{resolveCached=resolve}),{cacheable:true,sessionId:42})
  await Promise.resolve(); mod.reads.invalidateRecordReads(42); resolveCached('old'); await pending
  let cacheReloads=0
  assert.equal(await mod.reads.readRecord('pending',async()=>{cacheReloads++;return 'fresh'},{cacheable:true,sessionId:42}),'fresh')
  assert.equal(cacheReloads,1)
  console.log('4.2 通过：提交后增量/同版本总览、请求合并、按需缓存、隐藏暂停与恢复、有限退避、关闭清理、旧响应隔离。')
} finally {
  for (const app of apps) if (app._instance) app.unmount()
  globalThis.setTimeout=nativeTimeout
  await win.happyDOM.abort()
  rmSync(temp,{recursive:true,force:true})
}
