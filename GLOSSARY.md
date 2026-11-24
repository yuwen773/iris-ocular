# GLOSSARY.md

领域词汇表。架构评审与模块设计以这里的名字为准；新增概念时先在此命名。

## 后端业务

| 术语 | 含义 | 代码位置 |
|---|---|---|
| 诊断记录（Diagnosis） | 一次图片推理的落库结果，自带医疗可靠性证据与人工复核字段，不引用共享推理缓存 | `model/entity/Diagnosis.java` |
| 推理缓存（InferenceCache） | 按图片哈希 + 部署指纹跨用户共享的一行推理结果；仅供复用，不是历史记录 | `model/entity/InferenceCache.java` |
| 证据快照（DiagnosisEvidence） | 从 gRPC 响应一次归一化出的证据，写入诊断记录 / 结果 VO / 推理缓存三处 | `service/DiagnosisEvidence.java` |
| 健康资讯（News） | 面向患者的文章；发布生命周期状态共用一个 `status` 列，不另设审核状态列 | `model/entity/News.java` |
| 发布生命周期 | 0 草稿 → 3 待审核 / 1 已发布 / 2 下架；合法迁移见 `NewsLifecycleService` | `constant/NewsStatus.java` |
| 资讯审计（NewsAudit） | 状态与内容变更留痕，payload 只记字段级 diff，不含富文本正文 | `model/entity/NewsAudit.java` |
| Agent 会话 | 一次智能对话的线程；消息写入由 `ChatMessageWriter` 承担事务 | `agent/service/` |
| MCP 服务 | 用户可启用的外部工具服务，会话覆盖 → 全局启用 → 禁用的解析优先级 | `agent/manager/MCPManager.java` |
| 知识库文档（KnowledgeDocument） | RAG 文档的上传、切块与向量化记录 | `model/entity/` |
| 知识索引版本（indexVersion） | 一次文档处理的服务端 UUID；仅 READY 文档当前版本的片段和引用可作为证据，失败代及旧代必须排除 | `KnowledgeDocument` / `DocumentProcessingService` / `DocumentSearchTool` |
| 公开影像前缀审计（public/） | `public/` 下判读原图与头像**没有任务表登记**，只做只读对账：列出无数据库引用的对象并告警，删除仍由人工决定（同一前缀混两类对象，删错不可恢复） | `upload/audit/PublicImageOrphanAuditor.java` |
| 用户反馈（UserFeedback） | **登录用户**对产品、判读体验或 Agent 回答提出的意见，可关联一次判读任务或一个 Agent 会话，由管理员处理与回复；**不进入**医疗结论、复核队列与统计口径 | `model/entity/UserFeedback.java`、`service/UserFeedbackLifecycleService.java` |
| 站内通知（UserNotification） | 一条「有东西变了、去哪里看」的可读消息；文案在发送时定稿存库，正文只给指针（不含结论 / 病名 / 严重度）；已读且超过保留期即物理删除，不是台账 | `model/entity/UserNotification.java`、`service/NotificationService.java`、`constant/NotificationType.java` |

## 三个「反馈 / 意见」不要混用

- **Critic 反馈**：多 Agent 内部的审稿回环，状态 `NONE / REWRITE / SUPPLEMENT`，
  SSE 事件名 `agent.feedback.requested`——**已冻结契约**，不得改语义或改名。
- **复核意见**：医生对冻结草稿的结论，`t_doctor_review.note`。
- **审核意见**：管理员对资讯的驳回理由，`t_news.reviewNote`。
- **用户反馈**：本表里的新概念，是**产品意见通道**，与上面三者无关。
  代码标识一律带 `User`（`UserFeedback` / `t_user_feedback`），不新增别名。

## 三种「提示」不要混用

- **站内通知**：持久消息，进收件箱（`/notifications`），未读带数字；四类触发见
  `constant/NotificationType`。写失败不阻断业务（旁路）。
- **瞬时浮条**（`FaultToaster` / `useFault().push`）：一次操作的结果提示，不落库、不可回看。
- **待办徽标**：业务表里的真实待办数（如管理端「意见反馈」的待处理数、通知铃铛的未读数），
  不是消息本身，也不产生收件箱条目。


## 两个流程状态不要混用

- `reexamineStatus`：**患者**申请复诊的流程状态。
- `humanReviewStatus`：**医生**人工复核结论的状态。

二者在 `t_diagnosis` 中共存但语义独立，合并会破坏已冻结的 API 契约。

## 双线后端

`master` 是 Java 线；`feature/python-backend` 是 FastAPI 线，两条线不再互相合并。
`frontend/` 与 `algorithm/` 下的共享目录各持一份，改动需人工搬运。详见 `docs/backend-split.md`。

## 前端设计

`web/`（React）是产品前端，设计真源是仓库根 `DESIGN.md`
（**Iris 2.0 · Adaptive Visual System**：Brand / Management / Intelligence / Clinical
四种表达强度），令牌实现在 `web/src/styles/tokens.css`（已实现的事实，冲突时以它为准）；
`frontend/`（Vue）**已弃用（deprecated）**，仍用旧青绿 `--iris-*`，
不再作为设计参考。详见 `DESIGN.md` 与 `docs/backend-split.md`。

## 小程序（患者端）

| 术语 | 含义 | 代码位置 |
|---|---|---|
| 小程序令牌通道（Bearer） | 与 Cookie 会话并行的第二条身份通道：小程序请求带 `Authorization: Bearer <token>`，过滤器解出 `userId` 写进请求属性；没有 Bearer 头的请求原样走原 Cookie 分支，`web/` 语义不变 | `miniapp/MiniAppTokenFilter.java` / `constant/MiniAppConstant.java` |
| 令牌认证豁免 | 只有两类请求跳过 `SameOriginFilter` 的同源校验：令牌认证通过的请求，以及 `/mp/auth/login`（换令牌入口，本身不写 Cookie）。小程序的 Referer 被平台固定为 `servicewechat.com`，同源校验不可能通过 | `config/SameOriginFilter.java` |
| 微信绑定（t_user_wechat） | `(appid, openid)` → `userId` 的绑定行；`session_key` 不落库、不进日志、不返回客户端 | `model/entity/UserWechat.java` / `V35` |
| 首登建号 | 绑定不存在时自动建 `t_user`（`userAccount = wx_<openid>`、随机 BCrypt 密码占位、`role = user`），不与既有密码账号合并；并发首登由唯一键兜底并回读赢家 | `miniapp/MiniAppAuthService.java` |
| 令牌会话（mp:token） | 32 字节随机令牌，Redis 只存其 SHA-256，30 天滑动续期；登出删键 | `miniapp/MiniAppTokenService.java` |
