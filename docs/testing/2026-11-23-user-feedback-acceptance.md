# 用户反馈（UserFeedback）验收 · 2026-11-23

分支 `t3/add-feedback-feature`（基线 `c90b0caa` = master；本分支已 rebase 到 master，
提交 `b3f5bc71`）。
实施计划见 `docs/plans/2026-11-23-user-feedback-plan.md`。

## 1. 范围与边界

一条闭环最短的意见通道：

```
登录用户提交（可选关联一次判读 / 一个 Agent 会话，可选一张截图）
  → 「我的反馈」看到状态与管理员回复
  → 管理端台账筛选、状态流转、回复
```

**反馈不是医疗记录**（`GLOSSARY.md`「三个『反馈 / 意见』不要混用」）：
不进入判读统计、复核队列与数据分析口径，也不提供任何改写医学结论的动作。
代码标识一律带 `User`，与已冻结的 Critic 反馈 `agent.feedback.requested` 区分。

## 2. 数据模型与迁移

- 迁移：`V38__user_feedback.sql`（只新增表，不改任何既有表）。
- 表：`t_user_feedback`。列名小驼峰（`map-underscore-to-camel-case: false`）。
  正文 `TEXT`（`VARCHAR(64K)` 在 MySQL 里不成立）；无 `CHECK`（3306 是 5.7，会解析并忽略）。
- 索引：`(userId, createTime)`、`(status, createTime)`、`(targetType, targetId)`。
- 截图只存 COS 私有对象 key（`feedback/{userId}/...`）与格式，不存公开 URL；
  回读走鉴权端点现签 5 分钟链接。
- 逻辑删除 `isDelete`（`@TableLogic`）。

## 3. 接口

| 端点 | 守卫 | 说明 |
|---|---|---|
| `POST /api/feedback/attachment` | 登录 + 开关 | 上传截图（≤2 MB，jpeg/png），返回 object key；开关关闭时同样回 40300 |
| `POST /api/feedback` | 登录 | 提交；`targetType`/`targetId` 必须同时出现、属于本人，截图 key 必须落在本人前缀下且对象真实存在 |
| `POST /api/feedback/mine` | 登录 | 我的反馈分页（服务端恒按当前用户过滤） |
| `GET /api/feedback/{id}` | 登录 | 详情：本人或管理员 |
| `GET /api/feedback/{id}/attachment` | 登录 | 本人/管理员现签截图链接 |
| `POST /api/admin/feedback/page` | admin | 台账分页（分类 / 状态 / 正文关键词 / 时间窗 / 白名单排序） |
| `GET /api/admin/feedback/pending-count` | admin | 待处理数 |
| `GET /api/admin/feedback/{id}` | admin | 详情 |
| `POST /api/admin/feedback/{id}/status` | admin | 状态流转（可顺带回复） |
| `POST /api/admin/feedback/{id}/reply` | admin | 回复用户 |
| `GET /api/admin/feedback/{id}/attachment` | admin | 截图链接 |

鉴权两层：`@AuthCheck`（管理端 `mustRole = admin`）是入口守卫；
`UserFeedbackLifecycleService` 的 `requireUser / requireAdmin / requireVisible` 是「换调用方也不会漏」的那一道。

## 4. 状态机

| 当前 | 可迁往 |
|---|---|
| `PENDING` | `PROCESSING` / `RESOLVED` / `IGNORED` |
| `PROCESSING` | `PENDING` / `RESOLVED` / `IGNORED` |
| `RESOLVED` | `PROCESSING`（重新打开） |
| `IGNORED` | `PENDING`（重新打开） |

- 条件更新（`WHERE id = ? AND status = 旧值`）：影响行数 0 → `40900 USER_FEEDBACK_CONFLICT`，界面提示刷新后重试，不覆盖别人的处理结果。
- 标记 `RESOLVED` 前必须已有回复（新写或库内已有）。
- 已结案（`RESOLVED` / `IGNORED`）不能直接追加回复，先重新打开。
- 前端 `web/src/lib/userFeedbackDomain.ts` 镜像同一张矩阵，只决定「按钮能不能点」；后端仍是裁决者。

## 5. 前端

| 路由 | 场景 | 文件 |
|---|---|---|
| `/feedback` | Clinical | `web/src/pages/FeedbackPage.tsx` + `web/src/styles/feedback.css` |
| `/admin/feedback` | Management | `web/src/pages/admin/AdminFeedbackPage.tsx` + `web/src/styles/admin-feedback.css` |

- 管理端侧栏第 07 项「意见反馈」（`AdminNav.tsx`，归入「业务管理」）。
- 个人中心底部新增「我的反馈」入口（顶栏不放：顶栏只放跨产品目的地）。
- 「关联对象 → 判读任务」下拉取自 `/diagnosis-tasks`（`taskId`）。**不是**
  `/diagnosis/list/page/my`：那个接口给的是 `t_diagnosis.id`，与服务端比对的是
  `t_diagnosis_task.taskId`，两者不是同一个键。
- 查询键：`feedback.mine(scope, q)` / `admin.feedback(q)` 等；`feedback` 已加入
  `PRIVATE_QUERY_ROOTS`（换账号整域作废）。
- 状态永远带文字标签（`RiskTag`）；颜色只作辅助。
- 新增两个样式文件**零硬编码色**（`hardcoded-colors.test.ts` 棘轮通过）。

## 6. 开关与限流

- `eye-diagnosis.feedback.enabled`（默认 `true`）：关闭时所有端点回 `FORBIDDEN 40300` +
  「反馈功能暂未启用」，不是 404。**前端入口不读这个开关**（没有公开的开关查询端点）：
  关闭后 `/feedback`、`/admin/feedback` 入口仍在，页面按 40300 显示权限类提示
  （`faults.ts` 的 40300 分支统一文案）。要彻底下线需同时移除入口与路由。
- `eye-diagnosis.feedback.rate-limit-per-minute`（默认 5）：Redis 计数，超限回
  `42900 TOO_MANY_REQUESTS`（429 语义的独立码，不是「操作失败」）；
  **Redis 故障时放行**（限流是防刷手段，不是业务前提）。
- 开关与限额由 `config/UserFeedbackSettings` 统一读取，读写端点与截图上传共用；
  关掉开关后上传也不会再往 COS 写对象。
- 本轮不做审计表：行上的 `reply / repliedBy / handledBy / status` 已能表达一次处理；
  需要多轮留痕时再照 `t_news_audit` 加表。

## 7. 验证记录

环境：本工作树（`D:\develop-worktree-t3\eye-diagnosis\t3-a45a3119-0f628d126aa680cb`），
`node_modules` 以 junction 复用同机已安装依赖；树为 `dirty`（本分支未提交）。

| 门禁 | 命令 | 结果 |
|---|---|---|
| 前端类型检查 | `node node_modules/typescript/bin/tsc -b` | PASS（无输出） |
| 前端构建 | `node node_modules/vite/bin/vite.js build` | PASS（2.00 s，含 `NewsMarkdownEditor` 大 chunk 警告，为既有情况） |
| 前端全量用例 | `node node_modules/vitest/vitest.mjs run` | **115 文件 / 1226 用例，0 失败**（rebase 后复验，含 master 新增用例） |
| 新增前端用例 | `vitest run src/lib/userFeedbackDomain.test.ts src/pages/FeedbackPage.test.tsx src/pages/admin/AdminFeedbackPage.test.tsx src/lib/queryKeys.test.ts` | 24/24 PASS（含「关联判读任务送 taskId」回归用例） |
| 后端反馈四类 | `scripts/verify-fast.sh --java 'UserFeedbackLifecycleServiceTest,UserFeedbackAttachmentServiceTest,UserFeedbackStatusTest,UserFeedbackOwnershipTest'` | PASS — L1，4 类 / **41 用例**（38 s） |
| **L2 Flyway 门禁** | `scripts/verify-fast.sh --java FlywayMySqlIntegrationTest --db` | **PASS — L2**，1 类 / 5 用例（rebase 后复验，sha `b3f5bc71`） |
| **L2 Spring 装配** | `scripts/verify-fast.sh --java EyeDiagnosisApplicationTests --db` | **PASS — L2**，`contextLoads` 1/1：新 Bean、Mapper、`UserFeedbackSettings` 可装配（rebase 后复验） |

L2 用例覆盖：空库 → V39、master V17 → V39、旧迁移集合接受 future 版本、history 含无法解析版本时拒绝启动、V23 → V24 历史行不改写。

### 未执行（必须如实标注）

- **L3 全量**（`scripts/verify-fast.sh --level L3` / `scripts/verify.sh`）：合并前必须跑一次。
- **手工端到端**：后端 8081（`local,dev`）+ vite 5173 的真实提交 → 回复 → 回读闭环。
- **双主题人工验收**（浅色 / 深色）：新增样式文件与状态标签未做浏览器截图核对。

## 8. 手工验收清单（待执行）

1. 用普通账号登录 → `/feedback`：选分类、写正文、关联一条判读任务、上传一张 ≤2 MB 的 PNG → 提交。
2. `/profile` 底部「我的反馈」入口可达；`/feedback` 列表出现新条目，状态「待处理」。
3. 用 admin 登录 → `/admin/feedback`：待处理数 +1；按分类/状态/关键词/日期筛选。
4. 打开详情 → 写回复 → 「回复用户」（状态转「处理中」）；再「标记为已解决」。
5. 回到用户账号 → `/feedback`：状态「已解决」，管理员回复与时间可见；截图链接可打开。
6. 越权：用户 B 打开用户 A 的反馈详情 / 截图链接 → 403；提交时带别人的 taskId → 404。
7. 深色主题下复查两页：状态标签、徽标、弹层对比度。
8. 关闭开关（`eye-diagnosis.feedback.enabled=false`）重启：两端点回「反馈功能暂未启用」。

## 9. 已知限制

- 截图上传后若**放弃提交**，COS 里会留一个孤儿对象：`feedback/` 前缀没有对账器
  （`UploadCompensationService` 只认 `agent-chat/`）。落库失败时服务端会删掉刚上传的对象。
- 关联对象下拉只取最近 50 条（判读任务取 `/diagnosis-tasks` 的 50 条上限，会话取 50 条），
  不在表单里做全量分页。
- 关闭开关后前端入口不会消失，用户看到的是权限类提示而不是「暂未启用」（见 §6）。
- 管理端不提供内部备注；「意见反馈 → 触发正式复核」是另一条链路，本轮不做。
- 待处理数只作为页头/筛选读数，未接进仪表盘。

## 10. 合并顺序（重要）

V32–V34 已随 PR #156 合入 master，V35–V36 仍被在途的 Iris Control Center 占用，
本分支取 V38（站内通知另取 V39）。
`spring.flyway.out-of-order` 保持 `false`，所以**先应用过 V38/V39 的库会拒绝随后到达的 V35–V37**。
建议按编号顺序合并：小程序（V35）→ 配置中心（V36/V37）→ 本分支（V38/V39）；
若本分支先合，后续分支需重编号到 V40+。
详见 `docs/architecture/migration-matrix.md` §1.1。
