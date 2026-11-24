# WP-01C 交付记录：配置发布审批、原子发布、回滚与一致性闭环

> 状态：**本地验收候选**——未提交、未推送、未合并、未部署
> 基线：`master` @ `5dc2eac5`；迁移头 **V33**（本轮新增；V1–V32 未改动）
> 编号变更（2026-11-24，合并前核对 `master c90b0caa`）：master 已到迁移头 **V34**，
> V32–V34 已被占用，本轮的 V33 先改名为 V36；同日发现 V35 与在途的小程序后端分支
> 撞号（PR #160 微信绑定表），再改名为 **V37**（配套的 V32 → **V36**），
> **DDL 内容一字未改**；本文其余 V32/V33 均指这两条迁移。
> 全局编号分配见 `docs/architecture/migration-matrix.md` §1.1。
> 设计裁决：`docs/product/wp-01/wp-01c-publish-approval-design.md`（编码前完成，本文只记录落地结果）
> 工作树：本分支（`feat/control-center-wp01`，原名 `t3/wp-00-5-baseline-audit`），承接 WP-01A / WP-01B 全部改动

---

## 1. 结论

| 项 | 结果 |
|---|---|
| 发布审批（申请 / 批准 / 拒绝） | 已实现，状态机 `PENDING → APPROVED \| REJECTED \| STALE` |
| 原子发布（Release + 指针 + 幂等 + 审计同事务） | 已实现；审计写失败时整条链回滚 |
| 当前 / 历史 Release 查询 | `GET /releases`、`GET /releases/{id}`、`GET /active`（`source=RELEASE`） |
| 回滚 | 已实现，**新建** release（`rollbackFromReleaseId`），不修改历史 |
| WP-01B R-1（幂等记录后补写） | 已修复：占位与回填都与业务写同事务，插入先行、唯一键仲裁 |
| Provider 命名空间 | 一律 `40911` 拒绝（不做热切换，不建 Provider 表） |
| 默认关闭 | 是：`eye-diagnosis.control-center.enabled=false` 时不注册端点；发布人白名单默认空 |
| 交付判定 | **CONDITIONAL GO**（见 §8：并发证据为单 JVM 双事务模拟，非真双实例压测） |

---

## 2. 四条设计裁决的落地位置

| 裁决 | 落地 |
|---|---|
| R-1：发布/回滚用数据库约束保障幂等，**不能**在业务提交后补写 | `ControlCenterIdempotencyService#reserve` 在业务事务内第一条写占位，撞唯一键即回读已提交行；`complete` 在同一事务回填。旧 `remember()/replay()` 已删除。草稿的 create/update/validate 一并切换（不再有第二套语义） |
| `requestId` 与内容指纹绑定、重复语义、并发处理中、异常恢复 | 摘要 = `SHA-256(method + path + canonical(body))`，由控制器计算后传入服务层。同号同摘要 → 重放；同号异摘要 → `40908`；「处理中」只存在于未提交事务里，读到 `resultCode<0` 是防御分支（`40900`）；失败回滚使 `requestId` 不被消耗 |
| 草稿 revision / 验证摘要 / 审批 / 发布快照的不可变绑定 | `t_config_publish_request` 钉住 `draftRevision + payloadDigest + validationDigest + expectedActiveReleaseId`；批准事务逐条重验，不符即 `STALE` |
| 发布事务的原子性 | 见 §3：幂等、release、指针、审批决定、成功审计同一事务；失效标记在**事务之外**补写（避免在同一行上与自身行锁互等） |

---

## 3. 状态机与事务顺序（最终实现）

### 3.1 审批单

```
PENDING ──approve（全部校验通过）──► APPROVED   （附 releaseId）
PENDING ──reject(reason)───────────► REJECTED
PENDING ──approve（绑定已变）──────► STALE      （审计 FAILED + 原因码）
APPROVED / REJECTED / STALE ──任何再决定──► 40909，状态不变
```

- 迁移只有两条：「批准」「拒绝」；「失效」不是用户动作，而是批准时发现绑定的内容
  已经变了。这与「作废」不同——它证明的是**审批单描述的内容已不存在**。
- STALE 是终态：即使草稿改回原样，这张审批单也不会复活（重新申请必须新号）。

### 3.2 批准事务（`ControlCenterReleaseTransactions#approve`）

```
BEGIN
 1  INSERT t_config_request（resultCode=-1）            -- 幂等占位；撞键 → 重放/40908 后结束
 2  SELECT t_config_publish_request … FOR UPDATE        -- 锁①：同一审批单串行
 3  非 PENDING → DecisionNotPendingException（40909）
 4  读草稿 + 重算 payloadDigest / validationDigest      -- 绑定校验
 5  绑定不符 → StaleApprovalException（事务回滚，锁释放）
 6  SELECT t_config_active_pointer … FOR UPDATE         -- 锁②：全局发布串行
 7  expectedActiveReleaseId 不符（NULL 安全）→ 40900
 8  SELECT COALESCE(MAX(releaseVersion),0)+1（锁内）→ INSERT t_config_release
 9  UPDATE 指针 WHERE releaseId <=> 期望值（0 行 → 40900）
10  UPDATE 审批 SET status='APPROVED', releaseId, decidedBy, decidedTime
11  UPDATE t_config_request 回填 ReleaseVO
12  INSERT 审计（PUBLISH_APPROVE, SUCCESS）
COMMIT
```

第 5 步的 STALE 标记不在本事务内（`REQUIRES_NEW` 会与外层行锁互等）：
编排层 `ControlCenterPublishService` 捕获 `StaleApprovalException`（此时事务已回滚、
锁已释放），再调用 `markStale`（`REQUIRES_NEW`，条件 `status='PENDING'`）+ 失败审计。

### 3.3 回滚事务

指针锁 → 期望值比较 → 读目标 release（存在性 / 已是当前版本 / schema 版本）→
`MAX+1` 插入新 release（复制 payload/digest/schemaVersion，写 `rollbackFromReleaseId`）→
条件切指针 → 回填幂等 → 审计。**没有任何 UPDATE t_config_release**。

---

## 4. 逐文件变更

### 4.1 新增

| 文件 | 说明 |
|---|---|
| `src/main/resources/db/migration/V37__control_center_publish_approval.sql` | 新增 `t_config_publish_request`（唯一键 `(actorId, requestId)`；两个查询索引） |
| `controlcenter/model/entity/ConfigPublishRequest.java` | 审批单实体 + 状态常量 |
| `controlcenter/model/dto/PublishRequestBody.java`、`ApprovalDecisionRequest.java`、`RollbackRequestBody.java` | 三个写请求体 |
| `controlcenter/model/vo/PublishRequestVO.java`、`ReleaseVO.java`、`PageVO.java` | 三个返回视图 |
| `controlcenter/service/ControlCenterReleaseTransactions.java` | 申请/批准/拒绝/回滚事务体 + `markStale`（REQUIRES_NEW） |
| `controlcenter/service/ControlCenterPublishService.java` | 编排：摘要计算、异常翻译、回滚后补写 STALE；审批列表/详情 |
| `controlcenter/service/StaleApprovalException.java`、`DecisionNotPendingException.java` | 两个内部信号（不映射 HTTP，由编排层翻译） |
| `mapper/ConfigPublishRequestMapper.java` | `selectForUpdate` / 条件 `decide` / 分页 |
| `src/test/java/.../ControlCenterPublishIntegrationTest.java` | 17 个真 MySQL 用例：原子发布、绑定复核、回滚、并发三组、失败注入 |
| `src/test/java/.../ControlCenterPublishAcceptanceTest.java` | 6 个 RANDOM_PORT 用例：白名单、CSRF、HTTP 全链路 |
| `web/src/lib/controlCenterApi.test.ts` | 4 个客户端契约用例（CSRF 头、Long 归一化、请求体） |
| `docs/product/wp-01/wp-01c-publish-approval-design.md` | 设计裁决 |
| `docs/product/wp-01/wp-01c-publish-approval-delivery.md` | 本文件 |

### 4.2 修改

| 文件 | 变更 |
|---|---|
| `controlcenter/service/ControlCenterIdempotencyService.java` | 重写为 `reserve` / `complete` / `requireRequestId`；删除 `replay` / `remember` |
| `controlcenter/service/ControlCenterDraftService.java` | 三个写方法改为事务内 `reserve`→业务→`complete`；签名加 `requestDigest` |
| `controlcenter/service/ControlCenterReadService.java` | 新增 `releases(page,pageSize)` / `release(id)` 与 `toReleaseVO` |
| `controlcenter/ControlCenterController.java` | 新增 8 个端点；草稿写改为传摘要；删除控制器内的 `idempotent(...)` 包装 |
| `mapper/ConfigReleaseMapper.java` | 新增 `selectMaxVersion` / `page` / `countAll` |
| `mapper/ConfigActivePointerMapper.java` | 新增 `selectForUpdate` / `switchPointer`（`<=>` NULL 安全条件更新） |
| `mapper/ConfigRequestMapper.java` | 新增 `selectByActorAndRequestForUpdate` / `complete` |
| `exception/ErrorCode.java` | 新增 40909 / 40910 / 40911 |
| `controlcenter/model/enums/ConfigErrorReason.java` | 新增三个原因码（错误码与审计原因共用） |
| `controlcenter/registry/ConfigValueValidator.java` | Provider 命名空间（`provider` / `provider.*` / `providers*`）→ `PROVIDER_NOT_SUPPORTED`；未知键判定排除 Provider 命名空间 |
| `src/test/java/.../ControlCenterPersistenceIntegrationTest.java` | 跟进新签名；幂等用例改为服务级真实事务；补 `t_config_publish_request` 清理与表清单 |
| `src/test/java/.../ControlCenterAcceptanceTest.java` | 清理/收尾补 `t_config_publish_request` |
| `src/test/java/.../FlywayMySqlIntegrationTest.java` | 迁移头 32→33、迁移数 15→16、空库表清单 + 审批表、V31 升级用例改为「差两条」并新增审批表空表断言（2026-11-24 改号后：迁移头 33→36、迁移数 16→19、「差五条」） |
| `web/src/lib/controlCenterApi.ts` | 新增发布/审批/拒绝/回滚/版本查询 7 个函数与类型 |
| `docs/architecture/migration-matrix.md` | V33 行、链与升级路径、回滚方向、新迁移必须大于 V33（2026-11-24 改号后为 V37 行、大于 V37） |
| `docs/product/wp-00/mvp-api-contract.md` | §6 WP-01C 增量：40909–40911、审批路径、回滚语义 |
| `docs/README.md` | WP-01C 设计 + 交付索引 |

**未改动**：`V1`–`V32` 任何迁移脚本（`V33` 本身除外）、`application.yml` 的控制中心默认值、`@AuthCheck` 语义、
Spring AI / Agent / Python 推理调用路径、`web/` 页面层。

---

## 5. 并发证据

`ControlCenterPublishIntegrationTest`（真 MySQL，两个线程各自独立事务）：

| 用例 | 断言 |
|---|---|
| `twoInstancesDecidingTheSameTicketProduceExactlyOneRelease` | 恰好一个成功；release 计数 1；审批 `APPROVED`；`PUBLISH_APPROVE/SUCCESS` 审计 1 条 |
| `twoApprovalsBoundToTheSameActiveVersionCannotBothPublish` | 恰好一个成功；release 计数 +1；输掉的那张审批单仍是 `PENDING`（按 id 判定） |
| `concurrentPublishAndRollbackCannotBothWin` | 恰好一个成功；只多一个 release；指针仍指向合法 release |

失败侧看到的错误码：同一审批单 → `40909`；同期望活跃版本 → `40900`（审批单保持 PENDING，
不让审批人误以为内容失效）。

---

## 6. 失败恢复证据

`publishAuditFailureRollsBackEverythingAndStaysRetryable`：

1. 用 `@MockitoSpyBean` 包住 `ControlCenterAuditService`，让**第一次**成功审计写入抛
   `DataIntegrityViolationException`（ADR D4：没有审计就没有发布）；
2. 批准调用抛异常后断言：`t_config_release` 0 行、活跃指针仍为 `NULL`、审批单仍 `PENDING`；
3. 第二次用**同一个** `requestId` 重试成功——证明幂等占位随事务回滚、号没有被消耗。

补充：`approvalWhoseActiveVersionMovedIsRejectedWith40900AndStaysPending` 证明版本冲突不会把审批单
误标成 `STALE`；`oldApprovalCannotPublishADraftEditedAfterwards` 证明失效路径写 `STALE` + `FAILED` 审计。

> 未做「指针条件更新 0 行」的注入：该分支要求「持锁读到的期望值在锁内被改」，而指针行锁
> 本身已串行化发布与回滚，正常路径不可达。它保留为纵深防御；其前置条件由三组并发用例覆盖。

---

## 7. 验证报告

见 §7 末尾的「本轮实测」小节（命令、时间、结果、失败逐条）。规则遵循
`docs/testing/verification-levels.md`：受影响面含迁移、权限、真实事务 → L2 起；交付候选 → L3。

### 7.1 本轮实测（2026-11-23，基线 `5dc2eac5` + 未提交改动）

| 命令 | 范围 | 结果 |
|---|---|---|
| `scripts/verify-fast.sh --java ControlCenterPublishIntegrationTest … --db`（5 个类） | 配置中心全部 Java 门禁 | **PASS**，58 个用例（Publish 18 / PublishAcceptance 6 / Persistence 17 / Acceptance 11 / Flyway 6），116s |
| `scripts/verify-fast.sh --web src/lib/controlCenterApi.test.ts` | 新增客户端契约 | **PASS**，9s |
| `npm run typecheck` / `npm run lint` / `npm run build`（`web/`） | 前端静态门禁 | 全部通过（构建仅保留既有的 chunk 体积警告） |
| `npm test`（`web/` 全量） | 前端全套 | **PASS**，110 个文件 / **1184** 个用例（本轮 +4） |
| `scripts/verify.sh` 第 1 轮 | L3 全量（3 pass） | pass 1：996 用例 0 失败（304 跳过为设计内）；pass 2：MySQL 门禁 BUILD SUCCESS；pass 3：303 用例 **1 失败**——见下方 flake |
| `scripts/verify.sh` 第 2 轮（同一棵树） | L3 全量（3 pass） | **三轮全绿**：996 / 0 / 304 跳过；MySQL 门禁 SUCCESS；303 / 0 |

**关于第 1 轮 pass 3 的那 1 个失败（不隐藏、不粉饰）**：

```
com.csust.eyediagnosis.collaboration.GraphExecutionTest
  .databaseLeaseSurvivesHostClockSkew(int)[2]
  → heartbeatRenewsLeaseAndConcurrentClaimDoesNotDispatchProviders
  "Bounded condition did not become true"（10.76s 有界等待超时）
```

- 该类**本轮未被改动**（`git status` 里无 `collaboration/` 任何文件），失败点在租约心跳与
  主机时钟偏移的有界等待上；
- 同一棵树紧接着跑第 2 轮，该类 37/37 全绿（105.7s），说明是环境/时钟敏感而非确定性缺陷；
- WP-01B 交付时也记录过同一类（不同用例）的同类现象，仓库既有登记见
  `docs/release/verification-matrix.md` §2.3 / §2.8；
- **结论：本轮不把它算作通过，也不算作 WP-01C 的回归**。合并前应在安静机器上重取一次
  pass 3 的证据（第 2 轮已满足，但需要在最终合并树上再确认）。

### 7.2 迁移（真实 MySQL 8）

- 空库：V1–V9 + V15–V37 全量迁移，`FlywayMySqlIntegrationTest` 断言迁移头 `36`、表清单含
  `t_config_publish_request`；
- master V17 库 → V37：迁移数 19，附 `t_user` 既有账号保留断言；
- **V31 库 → V37**：执行 V32–V37 五条（V32–V34 是 master 的迁移，V36、V37 是配置中心两条），
  活跃指针单例行存在且 `releaseId IS NULL`，
  审批表为空表（`upgradesAnExistingV31SchemaToTheControlCenterSchema`）；
- 回滚方向：旧 jar（只认到 V17）在 V37 history 上仍可启动（`FUTURE_SUCCESS`），但不作为回退候选——该用例只证明迁移解析放行，不证明旧产物安全。

---

## 8. 剩余风险

| 编号 | 风险 | 处置 |
|---|---|---|
| R-1 | 并发证据是「单 JVM 双线程 + 两个独立事务」，不是两个真实进程；MySQL 行锁语义相同，但没有覆盖连接池耗尽、网络分区、实例时钟差 | 合并前建议在预发环境用两个进程做一次手工竞争复验；本轮不伪造该证据 |
| R-2 | 申请与批准可以是同一个管理员（单发布人部署可用） | 四眼原则属后续 WP；如需强制，加一条「申请人 ≠ 决定人」校验即可，但不能在本轮默认打开（会锁死只有一个发布人的部署） |
| R-3 | `markStale` 依赖「回滚已发生」这一时序；若将来有人把它挪进事务方法内部，会在审批行上自锁 | `ControlCenterReleaseTransactions` 的类注释与设计 §4.4 写明了原因；`markStale` 使用条件更新，重复调用幂等 |
| R-4 | `validationDigest` 依赖 `validationResult` 的规范化 JSON；若将来把校验结果改成非 JSON 字符串，摘要口径要同步迁移 | 摘要口径集中在 `validationDigest()` 一处，且只有在审批绑定时才被比较 |
| R-5 | 发布历史/审批列表没有归档与保留策略 | 属运维能力，未在本轮范围；表规模增长后需要分页 + 归档，不影响正确性 |
| R-6 | 没有「发布后自动回滚」的熔断（例如指标异常） | 明确属于后续 WP；本轮只提供人工回滚，且回滚同样受幂等与并发保护 |
| R-7 | 前端只提供 API 客户端，没有发布工作台 UI | WP-04 范围；本轮交付「后端能力 + 前端调用管道」，避免出现「后端有端点、前端只会 403」 |
| R-8 | `GET /releases` 没有过滤「当前活跃」参数 | 前端可用 `GET /active` 拿当前版本；列表页按 `releaseVersion` 倒序即可判断 |
| R-9 | L3 pass 3 的 `GraphExecutionTest#databaseLeaseSurvivesHostClockSkew[2]` 在两轮中一红一绿（同树同环境） | 与本轮改动无关（该类未改），属主机时钟/租约心跳敏感；合并前在安静机器上重取 pass 3 证据，不得用「第二轮绿了」代替最终合并树的实测 |

---

## 9. 范围外（明确未做）

Provider 元数据与热切换（WP-03）、品牌资产上传（WP-02）、管理端发布工作台（WP-04）、
release diff、审计查询接口、四眼原则强制、发布熔断、历史归档。

---

## 10. 对下一步的准入结论

- WP-02（品牌资产）可以在本能力之上叠加「资产 ID」字段，但必须先补齐 Provider/资产引用
  的注册表项与校验，否则 `ASSET_REF_INVALID` 之外没有可用的引用校验。
- WP-03 若要实现 Provider，必须替换 `ConfigValueValidator#isProviderNamespace` 的拒绝分支
  与 `40911`，并新增 Provider Revision 表；不要在今天的基础上「先放行再补校验」。
- WP-04 的前端工作台需要：`GET /publish-requests`（按状态分页）、`GET /releases`、
  以及三个写动作；错误码 `40909/40910/40900` 各有不同的 UI 处置（刷新审批单 / 重新申请 /
  刷新线上版本），不要把三者合并成一条「操作失败」。

---

## 11. WP-01D 追认（2026-11-23，追加，不改上文）

> 独立审计、跨进程验证与最终验收见 [`wp-01d-final-audit.md`](wp-01d-final-audit.md)。
> 本节只记录 WP-01D 对本文已交付内容的**追认与增量**，上文读数保持为当时快照。

- **§2 裁决、§3 状态机、§3.2 事务顺序：全部追认**。跨两个独立 JVM 的实测（同一审批单竞争、
  同期望活跃版本竞争、发布 vs 回滚、同号重放、同号异内容）结果与单 JVM 用例一致。
- **§6 失败恢复：追认，并补两条真实故障**：进程在事务中途被 `destroyForcibly()` 强杀后，
  release / 指针 / 审批状态三者都没有半发布，`requestId` 未被消耗；锁等待超时同样不产生
  半发布且号可重用。
- **增量 1（错误码）**：新增 `40906 CONFIG_CONCURRENT_CONFLICT`。WP-01C 时锁等待超时与死锁
  会落成 `50000 系统错误`；WP-01D 改成可重试的 40906（只作用于控制中心两个控制器）。
- **增量 2（输入边界）**：`requestId` 上限 64（按码点）在写库前以 `40000` 拒绝。
- **增量 3（契约澄清）**：「批准即发布」、`40900` 不改变审批状态（A-04 僵尸 PENDING）、
  「每个动作必须用不同的 `requestId`」——三条已写入 PRD §11.1 / ADR D13 / API 契约 §7。
- **§8 风险表**：R-9（L3 pass 3 的 `GraphExecutionTest` 时钟敏感 flake）仍开放，见 WP-01D §6。
