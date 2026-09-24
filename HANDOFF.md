# 海南麻将 · 交接文档（2026-09-24 重写）

> **本文件是当前唯一权威交接。** 旧版（描述 `hainanjong` 单包 + `static/` 单文件前端的时代）已整体过期，
> 对应代码在提交 `2a75c4d` 里被重写。桌面上的 `海麻-新会话交接.md` 只是指向本文件的入口。
> 详细结构见 `HainanMaJhong/README.md` 的「代码结构」，前端硬约定见 `HainanMaJhong/frontend/README.md`。
> 新会话直接说「请先读 F:/HainanMaJhong2/HANDOFF.md」即可。

---

## 0. 一句话现状

后端 = Spring Boot 2.7 / JDK 17 分层架构 + JWT 认证 + 原生 WebSocket 双端点；
前端 = Vue 3 + Vite 单页应用（含手机虚拟横屏）；
**本地**：后端 38 个测试、前端 `pnpm check`、smoke 123 项、桌面探针 115 项、手机探针 127/97 项，全绿；
**线上**：`jiangpahnmj.cn` 仍跑旧版本，新 jar 已打好但**还没部署**（且服务器当前连错了数据库，见 §6.1）。

---

## 1. 仓库与分支

| 项 | 值 |
|---|---|
| git 根 / 模块 | `F:\HainanMaJhong2` / `F:\HainanMaJhong2\HainanMaJhong` |
| 远程 | `https://github.com/jiangpa1/HainanMaJhong`（public） |
| 当前分支 | **`release/v2-arch`**（拉出来当新主分支用） |
| 提交 | `2a75c4d 整理代码架构，增加全局拦截器，增加jwt身份校验`（274 文件，+29033/−7432） |
| `main` | 已快进到同一个提交并推送（`b94fed7 → 2a75c4d`） |
| 旧分支 | `refactor/arch-stage1`（5e65364，本次提交的父）、`v4-update`（更早的 WIP），都保留没动 |

**网络**：这台机器访问 GitHub 必须走本机代理 `127.0.0.1:7897`（已配在 git 全局）。
`git -c http.proxy= -c https.proxy= ...` 是给**本地 curl**用的（绕开代理），对 GitHub 反而连不上。

**`.gitignore` 已重写为 UTF-8**（原来是 GBK/UTF-8 混编，中文注释全是乱码）。以下不入库：
`node_modules/`（4000+ 文件）、`HainanMaJhong/docs/probe-*.png` 与 `ui-*.png`（探针截图，可重跑生成）、
`back/*.jar`（旧版参考 jar 44MB，只在本地对照）、`.env`、`HainanMaJhong/game_log.txt`。

**仓库里有两份 README、两份 APK，别搞混**：

- `README.md`（**根目录**）—— 2026-09-24 由用户通过 GitHub 网页上传（同一提交还上传了根目录的
  `jiangpahnmj.apk`）。里面也有一节「代码结构」，但措辞偏旧。
- `HainanMaJhong/README.md`（**模块**）—— **权威版**：代码结构、牌张下标、番型、WebSocket 协议、
  部署到云服务器（systemd / nginx 片段）。**改文档改这一份**。
- APK：根目录那份是网页上传的留档；`HainanMaJhong/src/main/resources/static/apk/jiangpahnmj.apk`
  才是 `/download/app` 真正下发的一份（`.env` 的 `APP_APK_PATH` 可指向服务器上的外部文件，换包不用重打 jar）。

---

## 2. 架构速览

### 后端 `HainanMaJhong/src/main/java/hainanMahjong/`（18 个包）

| 包 | 干什么 |
|---|---|
| `config/` | `WebMvcConfig`（注册拦截器 + HTML 不缓存）、`WebSocketConfig`（`/room`、`/game` 两端点）、`MybatisPlusConfig` |
| `interceptor/` | `JwtInterceptor`（身份 + **单点登录**判定 + 401/4011 口径）、`AuthorizationInterceptor`（角色） |
| `controller/` | `AuthController`（登录/注册/改昵称改密/刷新/登出）、`RoomController`（`/pending`、`/check`）、`RecordsController`、`AppDownloadController`、`DebugController`（**仅 local profile**） |
| `service/` + `impl/` | `RoomService`（建房/加入/解散/踢人/预检）、`GameEngineService`（跑把）、`GamePushService`（唯一发送出口）、`RecordsService`（战绩装配）、`TokenService`、`UserService`、`RoomSnapshotService`、`RoundPersister`、`SchemaInitializer`（启动幂等建表）、`MultiPlayerRoomService` |
| `engine/` | `RoomManager`（一把的完整流程）；`bot/`、`human/`（`HumanPlayerController` 等人应答）、`rule/`（吃碰杠胡判定策略）、`port/`、`model/` |
| `room/` | `Room`/`Member`/`RoomRegistry`（注册表 + 会话绑定）/`SwitchingController`（机器人托管切换）/`State` |
| `rules/` | `HuLib`（查表判胡）、`HainanFan`、`HainanScore`（算分 + 包三道/包四道）、`HainanConfig`、`DealerFlow` |
| `mapper/` + `repository/` | MyBatis-Plus（注解动态 SQL 必须 `<script>` 开头）、`RoomSnapshotRepository`（Redis 房间快照） |
| `websocket/` | `RoomWebSocketHandler`、`GameWebSocketHandler`、`WsAuthHandshakeInterceptor`（握手鉴权 + 单点登录） |
| 其它 | `common/`（`Result`/`PageResult`/`CacheKeys`）、`exception/`（`GlobalExceptionHandler` 统一 `{code,message,data}`）、`dto/`/`vo/`/`pojo/`/`properties/`/`utils/`/`annotation/` |

### 前端 `HainanMaJhong/frontend/`

- `src/views/` Login / Lobby / Room / GameTable / Records；`src/components/` layout·lobby·table·tile·records
- `src/stores/game.js` 牌局状态机（WS 消息 → 状态 → 界面）；`src/game/` tiles·socket·audio·preload·assets·roomConfig
- `src/composables/useLandscapeScale.js` 虚拟横屏（旋转 + 缩放 + `--design-h` 紧凑设计高）
- `src/api/api.js` 接口封装（401 续期、**4011 被顶下线**、错误口径）；`src/styles/` base.css + landscape.css
- 构建产物**直接写进** `../src/main/resources/static/`，必须入库（服务器上没有 Node）

---

## 3. 环境与运行

| 工具 | 路径 |
|---|---|
| JDK 17 | `C:\Users\ASUS\.jdks\ms-17.0.20.1-1`（**必须 17**，class 文件版本 61） |
| Maven | `C:\Users\ASUS\tools\apache-maven-3.9.16\bin\mvn.cmd`（`-o` 可离线跑） |
| Node / pnpm | pnpm 10.x（**npm 是 6.14，别用**） |
| Python | `C:\Users\ASUS\AppData\Local\Programs\Python\Python39\python.exe`（装了 Pillow 11） |
| Chrome | `C:\Program Files\Google\Chrome\Application\chrome.exe` |
| mysql 客户端 | `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe` |

- **开发库/Redis**：`192.168.133.128`（本机 VM，**经常是关着的**；连不上时应用照常启动，只是持久化打警告）
- **凭据**：都在 `HainanMaJhong/.env`（`DB_PASSWORD` / `REDIS_PASSWORD` / `JWT_SECRET`）。
  **不要把密码写进任何文档或提交**；`.env.example` 是模板，`.env` 已被 gitignore。
- 本地启动：`run-local.ps1`（读 `.env` 并探活 DB/Redis），或
  `DB_PASSWORD=… REDIS_PASSWORD=… JWT_SECRET=… java -jar target\HainanMaJhong2-1.0-SNAPSHOT.jar`
- 云端启动：`SPRING_PROFILES_ACTIVE=prod` + `.env`（见 §6.1）

---

## 4. 验证（改完必跑，别靠肉眼）

```bash
# 后端：38 个测试（判胡、房间拆解落库、动态 SQL、握手鉴权、单点登录踢人）
cd F:\HainanMaJhong2\HainanMaJhong
set JAVA_HOME=C:\Users\ASUS\.jdks\ms-17.0.20.1-1
mvn -o -B clean package

# 前端：构建 + 三项静态自检（牌编码 / 音频与预加载清单对磁盘 / 构建产物一致）
cd frontend
pnpm build
pnpm check
node tools/smoke.mjs                 # happy-dom 驱动交互链路，123 项

# 真浏览器量像素（改布局必须跑）
python ..\docs\browser-probe.py --wait 12000 --shot ..\docs\probe-table.png    # 桌面 115 项
python ..\docs\mobile-probe.py --wait 16000                                    # 手机竖屏 127 项
python ..\docs\mobile-probe.py --orientation landscape --wait 16000            # 手机横屏 97 项
python ..\docs\mobile-probe.py --only table|history|settings|rule --shot x.png  # 只跑到某一屏出图
```

**跑的时候要知道的四件事**：

1. `smoke.mjs` 打完 `==== 全部通过 ====` **不会自己退出**（牌桌里有常驻 `setInterval`）——用超时杀掉，
   看结论行即可，这是旧行为、不是卡死。
2. 无头 Chrome 有 18px 边框 / 152px 标题栏开销，**最小窗宽被钳到 500**；所以探针默认 `--size 518,1232`
   （换算出 500×1080 的布局视口，比例 2.16:1，与真机一致）。换成更方的窗口，算出的留白会失真。
3. 探针会**注入假 WebSocket + 假 fetch**（`docs/browser-probe.py` 里的 `SHIM`），
   验的是「构建产物 + 真实 Chrome 渲染」，不连后端。
4. 手机探针里那份「设计坐标换算」假定整页旋转 90°，所以横屏（不旋转）下相关断言会自动跳过。

---

## 5. 这套代码里踩过的坑（硬约定）

完整版在 `HainanMaJhong/frontend/README.md` 第 1~10 条。最要命的六条：

1. **不要用 PowerShell 改 `.vue`/源码文件**（`Get-Content -Raw` 按 ANSI 读、`Set-Content -Encoding UTF8` 加 BOM
   → 双重编码，曾毁掉整个 `GameTable.vue`）。用编辑工具，或 `[IO.File]::WriteAllText($p,$t,UTF8Encoding($false))`。
2. **虚拟横屏下 `vh` 是物理视口**：页面根元素不能用 `min-height:100vh`，弹窗不能用 `88vh` 限高
   （曾导致「规则窗口上下都在屏幕外」）。用 `max-height: min(100%, var(--frame-h))`。
3. **旋转只做在 `#game-app` 上**，不要再转 `.scale-layer`；布局改动必须用探针量像素，
   而且旋转后 `getBoundingClientRect` 是**视觉**尺寸，判断上下左右要用设计坐标。
4. **牌桌是 1280×(设计高) 的等比缩放固定框**：手机竖屏用紧凑设计高 590（手牌 lg、操作键竖排等宽），
   桌面/横屏必须保持 720 —— 桌面那套有逐像素断言。
5. **CSS 注释里不能出现 `*/`**（提前结束注释，整段样式被静默丢弃）；**MyBatis 注解动态 SQL 必须以 `<script>` 开头**。
6. **改完前端一定要 `pnpm build` 再入库**；打 jar 用 `mvn clean package`（不带 `clean` 会把历次静态产物留在 jar 里，
   旧 URL 还能取到旧文件）。

---

## 6. 待办（按优先级）

### 6.1 云端部署新版本（最高优先，线上还是旧代码）

服务器：阿里云 ECS，域名 `jiangpahnmj.cn`，systemd 服务名 `mahjong`，nginx 反代
（**服务名/路径以服务器实际为准，先 `systemctl cat mahjong`**）。

已经踩到的两个坑：

1. **502 / 登录「服务器内部错误」**：应用在往 `192.168.133.128:3306` 发 SYN（那是本机开发 VM 的地址，
   公网服务器永远连不上）→ 说明服务器上跑的是 **local profile**，用上了 `application-local.yml` 的默认库地址。
   修法：unit 里加 `Environment=SPRING_PROFILES_ACTIVE=prod`，并在 `/opt/mahjong/.env` 写
   `DB_HOST=127.0.0.1`（或云库内网地址）+ `DB_PASSWORD` / `REDIS_PASSWORD` / `JWT_SECRET`；
   `WorkingDirectory` 必须是 `.env` 所在目录。
2. **老客户端会 404**：新 jar 里已经没有 `game.html` / `lobby.html` / `room.html` / `records.html`
   （新版只有一个 `index.html`，hash 路由）。老 APK（WebView）打开旧路径会白屏 → 要么发新 APK
   （`.env` 里 `APP_APK_PATH=/opt/mahjong/jiangpahnmj.apk`，换包不用重打 jar），要么在新前端给老路径加重定向。

上线前清单（都已在代码里处理，别改回去）：`/download/app` 已实现；`/api/debug/**` 只在 local 注册；
prod 关闭 SQL 日志；`.env` 不进仓库；MySQL/Redis 不暴露公网；**应用只能单实例**（房间状态在内存）。
部署步骤与 nginx 配置（含 WebSocket `Upgrade` 头）见 `HainanMaJhong/README.md` 的「部署到云服务器」。

### 6.2 其它

| # | 事项 | 说明 |
|---|---|---|
| 1 | BGM 压缩 | `music/background.mp3` = **2.49MB**，占手机首包 80%。本机**没有 ffmpeg**，需外部压到 64kbps 单声道（约 500KB） |
| 2 | 删无用大图 | `static/img/table_bg.png` = 2.42MB，**前端无任何引用**（旧版遗留，git 里有）。删了只影响 jar 体积，`check-tiles.mjs` 会提示 |
| 3 | GitHub 默认分支 | `main` 已指向新代码；若要让侧边栏显示 `release/v2-arch`，去 Settings → Branches 换一次 |
| 4 | 空闲大厅的「被顶下线」 | 现在靠 HTTP 4011 + WS `kick`；用户挂在大厅不动时要等下一次请求。要即时就得加 30~60s 心跳 |
| 5 | 历史 `total_fan` | 语义从「番型个数」改成「倍数求和」，库里 4700+ 行旧数据没回填（新数据正确） |
| 6 | `@RequireRole` / `AuthorizationInterceptor` | 已实现但 0 处使用；`DebugController` 用 `@Profile("local")` 兜住 |
| 7 | 老分支 | `v4-update` 是更早的 WIP，确认没用了可以删 |

---

## 7. 给新窗口的起点建议

1. 先跑一遍 §4 的验证命令确认基线（**别直接改代码**）。
2. 改界面 → 必然跑 `mobile-probe.py`（竖屏 + 横屏两个方向）；改牌桌布局 → 还要跑桌面探针。
3. 要动认证/房间 → 先看 §2 的类职责表和 `frontend/README.md` 的硬约定，别绕过拦截器。
4. 要部署 → 按 §6.1；服务器操作前先 `systemctl cat mahjong`、`journalctl -u mahjong -n 80`；
   应用日志若不在 journal 里，就在 unit 里加 `StandardOutput=append:/var/log/mahjong.log`。
5. 旧版参考：`docs/legacy-ref/*.html`（从旧 jar 抽出的单文件页面）、
   `back/HainanMaJhong.jar`（44MB，未入库，本地对照用）。
