# RoomManager 代码导读

> 面向：要读懂/改动海南麻将引擎核心的人（也适用于面试复习 + 答辩）
> 基准：2026-09-23，**已完成七轮拆分**（1232 → 906 行，协作类 8 个）
> 配套：`MULTIPLAYER-READING-GUIDE.md`（联机模块）、`CODE-READING-GUIDE.md`（全项目四条调用链）

---

## 0. 一句话概括

> **`RoomManager` = 一局牌的编排器。它自己不做规则判定，而是把活分给 8 个协作者，自己只负责"按什么顺序做"。**

读完这一句，再记住下面这张表，就能看懂这个类 90% 的代码。

---

## 1. 八个协作者（先看这张表，再读代码）

| 协作者 | 管什么 | 关键认知 |
| --- | --- | --- |
| `RuleSet`（+ `StandardRules` / `HainanRules`） | 规则差异 | **标准 vs 海南**的区别全在这一层 |
| `ActionDetector` | 算出"能做什么" | **纯读**，不改状态 |
| `RuleJudge` | 胡牌判定 | 与检测器**共用同一份**判定 |
| `TurnUtils` | 牌张判定/吃法枚举/排序 | 纯函数 |
| `PromptSupport` | 带超时地问玩家 | **唯一的并发点** |
| `GenChainTracker` | 海南"根链" | 自己的小状态机 |
| `ChiBan` | 海南"吃后禁打" | 纯函数 |
| `NoopGameListener` | 空监听器兜底 | 无状态单例 |

**读代码时的分工**：看到 `rules.xxx()` 就是"规则差异"，`detector.xxx()` 就是"找可用动作"，
`judge.xxx()` 就是"判胡"，`prompts.xxx()` 就是"要问玩家了"。

---

## 2. 它不做什么（边界）

理解一个类，先看它明确不干什么：

| 不做 | 谁做 |
| --- | --- |
| 不判番型分值 | `HainanScore` / `HainanFan` |
| 不管房间/座位/联机 | `room/` 包 + `service/impl/` |
| 不管网络、不管 Redis | `GamePushService` / `RoomSnapshotService` |
| 不认识 Spring | 它是纯 Java，**可以脱离容器单测**（就是靠这点，`RoomManagerSmokeTest` 才能 0.5 秒跑 400 把） |
| 不管"谁是人谁是机器人" | 那是 `PlayerController` 的实现（`HumanPlayerController` / `BotController`），由 `SwitchingController` 切换 |

---

## 3. 状态字段地图（哪些是一把的、哪些是整局的）

**整局不变**（构造时定下）：

```java
private final GameConfig config;          // 规则开关、庄家、超时、种子
private final GameListener listener;      // 事件出口
private final RuleSet rules;              // 标准 or 海南
private final RuleJudge judge;
private final ActionDetector detector;
private final GenChainTracker genTracker;
private final List<Integer> deck;         // 牌墙
private final List<Integer> discardPile;  // 牌河
private final Random random;              // 洗牌（种子来自 config.seed）
```

**每把重置**（`resetHandState()` 负责）：

```java
private final int[] reportMode = new int[4];  // 各座位报听：0无 / 1天听 / 2地听
private int totalDiscards;                    // 本把全场已出牌张数（天胡判定用）
private boolean anyMeldThisHand;              // 本把是否出现过吃碰杠（地听窗口关闭）
private List<Integer> eatBan;                 // 海南吃后禁打（只作用于下一手）
private int turnCount;                        // 本把出牌次数（config.maxTurns 限长用）
private RoundResult result;                   // 本把结果（getResult() 取）
```

**进行中（回合内瞬态）**：

```java
private volatile boolean roundOver;           // 本把结束
private volatile boolean cancelled;           // 被 cancel() 打断
private boolean lastDrawKongFlower;           // 这张是杠后/花后补牌（判"杠开"）
private Seat currentSeat;                     // 当前该谁
private boolean currentSkipDraw;              // 本回合不摸牌（碰/吃后直接出）
private boolean currentAllowWin;              // 本回合允许自摸
private String currentPhase = "TURN";         // 快照用："TURN" / "RESPOND"
private volatile Consumer<GameSnapshot> checkpoint;  // 每次出牌前的落盘回调
```

**⚠️ 为什么有的 `volatile` 有的不是**：`roundOver` / `cancelled` / `checkpoint` 会被
**其它线程**读写（`cancel()` 可能来自 WebSocket 线程），所以加 `volatile`；
其余字段只在引擎线程内访问，不需要。

---

## 4. `Player` 里有什么（读代码时需要知道的字段）

`RoomManager` 的绝大多数逻辑都是"读 `Player` 然后判断"：

| 字段 | 含义 |
| --- | --- |
| `hand` | 暗牌（`List<Integer>`，已排序） |
| `melds` | 副露（吃/碰/杠） |
| `flowers` | 摸到的花牌 |
| `lastDrawn` | 刚摸到的那张（-1 表示没摸） |
| `seat` | 座位 |
| `controller` | 决策出口（人 or 机器人） |
| `isBot` / `skipDraw` / `allowWin` | 由引擎/联机层设置的开关 |

**`p.count(t)`** 是高频方法，返回暗牌里 `t` 的张数。

---

## 5. 一局的完整流程

```
start()
 ├─ resetHandState()          清掉上一把的状态
 ├─ buildDeck()               136（无花）或 144（含花）
 ├─ shuffle()                 random 用 config.seed → 可复现
 ├─ deal()                    庄 14 张、闲 13 张，花牌即补
 ├─ prompts = new PromptSupport(定时器, listener)   ← 每把新建
 ├─ doTurn(config.dealer, skipDraw=true, allowWin=true)
 │    （庄家开局已 14 张，不摸牌）
 └─ finally { prompts.shutdown(); }   ← 每把关掉定时器

doTurn(seat, skipDraw, allowWin)
 ├─ 记录 currentSeat / currentPhase / doCheckpoint()
 ├─ 【荒庄】rules.shouldEndDraw(deckSize) → endDraw(); return
 ├─ 摸牌 drawOne(p)（含花牌连补）
 ├─ 【自摸/暗杠/补杠】while: detector.onDraw(p) → requestDrawAction → applyDrawAction
 ├─ 【出牌】tile = discard(p)
 │    ├─ requestDiscard(p)      ← 问玩家（带超时）
 │    ├─ 更新 discardPile / playerDiscards / totalDiscards
 │    ├─ genTracker.onDiscard(...)   海南：根链跟踪
 │    ├─ doCheckpoint()        先落 Redis 再广播（保证收到消息时快照已存）
 │    └─ listener.onDiscard(...)
 └─ continueAfterDiscard(seat, tile)
      ├─ resolveDiscard(discarder, tile)  ← 问其余三家
      │    └─ detector.onDiscard(q, tile, discarder) 算出可选动作
      │       → 按优先级询问（胡 > 杠 > 碰 > 吃）
      └─ 依据结果决定下一家：
           · 没人要  → doTurn(discarder.next(), 摸牌, 允许胡)
           · 碰/吃   → doTurn(claim.seat, 不摸牌, 不允许胡)
           · 明杠    → doTurn(claim.seat, 摸牌, 允许胡)
```

---

## 6. 三个"规则分叉"的落点

读到一个判断不确定"是不是海南才有"时，看 `rules`：

| 现象 | 方法 |
| --- | --- |
| 牌墙剩 15 张就荒庄 | `rules.shouldEndDraw(deckSize)` |
| 胡牌要有番 | `rules.canHu(concealed, ...)` |
| 补杠时问别人要不要抢 | `rules.canQiangGang(ganger, tile)` |
| 吃完后某些牌不能打 | `rules.canChiDiscard(...)` / `rules.chiBanAfterEat(...)` |
| 报听后锁手 | `rules.locksHandWhenReported(seat)` |
| 天胡（首轮自摸） | `rules.isTianHu(selfDraw, totalDiscards)` |

**还有 4 处 `config.hainan` 是刻意留在 `requestDiscard` 里的**（报听流程与回合控制流交织），
`RuleSet` 的类注释里写了理由。**不要以为漏了。**

---

## 7. ⚠️ 并发模型（最容易出事的地方）

### 7.1 谁在跑

`start()` / `resume()` 是**同步阻塞**的 —— 它跑完一整把才返回。
联机场景下调用者是 `room-engine-*` 线程。

**但过程中会牵扯另外两条线程**：

| 线程 | 干什么 |
| --- | --- |
| `room-engine-*`（调用者） | 跑 `start()`，阻塞在 `prompts.prompt(...)` 里 |
| `mahjong-timer` | `PromptSupport` 内部的定时器，超时后强制应答 |
| WebSocket 线程 | 玩家出牌时调 `Responder.discard(...)`，唤醒引擎线程 |

### 7.2 `PromptSupport.prompt` 是唯一的会合点

```java
引擎线程 ──调用──► prompt(...)
                    ├─ 把 reply 闭包交给 PlayerController（发消息给前端）
                    ├─ 调度一个定时任务（超时 → force）
                    └─ latch.await()   ← 阻塞在这里
                              ▲
        玩家应答（WS线程）────┤  reply.accept(...)
        超时（timer线程）─────┘  force.run()
```

**`done[0]` 一次性开关**保证先到者生效、后到者被丢弃 ——
玩家在超时后一秒才出牌，那次出牌会被**安全忽略**。

**`cancel()`** 通过 `prompts.forceNow()` 立即唤醒（否则要一直等到超时）。

### 7.3 加锁纪律

- **不要给 `RoomManager` 加锁**。它跑在引擎线程上，房间级并发由外层的
  `synchronized (room)` 负责（见 `MULTIPLAYER-READING-GUIDE.md` §7）。
- 唯一需要跨线程可见的字段用 `volatile`（`roundOver` / `cancelled` / `checkpoint`）。
- `PromptSupport` 内部用 `synchronized (lock)` 保护 `done` / `box`，**那是它自己的锁**。

---

## 8. 快照与恢复

| 方法 | 作用 |
| --- | --- |
| `snapshot()` | 把当前状态打成 `GameSnapshot`（牌墙/牌河/各家暗牌/副露/花/报听/阶段） |
| `resume(snap)` | 从快照续接**当前这一把**，不重新发牌 |
| `setCheckpoint(consumer)` | 每次出牌前回调 —— 联机层用它落 Redis |

**为什么 `currentPhase` 存在**：快照要在"出牌后、等其余三家响应"这个**中间态**也能恢复，
所以 `discard()` 里先置 `currentPhase = "RESPOND"` 再落盘；`resume` 时据此决定
是"继续等响应"还是"轮到下一家"。

---

## 9. 公开 API（只有 11 个方法）

**生命周期**：

```java
RoomManager(GameConfig, Map<Seat,PlayerController>, GameListener)
void start()                        // 跑完一整局（阻塞）
void resume(GameSnapshot)           // 从快照续跑
void cancel()                       // 打断并立即唤醒
```

**读状态**（联机层推送用）：

```java
RoundResult getResult()
Player getPlayer(Seat)
List<Integer> getPlayerDiscards(Seat)
int deckSize()
boolean genChainHit()
boolean wasLastDrawKongFlower()
```

**快照**：

```java
void setCheckpoint(Consumer<GameSnapshot>)
GameSnapshot snapshot()
```

**常量**：`public static final int REPORT_TILE = -99;`
（玩家选"报听"时 `Responder.report()` 回传的哨兵值）

> **改动提示**：这 11 个方法是 `GameEngineServiceImpl` 与联机层的契约。
> 加方法要考虑是否该放进 `RuleSet` / `ActionDetector` 而不是这里。

---

## 10. 常见改动落点

| 想改什么 | 改哪里 | 注意 |
| --- | --- | --- |
| 加/改一条海南规则 | `engine/rule/HainanRules.java` | 别在 `RoomManager` 里加 `if (config.hainan)` |
| 加一种可做的动作 | `ActionDetector` + `Action` | 记得在 `TurnUtils.sortByPriority` 的优先级里有位置 |
| 改判胡逻辑 | `RuleJudge` + `HainanRules.canHu` | 两处共用，别只改一处 |
| 改超时/停顿 | `room/RoomConfig`（联机层）或 `GameConfig`（引擎层） | |
| 加一个快照字段 | `GameSnapshot` + `snapshot()` + `resume()` **三处都要改** | 漏一处会导致恢复后状态不一致 |
| 改洗牌 | `shuffle()` | **必须保留 `config.seed` 分支**，否则回归测试失去可复现性 |

---

## 11. 改完必须跑的回归测试

```powershell
$env:JAVA_HOME='C:\Users\ASUS\.jdks\ms-17.0.20.1-1'
& 'C:\Users\ASUS\tools\apache-maven-3.9.16\bin\mvn.cmd' `
   -f 'F:\HainanMaJhong2\HainanMaJhong\pom.xml' -B test -Dtest=RoomManagerSmokeTest

# 结果指纹写在 target\engine-regression.txt
Get-Content 'F:\HainanMaJhong2\HainanMaJhong\target\engine-regression.txt'
```

**当前基线**（4 个机器人 × 400 把海南规则，完全可复现）：

```
hands=400 errors=0 wins=379 draws=21 qiangGangs=4
```

**这个数字变了就说明行为被改了。** 它覆盖了正常胡、流局、抢杠三条路径；
`errors=0` 表示没有一局卡死或抛异常。

> 局限：它只是"不崩 + 统计一致"，**不校验每一手是否合法**。
> 更细的规则正确性要靠 `src/test` 下的规则单测（目前只有这一个引擎测试）。

---

## 12. 一分钟自检（能答上来就算读懂了）

1. `RoomManager` 自己判胡吗？判定逻辑在哪？为什么两处要共用？
2. 标准规则和海南规则的差异，集中在哪个类？
3. `prompt()` 阻塞在什么上？谁负责唤醒它？超时后玩家再出牌会怎样？
4. 为什么 `roundOver` 是 `volatile` 而 `turnCount` 不是？
5. `start()` 里 `prompts` 为什么每把要重建？
6. 碰/吃之后为什么是 `doTurn(seat, skipDraw=true, allowWin=false)`？
7. `currentPhase` 是干什么的？去掉它会怎样？
8. 改了 `shuffle()` 里的种子逻辑，哪个测试会失效？

---

*本文基于 2026-09-23 七轮拆分后的代码编写，行数/字段/API 均为当日实测。*
