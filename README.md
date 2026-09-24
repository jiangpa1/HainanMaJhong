# HainanMaJhong 海南麻将

一套可联网对战的海南麻将：**查表法判胡** + **整局流程房间管理器** + **Redis / MySQL 持久化**
+ **Vue3 单页前端（含手机虚拟横屏）**。后端 Spring Boot 2.7 / **JDK 17**，前端 Vue 3 + Vite。

- 不含鬼牌（癞子），含花牌；四人联网，可补电脑。
- 番型：清一色、混一色、碰碰胡、七对、龙七对、十三幺、天胡、杠开、自摸…
- 身份走 JWT（access 30 分钟 / refresh 7 天），HTTP 与 WebSocket 共用同一套校验。

## 代码结构

```
HainanMaJhong/
├── pom.xml                        Maven（Spring Boot 2.7.18 / jdbc / data-redis / mysql / jjwt）
├── README.md                      本文件
├── .env.example                   环境变量模板（DB_PASSWORD / REDIS_PASSWORD / JWT_SECRET）
├── run-local.ps1 | run.bat        本地启动（从 .env 注入环境变量）
├── run_game.bat
│
├── src/main/java/hainanMahjong/   后端（按分层分包）
│   ├── HainanMahjongApplication.java   启动类
│   ├── annotation/                @RequireRole 角色注解
│   ├── common/                    Result / PageResult / CacheKeys（Redis 键名集中定义）
│   ├── config/                    WebMvcConfig（拦截器与静态资源）/ WebSocketConfig / MybatisPlusConfig
│   ├── controller/                Auth（登录注册改密）/ Room / Records / AppDownload；Debug 仅 local profile
│   ├── dto/ vo/ pojo/             入参 / 出参 / 表实体
│   ├── engine/                    牌局引擎：RoomManager（一把的完整流程）
│   │   ├── bot/ human/            机器人托管 / 真人控制器（断线由机器人接管）
│   │   ├── port/ model/ rule/     引擎对外接口 / 模型 / 吃碰杠胡判定与规则策略
│   │   └── ActionDetector / RuleJudge / TurnUtils / PromptSupport
│   ├── exception/                 BusinessException + 全局异常处理（统一 {code,message,data}）
│   ├── interceptor/               JwtInterceptor（身份 + 单点登录）/ AuthorizationInterceptor
│   ├── mapper/                    MyBatis-Plus Mapper（含注解动态 SQL）
│   ├── properties/                JwtProperties
│   ├── repository/                RoomSnapshotRepository（Redis 房间快照，掉线/重启重建）
│   ├── room/                      Room / Member / RoomRegistry / SwitchingController（房间状态与注册表）
│   ├── rules/                     HuLib（查表判胡）/ HainanFan / HainanScore / HainanConfig / DealerFlow
│   ├── service/ + service/impl/   RoomService / GameEngineService / GamePushService / RecordsService
│   │                              / TokenService / UserService / RoomSnapshotService / RoundPersister
│   │                              / SchemaInitializer（启动幂等建表）/ MultiPlayerRoomService
│   ├── utils/                     JwtUtils / PasswordUtil / Sha256Util
│   └── websocket/                 /room（等待房）与 /game（牌局）端点 + 握手鉴权拦截器
│
├── src/main/resources/
│   ├── application.yml            公共配置（可用 SPRING_PROFILES_ACTIVE 切 profile）
│   ├── application-local.yml      本地开发（默认，指向开发库）
│   ├── application-prod.yml       云端：密码全部走环境变量，SQL 日志关闭
│   ├── mapper/                    MyBatis XML
│   └── static/                    前端构建产物 + 图片/音频/APK（由 pnpm build 写入，需入库）
│
├── src/test/java/hainanMahjong/   38 个测试（判胡、房间拆解落库、握手鉴权、单点登录踢人…）
│
├── frontend/                      Vue 3 + Vite（构建产物直接写进 ../src/main/resources/static）
│   ├── index.html
│   ├── src/
│   │   ├── main.js  App.vue       入口 + #game-app 旋转层
│   │   ├── router/                路由（hash 模式：/ · /lobby · /room · /game · /records）
│   │   ├── views/                 Login / Lobby / Room / GameTable / Records
│   │   ├── components/
│   │   │   ├── layout/            LandscapeLayer（1280x720 固定框）
│   │   │   ├── lobby/             RuleDialog（建房规则）/ SettingsDialog（昵称密码）
│   │   │   ├── table/             SeatPanel / MeldRow / DiscardRiver / ActionBar / RoundHistory / TurnTimer
│   │   │   ├── tile/              Tile（牌面图 + 矢量降级）
│   │   │   └── records/           RecordDetail（大厅二级战绩与局内战绩共用同一个渲染）
│   │   ├── stores/game.js         Pinia：牌局状态机（消息 → 状态 → 界面）
│   │   ├── game/                  tiles（编码）/ socket（WS 客户端）/ audio（音效池）/
│   │   │                          preload + assets（进对局前预加载）/ roomConfig（房间规则）
│   │   ├── composables/           useLandscapeScale（虚拟横屏：旋转 + 等比缩放）
│   │   ├── api/api.js             接口封装（401 续期、4011 被顶下线、错误口径）
│   │   └── styles/                base.css（设计令牌）/ landscape.css（旋转 + 固定框 + 安全区）
│   └── tools/                     smoke.mjs（happy-dom 跑通交互）/ check-tiles / check-audio / check-build
│
├── docs/                          验证工具与交接文档
│   ├── browser-probe.py           桌面：真 Chrome 渲染构建产物，逐像素断言布局
│   ├── mobile-probe.py            手机：手机 UA + 竖屏，断言旋转/固定框/弹窗/触点命中
│   ├── ws-auth-e2e.ps1            WebSocket 鉴权端到端
│   └── thread-dump.ps1            卡死时抓线程转储
│
└── tools/                         构建辅助（如 shrink_tile_back.py：压缩牌背图）
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

`hainanMahjong.game` 子包在判胡逻辑之上搭了整局流程：

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

`hainanMahjong.web` 子包把人机对战做成网页：你坐庄（东家），另三家是机器人。

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

需要 **JDK 17** 和 Maven（IntelliJ 自带 Maven 亦可）。

```bash
# 方式一：Maven 直接跑 Spring Boot 应用
mvn spring-boot:run
# 启动后浏览器打开 http://localhost:8080/ 即可与三个机器人对战

# 方式二：打包后运行（改过前端必须先 pnpm build，见 frontend/README.md）
#   注意是 clean package：不带 clean 会把历次构建的静态产物留在 jar 里
mvn clean package
java -jar target/HainanMaJhong2-1.0-SNAPSHOT.jar
```

在 IntelliJ 里直接运行 `hainanMahjong.HainanMaJhongApplication` 亦可，然后访问 `http://localhost:8080/`。

> MySQL / Redis / JWT 的配置按 profile 分开：`application.yml`（公共）+ `application-local.yml`
> （本地开发，默认生效）+ `application-prod.yml`（云端）。密码等敏感值一律走环境变量或 `.env`，不进仓库。
> 应用即使连不上数据库也能启动，只是持久化会打 warning 日志。

> 若在 cmd 下中文乱码，先 `chcp 65001`；或直接用 IntelliJ 运行。

## 部署到云服务器（prod profile）

**不需要 Docker**：产物就是一个 fat jar（前端已打进 jar），依赖只有 JDK 17 + MySQL + Redis。

### 1. 前提

| 项 | 要求 |
|---|---|
| JDK | 17 |
| MySQL | 8.x。**先手工建空库**：`CREATE DATABASE hainan_majong DEFAULT CHARSET utf8mb4;`。表由 `SchemaInitializer` 启动时幂等创建（`user` / `game_sessions` / `game_rounds` / `game_round_scores`），所以账号要有建表/改表权限 |
| Redis | 6/7 并设密码。连不上应用仍能起，但断线重连 / 快照恢复降级 |
| 必填凭据 | `DB_PASSWORD`、`REDIS_PASSWORD`、`JWT_SECRET`（**≥32 字节**，否则 `WeakKeyException` 起不来） |
| 网络 | 一个端口（默认 8080）+ 域名 + HTTPS 证书（前端在 https 下自动改用 `wss`） |
| 实例数 | **只能 1 个**：房间状态在内存（`MultiPlayerRoomService`），多实例会把同一个房间的人分到不同进程 |

### 2. 打包与启动

```bash
# 本机打包
mvn clean package

# 服务器上：.env 与 jar 放同一目录，工作目录必须是该目录
cd /opt/mahjong
SPRING_PROFILES_ACTIVE=prod java -Xms256m -Xmx768m -Duser.timezone=Asia/Shanghai \
    -jar HainanMaJhong2-1.0-SNAPSHOT.jar
```

### 3. `.env` 写什么

复制 `.env.example` 为 `.env`（放在 jar 同一个目录），最少这五行：

```ini
DB_PASSWORD=你的MySQL密码
REDIS_PASSWORD=你的Redis密码
JWT_SECRET=用 openssl rand -base64 48 生成的一串
DB_HOST=127.0.0.1        # 云数据库就写它的【内网】地址
REDIS_HOST=127.0.0.1
```

其余都有默认值，可选：`DB_PORT` / `DB_NAME` / `DB_USERNAME` / `DB_POOL_SIZE` / `REDIS_DB` /
`SERVER_PORT` / `JWT_ACCESS_EXPIRATION` / `APP_APK_PATH`，含义见 `.env.example` 注释。

`.env` 已被 `.gitignore` 忽略。它是靠 `application-prod.yml` 里那行
`spring.config.import: optional:file:./.env[.properties]` 读进来的，**所以工作目录不对就读不到**
（此时只能靠环境变量，例如 systemd 的 `EnvironmentFile=`；环境变量优先级高于该文件）。

### 4. systemd（推荐）

```ini
# /etc/systemd/system/mahjong.service
[Unit]
Description=Hainan Mahjong
After=network.target

[Service]
User=mahjong
WorkingDirectory=/opt/mahjong          # .env 必须在工作目录里
Environment=SPRING_PROFILES_ACTIVE=prod
Environment=LANG=C.UTF-8               # 否则中文日志可能乱码
ExecStart=/usr/bin/java -Xms256m -Xmx768m -Duser.timezone=Asia/Shanghai \
          -jar /opt/mahjong/HainanMaJhong2-1.0-SNAPSHOT.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

发版就是：`mvn clean package` → `scp` jar → `systemctl restart mahjong`。

### 5. nginx（TLS + WebSocket 反代）

静态文件由 jar 自己提供，nginx 只做 TLS 和转发。**`Upgrade` 头必须转发**，否则
`/room`、`/game` 两个 WebSocket 端点握手失败，前端表现是"一直连接中"。

```nginx
server {
  listen 443 ssl http2;
  server_name 你的域名;
  ssl_certificate     /etc/letsencrypt/live/你的域名/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/你的域名/privkey.pem;

  location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_read_timeout 3600s;          # 长连接别被 60s 默认值掐断
  }
}
```

### 6. 上线前自检

- 大厅「下载APP」走 `/download/app`，由 `AppDownloadController` 提供
  （`Content-Disposition: attachment; filename="jiangpahnmj.apk"`）。
  换安装包不用重打 jar：`.env` 里设 `APP_APK_PATH=/opt/mahjong/jiangpahnmj.apk` 即可。
- `/api/debug/**` 诊断端点只在 `local` profile 注册（`DebugController` 上的 `@Profile("local")`），
  `prod` 启动时它不存在（404），不会把房间码 / 昵称 / userId 暴露到公网。
- SQL 日志只在 `local` 打开（`StdOutImpl`）；`prod` 用 `NoLoggingImpl`，查询参数不会进日志。
- MySQL / Redis 不暴露公网：安全组只开 22/80/443，应用用内网地址连接。
- `application-prod.yml` 的 JDBC URL 带了 `useSSL=false&allowPublicKeyRetrieval=true`：
  MySQL 8 默认 `caching_sha2_password`，不走 TLS 时缺这两个参数会报
  "Public Key Retrieval is not allowed"。云数据库强制 TLS 时改成 `useSSL=true` 并配证书。
