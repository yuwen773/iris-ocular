# WP-01B 后端持久化与配置基础 API — 交付记录

> 基线：`origin/master` @ `5dc2eac5`（2026-11-23 复核，工作树 `t3/wp-00-5-baseline-audit`；
> 该工作树于 2026-11-24 更名为 `feat/control-center-wp01`）
> 前置：`docs/product/wp-00/wp-00-5-baseline-audit.md`（WP-00.5）、WP-01A 已实现的
> Origin / CSRF / 权限 / 前端错误信封改动（同一工作树内未提交）
> 状态：**本地验收候选**（2026-11-24 起已提交到分支 `feat/control-center-wp01`，未合并、未部署）

本文件只记录 WP-01B 实际做了什么、验到了什么、还剩什么。设计结论仍以
`docs/product/wp-00/config-center-adr.md`、`configuration-boundary.md`、
`mvp-api-contract.md` 为准，本文件不复述、不覆盖。

---

## 1. 范围

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | WP-01A 定向安全复核（路由匹配、执行顺序、AOP 边界、CSRF 端点缓存） | 完成，1 项 P1 缺陷已修（CORS 未放行 `X-CSRF-Token`） |
| 1 | V35 五张表最小结构 + 并发/幂等/事务语义 | 完成 |
| 2 | 配置读取 + 草稿生命周期 API（无发布/回滚引擎） | 完成 |
| 3 | 结构化审计持久化 + 测试矩阵 + 完整验证 | 完成（见 §5、§6） |

范围外且确认未做：Git 资产管理（WP-02）、Provider 配置与热切换（WP-03）、
Agent / 临床推理 / 模型客户端调用路径、`@AuthCheck` 角色语义、管理端 UI（WP-04）。

---

## 2. 阶段 0：WP-01A 定向安全复核

| 复核项 | 结论 | 证据 |
|---|---|---|
| `/api/admin/control-center/**` 与 Origin/CSRF 过滤器的实际匹配 | 通过。`SameOriginFilter.isControlCenterPath` 用 `UrlPathHelper.getPathWithinApplication` 取「MVC 实际路由用的路径」——解码、剥掉 `;` 矩阵参数、剥离 `context-path`（本仓库 `/api`）——再比对 `/admin/control-center`；`/admin/control-center-fake` 不匹配。**2026-11-24 修正**：早先按 `getRequestURI()` 原始串比对，导致 `;test=1` 变体被 MVC 路由却跳过两道过滤器，见 WP-01D §8.5 | `SameOriginFilterControlCenterTest#pathDetectionStripsContextPath`、`#pathDetectionHandlesPrefixBoundary`、`ControlCenterAcceptanceTest#matrixParameterVariantsCannotBypassSecurityControls`（RANDOM_PORT 真 Tomcat，含 `;` 与 `%3B` 变体） |
| 执行顺序 | Origin（`@Order(1)`）→ CSRF（`@Order(2)`）→ 认证（`AuthInterceptor`，40100）→ `@ConfigPermission`（40301） | `ControlCenterAcceptanceTest#originIsCheckedBeforeCsrf`、`#anonymousRequestIsRejected` 等 |
| `@ConfigPermission` 在代理与内部调用下的边界 | **JSON 注释已写明**：CGLIB 代理不拦截同类内部调用，注解必须挂在控制器入口方法上；有专门用例锁定该事实 | `ConfigPermissionAspectTest#selfInvocationBypassesTheAspect` |
| CSRF 端点缓存与泄漏 | `GET /admin/control-center/csrf` 返回 `Cache-Control: no-store` + `Pragma: no-cache`，仅管理员可取，响应只含 token 本身 | `ControlCenterAcceptanceTest`（no-store + 非管理员拒绝） |

复核中发现并修复的缺陷（P1，非 P0）：

- `CorsConfig.allowedHeaders` 缺少 `X-CSRF-Token`。同源部署不受影响，但一旦按
  `allowed-origins` 白名单跨域部署，预检会挡掉写请求头，控制中心失去写能力。
  已加入白名单并注明「放行不改变安全边界」。

---

## 3. 阶段 1：持久化（`V35__control_center_core.sql`）

### 3.1 编号复核

开工前核对：`origin/master` 迁移头为 **V31**，工作树内无其它未应用迁移，
CI 的 `FlywayMySqlIntegrationTest` 按目录顺序校验连续性。故新迁移取 **V32**，
未修改 V1–V31 任何文件。

> **编号变更（2026-11-24，合并前核对 `master c90b0caa`）**：master 已前进到迁移头
> **V34**，V32–V34 已被占用（知识库候选索引版本 / 判读任务受限原因 / 判读证据语义）。
> 本迁移改号为 **V35**（配套的发布审批迁移由 V33 改为 V36），**DDL 内容一字未改**。
> 本文其余出现 V32 的地方均指这条迁移，按 V35 读。

### 3.2 结构

| 表 | 作用 | 关键约束 |
|---|---|---|
| `t_config_draft` | 单例草稿 | `PRIMARY KEY (id)`，`id` 恒为 1（把「全局只有一份草稿」落成结构约束）；`revision` 乐观锁；`payload JSON` |
| `t_config_release` | 不可变发布快照 | 只插入不更新；`uk_config_release_version (releaseVersion)`；`digest CHAR(64)`；`rollbackFromReleaseId` 记录回滚来源 |
| `t_config_active_pointer` | 单行活跃指针 | `PRIMARY KEY (id)`，预置行 `(1, NULL)`；发布事务 `SELECT ... FOR UPDATE` 该行串行化并发发布 |
| `t_config_request` | 请求级幂等 | `uk_config_request_actor (actorId, requestId)`，`requestId` 用 `utf8mb4_bin`（避免大小写不敏感把两个请求判成一个） |
| `t_config_audit_event` | 只追加审计 | `uk_config_audit_event (eventId)`、`idx_config_audit_time (occurredAt)`；不落 Session / CSRF token / 密钥 / Provider 凭据 |

口径与既有库一致：列名小驼峰（`map-underscore-to-camel-case: false`）、utf8mb4、
`ENGINE=InnoDB`、**不建外键**（跨表完整性由服务层事务保证）。

### 3.3 语义

- **不可变 Release**：本阶段只建表，不写数据；发布引擎留给 WP-01C。指针为
  `NULL` 时 `GET /active` 返回 Registry 默认值叠加，不报错。
- **乐观并发**：草稿更新用条件更新
  `UPDATE ... WHERE id = ? AND revision = expectedRevision`，影响 0 行即 `40900`；
  `revision` 成功编辑时 `+1`，并清空 `validationRevision` / `validationResult`。
- **事务边界**：业务写与成功审计同事务提交；失败/拒绝审计走 `REQUIRES_NEW`
  独立事务并可存活于业务回滚之后（有专门用例验证）。
- **幂等**：`requestId` 由客户端放在请求体；先重放、后执行、最后记录。同一
  `(actorId, requestId)` 内容不同返回 `40908`；记录发生在业务提交之后
  （取舍见 §7 风险 R-3）。

---

## 4. 阶段 2：配置基础 API

前缀 `/api/admin/control-center`（`server.servlet.context-path=/api`），全部受
`eye-diagnosis.control-center.enabled=true` 门控，默认关闭时端点不注册。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/csrf` | ADMIN | 签发/复用 Session 绑定 CSRF token；`no-store` |
| GET | `/registry` | `READ` | 配置键注册表（键、类型、范围、默认值、schemaVersion） |
| GET | `/active` | `READ` | 当前生效配置；无发布时返回默认值叠加 |
| GET | `/draft` | `READ` | 单例草稿；不存在时返回 `revision=0, status=NONE` |
| POST | `/drafts` | `EDIT` | 创建草稿（revision 1）；已存在 → `40900` |
| PUT | `/drafts/{id}` | `EDIT` | 修改草稿，必须带 `expectedRevision`；冲突 → `40900` |
| POST | `/drafts/{id}/validate` | `VALIDATE` | 校验并固化 `validationRevision`；不合法 → `ValidationVO(valid=false)` + `FAILED` 审计 |

**发布 / 回滚端点不存在**（`PUBLISH` / `ROLLBACK` 动作已在枚举里预留，尚无
调用方）——刻意不在本阶段提前实现审批发布引擎。

> 一处刻意的不一致：`GET /csrf` 用既有的 `@AuthCheck(mustRole=ADMIN)`，非管理员得到
> `40101`；配置动作端点走 `@ConfigPermission`，非管理员得到 `40301`。前者不是配置动作，
> 沿用既有角色语义才符合本轮「不扩展 `@AuthCheck`」的边界；两者都在验收用例里钉住。

错误码（新增于 `ErrorCode`，全部沿用 HTTP 200 + 统一信封）：

| 码 | 语义 |
|---|---|
| 40301 `CONFIG_PERMISSION_DENIED` | 是管理员但当前动作不在授权范围（如不在发布人名单） |
| 40900 `CONFIG_VERSION_CONFLICT` | revision 冲突 / 草稿已存在 |
| 40903 `CONFIG_SCHEMA_UNSUPPORTED` | `schemaVersion` 与代码内 `SCHEMA_VERSION` 不一致 |
| 40904 `CONFIG_UNKNOWN_KEY` | 载荷含未注册配置键 |
| 40905 `CONFIG_DEPLOY_PROTECTED` | 命中部署事实前缀（`spring.` / `server.` / `cos.` 等） |
| 40908 `CONFIG_IDEMPOTENCY_MISMATCH` | 同 `requestId` 内容不一致 |
| 40901 / 40902 | 未通过校验 / 校验结果过期（服务层已就绪，发布端点尚不存在，故当前无外部触发路径） |

输入校验在服务端完成：键必须在 Registry 内、类型匹配、字符串按**码点**计长度、
资源引用为 32 位十六进制 id、未知键与部署保护前缀分别报 `40904` / `40905`。

### 4.1 前端管道（同批交付）

| 文件 | 内容 |
|---|---|
| `web/src/lib/csrf.ts` | token 获取 + 缓存 + 单飞（并发只发一次）+ 失败复位；`csrfRequest()` 自动带 `X-CSRF-Token`，收到 CSRF 类错误时清缓存；监听认证失效事件清缓存 |
| `web/src/lib/controlCenterApi.ts` | 读取 / 草稿 5 个调用的类型化封装，写调用统一走 `csrfRequest` |
| `web/src/lib/http.ts` | 非 2xx 也解析统一信封（`code` 优先于 HTTP status），并复用认证失效广播 |
| `web/src/lib/csrf.test.ts` | 9 个用例：缓存、单飞、失败复位、错误清缓存、认证失效清缓存 |

---

## 5. 阶段 3：审计

`t_config_audit_event` 只追加，记录 `action` / `actorId` / `targetId`（草稿 id）/
`beforeVersion` → `afterVersion`（revision）/ `result`（`SUCCESS` / `DENIED` / `FAILED`）/
`reasonCode` / `payload`（只放**改动键名**，不放值）。

覆盖的动作：

- 业务成功：`DRAFT_CREATE` / `DRAFT_UPDATE` / `DRAFT_VALIDATE`（与业务同事务）
- 业务失败：版本冲突、非法载荷、校验不通过（`REQUIRES_NEW`，业务回滚后仍存在）
- 权限拒绝：`action` 为被拒绝的动作名（如 `PUBLISH`）+ `result=DENIED` +
  `reasonCode=PERMISSION_DENIED`（`ConfigPermissionAspect`）
- 前置安全拒绝：`ORIGIN_REJECTED` + `ORIGIN_MISSING|NULL|MISMATCH`、
  `CSRF_REJECTED` + `CSRF_MISSING|MISMATCH`。过滤器通过
  `com.csust.eyediagnosis.security.SecurityEventSink` 端口写入，不依赖
  `controlcenter` 包内部实现；匿名请求不触库（只在 Session 已存在且带登录态
  属性时解析主体）。

敏感值：审计服务对键名含 `secret` / `password` / `token` / `credential` 或以
`key` / `apikey` 结尾的字段做遮蔽；不写 Session id、CSRF token、Provider 凭据。

---

## 6. 测试矩阵与验证证据

### 6.1 本轮新增 / 改动的测试类

| 测试类 | 用例数 | 层次 | 覆盖 |
|---|---|---|---|
| `ControlCenterPersistenceIntegrationTest` | 17 | `@SpringBootTest` + 真 MySQL（`ci` profile） | V35 结构、默认值回落、创建/冲突、条件更新、只记改动键名、过期 revision、失败审计存活于回滚、校验固化与失效、非法载荷、未知键、部署保护前缀、schemaVersion、幂等重放/不一致/跨请求号隔离、10 轮并发仅一人成功、脱敏 |
| `ControlCenterAcceptanceTest` | 11 | `@SpringBootTest(RANDOM_PORT)` 真 Tomcat + HTTP 客户端 | 匿名 40100、非管理员 40301、读取端点、跨站 403 且无写入、`Origin: null` + 合法 Referer 仍拒、CSRF_MISSING / CSRF_MISMATCH 且留痕主体、Origin 先于 CSRF、CSRF 端点 `no-store` 且仅管理员、草稿全流程、过期 revision 的统一信封、写操作留痕且无泄漏 |
| `ControlCenterDisabledAcceptanceTest` | 3 | RANDOM_PORT | 默认关闭时端点不注册（既有 catch-all 返回 200/50000，不是 403）、不做 CSRF 拦截、既有接口不受影响 |
| `SameOriginFilterControlCenterTest` | 21 | Mock | 严格 Origin 全矩阵（同源、尾斜杠、跨站、null、缺失、空串、scheme/port 不符、带 path、Referer 回退、白名单精确匹配、子域不匹配、旧白名单不适用、代理头开关、安全方法、非控制中心路径保持旧语义、前缀边界） |
| `ControlCenterCsrfFilterTest` | 10 | Mock | 合法 token 通过、PUT/DELETE/PATCH 均校验、无 Session、Session 无 token、缺头、错 token、跨 Session token 复用、安全方法绕过、非控制中心路径不受影响、相似前缀不受保护 |
| `ConfigPermissionAspectTest` | 12 | 独立 AOP | 未登录 40100、非管理员 40301 且留痕、READ/EDIT/VALIDATE 不需要发布人、PUBLISH 需要白名单、`actorOf` 空值、**同类自调用绕过切面**（把边界钉死） |
| `ConfigRegistryTest` | 5 | 纯单测 | 默认值、键集合、类型、部署保护前缀、schemaVersion |
| `ConfigValueValidatorTest` | 12 | 纯单测 | 长度按码点、类型、范围、资源 id 格式、未知键、保护键、多错误原因排序 |
| `ConfigPayloadCodecTest` | 6 | 纯单测 | 规范化 JSON 稳定性、摘要、请求摘要含 method+path、默认值叠加 |
| `CsrfTokenServiceTest` | 8 | 纯单测 | 签发、复用、常量时间比对、缺失/错值、无 Session、清除、token 强度 |
| `ControlCenterWiringTest` | 4 | `@SpringBootTest(MOCK)` 开关打开 | 属性绑定与空白裁剪、CSRF 控制器注册、Origin 过滤器先于 CSRF 过滤器、token 服务被控制器与过滤器共享 |
| `EyeDiagnosisApplicationTests`（改） | 既有 `contextLoads` | `@SpringBootTest(MOCK)` 默认关闭 | 默认部署下全局 Origin 过滤器仍在，但 CSRF 过滤器与端点**一枚都不注册** |
| `SameOriginFilterTest`（改） | 10 | Mock | 既有全局 Origin 行为回归（跨站、缺失、白名单、默认端口、代理、端口混淆） |
| `web/src/lib/csrf.test.ts` | 9 | vitest | 缓存、单飞、失败复位、CSRF 错误清缓存、认证失效清缓存 |
| `web/src/lib/http.test.ts`（改） | 既有 + 4 | vitest | 非 2xx 时按信封 `code`/`msg` 抛 `ApiError`、非 JSON 体退回 HTTP 状态码、非 2xx 信封里的认证码仍广播、`silentAuth` 抑制广播 |

### 6.2 完整验证（本机，2026-11-23）

| 命令 | 结果 |
|---|---|
| `bash scripts/verify.sh` 第 1 轮（L3：一次性 MySQL 8 + Redis，三遍） | pass 1/3 `972 用例 / 0 失败 / 304 跳过`；pass 2/3 `19 用例 / 0 失败`；pass 3/3 `302 / 303`（唯一失败见下）；结束时容器自清理断言通过 |
| `bash scripts/verify.sh` 第 2 轮（复跑） | 见 §6.3 |
| `mvn -o -DskipTests test-compile` | EXIT 0 |
| `web/`: `npm run typecheck` / `vitest run` / `npm run build` / `npm run lint` | 全绿：109 文件 / **1180 用例**；构建通过（既有 chunk 体积告警）；lint 0 |

- 304 个跳过全部是既有按开关/环境门控的套件（协作、患者草稿、医生复核等在第 1 遍整体跳过，
  第 3 遍才用隔离库真跑）；**本轮新增套件 0 跳过**（见 §6.1 每个类的 `Skipped` 均为 0）。

**pass 3 的那一条失败（不算本轮回归，但必须登记）**

- 失败：`GraphExecutionTest#checkpointCommitFailureRollsBackEvidenceAndItsPublicFactsTogether`，
  `Bounded condition did not become true`——10 秒有界等待未观察到终态。
- 该类与本轮改动**零交集**：`git status` 在 `src/main/java/**/collaboration`、`patientdraft`、
  `agent`、`src/test/java/**/collaboration` 上没有任何条目。
- 仓库里已有同类登记：`docs/release/verification-matrix.md` §2.3 记录过同一个类
  `#processDeathRestoresOnlyCommittedNodesAndPreservesBudgets[7]` 在干净集成提交上等待超时；
  §2.8 的修复只动测试夹具、未放宽任何断言超时。该类的失败模式是环境（时钟 / 负载）敏感。
- 本机加跑（隔离 schema）2/2 失败在**另一个**参数化用例 `…processDeathRestoresOnlyCommittedNodes…[7]`，
  且本机 MySQL 是 `SYSTEM` 时区（+08）而 JVM 被固定成 UTC——相差 8 小时，正是文档记录的租约类 flaky 触发条件。
  两次失败落在不同用例上，说明不是确定性回归。
- 结论：**不归因于本轮改动**，但也不能算绿。合并前需要在安静机器上取一次 pass 3 全绿。

### 6.3 复跑结果

同一棵树、同一命令再跑一轮（`/tmp/verify-l3-wp01b-retry.log`）：

| 门禁 | 第 1 轮 | 第 2 轮 |
|---|---|---|
| pass 1/3 完整单测 | ✅ `972 / 0 / 304 跳过` | ✅ `972 / 0 / 304 跳过` |
| pass 2/3 MySQL 集成门禁 | ✅ `19 / 0` | ✅ `19 / 0` |
| pass 3/3 隔离库协作套件 | ❌ `302 / 303`，`GraphExecutionTest#checkpointCommitFailureRollsBackEvidenceAndItsPublicFactsTogether`（10.20s 有界等待） | ❌ `302 / 303`，`GraphExecutionTest#heartbeatRenewsLeaseAndConcurrentClaimDoesNotDispatchProviders`（10.53s 有界等待） |
| 收尾 | ✅ 容器自清理断言通过 | ✅ 容器自清理断言通过 |

两轮失败**不是同一条用例**，但都在同一个类、都是「10 秒有界等待没等到终态」。加上本机隔离
schema 上加跑的两次（又落在 `processDeathRestoresOnlyCommittedNodesAndPreservesBudgets[7]`），
四次里出现了三个不同的用例——这是这类租约/心跳用例在本机的环境敏感特征，不是确定性失败，
也不是本轮改动引入的路径（该类与协作/患者草稿/Agent 的生产代码在本工作树里零改动）。

**结论**：WP-01B 的门禁（pass 1 + pass 2 + 新增套件）两轮全绿；pass 3 的这条不确定项需要
在合并前用安静机器或 CI 取一次 `303/303` 再放行，不能拿「与改动无关」当绿。


---

## 7. 已知风险

| # | 风险 | 影响 | 处置 |
|---|---|---|---|
| R-1 | 幂等记录写在业务提交**之后**（`remember` 在 `work.get()` 之后） | 进程在「业务已提交、`t_config_request` 未写入」之间崩溃时，同一 `requestId` 重试会再执行一次：`CREATE` 撞主键返回 `40900`、`UPDATE` 撞 revision 返回 `40900`，结果可辨识但幂等键未生效 | 本阶段写端点只有草稿，后果是「多一条 40900」。**WP-01C 的发布端点必须改为占位先行（先插 request 行再执行）或 outbox**，否则同一 `requestId` 可能重复发布 |
| R-2 | 单例草稿：全局只有一份 | 两个管理员同时编辑会互相覆盖 revision 冲突（设计如此，ADR D3），不是缺陷；但 WP-04 UI 必须给出「他人正在编辑」的提示，否则体验上像 bug | WP-04 落地时处理 |
| R-3 | 失败/拒绝审计走 `REQUIRES_NEW` | 需要第二条数据库连接；连接池耗尽时审计写入失败被吞掉（只打 warn），该次拒绝仍生效 | 接受：不让审计拖垮主流程；运维需监控该 warn |
| R-4 | CSRF token 绑定 Session，**登录/登出不轮换**；既有登录流程也没有 `changeSessionId()` | 会话被固定（session fixation）时可继承 token。既有代码层面的会话固定缺口，不在本轮授权范围内修改（改登录流程属认证行为变更） | 登记为安全债；WP-05 安全回归时一并评估 |
| R-5 | V35 在 CI 一次性 MySQL 8 上验过「空库 → V35」与「**V31 → V35**」（新增用例；原为 V31→V32 只执行 1 条，2026-11-24 改号后为 V31→V36 执行 5 条，含 master 的 V32–V34） | 真实目标库副本尚未演练（`docs/architecture/migration-matrix.md` 要求逐库确认） | 发布前在目标库副本上跑一次；CI 路径已同构覆盖 |
| R-6 | 发布 / 回滚零实现 | `t_config_release`、`t_config_active_pointer` 无写入路径，`GET /active` 永远返回默认值；`PUBLISH` / `ROLLBACK` 枚举与 `publisher-user-ids` 配置暂无调用方 | 设计如此（WP-01C 范围） |
| R-7 | 部署前提：反代未改写 Host / scheme 时，控制中心写请求全部 403 | 默认 `trust-proxy-headers=false`，必须在 `allowed-origins` 显式登记外部 origin，且 nginx 需 `proxy_set_header Host $host`（与 `SameOriginFilter` 既有部署前提一致） | 部署清单里写明；开启前先验一次真实 origin |
| R-8 | 前端只交付管道（`csrf.ts` / `controlCenterApi.ts`），没有页面 | 浏览器端到端只有 vitest 单测与后端 `RANDOM_PORT` 验收，没有「真浏览器 + 真 CSRF 头」的证据 | WP-04 后补；届时必须补一条真实浏览器链路 |
| R-9 | 配置键注册表是代码内静态清单 | 新增配置项忘记登记会被 `40904` 拒绝（安全方向），但没有编译期约束；部署保护前缀同理 | 接受；新增键时同步 `ConfigRegistry` 并加用例 |

## 8. 未覆盖（明确不做）

- 发布审批引擎、`40901`/`40902` 的外部触发路径（表与服务就绪，端点不存在）
- 审计查询 / 导出接口（本阶段只写不读；ADR 未要求查询 API）
- 品牌资产存储与公开品牌接口（WP-02）
- Provider 元数据、密钥引用与连接测试（WP-03）
- Agent / 临床推理 / 模型客户端调用路径（未改一行）
- 管理端 UI（WP-04）
- 跨域部署实测（浏览器预检 + 白名单 Origin）

---

## 9. WP-01C 准入结论

**CONDITIONAL GO** —— 可以作为 WP-01C（发布与审批引擎）的基线，但下列三项必须先有结论：

1. **R-1 幂等语义**：发布端点是不可重复的动作，不能沿用「提交后记录」的写法。
   WP-01C 第一件事是把幂等改成占位先行，或在设计文档里明确接受重复发布并给出检测手段。
2. **R-5 升级路径**：V31 → V35 已在 CI 的真实 MySQL 8 上验过（新增用例；2026-11-24
   改号后为 V31 → V36，执行 5 条迁移）；
   发布前仍需在目标库副本上跑一次。
3. **R-7 部署配置**：开启控制中心前确认反代头与 `allowed-origins`，否则第一个写请求就会 403。

其余风险（R-2 / R-3 / R-4 / R-6 / R-8 / R-9）不阻塞 WP-01C，按表中处置执行。

合并前还差一条**验证口径**：pass 1/2 与新增套件已两轮全绿，pass 3 的
`GraphExecutionTest` 在本机两轮各挂一条（用例不同、都是 10s 有界等待）。本轮改动没有触碰
该类，但「与本轮无关」不等于「可以带红合并」——合并前需要在安静机器或 CI 上取一次
`303/303`，并把日志存证。

已具备的基线事实：V35 五表在真 MySQL 8 上可迁移、可写；草稿并发只有一个赢家；
成功审计与业务同事务、失败审计存活于回滚；默认关闭时端点一枚不注册、既有接口行为不变。

---

## 10. 逐文件变更

> 工作树里同时存在 WP-01A（已验收候选）与本轮 WP-01B 的未提交改动；
> 下表只列 **WP-01B 新增或本轮改动**的文件。WP-01A 的文件保持不变。

### 新增 — 后端主代码

| 文件 | 作用 |
|---|---|
| `config/ControlCenterProperties.java` | 部署级开关 / 白名单 / 代理信任 / 发布人名单，默认全关 |
| `security/CsrfTokenService.java` | Session 绑定 token：签发、复用、常量时间比对 |
| `security/SecurityEventSink.java` | 过滤器 → 审计的端口接口（避免反依赖） |
| `controlcenter/ControlCenterController.java` | 注册表、生效配置、草稿生命周期（5 个端点） |
| `controlcenter/ControlCenterCsrfController.java` | `GET /csrf`，`no-store`，仅管理员 |
| `controlcenter/CsrfTokenVO.java` | `{token, headerName}` |
| `controlcenter/security/ConfigAction.java` / `ConfigPermission.java` | 独立动作权限维度（READ/EDIT/VALIDATE/PUBLISH/ROLLBACK） |
| `controlcenter/security/ConfigPermissionAspect.java` | 注解执行器；40100 / 40301；拒绝留痕 |
| `controlcenter/security/ControlCenterCsrfFilter.java` | 写请求 CSRF 校验（`@Order(2)`） |
| `controlcenter/security/ControlCenterSecurityAudit.java` | `SecurityEventSink` 实现；匿名不触库 |
| `controlcenter/registry/{ConfigKey,ConfigType,ConfigRegistry,ConfigValueValidator}.java` | 6 个品牌键、schemaVersion=1、部署保护前缀、码点长度校验 |
| `controlcenter/service/ConfigPayloadCodec.java` | 规范化 JSON、SHA-256、请求摘要、默认值叠加 |
| `controlcenter/service/ControlCenterAuditService.java` | 同事务审计 + `REQUIRES_NEW` 失败的审计 + 脱敏 |
| `controlcenter/service/ControlCenterIdempotencyService.java` | 重放 / 记录 / 摘要不一致 40908 |
| `controlcenter/service/ControlCenterDraftService.java` | 草稿读取、创建、条件更新、校验 |
| `controlcenter/service/ControlCenterReadService.java` | 注册表与生效配置读取 |
| `controlcenter/model/entity/*.java`（5） | 五张表实体 |
| `controlcenter/model/enums/ConfigErrorReason.java` | 原因码 → `ErrorCode` 映射 |
| `controlcenter/model/dto/*.java`（2）/ `vo/*.java`（4） | 入参与响应 |
| `mapper/Config{Draft,Release,ActivePointer,Request,AuditEvent}Mapper.java` | 条件更新、指向查询、幂等查询 |

### 新增 — 迁移 / 前端 / 文档

| 文件 | 说明 |
|---|---|
| `src/main/resources/db/migration/V35__control_center_core.sql` | 五表 + 指针单例行（91 行） |
| `web/src/lib/csrf.ts` | token 获取 / 缓存 / 单飞 / 失效，`csrfRequest`（95 行） |
| `web/src/lib/controlCenterApi.ts` | 5 个端点类型化封装（154 行） |
| `docs/product/wp-01/wp-01b-persistence-and-config-api.md` | 本文件 |

### 修改

| 文件 | 改动 |
|---|---|
| `config/SameOriginFilter.java` | 控制中心严格 Origin 分支 + `SecurityEventSink` 留痕（WP-01A 主体，本轮接上 sink） |
| `config/CorsConfig.java` | `allowedHeaders` 放行 `X-CSRF-Token`（阶段 0 修复） |
| `exception/ErrorCode.java` | 40301、40900/40901/40902/40903/40904/40905/40908 |
| `src/main/resources/application.yml` | `eye-diagnosis.control-center.*` 配置块（默认关） |
| `src/test/java/.../migration/FlywayMySqlIntegrationTest.java` | 版本钉更新到 V32（2026-11-24 改号后为 V35/V36、迁移数 19、头 36）；空库断言加 5 张表；**新增 V31 → V32 升级用例**（改号后为 V31 → V36） |
| `src/test/java/.../EyeDiagnosisApplicationTests.java` | 默认关闭时 CSRF 过滤器与端点不注册 |
| `web/src/lib/http.ts` | 非 2xx 解析统一信封 |
| `docs/README.md` / `docs/architecture/migration-matrix.md` | 索引条目；V32 行、升级路径与「新迁移必须大于 V32」（2026-11-24 改号后为 V35 行、大于 V36） |



