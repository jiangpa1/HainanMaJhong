# MultiPlayerRoomService 拆分 · 第 4 步d 方案（GameEngineService）

> 状态：**待执行**（2026-09-23）
> 前置：第 1/2/3 步 + 4a/4b/4c 均已完成，`mvn test` 绿（9 个测试）
> 验证手段：`docs/dump-mprs-methods.ps1` + 基线 `docs/mprs-methods-step1.txt`

---

## 1. 这是最后一块，也是唯一"带线程"的一块

前六轮搬的都是**数据结构、推送、注册表、房间生命周期**——都是同步代码。
这一块不同：它包含

- **两个线程**：`room-bind-*`（`startMatch`）与 `room-engine-*`（`startEngine`）
- **一个阻塞式状态机**：`runEngine` 跑满一块（16 把/四风），期间 `sleepQuiet` 小睡
- **一个引擎回调实现**：`RoomListener`（`GameListener`），在引擎线程里被回调

**所以这一步必须是「纯位移、零逻辑改动」**，任何顺手优化都可能变成难复现的时序 bug。

---

## 2. 要搬走的成员（约 600 行）

### 2.1 入口方法（被非引擎代码调用 ⇒ 必须进接口）

| 方法 | 现调用者 | 接口签名 |
| --- | --- | --- |
| `startMatch` | `onMessage`(join 之后)、`startNowFor` | `void startMatch(Room)` |
| `fillBotsFor` | `onMessage`(fillBots) | `void fillBotsFor(long userId)` |
| `startNowFor` | `onMessage`(startNow) | `void startNowFor(long userId)` |
| `onRematch` | `onGameMessage`(rematch) | `void onRematch(Room, long userId)` |
| `startEngine` | `bindGameSeat`（Redis 重建后续跑） | `void startEngine(Room, boolean waitBind, EngineStart seed)` |

### 2.2 引擎内部（private，不进接口）

`runBlock` / `runEngine` / `sleepQuiet` / `broadcastFinish` / `RoomListener`(内部类)

### 2.3 随监听器一起走的工具（只有 listener 用）

`persistRound` / `userIdOf` / `pushSeatState` / `buildDetailJsons` /
`dealerFirstGangSeat` / `allBound`

### 2.4 监听器要用的静态工具（两边都要用 ⇒ 进 `room` 包共享）

`isBot`(9 处) / `bound`(8 处) / `botUserId` / `botNickname` / `memberCount`

> 前四个目前同时被门面与监听器使用，**必须**提取到 `room` 包（建议放进
> `Member` 或新的 `RoomSupport`），否则两个类各留一份 = 两处定义。

---

## 3. ⚠️ 两个真正的风险点

### 风险 1：`startEngine` 的 `waitBind` 参数**是死参数**

```java
private void startEngine(Room room, boolean waitBind, EngineStart seed) {
    room.blockThread = new Thread(() -> runEngine(room, seed), "room-engine-" + room.code);
    room.blockThread.setDaemon(true);
    room.blockThread.start();
}
```

`waitBind` **在方法体里一次都没用到** —— 也就是说 `startEngine(room, false, seed)`
和 `startEngine(room, true, seed)` 行为**完全相同**。
（`true` 只在 `bindGameSeat` 那条路被传过。）

而"等四个座位连入"的逻辑其实在 **`runBlock`** 里（`BIND_GRACE_MS` 宽限期），
`runBlock` 由 `startMatch` 启动。**所以 `waitBind` 是历史残留。**

👉 **搬的时候必须原样保留这个参数**（不改行为）。是否删掉，等你单独决定 ——
这是一个独立的小清理，不要混在位移里做。

### 风险 2：`startMatch` 启动的线程跑的是 `runBlock`，而 `runBlock` 可能**同步**触发 `disband`

```
startMatch → new Thread(room-bind-X) → runBlock
   runBlock: 宽限期内无人连入 → rooms.disband(room) → stopAndClear
```

也就是说 `rooms.disband` 会在**新线程**里被调用，而 `disband` 内部会
`active.cancel()` 并 `registry.unregister(room)`。

**搬迁后这条链变成**：`GameEngineService.runBlock` → `RoomService.disband` →
`RoomService.stopAndClear` → `registry.unregister`。

这构成 **GameEngineService → RoomService → RoomRegistry** 的单向依赖，
没有环，**是安全的**。但要在类注释里写明这条链，避免以后有人反向依赖。

---

## 4. 搬迁后各类的依赖方向（无环）

```
        ┌──────────────────────────────┐
        │ MultiPlayerRoomServiceImpl   │  门面：WebSocket 入口 + 解析
        │  (open/onMessage/onGame*/…)  │
        └───┬───────┬────────┬─────────┘
            │       │        │
     ┌──────▼──┐ ┌──▼─────┐ ┌▼──────────────┐
     │ Room    │ │ Game   │ │ GameEngine    │
     │ Service │ │ Push   │ │ Service       │
     └──┬──────┘ └────────┘ └───┬───────────┘
        │                        │
        │   ┌────────────────────┘
        ▼   ▼
     ┌────────────────┐
     │ RoomRegistry   │  （独占 6 个 Map）
     └────────────────┘
```

- `GameEngineService` → `RoomService`（disband/stopAndClear）、`RoomRegistry`、`GamePushService`
- `RoomService` → `RoomRegistry`、`GamePushService`、`RoomSnapshotRepository`
- **没有任何反向依赖**（门面只被 WebSocket handler 依赖）

---

## 5. 执行顺序（每小步都编译 + 比对）

| 小步 | 内容 | 验证 |
| --- | --- | --- |
| 4d.1 | 把 `isBot`/`bound`/`botUserId`/`botNickname`/`memberCount` 提到 `room/` 包 | 编译；门面与监听器都改用它 |
| 4d.2 | 建 `GameEngineService` 接口 + 实现骨架（先搬 `startEngine`/`sleepQuiet`/`broadcastFinish`） | 编译 |
| 4d.3 | 搬 `runBlock`/`runEngine` + `RoomListener`（**最大的一步**） | 编译；方法清单比对 |
| 4d.4 | 搬 `startMatch`/`fillBotsFor`/`startNowFor`/`onRematch` | 编译 |
| 4d.5 | 删门面里已孤立的私有方法与 import | 无孤立方法、无未使用 import |
| 4d.6 | 全量比对 + 锁归属核对 | 见第 6 节 |

---

## 6. 验收判据

```powershell
# ① 编译 + 测试
$env:JAVA_HOME='C:\Users\ASUS\.jdks\ms-17.0.20.1-1'
& 'C:\Users\ASUS\tools\apache-maven-3.9.16\bin\mvn.cmd' -f 'F:\HainanMaJhong2\HainanMaJhong\pom.xml' -B test

# ② 88 个基线方法逐个审计（每个都要找到去向，或属于已知改名）
& powershell -ExecutionPolicy Bypass -File F:\HainanMaJhong2\docs\dump-mprs-methods.ps1 `
  -Class 'hainanMahjong.service.impl.MultiPlayerRoomServiceImpl' `
  -NestedGlob 'MultiPlayerRoomServiceImpl$*.class' `
  -AlsoDirs 'hainanMahjong/room' `
  -OutPath F:\HainanMaJhong2\docs\mprs-methods-step4d.txt

# ③ 锁归属：synchronized (room) 总数必须仍是 9
#    现分布：impl 7 + RoomService 2
#    搬迁后预期：impl 5 + RoomService 2 + GameEngine 2
#      （impl 减少的是 fillBotsFor / startNowFor 各 1 处）

# ④ 孤立私有方法 = 0；未使用 import = 0

# ⑤ 线程名必须不变（线上排查靠它）：room-bind-<code> / room-engine-<code>
Select-String -Path 'HainanMaJhong\src\main\java\hainanMahjong\service\impl\GameEngineServiceImpl.java' -Pattern 'room-bind-|room-engine-'

# ⑥ 时序常量必须不变
Select-String -Path 'HainanMaJhong\src\main\java\hainanMahjong\room\RoomConfig.java' -Pattern 'GRACE|HAND_END|DRAW_PAUSE'
```

---

## 7. 这一步**不做**的事

- ❌ 不删 `waitBind` 死参数（独立清理，另开）
- ❌ 不改任何时序常量（`BIND_GRACE_MS` / `HAND_END_MS` / `DRAW_PAUSE_MS`）
- ❌ 不改线程名（线上日志靠它定位）
- ❌ 不改 `runEngine` 内的任何循环/条件
- ❌ 不动 Redis 快照格式（`saveRoom` 在 `EngineStart`/`resumeSeed` 那几处保持原样）

---

## 8. 为什么建议在这之前先提交

工作区目前叠了 **bcrypt + APK 改造 + 六轮拆分**，全部未提交。
而这是**唯一带线程的一步**：一旦引入时序 bug，**没有干净的回滚点**会很难定位
（无法用 `git diff` 区分"引擎搬迁引入的"还是"之前哪一轮引入的"）。

现有成果已经是可编译、可测试、结构清晰的**好存档点**。
