# 海麻架构调整指导文档 —— 对齐 Learning 的分层

> **文档性质**：先写设计 → 再动手 → 把实测结果和踩坑回填到本文末尾（沿用你的既定流程）。
> **编写时间**：2026-09-20
> **适用仓库**：`F:\HainanMaJhong2`（分支 `main`，HEAD `b94fed7`，工作区干净）
> **目标**：把 `HainanMaJhong` 模块的包结构从「按"是不是 web"分」改成「按"层"分」，与 `Learning` 一致；**不改任何功能行为**。
> **前置阅读**：`F:\HainanMaJhong2\HANDOFF.md` §2 目录、§4 协议；`C:\Users\ASUS\Desktop\Learning\LearningHANDOFF.md`（目标态参照）。

---

## 0. 一句话结论

Learning 值得抄的不是"controller/service/mapper 这几个名字"，而是它背后的三条铁律：

1. **一层只干一件事**：Controller 只做「收参数 → 调 Service → 包返回」；Service 只做业务编排；Mapper/Repository 只碰数据库。
2. **跨层只传对象，不传数据库行、不传裸 Map**：入口用 `dto`、出口用 `vo`、入库用 `pojo`。
3. **统一出口**：所有 HTTP 响应经 `Result<T>`，所有异常经 `GlobalExceptionHandler`。

海麻现在违反的正是这三条（`web/` 里 1635 行的 `MultiPlayerRoomService` 既是 Controller 又是 Service 又是引擎调度器，返回全是 `Map<String,Object>`，`RecordsController` 直接拼 Map）。所以本次调整 = 把这三条立起来。

**但要提前说清一件事**：海麻**不能**照抄 Learning 的「controller/service/mapper」三层就完事 —— 它比 Learning 多了两块 Learning 没有的东西（**游戏引擎**和**长连接**）。硬塞进三层会得到一个四不像。所以目标态是 **7 层**（见 §2），其中 `engine`（引擎）与 `websocket`（长连接）是海麻自己的层，`dto/vo/common/config` 才是对齐 Learning 的部分。

---

## 1. 现状盘点（实测）

### 1.1 当前包结构

```
HainanMaJhong/src/main/java/hainanjong/          ← Spring 扫描根
├── HainanMaJhongApplication.java   13 行
├── FanType.java                    23 行   ← 番型枚举
├── HuLib.java                     323 行   ← 查表法胡判定
├── HuResult.java                   46 行
├── Main.java                      347 行   ← fuzz 自检 main()（在 main 源里）
├── game/    15 个文件 1686 行  ← 引擎 + 「HumanPlayerController(真人控制器)」混住
├── rules/    6 个文件 1288 行  ← 番型/结算/规则自检
├── service/  2 个文件  338 行  ← MysqlService(310) + RedisService(28)
└── web/     10 个文件 2470 行  ← ★ 病灶：REST + WebSocket + 真人控制器 + 1635 行的 MultiPlayerRoomService
```

### 1.2 三个具体病灶（都能对应到 Learning 的铁律）

| # | 现状 | 对应 Learning 的哪条铁律 | 证据 |
| --- | --- | --- | --- |
| 1 | `MultiPlayerRoomService`（1635 行，`@Service`）同时承担：WS 端点回调（`open/onMessage/onClose/openGame/onGameMessage`）、房间生命周期、引擎调度线程（`runEngine`）、消息序列化（`sendJson/broadcastGame/pushSeatState`）、Redis 快照读写 | 「一层只干一件事」 | `web/MultiPlayerRoomService.java:195-336, 693-935, 938-1039, 1344-1412` |
| 2 | 全部 REST 接口返回 `Map<String,Object>` 手拼（`AuthController:125-150`、`RecordsController:52-64,87-139`） | 「跨层传对象 + 统一出口」 | 前端也只能靠 `data.success / data.userId` 硬猜字段 |
| 3 | `HumanPlayerController`（真人玩家的引擎回调实现）放在 `web/`，却被 `service` 层与引擎回调使用；引擎里要 `new HumanPlayerController(...)` | 「依赖方向单向」 | `web/HumanPlayerController.java:4-5` 反向 import `game.PlayerController`/`game.Responder`；`MultiPlayerRoomService:99` 直接 new 它 |

### 1.3 已有资产（不要动、不要重写）

- **引擎自检有价值**：`Main.java` 用暴力拆解 `bruteStandard` 对照查表法（`Main.fuzz`、`fuzzConcealedSizes`）、`rules/HainanRulesSelfTest.java`（290 行）。它们只是**放错了位置**（在 `src/main` 下，构建时不会跑）。
- **`MysqlService` 的建表/兼容旧表逻辑**（`MysqlService:35-114`）是能跑的，本次**不重写**。
- **`RedisService`（28 行）** 抽象粒度正好，本次只搬位置、不改签名。
- **前端 8 个 html/js** 与后端通过**严格字段名**耦合（`data.success`、`data.userId`、`data.nickname`、`data.records`、`data.rounds`、`data.notFound`、`msg.type/msg.seat/msg.coins…`）—— 这是本次最大的回归风险来源，见 §6。

---

## 2. 目标态包结构（对齐 Learning，7 层）

> **阶段 1 完成度（2026-09-20 实测，commit `c9edd8c`）**：✅ 已就位 = `config/ controller/ websocket/ engine/{model,port,bot,dev} repository/RoomSnapshotRepository`；⏳ 未做 = `common/`、`service/` 的接口拆分（`RoomService`/`GameService`/`UserService`/`RecordService`）、`repository/` 的 `UserRepository`/`GameRecordRepository`、`domain/`。
> 即：**物理分层已完成，业务拆分留待阶段 2/3**。另有 3 处与本文设计不同的地方，见 §11。

```
HainanMaJhong/src/main/java/hainanjong/
├── HainanMaJhongApplication.java        ← 启动类留在根（扫描根，不能挪走）

├── common/            ← 对齐 Learning 的 com.jiangpa.common
│   ├── Result.java                    【新】统一响应体 {code,message,data}
│   ├── PageResult.java                【新·可选】分页包装（对齐 Learning）
│   └── CacheKeys.java                 【新】Redis key 前缀集中处
│
├── config/            ← 对齐 Learning 的 com.jiangpa.config
│   ├── WebMvcConfig.java              ① AppWebConfig.java（HTML no-cache）
│   └── WebSocketConfig.java           ② 原 web/WebSocketConfig.java（+ 来源白名单）
│
├── controller/        ← 对齐 Learning 的 com.jiangpa.controller（只做三件事）
│   ├── AuthController.java            ③ 原 web/AuthController.java
│   ├── RecordsController.java         ④ 原 web/RecordsController.java
│   ├── RoomController.java            ⑤ 原 web/RoomApiController.java（改名）
│   └── AppDownloadController.java     ⑥ 原 web/AppDownloadController.java
│
├── websocket/         ← 【海麻独有】长连接接入层（Learning 没有，不能硬套）
│   ├── RoomWebSocketHandler.java      ⑦ 原 web/RoomWebSocketHandler.java
│   └── GameWebSocketHandler.java      ⑧ 原 web/MultiGameWebSocketHandler.java（改名）
│
├── service/           ← 应用层：编排，不碰协议、不碰 SQL
│   ├── RoomService.java               【接口·新】+ impl/RoomServiceImpl.java   ⑨ 从 MultiPlayerRoomService 拆出「房间生命周期」
│   ├── GameService.java               【接口·新】+ impl/GameServiceImpl.java   ⑩ 从 MultiPlayerRoomService 拆出「块调度/引擎线程」
│   ├── UserService.java               【接口·新】+ impl/UserServiceImpl.java   ⑪ 从 AuthController 抽出的注册/登录/改昵称改密码
│   ├── RecordService.java             【接口·新】+ impl/RecordServiceImpl.java ⑫ 从 RecordsController 抽出战绩查询
│   └── HumanPlayerController.java     ⑬ 原 web/HumanPlayerController.java（真人玩家的引擎回调实现）
│
├── repository/        ← 数据访问层（对齐 Learning 的 mapper 角色）
│   ├── UserRepository.java            【接口·新】+ impl/UserRepositoryImpl.java（@Repository）
│   ├── GameRecordRepository.java      【接口·新】+ impl/GameRecordRepositoryImpl.java
│   └── RoomSnapshotRepository.java    【新】原 service/RedisService.java 改（room:{code} 读写）
│
├── engine/            ← 【海麻独有】纯 Java 游戏引擎：零 Spring、零 WebSocket
│   ├── RoomManager.java               ⑭ 原 game/RoomManager.java（1105 行）
│   ├── model/  Action, Meld, Player, RoundResult, GameSnapshot, GameConfig, Seat
│   ├── port/   PlayerController, Responder, GameListener          ← 引擎对外的三个接口
│   ├── bot/    BotController
│   └── dev/    GameDemo, EngineSmokeTest, PrintListener           ← 开发用，Stage 5 再处理
│
├── domain/            ← 领域值对象（不是数据库表！）
│   ├── FanType.java                   ⑮ 原根包 FanType.java
│   ├── HuResult.java                  ⑯ 原根包 HuResult.java
│   ├── RoomState.java                 【新】enum WAITING/PLAYING/BETWEEN（现为内部 enum）
│   ├── RoomCode.java                  【新·可选】6 位码校验（现散在 isSixDigit）
│   ├── EngineStart.java               【新】现为 MultiPlayerRoomService 内部类
│   └── RoomSnapshot.java              【新】现为内部类 RedisRoom + MemberInfo
│
├── rules/             ← 规则层：番型与结算（保持原包，只加注释说明归属）
│   ├── HainanConfig.java  HainanFan.java  HainanScore.java  DealerFlow.java  RuleEnv.java
│   └── HainanRulesSelfTest.java       → Stage 5 迁到 src/test
│
├── HuLib.java                         ← 查表库，留在根包（受 §6 风险 R3 约束，见下）
└── FanType / HuResult                 → 见 domain/（Stage 4 再动，避免 import 大爆炸）

HainanMaJhong/src/test/java/hainanjong/    ← 【新】对齐 Learning 的 src/test/java
├── HuLibTest.java                     【新】把 Main.fuzz 的对照逻辑变成 JUnit
├── rules/HainanRulesSelfTestTest.java 【新】包一层 JUnit 入口
└── engine/EngineSmokeTestTest.java    【新】同上
```

### 2.1 与 Learning 的层级对应表（写简历/答辩时直接念这张）

| 层 | Learning | 海麻 | 职责 |
| --- | --- | --- | --- |
| 接入层（HTTP） | `controller/` | `controller/` | 收参数、调 Service、包 `Result` |
| 接入层（长连接） | — | `websocket/` | WS 回调 → 转成 Service 调用 |
| 应用层 | `service/` + `service/impl/` | `service/` + `service/impl/` | 事务边界、流程编排 |
| 引擎层 | — | `engine/` | 牌局规则执行，纯 Java 可单测 |
| 规则层 | — | `rules/` | 番型/结算口径 |
| 数据层 | `mapper/` + `pojo/` | `repository/` + `domain/` | SQL 与领域对象隔离 |
| 横切 | `common/` `config/` `interceptor/` `exception/` `utils/` | `common/` `config/` `interceptor/`（Stage 3 补） | 统一响应、拦截器、异常 |

---

## 3. 迁移映射表（逐文件，可直接照着改）

> 约定：①~⑯ = 本文档的移植编号，方便你在 commit message 里引用（如 `refactor(arch): ①② 配置层归位`）。

### 3.1 只改 `package` 与 `import`（不改逻辑）

| 编号 | 现路径 | 新路径 | 备注 |
| --- | --- | --- | --- |
| ① | `web/AppWebConfig.java` | `config/WebMvcConfig.java` | 同时改类名（类名与 `WebSocketConfig` 不冲突） |
| ② | `web/WebSocketConfig.java` | `config/WebSocketConfig.java` | 顺手把 `setAllowedOrigins("*")` 换成白名单（见 §5 阶段 4） |
| ③ | `web/AuthController.java` | `controller/AuthController.java` | 逻辑在 Stage 3 再抽 Service |
| ④ | `web/RecordsController.java` | `controller/RecordsController.java` | 同上 |
| ⑤ | `web/RoomApiController.java` | `controller/RoomController.java` | 类名去掉 Api |
| ⑥ | `web/AppDownloadController.java` | `controller/AppDownloadController.java` | 建议顺手加 `@RequestMapping`，路径不变 |
| ⑦ | `web/RoomWebSocketHandler.java` | `websocket/RoomWebSocketHandler.java` | — |
| ⑧ | `web/MultiGameWebSocketHandler.java` | `websocket/GameWebSocketHandler.java` | 类名去掉 Multi；`WebSocketConfig` 里的引用同步 |
| ⑬ | `web/HumanPlayerController.java` | `service/HumanPlayerController.java` | 真人玩家的 `PlayerController` 实现，属应用层 |
| ⑭ | `game/RoomManager.java` | `engine/RoomManager.java` | 1105 行，**只改 package 与 import** |
| — | `game/Action.java` | `engine/model/Action.java` | — |
| — | `game/Meld.java` | `engine/model/Meld.java` | — |
| — | `game/Player.java` | `engine/model/Player.java` | — |
| — | `game/RoundResult.java` | `engine/model/RoundResult.java` | — |
| — | `game/GameSnapshot.java` | `engine/model/GameSnapshot.java` | 快照结构，被 `repository` 与 `websocket` 使用 |
| — | `game/GameConfig.java` | `engine/model/GameConfig.java` | — |
| — | `game/Seat.java` | `engine/model/Seat.java` | — |
| — | `game/PlayerController.java` | `engine/port/PlayerController.java` | 引擎对外接口 |
| — | `game/Responder.java` | `engine/port/Responder.java` | 引擎对外接口 |
| — | `game/GameListener.java` | `engine/port/GameListener.java` | 引擎对外接口 |
| — | `game/BotController.java` | `engine/bot/BotController.java` | — |
| — | `game/PrintListener.java` | `engine/dev/PrintListener.java` | 开发用 |
| — | `game/GameDemo.java` | `engine/dev/GameDemo.java` | 开发用 |
| — | `game/EngineSmokeTest.java` | `engine/dev/EngineSmokeTest.java` | Stage 5 迁 `src/test` |
| — | `service/RedisService.java` | `repository/RoomSnapshotRepository.java` | Stage 2 再改造成"接口 + Serializer"（见 §4.4） |
| — | `service/MysqlService.java` | `repository/impl/GameRecordRepositoryImpl.java` | **Stage 2 拆**，不在这步动 |
| — | `rules/*.java`（5 个） | 不动 | 已符合"一层一包"，只补包注释 |
| — | `rules/HainanRulesSelfTest.java` | Stage 5 → `src/test/java/hainanjong/rules/` | — |
| — | `Main.java` | Stage 5 → `src/test/java/hainanjong/engine/` | 保留类与 `main()` 便于手动跑，同时加 JUnit 包装 |
| — | `HuLib.java` / `HuResult.java` / `FanType.java` | **本阶段不动** | 见 §6 风险 R3：它们被 15 个文件 import，留到 Stage 4 一次性搬 |

### 3.2 需要新建的类（Stage 2/3 才动）

| 新类 | 来源 | 内容 |
| --- | --- | --- |
| `common/Result.java` | 抄 Learning 的 `Result`（见 §4.1） | 7 个静态工厂：success/paramError/notFound/forbidden/unauthorized/overLimit/error |
| `common/CacheKeys.java` | 抄 Learning 的做法 | `room(code)`、`roomLock(code)` 等 key 收敛处 |
| `service/RoomService` + `impl` | 从 `MultiPlayerRoomService` 拆 | 房间生命周期：`open/onMessage/onClose/createRoom/joinRoom/leave/pendingReturn/removeMember/disband` |
| `service/GameService` + `impl` | 从 `MultiPlayerRoomService` 拆 | 块调度：`startMatch/runBlock/runEngine/onGameMessage/onGameClose/bindGameSeat` |
| `service/UserService` + `impl` | 从 `AuthController` 抽 | `register/login/changeNickname/changePassword` + 密码哈希（**Stage 3 换 BCrypt**） |
| `service/RecordService` + `impl` | 从 `RecordsController` 抽 | `listSessions/byRoom/sessionDetail`，返回 `RecordVO` |
| `repository/UserRepository` + `impl` | 从 `MysqlService:116-180` 拆 | 用户 CRUD |
| `repository/GameRecordRepository` + `impl` | 从 `MysqlService:183-310` 拆 | session/round/score 写入与查询；`initSchema` 归此 |
| `repository/RoomSnapshotRepository` | 由 `RedisService` 演进 | 保持 `saveRoomState/getRoomState/deleteRoomState` 签名，Stage 2 只搬家 |
| `domain/RoomState.java` | 从 `MultiPlayerRoomService` 内部 enum 提 | `WAITING/PLAYING/BETWEEN` |
| `domain/EngineStart.java` | 同上 | 现内部类 |
| `domain/RoomSnapshot.java` | 同上（`RedisRoom` + `MemberInfo`） | **序列化字段名一个都不能改**（见 R2） |
| `dto/`：`LoginDTO` `RegisterDTO` `ChangeNicknameDTO` `ChangePasswordDTO` `CreateRoomDTO(或沿用 Map config)` | 从 Controller 手取 body 改为对象 | Stage 3 同时补 `@NotBlank/@Size` 校验 |
| `vo/`：`LoginVO` `UserVO` `RoomPendingVO` `RecordListVO` `RecordDetailVO` `PlayerVO` | 从手拼 Map 改为对象 | Stage 3；字段名必须与现行 JSON 一致（见 R2） |

---

## 4. 关键设计决策（含取舍，答辩直接用）

### 4.1 统一响应：引入 `common/Result`（对齐 Learning）

```java
// common/Result.java —— 与 Learning 的 Result 同构，便于两个项目口径一致
public class Result<T> {
    private Integer code;
    private String message;
    private T data;
    public static <T> Result<T> success(T data) { ... code=200, message="操作成功" }
    public static <T> Result<T> paramError(String message)  { ... code=400 }
    public static <T> Result<T> unauthorized(String message){ ... code=401 }
    public static <T> Result<T> forbidden(String message)   { ... code=403 }
    public static <T> Result<T> notFound(String message)    { ... code=404 }
    public static <T> Result<T> overLimit(String message)   { ... code=429 }
    public static <T> Result<T> error(String message)       { ... code=500 }
}
```

**注意海麻现状与 Learning 有两个差异，必须明确取舍：**

| 维度 | Learning | 海麻现状 | 本次决定 |
| --- | --- | --- | --- |
| HTTP 状态码 | **恒 200**，业务码放 body | **真实 HTTP 状态码**（`Success:true/false` + 200/400） | **跟 Learning，恒 200**；但 Stage 2 只在**新增/改造**接口生效，`/api/login`、`/api/register` 等前端已解析的接口在 Stage 3 前端同步改之前**保持原样**（见 §5 阶段 3 的兼容窗口） |
| 字段名 | `code/message/data` | `success/message/userId/nickname` | Stage 2 新建 `Result`，Stage 3 前端改完后统一切换；**不要一步到位** |

### 4.2 `MultiPlayerRoomService` 怎么拆（本次最核心的决策）

它现在是 1635 行，直接搬包没意义。拆法是**按"谁在调用它"切**：

| 拆分后 | 收哪些方法 | 依赖方向 | 为什么这么切 |
| --- | --- | --- | --- |
| `websocket/RoomWebSocketHandler`（薄） | `afterConnectionEstablished/handleTextMessage/afterConnectionClosed` → 转调 `RoomService` | ws → service | 端点只做"协议转换"，不做判断 |
| `websocket/GameWebSocketHandler`（薄） | 同上 → 转调 `GameService` | ws → service | 同上 |
| `service/RoomService` | `open/onMessage/onClose/createRoom/joinRoom/leave/pendingReturn/removeMember/disband/sendRoomInfo/roomInfoMsg` | service → repository | 「房间」是业务概念，与传输无关 |
| `service/GameService` | `openGame/onGameMessage/onGameClose/bindGameSeat/startMatch/fillBots/startNow/onRematch/startEngine/runBlock/runEngine/broadcastFinish/saveRoom/resurrect/pushSeatState/sendHand/broadcastGame` | service → engine + repository | 「牌局块调度」是应用编排；**它仍然是线程宿主** |
| `engine/*`（纯 Java） | `RoomManager` 与 `PlayerController/Responder/GameListener/Action/...` | engine 不依赖任何上层 | 这样才能单测、也是你答辩时"引擎与框架解耦"的实证 |
| **留在 Service 的两个内部类** | `RoomListener`（实现 `engine.port.GameListener`，负责把引擎事件翻译成 WS 消息）、`HumanPlayerController` | service → engine.port | ★ 关键：**它俩必须留在应用层**，因为它们依赖 `WebSocketSession`。这就是"端口-适配器"：引擎定义 `GameListener` 接口（端口），应用层实现它（适配器） |

**拆分后的依赖方向（单向，无环）**：

```
controller ─┐
websocket ──┴─→ service ──→ engine.port ←── engine.impl(RoomManager)
                   │            ↑
                   │            └── engine.bot / engine.model
                   ├─→ repository ─→ (JDBC / Redis)
                   └─→ domain
```

> 答辩口径：**"引擎不依赖 Spring 和 WebSocket —— 它的对外交互只有三个接口（`PlayerController`/`Responder`/`GameListener`），联机只是这三个接口的一个实现。"** 这句话比"我分了 controller/service/mapper"值钱得多。

### 4.3 数据层：现在**不**换 MyBatis-Plus（重要）

Learning 用 MyBatis-Plus（`pojo/` + `mapper/` + `@TableLogic`）。海麻现在用 `JdbcTemplate` 裸 SQL（建表 DDL、JSON 列、批量插入）。本次决定：

- **保留 `JdbcTemplate`**，只把 `MysqlService` 按"用户"和"战绩"拆成两个 `Repository`，**不重写 SQL**。
- 理由：① 该逻辑跑通了（含旧表 ALTER 兼容），重写风险高；② 牌局记录是**批量写 + JSON 列**，MyBatis-Plus 的 CRUD 优势在这里不大；③ 架构调整的目的是分层，不是换框架 —— 一次只动一件事。
- 对齐 Learning 的地方是**"接口 + 实现 + 只暴露领域方法"**，不是"必须用 MyBatis"。
- 附：接口方法命名与返回类型建议 —— `UserRepository.insert(UserRow)` / `findByUsername` / `updatePasswordHash`；`GameRecordRepository.createSession(...) → long` / `appendRound(...)` / `listSessionsByUser(long)`。**返回 `domain` 对象或 `List<domain>`，不要返回 `Map<String,Object>`**（这是 Learning 最值得抄的一条）。

### 4.4 Redis 快照：从 `RedisService` 到 `RoomSnapshotRepository`

现状（能跑，不重写）：

```java
// service/RedisService.java —— 28 行，key = room:{code}，TTL 30min
public void saveRoomState(String roomId, String json)
public String getRoomState(String roomId)
public void deleteRoomState(String roomId)
```

调整后：

```java
public interface RoomSnapshotRepository {
    void save(String code, RoomSnapshot snapshot);      // 内部 ObjectMapper 序列化
    RoomSnapshot load(String code);                     // 找不到返回 null
    void delete(String code);
}
```
- **JSON 字段名保持完全不变**（`hostUserId/state/blockNo/sessionId/currentHand/dealer/cfg/firstDealer/bottom/windIdx/flowHandNo/firstDealerBegins/handsPlayed/coins/members/snapshot`）—— 否则**线上正在跑的房间快照重启后无法恢复**（R2）。
- TTL 仍是 30 分钟（本次不改；上一份缺陷清单里的"长对局过期"问题另开任务）。

### 4.5 测试目录（对齐 Learning 的 51 个单测形态）

Learning 的测试放在 `src/test/java`、用 JUnit 5 + Mockito。海麻现在**没有 `src/test`**，自检写在 `src/main` 里。调整后：

```
src/test/java/hainanjong/
├── HuLibTest.java               ← 把 Main.fuzz 的"查表 vs 暴力拆解"对照改成 @ParameterizedTest
├── rules/HainanRulesSelfTestTest.java   ← 薄包装：调 HainanRulesSelfTest.main() 并断言无异常
├── engine/EngineSmokeTestTest.java      ← 薄包装
└── engine/RoomManagerTest.java          ← 【高价值】用 4 个 BotController 跑一整局，断言不卡死、能结算
```
> 这一条的收益不只是"有测试"：`RoomManagerTest` 用 4 个 Bot 跑局，等于**把"从没做过真机 4 端回归"里的引擎部分补上了**（联机部分仍需真机）。

---

## 5. 分阶段执行计划（每阶段独立可回滚）

> **铁律：一个阶段一次 `git commit`，且每次提交都必须能 `mvn clean package` 成功、线上能跑。** 别把"搬包"和"改逻辑"混在一次提交里。

### 阶段 0：先立安全网（半天，**必做**）

> 📋 **逐步命令清单见 `docs/ARCH-REFACTOR-STAGE0-1.md` Part A（A0~A6）**：含基线文件生成、DevTools 抓包流程、消息类型/字段名提取脚本、Redis 快照与 HTTP 响应基线、`.gitignore` 排除规则、打 tag。

| 序号 | 动作 | 命令/做法 |
| --- | --- | --- |
| 0.1 | 记录基线：HEAD、编译结果、线上版本 | `git log --oneline -1`；`mvn clean package`；`curl -s http://jiangpahnmj.cn/game.html \| grep -o 'landscape.js?v=[0-9a-z]*'` |
| 0.2 | **抓一份消息基线**（回归对照用） | 见 §7 脚本：开一桌 4 人（可 1 真人 + 3 机器人）打 3 把，把浏览器 DevTools 的 WS 帧 + Network 响应导出到 `docs/baseline-messages.txt` |
| 0.3 | 打 tag | `git tag arch-baseline-20260920` |
| 0.4 | 建文档骨架 | 本文档 + `docs/` 目录一并提交 |

**验收**：`git status` 干净；`docs/baseline-messages.txt` 存在且含 `room_info/start/hand_start/hand/board/discard/meld/coins/match_finish` 等类型。

### 阶段 1：纯物理搬包（1 天，**零逻辑改动**）

> 📋 **逐步命令清单见 `docs/ARCH-REFACTOR-STAGE0-1.md` Part B（B1~B9）**：含 30 条 `git mv` 命令、17 个文件的 `package` 对照、**16 个文件的逐行 import 清单**、3 处必须新增的跨包 import、`HainanRulesSelfTest` 里 17 处全限定名的替换脚本、11 个 Spring bean 的核对清单。

| 序号 | 动作 |
| --- | --- |
| 1.1 | `git mv` 全部文件到 §3.1 的新路径（`git mv` 才能保留历史） |
| 1.2 | 改 `package` 声明 |
| 1.3 | 改 `import`（建议 IDEA：Refactor → Move，或全局替换 `hainanjong.game.` → `hainanjong.engine.model.` 等，逐个确认） |
| 1.4 | 改类名（`AppWebConfig`→`WebMvcConfig`、`MultiGameWebSocketHandler`→`GameWebSocketHandler`、`RoomApiController`→`RoomController`）并同步引用 |
| 1.5 | `mvn -B clean package`；启动；跑 §7 回归 |

**本阶段的"允许改"与"禁止改"**：

| ✅ 允许 | ⛔ 禁止 |
| --- | --- |
| `package` / `import` / `class` 名 | 任何方法签名、字段名、常量值 |
| 类的物理路径 | JSON 字段名（`@JsonProperty` 也不行） |
| | WebSocket 端点的 URL（`/room`、`/game`） |
| | 静态资源 / 前端任何文件 |

**验收判据**：
1. `mvn -B clean package` 成功，jar 产物存在；
2. 启动日志里 bean 数不少于改造前（`MultiPlayerRoomService`、`MysqlService`、`RedisService` 等都被扫描到）；
3. §7 回归脚本的**消息类型集合与字段集合**和 `baseline-messages.txt` **零差异**；
4. 线上部署后 `game.html`/`lobby.html`/`room.html` 均 200，能建房、能开局、能打完一把。

**回滚**：`git revert` 单个 commit 即可（因为没动逻辑）。

### 阶段 2：拆 `MultiPlayerRoomService` + 立 `common/Result`（2~3 天）

| 序号 | 动作 | 验收 |
| --- | --- | --- |
| 2.1 | 新建 `domain/RoomState`、`domain/EngineStart`、`domain/RoomSnapshot`（从内部类提取，**字段名不变**） | 编译通过；快照 JSON 与基线一致 |
| 2.2 | 新建 `repository/RoomSnapshotRepository`，把 `RedisService` 搬进去并加 `ObjectMapper` 封装 | 直接调用它读写 `room:{code}`，与旧键值逐字节一致 |
| 2.3 | `MysqlService` 拆成 `UserRepository` + `GameRecordRepository`（**SQL 原样拷贝**） | 建表/写入/查询三条链路实测通过 |
| 2.4 | `MultiPlayerRoomService` 按 §4.2 拆成 `RoomService` + `GameService`（`RoomListener` 与 `HumanPlayerController` 留在 service 层） | 两个类各自 < 700 行；`websocket/*` 两个 Handler 只做转发 |
| 2.5 | 新建 `common/Result`，**先只给新方法用**；`RecordsController` 改为返回 `RecordVO`（字段名与现 JSON 一致） | 前端不改也能跑 |
| 2.6 | 跑 §7 回归 + 一把完整对局 | 无差异 |

### 阶段 3：统一出口 + 补 Controller 规范（2 天）

| 序号 | 动作 | 验收 |
| --- | --- | --- |
| 3.1 | `AuthController` → 薄层；逻辑进 `UserService`；入参改 `dto/LoginDTO`/`RegisterDTO`（带 `@NotBlank/@Size`） | 非法参数返回 `Result.paramError` |
| 3.2 | `RecordsController` → 薄层；逻辑进 `RecordService` | 同上 |
| 3.3 | 引入 `GlobalExceptionHandler`（抄 Learning：`BusinessException` + 兜底），Service 抛异常不再手拼 Map | 抛 `BusinessException(404,...)` 能返回 `{code:404,...}` |
| 3.4 | 前端统一包一层 `apiFetch()`，把 `data.success` 改为读 `Result.code/data`（8 个 html 一次性改完） | 登录/注册/改昵称/改密码/战绩四类页面全部实测 |
| 3.5 | 切到「HTTP 恒 200 + body 带 code」 | 与 Learning 口径一致 |

> ⚠️ 3.4 和 3.5 必须**在同一个 commit 内一起完成**，中间态会导致前端全白。

### 阶段 4：补上 Learning 有而海麻没有的横切层（3 天，**与上一份缺陷清单重合**）

| 序号 | 动作 | 对应上一份缺陷 |
| --- | --- | --- |
| 4.1 | `interceptor/JwtInterceptor` + `TokenService`：登录发 token，Controller 用 `@RequestAttribute("userId")`（抄 Learning 的 `JwtInterceptor:88-91`） | P0-1 无认证 |
| 4.2 | `/game` 握手时校验 token + 座位归属 | P0-1 顶号 |
| 4.3 | 密码换 BCrypt（`PasswordEncoder` Bean，抄 `SecurityConfig`） | P0-2 无盐 SHA-256 |
| 4.4 | `config/WebSocketConfig` 的 `setAllowedOrigins("*")` 改白名单 | P1-10 任意源 |
| 4.5 | `common/CacheKeys` 收敛 Redis key | — |

### 阶段 5：测试与清理（1~2 天）

| 序号 | 动作 |
| --- | --- |
| 5.1 | 建 `src/test/java`，把 `Main`/`HainanRulesSelfTest`/`EngineSmokeTest` 的对照逻辑迁成 JUnit（`Main` 可保留 `main()` 便于手动跑） |
| 5.2 | 新增 `RoomManagerTest`（4 Bot 跑一整局，超时即失败） |
| 5.3 | 删除 `game_log.txt`（已被 `*.log` 忽略，历史里清理另议）、清理 `out_compile/`、`mjlib_java/**.class` |
| 5.4 | 更新 `HANDOFF.md` §2 目录 + README 结构图（现有 README 里 `listener/GamePersistenceListener`、`runner/DemoGameRunner` **两个包不存在**，是漂移的文档） |

---

## 6. 风险清单（**动手前先读这一节**）

| # | 风险 | 为什么危险 | 规避动作 |
| --- | --- | --- | --- |
| **R0** | **"架构对齐"≠"顺手修缺陷"，反之亦然** | 阶段 1~3 若混入鉴权改造，出问题时无法判断是"搬包搬坏了"还是"新代码有 bug"；而且认证改造会让**前端三处连接串同时要改**（见下）——这是唯一会让"只改后端"失败的改造 | 严格按 §5 分阶段：阶段 1~3 **只搬结构、只改返回口径**；鉴权/BCrypt/白名单一律放阶段 4。前端耦合点：`index.html:311-325`（`data.success/userId/nickname`）、`game.html:800-810`（`localStorage.userId` → `/game?userId=`）、`room.html:167,224`（`localStorage.userId` → `/room?userId=`）——阶段 4 这三处必须一起改（token 替代 userId 后，`/api/room/pending`、`/api/records` 的 `userId` 参数也应改为从 token 取） |

**★ 阶段 4 要修的那个洞，具体攻击链**（实测代码路径，写给自己看：为什么这个不算"优化"而是必修）：

```
① userId 是自增整数，浏览器一直存在 localStorage，且被写进 URL：
     game.html:800   const userId = localStorage.getItem('userId') || '0';
     game.html:803   url = ... + '/game?userId=' + userId + ...   ← 无 token
② 任何人可以拿别人的 userId 去问房间码：
     GET /api/room/pending?userId=<别人的id>  → {exists, code, state, seat}   （RoomApiController:25，不校验归属）
③ 后端在握手时只用 URL 里的 userId 与"该座位登记的人"比对：
     MultiPlayerRoomService:256-266 openGame()  → bindGameSeat(code, seat, userId, session)
     MultiPlayerRoomService:374-384            → if (m == null || m.userId != userId) 拒绝；相等即绑定
④ 于是 /game?userId=<别人>&code=<拿到的码>&seat=<seat> 即可顶掉该座位
     （bindGameSeat:382 会 m.human.attach(...) 把真人控制器指向攻击者的连接）
```
→ 所以阶段 4 的验收必须包含：**用 A 的凭证连 B 的座位，必须被拒**；且 `pending`/`records` 不再接受任意 `userId`。

| **R1** | Spring 组件扫描失效 | `@SpringBootApplication` 在 `hainanjong.HainanMaJhongApplication`，扫描根是 `hainanjong`。一旦把任何 `@Service/@Component/@Configuration/@RestController` 挪到 `hainanjong` 之外（比如新建 `com.hainan.*`），应用会**静默少 bean**：`WebSocketConfig` 不在 → 端点 404；`MysqlService` 不在 → `initSchema` 不跑 | 只搬进 `hainanjong/*` 子包，**绝不换根包**；每阶段启动后核对 bean 数 |
| **R2** | Jackson 字段名变了 → 前端/快照全崩 | 两条链路都靠字段名：① WS 消息/HTTP 响应被前端按 `msg.type/msg.seat/data.success/data.userId` 直接解析；② `room:{code}` 快照被 Redis 里**已存在的线上快照**反序列化（改名字段会让重启后的房间恢复失败）。另外 `MultiPlayerRoomService` 用的是自己 `new ObjectMapper()`（`mapper` 字段），不走 Spring 配置 —— `@JsonProperty` 之外任何调整都会静默改名字 | 阶段 1/2 **一个字段名都不改**；阶段 2 提取 `RoomSnapshot` 时用 diff 对照 JSON；部署前先 `redis-cli GET room:<code>` 存一份样本，恢复测试用它 |
| **R3** | `HuLib`/`HuResult`/`FanType` 被 15 个文件 import，搬动成本高 | 它们在根包，被 `game/*`、`rules/*`、`Main` 引用。搬动会牵动几乎所有 import | **留到最后（阶段 5 之后可选）**；先按 §2 计划把它们标为 `domain`，但物理位置不动 |
| **R4** | `RoomManager` 一个实例一把牌，挪包时容易顺手"重构" | 1105 行里含海南特殊规则（吃后禁打、报听、杠开、四风），改错的代价是**线上规则错乱**，而且靠对局很难发现 | 阶段 1/2 对它**只动 package/import**；真要拆，先写 `RoomManagerTest`（阶段 5）再拆 |
| **R5** | `mvn clean package` 的 JDK 版本 | `pom.xml:23` 是 `java.version=11`，本机 `JAVA_HOME` 指 `openjdk-26`（`HANDOFF.md:181`）。JDK 26 编 Java 11 目标一般没事，但 Spring Boot 2.7 + JDK 26 有兼容隐患。**另外两个已实测的坑**：① JDK 17 的正确路径是 `C:\Users\ASUS\.jdks\ms-17.0.20.1-1`（带 `-1`；同名不带后缀的那个目录是**空的**，`bin\java.exe`/`javac.exe` 都不存在）；② PATH 上的 `java` 是 **Java 8**（`java8path`），直接 `java -jar` 会 `UnsupportedClassVersionError` | 搬包期间**固定用 JDK 17 的绝对路径**（`C:\Users\ASUS\.jdks\ms-17.0.20.1-1`）跑 `mvn` 和启动 jar，与 Learning/Docker 的 17 对齐；顺手把 `pom.xml` 升到 17 |
| **R6** | 静态资源缓存 | 你**没改前端**，所以阶段 1/2 **不要 bump `?v=`**（当前 `20260908c`/线上 `20260908d`），否则要重新走一遍部署流程 | 阶段 3 改前端时才 bump，并同步更新 `HANDOFF.md:141` 与 §6 验证命令里的版本号 |
| **R7** | `systemctl restart mahjong` 是唯一部署手段，且配置不在仓库 | 一旦新 jar 起不来，回滚只能靠手工；`deploy/` 里没有 nginx/systemd 备份 | 动手前 `scp` 一份**当前线上 jar + nginx 配置 + service 文件**到本地 `/backup-arch/`；部署时先留旧 jar |
| **R8** | 阶段 3 的"HTTP 恒 200"切换 | 前端 8 个文件都按真实状态码/`success` 字段写；半途切换 = 全站白屏 | 3.4 + 3.5 同 commit；切换前把 8 个页面的关键请求先跑一遍记录响应 |

---

## 7. 回归验证清单（每阶段跑一遍）

### 7.1 编译与启动
```powershell
$env:JAVA_HOME = 'C:\Users\ASUS\.jdks\ms-17.0.20.1'
& 'C:\Users\ASUS\tools\apache-maven-3.9.16\bin\mvn.cmd' -f 'F:\HainanMaJhong2\HainanMaJhong\pom.xml' clean package
# 起服务：java -jar target\HainanMaJhong2-1.0-SNAPSHOT.jar（记得先设 DB_/REDIS_ 环境变量）
# 核对 bean：启动日志里 grep 下列 bean 是否都在
#   webSocketConfig / roomWebSocketHandler / multiGameWebSocketHandler(改名后 gameWebSocketHandler)
#   multiPlayerRoomService / mysqlService / redisService
```

### 7.2 HTTP 冒烟（对齐 HANDOFF §6）
```powershell
# 登录（注意：PowerShell 会把双引号吃掉，用文件体，实测踩过）
'{"username":"<账号>","password":"<密码>"}' | Set-Content $env:TEMP\login.json -Encoding ascii -NoNewline
& curl.exe -s --noproxy '*' -X POST 'http://<host>:8080/api/login' -H 'Content-Type: application/json' --data-binary "@$env:TEMP\login.json"
```
> 实测提醒：本机系统代理是 `127.0.0.1:7897`，`Invoke-WebRequest` 会走代理拿到 221 字节跳转页 → **验证一律用 `curl.exe --noproxy '*'`**。

### 7.3 WebSocket 协议基线（本阶段最重要的一条）
1. 用 4 个账号（或 1 真人 + `fillBots`）开一局，打到胡牌/流局各一次；
2. DevTools → Network → WS → 导出帧；把**每条消息的 `type` 排序去重**，以及**每个 type 的字段名排序去重**；
3. 与 `docs/baseline-messages.txt` 比对，**差集必须为空**。

**必须出现的 type**（来自 `HANDOFF.md` §4）：
`welcome, room_info, start, join_denied, room_closed, match, hand_start, coins, turn, board, hand, counts, draw, flower, discard, meld, hu, end, log, request, match_finish`
> 注：`kicked` 已随单人模式下线（后端不再发，`game.html:877` 还在处理）—— **基线里就不要指望它**，阶段 3 顺手把这段前端死代码删掉。

### 7.4 端到端（每阶段末尾）
| 场景 | 判据 |
| --- | --- |
| 建房 → 满 4 开局 | `room_info`/`start` 正常，四端座位两两不同 |
| 打一把含吃碰杠 | 四端 `meld` 一致，暗杠他人只见牌背 |
| 胡牌 | 四端同方位大字；`coins` 正确 |
| 某人关窗 | 剩余端继续，该座 bot 托管（≤30s 内应轮到，见缺陷 P1-5） |
| 服务重启后续跑 | 玩家凭码回来能重建房间（`resurrect`） |
| 战绩页 | `records.html` 列表与明细与改造前一致 |

---

## 8. 提交信息模板（沿用你的习惯：`git commit -F 文件`）

```
refactor(arch): 阶段1 纯物理搬包，对齐 Learning 分层

- web/ → controller/ + websocket/ + config/（仅移动与改名）
- game/ → engine/{model,port,bot,dev}（仅移动）
- service/RedisService → repository/RoomSnapshotRepository（仅移动）
- AppWebConfig→WebMvcConfig、MultiGameWebSocketHandler→GameWebSocketHandler、RoomApiController→RoomController

行为不变：JSON 字段名、WS 消息、URL、静态资源全部未改。
验证：mvn -B clean package 通过；对局消息与 baseline-messages.txt 无差异。

（注意：`git commit -F` 提交，不要用 -m $var —— 消息里的 ` + " 会被 git 当成 pathspec，静默失败）
```

---

## 9. 交付物清单

| 文件 | 状态 |
| --- | --- |
| `F:\HainanMaJhong2\docs\ARCH-REFACTOR.md` | ✅ 本文档（设计：目标态、迁移表、5 阶段、风险） |
| `F:\HainanMaJhong2\docs\ARCH-REFACTOR-STAGE0-1.md` | ✅ 阶段 0/1 可照敲清单（基线抓取 + 30 条 `git mv` + 逐文件 import 清单 + 回归判据） |
| `F:\HainanMaJhong2\docs\baseline-messages.txt` | ⬜ 阶段 0 产出（**不入库**） |
| `F:\HainanMaJhong2\docs\baseline-message-types.txt` | ⬜ 阶段 0 产出 |
| `F:\HainanMaJhong2\docs\baseline-roomsnapshot.json` | ⬜ 阶段 0 产出（**不入库**） |
| `F:\HainanMaJhong2\docs\baseline-http.txt` | ⬜ 阶段 0 产出（**不入库**） |
| `F:\HainanMaJhong2\docs\ARCH-REFACTOR-RESULT.md` | ⬜ 完成后回填：实测结果、踩坑、每个阶段的 commit hash |

---

## 10. 回填区（做完一阶段就在这里记一行）

| 阶段 | 日期 | commit | 实际耗时 | 遇到的问题 / 与设计的偏差 |
| --- | --- | --- | --- | --- |
| 0 安全网 | 2026-09-20 | `4fbb803` | ~2.5 h | ① 浏览器抓包两轮均不完整（Ctrl+A 只复制可见行）→ **改用服务端录制**（新增临时 `WsRecorder`）；② 录制初版写错目录（相对路径按进程工作目录解析）；③ `application.yml` 默认值被手工改成虚拟机 IP → 已回退；④ 端口被占导致一次无效重启。见 `docs/baseline-status.md` 与 `docs/stage0-CHANGES.md` |
| 1 纯搬包 | 2026-09-20 | `c9edd8c` | ~1.5 h | **设计偏差 3 处**（见下 §11） |
| 2 拆 Service | | | | |
| 3 统一出口 | | | | |
| 4 横切层 | | | | |
| 5 测试清理 | | | | |

## 11. 阶段 1 实测结论与设计偏差（回填）

### 验收结果 —— 全部通过

| 判据 | 结果 |
| --- | --- |
| `mvn -B clean package` | ✅ `BUILD SUCCESS`，38 文件 |
| package 与目录一致 | ✅ 0 不一致（脚本校验） |
| 行数 | 6679 → **6791（+112）** = 新增 import 26 处 + 分隔空行 + 注释，**无一行逻辑** |
| `git diff` 逐行复核 | ✅ 仅「类名 / 字段名 / 全限定名 / import / 注释」5 类 |
| HTTP 端点 | ✅ `/`、`/lobby.html`、`/game.html`、`/api/room/pending`、`/api/records`、`/download/app` 全 200 |
| WebSocket 端点 | ✅ `/room`、`/game` 均 `101 Switching Protocols` |
| **协议 diff** | ✅ **20 种共有类型的字段名集合零差异**；未丢失任何类型；另新捕获 `join_denied`/`room_closed`（**22/23 类型覆盖**） |
| 重命名历史 | ✅ git 识别 26 个 rename（相似度 71%~99%），`--follow` 可追溯 |
| 临时件清除 | ✅ `WsRecorder` 及其 3 处调用点 + 1 行 import 全部删除，`git grep WsRecorder` = 0 |

协议 diff 由 `docs/compare-ws-recordings.ps1` 产出（基线 `docs/ws-recording.log` 822 帧 vs 搬包后 816 帧）。最容易被搬包破坏的三处均保持原样：`board` 的 `discards,flowers,melds,type`、两种形态的 `counts`（带/不带 `wall`）、`request` 的多种变体。

### 与设计的偏差（3 处，均需在阶段 2 修正文档）

| # | 设计里写的 | 实际做的 | 原因 |
| --- | --- | --- | --- |
| 1 | 临时录制钩子放在 `web/WsRecorder.java` | 放在 **`hainanjong.diag`** 包 | `MultiPlayerRoomService` 搬到了 `service` 包，跨包引用需要 `public`；放进独立 `diag` 包让"临时"在结构上一目了然，也避免 `web/` 留个孤儿包 |
| 2 | `service/RedisService` → `repository/RoomSnapshotRepository` | ✅ 做了，但**类名变了而字段名 `redis` 保留** | 字段名有 20+ 调用点，改名收益低风险高 —— 阶段 2 用接口重构时再动 |
| 3 | §7 的"核对 11 个 Spring bean" | 未能从日志核对（本项目不打 bean 列表） | 改用**端点存活 + WS 握手**作为更强的证据（bean 没注册端点必然 404/握手失败）。若阶段 4 引入 actuator 后可补回这条 |

### 本阶段新增的踩坑（建议并入 `HANDOFF.md` §九）

1. **`Set-Content -Encoding UTF8` 会写 BOM** → `javac` 报 `非法字符: '\ufeff'`，31 个文件受害。修法：`[System.IO.File]::WriteAllLines($p, $lines, (New-Object System.Text.UTF8Encoding($false)))`。
2. **`cd` 只影响 PowerShell 当前位置，不影响 .NET 的 `[System.IO.File]`** —— 后者按**进程工作目录**解析相对路径。用 `File.ReadAllLines("相对路径")` 会去 `C:\Users\ASUS\Desktop\java` 找文件。
3. **PowerShell 按点号拆分 `-D` 参数** → `-Dws.record.file=...` 必须整体加引号（`'-Dws.record.file=F:\...'`），否则 Java 收到 `-Dws` + `.record.file=...`，报 `ClassNotFoundException: /record/file=...`。
4. **子包不再同包可见**：`game/*` 拆进 `engine/{model,port,bot,dev}` 后，**9 个文件**需要新增 import（`RoomManager`、`GameListener`、`PlayerController`、`Responder`、`BotController`、`Player`、`PrintListener`、`GameDemo`、`EngineSmokeTest`）。用脚本按"类名引用 vs 现有 import"自动检测比手工找可靠。
5. **改文件后用 `Select-String` 自查是必要的**：本轮出现过一次"脚本打印了'已补 import'但实际没写进去"（PowerShell 中 `.Replace()` 返回值未接收）—— **脚本的成功消息不能替代事后校验**。
