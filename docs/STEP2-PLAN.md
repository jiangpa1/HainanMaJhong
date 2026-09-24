# MultiPlayerRoomService 拆分 · 第 2 步方案

> 状态：**待执行**（2026-09-23 编写）
> 前置：第 1 步已完成（抽接口 + 实现改名入 `impl` 包，96 方法逐条比对一致）
> 验证手段：`docs/dump-mprs-methods.ps1` 生成方法清单，与 `docs/mprs-methods-step1.txt`（96 项）比对

---

## 1. 这一步要达成什么

第 1 步只做了「门面/实现」分层，**1758 行仍然全在一个文件里**。
第 2 步把**共享领域对象**从 `MultiPlayerRoomServiceImpl` 里剥离成顶层类，放进 `room/` 包 ——
这是后续第 3、4 步把「推送 / 快照 / 引擎」拆成独立 Service 的**前提**：
那些 Service 必须能引用 `Room` / `Member`，而不能再依赖某个类的内部类。

---

## 2. ⚠️ 这一步唯一的真风险：锁的归属

`Room` 对象**同时是共享状态和锁**。现有 9 处 `synchronized (room)`：

```
onClose:241   onGameMessage:279   onGameClose:328   bindGameSeat:374
joinRoom:496  leave:556           pendingReturn:597  fillBotsFor:648
startNowFor:682
```

**规则（不可违反）**：

- 抽成顶层类后，`Room` **仍然是那把锁**，所有模块继续 `synchronized (同一个 room 实例)`
- Java 的 `synchronized` **可重入**，所以"在锁内调用别的方法、那个方法再锁同一个 room"是安全的
- **绝对不要**"每个 Service 各配一把锁" —— 那是把一把锁变多把，会产生竞态

**执行时的硬性检查**：改完 `grep -c "synchronized (room)"` 必须仍为 **9**，且全部锁的还是 `Room` 实例。

---

## 3. 要搬出去的 7 个嵌套类型

| 现嵌套类型 | 行 | 性质 | 依赖 |
| --- | --- | --- | --- |
| `State`（enum） | 84 | 房间状态机 | 无 |
| `SwitchingController` | 107 | 实现 `PlayerController`，人/机切换 | `PlayerController`、`Seat`、`Action`、`Responder` → **叶子，无内部依赖** |
| `Member` | 86 | 座位成员（含两个 WS session） | `Seat`、`HumanPlayerController`、`SwitchingController`、2 个超时常量 |
| `Room` | 128 | **房间共享状态 + 锁对象** | `Member`、`State`、`EngineStart`、`RoomManager`、`GameSnapshot`、`HainanConfig`、`DealerFlow`、`Seat` |
| `EngineStart`（private） | 153 | 一块的起始参数 | `HainanConfig`、`DealerFlow`、`GameSnapshot`、`Seat` |
| `MemberInfo` | 170 | Redis 快照：成员 | 无 |
| `RedisRoom` | 176 | Redis 快照：整房 | `MemberInfo`、`GameSnapshot`、`HainanConfig` |

**目标包**：`hainanMahjong.room`（新建）

**访问性必须同步改的（否则编译不过）**：

| 成员 | 现状 | 改为 | 原因 |
| --- | --- | --- | --- |
| `Room(String, long)` 构造器 | 包私有 | `public` | impl 在 `service.impl`，不再同包 |
| `Member(...)` 构造器 | 包私有 | `public` | 同上 |
| `SwitchingController(...)` 构造器 | 包私有 | `public` | 同上 |
| `EngineStart` 类 | `private static final` | `public static final` | 被 6 个 impl 私有方法当参数类型用 |
| `EngineStart` 各字段 | 包私有 | `public` | impl 要读写 |
| `EngineStart()` 构造器 | 隐式 | 显式 `public` | 跨包 new |

---

## 4. 常量怎么办

`Member` 的构造器用到 `DISCARD_TIMEOUT_MS` / `ACTION_TIMEOUT_MS`（impl 的私有常量）。
搬包后不能再引用，**三个方案**：

| 方案 | 做法 | 评价 |
| --- | --- | --- |
| A | `Member` 构造器多收两个超时参数，impl 调用时传入 | 参数表变长，但语义清楚 |
| **B（推荐）** | 新建 `room/RoomConfig.java` 收起**全部 8 个可调项**，`Member` 与 impl 都引用它 | 常量有唯一出处，且以后调超时只改一处 |
| C | 在 `Member` 里复制一份常量 | ❌ 两处定义，迟早不一致 |

**推荐 B**。8 个常量：`MAX_MEMBERS`、`MATCH_HANDS`、`DISCARD_TIMEOUT_MS`、`ACTION_TIMEOUT_MS`、
`BIND_GRACE_MS`、`HAND_END_MS`、`DRAW_PAUSE_MS`（+ 其他 impl 顶层常量）。

---

## 5. 无连锁反应（已实测确认，这是关键结论）

搬这些类**不会**波及别的文件：

| 文件 | 是否引用嵌套类型 | 结论 |
| --- | --- | --- |
| `websocket/RoomWebSocketHandler` | ❌ 只调 `open/onMessage/onClose` | **不改** |
| `websocket/GameWebSocketHandler` | ❌ 只调 `openGame/onGameMessage/onGameClose` | **不改** |
| `config/WebSocketConfig` | ❌ 只注入两个 handler | **不改** |
| `controller/RoomController` | ❌ 只用接口的 `pendingReturn` | **不改** |
| `service/HumanPlayerController` | ❌ 零引用 | **不改** |

**所有改动都局限在 `impl` 那个文件 + 新增的 `room/` 包内。** 包名 `Room` 与
`RoomManager` / `RoomController` / `RoomSnapshotRepository` **不冲突**，不需要 import 别名。

---

## 6. 实施顺序（每小步都编译 + 比对）

| 小步 | 内容 | 验证 |
| --- | --- | --- |
| 2.1 | 建 `room/RoomConfig.java`（8 个常量） | 编译；impl 引用替换后常量值不变 |
| 2.2 | 搬 `State` + `SwitchingController`（两个叶子） | 编译；方法清单比对 |
| 2.3 | 搬 `Member`（依赖 2.2 + RoomConfig） | 编译；`Member` 构造器转 public |
| 2.4 | 搬 `EngineStart` + `MemberInfo` + `RedisRoom` | 编译；字段/构造器转 public |
| 2.5 | 搬 `Room`（最后，因为它依赖上面全部） | 编译；**`synchronized (room)` 仍 9 处** |
| 2.6 | 删掉 impl 里的原嵌套定义，补 `import hainanMahjong.room.*` | 编译 + 96 方法比对 + 9 测试 |

**注意**：`RoomListener` **不搬**（它是 `GameListener` 实现，属于第 3/4 步的"推送"模块）。

---

## 7. 验收判据

```powershell
# ① 编译 + 测试
$env:JAVA_HOME='C:\Users\ASUS\.jdks\ms-17.0.20.1-1'
& 'C:\Users\ASUS\tools\apache-maven-3.9.16\bin\mvn.cmd' -f 'F:\HainanMaJhong2\HainanMaJhong\pom.xml' -B test

# ② 方法清单与第 1 步基线比对（96 项，允许嵌套类名前缀变化）
& powershell -ExecutionPolicy Bypass -File 'F:\HainanMaJhong2\docs\dump-mprs-methods.ps1' `
  -Class 'hainanMahjong.service.impl.MultiPlayerRoomServiceImpl' `
  -NestedGlob 'MultiPlayerRoomServiceImpl$*.class' `
  -OutPath 'F:\HainanMaJhong2\docs\mprs-methods-step2.txt'
# 然后与 docs\mprs-methods-step1.txt 归一化比对：应仍然是 96 项、逐条对应

# ③ 锁没被拆散（必须仍是 9）
(Select-String -Path 'HainanMaJhong\src\main\java\hainanMahjong\service\impl\MultiPlayerRoomServiceImpl.java' `
  -Pattern 'synchronized \(room\)' | Measure-Object).Count

# ④ 新包结构
Get-ChildItem 'HainanMaJhong\src\main\java\hainanMahjong\room' | Select-Object Name
# 期望：Room.java Member.java SwitchingController.java State.java
#       EngineStart.java MemberInfo.java RedisRoom.java RoomConfig.java
```

**已知的比对噪声**：搬包后 `Room` 等从 `Impl$Room` 变成 `room.Room`，
所以清单比对**必须归一化类名前缀**（`impl.MultiPlayerRoomServiceImpl$Room` → `room.Room`），
否则会满屏假差异 —— 第 1 步已经踩过这个坑。

---

## 8. 这一步**不做**的事

- ❌ 不改 `RoomListener`（留给第 3 步"推送"模块）
- ❌ 不拆 Service（第 3/4 步）
- ❌ 不动任何逻辑、不改任何字段语义、不重排方法体
- ❌ 不动 `MysqlService` / `RoomSnapshotRepository` 的调用方式
