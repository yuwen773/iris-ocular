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
| 资讯原始资料（News Material） | 从指定信源采集的原始记录，同一信源内以稳定标识关联不可变内容修订 | `contracts/news/` / `newsingestion/` |
| 资讯资料修订（News Material Revision） | 同一 `(source, sourceRecordId)` 在某个 `contentDigest` 下冻结的不可变原始记录快照，仅整窗原子完成后对编辑可见 | `V40` / `newsingestion/NewsIngestionService.java` |
| 资讯采集窗口（Collection Window） | 为单个信源和采集条件版本指定的日期范围，全部批次确认后才推进游标 | `contracts/news/` / `newsingestion/` |
| 采集游标（Collection Cursor） | 按 `(source, sourceConfigVersion)` 独立维护的已提交日期上界，只随连续已完成的窗口单调前进，回填不回退 | `t_news_source_cursor` / `NewsIngestionService.java` |
| 资讯精选候选（News Curation Candidate） | 仅针对可见 `t_news_material_revision` 产出并绑定不可变证据快照的精选候选结果；需通过证据/许可/临床/防注入门禁，不自动写入 `t_news` | `V41` / `newscuration/` |
| 资讯内容修订（News Revision） | 编辑建稿、审批、发布、封面更新与软删时产出的不可变内容快照与证据摘要绑定记录，按 `(newsId, revisionNo)` 顺序持久化 | `V42` / `NewsLifecycleService` |
| 资讯刊物预览（News Issue Preview） | 管理员按资讯发布日期生成的只读报告，依据已发布修订的 PMID 或 DOI 聚合，保留各条发布修订的溯源 | `newsworkbench/NewsIssueService.java` |
| 资讯列表缓存纪元（News List Cache Epoch） | 持久化单调递增的缓存失效纪元；写事务提交前推进并于 `afterCommit()` 同步 Redis 世代，若 Redis 失效失败则读侧凭数据库纪元降级穿透并自愈 | `V42` / `NewsListCacheService` |
| 模型调用回执（Model Call Receipt） | 付费模型调用前写入的预扣预算与外部请求台账，用于区分预检中止与 `OUTCOME_UNKNOWN` 并支持人工对账，禁止未知付费状态下自动重发 | `V41` / `newscuration/NewsCurationService.java` |
| NewsBench（资讯精选离线评测） | 基于冻结夹具集验证眼科相关性筛选、逐字引文地面校验、摘要许可遵守与提示词注入拦截的离线评测基线 | `contracts/news/newsbench/` / `newscuration/NewsBenchRunner.java` |
| Agent 会话 | 一次智能对话的线程；消息写入由 `ChatMessageWriter` 承担事务 | `agent/service/` |
| 流式故障（AgentStreamError） | Agent 流里失败时对外的机器可读码与人话；模型服务故障与「服务端自己坏了」分开 | `agent/AgentStreamError.java` |
| MCP 服务 | 用户可启用的外部工具服务；解析优先级（会话覆盖 → 全局启用 → 禁用）在拦截器里，客户端与工具发现缓存在这里 | 优先级 `agent/interceptor/TextMcpModelInterceptor.java`；客户端 `agent/manager/MCPManager.java` |
| 知识库文档（KnowledgeDocument） | RAG 文档的上传、切块与向量化记录 | `model/entity/` |
| 知识索引版本（indexVersion） | 一次文档处理的服务端 UUID；仅 READY 文档当前版本的片段和引用可作为证据，失败代及旧代必须排除 | `KnowledgeDocument` / `DocumentProcessingService` / `DocumentSearchTool` |
| 公开影像前缀审计（public/） | `public/` 下判读原图与头像**没有任务表登记**，只做只读对账：列出无数据库引用的对象并告警，删除仍由人工决定（同一前缀混两类对象，删错不可恢复） | `upload/audit/PublicImageOrphanAuditor.java` |
| 上传任务（UploadTask） | 一次图片上传的状态机与补偿对账；清理 COS 上无任务行登记的孤儿对象 | `upload/state/`、`t_upload_task` |
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
