# 海南麻将 · 前端（Vue 3 + Vite）

后端仍是 Spring Boot（`../`），本项目只负责前端。构建产物**写进后端模块的
`src/main/resources/static/`**，由 Spring Boot 原样托管 —— 服务器上不需要 Node 环境。

## 快速开始

```bash
pnpm install      # 首次
pnpm dev          # 开发服务器 http://localhost:5173，热更新
pnpm verify       # 跑全部自检 + 构建（提交前推荐）
pnpm build        # 只构建到 ../src/main/resources/static/
```

`pnpm dev` 已经把 `/api`、`/room`、`/game`、`/img`、`/music` 代理到后端 `127.0.0.1:8080`
（见 `vite.config.js`），所以本地开发时后端要先起来：

```powershell
cd ..
.\run-local.cmd
```

### 自检工具（`pnpm check` / `pnpm smoke`）

前端大部分东西只能在浏览器里看，但有几类错误**编译不会报、浏览器也不报**，
只表现为「该响的时候没声音」「某张牌是空白」「点了没反应」，所以单独写了脚本核对：

| 命令 | 查什么 |
|---|---|
| `pnpm check:tiles` | 42 种牌的编码/命名/分类与后端 `HuLib.java` 是否一致；SVG 牌面对 42 种都能画出内容；座位旋转与风位计算是否正确 |
| `pnpm check:audio` | `audio.js` 里用到的每个音效名/语音名，磁盘上是否真有对应 mp3（名字写错不会报错，只会静默没声音） |
| `pnpm check:build` | 从 `index.html` 出发**递归**走完所有 chunk（含路由懒加载的），确认没有 404、没有孤儿产物 |
| `pnpm smoke` | **在 Node 里用 happy-dom 真正挂载 Vue 应用**并驱动交互：进牌桌、灌后端消息、模拟点击、断言发出的 WebSocket 帧 |

`pnpm verify` = `check` + `smoke` + `build`，提交前跑这个。

`pnpm build` 会先跑 `pnpm clean`：因为 `emptyOutDir` 必须是 false
（`static/` 里还有 `img/`、`music/`、`apk/` 不归前端管），
代价是旧 hash 产物会累积，所以每次构建前只清 `assets/` 这一层。

### 页面"点了没反应"怎么查

先在浏览器控制台跑这段（会打印出足以定位问题的信息）：

```js
(() => {
  const a = document.getElementById('app')
  console.log('URL          :', location.href)
  console.log('hash（=当前路由）:', location.hash || '（空 = 在登录页）')
  console.log('#app 子节点数 :', a ? a.children.length : '没有 #app 元素')
  console.log('#app 尺寸     :', a ? a.offsetWidth + 'x' + a.offsetHeight : '-')
  console.log('入口脚本      :', [...document.scripts].map(s => s.src).filter(Boolean))
  // 找出盖住页面、吃掉点击的元素
  const cx = Math.floor(innerWidth / 2), cy = Math.floor(innerHeight / 2)
  const top = document.elementFromPoint(cx, cy)
  console.log('屏幕正中元素  :', top ? top.tagName + '.' + top.className : 'null')
  document.querySelectorAll('*').forEach(el => {
    const s = getComputedStyle(el)
    if (s.position === 'fixed' && s.display !== 'none' && el.offsetHeight >= innerHeight * 0.9) {
      console.warn('大面积 fixed 元素（可能挡住点击）:', el.tagName, el.className,
                   'pointer-events=' + s.pointerEvents)
    }
  })
})()
```

判断方式：

| 现象 | 说明 |
|---|---|
| `入口脚本` 里出现 `/game.html` 或 `/api.js` | 看的是**旧版页面**，静态资源没重新构建，或服务端还是旧 jar |
| `#app 子节点数 = 0` 或 `没有 #app 元素` | Vue 没挂载，控制台应有红色报错，把它发出来 |
| `#app 尺寸` 为 `0x0` | 组件渲染了但被 CSS 压成 0 高，点不到东西 |
| 屏幕正中元素是个 `fixed` 的大块 | 有东西盖住了页面，这就是「点了没反应」的直接原因 |
| `hash` 为空且停在登录页 | 说明卡在登录步骤，不是牌桌的问题 |

也可以直接访问 `http://localhost:8080/game.html`：**返回 404 才是新版**
（旧版那个 1744 行的单文件已经删掉了）。

### 牌桌停在"轮到你，正在等服务端下发可出牌状态…"

这句话的含义：**引擎已广播 `turn`（轮到你了），但前端没收到私人帧 `request`**。

为什么这两帧会不一致——引擎的顺序是固定的：

```
turn     → 广播给全场，只是"告诉大家轮到谁"
request  → 只发给本人，才是真正的"给你出牌机会"
```

手牌只有在收到 `request` 之后才可点。所以卡在这句话，说明 `request` 这一帧
**没到浏览器**（后端已确认会发，见 `HumanDiscardPromptIT`）。

用这段脚本把真实收到的帧打出来（在牌桌上按 F12 粘贴，然后等下一次轮到你）：

**注意：这段必须在打开牌桌页之前装好才有效**（先在大厅页粘贴，再点进牌桌；
或粘贴后刷新页面——刷新后要重新粘贴）。更省事的办法：把断点打在
`socket.js` 的 `onmessage` 上。

```js
(() => {
  // 先装全局异常捕获：页面"卡住"时最常见的真因是某个回调抛了异常被吞掉
  window.addEventListener('error', (e) =>
    console.log('%c[异常] ' + e.message, 'color:#c00;font-weight:bold', e.filename + ':' + e.lineno, e.error))
  window.addEventListener('unhandledrejection', (e) =>
    console.log('%c[未处理的 Promise 拒绝] ' + e.reason, 'color:#c00;font-weight:bold', e.reason))

  const t0 = performance.now()
  const at = () => '+' + ((performance.now() - t0) / 1000).toFixed(2) + 's'

  const OrigWS = window.WebSocket
  window.WebSocket = function (url, protocols) {
    const ws = protocols ? new OrigWS(url, protocols) : new OrigWS(url)
    console.log('%c[WS] 连接 ' + at(), 'color:#0a0', url)
    ws.addEventListener('open', () => console.log('%c[WS] 已打开 ' + at(), 'color:#0a0'))
    ws.addEventListener('close', (e) => console.log('%c[WS] 已关闭 ' + at() + ' code=' + e.code, 'color:#c00;font-weight:bold'))
    ws.addEventListener('error', () => console.log('%c[WS] 出错 ' + at(), 'color:#c00;font-weight:bold'))
    ws.addEventListener('message', (e) => {
      let m
      try { m = JSON.parse(e.data) } catch { return console.log('[WS] 非JSON:', e.data) }
      const isReq = m.type === 'request'
      console.log(
        (isReq ? '%c★ ' : '%c  ') + at() + ' ← ' + m.type,
        isReq ? 'color:#e07;font-weight:bold' : 'color:#666',
        m,
      )
    })
    const origSend = ws.send.bind(ws)
    ws.send = (d) => { console.log('%c  ' + at() + ' → ' + d, 'color:#08f'); return origSend(d) }
    return ws
  }
  window.WebSocket.prototype = OrigWS.prototype
  Object.assign(window.WebSocket, OrigWS)
  console.log('开始记录。请在卡住后把整段日志发出来（含时间戳）。')
})()
```

判读：

| 现象 | 结论 |
|---|---|
| 有 `turn` 但**始终没有** `★ request` | 服务端没发给这个连接 → 后端问题 |
| 你发出 `discard` 后，**5 秒内没有任何 `←` 帧** | 服务端处理卡住或出牌被拒 → 后端问题，需要后端控制台日志 |
| 有 `←` 帧但界面不变 | 前端状态机问题 → 把这帧发我 |
| 出现 `[异常]` 或 `[未处理的 Promise 拒绝]` | 直接就是这个异常导致的，把内容发我 |
| `[WS] 已关闭 code=1006` | 连接被异常断开 |

### 屏幕上有个"不知道是哪来的"元素

牌桌是绝对定位 + 缩放舞台，肉眼看到一个多余的方框时，从截图判断它是哪个 div
很容易猜错。直接让浏览器把每个元素的位置和尺寸报出来：

```js
(() => {
  const host = document.querySelector('.table') || document.body
  const hr = host.getBoundingClientRect()
  const rows = []
  host.querySelectorAll('*').forEach((el) => {
    const r = el.getBoundingClientRect()
    if (r.width < 4 || r.height < 4) return
    const cs = getComputedStyle(el)
    // 只列出"有可见背景/边框"的元素：空容器恰恰是问题所在
    const painted =
      cs.backgroundColor !== 'rgba(0, 0, 0, 0)' ||
      cs.backgroundImage !== 'none' ||
      cs.borderTopWidth !== '0px'
    rows.push({
      sel: el.tagName.toLowerCase() + (el.className ? '.' + String(el.className).trim().split(/\s+/).join('.') : ''),
      // 换算成 .table 内部坐标（1280x720 设计稿），方便和 CSS 里的数值对照
      x: Math.round(r.left - hr.left),
      y: Math.round(r.top - hr.top),
      w: Math.round(r.width),
      h: Math.round(r.height),
      painted,
      text: (el.textContent || '').trim().slice(0, 16),
    })
  })
  console.table(rows.filter((r) => r.painted).sort((a, b) => b.y - a.y))
  console.log('共 ' + rows.length + ' 个元素，上表只列出有背景/边框的')
})()
```

判读：把 `y` 和 CSS 里的 `bottom` 换算一下（`y = 720 - bottom - height`），
就能对上具体是哪个规则。**空容器往往是罪魁** —— 比如一个没有内容的
绝对定位块，会显示成一块孤零零的底色。


后端有一个**只读**诊断端点，把房间与牌局的实时状态吐成 JSON，专门用来回答
"引擎此刻在等哪个座位"这个从日志看不出来的问题：

```powershell
curl.exe -s --noproxy "*" http://localhost:8080/api/debug/rooms
```

重点看每个房间 `members[]` 里这几个字段：

| 字段 | 含义 |
|---|---|
| `delegate` | 这个座位的控制器委托是 `HumanPlayerController` 还是 `BotController`。是 Bot 就说明引擎**不会**向这家发询问 |
| `pendingRequest` | 这家此刻有没有"正在等回答"的请求；不为 null 且一直不消失，说明引擎发过询问但没人应答 |
| `gameWsOpen` / `hasGameWs` | 牌局连接是否还在 |
| `blockThreadState` | 引擎线程状态；`WAITING` + `engineActive=true` 说明牌局在跑、在等人 |

> 这个端点由 `DebugController` 提供，且 `/api/debug/**` 被加进了 `WebMvcConfig.AUTH_FREE`。
> **排查结束后应把这两处一起删掉**——它不该留在生产代码里。

### 已知设计缺陷：丢一帧 `request` 会永久卡住这一把

`HumanPlayerController.remember()` 每次询问都会 `pending.clear()`，只保留**最新一个**
待答请求。后果是：

1. 引擎发出 `request#1`，前端因为某种原因没收到；
2. 引擎等 30 秒后强制出牌（这一步本身是好的）；
3. 但下一个回合发出 `request#2` 时，`pending.clear()` 会把 `#1` 的 responder 一并丢弃；
4. 于是这一把里只要**丢过一帧**，再往后引擎就可能停在某个永远等不到答案的请求上。

这也是"点了出牌没反应、后端一行日志都没有"的最可能成因。
彻底修法需要后端配合（例如：超时后把该请求标记为已了结，或对丢失的请求重发而不是静默丢弃），
已记录待办，**尚未修改**。


同时看一下**后端控制台**有没有这行（这是我在 `HumanPlayerController` 里加过的诊断）：

```
[EAST] discard 请求 reqId=N 未送达前端（发送通道缺失，/game 可能已断开）
```

有这行就说明服务端认定你的连接不可用；没有这行则说明帧确实发出去了。


## 目录结构

```
frontend/
├─ index.html            入口（Vite 的 HTML 模板）
├─ vite.config.js        构建配置 + 开发代理
└─ src/
   ├─ main.js            挂载 Vue / pinia / 路由
   ├─ App.vue            只放 <router-view>
   ├─ router/index.js    hash 路由（原因见文件注释）
   ├─ api/api.js         REST 封装：token、主动续期、401 单飞重试
   ├─ game/
   │  ├─ tiles.js        牌模型：42 种牌的编码/命名/SVG 牌面绘制
   │  ├─ audio.js        音效与语音：番型→音效映射、自动播放解锁、开关
   │  └─ socket.js       WebSocket 客户端：token 握手、重连、状态机
   ├─ stores/game.js     牌局状态机：与后端每一条消息一一对应
   ├─ composables/
   │  └─ useLandscapeScale.js   1280x720 等比缩放 + 移动端横屏旋转
   ├─ components/
   │  ├─ tile/Tile.vue           单张牌（正面/背面/可点/禁打/高亮）
   │  ├─ table/SeatPanel.vue     对手信息（牌背数量、金币、当前回合）
   │  ├─ table/DiscardRiver.vue  中央牌河（四家分向 + 旋转）
   │  ├─ table/MeldRow.vue       副露（吃碰杠）与花牌
   │  ├─ table/ActionBar.vue     吃/碰/杠/胡/过/报听 按钮
   │  ├─ table/TurnTimer.vue     回合倒计时圆环
   │  └─ layout/LandscapeLayer.vue  缩放舞台
   └─ views/             Login / Lobby / Room / GameTable / Records
```

另有 `tools/`：`check-tiles.mjs`、`check-audio.mjs`（自检）、`clean-assets.mjs`（构建前清理）、
`build_favicon.py`（生成站点图标）。

### 站点图标

图标是**生成**的，不要手改 `static/favicon.*`：

```bash
python tools/build_favicon.py    # 写入 public/favicon.ico + public/favicon.svg
pnpm build                        # Vite 把 public/ 拷进 static/
```

两个文件都放 `public/` 是为了让 Vite 独占拷贝这一步——如果直接往 `static/` 写，
会和 `public/` 的拷贝撞在同一个输出路径上（同一份产物两个源，构建后谁赢看顺序）。
`index.html` 里同时声明了 svg（主，矢量）与 ico（旧浏览器兜底），
这样浏览器不会再去裸请求 `/favicon.ico` 而报 404。

## 必须知道的几条硬约定

这些是**前后端线协议**，改任何一边都要同步另一边，否则会出现
「能连上但什么都不显示」或「点了没反应」这类很难查的问题。

### 1. 牌的编码（`src/game/tiles.js`，对应后端 `HuLib.java`）

| 下标 | 含义 |
|---|---|
| `0..8` | 一万 ~ 九万 |
| `9..17` | 一筒 ~ 九筒 |
| `18..26` | 一条 ~ 九条 |
| `27..33` | 东 南 西 北 中 发 白 |
| `34..41` | 春 夏 秋 冬 梅 兰 竹 菊（**只计番，不参与成牌**） |

共 42 种牌。整副 144 张 = 34 种正牌 × 4 + 8 张花牌 × 1
（花牌每种只有 1 张，不能按「每种 4 张」推）。

### 2. WebSocket 身份只认 token

- `/room` —— 等待房：`createRoom` / `joinRoom` / `fillBots` / `startNow` / `leaveRoom`
- `/game?code=<房号>&seat=<座位>` —— 牌局

URL 上的 `?userId=` **已被服务端忽略**，身份由握手阶段的 `accessToken` 决定
（`WsAuthHandshakeInterceptor`）。浏览器原生 WebSocket 不能自定义请求头，
所以 token 只能放查询参数 —— 这是 `socket.js` 里 `_url()` 那么写的原因。

握手被拒时服务端返回真实 **401**（不是 200），浏览器只给一个笼统的 error 事件，
所以 `socket.js` 用「从没 open 过就失败」来推断认证失败。

### 3. 战绩分页 pageSize 上限 20

后端 `PageQueryDTO` 上是 `@Max(20)`。传大于 20 会被参数校验直接打回 400，
请求**到不了数据库**，页面表现为「一条战绩都没有」。`api.js` 里已做钳制，别改成 50。

### 4. 构建产物要入库

`vite.config.js` 里 `outDir: '../src/main/resources/static'` 且
`emptyOutDir: false`：

- 产物**必须入库** —— 服务器上没有 Node，`mvn package` 只会打包 static 里已有的文件。
- `emptyOutDir` 关掉是因为 `static/` 里还有 `img/`、`music/`、`apk/` 这些
  不归前端构建管的东西，一旦被清空就是数据丢失。
- 代价：旧的 hash 产物会残留累积，需要时手工清理 `static/assets/`。
  （`pnpm build` 里的 `clean` 脚本只会清 `static/assets/`，不会碰 `img/music/apk`。）

**打包 jar 一定要用 `mvn clean package`，不要单独 `mvn package`**：
maven-resources-plugin 只复制不删除，`target/classes/static/assets/` 会把历次构建的
hash 产物全部留下来 —— 实测 jar 里多塞了 4 个过期 css/js（约 148KB），而且在浏览器
直接请求旧 URL 时**照样返回旧内容**（我核对"修复有没有进 jar"时就被它骗过一次，
以为修好的 CSS 没生效）。`clean` 之后 jar 里的 assets 与 `static/assets/` 逐个同名同数。

### 5. 布局改动必须用真浏览器量像素

`tools/smoke.mjs` 跑在 happy-dom 上 —— 它**没有排版引擎**，
`getBoundingClientRect()` 全返回 0。它能证明"交互链路通"，
但证明不了"排得下、没重叠、贴得紧"，而布局恰恰是最容易改错的部分。

所以有第二个工具：`docs/browser-probe.py`（纯标准库，不依赖 selenium/playwright）。
它用无头 Chrome 渲染**真实构建产物**并量出像素：

```powershell
cd frontend
pnpm build
python ..\docs\browser-probe.py --shot ..\docs\probe-table.png
```

它做的事：起一个临时 HTTP 服务托管 `static/`（`file://` 下 ES module 会被 CORS 拦掉），
注入一段 shim 替换 `window.WebSocket`（注意 `socket.js` 用的是 `ws.onmessage = ...`
属性赋值，不是 `addEventListener`），灌入合成的服务端消息让四家都进入
"有手牌 + 有副露"的状态，然后断言：

| 断言 | 说明 |
|---|---|
| 三家各 13 张牌背、相邻重叠 0 处 | 牌背不重叠 |
| 牌背间距 0~1px | 紧挨着而不是分散 |
| 副露区没有任何文字 | 上家/对家/下家角标已去掉 |
| 副露内容起点到牌背内沿 | 副露贴着手牌 |
| 名字条不压牌背、不压副露 | 名字是浮层且位置正确 |
| 没有元素越出桌面包围盒 | 不溢出屏幕 |

改动任何座位布局后都要跑一次，别靠肉眼估计。

### 6. 为什么用 hash 路由

页面由 Spring Boot 当静态资源托管，**没有 SPA fallback**。
history 模式下用户在 `/game` 刷新会 404，所以用 `#/game`。

### 7. ⚠️ 绝对不要用 PowerShell 改这些 .vue 文件

**踩过一次，代价是整个 `GameTable.vue` 被毁。**

PowerShell 5.1 的 `Get-Content -Raw` **不带 `-Encoding` 时按系统 ANSI（中文机器上是 cp936）读**，
而 `Set-Content -Encoding UTF8` 又会加成 BOM。于是：

```powershell
# 这一行就能毁掉文件
(Get-Content a.vue -Raw) -replace 'x', 'y' | Set-Content -NoNewline a.vue
```

结果：UTF-8 源文件被按 GBK 解码成乱码字符串，再按 UTF-8 写回 —— **双重编码**。
cp936 没有映射的字节序列还会被替换成私用区字符，**这部分字节不可逆丢失**
（实测 896 行的文件丢了 197 个字符，需要靠还原 + 人工逐段补写才救回来）。

改文件只有两种正确做法：

1. 用编辑工具（推荐），它按 UTF-8 读写、不带 BOM。
2. 实在要写脚本，用 .NET 显式指定：
   ```powershell
   [System.IO.File]::WriteAllText($p, $text, [System.Text.UTF8Encoding]::new($false))
   ```

另外：**`.ps1` 脚本必须写成纯 ASCII**（PowerShell 5.1 按 GBK 读 .ps1），
`tools/thread-dump.ps1` 就是为此刻意不含中文。

### 8. 手机适配：虚拟横屏（旋转画面 + 固定框）

手机竖屏持握时，整个应用被 CSS 旋转 90° 当成横屏用。三层结构（移植自旧版
`static/landscape.css` + `landscape.js`，规则逐条保留）：

| 层 | 谁 | 干什么 |
|---|---|---|
| `body` | `styles/landscape.css` | 物理视口，只做黑色兜底，**不放背景** |
| `#game-app` | `App.vue` 里的根 div | 会被旋转的那一层；背景、安全区、铺满屏幕 |
| `.scale-layer` | `components/layout/LandscapeLayer.vue` | 仅牌桌：1280x720 设计分辨率的固定框，等比缩放 |

三条不能违反的规矩：

1. **背景必须设在 `#game-app` 上**。放在 `body` 上背景不跟着转，牌桌转了 90°
   而背景还是竖的，会露出竖着的底色。
2. **视图根元素不能用 `min-height: 100vh`**。`vh` 是物理视口高度，**不受
   transform 影响**：竖屏下物理高 = 旋转后容器的"宽"，拿它当高度会把内容顶出
   可视区（超出部分被 `#game-app` 的 `overflow: hidden` 吃掉，表现为"底部按钮
   点不到"）。视图根元素是 `#game-app` 的 flex 子项，靠
   `#game-app > * { flex: 1 1 auto; min-height: 0 }` 撑满；内容多就让页面根
   自己 `overflow-y: auto` 滚。
3. **旋转只做在 `#game-app` 上，不要再去转 `.scale-layer`**。两处都转会转 180°；
   而且旧的"只转内层"写法会让 `position: fixed` 弹层的参照盒子跟屏幕对不上。
4. **弹窗不许用 `vh` 限高**。这条被用户实测抓到过一次：规则弹窗写
   `max-height: min(88vh, 720px)`，`88vh` 是物理视口高（≈743px），而竖屏虚拟横屏
   的可见框只有 ≈390px —— 弹窗上下都被 `#game-app` 的 `overflow: hidden` 切掉，
   "上界和下界都不在屏幕内"，底部的「创建房间」永远点不到。
   正确写法（`RuleDialog` / `SettingsDialog` / 大厅关于弹窗）：

   ```css
   .mask { position: fixed; inset: 0; display: flex; align-items: center;
           justify-content: center; padding: 12px; overflow-y: auto; }
   .dlg  { max-height: min(100%, var(--frame-h, 100vh));   /* --frame-h 由 JS 写入 */
           display: flex; flex-direction: column; overflow: hidden; }
   .body { flex: 1 1 auto; min-height: 0; overflow-y: auto; }  /* 内容多就内部滚 */
   ```

   - 用 **flex 居中**而不是 `grid` + `place-items`：flex 项的百分比 `max-height`
     对着容器【确定的高度】解析，弹窗才会被真正压住。
   - `--frame-w/--frame-h` 是 JS 按"旋转后的可视宽高"写进 `:root` 的（单位 px），
     与朝向无关；`100%` 则是对着遮罩算，两者取小即"绝不超出可见框"。
   - 旧版 `lobby.html` 用 `min(88vh, 92vw, 820px)` 绕过（`92vw` 正好是屏幕短边），
     结论一样，但换成上面这套更精确。
   - ⚠️ **在 `.scale-layer` 里面的弹窗**（如 `RoundHistory`）**不要**用
     `var(--frame-h)` —— 那一层是设计像素（1280x720），只能取遮罩高度的 `100%`。

其它要点：

- 旋转本身靠 CSS `@media (orientation: portrait) html.virtual-landscape #game-app`
  ——物理转回横屏时媒体查询不再匹配，transform 自动撤销，不需要 JS 参与。
  JS 只管"是不是手机"（桌面窗口拉窄也会命中 portrait，那时不该转）。
- `composables/useLandscapeScale.js` 是**模块级单例**：算 `--scale`、给
  `<html>` 加 `virtual-landscape`、并在竖屏时用**显式像素**再写一遍
  `#game-app` 的尺寸/旋转（`sizeVirtualApp`）。后者是旧版踩出来的兜底：
  旧 iOS Safari 不支持 `dvh`，只靠 CSS 会只显示部分画面且刷新不恢复。
- 首次进页面会连算多次（rAF / 250ms / 800ms），因为字体、地址栏伸缩会让
  首次测量偏大或偏小，不补算就会出现"刷新一下才正常"。
- 桌面端**不包裹、不加类、不旋转**：`isMobile()` 为假时 `sizeVirtualApp`
  会把内联样式清空，交回 CSS。

验证：`docs/mobile-probe.py`（手机 UA + 竖屏窗口）断言旋转矩阵、显式像素、
固定框完整落在屏幕内、页面根元素布局高 = 物理宽，以及**触点命中**（拿元素视觉
中心去 `elementFromPoint`，必须命中它自己——这条直接证明触摸坐标经 transform 反算）。

```powershell
cd frontend; pnpm build
python ..\docs\mobile-probe.py --shot ..\docs\probe-mobile.png          # 牌桌+大厅+两个弹窗+战绩
python ..\docs\mobile-probe.py --only table --shot ..\docs\probe-mobile-table.png
python ..\docs\mobile-probe.py --only rule  --shot ..\docs\probe-mobile-rule.png   # 规则弹窗（保持打开）
python ..\docs\mobile-probe.py --orientation landscape                  # 物理转回横屏：必须不转
```

`--only rule` / `--only settings` 会把弹窗**留在打开状态**截图，方便肉眼复核。
探针会一并断言弹窗的每个部件都完整落在屏幕内、内容超出时自己可滚、
以及「创建房间」按钮的视觉坐标 `elementFromPoint` 必须命中它自己。

最后一条单独验"物理转回横屏自动恢复"：窗口 826x366 横屏时 `#game-app` 的
`transform` 必须是 `none`、内联尺寸必须清空——否则会出现双重旋转或尺寸不对。

把 `isMobile()` 改成恒 `false` 做变异测试，这个探针会报 17 项失败（固定框越界、
触点全部落空），说明它真的挡得住回归。

### 9. 登录页的「记住我」用独立存储键

`Login.vue` 的记住信息存在 `hnLoginRemember` 里，**不要**并进 `api.js` 的登录态键，
也不要把 `clearAuth()` 改成 `localStorage.clear()`：

- 用户名**默认就记住**（登录成功后写库），密码只在勾「记住密码」时存，且**明文**
  —— 这是"记住密码"的固有代价，默认关掉；取消勾选时必须立刻抹掉已存的密码。
- `clearAuth()` 是按具体键逐个删的（见 `api.js`），所以**退出登录不会清掉记住的用户名**，
  这正是想要的行为；改成 `localStorage.clear()` 会把它一起干掉。

验证：`docs/mobile-probe.py` 的最后一步会预置一份"记住我"存档进 `localStorage`，
断言用户名/密码被预填、勾选状态被还原、取消勾选后密码从本机消失、备案号存在且可点。

### 10. 注释里不要出现 `*/`

CSS 块注释不能嵌套。在 `/* ... */` 里写一个 `*/`（例如解释"这里不该有 `*/`"）
会**提前结束注释**，后面整段 CSS 变成非法选择器被浏览器静默丢弃 ——
表现为"样式怎么改都不生效"。这个坑也踩过，排查了很久。

## 与旧版的关系

旧版是 `static/` 下的 6 个单文件 HTML（`game.html` 一个就 1744 行）。
本次重写后它们已被移除，备份在仓库外的 `F:\HainanMaJhong2-legacy-static\`，需要时取回。
