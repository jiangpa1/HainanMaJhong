# HainanMaJhong 海南麻将

一套海南麻将：**查表法判胡** + **整局流程房间管理器** + **Redis / MySQL 持久化**。基于 Spring Boot 2.7（Java 11）构建。

- 不含鬼牌（癞子），含花牌。
- 番型：清一色、混一色、碰碰胡、七对、十三幺。

## 目录结构

```
HainanMaJhong/
├── pom.xml                     Maven 配置（Spring Boot 2.7.18，jdbc + data-redis + mysql）
├── src/main/java/hainanjong/
│   ├── HainanMaJhongApplication.java    Spring Boot 启动类
│   ├── FanType.java / HuResult.java     番型枚举 / 判定结果
│   ├── HuLib.java                       核心：查表法胡牌 + 番型判定
│   ├── Main.java                        胡牌判定示例 + 随机对照自检
│   ├── game/                            房间管理器（整局流程）
│   │   ├── RoomManager.java             洗牌发牌、抓打出牌、吃碰杠胡、优先级、超时
│   │   ├── Seat.java / Action.java / Meld.java / Player.java / RoundResult.java
│   │   ├── PlayerController.java / Responder.java   玩家决策回调接口
│   │   ├── BotController.java / PrintListener.java  演示用机器人 / 日志
│   │   └── GameDemo.java                整局演示入口（纯 Java，不依赖 Spring）
│   ├── service/                         Service 层
│   │   ├── RedisService.java            牌局状态缓存、在线状态、最近战绩
│   │   └── MysqlService.java            牌局记录落库（自动建表）
│   ├── listener/
│   │   └── GamePersistenceListener.java 实现 GameListener，把事件写入 Redis / MySQL
│   └── runner/
│       └── DemoGameRunner.java          启动后跑一局演示对局
└── src/main/resources/application.yml   MySQL / Redis 连接配置
```

## 牌张下标（共 42 张）

| 下标 | 花色 | 说明 |
|---|---|---|
| 0 – 8 | 万 | 1万 ~ 9万 |
| 9 – 17 | 筒 | 1筒 ~ 9筒 |
| 18 – 26 | 条 | 1条 ~ 9条 |
| 27 – 33 | 字 | 东 南 西 北 中 发 白 |
| 34 – 41 | 花牌 | 春 夏 秋 冬 梅 兰 竹 菊（只计番，不参与成牌） |

## 判胡（HuLib）

```java
int[] hand = new int[42];
// ... 填入手牌 ...
HuResult r = HuLib.checkHu(hand);                 // 整副 14 张
HuResult r = HuLib.checkHu(handCards, curCard);   // 13 张 + 胡的那张

if (r.isHu) {
    System.out.println("胡牌: " + r.fanTypes + "，花牌x" + r.flowerCount);
}
```

`HuResult`：`isHu`（是否胡牌）、`fanTypes`（番型列表）、`flowerCount`（花牌张数）。

## 支持的番型

| 番型 | 判定规则 |
|---|---|
| 清一色 | 全部为一种花色（万/筒/条），无字牌 |
| 混一色 | 一种花色 + 字牌 |
| 碰碰胡 | 4 个刻子 + 1 个将（无顺子） |
| 七对 | 7 个对子（4 张同牌按两对计） |
| 十三幺 | 1、9 万筒条 + 东南西北中发白，13 种各至少 1 张再加 1 张成对 |

优先级：十三幺 > 七对 > 普通胡。清一色 / 混一色可与七对或碰碰胡叠加。

## 房间管理器（整局流程）

`hainanjong.game` 子包在判胡逻辑之上搭了整局流程：

- **洗牌发牌**：`Collections.shuffle` 洗 136（无花）或 144（含花）张，庄家 14 张、其余 13 张，花牌补牌。
- **抓打出牌**：四家（东南西北）轮流抓牌/出牌。
- **出牌超时**：默认 15 秒（`GameConfig.discardTimeoutMs` 可配），超时强制出一张「孤张」。
- **动作判定与优先级**：出牌后通知其余三家，判定 吃/碰/杠/胡，按 **胡 > 杠 > 碰 > 吃** 依次询问；同级按离出牌者近优先。
- **完整动作**：自摸胡、点炮胡、暗杠、明杠、补杠、流局。

```java
Map<Seat, PlayerController> bots = new EnumMap<>(Seat.class);
for (Seat s : Seat.values()) bots.put(s, new BotController());

GameConfig cfg = GameConfig.standard();          // 含花牌 144 张，15s 超时
RoomManager room = new RoomManager(cfg, bots, new PrintListener());
room.start();                                    // 同步跑完一整局
RoundResult r = room.getResult();                // 谁胡了 / 是否流局
```

## Redis / MySQL 持久化（Service + Listener）

- **`RedisService`**：缓存实时牌局状态（`mahjong:room:{roomId}` Hash）、玩家在线（`mahjong:online` Set）、最近战绩（`mahjong:latest:{roomId}`，30 分钟过期）。
- **`MysqlService`**：启动时自动建 `game_record` 表，一局结束写入一条记录。
- **`GamePersistenceListener`**：实现 `GameListener`，在 `onDeal/onDraw/onDiscard/onHu/onEnd` 等事件时调用上面两个 Service；所有持久化都做了容错，Redis/MySQL 暂时不可用不会中断牌局。

`DemoGameRunner` 在应用启动后跑一局四机器人对局，把结果落库、缓存到 Redis。接入真实玩家/网络后，可删除该演示类或改为按需触发。

### game_record 表结构

| 字段 | 说明 |
|---|---|
| id | 自增主键 |
| room_id | 房间号 |
| result | WIN / DRAW |
| winner_seat | 胡牌者座位（流局为 null） |
| win_type | SELF_DRAW / DISCARD |
| fan_types | 番型（逗号分隔，如「碰碰胡,清一色」） |
| win_tile | 胡的那张牌名 |
| flower_count | 花牌数 |
| create_time | 创建时间 |

## 网页对战（WebSocket）

`hainanjong.web` 子包把人机对战做成网页：你坐庄（东家），另三家是机器人。

- **后端**：`WebSocketConfig` 注册 `/ws` 端点；`GameWebSocketHandler` 每个连接开一局；`HumanPlayerController` 把你的决策请求转发给前端并异步等待回复；`WebGameListener` 把手牌/张数/吃碰杠胡等事件推给前端。
- **前端**：`src/main/resources/static/index.html`，原生 JS + CSS 渲染麻将牌，WebSocket 收发 JSON 消息，点击手牌出牌、按钮吃/碰/杠/胡/过，带超时倒计时。

消息协议（JSON）：

| 方向 | 消息 | 说明 |
|---|---|---|
| 服务端 → 前端 | `welcome` / `dealer` | 座位、庄家 |
| | `hand` | 你的手牌 / 副露 / 花牌快照 |
| | `counts` | 四家手牌张数（对手隐藏牌面，只显示张数） |
| | `draw` / `discard` / `meld` / `flower` / `hu` / `end` / `log` | 牌局公开事件 |
| | `request{kind:discard\|action\|draw}` | 请求你出牌 / 响应吃碰杠胡 / 自摸杠 |
| 前端 → 服务端 | `discard` / `act` / `pass` | 你的决定（带 reqId） |

## 编译运行

需要 JDK 11+ 和 Maven（IntelliJ 自带 Maven 亦可）。

```bash
# 方式一：Maven 直接跑 Spring Boot 应用
mvn spring-boot:run
# 启动后浏览器打开 http://localhost:8080/ 即可与三个机器人对战

# 方式二：打包后运行
mvn package
java -jar target/HainanMaJhong2-1.0-SNAPSHOT.jar
```

在 IntelliJ 里直接运行 `hainanjong.HainanMaJhongApplication` 亦可，然后访问 `http://localhost:8080/`。

> 应用启动时 `DemoGameRunner` 会先跑一局机器人对局演示（落库到 MySQL），接入网页后如不需要可删掉该 `@Component`。

> MySQL / Redis 连接地址在 `src/main/resources/application.yml` 中配置（当前指向虚拟机 `192.168.133.128`）。应用即使连不上数据库也能启动，只是持久化会打 warning 日志。

> 若在 cmd 下中文乱码，先 `chcp 65001`；或直接用 IntelliJ 运行。
