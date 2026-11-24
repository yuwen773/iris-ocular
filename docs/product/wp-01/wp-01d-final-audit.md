# WP-01D 最终集成审计、并发故障验证与交付收口

> 状态：**本地验收候选**——未提交、未推送、未合并、未部署
> 基线：`master` @ `5dc2eac5` + WP-01A/B/C 全部未提交改动；迁移头 **V33**
> 工作树：`t3/wp-00-5-baseline-audit`
> 本轮定位：独立审计 + 必要的最小整改 + 验收，不开发新业务模块
> **本报告的全部结论只对上述基线成立**：`master` 已前进到 `c90b0caa`，且 V32–V34
> 已被其它迁移占用，合入前必须重新编号并复跑迁移门禁（§8.4）。

---

## 1. 结论

| 项 | 结果 |
|---|---|
| 阶段 1 源码审计 | 完成；**未发现 P0**；P1 4 条（3 条已最小整改，1 条留裁决）、P2 12 条 |
| 阶段 2 多实例与故障注入 | 见 §4：**两个独立 JVM** 连接同一 MySQL 8 的 5 类竞争 + 强杀 + 锁等待超时，全部通过 |
| 阶段 3 契约裁决 | 确认「批准即发布」；PRD / ADR / API 契约 / 验收文档已**追加**结论，历史记录未改 |
| 阶段 4 最终验收 | 见 §6：L3 复跑与 V31→V33 结果、反向代理与会话安全复核（部分**待验证**） |
| 合并准入判断 | **GO**（§8，**对审计基线 `5dc2eac5` 成立**）：owner 2026-11-23 裁决 C-3「A-04 接受为 R-1」、C-5「接受 pass 1+2 全绿 + pass 3 独立归因」。**当前 master `c90b0caa` 上执行前须先完成 §8.4**（V32–V34 已被占，控制中心迁移要重新编号并复跑）；部署启用仍需另一轮 |

---

## 2. 审计方法

三样东西分开看，避免「测试绿灯 ⇒ 没问题」：

1. **读实现**：V32/V33 全部 DDL、六个 Mapper 的 SQL、三个服务、事务体、过滤器链、权限切面；
2. **读契约**：PRD / ADR（D1–D12）/ API 契约 / WP-01B/C 的设计与交付记录，逐条对实现；
3. **跑真实环境**：真 MySQL 8 上的并发竞争、跨进程竞争、崩溃与锁等待；不靠阅读推断并发性质。

判定口径：**P0** = 会写坏数据、发布错误配置或丢审计；**P1** = 正确性有缺口或失败语义不可用，
合并前应处理；**P2** = 契约/文档漂移、可运维性、性能与部署前提。

---

## 3. 阶段 1：审计发现

### 3.1 P0

**未发现 P0。** 支撑这个判断的不是「测试都绿了」，而是下列不变量逐条对上了实现：

| 不变量 | 实现位置 | 复核结论 |
|---|---|---|
| 发布/回滚全局串行 | `ConfigActivePointerMapper#selectForUpdate`（`t_config_active_pointer` id=1 行锁） | 三条写路径（批准 / 回滚 / 拒绝不涉及指针）都在同一把锁下；锁内取 `MAX+1`，唯一键 `uk_config_release_version` 是第二道闸 |
| 同一审批单只能被决定一次 | `ConfigPublishRequestMapper#selectForUpdate` + 条件 `decide(... WHERE status='PENDING')` | 锁 + 条件更新双保险；并发决定的后到者读到的已是终态 → 40909 |
| 失败不留半发布 | `@Transactional` 覆盖幂等占位 / release / 指针 / 审批 / 成功审计 | 任何 RuntimeException 都让整条链回滚；强杀进程由 MySQL 回滚未提交事务（§4.3 用例 6 实测） |
| 历史不可变 | `ConfigReleaseMapper` 只有 insert / select | 全仓无 `UPDATE t_config_release` |
| 幂等不被绕过 | `t_config_request` UNIQUE `(actorId, requestId)` + 事务内插入先行 | ⚠ REPEATABLE READ 下的重放语义见 §3.2，结论是**正确** |
| 审计不丢成功记录 | 成功审计与发布同事务；失败/拒绝审计 REQUIRES_NEW | 审计写失败即整条发布回滚（WP-01C 已注入验证） |
| 未启用不可达 | 控制器 / CSRF 控制器 / CSRF 过滤器 `@ConditionalOnProperty(enabled=true)`；严格 Origin 分支同开关 | `enabled` 默认 `false`；发布人白名单默认空 |

### 3.2 逐条复核：幂等在 REPEATABLE READ 下的重放语义（用户点名项）

**结论：正确，但它依赖一个容易被改坏的前提。**

- 占位插入与结果回填**同事务**（`ControlCenterIdempotencyService:79` 插入、`:101` 回填），
  因此「已提交的行」必然带结果；未提交的行对别的会话不可见。
- 撞唯一键后走**当前读** `SELECT … FOR UPDATE`（`ConfigRequestMapper:25`），
  而不是快照读：REPEATABLE READ 下快照读可能看不到对方刚提交的行，当前读一定看得到。
- 因此三种时序都正确：
  1. **对方已提交** → 本事务插入撞唯一键 → 当前读拿到已提交行 → 重放（或摘要不一致 `40908`）；
  2. **对方未提交** → 本事务插入**阻塞**在唯一键上（受 `innodb_lock_wait_timeout` 约束），
     对方提交则按上一条处理，对方回滚则本事务插入**成功**、正常执行自己的业务；
  3. **行在「插入失败 → 当前读」之间消失** → `replayExisting` 读到 null → `40900`
     （`ControlCenterIdempotencyService:126-128`）。生产里没有任何代码删除
     `t_config_request` 行，所以这是防御分支；`40900` 在此处与「版本冲突」共用一个码，
     已登记为 A-16。

⚠ 前提：**先插入、后校验**的顺序不能反过来。若把 `reserve` 挪到业务读之后，
快照读会把「对方刚提交」判成不存在。`ControlCenterIdempotencyServiceTest` 与
`ControlCenterPersistenceIntegrationTest` 锁住了这个顺序。

### 3.3 发现清单

| ID | 严重度 | 位置 | 触发条件 | 影响 | 处置 |
|---|---|---|---|---|---|
| **A-01** | **P1** | `ControlCenterReleaseTransactions:179/193/250/285`（`FOR UPDATE`）；`GlobalExceptionHandler:36`（兜底 `Exception`） | 两个实例争同一审批行或指针行，等待超过 `innodb_lock_wait_timeout`（默认 50s），或 InnoDB 判定死锁 | 客户端收到 `50000 系统错误`。事务其实已完整回滚、原样重试就正确，但接口没有可重试的码，前端只能报「服务坏了」 | **已修**：新增 `40906 CONFIG_CONCURRENT_CONFLICT` + `ControlCenterExceptionHandler`（只作用于两个控制中心控制器，`HIGHEST_PRECEDENCE`）；`ControlCenterExceptionHandlerTest` 3 例；跨进程证据见 §4.7 |
| **A-02** | **P1** | `ControlCenterIdempotencyService:70/91/113`（校验点）、`:79`（插入）；`V32:60` `VARCHAR(64)` | 客户端传超过 64 字符的 `requestId` | MySQL 严格模式抛 1406 → 落兜底 → `50000`；调用方以为服务故障 | **已修**：`validateRequestId` 在写库前按码点限长 → `40000`；5 个单测（含上限、超限、emoji 码点计法） |
| **A-03** | **P1** | `ControlCenterIdempotencyService:63-70`；`web/src/lib/controlCenterApi.ts` 草稿三方法 | ① 同一个 `requestId` 先「申请发布」再「批准」；② 草稿写操作不传 `requestId` | ① `40908 同一请求号的内容不一致`——读起来像服务端 bug，其实是客户端用错了号；② 幂等被静默关闭，网络重试变成第二次写入（推高 revision、作废校验） | **已修**：前端为草稿写操作默认生成 `requestId`，并在模块文档写明「每个动作必须是不同的号」；新增 2 个 vitest 用例 |
| **A-04** | **P1（待裁决）** | `ControlCenterReleaseTransactions:198-202` | 两张 PENDING 审批单绑定同一 `expectedActiveReleaseId`，先批一张 | 另一张**永远**批准不了（每次都 40900），也不会自动变 STALE，只能人工拒绝。审批列表会累积「看着可点、点不动」的僵尸单 | **未改**：把它标成 STALE 会改变 WP-01C 已接受的状态机语义（40900 故意保持 PENDING，避免谎称「内容失效」）。列为 WP-01E 候选，见 §9 |
| A-05 | P2 | `ControlCenterIdempotencyService:126-131` | 同一 `requestId` 并发提交 | 注释里的「处理中 → 40900」分支**不可达**：已提交行必有结果，未提交行不可见。真实行为是阻塞等待（最长 50s），HTTP 线程被占住 | 记录契约（§5.3）；不改为轮询式「处理中」，MVP 不需要 |
| A-06 | P2 | `ControlCenterReleaseTransactions:349-359` | 批准时发现绑定已失效 | `markStale` 的 STALE 更新（REQUIRES_NEW）与它的失败审计（也是 REQUIRES_NEW）分两次提交 → 极端情况下「有 FAILED 审计、审批单仍 PENDING」。方向是安全的（不会出现「已 STALE 但无审计」） | 记录；不改（改成同事务会与外层行锁互等，正是 WP-01C §4.4 的结论） |
| A-07 | P2 | `ControlCenterDraftService:93-94`、`:131-132` | 两次并发草稿写，后者的 `get()` 读到前者 | 幂等记录里存的是「重读到的当前草稿」而非本次写入的版本；重放时返回的 `revision` 可能不是本次请求写入的那一版 | 记录；影响限于重放载荷的可读性，不改变数据 |
| A-08 | P2 | `V32:62`（`action` 列注释只列了 `DRAFT_*`） | 任何阅读 DDL 的人 | 注释与实际写入的 `PUBLISH_REQUEST/PUBLISH_APPROVE/PUBLISH_REJECT/ROLLBACK` 不符 | **不修改 V32**（已应用迁移不改 checksum）；在迁移矩阵注明 |
| A-09 | P2 | `ControlCenterIdempotencyService:101` | — | `resultCode` 永远写 0（失败时整行回滚），列注释暗示的「非 0 = 失败」不会发生 | 记录；语义（失败不消耗号）是对的，注释是历史残留 |
| A-10 | P2 | `ControlCenterSecurityAudit:44`（写审计）与类注释 | 匿名请求被 Origin/CSRF 拒绝 | 原注释「匿名请求不落库」与实现不符：**每个被拒请求都会写一条 `actorId=NULL` 的审计行**，无速率限制 | **已更正注释**并写进风险清单（保留期/限流待运维决策） |
| A-11 | P2（待验证） | `application.yml:7-12`；`SameOriginFilter:184-193` | 部署到反向代理之后 | ① 会话 Cookie 未设 `Secure`（注释说「生产 HTTPS 再加」，生产域名是 HTTPS）；② 严格 Origin 只信任 `X-Forwarded-Proto`，**不信任 `X-Forwarded-Host`**——反代若把 Host 改写成内网地址，所有写请求都会 403 | 本轮无真实反代环境 → **标为待验证**；部署检查项见 §7 |
| A-12 | P2 | `CsrfTokenService`、`application.yml`（cookie max-age 30 天） | — | Token 会话内不轮换、会话 30 天；XSS 一旦发生，攻击面持续整个会话期 | 接受（会话绑定 token 已是会话级凭据，轮换不改变根本暴露面） |
| A-13 | P2 | `ConfigReleaseMapper:28`、`ConfigPublishRequestMapper:44-48` | 发布/审批列表分页 | 每页一次全表 `COUNT(*)`；带 `status` 过滤时 `ORDER BY requestedTime DESC, id DESC` 仍需 filesort（索引只覆盖到 `requestedTime`） | 接受（MVP 数据量）；上量后加计数缓存/覆盖索引 |
| A-14 | P2 | — | — | 无「发布后自动回滚」熔断；releases / requests / audit 三张表无归档保留策略 | 沿用 WP-01C R-5/R-6，明确属运维与后续 WP |
| A-15 | P2 | `ControlCenterReleaseTransactions:214-219` | 「持锁读到的期望值在锁内被改」 | 指针条件更新 0 行的分支在正常路径不可达（行锁已串行化），仍无注入用例 | WP-01C 已如实登记；本轮保留该状态 |
| A-16 | P2 | `ControlCenterIdempotencyService:126-128` | 幂等占位行在「插入撞键 → 当前读」之间被删除（生产无删除路径） | 防御分支抛 `40900 配置版本冲突，请刷新后重试`，与「期望活跃版本过期」共用一个码，但正确处置是「原样重试」而非「刷新」 | 记录；不加新码（分支生产不可达，加码会扩大契约面）。若将来引入归档删除，必须先改这里 |

### 3.4 迁移与表结构复核（用户点名项）

| 表 | 复核点 | 结论 |
|---|---|---|
| `t_config_draft`（V32） | 单例行 `id=1` 主键；`revision` 乐观锁；`updateWithRevision` 同时把状态打回 `DRAFT` 并清空校验 | ✓ 结构约束而非服务层自觉；编辑即作废校验在 SQL 里 |
| `t_config_release`（V32） | 只插入；`uk_config_release_version` 唯一 | ✓ 全仓无更新路径；序列号唯一键兜底 |
| `t_config_active_pointer`（V32） | 单例行 + 迁移内预置；`<=>` NULL 安全条件更新 | ✓ 首次发布（NULL → 首条）可用 |
| `t_config_request`（V32） | `UNIQUE(actorId, requestId)`；`requestId` 用 `utf8mb4_bin` | ✓ 大小写不同不会误判同一请求；`collation` 选择有理由 |
| `t_config_audit_event`（V32） | `eventId` 唯一（UUID）；`occurredAt DATETIME(3)` + 时间索引 | ✓ 只追加 |
| `t_config_publish_request`（V33） | `UNIQUE(actorId, requestId)` 二道闸；`idx(status, requestedTime)`；`idx(draftRevision)` | ✓ 与幂等表互不替代；状态列 `VARCHAR(16)` 容得下四个终态 |
| 迁移兼容 | V31 库 → V33 只跑 V32/V33；旧 jar（认到 V17）在 V33 history 上可解析 | ✓ 见 §6.3（本轮在**隔离的一次性 MySQL 8** 上复核） |
| 命名口径 | 全部小驼峰列名 + `map-underscore-to-camel-case: false` | ✓ 与既有库一致 |

---

## 4. 阶段 2：多实例与故障注入

### 4.1 为什么不用「一个 JVM 两个线程」

WP-01C 的并发证据是「一个 JVM、两个线程、两个独立事务」——那能证明行锁语义，
但证明不了多实例部署真正会遇到的：**各自的连接池、各自的会话、各自的事务管理器**，
以及**进程被强杀时数据库怎么收拾残局**。所以本阶段的每一项都拉起**独立的 Java 进程**
（完整 Spring 上下文 + 真实 Hikari 连接池 + 生产代码路径），连同一个 MySQL 8。

实验装置两个类，都在 `src/test/java/.../controlcenter/`：

| 类 | 作用 |
|---|---|
| `ControlCenterProcessHarness` | 子进程入口：`SpringApplication` 起完整应用（`--server.port=0`，`--spring.flyway.enabled=false`），执行一个命令后向 stdout 打一行 `HARNESS_RESULT {json}`；退出码 0/3/4/5/1 区分成功/业务错误/锁超时/死锁/其他 |
| `ControlCenterMultiProcessTest` | 父进程：准备数据、拉起子进程、用**起跑门**同步、收集结果、断言数据库终态 |

两个关键设计：

1. **起跑门**（`-Dharness.goFile`）：子进程启动完打印 `HARNESS_READY` 后停在门前；
   父进程等**两个都就绪**才创建门文件。没有这道门，两个子进程会先后脚跑完，
   测到的只是串行结果——那种「恰好一个成功」什么也证明不了。
2. **数据源显式传递**：父进程把**自己实际在用的** `HikariDataSource` 的 URL/账号/口令
   以 `--spring.datasource.*` 形式传给子进程，而不是靠环境变量继承。否则父进程可能由
   profile 决定了另一个库，靠继承会悄悄连到别处——「同一个数据库」这个前提就假了。

**环境约束（写在代码里，不是口头约定）**：`@BeforeEach` 先 `assumeTrue(isTestSchema())`，
数据源 URL 不含 `ci`/`test` 就整类**显式跳过**（跳过会出现在报告里，不会伪装成通过）。
本轮只连 `scripts/verify-fast.sh --db` 起的一次性 MySQL 8（随机宿主端口、一次性 schema），
不连生产库、不连真实模型服务、不含任何患者数据。

### 4.2 用例与要证明的性质

| # | 用例 | 要证明的性质 |
|---|---|---|
| 1 | `twoProcessesApprovingTheSameTicketProduceExactlyOneRelease` | 同一审批单被两个实例同时批准：只有一条 release、一张 APPROVED、一条成功审计，输家 `40909` |
| 2 | `twoProcessesWithTheSameRequestIdReplayTheSameRelease` | 同一 `requestId` 并发批准：两次都成功且**返回同一条 release**，只产生一条 release、一条审计 |
| 3 | `twoProcessesReusingOneRequestIdForDifferentContentAreRejected` | 同一 `requestId` 配**不同动作**（批准/拒绝）：只有一个成功，另一个 `40908`；胜者的结局自洽（批准 ⇒ 1 条 release + 指针切换；拒绝 ⇒ 0 条 release + 单据 REJECTED） |
| 4 | `twoApprovalsBoundToTheSameActiveVersionCannotBothPublishAcrossProcesses` | 两张审批单绑定同一个 `expectedActiveReleaseId`：只有一个发布成功，另一个 `40900` **且保持 PENDING** |
| 5 | `publishAndRollbackInTwoProcessesCannotBothWin` | 发布与回滚并发：只能一个生效，指针始终指向合法 release |
| 6 | `aKilledInstanceLeavesNoPartialPublishAndTheRequestIdStaysUsable` | 子进程在**未提交**事务里完成发布动作后停在门后，父进程 `destroyForcibly()` 强杀：不得留 release、不得切指针、审批单仍 PENDING、幂等占位随事务回滚；**同一 `requestId` 仍可用** |
| 7 | `aLockWaitTimeoutLeavesNoPartialPublish` | 父进程持住审批行锁，子进程以 `innodb_lock_wait_timeout=2` 批准：拿到锁等待超时（映射 `40906`），不留半发布，锁放开后同一 `requestId` 可用 |

用例 6 是「实例在发布中途被 kill -9」的真实证据：僵尸连接上的未提交事务由 MySQL
在检测到连接断开时回滚，不是模拟出来的。用例 7 证明「失败不消耗幂等号」——
这条如果不成立，客户端重试就会永久卡在 `40908`。

### 4.3 结果

| 用例 | 结果 | 耗时 |
|---|---|---|
| 1 同一审批单并发批准 | PASS | 13.0s |
| 2 同一 `requestId` 并发重放 | PASS | 15.9s |
| 3 同一 `requestId` 不同动作 | PASS | 21.4s |
| 4 同期望活跃版本竞争 | PASS | 16.8s |
| 5 发布 vs 回滚 | PASS | 18.1s |
| 6 进程强杀后的数据库一致性 | PASS | 11.8s |
| 7 锁等待超时 | PASS | 19.5s |

**合计 7 个用例 / 0 失败 / 0 跳过 / 145.1s**（`ControlCenterMultiProcessTest`，
`scripts/verify-fast.sh --java ControlCenterMultiProcessTest --db` 在一次性 MySQL 8 + Redis 上执行；
每轮每个用例 1–2 个子 JVM，日志落在 `target/multiprocess-logs/junit-*/`）。

用例 6 的关键断言（强杀后逐条查库）：release 数不变、活跃指针仍为 NULL、审批单仍 `PENDING`、
`t_config_request` 无残留行；随后**用同一个 `requestId` 正常批准成功**——证明崩溃既不会
留下半发布，也不会吞掉幂等号。用例 7 同样在锁放开后复用同一 `requestId` 成功。

> **诚实记录：本轮第一次运行是 6/7，红的是用例 3，但缺陷在验证代码而不在产品。**
> `ControlCenterProcessHarness` 的 `reject` 分支把 harness 自己的 `status` 字段
> **覆盖**成了单据终态（`payload.put("status", request.status())`），于是「拒绝成功」
> 在父进程眼里成了 `{"status":"REJECTED"}`，被计成 0 个成功。修复是把该字段改名为
> `approvalStatus`，并把用例 3 的断言改成对「胜者是批准还是拒绝」两种合法结局分别校验
> （批准：1 条 release + 指针切换 + 单据 APPROVED；拒绝：0 条 release + 指针不动 + 单据 REJECTED）。
> 修复后 7/7 通过。**这条记录同时说明用例 3 的价值**：它锁住「幂等摘要必须覆盖方法 + 路径」——
> 若摘要只覆盖请求体，批准与拒绝的 body 相同，后到者会被当成重放，拿到一个语义完全错误的结果。

---

## 5. 阶段 3：契约裁决

裁决以「读实现 → 对契约 → 只追加、不改历史」的方式落地。四处文档都只**追加**了新章节，
原有 PRD / ADR / API 契约 / 交付记录一字未改（历史读数保持为当时快照）。

### 5.1 当前流程是「批准即发布」——确认，并写死

`ControlCenterReleaseTransactions#approve`（`:150-235`）在**同一个事务**里完成：
占位幂等 → 锁审批行 → 重验绑定 → 锁活跃指针 → 版本比对 → `MAX+1` 插 release →
切指针 → 审批置 `APPROVED` → 回填幂等 → 写成功审计。没有任何「已批准待发布」的中间态，
也没有直发端点（`/drafts/{id}/publish` 只有申请，`/publish-requests/{id}/approve` 才生效）。

因此 **批准是不可逆动作**：审批单终态为 `APPROVED` 后无法撤回，撤销线上配置的唯一手段是
回滚（产生一条新 release，历史不可变）。这条已写进 PRD §11.1 第 1、2 条与 API 契约 §7。

### 5.2 状态机终态

`PENDING → APPROVED | REJECTED | STALE`，三者皆终态；对终态再决定一律 `40909`
（`decide(... WHERE status='PENDING')` 条件更新 0 行 → 40909）。
`STALE` 不是用户动作：只在「批准时发现草稿 revision / 载荷摘要 / 验证摘要已变」时由
`markStale` 写入（`ControlCenterReleaseTransactions:349-359`）。
**唯独 `40900`（活跃版本在申请之后变了）不改变状态**——审批单保持 `PENDING`，
这是 WP-01C 已接受的语义（不能谎称「内容失效」）。代价是可能出现「看着可点、点不动」的
僵尸审批单，已登记为 A-04，留待 WP-01E 裁决。

### 5.3 409xx 错误语义

| 码 | 触发点 | 事务状态 | 客户端动作 |
|---|---|---|---|
| `40000` | 参数错误：缺 `expectedRevision`、`requestId` 为空或超 64 码点 | 未进入业务写 | 修参数 |
| `40400` | 草稿不存在 / 审批单不存在 | 未进入业务写 | 先创建 / 刷新列表 |
| `40900` | 期望 revision ≠ 当前草稿（`ControlCenterDraftService:123/160`）、期望活跃版本 ≠ 当前（`ControlCenterReleaseTransactions:198`）、幂等行在「撞键→当前读」之间消失（A-16 防御分支） | 已回滚，`requestId` 未消耗 | 刷新后重做；审批单仍 `PENDING` |
| `40901` | 草稿未校验 | 同上 | 先校验 |
| `40902` | 校验结果已过期 | 同上 | 重新校验 |
| `40903` | 载荷 `schemaVersion` 不受支持 | 同上 | 升级客户端 |
| `40904` | 存在未注册的配置键 | 同上 | 修草稿 |
| `40905` | 键属于部署事实（不允许在线改） | 同上 | 从草稿移除 |
| **`40906`（本轮新增）** | 锁等待超时（MySQL 1205）/ 死锁（1213） | 同上 | **原样重试同一 `requestId`** |
| `40908` | 同一 `(actorId, requestId)` 的摘要不一致 | 同上 | 换新 `requestId` |
| `40909` | 审批单已是终态 | 同上 | 刷新审批列表 |
| `40910` | 审批绑定内容已变（STALE） | 同上 | 重新申请 |
| `40911` | 草稿含未实现的 Provider 键 | 同上 | 移除 `provider*` |
| `40301` | 非管理员，或 PUBLISH/ROLLBACK 不在发布人白名单 | 未进入业务写 | 找有权限的人 |

（`40900` 的三处触发点共用同一个码：前两处是「你手里的版本旧了」，第三处是防御分支，
正确动作其实是重试——已登记为 A-16，不改码。）

`40906` 的语义边界（ADR D13）：**可重试**，与 `40900` 的「必须换内容/刷新」正好相反。
把这两者混成一个码会让客户端在真正该重试时放弃、在该刷新时死循环。

### 5.4 发布人权限与「申请人 = 审批人」

- 权限分层不变：READ / EDIT / VALIDATE 给所有管理员；PUBLISH（批准 / 拒绝）与 ROLLBACK
  还要求落在部署期 `publisher-user-ids` 白名单内（**默认空 ⇒ 默认无人可发布**）。
- 提交发布申请只需 EDIT，因此任何管理员都能提单；只有白名单成员能批。
- **申请人可以是审批人**（MVP 边界，不变）：单发布人部署下强制分离会直接锁死发布能力。
  需要四眼时用「多人白名单 + 流程约定」，后续 WP 再加强制校验。这条已在 PRD §11.1 第 4 条
  明确登记为**已知边界**，不是缺陷。

### 5.5 落地的文档增量

| 文档 | 追加内容 |
|---|---|
| `docs/product/iris-control-center-1.0-mvp-prd.md` | `§11.1 WP-01D 契约裁决`：批准即发布、不可撤回、状态机、申请人=审批人、权限分层；并标注「尚未进入开发」已过期 |
| `docs/product/wp-00/config-center-adr.md` | `2026-11-23 WP-01D 裁决` 表（D2/D3/D4/D11/D12 追认 + D13 新增） |
| `docs/product/wp-00/mvp-api-contract.md` | `§7 WP-01D 增量`：40906、`requestId` 三条规则、批准即发布、按码的客户端处置表 |
| `docs/product/wp-01/wp-01c-publish-approval-delivery.md` | `§11 WP-01D 追认`：追认裁决/状态机/事务顺序/失败恢复，登记三条增量 |
| `docs/architecture/migration-matrix.md` | V32 列注释漂移说明（A-08），并说明为何不改已应用迁移 |

---

## 6. 阶段 4：最终验收

### 6.1 完整 L3 复跑

`scripts/verify.sh`（一次性 MySQL 8.0.36 + Redis 7.2.4，三遍：全量单测 → MySQL 门禁 →
隔离库协作套件），每轮原始日志原样保留：

| 轮次 | 日志 | pass 1 | pass 2 | pass 3 | 结论 |
|---|---|---|---|---|---|
| 第 1 轮·尝试 1（作废） | `target/wp01d-l3-round1-attempt1-aborted.log` | 中途 JVM 被杀（surefire 流 EOF，**无任何 `[ERROR]`**） | — | — | 环境资源耗尽，不计为结果 |
| **第 1 轮** | `target/wp01d-l3-round1.log` | 1004 例 / 0 失败 / 304 跳过（设计内） | 26 例 / 0 失败 | 303 例 / **1 失败** | 失败项为既有租约 flake（§6.4） |
| 第 2 轮·尝试 1（作废） | `target/wp01d-l3-round2-attempt1-aborted.log` | BUILD SUCCESS | 中途整条进程树被杀 | — | 同机另一个工作树（`iris-p1-fix`）同时在跑 verify，资源争抢；不计为结果 |
| 第 2 轮·尝试 2（作废） | `target/wp01d-l3-round2-attempt2-mysql-died.log` | BUILD SUCCESS | **MySQL 容器在 multiprocess 用例中途死亡**（`Communications link failure`；随后 Flyway / DiagnosisRun 全部连接失败） | — | 又一个 `eye-test-*` 栈在同时启动（Docker VM 共 7.9GB）；不计为结果 |
| **第 2 轮** | `target/wp01d-l3-round2.log` | 1004 例 / 0 失败 / 304 跳过 | 26 例 / 0 失败 | 303 例 / **2 失败** | `GraphExecutionTest` 两个参数化用例（`processDeathRestoresOnlyCommittedNodesAndPreservesBudgets`[4]、`safetyAndProviderFailuresMatchCoordinator`[7]）；与第 1 轮的失败集合**不相交** |
| 第 3 轮 | `target/wp01d-l3-round3.log` | 1004 例 / 0 失败 / 304 跳过 | 26 例 / 0 失败 | 303 例 / **3 失败** | 全部在 `GraphExecutionTest`（`processDeath…`[3]、`heartbeatRenewsLease…`、`blockedConnectionAcquisition…`）；**本轮启动时警告已触发**（另一 `eye-test-*` 栈在跑），属受干扰的一轮 |

> **本机无法同时跑两个 L3**：Docker Desktop VM 只有 7.9GB，两个 `verify.sh` 各自要
> MySQL + Redis + Maven + fork JVM。三次作废（第 1 轮 1 次、第 2 轮 2 次）都不是产品失败，
> 而是资源争抢——一次是 fork JVM 被杀（surefire 流 EOF），一次是整条进程树被杀，
> 一次是 MySQL 容器直接死掉（连接被拒）。因此正式结果只在空窗期采集；第 3 轮虽已触发
> 警告但仍受干扰，已在该行注明。

**第 1 轮尝试 1 的失败方式值得记录**：`TestSet has not finished before stream error` +
`java.io.EOFException`，日志在 `FullApplicationAcceptanceTest` 启动处戛然而止，
Maven 没有输出任何测试失败——只有把 dumpstream 打开才看得出是 forked JVM 被杀。
根因是本机内存被 Docker（MySQL/Redis/Chroma）、浏览器与默认堆（物理内存 1/4）同时占满
（当时是否还有别的 `verify.sh` 在跑没有逐一取证，故不把并发算作唯一原因）。处置：
`scripts/verify.sh` 给 forked 测试 JVM 显式加堆上限（`VERIFY_TEST_XMX`，默认 `1500m`，
保留 pom 的 `-Dfile.encoding=UTF-8`），并新增「本机已有 `eye-test-*` 栈」的启动警告。
这不是产品改动，也不改变任何测试语义。

### 6.2 V31 → V33

门禁：`FlywayMySqlIntegrationTest#upgradesAnExistingV31SchemaToTheControlCenterSchema`，
在 pass 2 的一次性 MySQL 8 上真跑（不是模拟）：新建独立 schema → 迁到 `target=31` →
再跑全量迁移。断言：

- 首次迁移数 = 总迁移数 − 2（只差 V32、V33）；
- 升级只执行 **2** 条，`current` 版本为 **33**；
- `t_config_active_pointer` 恰好一行 `(1, NULL)`；
- `t_config_publish_request` 存在且为空表。

结果：**6/6 通过 / 54.81s**（第 1 轮 pass 2）。同遍 `ControlCenterMultiProcessTest` 7/7 通过
（147.0s），证明升级后的库能直接承载跨进程发布。**不是 BLOCKED**：环境可用，已在受控的一次性库上完成。

### 6.3 反向代理、allowed-origins、Cookie 与 Session（**部分待验证**）

| 检查项 | 结论 | 依据 |
|---|---|---|
| 严格 Origin 分支不出现在非控制中心路径上 | ✓ 通过 | `SameOriginFilter` 严格分支由 `enabled` 决定；`SameOriginFilterTest` / `SameOriginFilterControlCenterTest` 覆盖「越权写」「空 Origin 拒绝」「Referer 不兜底」 |
| `allowed-origins` 只接受完整 `scheme://host[:port]`，不支持通配 | ✓ 通过 | `ControlCenterProperties:30-35` + `normalizedOrigin`（拒绝非 http/https、带 userinfo、带 path/query） |
| 反代保留 `Host` | **待验证（无真实反代环境）** | `requestOrigin` 用 `request.getServerName()`；若反代改写 Host 成内网地址，所有控制中心写请求都会 403。部署时必须 `proxy_set_header Host $host` |
| `X-Forwarded-Proto` | **待验证** | 只在 `trust-proxy-headers=true` 时读取；开启前必须确认反代会**覆写**（而非追加）该头，否则客户端可伪造 scheme 绕过 Origin 比较 |
| Session Cookie `Secure` | **未满足** | `application.yml:6-9` 只有 `max-age: 2592000` / `same-site: lax` / `http-only: true`，**没有 `secure: true`**。生产走 HTTPS，这一项属部署前必须补的配置 |
| Session 存储 | ✓ 有事实依据，待部署确认 | 生产 profile 用 Spring Session + Redis；`ci` profile `store-type: none`（因此测试不覆盖 Redis 序列化）。30 天会话 + 会话内不轮换的 CSRF token 见 A-12 |
| CSRF token 传输 | ✓ 通过 | `GET /api/admin/control-center/csrf` 带 `Cache-Control: no-store`；客户端每次写操作带 `X-CSRF-Token`，服务端常量时间比较 |

**没有任何一项可以写成「已在真实环境验证」**——本轮全部证据来自单元/集成测试与一次性 MySQL，
没有站在真实反向代理后面的实例。这一节的「待验证」不会因为 L3 全绿而变成已通过。

### 6.4 既有租约类 flaky 的单独记录

**第 1 轮 pass 3 唯一失败**：`DiagnosisTaskRecoveryTest.resultCannotCommitAfterLeaseExpiresWhileWaitingForRowLock`
（`expected: <PROCESSING> but was: <WAITING_REVIEW>`，1.9s 失败）。机制：测试把任务的
`leaseUntil` 设为 `CURRENT_TIMESTAMP(6) + 1s`，然后 `Thread.sleep(1600)` 再放行持锁线程，
断言 runner 在锁释放后必须发现租约已过期、拒绝提交推理结果。这是一个**依赖 600ms 余量的
时钟断言**——宿主机或 MySQL 容器在负载下出现时钟/调度抖动就会翻面。

为什么可以确定与本次改动无关：
1. 该用例不读任何 `t_config_*` 表、不经过 `controlcenter` 包、不启动控制中心（`enabled`
   默认关闭），本轮改动（40906 映射、requestId 长度、前端客户端、测试装置）都无法进入它的代码路径；
2. **2026-11-20 的三轮实验已证明它是非确定性的**：同一棵树跑两次，第一次它红、第二次它
   10/10 全过；同一批里 `GraphExecutionTest` 的租约用例在**纯 master** 上同样红、报文逐字相同。

**处置**：第 1 轮如实记为失败并归因于既有 flake；**不改它的断言、不跳过它**——放宽会毁掉门禁。

**第 2 轮 pass 3 的 2 条失败**（与第 1 轮完全不相交）：

| 用例 | 报文 | 机制 |
|---|---|---|
| `GraphExecutionTest.safetyAndProviderFailuresMatchCoordinator[7]` | `Bounded condition did not become true`（`await:159` / `done:153`） | 与 2026-11-20 记录的**同一个失败点**：图执行在 10s 预算内没走到 `done` |
| `GraphExecutionTest.processDeathRestoresOnlyCommittedNodesAndPreservesBudgets[4]` | `assertTrue(Files.exists(marker))`（`GraphExecutionTest:414`），附带子进程日志 | 用例 fork 一个子 JVM，10s 内没等到它写出 `entered` 标记——子进程启动慢于预算 |

**第 3 轮 pass 3 的 3 条失败**（仍全在 `GraphExecutionTest`；本轮启动时「本机已有
`eye-test-*` 容器」警告已触发，属受干扰的一轮）：`processDeath…[3]`、
`heartbeatRenewsLeaseAndConcurrentClaimDoesNotDispatchProviders`、
`blockedConnectionAcquisitionSilencesCoordinatorSqlAndExpiresTheLease`——后两条正是
2026-11-20 记录里点名过的两个 flaky。

**三轮对照是最有力的证据**：

| 用例 | 第 1 轮 | 第 2 轮 | 第 3 轮 |
|---|---|---|---|
| `DiagnosisTaskRecoveryTest.resultCannotCommitAfterLeaseExpires…` | **红** | 10/10 绿 | 10/10 绿 |
| `GraphExecutionTest`（37 例） | 37/37 绿 | **2 红** | **3 红** |
| 控制中心相关（pass 2，26 例） | 26/26 绿 | 26/26 绿 | 26/26 绿 |
| 其余 pass 3 类 | 全绿 | 全绿 | 全绿 |

同一棵树、同一套环境，三轮失败集合**两两不相交**（第 2、3 轮同属 `GraphExecutionTest`
但具体用例不同），且都落在「fork 子进程 / 10 秒预算 / 租约心跳」这类时序断言上——
这些用例不读 `t_config_*`、不经过 `controlcenter` 包。结论：**本机全量 L3 到不了稳定的绿**，
这是既有测试设计的边界（与 2026-11-20 的结论一致），不是本轮改动引入的回归。

`GraphExecutionTest#databaseLeaseSurvivesHostClockSkew[2]` 是**本轮之前就存在**的时钟敏感用例
（2026-11-20 的 `verify.sh` 复跑里，同一次运行的 pass 1/2 全绿、pass 3 上它三条全红；
在纯 master 上同样复现）。它的行为特征是：

- 只在 pass 3（隔离库 + 真实 MySQL）出现，单跑该类通常绿；
- 失败模式与租约过期判定有关，与配置中心代码路径无交集（不读 `t_config_*`，不经过 `controlcenter` 包）；
- **本轮不修、不改写它的断言**：修它属于另一个 WP 的范围，且「顺手放宽断言」会让它失去门禁意义。

处置：如实记录每轮 L3 中它的结果；若某轮它在 pass 3 变红，该轮判为**不通过**，但归因写明
「既有 flaky，与本次改动无交集」，不当作本轮引入的回归，也不把它抹成通过。

### 6.5 控制中心全套 L2（回归证明）

整改动了错误码映射、幂等输入校验与前端客户端，必须证明没有回归。点名 17 个类在
一次性 MySQL 8 + Redis 上跑：

| 类 | 用例 | 结果 |
|---|---|---|
| `SameOriginFilterControlCenterTest` | 21 | PASS |
| `SameOriginFilterTest` | 10 | PASS |
| `ControlCenterAcceptanceTest` | 11 | PASS |
| `ControlCenterDisabledAcceptanceTest` | 3 | PASS |
| `ControlCenterExceptionHandlerTest` | 3 | PASS |
| `ControlCenterMultiProcessTest` | 7 | PASS |
| `ControlCenterPersistenceIntegrationTest` | 17 | PASS |
| `ControlCenterPublishAcceptanceTest` | 6 | PASS |
| `ControlCenterPublishIntegrationTest` | 18 | PASS |
| `ConfigRegistryTest` | 5 | PASS |
| `ConfigValueValidatorTest` | 12 | PASS |
| `ConfigPermissionAspectTest` | 12 | PASS |
| `ControlCenterCsrfFilterTest` | 10 | PASS |
| `ConfigPayloadCodecTest` | 6 | PASS |
| `ControlCenterIdempotencyServiceTest` | 5 | PASS |
| `ControlCenterWiringTest` | 4 | PASS |
| `CsrfTokenServiceTest` | 8 | PASS |

**合计 17 类 / 158 例 / 0 失败 / 0 跳过 / 522s**（`scripts/verify-fast.sh --db --java <上述 17 类>`）。
前端改动单独跑：`npx vitest run src/lib/controlCenterApi.test.ts src/lib/csrf.test.ts`
→ **2 文件 / 15 例通过**；`npm run typecheck`（`tsc -b`）与 `eslint`（3 个改动文件）均无输出。

---

## 7. 部署前置检查（本轮**未验证**，不得当作已通过）

| 项 | 状态 | 说明 |
|---|---|---|
| 反向代理保留 `Host` | 待验证 | `SameOriginFilter` 严格模式用 `request.getServerName()` 拼 origin；反代必须 `proxy_set_header Host $host`（与既有 `SameOriginFilter` 的部署前提一致） |
| `X-Forwarded-Proto` | 待验证 | 仅当 `eye-diagnosis.control-center.trust-proxy-headers=true` 才读取；开启前必须确认反代会覆写该头 |
| 外部 Origin 白名单 | 待验证 | 若反代改写 Host，必须把外部 origin 显式登记到 `allowed-origins`（完整 `scheme://host[:port]`，不支持通配） |
| Session Cookie `Secure` | **未满足** | 当前只在 HTTPS 上靠 SameSite=Lax + HttpOnly；`Secure` 未设置 |
| 控制中心开关 | 已满足 | 默认 `false`；白名单默认空 —— 合并不等于启用 |
| 审计表增长 | 待决策 | 匿名 Origin/CSRF 拒绝每请求一行（A-10）；需要保留期或网关限流 |

---

## 8. 合并准入判断

### 8.1 判断

**GO（对审计基线 `5dc2eac5` 成立）。** 当前 master 已前进到 `c90b0caa` 且 V32–V34 被占，
在该基线上执行前必须先完成 §8.4 的重编号与复跑（C-9）——那是执行前置，不改变本报告的审计结论。

> **owner 裁决（2026-11-23）**：C-3 选「接受为已登记风险 R-1（A-04 不在本轮修）」；
> C-5 选「接受『pass 1 + pass 2 全绿 + pass 3 既有 flake 独立归因』作为合并证据」。
> 两条均为**接受现状**，不是把未达成的事写成已达成。

- **P0：0 条。** 七条不变量（§3.1）逐条对上了实现，跨进程与故障注入没有推翻任何一条。
- **P1：4 条**，3 条已在本轮最小整改并有测试锁住；剩 1 条（A-04）经 owner 裁决**接受为 R-1**，
  不在本轮修（它不是代码缺陷，是已定稿状态机语义的取舍）。
- **合并 ≠ 启用**：控制中心 `enabled` 默认 `false`、发布人白名单默认空、严格 Origin 与
  CSRF 过滤器只在开启时注册。合入 master 后既有接口行为不变：迁移只新建六张空表
  （V32 五张 + V33 一张）与一行活跃指针，不碰任何既有表；控制中心端点不注册。
- **C-5 的事实不变**：本机完整 L3 **三轮都没有全绿**，红的是既有协作/租约类时序用例
  （§6.4），三轮失败集合两两不相交，控制中心所在的 pass 2 三轮都是 26/26。owner 已裁决
  以「pass 1 + pass 2 全绿 + pass 3 独立归因」作为合并证据；**合并说明里必须照实写
  「第三段有已知的不稳，与本改动无关」，不得写成「全绿」**。
- **启用前**必须完成 §9.3 的部署项，尤其是反代 `Host` 与 Session Cookie `Secure`——
  这两项本轮**没有真实环境可验证**，已标为待验证，不得当作已通过。

### 8.2 准入条件（逐条可核对）

| # | 条件 | 状态 |
|---|---|---|
| C-1 | 无 P0 | ✓ |
| C-2 | P1 中影响正确性的 A-01/A-02/A-03 已修且有测试 | ✓（单测 8 例 + 前端 15 例 + 跨进程 7 例） |
| C-3 | A-04 由 owner 裁决接受或转 WP | ✓ **接受为 R-1**（owner 2026-11-23） |
| C-4 | 控制中心测试全绿（17 类 / 158 例） | ✓（§6.5） |
| C-5 | 完整 L3 至少一轮全绿，且每轮原始日志保留 | ✓ **owner 裁决接受替代证据**（owner 2026-11-23）。事实：三轮 pass 1 / pass 2 全绿，pass 3 分别红 1 / 2 / 3 条**既有**时序 flake（§6.4），三轮失败集合两两不相交；日志全部保留。合并说明必须照实写明第三段的不稳 |
| C-6 | V31→V33 在真实 MySQL 8 上验证 | 见 §6.2 |
| C-7 | 默认关闭、白名单为空未被改动 | ✓（`ControlCenterProperties:29-35`，未在本轮修改默认值） |
| C-8 | 未引入越界改动（Provider / 品牌 / WP-04 / 临床） | ✓ |
| C-9 | 基于**当前** master 重新编号迁移并复跑迁移门禁 | ⏳ **未做**：见 §8.4（本报告写在 `5dc2eac5` 上，那时 V32/V33 还空着） |

### 8.4 合并前置：基线与迁移编号（2026-11-24 核对）

**本报告的 GO 只对审计基线成立，不能原样搬到当前 master。** 核对结果：

| | 审计基线 `5dc2eac5` | 当前 master `c90b0caa` |
|---|---|---|
| 迁移头 | V31 | **V34** |
| V32 | 空闲 → 本分支 `V32__control_center_core.sql` | **`V32__knowledge_candidate_index_version.sql`（已合入）** |
| V33 | 空闲 → 本分支 `V33__control_center_publish_approval.sql` | **`V33__diagnosis_task_restrict_reason.sql`（已合入）** |
| V34 | — | `V34__diagnosis_evidence_semantics.sql` |

合入前必须完成（缺一条都是真失败，不是「环境问题」）：

1. 把分支 rebase 到当前 master（`c90b0caa` 起）；
2. 两个控制中心迁移重新编号到 **V35 / V36**（或届时最新的两个空号），文件名、`flyway_schema_history`
   预期、`docs/architecture/migration-matrix.md` 的 V32/V33 行同步更新；
3. **不能只改文件名**：若目标库已经跑过 master 的 V32/V33，控制中心迁移必须以新版本号在其后执行；
   若目标库跑过本分支的 V32/V33（本地/演示库），需按重建处理，不得 `repair` 掩盖；
4. 复跑迁移门禁（空库 → 最新、master → 最新、V31 → 最新），并复跑控制中心 L2（17 类）；
5. 重跑一次 L3（结论仍按 §6.4 的口径记录 pass 3 的既有 flake，不得写成全绿）。

> 这条不在 WP-01D 审计范围内（审计时这些版本号确实空闲），但它是**合并准入的一部分**：
> 带着重复版本号合入会让 Flyway 直接启动失败。

### 8.3 合并后仍需完成

部署启用是**另一轮**决策，不随合并自动发生：§9.3 全部条目 + 真实反代端到端验证 +
owner 对 R-2…R-9 的签字（R-1 已随 C-3 裁决接受，2026-11-23）。

---

## 9. 风险接受清单与后续工作

### 9.1 本轮明确接受的风险（合并前需 owner 签字）

| # | 风险 | 为什么接受 | 触发条件 | 升级路径 |
|---|---|---|---|---|
| R-1 | **A-04 僵尸 PENDING 审批单**：`expectedActiveReleaseId` 过期后，审批单永远 40900、不会自动 STALE，只能人工拒绝 | 把它自动标 STALE 会改变 WP-01C 已接受的状态机语义（40900 故意不改状态，避免谎称「内容失效」）。当前行为**安全但烦人**。**owner 2026-11-23 裁决：接受** | 两张 PENDING 单绑定同一活跃版本，先批一张 | WP-01E：审批列表标「期望版本已过期」或提供批量拒绝 |
| R-2 | 幂等「处理中」无轮询语义（A-05）：同号并发提交会阻塞到 `innodb_lock_wait_timeout`（默认 50s） | 已提交行必有结果、未提交行不可见，不存在「处理中」状态；改成轮询式会让 API 复杂化，MVP 不需要 | 客户端对同一号并发重试 | 阈值调小 + 客户端超时重试 |
| R-3 | STALE 标记与其失败审计分两次提交（A-06） | 方向安全（不会「已 STALE 但无审计」）；改成同事务会与外层行锁互等 | `markStale` 的两次 REQUIRES_NEW 之间进程崩溃 | 可接受 |
| R-4 | 匿名 Origin/CSRF 拒绝**每请求写一行审计**，无速率限制（A-10） | 审计可达性优先于表体积；当前无公网暴露的控制中心 | 公网可达且被刷 | 网关限流 + 审计保留期 |
| R-5 | 会话 30 天、CSRF token 会话内不轮换（A-12） | token 是会话级凭据，轮换不改变根本暴露面 | XSS | 缩短会话 + 关键操作二次校验 |
| R-6 | 发布/审批列表每页一次 `COUNT(*)`，带状态过滤仍需 filesort（A-13） | MVP 数据量（发布以天计） | 发布记录上万 | 计数缓存 / 覆盖索引 |
| R-7 | 无「发布后自动回滚」熔断；三张表无归档保留策略（A-14） | 属运维与后续 WP | 线上配置引发故障 | 监控 + 手动回滚演练 |
| R-8 | `switchPointer` 的 0 行分支正常路径不可达且无用例（A-15） | 行锁已串行化；该分支是纵深防御 | — | 保持 |
| R-9 | 既有租约类 flaky：`DiagnosisTaskRecoveryTest#resultCannotCommitAfterLeaseExpiresWhileWaitingForRowLock`、`GraphExecutionTest#databaseLeaseSurvivesHostClockSkew[*]`（§6.4） | 与本次改动无交集（不读 `t_config_*`、不经 `controlcenter`）；放宽断言会毁掉门禁 | 宿主机 / 容器时钟与调度抖动 | 另立 WP 改成可注入时钟 |

### 9.2 合并前必须完成（代码层面）

- [x] A-01 锁冲突 → `40906`（含单测与跨进程证据）
- [x] A-02 `requestId` 超长 → `40000`
- [x] A-03 前端每个动作使用不同 `requestId` + 草稿写操作默认生成
- [x] A-04 裁决：owner 2026-11-23 选择**接受（R-1）**，不在本轮修；转 WP-01E 处理列表提示

### 9.3 部署/启用前必须完成（非代码，或需另一轮）

- [ ] 反向代理 `proxy_set_header Host $host`（§7）
- [ ] `trust-proxy-headers` 开启前确认反代覆写 `X-Forwarded-Proto`
- [ ] Session Cookie 加 `secure: true`（HTTPS 部署）
- [ ] 若反代改写 Host：把外部 origin 显式登记进 `allowed-origins`
- [ ] `publisher-user-ids` 为空时**任何人都不能发布**——启用前显式配置
- [ ] 审计表保留期 / 网关限流策略
- [ ] 真实反代环境下的端到端验证（本轮全部标「待验证」）

### 9.4 明确不在本轮范围

Provider 热切换、品牌资产管理、WP-04 管理 UI、Agent/临床推理接管——均未触碰。
配置中心仍默认关闭、发布人白名单仍默认为空。

---

## 10. 本轮交付物（全部未提交）

### 10.1 代码（最小整改）

| 文件 | 改动 |
|---|---|
| `src/main/java/.../exception/ErrorCode.java` | 新增 `CONFIG_CONCURRENT_CONFLICT(40906)`，含与 40900 的语义区分说明 |
| `src/main/java/.../controlcenter/ControlCenterExceptionHandler.java` | **新增**：`@Order(HIGHEST_PRECEDENCE)` + `@RestControllerAdvice(assignableTypes={ControlCenterController, ControlCenterCsrfController})`，把 `PessimisticLockingFailureException` 译成 40906 |
| `src/main/java/.../controlcenter/service/ControlCenterIdempotencyService.java` | 新增 `REQUEST_ID_MAX_LENGTH=64` 与 `validateRequestId`（按码点），在 `reserve` / `complete` / `requireRequestId` 写库前调用 |
| `src/main/java/.../controlcenter/security/ControlCenterSecurityAudit.java` | 更正类注释：匿名拒绝**会**写 `actorId=NULL` 的审计行（原文声称不落库） |
| `web/src/lib/controlCenterApi.ts` | 导出 `REQUEST_ID_MAX_LENGTH` / `newRequestId()`；草稿三个写方法默认生成 `requestId`；模块文档写明「每个动作用不同的号」 |

### 10.2 测试

| 文件 | 内容 |
|---|---|
| `src/test/java/.../controlcenter/ControlCenterExceptionHandlerTest.java` | **新增**：锁超时 / 死锁 → 40906，报文含「重试」；两类异常都可被该 advice 捕获 |
| `src/test/java/.../controlcenter/service/ControlCenterIdempotencyServiceTest.java` | **新增**：空号不落库、65 码点拒绝、64 接受、`requireRequestId` 同规则、emoji 按码点计 |
| `src/test/java/.../controlcenter/ControlCenterProcessHarness.java` | **新增**：子 JVM 入口（完整 Spring 上下文 + 生产代码路径 + 起跑门 + 事务挂起模式） |
| `src/test/java/.../controlcenter/ControlCenterMultiProcessTest.java` | **新增**：7 个跨进程用例（§4.2） |
| `web/src/lib/controlCenterApi.test.ts` | 追加 2 例：默认生成互不相同的 `requestId`；显式传入的 `requestId` 原样保留 |

### 10.3 工具与文档

| 文件 | 改动 |
|---|---|
| `scripts/verify.sh` | ① `ControlCenterMultiProcessTest` 从 pass 1 移到 pass 2；② fork JVM 堆上限 `VERIFY_TEST_XMX`（默认 1500m）；③ 本机已有 `eye-test-*` 栈时启动警告 |
| `docs/product/wp-01/wp-01d-final-audit.md` | 本文件 |
| `docs/product/iris-control-center-1.0-mvp-prd.md` | 追加 §11.1 契约裁决 |
| `docs/product/wp-00/config-center-adr.md` | 追加 WP-01D 裁决表 + D13 |
| `docs/product/wp-00/mvp-api-contract.md` | 追加 §7 增量 |
| `docs/product/wp-01/wp-01c-publish-approval-delivery.md` | 追加 §11 追认 |
| `docs/architecture/migration-matrix.md` | V32 列注释漂移说明（A-08） |
| `docs/README.md` | 索引补 WP-01D 与本轮新增文档 |
