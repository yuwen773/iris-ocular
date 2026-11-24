# 用户反馈（UserFeedback）实施计划 · 2026-11-23

状态：**已实施**（分支 `t3/add-feedback-feature`，基线 `c90b0caa` = master，提交 `b3f5bc71`）。
交付边界与验证读数见 `docs/testing/2026-11-23-user-feedback-acceptance.md`。

## 1. 为什么要做

Iris 之前没有用户声音通道：患者对判读结论有异议、对 Agent 回答不满意、对界面有意见，
只能私下找管理员；管理员没有收件箱，也谈不上闭环。

本轮交付一条**闭环最短、语义完整**的链路：

```
用户提交（可挂到某次判读 / 某个 Agent 会话，可附一张截图）
  → 我的反馈（看得见状态与管理员回复）
  → 管理端工作台（筛选、状态流转、回复）
```

## 2. 命名（先定，再写码）

仓库里「反馈」一词已被三处占用，含义都与本功能不同（`GLOSSARY.md` 明言「合并会破坏已冻结的 API 契约」）：

| 已有用法 | 位置 | 含义 |
|---|---|---|
| Critic 反馈 | `agent.feedback.requested` SSE 事件、`FourRoleGraph.feedback` | 多 Agent 内部重写/补充（**已冻结契约**） |
| 复核意见 | `t_doctor_review.note` | 医生对冻结草稿的结论 |
| 审核意见 | `t_news.reviewNote` | 资讯审核驳回理由 |

因此新概念**不叫裸词「反馈」**：

- 中文领域名：**用户反馈**
- 代码标识：`UserFeedback`（表 `t_user_feedback`、实体、VO、Service、常量 `UserFeedbackStatus` / `UserFeedbackCategory`）
- 路由：`/feedback`（用户侧）、`/admin/feedback`（管理侧）

## 3. 范围（已与用户确认）

| 维度 | 决定 |
|---|---|
| 提交者 | 登录用户（`user` / `doctor` / `admin`；患者就是 `user`；`ban` 已被登录门禁挡住） |
| 内容 | 自由撰写（1–2000 字）+ 固定分类 + 可选关联对象 + 可选一张截图 |
| 分类 | `SUGGESTION` 功能建议 / `ISSUE` 使用问题 / `RESULT_DOUBT` 结果质疑 / `OTHER` 其他 |
| 关联对象 | `DIAGNOSIS_TASK` 或 `AGENT_SESSION`，可空，且必须属于提交人 |
| 管理端 | 列表 + 详情、状态流转、回复用户（**不做**内部备注） |
| 用户端 | 「我的反馈」：状态 + 管理员回复可见 |
| 开关 | `eye-diagnosis.feedback.enabled`，默认 `true`，关闭时明确报错 |
| 交付 | 后端 + 用户端 + 管理端全套 |
| 审计表 | 本轮不做 |

## 4. 场景归属（Iris 2.2）

| 路由 | 场景 | 硬约束 |
|---|---|---|
| `/feedback` | Clinical | `.is-field .is-brand-page`；品牌玻璃面板 + 发丝线分区；状态标签永远带文字 |
| `/admin/feedback` | Management | `.is-admin` + 独立侧栏；管理紫主操作；`DataTable` + 分页 |

## 5. 后端结构

```
src/main/resources/db/migration/V38__user_feedback.sql
model/entity/UserFeedback.java                 @TableName + @TableLogic
mapper/UserFeedbackMapper.java                 BaseMapper + 两个归属计数查询
model/dto/userfeedback/{Submit,Query,Decide,Reply}DTO.java
model/vo/userfeedback/UserFeedbackVO.java
config/UserFeedbackSettings.java               @ConfigurationProperties：开关 + 限额（一处读取）
service/UserFeedbackService(.impl)             查询 / VO / 待处理数
service/UserFeedbackLifecycleService.java      唯一写入口 + 唯一鉴权面
service/UserFeedbackAttachmentService.java     COS 上传 / 现签链接 / 存在性
controller/UserFeedbackController.java         /feedback
controller/AdminUserFeedbackController.java    /admin/feedback
constant/{UserFeedbackStatus,UserFeedbackCategory}.java
```

实施相对初稿的调整（均已落地）：

- **不另设 `feedbackId`**：主键 `id BIGINT` 就是对外 id，由 MyBatis-Plus 的
  `IdType.ASSIGN_ID` 生成雪花值（迁移里也**不写** `AUTO_INCREMENT`：列定义与真实来源保持一致）；
  JSON 经 `JsonConfig` 全局 Long→String，前端拿到字符串。
- 回复人 / 处理人列名与实体字段一致：`repliedById`、`handledById`。
- 条件更新用**字符串列名的 `UpdateWrapper`** 而不是 `LambdaUpdateWrapper`：
  列名与字段同名，而 Lambda 形式要求 MyBatis-Plus 反射缓存，纯单元测试里没有 Spring 就解析不了。
- 分页 VO 转换手工建 `Page`（MP 的 `page.convert` 返回 `IPage`，与接口声明的 `Page` 不是同一类型）。
- 「我的反馈」用 `POST /feedback/mine`（查询条件走请求体，与 `/diagnosis/list/page/my` 同形），
  不是初稿里的 GET。
- 关联对象下拉的判读取自 `/diagnosis-tasks`（`taskId` 是 `t_diagnosis_task.taskId`）：
  初稿写的是 `listMyDiagnoses`，它给的是 `t_diagnosis.id`，与服务端比对的是两个不同的键。
- 开关与限额收敛到 `config/UserFeedbackSettings`（`@ConfigurationProperties`），
  读写端点与截图上传共用一份——否则关掉一处等于没关。

## 6. 关键规则

- 状态机与条件更新见验收文档 §4；非法迁移 → `40900 USER_FEEDBACK_CONFLICT`。
- `RESOLVED` 前必须有回复。
- 归属：`DIAGNOSIS_TASK` / `AGENT_SESSION` 必须属于提交人，否则 404（不让人拿别人的 id 试探存在性）。
- 截图 key 必须落在 `feedback/{userId}/` 下，含 `..` 一律拒绝；**并且对象必须真的存在**
  （只比前缀会接受自造 key，管理端详情页只剩一张裂图）。
- 排序字段白名单：`id / status / createTime / updateTime`。
- 限流 Redis 计数，超限 → `42900 TOO_MANY_REQUESTS`（429 语义，不是「操作失败」）；Redis 故障时放行。
- 功能开关同时管住读写端点与截图上传。

## 7. 前端结构

- 数据层：`web/src/lib/api.ts`（用户侧）、`web/src/lib/adminApi.ts`（管理侧）。
- 领域层：`web/src/lib/userFeedbackDomain.ts`（枚举镜像、文案、迁移矩阵镜像、正文校验）。
- 查询键：`queryKeys.feedback.*`、`queryKeys.admin.feedback*`；`feedback` 进 `PRIVATE_QUERY_ROOTS`。
- 页面：`FeedbackPage`（Clinical）、`AdminFeedbackPage`（Management）。
- 样式：`feedback.css`、`admin-feedback.css`，零硬编码色。
- 入口：`AdminNav` 第 07 项；个人中心底部「我的反馈」。

## 8. 测试与验证

- 后端：`UserFeedbackStatusTest`（5）、`UserFeedbackLifecycleServiceTest`（25）、
  `UserFeedbackAttachmentServiceTest`（4，开关 / 解码 / 前缀 / 存在性）、
  `UserFeedbackOwnershipTest`（7，控制层：会话用户是唯一身份来源、请求体不得夹带
  `userId` / `status`、六个管理端点必须挂 `@AuthCheck(mustRole = admin)`、响应 `no-store`）。
- 前端：`userFeedbackDomain.test.ts`、`FeedbackPage.test.tsx`、`AdminFeedbackPage.test.tsx`（17）。
- 迁移：`FlywayMySqlIntegrationTest` 三条版本断言同步到 V38 / 本分支 16 条迁移（V38 反馈 + V39 通知）
  / 表清单加 `t_user_feedback`。
- L2 判据：新增迁移 + 写库 + 事务 + 权限 + 资源归属 + 上传归属 + 新 Bean / 配置。
- 合并前需补 L3、手工端到端与双主题验收（见验收文档 §7–§8）。

## 9. 风险与已知边界

- **迁移编号**：master 已含 V32–V34（PR #156），V35–V36 被在途的 Iris Control Center 占用，
  本功能取 V38（站内通知另取 V39）；合并顺序见
  `docs/architecture/migration-matrix.md` §1.1。谁先合谁保留；重编号 = 改文件名 + 版本/迁移数断言。
- **反馈不是医疗记录**：不进判读统计、复核队列与数据分析口径。
- **审计表**本轮不做；需要多轮处理留痕时再照 `t_news_audit` 加表。
- 截图放弃提交会留 COS 孤儿对象（`feedback/` 前缀没有对账器）。
