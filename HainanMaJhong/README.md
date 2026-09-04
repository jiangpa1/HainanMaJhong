# HainanMaJhong 胡牌判定（查表法）

一套海南麻将胡牌判定逻辑，基于「查表法」实现。**不含鬼牌（癞子）**，**含花牌**。

## 目录结构

```
HainanMaJhong/
├── src/hainanjong/
│   ├── FanType.java        番型枚举
│   ├── HuResult.java       判定结果
│   ├── HuLib.java          核心：查表法胡牌 + 番型判定
│   ├── Main.java           胡牌判定示例 + 随机对照自检
│   └── game/               房间管理器（整局流程）
│       ├── RoomManager.java   核心：洗牌发牌、抓打出牌、吃碰杠胡、优先级、超时
│       ├── Seat.java          座位（东南西北）
│       ├── Action.java / Meld.java / Player.java / RoundResult.java
│       ├── PlayerController.java / Responder.java   玩家决策回调接口
│       ├── BotController.java / PrintListener.java  演示用机器人 / 日志
│       └── GameDemo.java     整局演示入口
├── out/               编译产物（已编译，可直接运行）
├── run.bat            运行胡牌判定示例
└── run_game.bat       运行整局流程演示
```

## 牌张下标（共 42 张）

| 下标 | 花色 | 说明 |
|---|---|---|
| 0 – 8 | 万 | 1万 ~ 9万 |
| 9 – 17 | 筒 | 1筒 ~ 9筒 |
| 18 – 26 | 条 | 1条 ~ 9条 |
| 27 – 33 | 字 | 东 南 西 北 中 发 白 |
| 34 – 41 | 花牌 | 春 夏 秋 冬 梅 兰 竹 菊（只计番，不参与成牌） |

## 使用方式

```java
// 方式一：整副手牌（已含胡的那张，14 张）
int[] hand = new int[42];
// ... 填入手牌 ...
HuResult r = HuLib.checkHu(hand);

// 方式二：13 张手牌 + 胡的那张牌
HuResult r = HuLib.checkHu(handCards, curCard); // curCard 为牌下标

if (r.isHu) {
    System.out.println("胡牌: " + r.fanTypes + "，花牌x" + r.flowerCount);
}
```

`HuResult` 三个字段：

- `isHu`：是否胡牌；
- `fanTypes`：命中的番型列表（可能多个，如 `[碰碰胡, 清一色]`）；
- `flowerCount`：手中花牌张数。

## 支持的番型

| 番型 | 判定规则 |
|---|---|
| 清一色 | 全部为一种花色（万/筒/条），无字牌 |
| 混一色 | 一种花色 + 字牌 |
| 碰碰胡 | 4 个刻子 + 1 个将（无顺子） |
| 七对 | 7 个对子（4 张同牌按两对计） |
| 十三幺 | 1、9 万筒条 + 东南西北中发白，13 种各至少 1 张再加 1 张成对 |

优先级：十三幺 > 七对 > 普通胡。清一色 / 混一色可与七对或碰碰胡叠加（如「清一色七对」「清一色碰碰胡」）。

## 设计思路（查表法）

不含鬼牌时，「某一花色（9 张）或字牌（7 张）能否拆成若干副（刻子/顺子）+ 可选一个将」是固定的。离线把这些牌型的**张数分布**编码成一个十进制整数（每一位是一张牌的张数），存入 `HashSet`：

- 花色：无将表 + 含将表（万/筒/条，可吃）
- 字牌：无将表 + 含将表（只能碰）

运行时把 14 张牌按 4 个花色分组，每组 O(1) 查表；由于整副手牌 mod 3 必余 2，恰好一组含将，据此完成判定。表在类加载时一次性生成（表规模见运行输出）。

## 房间管理器（整局流程）

`hainanjong.game` 子包在上面的判胡逻辑之上，搭了整局流程：

- **洗牌发牌**：`Collections.shuffle` 洗 136（无花）或 144（含花）张，庄家 14 张、其余 13 张，花牌补牌。
- **抓打出牌**：四家（东南西北）轮流抓牌/出牌；花牌自动放一旁并补牌。
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

玩家决策通过 `PlayerController` 接口注入（可换成 UI / 真人 / 机器人）；牌局事件通过 `GameListener` 回调输出。`GameDemo` 是现成的演示入口。

## 编译运行

**命令行（需 JDK）**

```bash
javac -encoding UTF-8 -d out src/hainanjong/*.java src/hainanjong/game/*.java
java -cp out hainanjong.Main            # 胡牌判定示例 + 自检
java -cp out hainanjong.game.GameDemo   # 整局流程演示
```

> 仓库内 `out/` 已用 `--release 8` 编译，可在 Java 8 及以上运行；若自行用较新 JDK 重新编译且要在 Java 8 上运行，请加 `--release 8`。

**Windows 双击**：`run.bat` 跑胡牌判定示例，`run_game.bat` 跑整局流程演示（两者都直接运行已编译的 `out/`）。

> 若在 cmd 下中文乱码，`.bat` 已内置 `chcp 65001` 切换为 UTF-8；或直接用 IntelliJ 运行。
