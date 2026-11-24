# 站内通知（UserNotification）验收 · 2026-11-24

分支 `t3/add-feedback-feature`（基线 `c90b0caa` = master；本分支已 rebase 到 master，
提交 `b3f5bc71`）。
实施计划见 `docs/plans/2026-11-24-user-notification-plan.md`。
同分支的上一功能（用户反馈）见 `docs/testing/2026-11-23-user-feedback-acceptance.md`。

## 1. 范围与边界

系统主动告诉用户「有东西变了」：

```
判读完成 / 判读受限  →  任务所有者收到一条消息
医生复核出结论       →  任务所有者收到一条消息
管理员回复反馈       →  反馈提交人收到一条消息
```

**通知不是台账**：删掉它不影响任何业务状态；真正的待办仍在 `t_diagnosis_task` /
`t_user_feedback` 里。**通知也不承载结论**：正文只有指针（哪个任务、去哪个列表看），
结论与免责声明在记录页 / 反馈页——通知可能被截屏或预览，医疗措辞只出现在有上下文的地方。

三种「提示」的边界写在 `GLOSSARY.md`：站内通知（持久、进收件箱）/ 瞬时浮条
（`useFault`，不落库）/ 待办徽标（业务表计数）。

## 2. 数据模型与迁移

- 迁移：`V39__user_notification.sql`（只新增表，不改任何既有表）。
- 表：`t_user_notification`。列名小驼峰；`id` 是雪花值（`IdType.ASSIGN_ID`），
  迁移里**不写** `AUTO_INCREMENT`（与 V38 一致：列定义与真实来源保持一致）。
- 存文案（`title` / `body`）：通知是「发出去那一刻的通知」，类型文案以后会改，
  历史消息保持当时的措辞。
- 没有 `isDelete`：已读且超过保留期即物理删除；未读永不清理。
- 索引：`(userId, createTime)` 列表翻页；`(userId, readAt)` 未读数与全部已读。

## 3. 接口

| 端点 | 守卫 | 说明 |
|---|---|---|
| `GET /api/notifications` | 登录 | 我的通知分页：`current` / `pageSize`（服务端上限 50）/ `type` / `unreadOnly`；时间倒序，同毫秒按 id 兜底 |
| `GET /api/notifications/unread-count` | 登录 | 未读数（铃铛与页面共用一份缓存） |
| `POST /api/notifications/{id}/read` | 登录 | 标记已读，幂等：重复点击不改写首次已读时间；别人的 id 回 404 |
| `POST /api/notifications/read-all` | 登录 | 全部已读（`{type?}`），返回真正被标记的条数；只清当前筛选 |

接收人恒取会话用户：查询条件里没有 `userId`，请求体夹带的一律忽略；
响应统一 `Cache-Control: no-store`。

**一处与计划的偏差**：「全部已读」不接受 `unreadOnly`。把已读行再标一次已读是空操作，
能改变结果的条件只有 `type`——多一个恒等于无操作的参数只会让人以为它有别的含义。

## 4. 触发点与语义

| 位置 | 条件 | 接收人 | 类型 |
|---|---|---|---|
| `DiagnosisTaskRunner.finish` | 影响行数 = 1 且新状态 ∈ `WAITING_REVIEW` / `RESTRICTED`（`PENDING` 重试不发） | 任务所有者 | `DIAGNOSIS_READY` / `DIAGNOSIS_RESTRICTED` |
| `DoctorReviewService.submit` | 从 `PENDING` 落定为 `APPROVED` / `REJECTED`（同一个 `submit` 里的并发 replay 不发） | 任务所有者（患者） | `REVIEW_DONE` |
| `UserFeedbackLifecycleService.reply` | 写入成功后 | 反馈提交人 | `FEEDBACK_REPLIED` |
| `UserFeedbackLifecycleService.changeStatus` | 仅当本次带 `reply` | 反馈提交人 | `FEEDBACK_REPLIED` |

- **提交后写入**：反馈两条与复核一条注册在事务 `afterCommit`，回滚就不发；
  判读任务在 `fencedUpdate` 事务返回后（已提交）直接写。
- **写失败不阻断业务**：`NotificationService.notify` 内部 try/catch，只记日志。
- **不做去重**：一次「回复 + 改状态」只产生一条（只有真写了回复才通知）；
  复核挂在 `submit` 的真实落定上而不是 `decide()` 的返回上，并发下别人先决定时
  走 replay 的那次请求不会再发一条；极端重试仍可能多发，重复比丢失温和。
- 任务与复核两处位于默认关闭的协作线：**启用前只有反馈会产生通知**。

## 5. 前端

| 路由 / 位置 | 场景 | 文件 |
|---|---|---|
| `/notifications` | Clinical | `web/src/pages/NotificationsPage.tsx` + `web/src/styles/notification.css` |
| 用户端顶栏 | — | `NotificationBell`（`ProductHeader` 的 actions 区，登录后才挂载） |
| 管理端顶栏 | Management | `NotificationBell variant="admin"`（铃铛在主题切换左侧） |
| 管理端侧栏 | Management | 「意见反馈」项的待处理数徽标（`AdminLayout` 取数、`AdminNav` 只显示） |

- 铃铛拆两层：外层只读会话，登录后才挂内层查询——未登录访客既不看到铃铛，
  也不会每分钟被替它打一次个人接口。
- 轮询 60 秒 + 窗口聚焦刷新；标记已读后失效 `notifications.list` 与 `notifications.unread`。
- 「未读 / 全部」页签与 4 个类型 chip 都进查询（服务端过滤），翻页不靠前端筛当前页。
- 点击即已读：标记失败也照常跳转——用户想看的是那条记录。
- 深链只到列表页（`DIAGNOSIS_TASK → /records`、`USER_FEEDBACK → /feedback`），
  认不出的关联类型只标已读、不跳转。
- 新增样式文件**零硬编码色**（`hardcoded-colors.test.ts` 棘轮通过）；断点只用 768。

## 6. 保留期清理

`NotificationCleanupScheduler` 每天 03:40 物理删除「已读且早于 `retention-days` 天」的行，
每批 1000 条、单次最多 20 批。未读通知永不清理（用户还没看到的东西不该因为时间到了消失）。
多实例重复执行是幂等的（删除条件相同）。

唯一可配项：`eye-diagnosis.notification.retention-days`（默认 90；≤0 关闭清理）。
没有功能开关：通知是旁路，写失败只记日志，不需要单独关停。

## 7. 验证记录

环境：本工作树（`D:\develop-worktree-t3\eye-diagnosis\t3-a45a3119-0f628d126aa680cb`），
`node_modules` 以 junction 复用同机已安装依赖；树为 `dirty`（提交 `b3f5bc71` 之后、
补文档前复验）。

| 门禁 | 命令 | 结果 |
|---|---|---|
| 前端类型检查 | `node node_modules/typescript/bin/tsc -b` | PASS（无输出） |
| 前端构建 | `node node_modules/vite/bin/vite.js build` | PASS（2.45 s，含既有的大 chunk 警告） |
| 前端全量用例 | `node node_modules/vitest/vitest.mjs run` | **115 文件 / 1226 用例，0 失败**（rebase 后复验，含 master 新增用例） |
| 新增前端用例 | `vitest run src/lib/notificationDomain.test.ts src/components/NotificationBell.test.tsx src/pages/NotificationsPage.test.tsx` | 16/16 PASS |
| 后端通知 + 反馈写侧 | `scripts/verify-fast.sh --java 'NotificationServiceTest,NotificationOwnershipTest,UserFeedbackLifecycleServiceTest,UserFeedbackStatusTest,UserFeedbackAttachmentServiceTest'` | PASS — L1，5 类 / **59 用例** |
| **L2 Flyway 门禁** | `scripts/verify-fast.sh --java FlywayMySqlIntegrationTest --db` | **PASS — L2**，1 类 / 5 用例（空库 → V39、master V17 → V39、future 版本放行、缺失版本拒绝、V23→V24 不改写） |
| **L2 Spring 装配** | `scripts/verify-fast.sh --java EyeDiagnosisApplicationTests --db` | **PASS — L2**，1 类 / 1 用例（`contextLoads`） |
| 复核写侧回归 | `ISSUE20_TEST_URL=jdbc:mysql://127.0.0.1:<临时容器>/issue20_test scripts/verify-fast.sh --java DoctorReviewApiTest` | PASS，1 类 / **38 用例实际执行、0 跳过**（复核通知改挂在 `submit` 之后，回归这套） |

> rebase 到 master（`c90b0caa`）后一次性复跑：后端 7 类 / **65 用例** PASS
> （上表 L1 五类 + Flyway 五例 + `contextLoads` 一例），前端 115 文件 / 1226 用例 PASS，
> 三个文档门禁全绿。master 带进来的改动与本分支的冲突点：`DiagnosisTaskRunner.finish`
> （master 新增 `restrictReason` 列，通知逻辑叠在上面）、`FlywayMySqlIntegrationTest`
> 的迁移数与版本断言（V18–V39 共 19 条）、`web/src/lib/adminApi.ts` 的类型导入合并。


> `verify-fast.sh --db` 的 MySQL 端口默认固定 33316。同机有别的会话在跑
> `docker-test-harness` 时会占掉这个端口，本树一次性 MySQL 连上去后中途被对方重建，
> 表现为 `Communications link failure`（不是代码问题）。复跑用
> `VERIFY_FAST_MYSQL_PORT=33326 VERIFY_FAST_REDIS_PORT=36399` 即可。

> 迁移改名的坑（已实证）：`V35__user_feedback.sql` 改名为 V37 时（本轮再改为 V38），
> `target/classes` 里仍留着旧的 V35 编译产物，Flyway 会先跑 V35 再跑 V37 →
> `Table 't_user_feedback' already exists`。每次改迁移文件名后都必须清掉过期资源
> （`mvn clean` 或删 `target/classes/db/migration/V35*` / `V37*`）。

### 未执行（必须如实标注）

- **L3 全量**（`scripts/verify-fast.sh --level L3` / `scripts/verify.sh`）：合并前必须跑一次。
- **手工端到端**：后端 8081（`local,dev`）+ vite 5173 的真实触发（见 §8）。
- **双主题人工验收**（浅色 / 深色）：铃铛角标、未读竖线、类型 chip 未做浏览器截图核对。

## 8. 手工验收清单（待执行）

1. admin 登录 → `/admin/feedback` → 对一条反馈「回复用户」。
2. 用提交人账号登录 → 顶栏铃铛出现未读数 → `/notifications` 显示「反馈有了回复」→ 点进去落在 `/feedback`。
3. 回到 `/notifications`：该条变已读、铃铛数字减一（60 秒内或切窗口后）。
4. 「全部已读」在选了某个类型时只清该类型的未读（页签与 chip 组合各试一次）。
5. 未读 / 全部两个页签分别翻页，条数与总数一致。
6. 管理端：侧栏「意见反馈」徽标 = 待处理数；顶栏铃铛可点。
7. 越权：用户 B 对用户 A 的通知 id 调 `POST /notifications/{id}/read` → 404。
8. 深色主题复查：角标、未读竖线、已读/未读标题对比度。

## 9. 已知限制

- 任务 / 复核两个触发点在本分支默认关闭的协作线上，未启用时不会产生通知。
- 同一任务会连发两条（结果已出、复核完成）：这是两个不同的状态变化，不是重复。
- 通知不按记录 id 深链：关联对象被删后，消息仍要到列表页，而不是一个 404。
- 清理依赖应用内调度；多实例重复删除幂等，但没有跨实例的分布式锁。
- 铃铛轮询固定 60 秒，不可配置；需要更快时可改成 SSE，本轮不做。

## 10. 合并顺序（重要）

V32–V34 已随 PR #156 合入 master，V35–V36 仍被在途的 Iris Control Center 占用，
本分支的反馈与通知分别取 V38、V39。
`spring.flyway.out-of-order` 保持 `false`，所以**先应用过 V38/V39 的库会拒绝随后到达的 V35–V37**。
建议按编号顺序合并：小程序（V35）→ 配置中心（V36/V37）→ 本分支（V38/V39）；
若本分支先合，后续分支需重编号到 V40+。
详见 `docs/architecture/migration-matrix.md` §1.1。
