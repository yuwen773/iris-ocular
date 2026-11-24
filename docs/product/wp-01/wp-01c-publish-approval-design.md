# WP-01C 设计裁决：发布审批、原子发布、回滚与一致性闭环

> 基线：`origin/master` @ `5dc2eac5`；迁移头 **V32**（本轮新增 V33，V1–V32 不动）
> 编号变更（2026-11-24，合并前核对 `master c90b0caa`）：master 已到迁移头 **V34**，
> V32–V34 已被占用，本轮的 V33 改号为 **V36**（配套的 V32 改号为 V35），
> **DDL 内容一字未改**；本文其余 V32/V33 均指这两条迁移。
> 前置：`docs/product/wp-00/config-center-adr.md`（D2–D4、D6、D12）、
> `docs/product/wp-00/mvp-api-contract.md`、WP-01B 交付记录
> 状态：本地验收候选，未提交、未推送、未合并、未部署

本文件只写**本轮新增的裁决**与对既有裁决的落点；不重写 WP-00 结论。
编码以本文件为准，不从 Controller 反推事务规则。

---

## 1. 幂等：插入先行，数据库唯一键仲裁（修复 R-1）

### 1.1 问题

WP-01B 的 `t_config_request` 是「业务提交之后再补写」，存在崩溃窗口：业务已提交、
幂等记录未写入时，同一 `requestId` 重试会再执行一次。对草稿写入只是多一条 `40900`，
对发布就是**重复发布**。

WP-01B 当时的注释认为「在业务事务内先占位再回填，会让一次重复提交连带回滚业务写」。
这个前提是错的：能在唯一键上撞到的行，一定来自**另一个已经提交的**事务；此时回滚
本事务正是我们要的结果，而不是损失。

### 1.2 裁决

`requestId` 的幂等记录**与业务写同事务**，且是事务内的第一条写：

```
BEGIN
  INSERT INTO t_config_request (actorId, requestId, requestDigest, action, resultCode=-1, resultPayload=NULL)
      -- 唯一键 (actorId, requestId) 就是判重点
  业务写（release / pointer / approval / draft …）
  UPDATE t_config_request SET resultCode=0, resultPayload=<首次结果>
  INSERT INTO t_config_audit_event (… SUCCESS …)
COMMIT
```

| 情形 | 结果 |
|---|---|
| 本事务是首次 | 占位成功 → 执行业务 → 回填结果 → 提交，一次原子落库 |
| 并发同 `requestId` 的第二个事务 | 插入阻塞在唯一索引上；第一个提交后拿到重复键 → 读已提交行 |
| 已提交行摘要相同 | **重放**：直接返回其 `resultPayload`，不执行业务、不产生第二条 release |
| 已提交行摘要不同 | `40908 CONFIG_IDEMPOTENCY_MISMATCH` |
| 第一个事务失败/回滚 | 占位一并回滚 → 第二个事务插入成功并真正执行（失败不消耗 `requestId`） |
| 崩溃在提交之前 | 事务整体回滚，无半成品 |
| 崩溃在提交之后、响应之前 | 行里有完整结果 → 重试命中重放 |

「处理中」状态只存在于**未提交**的事务里（其它连接看不见），因此不需要额外的
in-progress 表或锁等待超时处理。读到的已提交行 `resultCode=-1` 在构造上不可能出现；
代码里保留防御分支（返回 `40900`，提示稍后重试）并打 warn，不把它说成正常路径。

**一条已提交但结果载荷为 NULL 的行同样不可达**：占位与回填在同一事务。

### 1.3 摘要绑定

`requestDigest = SHA-256(method + "\n" + path + "\n" + canonical(body))`，其中
`canonical` 是本仓库唯一的规范化 JSON 口径（键字典序、紧凑输出）。同一 `requestId`
换内容一律 `40908`；不同 `requestId` 走各自的幂等槽位。

### 1.4 落点

- `ControlCenterIdempotencyService` 重写为 `reserve(...)`（同事务插入；返回可选重放）
  + `complete(...)`（同事务回填）。
- 草稿的 `create/update/validate` 一并切到同一机制：控制器不再在事务外「先查重放、
  后补记录」，改为把摘要传进服务层，由服务层在事务内 reserve。
- 旧 `remember()`（REQUIRES_NEW 补写）删除，不留第二套语义。

---

## 2. 绑定链：草稿 revision → 验证摘要 → 审批 → 发布快照

发布内容不能再从「当时的草稿」现取，否则审批与发布之间有人改了草稿，就会把
**未经审批的内容**发出去。因此审批请求在创建时就把内容钉死：

| 层 | 字段 | 含义 |
|---|---|---|
| 草稿 | `revision` | 单调递增 |
| 草稿 | `validationRevision` + `validationResult` | 校验结论只对某个 revision 成立 |
| 审批请求 | `draftRevision` = 申请时的 `draft.revision` | 旧审批不能发布新草稿 |
| 审批请求 | `payloadDigest` = SHA-256(canonical(draft.payload)) | 内容指纹 |
| 审批请求 | `validationDigest` = SHA-256(`"rev:" + validationRevision + "\|result:" + canonical(validationResult)`) | 验证摘要指纹 |
| 发布快照 | `payload` / `digest` / `schemaVersion` | 自包含、不可变；`digest == approval.payloadDigest` |
| 发布快照 | `rollbackFromReleaseId` | 回滚来源（回滚生成的也是新 release） |

**审批执行时逐条重验**（任一不成立即拒绝）：

1. `approval.status == PENDING`，否则 `40909`；
2. `draft.revision == approval.draftRevision` 且
   `SHA-256(canonical(draft.payload)) == approval.payloadDigest`；
3. `draft.status == VALIDATED` 且 `draft.validationRevision == draft.revision`，
   且验证摘要与 `approval.validationDigest` 相同；
4. `currentPointer.releaseId` 与 `approval.expectedActiveReleaseId` 相等（NULL 安全）。

2、3 的顺序是语义的一部分：先判 `draft.revision != approval.draftRevision`（或摘要不符）
即 `40910 APPROVAL_STALE`——草稿一旦推进，这张审批单唯一正确的归宿就是失效；
再判「同 revision 但状态被改回未校验」（外部改库 / 异常恢复）→ `40901`/`40902`。
反过来先判校验状态，会把「内容已经变了」伪装成「未校验」，让审批人查错方向。

2、3 任一不成立时，把审批置为 `STALE` 并记 `FAILED` 审计 —— 这条审批**永久失效**，
不会在草稿改回原文件后又被拿来用。
4 不成立时保持 `PENDING` 返回 `40900`：指针可能因并发发布变化，也可能因回滚回到过去，
让审批人决定重新申请还是作废。

---

## 3. 状态机

### 3.1 审批请求 `t_config_publish_request`

```
            ┌──────────── reject(reason) ───────────► REJECTED（终态）
   PENDING ─┤
            ├──────────── approve：全部校验通过 ────► APPROVED（终态，附 releaseId）
            └── approve：草稿/验证已变 ─────────────► STALE（终态，审计 APPROVAL_STALE）
```

- 只允许 `PENDING → *`；`APPROVED/REJECTED/STALE` 上的任何新决定都是 `40909`。
- 终态不可回退：重新发布必须新建审批请求。
- 审批请求不随草稿变化自动作废；它在被审批时才发现过期（读路径不做后台扫描）。

### 3.2 Release

只插入：`PUBLISH`（来自 APPROVED 审批）与 `ROLLBACK`（来自历史 release）。
两者都写 `t_config_release`，回滚额外写 `rollbackFromReleaseId`。没有更新路径。

### 3.3 活跃指针

单行 `id=1`，只由发布/回滚事务切换；切换条件是「读到的旧值仍等于期望值」，
0 行受影响即 `40900`。

---

## 4. 事务顺序与原子性

### 4.1 发布（approve）

```
BEGIN
1. INSERT t_config_request（占位；重复则重放/40908，事务结束）
2. SELECT t_config_publish_request … FOR UPDATE      -- 锁 ① 审批行
3. SELECT t_config_draft / 重算 payloadDigest、validationDigest   -- 绑定校验（§2）
4. SELECT t_config_active_pointer … FOR UPDATE       -- 锁 ② 指针行
5. 比对 expectedActiveReleaseId                        -- 不匹配 → 40900
6. SELECT COALESCE(MAX(releaseVersion),0)+1（指针锁内，天然串行）
   INSERT t_config_release
7. UPDATE t_config_active_pointer SET releaseId=? WHERE id=1 AND releaseId <=> ?
8. UPDATE t_config_publish_request SET status='APPROVED', releaseId=?, decidedBy=?, decidedTime=?
9. UPDATE t_config_request SET resultCode=0, resultPayload=<ReleaseVO>
10. INSERT t_config_audit_event (PUBLISH_APPROVE, SUCCESS, before/after version, requestId)
COMMIT
```

**原子性**：6–10 在同一事务；任一步失败（含审计写失败）整体回滚——
不会出现「release 已插入但指针没切换」「指针切了但审计缺失」「审批已批准但 release 不存在」
的任何组合。这正是 ADR D4「禁止配置已发布但没有成功审计」的机械保证。

**锁顺序**：审批行 → 指针行 → release 唯一索引。回滚事务只取「指针行」，
拒绝事务只取「审批行」，因此不存在反向持锁。

### 4.2 回滚

```
BEGIN
1. INSERT t_config_request（占位）
2. SELECT t_config_active_pointer … FOR UPDATE
3. 比对 expectedActiveReleaseId → 40900
4. SELECT t_config_release WHERE id = 目标 → 不存在 40400；等于当前指针 → 40900（无操作）
5. INSERT t_config_release（payload/digest/schemaVersion 复制，rollbackFromReleaseId=目标）
6. UPDATE 指针（条件更新，含 NULL 安全比较）
7. UPDATE t_config_request 结果
8. INSERT 审计（ROLLBACK，beforeVersion=旧 releaseVersion，afterVersion=新）
COMMIT
```

### 4.3 拒绝 / 申请

- 拒绝：占位 → 锁审批行 → `PENDING` 校验 → `REJECTED` + reason → 回填 → 审计。
- 申请：占位 → 读草稿（不锁，随后在审批时再验）→ 写审批行 → 回填 → 审计。
  申请阶段只做「此刻合法」的检查，真正的绑定校验在审批事务里重做一遍。

### 4.4 为什么 STALE 标记发生在发布事务<b>之外</b>

第 3 步发现绑定失效时，本事务持有审批行锁（第 2 步）。若此时用 `REQUIRES_NEW`
去改同一行的 `status`，内层事务会等外层释放锁、外层又在等内层提交——直接自锁。

因此实现上分成两层：

| 类 | 职责 | 事务 |
|---|---|---|
| `ControlCenterReleaseTransactions` | 所有业务写 | `@Transactional`，失败整体回滚 |
| `ControlCenterPublishService` | 算摘要、翻译异常、**回滚之后**补写 STALE 与失败审计 | 无事务；只调用上面的事务方法 |

绑定失效时事务方法抛 `StaleApprovalException`，Spring 完成回滚并释放行锁，
编排层再调用 `markStale(...)`（`REQUIRES_NEW`）把审批单条件更新为 `STALE`
（`WHERE status='PENDING'`：并发批准已经成功的场景下不覆盖它的终态）并写 `FAILED` 审计。

两个内部异常按语义分开：`StaleApprovalException`（内容变了，写 STALE，40910/40901/40902）
与 `DecisionNotPendingException`（别人已经决定了，不改状态，40909）。

---

## 5. 并发与两实例竞争

- **指针行是发布路径的唯一串行化点**。`SELECT … FOR UPDATE` 把它变成互斥锁，
  `releaseVersion = MAX+1` 因此在锁内计算，不会出现两个版本号相同。
- 条件更新 `WHERE releaseId <=> #{expected}` 是第二道闸：即便有人绕过锁顺序，
  0 行受影响也会 `40900` 而不是静默覆盖。
- 两个实例同时审批不同审批单：都拿指针锁 → 串行；后者读到新指针，
  `expectedActiveReleaseId` 不匹配 → `40900`，只产生**一个** release。
- 两个实例同时审批同一审批单：审批行 `FOR UPDATE` 串行；后者看到 `APPROVED` →
  `40909`；若两请求同 `requestId`，后者在步骤 1 就命中重放。
- 发布与回滚并发：同一把指针锁，结果只可能是其中之一。

单 JVM 内用两个线程 + 两个独立事务（Spring 代理按线程绑定连接）模拟两实例，
证据见 `ControlCenterPublishIntegrationTest` 的三组并发用例
（同审批单 / 同期望活跃版本的两张审批单 / 发布 vs 回滚）。

---

## 6. 错误码（新增）

| 码 | 名称 | 场景 |
|---|---|---|
| 40909 | `CONFIG_APPROVAL_NOT_PENDING` | 对已批准/已拒绝/已失效的审批再做决定 |
| 40910 | `CONFIG_APPROVAL_STALE` | 审批绑定内容与当前草稿/验证不一致 |
| 40911 | `CONFIG_PROVIDER_NOT_SUPPORTED` | 载荷引用 Provider（`provider.*` / `providers*`）；WP-03 未实现，一律拒绝 |

复用：`40900`（revision / expectedActiveReleaseId / 已是当前版本）、
`40901`（未校验）、`40902`（校验过期）、`40908`（幂等摘要不符）、`40400`（release 不存在）。

> 40909–40911 是 WP-01C 对契约错误码表的**扩展**（契约 §错误码 原先列到 40908），
> 已在 `mvp-api-contract.md` 的勘误节登记，不覆盖历史表。

---

## 7. API

前缀 `/api/admin/control-center`，全部 `@ConditionalOnProperty`（默认关闭）。

| 方法 | 路径 | 动作权限 | 说明 |
|---|---|---|---|
| POST | `/drafts/{id}/publish-requests` | `EDIT` | 申请发布：`{revision, expectedActiveReleaseId, requestId, note?}` |
| GET | `/publish-requests` | `READ` | `?status=PENDING\|APPROVED\|REJECTED\|STALE&page&pageSize` |
| GET | `/publish-requests/{id}` | `READ` | 单条 |
| POST | `/publish-requests/{id}/approve` | `PUBLISH` | `{requestId}` → 原子发布，返回 `ReleaseVO` |
| POST | `/publish-requests/{id}/reject` | `PUBLISH` | `{requestId, reason}` |
| GET | `/releases` | `READ` | `?page&pageSize`，按 `releaseVersion` 倒序 |
| GET | `/releases/{id}` | `READ` | 单版本快照 |
| POST | `/releases/{id}/rollback` | `ROLLBACK` | `{expectedActiveReleaseId, requestId, reason}` → 新 release |

`GET /active` 现在是真实语义：有发布则返回 `source=RELEASE` + release 载荷 + digest；
从未发布则 `source=DEFAULT` + 代码默认值（WP-01B 行为不变）。

**与契约的差异（必须登记）**：契约 §发布 写的是 `POST /drafts/{id}/publish` 直发。
本轮按 WP-01C 指令引入审批闸口，直发端点**不实现**；等价能力 = 申请 + 审批。
审批人与申请人可以是同一管理员（单发布人部署不被死锁）；四眼原则留给后续 WP。

---

## 8. 权限

- 申请：`EDIT`（任意管理员）；审批/拒绝：`PUBLISH`；回滚：`ROLLBACK`（后两者要求
  `userId ∈ publisher-user-ids`，默认空 = 没有人能发布）。
- 所有写端点仍经 Origin 严格校验 + CSRF（WP-01A 管道，前端 `csrfRequest` 复用）。
- 不扩展 `@AuthCheck`；40301 与 40101 的区分不变。

---

## 9. 验收映射（逐条对应本轮验收重点）

| 验收要求 | 证明方式 |
|---|---|
| 重复发布不重复生效 | 同一 `requestId` 连续审批两次、并发审批两次：release 计数 +1，第二次返回同一 `ReleaseVO` |
| 不同负载复用 requestId 被拒绝 | 同 `requestId` 改 `note`（同路径同 revision）→ `40908`，审批单不新增 |
| 旧审批不能发布新草稿 | 申请@rev1 → 改草稿@rev2 → 审批 → `40910` + 审批 `STALE` + 无 release |
| 两个实例竞争只有合法结果 | 双线程独立事务：同审批单（一成功一 40909）/ 同期望活跃版本的两张审批单（一成功一 40900，输者仍 PENDING）/ 发布 vs 回滚（只一个成功） |
| 失败不会造成半发布 | 审计写失败注入：release、指针、审批状态、幂等记录四方都回到发布前；同一 `requestId` 重试可成功（证明失败不消耗号） |
| 指针条件更新 0 行 | 不走注入：该分支要求「持锁读到的期望值在锁内被改」，而指针锁本身已串行化发布/回滚，正常路径不可达；由并发用例覆盖其前置条件，代码分支保留为纵深防御 |
| 回滚产生新 Release | 回滚后 release 计数 +1、`releaseVersion` 取新号、`rollbackFromReleaseId` 指向来源，原 release 逐字段不变 |
| 审计不丢失成功记录 | 每类成功动作断言审计行（action/result/target/requestId）；失败/失效/拒绝各有 `FAILED` 行 |
| 非管理员无法越权发布 | 非管理员拿不到 CSRF token、写端点被拒；管理员但不在白名单 → `40301` + `DENIED` 审计 |

---

## 10. 范围外（明确不做）

Provider 元数据与热切换（WP-03）、品牌资产（WP-02）、管理端 UI（WP-04）、
`GET /releases/{id}/diff`（契约里有，但属于展示层能力，本轮不实现）、
审计查询接口、四眼原则强制、release 归档清理。
