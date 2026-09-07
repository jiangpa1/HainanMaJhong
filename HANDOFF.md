# 海南麻将 · 联机改造交接文档

> 本文档汇总本会话全部上下文，供在新窗口/新会话继续开发时快速接手。
> 新会话直接说“请先读 F:/HainanMaJhong2/HANDOFF.md”即可。

更新时间：2026-09-07

---

## 0. 一句话现状

把原来「1 真人 + 3 机器人」的快速开始（单人）扩成了 **4 真人联机房**：大厅创建/加入 6 位房间码 → 等待页 → 满 4 自动开 16 局 → 房主可再来一轮；对局中任何人离开都不解散（机器人托管、座位保留、大厅出现“返回房间”）；多人局也写 Redis 快照支持进程重启恢复；并补了一批界面/交互（出牌 30s、胡牌大字、最新出牌高亮、吃碰杠按钮改造、副露摆放规则、暗杠保密等）。

---

## 1. 工程信息

- 仓库根目录：`F:/HainanMaJhong2`（含 `HainanMaJhong` Maven 模块 + `mjlib_java` 参考库）
- 当前分支：`v2-update`（主分支 `master` 是纯引擎旧版，无 Spring）
- 技术栈：Spring Boot 2.7.18 / Java 11（打包 target），JDK26 可编译；WebSocket + 静态 HTML/JS（无框架）
- 配置 `HainanMaJhong/src/main/resources/application.yml`：
  - 端口默认 **8080**，无 context-path
  - MySQL `39.107.247.119:3306/hainan_majong` root / `10201122Zxj@`
  - Redis 同主机 6379 同密码（`RedisService` 用 `room:{id}` 存 30 分钟）
  - ⚠️ 这些是远程凭据，已提交在库里；外发/上传前注意脱敏，建议后续改成环境变量
- 服务启动需要 MySQL/Redis 可达；Redis 不可达会自动降级（游戏照常，只是无快照续玩）

---

## 2. 目录与关键文件

### 后端 `HainanMaJhong/src/main/java/hainanjong/`
- 引擎（纯 Java，无外部依赖）：
  - `game/RoomManager.java` —— 单把 4 家引擎，座位无关；改过：canHu 只看暗牌、`onTurnStart` 回调、`wasLastDrawKongFlower`（杠/花后补牌标记）
  - `game/Seat.java`(东→南→西→北, next/下家=下一顺位)、`Player/Controller/Responder/Action/Meld/GameConfig/GameListener/RoundResult/GameSnapshot/PrintListener`、`BotController`（座位无关，当托管/电脑）
  - `HuLib.java`、`HuResult.java`、`FanType.java` —— 胡判定（支持 14/11/8/5/2 暗牌）
- 服务：`service/MysqlService.java`（启动幂等建表 user/game_sessions/game_rounds/game_round_scores）、`service/RedisService.java`
- Web：`web/WebSocketConfig.java`（注册 `/ws`、`/room`、`/game`）、`GameWebSocketHandler`+`WebSocketGameService`（单人）、`RoomWebSocketHandler`+`MultiPlayerRoomService`（多人）、`MultiGameWebSocketHandler`（/game 座位）、`HumanPlayerController`（真人控制器，可 attach/resend）、`WebGameListener`（单人监听）、`MultiPlayerRoomService.RoomListener`（四人广播监听）、`AuthController`、`RecordsController`、`RoomApiController`
- 注意：`listener/GamePersistenceListener`、`runner/DemoGameRunner` 已删除（工作区 D 状态）

### 前端 `HainanMaJhong/src/main/resources/static/`
- `index.html`（**登录/注册：竖屏**，已移除 landscape.css/js）
- `lobby.html`（大厅；快速开始/创建房间/加入房间/战绩/设置；“返回房间”蓝钮）
- `room.html`（多人等待房：6 位码/房主/成员；收到 start 跳 game.html?mode=multi）
- `game.html`（牌桌，单/多人共用；最复杂，座位翻译/副露/胡牌大字/操作按钮都在这）
- `records.html`（战绩，只读）
- `landscape.css` / `landscape.js`（虚拟横屏：竖屏持机时旋转 90°；页面资源链接带版本 `?v=20260907g`）
- `img/…`（tiles 0-41 等图）

---

## 3. 已实现功能（按会话顺序）

### 3.1 胡判定与暗牌
- `HuLib`：原只判 14 张，现 `canHuConcealed(int[])` 支持暗牌 2/5/8/11/14（3k+2，k=0..4），七对/十三幺仅 14 张无副露时成立；花色副表启动一次生成。
- `RoomManager.canHu` 改为只查暗牌（吃碰杠为固定组，不再折回 14 张重拆）；`win()` 仍用折回 14 张取番型。
- `Main.java` fuzz 覆盖 14 + 各张数随机对照。

### 3.2 出牌 30 秒、账号互斥
- `WebSocketGameService`：DISCARD 30s / ACTION 15s；`HumanPlayerController` 拆两个超时并新增 `attach(Sender)`+`resendPending()`（断线重连用）。
- 单人同账号互斥：`gamesByUser` 登记，`kickUser(long)`；`AuthController.login` 成功即踢旧在线局；旧端收 `type:"kicked"` + 4001 关闭（game.html 处理并清本地回登录页）。游客(userId=0)不参与。

### 3.3 多人联机房（等待 + 4 人 16 局）
- `MultiPlayerRoomService`（内存房间）+ `/room`、`/game` 端点。
- 创建/加入：6 位数字房间码（100000-999999 唯一）；游客禁入；一人一活房（占座时禁止另开新房）。
- 状态机：WAITING→(满4)→PLAYING→(打完且4人在)→BETWEEN；BETWEEN 房主可 rematch 开新 16 局（新 MySQL session、比分清零）。
- 引擎：每块一个守护线程循环 16 把，每把 `new RoomManager`，座位控制器用 `SwitchingController`（真人↔Bot 切换）；监听 `RoomListener`：公开事件广播 4 端、私牌只发该座位本人。
- 每 16 把 = 一条 `game_sessions`（4 个真实 id，creator=host）；records 无需改。
- 断线/离开语义（重要）：
  - **对局中任何人（含房主）离开/刷新 = 不断房**：座位保留、该座切机器人托管；同人按码/点“返回房间”可夺回座位。
  - 一个 16 局块结束仍有人离线 → 房间解散（成员回大厅）；4 人都在 → 留在 BETWEEN 等房主再来一轮。
  - 房主在**等待态**关闭页面 → 立即解散整房。
  - 等待态成员关闭 = 退出。
- 大厅“返回房间”：`GET /api/room/pending?userId=` 返回 `{exists,code,state,seat}`；lobby 每 8s 轮询，有可回房显示蓝色按钮；PLAYING 直接回 game、否则回 room.html。
- 中断线重连 `/game?userId&code&seat`：补发当前把 hand_start + 全场 board/hand/counts（`pushSeatState`）；`HumanPlayerController.attach+resendPending` 重发在途请求。

### 3.4 多人 Redis 快照 + 重启恢复
- 多人每把 checkpoint 写 `room:{房间码}`（含 host/座位/金币/currentHand/庄/`GameSnapshot`）。
- 服务重启后，玩家凭房间码回来时若内存无房 → `resurrect(code)` 从 Redis 重建；PLAYING 且有快照则续跑剩余局数（引擎 seed 带 session/coins/dealer/resume snapshot）。
- 解散/清房会删该键；键 30 分钟自动过期。
- ⚠️ 限制：`codeByUser` 是内存映射，重启后未回来的人大厅“返回房间”按钮要等**第一个回来的人**重建后才恢复；如需彻底，需把 userId→code 也写 Redis（待办）。

### 3.5 出牌轮次高亮与全局倒计时
- `GameListener` 加默认 `onTurnStart(seat,kind,ms)`，`RoomManager.requestDiscard` 开头调用。
- 多人广播 `{type:"turn",seat,kind:"discard",timeoutMs}`：所有端把“出牌人昵称”放大标红（`.hl-turn`），中央倒计时全员可见；其打出后清除。

### 3.6 界面与交互（当前最新）
- **登录/注册竖屏**：index 去掉 landscape；点进入后才进横屏页。
- **自己吃碰杠副露显示**（修复多人在 hand/board 里把 melds 数组误当映射转坏的 bug）。
- **吃碰杠出牌堆消失**：melds 消息新增 `from`，前端把来源家出牌堆里刚出的那张拿走。
- **来源牌横放**：上家→最左、下家→最右、对家→中间（吃碰杠统一；暗杠无来源）。
- **暗杠保密**：本人看 4 张正面，其他三家看 4 张牌背。
- **最新出牌高亮**：只高亮“全场最新一张”（`discardLog`），被吃碰杠拿走则高亮自动落到前一张。
- **操作按钮（右侧偏中下）**：只显示大字 胡/杠/碰/吃/过；按钮左侧显示“被操作的那张牌”（蓝色描边 `.act-tile`）；**多种吃法**先点“吃”，再弹带牌面预览的选择列表，可返回。
- **胡牌大字**：全员可见并按“胡的那家方位”显示（上/右/左/中），两段式（先 1s 自摸/杠开/点炮，再 1s 番型，无番“平胡”；红楷金描边）。后端在杠/花后补牌自摸时标记 `ganKai`（`wasLastDrawKongFlower`）。
- 全站静态资源链接用版本号 `?v=…`（当前 `20260907g`）防缓存，改静态后要再升级并 `mvn clean package`。

---

## 4. 关键协议备忘

### 消息 type（含 type 字段，牌局事件单/多人同名）
- C→S：`discard/act/pass`、单人 `quit`；多人 /room：`createRoom/joinRoom/leaveRoom`；多人 /game：`rematch/leaveRoom`
- S→C：
  - 单人 `/ws`：`welcome/match/hand_start/coins/match_end/dealer/hand/counts/board/draw/flower/discard/meld/hu(+ganKai)/end/log/request/kicked`
  - /room：`room_info{code,hostUserId,state,members[]}/start{code,seat}/join_denied/room_closed`
  - /game：`welcome{seat,roomId,players[]}/match/hand_start/coins/turn/match_finish{coins,disband,isHost}/room_closed` + 上面牌局事件
- 端上“本地视角”：多人把服务端座位翻译成“自己=EAST 底部，右=下家，上=对家，左=上家”；`serverToLocal/mapSeatKeys/translateMeld/translateMsg` 处理所有座位/`from`/地图键。

### 多人房间生命周期
```
WAITING (0..3人, 满4自动PLAYING; 房主关页→解散)
PLAYING (16把; 任何人离开→座位保留bot托管; 块末仍离线→解散)
BETWEEN (4人在, 房主rematch→新PLAYING; 有人离开→解散)
```
- Redis 键：`room:{code}`（`RedisRoom` JSON：host/state/block/session/hand/dealer/coins/members/snapshot）
- 单人键 `room:{room-…}` 不冲突。

---

## 5. 已知问题 / 限制 / 待办

- 重启后“返回房间”按钮对未回来者不出现（见 3.4 限制），需 userId→code 入 Redis。
- Redis 键 30 分钟过期。
- 断线瞬间若正好轮到该玩家，替身要等现超时（≤30s/15s）才接管（如要秒级需加“立即 Bot 应答”钩子）。
- 16 局块内有人离线 → 块末解散；**不做块间补人续房**（想支持“打完回等待补人再开”需加房间页状态）。
- 副露横放方向：按引擎 `Seat` 顺序的“上/下家”实现；若真机方向与你预期相反，改 `meldHtml` 里 0/末位映射对调即可。
- 多人 engine/RoomListener 等只做编译级验证，**未在本环境真机 4 端回归**。
- landscape 缩放曾出过问题（旧 iOS dvh、进页面时机算错、跳转前全屏卡页面）：已加 JS 像素显式尺寸 + 多次重算 + 去掉跳转前全屏 + CSS vh 兜底；若再复发优先查缓存/代理/是否真部署了新文件。
- 大量未提交改动在工作区（git status 可见 M/D 很多），提交前逐文件确认，勿把凭据外发。

---

## 6. 构建 / 自测方法

- 命令行无 mvn；本机 JDK 在 `C:/Users/ASUS/.jdks/openjdk-26`。
- 纯编译（会话内方法）：把 src/main/java 下核心/服务/web 源文件用
  `"/c/Users/ASUS/.jdks/openjdk-26/bin/javac" -encoding UTF-8 -cp "<m2 全部 jar, slf4j-api 用 1.7.36 放最前>" -d <out> <源列表>` 编译。
- 正式打包部署：`mvn clean package`（在 HainanMaJhong 目录）→ 部署 jar；改静态后务必重打包再强刷（版本号 bump）。
- 后端 HTTP 冒烟：`curl -i -X POST http://<host>:8080/api/login -d '{"username":"…","password":"…"}'` 应回 200 JSON。
- 确认静态是新文件：
  `curl -s http://<host>:8080/game.html | grep -o 'landscape.js?v=[0-9a-z]*'`
  `curl -s http://<host>:8080/landscape.js?v=20260907g | grep -c sizeVirtualApp`
- 前端 JS 语法：提取各 html 内联 <script> 后 `node --check`。

### 真机/浏览器验证清单（多人）
1. 4 个真实账号（游客禁入多人）各开窗口/无痕。
2. A 创建见 6 位码 → B/C/D 输码加入实时显示成员；游客被拦。
3. 满 4 自动开局；各人视角自己底部、三家真名/金币/张数正确；打一把吃碰杠胡事件四端一致。
4. 出牌时昵称红色放大、倒计时全员可见；最新出牌金色高亮；吃碰杠后出牌堆那张消失、来源牌按方向横放；暗杠他人只见牌背。
5. 有人胡：全员在对应方位看到两段大字（杠开判定）。
6. 操作按钮右侧出现蓝描边目标牌；多种吃法先点“吃”再选。
7. 中途某人关窗 → 游戏照常（bot 托管），其大厅出现“返回房间”，点回可夺座；块末未归→全员解散。
8. 房主对局中关窗 → 不断房（bot 托管）；打完 16 把且 4 人在 → 房主“再来一轮”开新 session；records 逐户正确。
9. 服务重启 → 任一玩家凭码回房 → 从断点续跑。
10. 单人“快速开始”回归不变；同账号另一设备登录仍能顶掉旧连接。

---

## 7. 给新窗口的起点建议

- 读本文件后，先 `git status` 看当前工作区差异、再 `git log --oneline` 看基线。
- 若要继续改，先跑后端编译（见 §6）与 `node --check` 前端。
- 常见小任务提示：改静态资源记得 bump 页面里的 `landscape.*?v=`；涉及房间生命周期改动先在 `MultiPlayerRoomService` 的状态机注释里对一对。
