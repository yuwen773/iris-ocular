# 站内通知（UserNotification）实施计划 · 2026-11-24

状态：**已实施**（分支 `t3/add-feedback-feature`，基线 `c90b0caa` = master，提交 `b3f5bc71`）。
交付边界与验证读数见 `docs/testing/2026-11-24-user-notification-acceptance.md`。

## 1. 为什么要做

用户反馈把「用户说话」的通道补上了，但**系统从不主动说话**：判读跑完、复核出结论、
管理员回了反馈，用户只能自己去翻页面。通知是这条闭环的另一半——
没有它，反馈的回复也要靠用户猜什么时候回来看。

## 2. 命名与边界（先定，再写码）

- 中文领域名：**站内通知**；代码标识 `UserNotification`（表 `t_user_notification`、
  实体、VO、`NotificationService`、常量 `NotificationType`）；路由 `/notifications`。
- 「通知」在仓库里没有既有占用，但**三种「提示」容易混**，已在 `GLOSSARY.md` 写清：
  站内通知（持久、进收件箱）/ 瞬时浮条（`useFault`，不落库）/ 待办徽标（业务表计数）。
- 通知**不是台账**：删掉它不影响任何业务状态；真正的待办仍在 `t_diagnosis_task` /
  `t_user_feedback` 里。

## 3. 范围（已与用户确认）

| 维度 | 决定 |
|---|---|
| 形态 | 只有消息，不建待办表；待办以徽标计数表达 |
| 接收人 | 通用 `userId`（患者 / 医生 / 管理员同一套） |
| 类型 | `DIAGNOSIS_READY` / `DIAGNOSIS_RESTRICTED` / `REVIEW_DONE` / `FEEDBACK_REPLIED` |
| 写入时机 | 业务事务**提交后**写入；独立 try/catch，写失败只记日志 |
| 去重 | 不做；一次「回复 + 改状态」只产生一条（只有真写了回复才发） |
| 内容 | 文案在发送时定稿存库；正文只给指针，不含结论 / 病名 / 严重度 |
| 深链 | 只到列表页（`/records`、`/feedback`），不按记录 id 跳 |
| 入口 | 用户端顶栏铃铛 + 管理端顶栏铃铛 + 侧栏「意见反馈」待处理徽标 |
| 页面 | `/notifications`（Clinical）：未读 / 全部 + 类型筛选 + 20 条/页 |
| 刷新 | 60 秒轮询 + 窗口聚焦刷新；点击即已读（幂等） |
| 全部已读 | 只清**当前筛选**下的未读 |
| 清理 | 已读且超过保留期物理删除；未读永不清理；只暴露 `retention-days` 一项配置 |
| 开关 | 不加开关（旁路，写失败不阻断业务） |

## 4. 数据模型（V39）

`t_user_notification`：`id`（雪花）/ `userId` / `type` / `title` / `body` / `refType` /
`refId` / `readAt` / `createTime`；索引 `(userId, createTime)`、`(userId, readAt)`。
没有 `isDelete`，没有 `AUTO_INCREMENT`（主键由 `IdType.ASSIGN_ID` 生成）。

为什么存文案：通知是「发出去那一刻的通知」，90 天后清理；类型文案会改，
历史消息应当保持当时的措辞。

## 5. 四个触发点

| 位置 | 条件 | 接收人 | 类型 |
|---|---|---|---|
| `DiagnosisTaskRunner.finish` | 影响行数 = 1 且新状态 ∈ `WAITING_REVIEW` / `RESTRICTED`（`PENDING` 重试不发） | `Claimed.userId` | `DIAGNOSIS_READY` / `DIAGNOSIS_RESTRICTED` |
| `DoctorReviewService.decide` | 从 `PENDING` 落定为 `APPROVED` / `REJECTED`（replay 不发） | `t_diagnosis_task.userId` | `REVIEW_DONE` |
| `UserFeedbackLifecycleService.reply` | 写入成功后（`afterCommit`） | 反馈提交人 | `FEEDBACK_REPLIED` |
| `UserFeedbackLifecycleService.changeStatus` | 仅当本次带 `reply`（`afterCommit`） | 反馈提交人 | `FEEDBACK_REPLIED` |

前两处所在的协作线默认关闭，**启用前只有反馈会产生通知**。

## 6. 接口

| 端点 | 说明 |
|---|---|
| `GET /api/notifications` | 我的通知分页（`current` / `pageSize` ≤ 50 / `type` / `unreadOnly`），时间倒序 |
| `GET /api/notifications/unread-count` | 未读数（铃铛与页面共用一份缓存） |
| `POST /api/notifications/{id}/read` | 标记已读，幂等；别人的 id 回 404 |
| `POST /api/notifications/read-all` | 全部已读（`{type?}`），返回真正标记的条数 |

全部 `@AuthCheck`，接收人恒取会话用户，响应 `Cache-Control: no-store`。

## 7. 前端结构

```
lib/api.ts                    类型 + 四个函数（列表用 GET + 查询串）
lib/notificationDomain.ts     类型镜像、筛选文案、refType → 列表页
lib/queryKeys.ts              notifications.list / listAll / unread；notifications 进 PRIVATE_QUERY_ROOTS
components/NotificationBell.tsx  会话门内的轮询组件（未登录不挂查询）
pages/NotificationsPage.tsx   未读 / 全部 + 类型筛选 + 点击即已读 + 全部已读
components/admin/AdminLayout  顶栏铃铛 + 待处理反馈数（传给两个 AdminNav）
styles/notification.css       铃铛 / 页面 / 管理端徽标；零硬编码色；断点 768
```

## 8. 测试与验证

- 后端：`NotificationServiceTest`（15）、`NotificationOwnershipTest`（7）、
  `UserFeedbackLifecycleServiceTest` 新增 3 条通知用例。
- 前端：`notificationDomain.test.ts`（4）、`NotificationBell.test.tsx`（4）、
  `NotificationsPage.test.tsx`（8）。
- 迁移：`FlywayMySqlIntegrationTest` 同步到 V39 / 16 条迁移 / 表清单加 `t_user_notification`。
- 判据：新增迁移 + 写库 + 事务提交后写入 + 接收人归属 → **L2**。

## 9. 风险与已知边界

- **同一任务会连发两条**（结果已出、复核完成）：这是两个不同的状态变化，不是重复。
- **没有去重**：并发重复提交可能多发一条，重复比丢失温和。
- 通知正文只有指针：这是隐私边界，不是省事——结论在记录页，那里有免责声明。
- 清理依赖应用调度；多实例重复执行是幂等的（条件相同）。
- 迁移编号与合并顺序见 `docs/architecture/migration-matrix.md` §1.1。
