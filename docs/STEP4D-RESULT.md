# 第 4 步d 执行结果（GameEngineService）

> 执行日期：2026-09-23
> 前置：第 1/2/3 步 + 4a/4b/4c 已完成

## 结果

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `impl/MultiPlayerRoomServiceImpl.java` | 470 | 门面：WebSocket 入口 + 消息解析 |
| `impl/GameEngineServiceImpl.java` | 860 | 引擎：开局 / 跑块 / 跑把 / 监听 / 结算持久化 |
| `impl/RoomServiceImpl.java` | 470 | 房间生命周期与成员管理 |
| `impl/GamePushServiceImpl.java` | 136 | 消息组装与发送（唯一发送出口） |
| `impl/RoomSnapshotServiceImpl.java` | 69 | Redis 快照落盘 |

**门面从原始 1758 行降到 470 行**（累计 -73%）。

## 搬迁清单

- 进 `GameEngineService` 接口（5 个）：`startMatch` / `fillBotsFor` / `startNowFor` / `onRematch` / `startEngine`
- 引擎内部：`runBlock` / `runEngine` / `sleepQuiet` / `broadcastFinish` / `RoomListener`
- 随监听器走：`persistRound` / `userIdOf` / `buildDetailJsons` / `dealerFirstGangSeat` / `allBound` / `newFlow` / `noHumanBound`
- `pushSeatState`：**public 但不进接口**（需具体 RoomManager，只给同包门面用）
- 新 `room/RoomSupport.java`：`isBot` / `bound` / `botUserId` / `botNickname` / `memberCount`（门面与引擎共用）

## 验证证据

| # | 检查 | 结果 |
| --- | --- | --- |
| 1 | `mvn test` | Tests run: 9, Failures: 0 / BUILD SUCCESS |
| 2 | 88 个基线方法逐个审计 | 全部找到去向（4 个改名，3 个 lambda 为编译器生成） |
| 3 | 锁归属 | impl 5 + RoomService 2 + GameEngine 2 = **9**（与原先逐一对应） |
| 4 | 门面孤立私有方法 | 0 |
| 5 | 未使用 import（三文件） | 0（清理 21 个） |
| 6 | 线程名 | `room-bind-<code>` / `room-engine-<code>` **未变** |

## 保留的设计决定

- **`startEngine` 的 `waitBind` 参数是死参数**：方法体不使用它。为不改调用方而保留，
  已在接口与实现上注明。是否删除另开一项。
- **引擎注入用的是具体类 `GameEngineServiceImpl`**（而非接口），只为调用不进接口的
  `pushSeatState`。代价是门面与具体实现耦合；换实现时要一起改。
- **锁顺序约束**：持有 Registry 锁时不得获取 Room 锁；Registry 方法不获取任何 Room 锁。

## ⚠️ 未完成的验证（诚实记录）

**引擎是唯一带线程的模块，而虚拟机未开机 ⇒ 从未连库实测。**

结构性验证（编译、方法清单、锁归属、静态审计）全部通过，但以下**运行时行为未验证**：

1. 开局线程 `room-bind-*` 是否正常起停
2. `runEngine` 跑满一块（四风）的完整流程
3. 宽限期内无人连入 ⇒ `runBlock` 同步触发 `disband` 这条链
4. Redis 快照续跑（`resumeSeed` → `startEngine`）
5. WebSocket 消息类型与阶段0基线 `docs/ws-recording.log` 是否仍逐字节一致

**连库后必须按 `docs/ARCH-REFACTOR-STAGE0-1.md` 的协议 diff 手段复核一次。**

## 本次踩的坑（可复用）

- **不要"一次性算出多个成员的 span 再统一删除"**：span 会相互重叠（向上吸收注释时尤其），
  导致误删大段代码。正确做法：**一次只删一个，每次重新定位**，并优先从文件末尾往前删。
- **多行签名的方法，其 span 计算容易只覆盖到签名末行**，留下折行的参数行成为"孤儿"。
  删完必须 `mvn compile` 复核，不能只看行数变化。
- **删除前先备份**（本次靠 `%TEMP%\MPRSI.before-*` 恢复了两次）。