# 阶段 0 / 阶段 1 执行清单（可直接照敲）

> **配套文档**：`docs/ARCH-REFACTOR.md`（目标态与后续阶段）。本文只讲**阶段 0（安全网）**和**阶段 1（纯物理搬包）**。
> **编写时间**：2026-09-20 · 基线 HEAD `b94fed7` · 工作区干净
> **执行前请默念三遍**：阶段 1 **只允许**改「文件位置」「`package` 行」「`import` 行」「类名 + 它的引用」。**任何方法签名、字段名、常量值、URL、前端文件都不许动。**

---

## 目录

- [Part A：阶段 0 —— 安全网（半天）](#part-a阶段-0--安全网半天)
- [Part B：阶段 1 —— 纯物理搬包（1 天）](#part-b阶段-1--纯物理搬包1-天)
  - [B1 前置检查](#b1-前置检查10-分钟)
  - [B2 建目录 + git mv（30 条命令）](#b2-建目录--git-mv30-条命令)
  - [B3 改 package 行（17 个文件，顺序敏感）](#b3-改-package-行17-个文件顺序敏感)
  - [B4 改 import（16 个文件，逐文件精确清单）](#b4-改-import16-个文件逐文件精确清单)
  - [B5 三处额外的破坏性引用（漏了就编译不过）](#b5-三处额外的破坏性引用漏了就编译不过)
  - [B6 类名改动的引用同步](#b6-类名改动的引用同步)
  - [B7 编译 + 启动 + bean 核对](#b7-编译--启动--bean-核对)
  - [B8 回归验证](#b8-回归验证)
  - [B9 提交](#b9-提交)
- [Part C：回滚预案](#part-c回滚预案)
- [Part D：本阶段**不做**的事（防止手痒）](#part-d本阶段不做的事防止手痒)

---

# Part A：阶段 0 —— 安全网（半天）

目标：**在动任何文件之前，拿到一份"改造前行为"的可比对快照**。没有它，阶段 1 之后你无法证明"行为没变"。

## A0. 确认起点状态（2 分钟）

```powershell
cd F:\HainanMaJhong2
git status --short                 # 期望：无输出（干净）
git log --oneline -1               # 期望：b94fed7
git rev-parse HEAD                 # 记下来：b94fed7…
```

- [ ] `git status` 干净、HEAD 是 `b94fed7`

## A1. 记录基线（5 分钟）

> ⚠️ **命令里一律写绝对路径**。下面这段会 `cd` 到 `HainanMaJhong\`，如果你之后在同一个窗口里复制了带**相对路径**的命令（比如 `Add-Content .gitignore`），文件会落到 `HainanMaJhong\.gitignore` 而不是仓库根 —— 这是个很容易埋下的坑。

```powershell
New-Item -ItemType Directory -Force -Path 'F:\HainanMaJhong2\docs' | Out-Null
"基线 HEAD: $(git -C F:\HainanMaJhong2 rev-parse HEAD)" | Set-Content 'F:\HainanMaJhong2\docs\baseline-info.txt'
"日期: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Add-Content 'F:\HainanMaJhong2\docs\baseline-info.txt'

# 源码规模（后面用来确认"只搬不改"）
$src = 'F:\HainanMaJhong2\HainanMaJhong\src\main\java'
$f = Get-ChildItem $src -Recurse -Filter *.java
"主源码 $($f.Count) 个文件 / $(($f | Get-Content | Measure-Object -Line).Lines) 行" | Add-Content 'F:\HainanMaJhong2\docs\baseline-info.txt'
Get-Content 'F:\HainanMaJhong2\docs\baseline-info.txt'
```

- [ ] `baseline-info.txt` 里记下了 **38 个文件 / 6679 行**（这是阶段 1 后必须保持不变的数字）

## A2. 编译基线（10 分钟）

```powershell
$env:JAVA_HOME = 'C:\Users\ASUS\.jdks\ms-17.0.20.1-1'   # ★ 固定用 17，别用 openjdk-26（见主文档 R5）
& 'C:\Users\ASUS\tools\apache-maven-3.9.16\bin\mvn.cmd' `
   -f 'F:\HainanMaJhong2\HainanMaJhong\pom.xml' -B clean package
```
- [ ] `BUILD SUCCESS`
- [ ] `HainanMaJhong\HainanMaJhong\target\HainanMaJhong2-1.0-SNAPSHOT.jar` 存在（约 45 MB）

> ⚠️ **两个实测坑（2026-09-20 亲测，别踩）**：
> 1. **JDK 17 的正确路径带 `-1` 后缀**：`C:\Users\ASUS\.jdks\ms-17.0.20.1-1`。
>    同目录下还有个 `ms-17.0.20.1`，**那是空的/未解压完的**（`bin\java.exe`、`javac.exe`、`release` 全都**不存在**）。用错那个会得到 `JAVA_HOME 指向的不是 JDK` 之类莫名其妙的报错。
>    ```powershell
>    # 一行自检（应输出 openjdk version "17.0.20.1"）
>    & 'C:\Users\ASUS\.jdks\ms-17.0.20.1-1\bin\java.exe' -version 2>&1 | Select-Object -First 1
>    ```
> 2. **PATH 上的 `java` 是 Java 8**（`C:\Program Files (x86)\Common Files\Oracle\Java\java8path\java.exe`，实为 1.8.0_431）。
>    所以**不要**直接敲 `java -jar ...` —— 会报 `UnsupportedClassVersionError: class file version 61.0`。启动 jar 必须用绝对路径或用 `-1` 那个 JDK：
>    ```powershell
>    & 'C:\Users\ASUS\.jdks\ms-17.0.20.1-1\bin\java.exe' -jar target\HainanMaJhong2-1.0-SNAPSHOT.jar
>    ```

## A3. 抓「消息基线」（30 分钟，**本阶段最重要的一步**）

目的：拿到改造前**所有** WebSocket 消息类型 + 每种消息的字段名，作为阶段 1 的比对基准。

**做法（DevTools 法，最省事）：**
1. 启动服务（设好 `DB_*` / `REDIS_*` 环境变量后，用 **JDK 17 的绝对路径**启动：
   `& 'C:\Users\ASUS\.jdks\ms-17.0.20.1-1\bin\java.exe' -jar target\HainanMaJhong2-1.0-SNAPSHOT.jar`
   —— 见 A2 的两个坑，PATH 上的 `java` 是 8）；
2. 浏览器开 `http://localhost:8080`，登录 → 大厅 → 创建房间 → 用「机器人补齐」凑满 4 人 → 开局；
3. **打到至少：一次吃/碰、一次杠、一次胡（或一次流局）**；
4. F12 → Network → 筛 `WS` → 点 `/game` 那条 → Messages → 全选导出（或复制全部）；
5. 把文本存成 `F:\HainanMaJhong2\docs\baseline-messages.txt`；
6. **同时**把 `/room` 那条连接的消息也导出，追加到同一文件（用 `===== /room =====` 分隔）。

**然后生成"类型 + 字段名"清单**（这才是真正要 diff 的东西）：

```powershell
$raw = Get-Content 'F:\HainanMaJhong2\docs\baseline-messages.txt' -Raw
$json = [regex]::Matches($raw, '\{[^\r\n]*?"type"\s*:\s*"[a-z_]+"[^\r\n]*?\}')
$rows = foreach ($m in $json) {
    try { $o = $m.Value | ConvertFrom-Json } catch { continue }
    if ($o.type) { "{0}`t{1}" -f $o.type, (($o.PSObject.Properties.Name | Sort-Object) -join ',') }
}
$rows | Sort-Object -Unique | Set-Content 'F:\HainanMaJhong2\docs\baseline-message-types.txt'
$rows | Sort-Object -Unique
```

- [ ] `baseline-messages.txt` 存在，含 `/room` 与 `/game` 两段
- [ ] `baseline-message-types.txt` 生成成功
- [ ] **人工核对**：出现的 `type` 应覆盖下列集合（我从源码里扫出来的全集，供比对）

| 来源 | type |
| --- | --- |
| `/room` 段 | `room_info`, `start`, `join_denied`, `room_closed` |
| `/game` 段 | `welcome`, `match`, `hand_start`, `turn`, `board`, `hand`, `counts`, `draw`, `flower`, `discard`, `meld`, `hu`, `report`, `coins`, `chat`, `dealer`, `log`, `end`, `request`, `match_finish` |

> ⚠️ 两个提醒：
> ① **`request` 是私发**（只发给该座位本人，见 `MultiPlayerRoomService` 里 `sendJson` 的调用），抓包时别只看一个窗口；
> ② **`kicked` 已经不存在了**（单人模式下线后后端不再发），如果你在旧笔记里看到它，那是过时信息 —— 前端 `game.html:877` 还在处理它，属于死代码，阶段 3 再删。

## A4. 抓一份 Redis 快照基线（10 分钟）

**用途**：阶段 1 会挪 `RedisService`，阶段 2 会把它改成 `RoomSnapshotRepository` 并提取 `RoomSnapshot` 类 —— 有这份样本才能证明"老快照仍能恢复"。

```powershell
# 在一局正在进行时执行（对局中段，别等结束）
redis-cli -h <REDIS_HOST> -a <REDIS_PASSWORD> --no-auth-warning GET room:<房间码> | Set-Content 'F:\HainanMaJhong2\docs\baseline-roomsnapshot.json'
# 没装 redis-cli 也可以用 RESP_app（你机器上有）导出，或临时写个 Java 一行程序
```
- [ ] `baseline-roomsnapshot.json` 存在，且包含这些字段（**这是字段名必须保持不变的清单**）：
  `hostUserId, state, blockNo, sessionId, currentHand, dealer, cfg, firstDealer, bottom, windIdx, flowHandNo, firstDealerBegins, handsPlayed, coins, members, snapshot`

## A5. 抓 HTTP 响应基线（10 分钟）

```powershell
$out = 'F:\HainanMaJhong2\docs\baseline-http.txt'
Remove-Item $out -ErrorAction SilentlyContinue
$body = '{"username":"<你的测试账号>","password":"<密码>"}'   # ★ 别把这个文件提交进 git
$body | Set-Content $env:TEMP\login.json -Encoding ascii -NoNewline
& curl.exe -s -i --noproxy '*' -X POST 'http://localhost:8080/api/login' -H 'Content-Type: application/json' --data-binary "@$env:TEMP\login.json" | Add-Content $out
& curl.exe -s -i --noproxy '*' 'http://localhost:8080/api/room/pending?userId=1' | Add-Content $out
& curl.exe -s -i --noproxy '*' 'http://localhost:8080/api/records?userId=1' | Add-Content $out
& curl.exe -s -o NUL -w "index=%{http_code} lobby=" 'http://localhost:8080/' ; & curl.exe -s -o NUL -w "%{http_code}`n" --noproxy '*' 'http://localhost:8080/lobby.html'
```
- [ ] `baseline-http.txt` 存在，含 3 个接口的**完整响应体（含字段名）**
- [ ] **确认 `baseline-http.txt` 不会被提交**：检查 `.gitignore` 是否覆盖 → 建议直接加一行 `docs/baseline-http.txt`（如果里面有真实口令）

## A6. 打 tag（1 分钟）

```powershell
cd F:\HainanMaJhong2
git add docs/ARCH-REFACTOR.md docs/ARCH-REFACTOR-STAGE0-1.md
git commit -F - <<'EOF'
docs(arch): 新增分层改造指导文档与阶段0/1执行清单（含回归基线方案）
EOF
# ↑ 若你的 shell 不支持 here-doc：把消息写进文件再 git commit -F 文件（你惯用的方式）
git tag arch-baseline-20260920
git push origin main --tags
```
- [ ] tag `arch-baseline-20260920` 已在本地和远端

> ⚠️ `docs/baseline-messages.txt` / `baseline-http.txt` / `baseline-roomsnapshot.json` **不要提交**（含真实账号/房间码），先加进 `.gitignore`：
> ```powershell
> cd F:\HainanMaJhong2
> Add-Content 'F:\HainanMaJhong2\.gitignore' @'
> 
> # ---- 架构改造回归基线 / 本地运行日志（含真实账号与房间码，只本地留档）----
> docs/baseline-*
> docs/stage0-*
> docs/stage1-*
> docs/local-*
> backup-arch/
> commit-stage1.txt
> '@
> git -C F:\HainanMaJhong2 check-ignore -v docs/baseline-http.txt docs/stage0-run.log backup-arch/x.jar docs/ARCH-REFACTOR.md
> ```
> 注意最后那条的期望输出：**前三行命中、第四行（设计文档）不命中** —— 即设计文档要入库、基线不要。规则用 `baseline-*`/`stage0-*`/`stage1-*` 前缀精确匹配，不会误伤 `docs/ARCH-REFACTOR*.md`。
>
> ### 免手敲口令的启动脚本（已备好）
> `HainanMaJhong/run-local.ps1` —— 从 `.env` 读口令注入环境变量（**不回显、不进日志、不入库**），用 JDK 17 启动 jar，并顺便探活 MySQL/Redis：
> ```powershell
> cd F:\HainanMaJhong2\HainanMaJhong
> .\run-local.ps1 -Probe     # 只探活
> .\run-local.ps1            # 启动（日志写 docs\stage0-run.log）
> ```
> ⚠️ 这个脚本是**纯 ASCII** 写的 —— 因为 PowerShell 5.1 会用 ANSI/GBK 读 `.ps1`，文件里但凡有中文就会 `Unexpected token` 解析失败（你 HANDOFF §九 记过这个坑）。想加中文注释就只能用 `pwsh`（PS7）运行，别用 `powershell`。
>
> ### ⚠️ 前提：本机执行策略是 `Restricted`（实测），直接 `.\run-local.ps1` 会被拒
> 报错形如：`无法加载文件 …，因为在此系统上禁止运行脚本`（`PSSecurityException` / `UnauthorizedAccess`）。三种解法：
> ```powershell
> # 方案 A（推荐，一次性、不动全局设置）：绕过后运行
> powershell -NoProfile -ExecutionPolicy Bypass -File .\run-local.ps1
> # 方案 B（一劳永逸，只改当前用户，不需要管理员）：
> Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
> # 之后可直接：
> .\run-local.ps1
> ```
> 说明：`RemoteSigned` 在 `CurrentUser` 作用域下只影响你自己，且**本地写的脚本可直接运行、从网上下载的才要求签名** —— 不会降低从互联网来的脚本的安全门槛。方案 C（把脚本内容逐行粘贴到终端）也可以，但会把口令带进 PSReadLine 历史文件，不推荐。
>
> ### 备选：不想碰执行策略就用 `.cmd` 包装
> 新建 `HainanMaJhong\run-local.cmd`（`.cmd` 不受 PowerShell 执行策略约束）：
> ```bat
> @echo off
> powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-local.ps1" %*
> ```

---

# Part B：阶段 1 —— 纯物理搬包（1 天）

## B1 前置检查（10 分钟）

- [ ] Part A 全部完成，`git status` 干净
- [ ] 当前分支 `main`，且已 `git pull`（避免和别人/另一台机器的改动打架）
- [ ] IDEA 已关闭「自动优化 import / 保存时格式化」（避免它在后台改文件，让你无法判断改了什么）：
      `Settings → Tools → Actions on Save` 全部取消勾选
- [ ] 备份当前线上 jar（主文档 R7）：
```powershell
scp root@<服务器>:<线上jar路径> F:\HainanMaJhong2\backup-arch\hainanjong-$(Get-Date -Format yyyyMMdd).jar
scp root@<服务器>:/etc/nginx/sites-enabled/* F:\HainanMaJhong2\backup-arch\
scp root@<服务器>:/etc/systemd/system/mahjong.service F:\HainanMaJhong2\backup-arch\
```
- [ ] **新建分支**（强烈建议，别在 main 上直接搬）：
```powershell
cd F:\HainanMaJhong2
git checkout -b refactor/arch-stage1
```

---

## B2 建目录 + `git mv`（30 条命令）

> **为什么必须用 `git mv`**：它能保留文件历史（`git log --follow` 仍能追到改造前的提交）。用资源管理器拖拽或 `Move-Item` 会让 git 看成"删一个 + 建一个"，历史断掉。

**先在 `F:\HainanMaJhong2` 下开一个 PowerShell，逐块粘贴：**

### B2-a 建目录（空目录 git 不跟踪，所以先建着）

```powershell
$base = 'F:\HainanMaJhong2\HainanMaJhong\src\main\java\hainanjong'
foreach ($d in 'config','controller','websocket','repository','domain',
               'engine','engine\model','engine\port','engine\bot','engine\dev') {
    New-Item -ItemType Directory -Force -Path (Join-Path $base $d) | Out-Null
}
Get-ChildItem $base -Directory | Select-Object Name
```
- [ ] 目录建好：`config controller engine domain repository websocket`（`engine` 下有 4 个子目录）

### B2-b `web/` → 拆分（8 条）

```powershell
cd F:\HainanMaJhong2
$S = 'HainanMaJhong/src/main/java/hainanjong'

git mv "$S/web/AppDownloadController.java"     "$S/controller/AppDownloadController.java"
git mv "$S/web/AuthController.java"            "$S/controller/AuthController.java"
git mv "$S/web/RecordsController.java"         "$S/controller/RecordsController.java"
git mv "$S/web/RoomApiController.java"         "$S/controller/RoomController.java"
git mv "$S/web/RoomWebSocketHandler.java"      "$S/websocket/RoomWebSocketHandler.java"
git mv "$S/web/MultiGameWebSocketHandler.java" "$S/websocket/GameWebSocketHandler.java"
git mv "$S/web/AppWebConfig.java"              "$S/config/WebMvcConfig.java"
git mv "$S/web/WebSocketConfig.java"           "$S/config/WebSocketConfig.java"
```

### B2-c `web/MultiPlayerRoomService.java` → `service/`（1 条 + **注意见下**）

```powershell
git mv "$S/web/MultiPlayerRoomService.java" "$S/service/MultiPlayerRoomService.java"
```

> ⚠️ **这一步会让 4 个文件编译不过**（它们现在靠"同包"直接引用这个类，挪出包后必须显式 import）。这不是错误，是 B4 要补的。缺的 4 行 import 是：
> - `controller/RoomController.java`、`websocket/RoomWebSocketHandler.java`、`websocket/GameWebSocketHandler.java`、`config/WebSocketConfig.java`
> 详见 **B4 的 ⑭⑮⑯⑰**。

### B2-d `web/HumanPlayerController.java` → `service/`（1 条）

```powershell
git mv "$S/web/HumanPlayerController.java" "$S/service/HumanPlayerController.java"
```

### B2-e `game/` → `engine/`（15 条）

```powershell
git mv "$S/game/RoomManager.java"       "$S/engine/RoomManager.java"
git mv "$S/game/Action.java"            "$S/engine/model/Action.java"
git mv "$S/game/Meld.java"              "$S/engine/model/Meld.java"
git mv "$S/game/Player.java"            "$S/engine/model/Player.java"
git mv "$S/game/RoundResult.java"       "$S/engine/model/RoundResult.java"
git mv "$S/game/GameSnapshot.java"      "$S/engine/model/GameSnapshot.java"
git mv "$S/game/GameConfig.java"        "$S/engine/model/GameConfig.java"
git mv "$S/game/Seat.java"              "$S/engine/model/Seat.java"
git mv "$S/game/PlayerController.java"  "$S/engine/port/PlayerController.java"
git mv "$S/game/Responder.java"         "$S/engine/port/Responder.java"
git mv "$S/game/GameListener.java"      "$S/engine/port/GameListener.java"
git mv "$S/game/BotController.java"     "$S/engine/bot/BotController.java"
git mv "$S/game/PrintListener.java"     "$S/engine/dev/PrintListener.java"
git mv "$S/game/GameDemo.java"          "$S/engine/dev/GameDemo.java"
git mv "$S/game/EngineSmokeTest.java"   "$S/engine/dev/EngineSmokeTest.java"
```

### B2-f `service/RedisService.java` → `repository/`（1 条）

```powershell
git mv "$S/service/RedisService.java" "$S/repository/RoomSnapshotRepository.java"
```

### B2-g 检查搬完后的树

```powershell
Get-ChildItem "$S" -Recurse -Filter *.java | ForEach-Object { $_.FullName.Replace("$base\",'') } | Sort-Object
git status --short          # 期望：全部是 R（renamed），没有 D+A 的组合
```
- [ ] 文件数仍是 **38**（+0 新增，-0 删除）
- [ ] `web/` 与 `game/` 目录已空（git 不跟踪空目录，`Get-ChildItem` 可能还看得到空壳，正常）
- [ ] `git status` 里全部显示 `renamed:`

> ⚠️ 如果 `git status` 显示的是 `deleted + untracked` 而不是 `renamed`，说明 `git mv` 没生效（比如你先手工拖拽了）。用 `git restore --staged --worktree .` 回到干净状态重来。

---

## B3 改 `package` 行（17 个文件，**顺序敏感**）

> **顺序敏感的原因**：如果你先做全局替换 `hainanjong.game;` → `hainanjong.engine.model;`，那么 `RoomManager.java` 和 `engine/port` 里的文件（它们也是 `package hainanjong.game;`）会被**误改**。所以必须**按最终归属精确改**，且**先做最具体的**。

**逐个文件把第 1 行的 `package` 改成下表的值**（用 IDEA 打开每个文件改第一行，或按下面的分组用编辑器批量替换 —— 注意批量替换时**必须带上文件名限定**，别全仓替换）：

| 新路径 | 新 `package` |
| --- | --- |
| `controller/AppDownloadController.java` | `hainanjong.controller` |
| `controller/AuthController.java` | `hainanjong.controller` |
| `controller/RecordsController.java` | `hainanjong.controller` |
| `controller/RoomController.java` | `hainanjong.controller` |
| `websocket/RoomWebSocketHandler.java` | `hainanjong.websocket` |
| `websocket/GameWebSocketHandler.java` | `hainanjong.websocket` |
| `config/WebMvcConfig.java` | `hainanjong.config` |
| `config/WebSocketConfig.java` | `hainanjong.config` |
| `service/MultiPlayerRoomService.java` | `hainanjong.service`（原就是，**不用改**） |
| `service/HumanPlayerController.java` | `hainanjong.service` |
| `repository/RoomSnapshotRepository.java` | `hainanjong.repository` |
| `engine/RoomManager.java` | `hainanjong.engine` |
| `engine/model/Action.java` | `hainanjong.engine.model` |
| `engine/model/Meld.java` | `hainanjong.engine.model` |
| `engine/model/Player.java` | `hainanjong.engine.model` |
| `engine/model/RoundResult.java` | `hainanjong.engine.model` |
| `engine/model/GameSnapshot.java` | `hainanjong.engine.model` |
| `engine/model/GameConfig.java` | `hainanjong.engine.model` |
| `engine/model/Seat.java` | `hainanjong.engine.model` |
| `engine/port/PlayerController.java` | `hainanjong.engine.port` |
| `engine/port/Responder.java` | `hainanjong.engine.port` |
| `engine/port/GameListener.java` | `hainanjong.engine.port` |
| `engine/bot/BotController.java` | `hainanjong.engine.bot` |
| `engine/dev/PrintListener.java` | `hainanjong.engine.dev` |
| `engine/dev/GameDemo.java` | `hainanjong.engine.dev` |
| `engine/dev/EngineSmokeTest.java` | `hainanjong.engine.dev` |

**一行校验（改完必跑）**：
```powershell
$S = 'F:\HainanMaJhong2\HainanMaJhong\src\main\java\hainanjong'
# 期望：每个文件的 package 与它的目录路径一致；下面这条应输出 **空**
Get-ChildItem $S -Recurse -Filter *.java | ForEach-Object {
    $pkg = (Select-String -Path $_.FullName -Pattern '^package\s+([\w\.]+);').Matches.Groups[1].Value
    $dir = $_.DirectoryName.Replace("$S\",'').Replace('\','.')
    $expect = if ($dir) { "hainanjong.$dir" } else { 'hainanjong' }
    if ($pkg -ne $expect) { "MISMATCH: $($_.Name)  package=$pkg  期望=$expect" }
}
```
- [ ] 上一条输出为空（**package 与目录一一对应**）

---

## B4 改 `import`（16 个文件，逐文件精确清单）

> **规律**：同一新包**内部**的互相引用不需要 import（Java 同包可见）；**跨新包**才需要。我已经把"哪些文件需要改哪几行"全部扫出来了，照着改即可。

**改写对照表（把旧的整行替换成新的整行）：**

| 旧 import | 新 import |
| --- | --- |
| `hainanjong.game.Action` | `hainanjong.engine.model.Action` |
| `hainanjong.game.Meld` | `hainanjong.engine.model.Meld` |
| `hainanjong.game.Player` | `hainanjong.engine.model.Player` |
| `hainanjong.game.RoundResult` | `hainanjong.engine.model.RoundResult` |
| `hainanjong.game.GameSnapshot` | `hainanjong.engine.model.GameSnapshot` |
| `hainanjong.game.GameConfig` | `hainanjong.engine.model.GameConfig` |
| `hainanjong.game.Seat` | `hainanjong.engine.model.Seat` |
| `hainanjong.game.PlayerController` | `hainanjong.engine.port.PlayerController` |
| `hainanjong.game.Responder` | `hainanjong.engine.port.Responder` |
| `hainanjong.game.GameListener` | `hainanjong.engine.port.GameListener` |
| `hainanjong.game.BotController` | `hainanjong.engine.bot.BotController` |
| `hainanjong.game.RoomManager` | `hainanjong.engine.RoomManager` |
| （不变）`hainanjong.HuLib` / `hainanjong.HuResult` / `hainanjong.FanType` | 原样保留（本轮不搬） |
| （不变）`hainanjong.rules.*` | 原样保留 |
| （不变）`hainanjong.service.MysqlService` | 原样保留（本轮不搬） |

**逐文件清单（① ~ ⑰）：**

**① `engine/model/Action.java`**
```java
-import hainanjong.HuLib;          // 保持不变，无需改
```
→ **无需改动**（只改 package 行）

**② `engine/dev/EngineSmokeTest.java`**
```java
 import hainanjong.HuResult;       // 不变
```
→ **无需改动**

**③ `engine/port/GameListener.java`**
```java
 import hainanjong.HuResult;       // 不变
```
→ **无需改动**

**④ `engine/model/Meld.java`**
```java
 import hainanjong.HuLib;          // 不变
```
→ **无需改动**

**⑤ `engine/dev/PrintListener.java`**
```java
 import hainanjong.HuLib;          // 不变
 import hainanjong.HuResult;       // 不变
```
→ **无需改动**

**⑥ `engine/RoomManager.java`** ← 改 2 行
```java
 import hainanjong.HuLib;                        // 不变
 import hainanjong.HuResult;                     // 不变
 import hainanjong.rules.HainanFan;              // 不变
 import hainanjong.rules.RuleEnv;                // 不变
```
→ **无需改动**（同包引用 `PlayerController/Responder/GameListener/Action/...` 仍成立，因为它们都跟着搬进 `engine` 家族）

**⑦ `engine/model/RoundResult.java`** ← 改 1 行
```java
-import hainanjong.HuResult;        // 不变
```
→ **无需改动**；但 **第 77 行有全限定名**：`hainanjong.HuLib.cardName(winTile)` → 不变（HuLib 没搬）。**这条只是提醒你检查，不要动。**

**⑧ `engine/model/Player.java`** → **无需改动**

**⑨ `rules/DealerFlow.java`** ← 改 2 行 ★
```java
-import hainanjong.game.RoundResult;
-import hainanjong.game.Seat;
+import hainanjong.engine.model.RoundResult;
+import hainanjong.engine.model.Seat;
```

**⑩ `rules/HainanFan.java`** ← 改 1 行 ★
```java
 import hainanjong.FanType;                      // 不变
 import hainanjong.HuLib;                        // 不变
-import hainanjong.game.Meld;
+import hainanjong.engine.model.Meld;
```

**⑪ `rules/HainanConfig.java`** → **无需改动**（只 import `hainanjong.FanType`）

**⑫ `rules/HainanRulesSelfTest.java`** ← 改 2 行 + **15 处全限定名**（见 B5）★
```java
 import hainanjong.FanType;                      // 不变
-import hainanjong.game.Meld;
-import hainanjong.game.Seat;
+import hainanjong.engine.model.Meld;
+import hainanjong.engine.model.Seat;
```

**⑬ `rules/HainanScore.java`** ← 改 3 行 ★
```java
 import hainanjong.FanType;                      // 不变
-import hainanjong.game.Meld;
-import hainanjong.game.RoundResult;
-import hainanjong.game.Seat;
+import hainanjong.engine.model.Meld;
+import hainanjong.engine.model.RoundResult;
+import hainanjong.engine.model.Seat;
```

**⑭ `rules/RuleEnv.java`** ← 改 2 行 ★
```java
-import hainanjong.game.Meld;
-import hainanjong.game.Seat;
+import hainanjong.engine.model.Meld;
+import hainanjong.engine.model.Seat;
```

**⑮ `service/MultiPlayerRoomService.java`** ← **改 18 行**（最大的一处）★
```java
 import hainanjong.FanType;                      // 不变
 import hainanjong.HuResult;                     // 不变
-import hainanjong.game.Action;            +import hainanjong.engine.model.Action;
-import hainanjong.game.BotController;     +import hainanjong.engine.bot.BotController;
-import hainanjong.game.GameConfig;        +import hainanjong.engine.model.GameConfig;
-import hainanjong.game.GameListener;      +import hainanjong.engine.port.GameListener;
-import hainanjong.game.GameSnapshot;      +import hainanjong.engine.model.GameSnapshot;
-import hainanjong.game.Meld;              +import hainanjong.engine.model.Meld;
-import hainanjong.game.Player;            +import hainanjong.engine.model.Player;
-import hainanjong.game.PlayerController;  +import hainanjong.engine.port.PlayerController;
-import hainanjong.game.Responder;         +import hainanjong.engine.port.Responder;
-import hainanjong.game.RoomManager;       +import hainanjong.engine.RoomManager;
-import hainanjong.game.RoundResult;       +import hainanjong.engine.model.RoundResult;
-import hainanjong.game.Seat;              +import hainanjong.engine.model.Seat;
 import hainanjong.rules.DealerFlow;             // 不变
 import hainanjong.rules.HainanConfig;           // 不变
 import hainanjong.rules.HainanFan;              // 不变
 import hainanjong.rules.HainanScore;            // 不变
 import hainanjong.service.MysqlService;         // 不变
-import hainanjong.service.RedisService;
+import hainanjong.repository.RoomSnapshotRepository;
```
> ⚠️ 这个文件里**类名也要改**（`RedisService` → `RoomSnapshotRepository`）：第 67 行 `private final RedisService redis;`、第 77 行构造参数。**只改类型名，别改字段名 `redis`**（改了要动 20 多处调用点）。同文件的 `HumanPlayerController`（第 91、99 行）现在在 `hainanjong.service`，**同包，无需 import** ✓

**⑯ `service/HumanPlayerController.java`** ← 改 4 行 ★
```java
-import hainanjong.game.Action;
-import hainanjong.game.PlayerController;
-import hainanjong.game.Responder;
-import hainanjong.game.Seat;
+import hainanjong.engine.model.Action;
+import hainanjong.engine.port.PlayerController;
+import hainanjong.engine.port.Responder;
+import hainanjong.engine.model.Seat;
```

**⑰ `repository/RoomSnapshotRepository.java`**（原 `service/RedisService.java`）
→ **无需 import 改动**（它只 import Spring 的 `StringRedisTemplate` 和 `java.time.Duration`）；**但要改类名**：
```java
-public class RedisService {
+public class RoomSnapshotRepository {
-public RedisService(StringRedisTemplate redis) {
+public RoomSnapshotRepository(StringRedisTemplate redis) {
```
> 建议顺手把类名对应的注释也改了（`@Service` 保留 —— Stage 2 才换 `@Repository`）。

**⑱ `controller/AuthController.java`** / **⑲ `controller/RecordsController.java`**
```java
 import hainanjong.service.MysqlService;   // 不变
 import hainanjong.FanType;                // 不变（仅 RecordsController）
```
→ **无需改动**

**⑳ `controller/RoomController.java`**（原 `RoomApiController`）← **加 1 行**（见 B5-①）
```java
+import hainanjong.service.MultiPlayerRoomService;
```

**㉑ `controller/AppDownloadController.java`** / **㉒ `config/WebMvcConfig.java`**
→ **无需改动**

**㉓ `websocket/RoomWebSocketHandler.java`** ← **加 1 行**
```java
+import hainanjong.service.MultiPlayerRoomService;
```

**㉔ `websocket/GameWebSocketHandler.java`** ← **加 1 行**
```java
+import hainanjong.service.MultiPlayerRoomService;
```

**㉕ `config/WebSocketConfig.java`** ← **加 1 行 + 改类名引用**（见 B5-③）
```java
+import hainanjong.service.MultiPlayerRoomService;   // 其实它不直接用，检查后若不需要可省
```

---

## B5 三处额外的破坏性引用（**漏了就编译不过**）

### ① 同包引用变跨包（4 个文件）

`MultiPlayerRoomService` 从 `web` 挪到 `service` 后，以下 3 个文件必须**新增** import：

| 文件 | 需要新增 |
| --- | --- |
| `controller/RoomController.java` | `import hainanjong.service.MultiPlayerRoomService;` |
| `websocket/RoomWebSocketHandler.java` | `import hainanjong.service.MultiPlayerRoomService;` |
| `websocket/GameWebSocketHandler.java` | `import hainanjong.service.MultiPlayerRoomService;` |
| `config/WebSocketConfig.java` | 它只用两个 Handler（都在 `hainanjong.websocket`），**需要新增** `import hainanjong.websocket.RoomWebSocketHandler;` 和 `import hainanjong.websocket.GameWebSocketHandler;` |

> 这 4 处正是"搬包最经典的坑"：**原来靠同包可见，搬走后必须显式 import**。编译器会报 `cannot find symbol`，别慌，加 import 即可。

### ② `HainanRulesSelfTest.java` 里的 **15 处全限定名**

这个文件不用 import，而是**写死了全限定名** `hainanjong.game.RoundResult`（共 15 处，行号 168/176/184/192/194/202/204/206/208/215/223/246/250/269/294/322/330 —— 共 17 处，含 `winFull`）：

```powershell
$f = 'F:\HainanMaJhong2\HainanMaJhong\src\main\java\hainanjong\rules\HainanRulesSelfTest.java'
(Get-Content $f -Raw) -replace 'hainanjong\.game\.RoundResult', 'hainanjong.engine.model.RoundResult' |
    Set-Content $f -NoNewline -Encoding utf8
Select-String -Path $f -Pattern 'hainanjong\.game\.'   # 期望：无输出
```
- [ ] 该文件里 `hainanjong.game.` 已归零

> ⚠️ **顺带自查**：其他文件有没有同类"全限定名"？跑一遍：
> ```powershell
> Get-ChildItem 'F:\HainanMaJhong2\HainanMaJhong\src\main\java' -Recurse -Filter *.java |
>   Select-String -Pattern 'hainanjong\.(game|web)\.' |
>   Where-Object { $_.Line -notmatch '^\s*import' }
> ```
> **期望：无输出。**
>
> 已实测确认：全仓只有 **两个** 非 import 的全限定名，且只有一个是坏的 ——
> - ❌ `rules/HainanRulesSelfTest.java` 的 `hainanjong.game.RoundResult`（17 处，必须改成 `hainanjong.engine.model.RoundResult`）
> - ✅ `engine/model/RoundResult.java:77` 的 `hainanjong.HuLib.cardName(winTile)`（`HuLib` **本轮没搬**，原样保留，**不要动**）
>
> 另外全仓搜索 `hainanjong.service.` 也会命中 `import hainanjong.service.MysqlService` —— 那是合法的（本轮不搬 `MysqlService`），别误改。

### ③ `WebSocketConfig.java` 里的类名与字段名（3 处）

```java
 private final RoomWebSocketHandler roomHandler;                       // 不变
-private final MultiGameWebSocketHandler multiGameHandler;
+private final GameWebSocketHandler gameWebSocketHandler;
 public WebSocketConfig(RoomWebSocketHandler roomHandler,
-                       MultiGameWebSocketHandler multiGameHandler) {
+                       GameWebSocketHandler gameWebSocketHandler) {
-    this.multiGameHandler = multiGameHandler;
+    this.gameWebSocketHandler = gameWebSocketHandler;
```
再改 `registerWebSocketHandlers` 里的两行：
```java
-registry.addHandler(multiGameHandler, "/game").setAllowedOrigins("*");
+registry.addHandler(gameWebSocketHandler, "/game").setAllowedOrigins("*");   // ★ origins 本阶段不改！
```
> ⚠️ **`setAllowedOrigins("*")` 这一阶段绝对不要动**（改白名单属阶段 4）。本阶段结束时的行为必须与基线**完全一致**。

---

## B6 类名改动的引用同步（**只需 3 处**，我已扫全）

| 新类名 | 原类名 | 引用点 | 处理 |
| --- | --- | --- | --- |
| `hainanjong.config.WebMvcConfig` | `AppWebConfig` | **无外部引用**（Spring 只按类名注册） | ✓ 只改类名（`public class AppWebConfig implements WebMvcConfigurer` → `public class WebMvcConfig implements WebMvcConfigurer`） |
| `hainanjong.websocket.GameWebSocketHandler` | `MultiGameWebSocketHandler` | `config/WebSocketConfig.java`（见 B5-③） | 改 4 处 |
| `hainanjong.controller.RoomController` | `RoomApiController` | **无外部引用** | ✓ 只改类名 |
| `hainanjong.repository.RoomSnapshotRepository` | `RedisService` | `service/MultiPlayerRoomService.java`（见 B4-⑮） | 改 2 处（字段类型 + 构造参数类型） |

- [ ] 全仓搜一遍确认没有漏网：
```powershell
cd 'F:\HainanMaJhong2\HainanMaJhong\src\main\java'
foreach ($n in 'AppWebConfig','MultiGameWebSocketHandler','RoomApiController','RedisService') {
    $hits = Get-ChildItem . -Recurse -Filter *.java | Select-String -Pattern $n
    "{0,-30} {1} 处" -f $n, ($hits | Measure-Object).Count
}
```
**期望**：4 项全部 **0 处**。（若 `RedisService` 还有残留，说明 B4-⑮ / B6 漏了）

---

## B7 编译 + 启动 + bean 核对

### B7-a 编译

```powershell
$env:JAVA_HOME = 'C:\Users\ASUS\.jdks\ms-17.0.20.1-1'    # ★ 注意 -1 后缀，见 A2 的坑
& 'C:\Users\ASUS\tools\apache-maven-3.9.16\bin\mvn.cmd' `
   -f 'F:\HainanMaJhong2\HainanMaJhong\pom.xml' -B clean package 2>&1 |
   Tee-Object 'F:\HainanMaJhong2\docs\stage1-build.log' | Select-String 'BUILD|ERROR|error:'
```
- [ ] `BUILD SUCCESS`
- [ ] `docs/stage1-build.log` 里 `ERROR` 数为 0

**常见报错 → 对号入座：**

| 报错 | 原因 | 修法 |
| --- | --- | --- |
| `cannot find symbol: class MultiPlayerRoomService` | B5-① 漏了 | 给那 4 个文件补 import |
| `cannot find symbol: class Seat / Meld / RoundResult` | B4 漏了某个 import | 按 B4 表补 |
| `package hainanjong.game does not exist` | B3 的 package 行没改全，或漏了某个文件的 import | 用 B3 的校验脚本查 package；用 B5 末尾的脚本查全限定名 |
| `duplicate class` | B3 批量替换时误改了不该改的文件的 package | 按 B3 表逐个核对 |

### B7-b 启动 + bean 核对

```powershell
cd 'F:\HainanMaJhong2\HainanMaJhong'
$env:DB_URL='jdbc:mysql://<host>:3306/hainan_majong?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8'
$env:DB_USERNAME='root'; $env:DB_PASSWORD='<密码>'
$env:REDIS_HOST='<host>'; $env:REDIS_PASSWORD='<密码>'
# ★ 不要用裸 java（PATH 上是 Java 8），用 JDK 17 绝对路径：
& 'C:\Users\ASUS\.jdks\ms-17.0.20.1-1\bin\java.exe' -jar target\HainanMaJhong2-1.0-SNAPSHOT.jar 2>&1 |
    Tee-Object 'F:\HainanMaJhong2\docs\stage1-run.log'
```

另开一个窗口，**核对 Spring 是否扫到了全部 bean**（这是 R1 风险的验证）：

```powershell
# 方式一（推荐）：加 --debug 启动？太啰嗦。用 actuator？没装。
# 方式二：直接看"启动日志里出现的 bean 名"，本项目的 bean 名都是类名首字母小写
Select-String -Path 'F:\HainanMaJhong2\docs\stage1-run.log' -Pattern `
  'webMvcConfig|webSocketConfig|roomWebSocketHandler|gameWebSocketHandler|multiPlayerRoomService|mysqlService|roomSnapshotRepository|authController|recordsController|roomController|appDownloadController'
```
- [ ] **11 个 bean 全部出现**：

| bean 名 | 来源类 | 说明 |
| --- | --- | --- |
| `webMvcConfig` | `config/WebMvcConfig` | 原 AppWebConfig（HTML no-cache） |
| `webSocketConfig` | `config/WebSocketConfig` | 端点注册 |
| `roomWebSocketHandler` | `websocket/RoomWebSocketHandler` | `/room` |
| `gameWebSocketHandler` | `websocket/GameWebSocketHandler` | `/game`（★ 改名后应是这个名字） |
| `multiPlayerRoomService` | `service/MultiPlayerRoomService` | 房间+引擎 |
| `humanPlayerController`？ | `service/HumanPlayerController` | **不是 Spring bean**（它是 `new` 出来的，不是 `@Component`）→ 日志里**不该**有 ✓ |
| `mysqlService` | `service/MysqlService` | 建表 `@PostConstruct` |
| `roomSnapshotRepository` | `repository/RoomSnapshotRepository` | 原 RedisService（★ 改名后） |
| `authController` / `recordsController` / `roomController` / `appDownloadController` | `controller/*` | 4 个 REST |

- [ ] 启动日志里有 `数据表已就绪：user / game_sessions / game_rounds / game_round_scores`（证明 `MysqlService.@PostConstruct` 跑了 → **组件扫描正常**）

> **bean 名核对顺带说明**：`@Component`/`@Service` 默认 bean 名 = 类名首字母小写。所以改名会让 bean 名变，**这是预期的**。但要注意：如果你在别处用 `@Qualifier("redisService")` 之类按名字注入，就会失效 —— 我已全仓搜过，**没有按名字注入的地方**，安全。

---

## B8 回归验证（与 Part A 的基线逐项比对）

### B8-1 静态资源与 HTTP（对齐 HANDOFF §6）

```powershell
# 前端一行都没改，所以版本号必须还是 20260908c（本地源码）
Select-String -Path 'HainanMaJhong\src\main\resources\static\game.html' -Pattern '\?v=[0-9a-z]+' | ForEach-Object { $_.Matches.Value } | Select-Object -Unique
# 期望：?v=20260908c

& curl.exe -s -o NUL -w "index=%{http_code}`n"      --noproxy '*' 'http://localhost:8080/'
& curl.exe -s -o NUL -w "lobby=%{http_code}`n"      --noproxy '*' 'http://localhost:8080/lobby.html'
& curl.exe -s -o NUL -w "game=%{http_code}`n"       --noproxy '*' 'http://localhost:8080/game.html'
& curl.exe -s -o NUL -w "room=%{http_code}`n"       --noproxy '*' 'http://localhost:8080/room.html'
& curl.exe -s -o NUL -w "records=%{http_code}`n"    --noproxy '*' 'http://localhost:8080/records.html'
& curl.exe -s -o NUL -w "pending=%{http_code}`n"    --noproxy '*' 'http://localhost:8080/api/room/pending?userId=1'
& curl.exe -s -o NUL -w "download=%{http_code}`n"   --noproxy '*' 'http://localhost:8080/download/app'
```
- [ ] 七个全 200
- [ ] `game.html` 里 `?v=` 仍是 `20260908c`（**没 bump** = 确实没改前端）

### B8-2 HTTP 响应字段 diff（对 B 期基线）

```powershell
& curl.exe -s --noproxy '*' 'http://localhost:8080/api/room/pending?userId=1'
# 与 docs\baseline-http.txt 里同名请求的响应**逐字段比对**：字段名一个都不能少/不能变
```
- [ ] `pending` 返回 `{exists, code, state, seat}` 结构不变
- [ ] `records` 返回 `{records:[{sessionId,roomId,startTime,endTime,status,totalScore,rank}]}` 结构不变

### B8-3 WebSocket 协议 diff（**最重要**）

重复 Part A3 的抓包流程，导出为 `docs\stage1-messages.txt`，然后：

```powershell
function Get-Types($f) {
    $raw = Get-Content $f -Raw
    [regex]::Matches($raw, '\{[^\r\n]*?"type"\s*:\s*"[a-z_]+"[^\r\n]*?\}') | ForEach-Object {
        try { $o = $_.Value | ConvertFrom-Json } catch { return }
        if ($o.type) { "{0}`t{1}" -f $o.type, (($o.PSObject.Properties.Name | Sort-Object) -join ',') }
    } | Sort-Object -Unique
}
$base = Get-Types 'F:\HainanMaJhong2\docs\baseline-messages.txt'
$now  = Get-Types 'F:\HainanMaJhong2\docs\stage1-messages.txt'
Write-Output '=== 只在基线里（丢失了！）==='; Compare-Object $base $now | Where-Object SideIndicator -eq '<=' | ForEach-Object { $_.InputObject }
Write-Output '=== 只在改造后（多出来的！）==='; Compare-Object $base $now | Where-Object SideIndicator -eq '=>' | ForEach-Object { $_.InputObject }
```
- [ ] **两侧都必须为空** —— 即：消息类型集合与字段名集合**零差异**

### B8-4 Redis 快照 diff

```powershell
# 起一局，对局中段取一份
redis-cli -h <host> -a <pass> --no-auth-warning GET room:<新房间码> | Set-Content 'F:\HainanMaJhong2\docs\stage1-roomsnapshot.json'
# 用 JSON 比对字段名集合（顺序无关）
$a = (Get-Content 'F:\HainanMaJhong2\docs\baseline-roomsnapshot.json' -Raw | ConvertFrom-Json).PSObject.Properties.Name | Sort-Object
$b = (Get-Content 'F:\HainanMaJhong2\docs\stage1-roomsnapshot.json' -Raw | ConvertFrom-Json).PSObject.Properties.Name | Sort-Object
Compare-Object $a $b      # 期望：无输出
```
- [ ] 字段名集合无差异
- [ ] **重启恢复实测**：对局中 `Ctrl+C` 杀掉进程 → 重启 → 玩家凭房间码回来 → 能重建房间（这是 `roomSnapshotRepository` 改名后最关键的一条）

### B8-5 端到端清单（照 HANDOFF §6 的 10 条，本轮至少跑前 4 条）

| # | 场景 | 判据 | 结果 |
| --- | --- | --- | --- |
| 1 | 建房 → 满 4 开局 | 四端座位两两不同、昵称/金币/张数正确 | ☐ |
| 2 | 打一把含吃/碰/杠/胡 | 四端事件一致；暗杠他人只见牌背 | ☐ |
| 3 | 某人关窗 | 其余端继续，该座 bot 托管 | ☐ |
| 4 | 服务重启后续跑 | 凭码回来能重建（B8-4 已覆盖） | ☐ |
| 5 | 战绩页 | `records.html` 与改造前一致 | ☐ |
| 6 | APK 下载 | `/download/app` 返回 apk | ☐ |

### B8-6 规模自检（证明"只搬不改"）

```powershell
cd 'F:\HainanMaJhong2\HainanMaJhong'
$f = Get-ChildItem src/main/java -Recurse -Filter *.java
"文件数 $($f.Count) / 总行数 $(($f | Get-Content | Measure-Object -Line).Lines)"
```
- [ ] **仍是 38 个文件**（行数允许 ±0；若总行数变了，说明你顺手改了逻辑 —— 回去 `git diff` 看看）

---

## B9 提交

```powershell
cd F:\HainanMaJhong2
git status --short        # 检查：应只有 renamed + modified（package/import 行）
git diff --stat           # 期望：每个文件只有 1~20 行改动，且都是 package/import
git diff                 # ★ 逐页过一遍：确认没有一行是"逻辑改动"
```

把提交信息写进文件（你惯用的方式，避免 `-m` 被 shell 吃掉特殊字符）：

```powershell
@'
refactor(arch): 阶段1 纯物理搬包，对齐 Learning 分层

web/ 拆分：
- AppWebConfig        → config/WebMvcConfig（类名同步）
- WebSocketConfig     → config/WebSocketConfig
- MultiGameWebSocketHandler → websocket/GameWebSocketHandler（类名同步）
- RoomWebSocketHandler → websocket/RoomWebSocketHandler
- AuthController / RecordsController / RoomApiController(→RoomController) / AppDownloadController → controller/
- MultiPlayerRoomService → service/
- HumanPlayerController  → service/

game/ → engine/：
- RoomManager → engine/
- Action/Meld/Player/RoundResult/GameSnapshot/GameConfig/Seat → engine/model/
- PlayerController/Responder/GameListener → engine/port/
- BotController → engine/bot/
- PrintListener/GameDemo/EngineSmokeTest → engine/dev/

service/RedisService → repository/RoomSnapshotRepository（类名同步）

行为不变：JSON 字段名、WS 消息类型与字段、URL、静态资源版本号全部未改。
验证：
- mvn -B clean package 通过，38 文件 / 6679 行未变
- 11 个 Spring bean 全部注册
- WS 消息类型+字段名 与 docs/baseline-messages.txt 零差异
- Redis 快照字段名无差异；重启凭码能重建
'@ | Set-Content F:\HainanMaJhong2\commit-stage1.txt -Encoding utf8

git add -A
git commit -F F:\HainanMaJhong2\commit-stage1.txt
git log --oneline -1
git show --stat --follow HEAD -- HainanMaJhong/src/main/java/hainanjong/engine/RoomManager.java   # 验证历史不断
```

- [ ] 提交成功（`git log` 能看到）
- [ ] `git show --stat --follow` 显示 `RoomManager.java` 的历史可追溯（rename 被识别）
- [ ] 合并回 main（实测通过后再合）：
```powershell
git checkout main
git merge --no-ff refactor/arch-stage1
git push origin main
```

---

# Part C：回滚预案

| 情况 | 动作 |
| --- | --- |
| 还在本地、没 push | `git checkout main ; git branch -D refactor/arch-stage1` |
| 已 push 但线上没部署 | `git revert -m 1 <merge-commit>` 或 `git reset --hard arch-baseline-20260920 && git push -f origin main`（**public 仓库慎用 force push**） |
| 已部署且线上崩 | ① `systemctl stop mahjong`；② 把 `backup-arch\` 里的旧 jar 传回去；③ `systemctl start mahjong`；④ 确认 `/`、`/game.html` 200 |
| 编译过但运行时 404（端点没了） | 90% 是 R1：`WebSocketConfig` 没被扫到。查它的 `package` 是不是 `hainanjong.config`（必须在 `hainanjong` 下）；查启动日志有没有 `webSocketConfig` bean |

**回滚演练（建议在动手前的空档先做一次）**：把当前 jar 和备份 jar 互换启动一次，确认你真的会回滚。这 15 分钟能省掉事故发生时的 2 小时。

---

# Part D：本阶段**不做**的事（防止手痒）

| ⛔ 不要做 | 什么时候做 |
| --- | --- |
| 拆 `MultiPlayerRoomService`（它现在还在 service 包，但仍是 1635 行） | 阶段 2 |
| 新建 `common/Result`、把 Controller 返回值改掉 | 阶段 2/3 |
| 把 `MysqlService` 拆成两个 Repository | 阶段 2 |
| 把 `RedisService` 换 `@Repository`、把 `saveRoomState(String json)` 改成 `save(RoomSnapshot)` | 阶段 2 |
| 提取 `domain/RoomState`、`EngineStart`、`RoomSnapshot` | 阶段 2 |
| 改 `setAllowedOrigins("*")` / 加 token / 换 BCrypt | **阶段 4** |
| 搬 `HuLib` / `HuResult` / `FanType` | 最后（主文档 R3） |
| 改 `pom.xml` 的 `java.version=11` → 17 | 单独一个 commit（别混进搬包） |
| bump 前端 `?v=20260908c` | 阶段 3 改前端时 |
| 删 `game_log.txt` / `out_compile/` | 阶段 5 |

> **判断标准**：阶段 1 的 diff 里，**除了 `package`/`import`/类名，不应该出现任何其他行**。如果 `git diff` 里出现了 `if (...)`、`new ...`、字符串常量、注解参数的改动 —— 你越界了，撤掉。

---

# 附：阶段 1 完成判据（一张表总览）

| # | 判据 | 命令/方式 |
| --- | --- | --- |
| 1 | 38 文件 / 6679 行不变 | B8-6 |
| 2 | 每个文件 package 与目录一致 | B3 校验脚本输出为空 |
| 3 | `mvn -B clean package` 成功 | B7-a |
| 4 | 11 个 bean 全部注册，建表日志出现 | B7-b |
| 5 | 7 个 HTTP 路径全 200 | B8-1 |
| 6 | WS 消息类型 + 字段名零差异 | B8-3 |
| 7 | Redis 快照字段名零差异 + 重启能重建 | B8-4 |
| 8 | 前端 `?v=` 未变（证明没动前端） | B8-1 |
| 9 | `git diff` 只有 package/import/类名 | B9 |
| 10 | `git log --follow` 能追到搬包前的历史 | B9 |

---

*执行中的每个意外（报错、与清单不符之处）都记到主文档 §10 回填区，别只留在脑子里 —— 那份表就是下次接手时的"踩坑地图"。*
