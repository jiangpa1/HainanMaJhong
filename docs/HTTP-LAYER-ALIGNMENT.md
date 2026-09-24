# 后端 HTTP 层规范化改造文档

> **编写时间**：2026-09-20（基于**实测**的当前代码）
> **适用仓库**：`F:\HainanMaJhong2`，分支 `refactor/arch-stage1`
> **前提**：**前端已决定弃用/重写** → 因此不需要任何兼容层，可直接把响应形状改到规范口径。
> **基准**：`Learning` 项目的 `common/Result` + `exception` + `dto/@Valid` + `@RequestAttribute("userId")` 范式
> **配套**：`docs/ARCH-REFACTOR.md`（分层设计）、`docs/CODE-READING-GUIDE.md`（代码导读）

---

## 0. 实测现状

| 项 | 状态 | 证据 |
| --- | --- | --- |
| `common/Result.java` | ✅ 已建，与 Learning **逐字一致**（7 个工厂方法） | `common/Result.java:1-72` |
| `pom.xml` Lombok | ✅ 已加（版本由 `spring-boot-starter-parent:2.7.18` 管理） | `pom.xml` |
| 四个 Controller | ❌ 全部返回 `Map<String,Object>` 手拼结构 | 见 §0.1 |
| `Result` 使用情况 | ❌ **0 处** | `grep` |
| `BusinessException` / `GlobalExceptionHandler` | ❌ 不存在 | — |
| DTO + `@Valid` | ❌ 不存在（`@RequestBody Map<String,String>` 手取） | `AuthController:30,44,60,78` |
| 鉴权 | ❌ 不存在（`userId` 由请求方明文传） | `AuthController:61,79`、`RoomController:26`、`RecordsController:39,69,83` |

### 0.1 待改造的接口清单（4 个 Controller / 8 个端点）

| Controller | 端点 | 现状返回 | `userId` 来源 |
| --- | --- | --- | --- |
| `AuthController` | `POST /api/register` | `Map` | — |
| | `POST /api/login` | `Map` | — |
| | `POST /api/user/nickname` | `Map` | **请求体**（`:61`） |
| | `POST /api/user/password` | `Map` | **请求体**（`:79`） |
| `RoomController` | `GET /api/room/pending` | `Map` | **query 参数**（`:26`） |
| `RecordsController` | `GET /api/records` | `Map` | **query 参数**（`:39`） |
| | `GET /api/records/byRoom` | `Map` | query（`:69`） |
| | `GET /api/records/{sessionId}` | `Map` | query（`:83`） |
| `AppDownloadController` | `GET /download/app` | `ResponseEntity<byte[]>` | — ★ **例外，不参与改造** |

---

## 1. 目标口径（与 Learning 完全一致）

| 维度 | 规范 | 说明 |
| --- | --- | --- |
| 响应体 | `{code, message, data}` | `Result<T>`，7 个工厂方法 |
| HTTP 状态码 | **恒 200** | 业务码只出现在 body 的 `code`；`400/401/403/404/429/500` |
| 错误产生方式 | Service/Controller **抛 `BusinessException`** | 不在 Controller 里手拼错误结构 |
| 错误翻译 | `@RestControllerAdvice` 集中处理 | 业务异常、参数校验、报文解析、兜底 |
| 入参 | `dto/` + `@Valid` 约束注解 | 不再 `@RequestBody Map<String,String>` |
| 身份 | `@RequestAttribute("userId")` | **不再从请求体/query 取 `userId`** |
| Controller 职责 | 只做「收参数 → 调 Service → 包 `Result`」 | 不含业务判断、不碰 SQL |

### 1.1 目标范例（照这个写）

```java
// controller/AuthController.java —— 目标形态
@RestController
@RequestMapping("/api")
public class AuthController {

    private final UserService userService;                 // ← 抽出的 Service（见 §2.3）

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public Result<?> register(@Valid @RequestBody RegisterDTO dto) {
        return Result.success(userService.register(dto));
    }

    @PostMapping("/login")
    public Result<?> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success(userService.login(dto));      // 返回 LoginVO{userId,nickname,(token)}
    }

    @PostMapping("/user/nickname")
    public Result<?> changeNickname(@Valid @RequestBody UpdateNicknameDTO dto,
                                    @RequestAttribute("userId") Long userId) {   // ★ 身份来自 token
        userService.updateNickname(userId, dto.getNickname());
        return Result.success();
    }

    @PostMapping("/user/password")
    public Result<?> changePassword(@Valid @RequestBody UpdatePasswordDTO dto,
                                    @RequestAttribute("userId") Long userId) {
        userService.changePassword(userId, dto.getOldPassword(), dto.getNewPassword());
        return Result.success();
    }
}
```

**对照现状**：`AuthController` 现在自己算 `sha256`、自己查库、自己拼 `Map`、自己写 `if` 校验 —— 这四件事**全部要移出 Controller**。

---

## 2. 分四个工作域改造（建议按此顺序，每域独立提交）

> 顺序原则：**先改"形状"（低风险机械改动），再改"身份"（有安全语义）**。
> 因为 §2.4 会把 `userId` 来源从"请求参数"换成"token 属性"，接口契约会再变一次 —— 所以**先把 DTO/异常/响应定下来，再动身份**。

### 2.1 域一：统一响应形状（`Result` 落地）

| 步骤 | 文件 | 动作 |
| --- | --- | --- |
| 1.1 | `controller/AuthController.java` | 4 个方法返回 `Result<?>`；删除 `ok/okWithUser/fail` 三个私有方法 |
| 1.2 | `controller/RoomController.java` | `pending` 返回 `Result<Map<String,Object>>`，把现有 Map 塞进 `data` |
| 1.3 | `controller/RecordsController.java` | `list`/`byRoom`/`detail` 返回 `Result<?>`，把 `{records}` / `{rounds,overview,notFound}` 塞进 `data` |
| 1.4 | `AppDownloadController.java` | **不动**（二进制流） |

**验收**
```powershell
& curl.exe -s --noproxy '*' -X POST 'http://localhost:8080/api/login' -H 'Content-Type: application/json' --data-binary "@login.json"
# 期望: {"code":200,"message":"操作成功","data":{"userId":1,"nickname":"…"}}
& curl.exe -s --noproxy '*' 'http://localhost:8080/api/room/pending?userId=1'
# 期望: {"code":200,...,"data":{"exists":false}}
```
- [ ] 所有 JSON 接口响应都是 `{code,message,data}` 三字段
- [ ] `AppDownloadController` 仍返回 APK 字节流

### 2.2 域二：全局异常处理

| 步骤 | 新增 | 说明 |
| --- | --- | --- |
| 2.1 | `exception/BusinessException.java` | 抄 Learning：`@Getter` + `code`/`message`，默认 `400` |
| 2.2 | `exception/GlobalExceptionHandler.java` | `@RestControllerAdvice`，至少 5 个分支 |

**必须覆盖的 5 类**（前 4 类都是"把非标准响应拉回项目口径"）：

| 异常 | 返回 | 为什么必须有 |
| --- | --- | --- |
| `BusinessException` | 原样透传 `e.getCode()` | 主路径：业务规则拒绝 |
| `MethodArgumentNotValidException` | `400` + 第一条字段错误 | `@RequestBody` + `@Valid` 失败 |
| `BindException` | `400` + 第一条字段错误 | `@ModelAttribute`/query 校验失败（与上者是**父子关系但不同异常**） |
| `HttpMessageNotReadableException` | `400 请求体格式错误` | JSON 语法错误、字段类型不匹配 |
| `Exception`（兜底） | `500 服务器开小差了` | 未预期异常；**日志用 `error`**，其余用 `warn` |

**验收**
```powershell
# 空 body → 应得 {"code":400,...} 且 HTTP 200，而不是 Spring 默认错误 JSON
& curl.exe -s -i --noproxy '*' -X POST 'http://localhost:8080/api/register' -H 'Content-Type: application/json' -d '{}'
# 坏 JSON → 400「请求体格式错误」
& curl.exe -s --noproxy '*' -X POST 'http://localhost:8080/api/register' -H 'Content-Type: application/json' -d '{oops'
```
- [ ] 上述两种输入都返回 `Result` 形状，HTTP 均为 200
- [ ] 日志级别正确（业务异常 `warn`、兜底 `error`）

### 2.3 域三：入参与校验（`dto` + `@Valid`）+ 抽 Service

| 步骤 | 新增 `dto/` | 约束 |
| --- | --- | --- |
| 3.1 | `LoginDTO` | `@NotBlank username`、`@NotBlank password` |
| 3.2 | `RegisterDTO` | 同上 + `@Size(min=6,max=20) password`、`@Size(max=16) username` |
| 3.3 | `UpdateNicknameDTO` | `@NotBlank` + `@Size(max=16)` |
| 3.4 | `UpdatePasswordDTO` | `@NotBlank oldPassword`、`@Size(min=6) newPassword` |

**同时抽 Service**（Controller 不该碰 `MysqlService`/`sha256`）：

| 新增 | 职责 | 从哪来 |
| --- | --- | --- |
| `service/UserService` + `impl/UserServiceImpl` | `register` / `login` / `updateNickname` / `changePassword` | `AuthController:29-97` 的业务逻辑整体搬过去 |
| `vo/LoginVO` | `{userId, nickname}`（+ 之后的 token） | 替代现在的 `okWithUser` 手拼 Map |

**验收**
- [ ] `grep -n "sha256\|MysqlService" controller/` → **0 命中**
- [ ] `grep -n "Map<String, String> body" controller/` → **0 命中**
- [ ] 用户名超 16 字 / 密码不足 6 位 → `{"code":400,"message":"…"}`

### 2.4 域四：身份与鉴权（★ 顺带修掉一个安全洞）

**为什么必须做**：现在 `changeNickname`/`changePassword` 接收**请求体里的 `userId`**（`:61,79`），`records`/`pending` 接收**query 里的 `userId`** —— 也就是说**知道一个数字就能改别人的昵称、看别人的战绩**。前端既然要重搭，这是唯一不用考虑兼容的时机。

| 步骤 | 新增/改动 | 说明 |
| --- | --- | --- |
| 4.1 | `utils/JwtUtils`、`service/TokenService` | 登录签发 token；参考 Learning 的 `JwtUtils`（HS256，secret 走环境变量） |
| 4.2 | `interceptor/JwtInterceptor` | 校验 `Authorization: Bearer …`，把 `userId`/`username` 挂到 `request.setAttribute(...)` |
| 4.3 | `config/WebMvcConfig` | 注册拦截器；放行 `/api/register`、`/api/login`、`/download/app`、静态资源 |
| 4.4 | `config/SecurityConfig` | `BCryptPasswordEncoder` Bean |
| 4.5 | `AuthController` / `RoomController` / `RecordsController` | `userId` 一律改 `@RequestAttribute("userId")`；删掉请求体/query 里的 `userId` |
| 4.6 | `WebSocketConfig` | `/game` 握手校验 token + 座位归属（**本次可留到下一轮**，见 §2.5） |
| 4.7 | **密码迁移** | ⚠️ 见下方"必须处理" |

> ⚠️ **必须处理的迁移问题**：`user.password` 列现在存的是 **无盐 SHA-256**（`AuthController:111-123`）。
> 改成 BCrypt 后**老密码全部无法登录**。三种选择：
>
> | 方案 | 做法 | 适用 |
> | --- | --- | --- |
> | **A（推荐，成本最低）** | 登录时判断哈希前缀：`$2a$` 开头 → BCrypt 校验；否则按 SHA-256 校验，**成功则立刻原地升级为 BCrypt** 并 `UPDATE` | 老用户无感迁移，代码约 10 行 |
> | B | 全量重置密码（发临时密码/公告） | 用户少、能接受打扰 |
> | C | 只对新注册用户用 BCrypt | ❌ 不推荐：两套并存，长期更乱 |

**验收**
```powershell
# 1) 不带 token 调受保护接口 → 401
& curl.exe -s --noproxy '*' -X POST 'http://localhost:8080/api/user/nickname' -H 'Content-Type: application/json' -d '{"nickname":"x"}'
# 期望: {"code":401,"message":"未登录，请先登录"}
# 2) 用 A 的 token 改 B 的昵称 → 不可能（请求体里已无 userId 字段）
# 3) 登录取 token → 带 token 改昵称 → 成功
```
- [ ] 受保护接口无 token → `401`
- [ ] `grep -rn "userId" controller/` 只剩 `@RequestAttribute("userId")`
- [ ] 老账号（SHA-256 哈希）仍能登录，且登录后库里的哈希已变成 `$2a$…`
- [ ] 库里找不到明文密码

### 2.5 暂不纳入本轮（避免一次动太多）

| 项 | 为什么放后面 |
| --- | --- |
| WebSocket 握手鉴权（`/game?userId=&code=&seat=`） | 与前端重搭强耦合（连接串要带 token），建议**前端重搭时一起做**；在 backlog 里记着 |
| 接口限流（`@RateLimit` + Lua） | Learning 有，本项目**登录接口尤其需要**；可作为独立一轮 |
| Knife4j 在线文档 | 与"前端重搭"独立，可稍后 |

---

## 3. 风险与坑

| # | 风险 | 规避 |
| --- | --- | --- |
| R1 | **前端此刻完全不可用**（有意接受的代价） | 每改一域都用 `curl` 验收；**不要**中途跑去改前端 |
| R2 | `@Valid` 不生效 | 本项目**已有** `spring-boot-starter-validation`；注意是 `javax.validation`（Spring Boot 2.7 用 javax，**不是 jakarta**） |
| R3 | `BindException` 与 `MethodArgumentNotValidException` 混用 | 两者是父子关系：`@RequestBody` 抛子类，`@ModelAttribute`/query 抛父类。**两个 handler 都要写**，否则 query 参数越界会掉到兜底 500 |
| R4 | BCrypt 迁移遗漏 → 老用户登不上 | 必须实现 §2.4 方案 A；上线前用**一个老账号**实测 |
| R5 | JWT secret 硬编码 | 走环境变量（`JWT_SECRET`），与既有的 `DB_*`/`REDIS_*` 同口径；**绝不入库** |
| R6 | Lombok 作用域 | 现在没写 `scope`，会被打进 jar（约 +100KB）。可接受；想干净就加 `<scope>provided</scope>` |
| R7 | `AppDownloadController` 被误改 | `ResponseEntity<byte[]>` 包成 `Result` 会损坏 APK |
| R8 | `records.byRoom` 的 `notFound` 语义 | 它是**业务字段**（该房间没有战绩），要放进 `data`，**不要**变成 `code=404` |
| R9 | 与未提交改动混在一起 | 工作区现有 5 处未提交改动（见 §4）——**先提交它们再开始** |

---

## 4. 动手前必须先处理的事

| 文件 | 内容 | 处理 |
| --- | --- | --- |
| `engine/model/RoundResult.java` | 换行（纯格式） | ✅ **已验证**：字节码与改动前**完全相同**（4173B） |
| `rules/HainanScore.java` | 删 `e2`（纯重构） | ✅ **已验证**：387 行确定性输出 + SHA256 与改动前**逐字节一致** |
| `rules/RuleEnv.java` | 注释修正（补/明杠） | ✅ **已验证**：字节码完全相同（2843B） |
| `common/Result.java` | 新增 | 随域一提交 |
| `pom.xml` | 加 Lombok | 同上 |
| `docs/CODE-READING-GUIDE.md` | 未跟踪 | **建议提交**，别丢 |

> 建议：先把"已验证的三个纯重构"单独提交（一条 commit），再开始本改造。

---

## 5. 总验收清单

### 形状与错误
- [ ] 所有 JSON 接口返回 `{code,message,data}`；HTTP **恒 200**
- [ ] `GlobalExceptionHandler` 覆盖 5 类异常
- [ ] 非法入参 → `400`，坏 JSON → `400`，未预期 → `500`（均带 `code`）

### 分层纪律
- [ ] `controller/` 里 **0** 处 `sha256`、**0** 处 `MysqlService`、**0** 处 `Map<String,String> body`
- [ ] Controller 方法体 ≤ 3 行（收参数 → 调 Service → 包 Result）
- [ ] 业务逻辑在 `service/UserServiceImpl`；SQL 在 `MysqlService`

### 身份
- [ ] 受保护接口无 token → `401`
- [ ] 请求体/query 里**不再出现 `userId`**（除登录返回体）
- [ ] 老账号可登录且哈希已升级为 BCrypt
- [ ] `JWT_SECRET` 来自环境变量

### 例外与不变量
- [ ] `/download/app` 仍返回 APK
- [ ] `byRoom` 的 `notFound` 仍在 `data` 里

---

## 6. 自检问题

1. 为什么前端弃用后，"兼容字段"方案就该删掉？（不需要为不存在的调用方保留旧形状，分阶段反而增加中间态）
2. 为什么 `BindException` 和 `MethodArgumentNotValidException` 都要写 handler？（不同注解触发不同异常，漏一个会让 query 校验失败掉到兜底 500）
3. `userId` 从请求体改到 `@RequestAttribute` 后，**接口契约**变了吗？（变了：请求体少一个字段、多一个 `Authorization` 头 —— 所以前端必须重搭，这正是本轮的前提）
4. 为什么密码迁移要用"登录时原地升级"而不是"启动时批量转换"？（批量转换需要**明文**，而库里只有哈希 —— 只有登录那一刻才拿得到明文）
5. 为什么 WebSocket 握手鉴权不放在本轮？（它要改连接串，与前端重搭强耦合；先让 HTTP 侧定稿，两次改动不互相干扰）
