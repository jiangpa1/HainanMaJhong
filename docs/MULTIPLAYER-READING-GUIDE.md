# 海南麻将 · 多人联机模块 代码导读

> 面向：想读懂这个项目联机部分的人（也适用于面试前复习 + 答辩）
> 基准：2026-09-23，已完成分层重构（`service` / `service.impl` / `room` 三层）
> 前提：**本文只讲联机模块**；牌局规则本身（番型/结算/胡牌判定）见 `HainanScore`、`HainanFan`、`HuLib`，不在这里展开

---

## 0. 一句话概括

> **引擎不认识 Spring，也不认识 WebSocket。它对外只有三个接口：`PlayerController`（问出哪张牌）、`Responder`（等答复）、`GameListener`（喊事件）。联机只是这三个接口的一个实现。**

这句是理解整个模块的钥匙。读到后面如果迷路，回到这句。

---

## 1. 为什么读这个模块不能"按文件读"

联机部分由 **15 个类**协作，但它其实是 **3 条独立的链路**：

| 链路 | 入口 | 目的 |
| --- | --- | --- |
| **等待房** | `ws://host/room` | 创建/加入房间、人机补齐、开局 |
| **牌局** | `ws://host/game` | 出牌、吃碰杠胡、断线重连 |
| **断线恢复** | Redis `room:{code}` | 服务重启后凭房间码续跑本局 |

**按链路读**，一条一条读通；**不要按包名读**（那样会看到一堆互不相关的类）。

---

## 2. 分层地图

```
┌─────────────────────────────────────────────────────────┐
│ 接入层                                                    │
│   websocket/RoomWebSocketHandler   → ws://host/room      │
│   websocket/GameWebSocketHandler   → ws://host/game      │
│   controller/RoomController        → GET /api/room/pending│
│   （都在 config/WebSocketConfig 里注册）                   │
└────────────────────────┬────────────────────────────────┘
                         │ 只依赖 8 个方法的接口
┌────────────────────────▼────────────────────────────────┐
│ 门面层                                                    │
│   service/MultiPlayerRoomService（接口，47 行）            │
│   service.impl/MultiPlayerRoomServiceImpl（383 行）       │
│   职责：解析消息 → 分发；不写业务规则                        │
└───┬──────────────┬──────────────┬───────────────┬────────┘
    │              │              │               │
┌───▼──────┐ ┌─────▼─────┐ ┌──────▼──────┐ ┌──────▼────────┐
│ 房间模块  │ │ 引擎模块   │ │ 推送层       │ │ 快照层         │
│ RoomService│ │GameEngine │ │ GamePush     │ │ RoomSnapshot  │
│ 470 行    │ │ 857 行    │ │ 136 行       │ │ 69 行         │
│ 人怎么进出 │ │ 牌怎么打   │ │ 消息怎么发    │ │ 状态怎么落盘    │
└───┬──────┘ └─────┬─────┘ └─────────────┘ └───────────────┘
    │              │
    └──────┬───────┘
           ▼
┌─────────────────────────────────────────────────────────┐
│ 共享领域层  hainanMahjong.room（10 个类）                  │
│   Room         房间共享状态 + **锁对象**                   │
│   RoomRegistry 全局注册表（6 个 Map，独占）                 │
│   Member       座位成员（真人/机器人）                      │
│   State        房间状态机 WAITING/PLAYING/BETWEEN          │
│   EngineStart  一块的起始参数                              │
│   SwitchingController  人/机托管切换                       │
│   RoomConfig / RoomSupport / MemberInfo / RedisRoom        │
└─────────────────────────────────────────────────────────┘
```

**依赖方向是单向的**：接入层 → 门面 → 各模块 → `room`。
没有任何反向依赖。加新功能时**不要打破它**（否则会出现循环依赖）。

---

## 3. 第一链路：等待房（`ws://host/room`）

### 3.1 进去

`RoomWebSocketHandler` 只有 37 行，收到连接就调 `roomService.open(session)`，
收到消息就调 `onMessage`。**它不做任何判断**。

门面从 URL query 里取 `userId`（`parseLong(session,"userId",0)`），存进注册表：
`registry.bindClientSession(session, userId)`。

> ⚠️ **握手不带鉴权**：`/room?userId=` 是明文传身份。知道别人 userId 就能顶号。
> 这是已知缺陷（见 §9），不是设计意图。

### 3.2 五个指令

客户端发 JSON `{"type":"...", ...}`，门面 `onMessage` 分发：

| type | 走向 | 说明 |
| --- | --- | --- |
| `createRoom` | `rooms.create(...)` | 生成不重复 6 位码、房主**随机入座** |
| `joinRoom` | `rooms.join(...)` | 返回 true 表示四人到齐 → 门面调 `engine.startMatch(room)` |
| `fillBots` | `engine.fillBotsFor(...)` | 空缺补机器人，**仍停在等待房** |
| `startNow` | `engine.startNowFor(...)` | 房主点"开始" |
| `leaveRoom` | `leave(userId)` | 走 `rooms.handleMemberLeft` |

**为什么房主随机入座**：让整桌座位与"进房顺序"无关，避免固定东家带来的不公平。

**为什么 `fillBots` 和 `startNow` 是两个动作**：补机器人后房主还能再等真人进来，
所以补位 ≠ 开局。

### 3.3 座位分配

`RoomServiceImpl.join` 的顺序是：

1. 已在房内 → 换掉 `roomWs`，返回
2. `room.state == PLAYING` → 拒绝（"请直接返回牌局"）
3. 真人数已满 4 → 拒绝
4. `busyRoomCode(userId) != null` → 拒绝（已在别的进行中房间）
5. 先找**空位** `randomFreeSeat`；没有空位就**顶替一个机器人** `firstBotSeat`

---

## 4. 第二链路：牌局（`ws://host/game`）

### 4.1 三个端口（engine.port）

这是引擎与外界的**唯一契约**，共 3 个接口：

```java
// 引擎问"你要出哪张牌" —— 阻塞式询问
interface PlayerController {
    void onDiscardTurn(Seat, List<Integer> hand, int drawnTile, List<Integer> banned, Responder);
    void onActionChance(Seat, int discardedTile, List<Action> options, Responder);
    void onDrawChance(Seat, int drawnTile, List<Action> options, Responder);
    default boolean isAutoReport();
    default void prepareDiscard(boolean canReport);
}

// 引擎等"你的答复"
interface Responder {
    void discard(int tileKind);
    void act(Action action);
    void pass();
    default void report();
}

// 引擎喊"发生了这些事"
interface GameListener {
    void onShuffle(int deckSize);  void onDeal(Seat);
    void onDraw(Seat, int);        void onFlower(Seat, int);
    void onDiscard(Seat, int);     void onMeld(Seat, Meld);
    void onHu(Seat, HuResult, boolean selfDraw, boolean tianHu, int baoTing, int tile, Seat from);
    void onTimeout(Seat, String);  void onRoundDraw();
    void onResume();               void onEnd(RoundResult);
    default void onTurnStart(Seat, String kind, long timeoutMs);
    default void onReport(Seat, int mode);
}
```

**联机是这三个接口的一个实现**：

| 接口 | 联机实现 | 机器人实现 |
| --- | --- | --- |
| `PlayerController` | `HumanPlayerController`（把询问发前端、等回复） | `BotController`（本地算） |
| `Responder` | 由 `HumanPlayerController` 内部持有 | 直接返回 |
| `GameListener` | `GameEngineServiceImpl.RoomListener`（组装消息广播） | `PrintListener`（已删除，原为演示用） |

### 4.2 `SwitchingController`：人机切换的关键

引擎侧**永远只看到一个 controller 实例**（`Member.controller`）。
- 平时它的代理是 `HumanPlayerController`
- 玩家断线 → `setDelegate(new BotController())`
- 玩家回来 → `setDelegate(m.human)`

```java
class SwitchingController implements PlayerController {
    private volatile PlayerController delegate;
    public void setDelegate(PlayerController d) { this.delegate = d; }
    // 全部转发给 delegate
}
```

**这样设计的好处**：引擎不需要知道"这个座位现在是人还是机器"，
换代理不影响正在等待的询问（应答由 `HumanPlayerController` 自己的 pending 表管理）。

### 4.3 `HumanPlayerController`：阻塞怎么变非阻塞

引擎的询问是**阻塞**语义，但 WebSocket 是**异步**的。桥接方式：

1. `onDiscardTurn(...)` 把 `responder` 存进 `pending` 表（带自增 `reqId`），**立即返回**
2. 同时把消息发给前端（带 `reqId` + 超时毫秒，前端用来画倒计时）
3. 前端回 `{"type":"discard","reqId":3,"tile":12}` → 门面调 `m.human.discard(3, 12)`
4. `HumanPlayerController` 从 `pending` 取出 responder，调 `responder.discard(12)` → 引擎继续

**超时**：由 `RoomManager` 的 `mahjong-timer` 线程强制处理，走 `onTimeout` →
`SwitchingController` 代理到 `BotController` 自动出牌。

**断线重连**：`attach(newSender)` 换出口，`resendPending()` 把还在等的询问重发给新连接。

### 4.4 客户端发来的七个指令

门面 `onGameMessage` 分发：

| type | 处理 |
| --- | --- |
| `discard` | `m.human.discard(reqId, tile)` |
| `act` | `m.human.act(reqId, index)` |
| `pass` | `m.human.pass(reqId)` |
| `baoting` | `m.human.baoTing(reqId)` |
| `chat` | 广播 `{"type":"chat","seat":...,"text":...}`（≤80 字） |
| `rematch` | `engine.onRematch(room, userId)` |
| `leaveRoom` / `quit` | `rooms.handleMemberLeft(room, m)` |

---

## 5. 第三链路：断线重连与 Redis 快照恢复

这是这个项目**最值得讲**的地方。

### 5.1 关键规则

> **对局中任何人（含房主）离开/刷新都不结束游戏**：座位保留、由机器人托管，
> 回到大厅会看到"返回房间"按钮；只有 16 局块结束仍有人未归才解散。

### 5.2 三种断线，三种处理

| 情形 | 触发 | 处理 |
| --- | --- | --- |
| `/room` 连接断开 | `onClose` → `rooms.onClientSocketClosed` | 等待房：直接移除座位；对局中：保留 |
| `/game` 连接断开 | `onGameClose` | `gameWs = null`、`offline = true`、代理换机器人 |
| 主动 `leaveRoom` | `rooms.handleMemberLeft` | 对局中转为托管；等待房直接解散 |

`rooms.handleMemberLeft` 判断"是否已无真人在线"（`noHumanBound`），
全离线才 `disband` —— **不让 4 个机器人空跑**。

### 5.3 回来的时候发生什么

`/game?userId=&code=&seat=` 连回来 → 门面 `openGame` → `bindGameSeat`：

1. `registry.find(code)` 找房；找不到 → `rooms.resurrect(code)` 从 Redis 重建
2. `synchronized (room)` 里按 `seat` 找回 `Member`
3. `m.gameWs = session`、`m.offline = false`
4. `m.human.attach(...)` 把消息出口指向新连接
5. `m.controller.setDelegate(m.human)` **把代理换回真人**
6. 发 `welcome`（座位表）→ `match`（总局数）→ `hand_start`
7. `engine.pushSeatState(session, room, seat)` 补发**现场**：
   - `board`：全场弃牌 + 副露 + 花（**不含任何人的暗牌**）
   - `counts`：各家手牌张数
   - `hand`：**只发给本人**的暗牌 + 副露 + 花
8. 若本块还没跑起来（`blockThread == null && resumeSeed != null`）→ `engine.startEngine(...)` 续跑

> 🔴 **安全要点**：暗牌只能发给本人。`pushSeatState` 的 `hand` 那段用的是传入的
> `session`，不是广播 —— 改这块时务必保持。

### 5.4 Redis 快照

- 键：`room:{房间码}`，值：`RedisRoom` 的 JSON，**30 分钟过期**
- 写入时机：每把开始前、每次出牌检查点（`rm.setCheckpoint`）、每把结束、块结束
- 读取时机：`/room` 的 `joinRoom` 与 `/game` 的 `openGame` 找不到房间时

`RedisRoom` 的字段名**就是存档格式**，改字段名会让线上旧快照恢复不了
（`RoomSnapshotService` 的类注释里有警告）。

---

## 6. 标识符映射关系（最容易搞混的地方）

`RoomRegistry` 独占 **6 个 Map**，它们管的是"谁在哪里"：

| Map | 映射 | 谁写 |
| --- | --- | --- |
| `roomsByCode` | 房间码 → `Room` | `register` / `unregister` / `resurrected` |
| `codeByUser` | userId → 房间码 | `bindUser` / `unbindUser` |
| `userOfSession` | `/room` 的 sessionId → userId | `bindClientSession` |
| `gameUserOfSession` | `/game` 的 sessionId → userId | `bindGameSession` |
| `gameCodeOfSession` | `/game` 的 sessionId → 房间码 | 同上 |
| `gameSeatOfSession` | `/game` 的 sessionId → 座位 | 同上 |

**一条典型查询链**（"这个 WebSocket 是谁？"）：

```
sessionId → gameUserOfSession → userId → codeByUser → 房间码 → roomsByCode → Room
```

**注意区分两种 id**：
- `roomId`（房间码）是 **`VARCHAR(20)` 字符串**（6 位数字），不是 Long
- `sessionId` 是 `game_sessions.id`（BIGINT），是"一块/一轮"的数据库主键

**bot 的 userId 是负数**：`电脑·南=-1 / 西=-2 / 北=-3`（`RoomSupport.botUserId`），
与战绩页 `RecordsServiceImpl.nickname()` 的口径一致。**`isBot(m)` 就是 `userId < 0`**。

---

## 7. 并发模型（这一节最重要）

### 7.1 两把锁 + 三个线程

**线程**：

| 线程名 | 谁起的 | 干什么 |
| --- | --- | --- |
| `room-bind-<房间码>` | `engine.startMatch` | 跑 `runBlock`：先等座位连入（宽限期），再进跑把循环 |
| `room-engine-<房间码>` | `engine.startEngine` | 直接跑 `runEngine`（"再来一轮"与 Redis 续跑） |
| `mahjong-timer` | `RoomManager` 内部 | 出牌/吃碰杠超时强制处理 |

> **线程名不要改** —— 线上排查就靠它。

**锁**：

| 锁 | 保护 | 加锁方式 |
| --- | --- | --- |
| **Room 锁** | 单个房间的成员与状态 | `synchronized (room)`，共 **9 处** |
| **Registry 锁** | 6 个 Map 的复合操作 | `RoomRegistry` 的 `synchronized` 方法 |

**9 处 `synchronized (room)` 的分布**：

| 位置 | 文件 |
| --- | --- |
| `onGameMessage` / `onGameClose` / `bindGameSeat` / `leave` / `pendingReturn` | `MultiPlayerRoomServiceImpl`（5） |
| `join` / `onClientSocketClosed` | `RoomServiceImpl`（2） |
| `fillBotsFor` / `startNowFor` | `GameEngineServiceImpl`（2） |

### 7.2 锁的三条纪律

1. **锁对象是 `Room` 实例本身，不是类**。所以"持锁时调用另一个也锁同一 room 的方法"
   **是可重入的、安全的**。
2. **锁顺序**：**先 Room 锁，后 Registry 锁**。
   持 Registry 锁时**不得**去拿 Room 锁。`RoomRegistry` 的所有方法都不获取 Room 锁，
   所以这条永远不会被违反。
3. **锁内不做 I/O**。MySQL / Redis 调用全部放在锁外。
   例外核查点：`runEngine` 调 `snapshots.save` / `persistRound` 时**没有持锁**（引擎线程不锁 room）。

### 7.3 `runEngine` 为什么不长期持锁

引擎线程跑满一块（可能十几分钟），期间**持续改写** `room.coins` / `room.currentSnap` /
`room.flow`。如果它长期持锁，门面就完全无法处理 `/game` 消息（出牌会被卡住）。

所以引擎线程**不锁房间**，只在真正需要原子性的地方（成员读改）短暂加锁。
**代价**：`room.coins` 等字段可能读到"进行中"的值 —— 这是**有意接受的**，
因为它们是单调推进的，不需要快照一致性。

---

## 8. 一个完整的"打一把"时序

```
前端              门面            引擎             RoomManager        推送层
 │                 │               │                   │               │
 │─ws /game ──────►openGame        │                   │               │
 │                 │─bindGameSeat─►│                   │               │
 │◄──welcome/match/hand_start──────┤                   │               │
 │◄──board/counts/hand ────────────┤                   │               │
 │                 │               │                   │               │
 │                 │         (房主 startNow)           │               │
 │                 │─startMatch───►│                   │               │
 │                 │               │─new Thread(room-bind)             │
 │                 │               │─runBlock: 等 allBound             │
 │◄──start ────────┼───────────────┤                   │               │
 │                 │               │─runEngine         │               │
 │◄──hand_start ───┼───────────────┼───────────────────┤               │
 │                 │               │─new RoomManager──►│               │
 │◄──dealer ───────┼───────────────┼───────────────────┤               │
 │                 │               │                   │─onDeal───────►│
 │◄──hand/counts ──┼───────────────┼───────────────────┼───────────────┤
 │                 │               │                   │               │
 │◄──turn(30s) ────┼───────────────┼───PlayerController.onDiscardTurn─►│
 │─{discard,tile}─►onGameMessage   │                   │               │
 │                 │─human.discard►│───Responder.discard──────────────►│
 │◄──discard ──────┼───────────────┼───────────────────┼─onDiscard────►│
 │                 │               │                   │               │
 │◄──hu/end ───────┼───────────────┼───────────────────┤               │
 │◄──coins ────────┼───────────────┼───────────────────┤               │
 │                 │               │─sleepQuiet(2600ms)│               │
 │                 │               │  (下一把循环)      │               │
```

---

## 9. 已知缺陷与边界（面试会被追问）

| # | 问题 | 位置 | 说明 |
| --- | --- | --- | --- |
| 1 | **WebSocket 握手无鉴权** | `openGame` / `open` | `/room?userId=&code=&seat=` 明文传身份，知道 userId 就能顶号 |
| 2 | **只能单机部署** | 房间状态在内存 | 状态在 `MultiPlayerRoomServiceImpl` 的注册表里 → 多实例会各有一半房间 |
| 3 | **密码无盐 SHA-256** | 已迁移到 BCrypt | 新注册用 BCrypt，登录时对旧密文透明升级 |
| 4 | **零单元测试** | 整个联机模块 | `src/test` 只有 `PasswordUtilTest` |

**标准答案（第 2 题）**：状态外置到 Redis + 网关按房间码做**粘性路由**
（同一房间的请求必须落到同一台机器）。

---

## 10. 想深入时读什么

| 主题 | 文件 | 关键点 |
| --- | --- | --- |
| 房间状态字段 | `room/Room.java`（66 行） | 每个字段都有注释；`stop` 置位后引擎尽快退出 |
| 注册表与锁纪律 | `room/RoomRegistry.java`（204 行） | 类注释里有完整的锁顺序说明 |
| 引擎主循环 | `service/impl/GameEngineServiceImpl.java`（857 行） | 先看 `runBlock`（等绑定）再看 `runEngine`（跑把） |
| 超时与应答 | `service/HumanPlayerController.java`（202 行） | `pending` 表 + `reqId` + `resendPending` |
| 机器人决策 | `engine/bot/BotController.java`（126 行） | 托管时引擎看到的实现 |
| 房间生命周期 | `service/impl/RoomServiceImpl.java`（470 行） | `join` 的座位分配顺序值得逐行读 |

---

## 11. 加新功能时的落点

| 需求 | 改哪里 | 注意 |
| --- | --- | --- |
| 加一个新客户端指令 | 门面 `onMessage`/`onGameMessage` 加分支 | 别忘了 `RoomServiceImpl.denied(...)` 拒绝路径 |
| 加一个新服务端消息 | `GamePushService` 的 `sendJson`/`broadcastGame` | 消息 `type` 是前端契约，改名要同步前端 |
| 改超时/停顿 | `room/RoomConfig.java` | **只改这一处** |
| 加一个房间可调项 | `rules/HainanConfig.java` + 前端创建房间弹窗 | 会进 Redis 快照（`RedisRoom.cfg`） |
| 改快照格式 | `room/RedisRoom.java` | ⚠️ 字段名即存档格式，会让线上旧快照失效 |
| 改开局等待时长 | `RoomConfig.BIND_GRACE_MS`（现值 40s） | 影响 `runBlock` 的宽限期 |

---

## 12. 一分钟自检（能答上来就算读懂了）

1. 引擎为什么会阻塞？谁把它桥接成异步的？
2. 玩家断线后，引擎侧看到的是什么？为什么引擎不需要知道这件事？
3. `SwitchingController` 存在的意义是什么？
4. 玩家重连后，暗牌为什么必须只发给他本人？代码里是哪一段保证的？
5. 两把锁分别保护什么？锁顺序是什么？为什么这个顺序不会被违反？
6. 服务重启后，玩家凭什么恢复牌局？`resumeSeed` 是什么时候被消费的？
7. 为什么引擎线程不长期持有房间锁？代价是什么？
8. 为什么这个服务只能单机部署？怎么改？（这是必答题）

---

*本文基于 2026-09-23 的重构后代码编写。行数与常量值均为当日实测。*
