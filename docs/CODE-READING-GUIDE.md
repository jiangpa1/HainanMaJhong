# 海南麻将 · 代码阅读指南（对照重构后的分层）

> **这份文档解决什么问题**：代码你已经有了（38 个文件 / 6791 行），但"从哪开始读、读到什么程度算读懂了"没有答案。
> 本文给你**四条阅读链路**、**每步的准确入口（文件:行号）**、以及**自检问题**——能答上来就算这段读懂了。
> **编写时间**：2026-09-20 · 对应 commit `c9edd8c`（阶段 1 分层搬包之后）
> **配套**：`HANDOFF.md`（项目技术交接）、`docs/ARCH-REFACTOR.md`（分层设计与迁移表）

---

## 0. 先建立心智模型（10 分钟，别跳过）

### 0.1 一句话架构

```
浏览器(8 个 html/js，手写)
   │  HTTP：登录/注册/改昵称/战绩/下载 APK
   │  WebSocket：/room（等待房）+ /game（牌局）
   ▼
controller/ + websocket/      ← 只做「协议 → 方法调用」的翻译
   ▼
service/MultiPlayerRoomService ← 房间生命周期 + 块调度 + 事件转消息（1635 行，最大）
   ▼
engine/                        ← 纯 Java 牌局引擎（不依赖 Spring / 不依赖 WebSocket）
   ├─ RoomManager   一把牌的完整流程（1236 行）
   ├─ model/        Seat / Player / Meld / Action / GameSnapshot / GameConfig / RoundResult
   ├─ port/         PlayerController / Responder / GameListener  ← 引擎对外的三个接口
   ├─ bot/          BotController
   └─ dev/          GameDemo / PrintListener / EngineSmokeTest
   ▼
rules/                        ← 番型与结算（HainanFan / HainanScore / DealerFlow / HainanConfig / RuleEnv）
   ▼
repository/                   ← RoomSnapshotRepository（Redis 快照）
service/MysqlService          ← 全部 SQL（阶段 2 才会拆成两个 Repository）
```

### 0.2 最关键的一句话（也是答辩最值钱的一句）

> **引擎不认识 Spring，也不认识 WebSocket。它对外只有三个接口：**
> - `PlayerController`（引擎问"你出哪张牌"）——`engine/port/PlayerController.java`
> - `Responder`（引擎等你的答复）——`engine/port/Responder.java`
> - `GameListener`（引擎喊"有人出牌了/有人胡了"）——`engine/port/GameListener.java`

**联机只是这三个接口的一个实现**：`HumanPlayerController`（真人）和 `BotController`（机器人）都实现 `PlayerController`；
`MultiPlayerRoomService.RoomListener`（内部类）实现 `GameListener`，把引擎事件翻译成 WebSocket 消息。

**读懂这三个接口，你就读懂了整个项目的骨架。** 剩下 6000 行都是它们的实现细节。

### 0.3 为什么"按链路读"而不是"按文件读"

按文件读会卡在 `MultiPlayerRoomService` 的 1635 行里（它同时是 WS 入口、房间管理、线程宿主、消息序列化）。
按链路读，每次只跟一条线，遇到不认识的类再跳过去看——这是唯一能在几小时内读懂 6700 行的方式。

**判断标准（沿用你自己定的）**：**能合上代码，画出带方法名的时序图。**

---

## 1. 链路一：一次出牌的全链路 ★最重要

这是四条链路里唯一"贯穿所有层"的一条。把它读透，其余三条都是它的分支。

### 1.1 自上而下：前端 → 引擎

| 步 | 位置 | 做什么 |
| --- | --- | --- |
| 1 | `static/game.html:800-810`（`localStorage.userId` → `new WebSocket`） | 连 `/game?userId=&code=&seat=`，**身份就是 URL 参数**（这就是那份缺陷清单里 P0-1 的根源） |
| 2 | `websocket/GameWebSocketHandler.java:23-30` | 三个回调转调 `roomService.openGame / onGameMessage / onGameClose` |
| 3 | `service/MultiPlayerRoomService.java:255-267` `openGame` | 从 query 取 `userId/code/seat` → `bindGameSeat(...)`；`code` 对不上会 `resurrect(code)` 尝试从 Redis 重建 |
| 4 | 同上 `:365-434` `bindGameSeat` | 校验"这个座位登记的人 == 你"→ `m.gameWs = session`、`m.human.attach(sender)`、`m.controller.setDelegate(m.human)`、`m.human.resendPending()` |
| 5 | 同上 `:269-315` `onGameMessage` | 解析 JSON → 按 `type` 分派：`discard / act / pass / baoting / chat / rematch / leaveRoom` |
| 6 | `service/HumanPlayerController.java:174-201` | **不直接操作牌局**，而是把值塞进"待应答槽"：`discard(reqId,tile)` / `act(reqId,index)` / `pass(reqId)`（另有 `baoTing(reqId)` `:182`，落点在 `Responder.report()`） |
| 7 | `engine/RoomManager.java:1184-1235` `prompt` | ★ **阻塞点**：`CountDownLatch.await()` 等人应答，超时用 `forced` 值兜底，超时会回调 `listener.onTimeout` |
| 8 | `engine/RoomManager.java:426-488` `doTurn` | 真正的回合：检查牌墙 → `drawOne(p)` 摸牌 → `detectDrawActions` 问自摸/暗杠/补杠 → `discard(p)` 出牌 → `continueAfterDiscard` |
| 9 | `engine/RoomManager.java:490-505` `continueAfterDiscard` | `resolveDiscard` 问其他三家（吃/碰/杠/胡），按优先级裁决后把回合交给下家 |

> **第 6~7 步是这个项目最巧妙、也最该讲清楚的地方**：引擎是同步阻塞的（一次 `simulate` 跑到一把结束），
> 而真人的操作是异步到达的。两者靠 `HumanPlayerController` 的"待应答槽 + 断路器(Responder)"桥接。
> **能讲清这一点，就说明你真读懂了联机是怎么和同步引擎接上的。**

### 1.2 自下而上：引擎 → 四端（最容易读反的一段）

引擎不直接发消息，它只"喊事件"；翻译工作在 `RoomListener`（`MultiPlayerRoomService` 的内部类）：

| 引擎回调 | 位置 | 转成的 WS 消息 |
| --- | --- | --- |
| `onDeal` | `:1055-1064` | 广播 `dealer` → 给**每家**发自己的 `hand`（`sendHand(s)`）→ 广播 `counts` |
| `onDraw` | `:1066-1074` | 广播 `draw` + `counts`；**只给摸牌者**发 `hand` |
| `onFlower` | `:1076-1084` | 同上（`flower`） |
| `onDiscard` | `:1086-1094` | 广播 `discard` + `counts`；给出牌者发 `hand` |
| `onMeld` | `:1096-1104` | 广播 `meld` + `counts` + 给该家发 `hand` |
| `onHu` | `:1106-1134` | 广播 `hu`（番型由 `HainanScore.fansOfHand` 现算，`ganKai` 用 `rm.wasLastDrawKongFlower()`） |
| `onTurnStart` | `:1144-1151` | 广播 `turn{seat,kind,timeoutMs}`（前端据此把昵称标红 + 起倒计时） |
| `onReport` | `:1153-1160` | 广播 `report{seat,mode}`（**字段是 `mode` 不是 `baoTing`，与 `hu` 不同**） |
| `onRoundDraw` / `onTimeout` | `:1136-1142` | 只发 `log` |

### 1.3 ★ 暗牌保密：私牌只发给该座位本人

这是全项目**唯一一条安全红线**，读的时候必须盯住：

```java
// MultiPlayerRoomService.java:1216-1239  sendHand(Seat)
private void sendHand(Seat seat) {
    Member mem = room.members.get(seat);
    if (mem == null || !bound(mem)) { return; }   // 未绑定/机器人跳过
    ... sendJson(mem.gameWs, m);                  // ★ 只发给这一个 session
}
```

**自检问题**（能答上来说明读懂了）：
1. `hand` 消息为什么不能走 `broadcastGame`？（因为那样会把别人的牌发给你）
2. `counts`（各家手牌"张数"）为什么可以广播？（张数是公开信息，牌面才保密）
3. 断线重连补发时（`pushSeatState:1344-1398`）怎么保证不串牌？（它按**本次连接的座位**组装 `board`+`counts`，只发给 `session` 一个）

> 顺带记住一个**已实测的细节**：重连补发路径发出的 `counts` **不带 `wall` 字段**，而常规 `sendCounts()`（`:1240-1256`）**总是带** `wall`。
> 同一种消息两种形态，是有意/历史原因，不是 bug —— **改代码时不要"顺手统一"**。

---

## 2. 链路二：断线重连与机器人托管

| 步 | 位置 | 关键点 |
| --- | --- | --- |
| 1 | `MultiPlayerRoomService.java:317-336` `onGameClose` | `m.gameWs = null` → `handleMemberLeft` |
| 2 | 同上 `:342-353` `handleMemberLeft` | **对局中**：`m.offline = true` + `m.controller.setDelegate(new BotController())` → 座位保留；若 `noHumanBound(room)` 则解散 |
| 3 | 同上 `:356-363` `noHumanBound` | ★ **"只剩机器人就解散"** —— 这就是你实测到的"单人退出后房间没了、无法重进"的根因 |
| 4 | 同上 `:583-612` `pendingReturn` | 大厅每 8 秒轮询 `/api/room/pending?userId=` 决定要不要显示蓝色「返回房间」 |
| 5 | 同上 `:974-1039` `resurrect(code)` | 内存无此房 → 从 Redis 重建（见链路三） |
| 6 | 同上 `:1344-1398` `pushSeatState` | 补发：`board`（四家弃牌/副露/花）+ `counts` + 自己的 `hand` |
| 7 | `service/HumanPlayerController.java:57-80` | `attach(Sender)` `:57` + `resendPending()` `:69`：把**在途的**那次请求（`discard`/`act` 还没答的）重发一遍 |

**运行时的两个已知延迟（HANDOFF §5 已记）**：
- 断线瞬间**若正好轮到你**，`RoomManager.prompt` 已经在阻塞等待 → 替身要等满超时（出牌 30s / 操作 15s）才接管。
- **要秒级得加"立即 Bot 应答"钩子**：`handleMemberLeft` 里调 `room.active.cancel()` 之类的强制唤醒（`RoomManager:219-225` 有 `cancel()`，它正是为这个准备的 —— 但目前**只在解散时被调用**）。

**自检问题**：
1. `Member.offline` 和 `bound(m)` 分别表示什么？（离线 ≠ 未绑定；`bound` 见 `:1540-1543`）
2. 为什么 `codeByUser` 只存内存会导致"重启后未回来的人看不到返回按钮"？（`:71`，它是 `ConcurrentHashMap`）
3. `SwitchingController`（`:105-124`）为什么要存在？（`RoomManager` 持有的是它，切换真人/机器人时**不需要重建引擎**）

---

## 3. 链路三：Redis 快照与重启恢复

这条链路**比交接文档写的更强** —— 所以我把之前的说法更正了（见 §7）。

| 步 | 位置 | 关键点 |
| --- | --- | --- |
| 1 | `engine/RoomManager.java:242-253` | `setCheckpoint(Consumer<GameSnapshot>)`：引擎在**每次出牌前**（`:432`）和**弃牌后进入 RESPOND 前**（`:536`）回调 |
| 2 | 同上 `:256-288` `snapshot()` | ★ **导出完整状态**：`deck` + `discardPile` + **四家手牌** `ps.hand` + 副露 + 花 + `lastDrawn` + `reportMode` + `phase` + `currentSeat` |
| 3 | `MultiPlayerRoomService.java:835-839` | 注册回调：`rm.setCheckpoint(snap -> { room.currentSnap = snap; saveRoom(room, snap); })` |
| 4 | 同上 `:938-972` `saveRoom` | 组装 `RedisRoom`（房间元数据 + `members` + `snapshot`）→ `repository/RoomSnapshotRepository.save(code, json)` |
| 5 | `repository/RoomSnapshotRepository.java:25-37` | 键 `room:{code}`，**TTL 30 分钟** |
| 6 | 同上 `:974-1039` `resurrect` | 反序列化 → 重建 `Room` 与 `members` → `seed.resume = rs.snapshot` → 启动引擎线程 |
| 7 | `engine/RoomManager.java:289-355` `resume(snap)` | 恢复牌墙/手牌/副露/弃牌 → **按 `phase` 分派**：`"RESPOND"` → `continueAfterDiscard`；否则 → `doTurn` |

> ★ **重点更正**：快照**包含各家手牌**，`resume()` 也**能续当前这一把**（不是"从下一把开始"）。
> 我此前在 `docs/baseline-status.md` 里写的"快照不含手牌、当前那把作废"是**误判**，已按本节更正。
> 面试时可以放心说：**"每把出牌前后写全量快照，重启后按 phase 从断点续跑。"**

**仍然成立的三个弱点**（这些是真问题，值得主动讲）：
1. **TTL 固定 30 分钟**，且只在出牌前后写 —— 若房间长时间停在一把里（例如四家全部掉线等超时），键可能过期。
2. **`codeByUser` 只在内存**（`:71`）：重启后"返回房间"按钮要等第一个回来的人 `resurrect` 才恢复。
3. **写快照失败只 `log.warn`**（`:969-971`）→ 静默降级，没有任何告警或重试。

**自检问题**：
1. `snapshot.phase` 有哪两个取值？各自恢复到哪一步？
2. 快照为什么在"出牌前"和"弃牌后"各写一次，而不在每次摸牌后写？（提示：`prompt` 是阻塞点，写太频繁无收益）
3. 机器人（`userId < 0`）在快照里是怎么表示的？（`:960-966` + `:1526-1539`）

---

## 4. 链路四：规则引擎与结算

### 4.1 胡牌判定（你唯一需要"能干脆讲清"的算法）

| 文件 | 职责 | 关键方法 |
| --- | --- | --- |
| `engine/../HuLib.java`（根包，323 行） | **查表法**胡判定 + 番型原料 | `canHuConcealed(int[])`（★ 你做的**2/5/8/11/14 张暗牌**扩展）、`checkHu`、`isStandard/isSevenPairs/isThirteenOrphans` |
| `rules/HainanFan.java`（313 行） | 番型识别 | 清一色/混一色/碰碰胡/七对/十三幺/天胡/天听/地听 |
| `rules/HainanScore.java`（437 行） | 番型倍数 + 结算 | `multFans` / `multSum` / `settle` / `envOf` |
| `engine/model/RoundResult.java` | 一把的结果载体 | `win/winFull/draw`（被 `HainanRulesSelfTest` 大量构造） |

**为什么"暗牌张数扩展"是难点**（也是你简历上唯一属于你的算法工作）：
吃/碰/杠之后暗牌张数是 `3k+2`（k=0..4）→ `2/5/8/11/14`。原来的查表法只认 14 张，所以：
- `RoomManager.canHu`（`:758-772`）改成**只查暗牌**（副露是固定组，不再折回 14 张重拆）；
- `win()`（`:951-971`）才折回 14 张去取番型（番型需要看到完整牌型）。

**自检问题**（面试高频）：
1. 为什么七对/十三幺只在"14 张且无副露"时成立？（`HuLib.canHuConcealed` 里的 `switch(total)`）
2. `SUIT_EYE` / `SUIT_NO_EYE` 两张副表是干什么的？（`:276-322` `genEye/genNoEye/genMelds`，启动时生成一次；**表生成**与**查表**是两件事）
3. 15 秒/30 秒超时分别用在哪两种决策上？（`GameConfig` 里的 `discardTimeoutMs` / `actionTimeoutMs`）

### 4.2 结算（海南规则的口径都在这里）

`rules/HainanScore.settleRound(...)`（`:170` 起）是唯一出口，被 `MultiPlayerRoomService.runEngine:860-865` 调用：

```java
// MultiPlayerRoomService.java:860-865（原文）
HainanScore.Settlement st = HainanScore.settleRound(r, flow.dealer, flow.bottom, cfg,
        flMap, mMap, rm.deckSize(), rm.genChainHit(), dealerFirstGangSeat(dealer, rm));
Map<Seat, Integer> delta = st.delta;
for (Seat s : Seat.values()) {
    room.coins.put(s, room.coins.get(s) + delta.get(s));   // 金币累加
}
```
（`HainanScore.settle(RoundResult,Seat,int,HainanConfig)` `:76` 是**不带**细分信息的简化版，主要给自检/演示用；联机走的是 `settleRound`。）

**口径清单**（答辩时能背下来最好）：
- **庄相关笔**按"连庄后的当前底分"，**闲与闲之间**按"房间初始底分"，**点炮追加**按"初始底分"；
- 杠：三种杠都是三家各付一份；花分各自独立累加，真/假花不互斥；
- 牌墙 ≤19 张时点炮独担三家；牌墙剩 15 张流局（荒庄）；
- 基础分：胡 1 / 自摸 2 / 杠开 3；番型倍数命中相加；平胡仅无其它番型时兜底 = 1。

### 4.3 庄/令/底分的轮转

`rules/DealerFlow.java`（97 行，短小但重要）：`afterRound(RoundResult)` 决定下一把谁坐庄、底分是否翻倍、什么时候"打满东南西北四风"结束一轮。
调用点在 `MultiPlayerRoomService.runEngine:880-881`（每把结算后 `flow.afterRound(r)`）。

---

## 5. 前端要点（只看这 3 个地方就够）

| 主题 | 位置 | 说明 |
| --- | --- | --- |
| **本地视角座位翻译** ★ | `static/game.html:729-746` | `serverToLocal(s)` 把服务端座位换算成"自己=EAST 底部、右=下家、上=对家、左=上家"；`mapSeatKeys` / `translateMeld` / `translateMsg` 处理所有含座位的地图键与 `from` 字段 |
| WebSocket 连接串 | `game.html:800-810`、`room.html:167,224` | `ws://host/game?userId&code&seat`、`ws://host/room?userId` |
| 静态资源防缓存 | 各 html 的 `?v=20260908c` | 改前端后**必须 bump**，否则浏览器用旧文件（HANDOFF §7 的流程） |

> **前端 8 个文件的职责**：`index.html`（登录/注册）、`lobby.html`（大厅+设置+创建房间弹窗）、`room.html`（等待房）、`game.html`（牌桌，最大 76KB）、`records.html`（战绩）、`landscape.js/css`（横屏适配）、`music/`（BGM+音效+语音）。**没有前端框架**，全是原生 JS。

---

## 6. 建议的阅读顺序与时间预算

| 顺序 | 内容 | 预算 | 产出（验收） |
| --- | --- | --- | --- |
| 1 | §0 心智模型 + 三个接口源码（`engine/port/*` 共 3 个文件，约 90 行） | 0.5 h | 能说出引擎与外界的三条通道 |
| 2 | **链路一**：前端 → `onGameMessage` → `RoomManager.doTurn` → `RoomListener.onDiscard` | 2 h | 画出**带方法名**的时序图 |
| 3 | 链路一的后半：`prompt`（`:1184-1235`）+ `HumanPlayerController` 的槽位桥接 | 1 h | 能解释"异步真人操作"怎么接上"同步引擎" |
| 4 | 链路二（断线/托管）+ 前端 `serverToLocal` | 1 h | 能说出"为什么只剩机器人就解散" |
| 5 | 链路三（快照/恢复） | 1 h | 能说清快照存了什么、按 phase 分两路恢复 |
| 6 | 链路四（`HuLib` 暗牌扩展 + `HainanScore.settle`） | 2 h | 能干脆讲清"查表法 + 我做暗牌张数扩展" |
| 7 | 前端 3 个要点（§5） | 0.5 h | — |

**合计约 8 小时**，正好是你 HANDOFF 里说的"5~7 天里抽一天"的量级。

### 读的时候建议：边读边开一局

开两个浏览器窗口（两个账号）打一局，同时把 `docs/analyze-ws-recording.ps1` 的思路用上 —— **把 WS 消息按时间顺序对着代码看**，你会立刻看清"哪个事件触发了哪条消息"。
（注：采集脚本依赖的临时录制钩子已经在阶段 1 删掉了；若还想采集，可临时放回 `diag/WsRecorder`，见 `docs/stage0-CHANGES.md`。）

---

## 7. 本次阅读中发现并更正的两处（重要）

| # | 之前的说法 | 实测事实 | 影响 |
| --- | --- | --- | --- |
| 1 | "快照不含手牌，重启后只能从下一把续跑"（`docs/baseline-status.md` §2.3） | **错**。`RoomManager.snapshot()` 存 `deck` + 四条 `ps.hand` + 副露 + 花 + `phase`；`resume()` 能续当前那把 | 简历/答辩措辞可以更强："每把出牌前后写全量快照，重启后按 phase 从断点续跑" |
| 2 | 交接文档 §3.4 写"PLAYING 且有快照则续跑剩余局数" | 表述含糊，容易被理解成"弃掉当前把" | 按 §3 的实测口径讲 |

**仍待确认的一条**（我没能实测，留给你）：HANDOFF §5 说"报听状态写入快照但重启续跑中若处于报听回合可能需要人工核对" —— 报听相关状态是 `reportMode[]`（已进快照）与 `GameSnapshot.PlayerState.reportMode`（已恢复），但 `DealerFlow` 的报听窗口是否也完整恢复，值得你亲自验一次。

---

## 8. 读完之后的下一步

1. **把四条链路的时序图画出来**（纸笔或 PlantUML 都行）—— 这是你 HANDOFF 里定的判断标准；
2. 画的过程里必然冒出"这个方法是干嘛的"清单 —— **那份清单就是阶段 2 的 backlog**；
3. 然后才动阶段 2（拆 `MultiPlayerRoomService` 为 `RoomService` + `GameService`）。**读懂了再拆，拆错了能立刻看出来。**

> 如果读的过程中发现本文档有错（行号漂了、方法名记错了、结论不对），**直接在文档里改并标注日期** —— 这份导读的价值完全取决于它和代码的一致性。
