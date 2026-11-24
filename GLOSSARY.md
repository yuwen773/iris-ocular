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
| 流式故障（AgentStreamError） | Agent 流里失败时对外的机器可读码与人话；模型服务故障与「服务端自己坏了」分开 | `agent/AgentStreamError.java` |
| MCP 服务 | 用户可启用的外部工具服务；解析优先级（会话覆盖 → 全局启用 → 禁用）在拦截器里，客户端与工具发现缓存在这里 | 优先级 `agent/interceptor/TextMcpModelInterceptor.java`；客户端 `agent/manager/MCPManager.java` |
| 知识库文档（KnowledgeDocument） | RAG 文档的上传、切块与向量化记录 | `model/entity/` |
| 知识索引版本（indexVersion） | 一次文档处理的服务端 UUID；仅 READY 文档当前版本的片段和引用可作为证据，失败代及旧代必须排除 | `KnowledgeDocument` / `DocumentProcessingService` / `DocumentSearchTool` |
| 词法语料（LexicalCorpus） | 按 `ownerUserId` 元数据从向量库**直取**的该用户可见全部分块（含内置语料），供 BM25 使用；不是语义近邻子集 | `agent/rag/LexicalCorpusService.java` |
| 混合检索（hybrid-search） | 向量检索 + 词法召回经 RRF 融合；词法命中必须回向量库核对相似度阈值，未通过的一律丢弃 | `agent/tools/DocumentSearchTool.java` |
| 图片引用（iris-img://） | 后端签发的短期对象引用（HMAC-SHA256，≤15 分钟）；MCP 默认只认它，不认任意 URL | `agent/service/ImageReferenceSigner.java` / `algorithm/mcp_server/image_ref.py` |
| 图像质量门（ImageQualityState） | 这张图过没过质量门的结论，对外只说 `PASSED / FAILED / NOT_EVALUATED`；与「结论可不可信」分开 | `algorithm/eye_diagnosis/quality.py` / `V34` |
| 质量门标定产物（QualityCalibration） | 模糊阈值及其出处的版本化产物；`UNCALIBRATED` 时必须如实标注，不得当成已验证阈值 | `algorithm/eye_diagnosis/quality_calibration.py` / `algorithm/configs/calibration/` |
| 分类概率（classificationProbability） | 原始 softmax top-1（0–1）。**不是**校准概率，更不是医学可靠性；未标定时界面必须照此标注 | `DiagnosisEvidence` / `V34` |
| 患者结论准入（patientConclusionAllowed） | 算法侧判定「这条结论能否面向患者呈现」；与异步任务准入（`restrictReason`）是两道独立的门 | `diagnosis.proto` / `DiagnosisTaskRunner` |
| 任务受限原因（restrictReason） | 异步患者任务为准入未通过时落库/下发的受限码；前端据此如实说明为何被限制 | `V33` / `DiagnosisTaskRunner` |
| 两条判读准入 | 同步 `/api/diagnosis/*` 是「研发演示推理」：未被拒识就回分类结果，并用 `patientConclusionAllowed` 标注能不能作患者结论；异步 `/api/diagnosis-tasks/*` 是「满足证据准入的患者任务」：另加版本绑定的 OOD 证据门，缺证据即 `RESTRICTED`。两者是不同产品面，不是同一道门的两份实现 | `DiagnosisServiceImpl` / `DiagnosisTaskRunner` |
| 两种「未标定」 | `NOT_CALIBRATED` 说的是**概率**有没有做温度校准（`t_diagnosis.calibrationStatus`）；`UNCALIBRATED` 说的是**图像质量门阈值**有没有在真实数据上标定（`imageQualityCalibrationState`）。两者互不相干，不要互相顶替：一张图可能质量阈值已标定、而概率还没校准 | `diagnosis.proto` / `quality_calibration.py` |
| 对外置信度（exposedConfidence） | 只有 `calibrationStatus = CALIBRATED` 才有值；未标定时对外为 null，读法改用 `classificationProbability` 并标注「模型输出概率（未标定）」。库里的 `confidence` 列另有一套口径（模型自评概率，供分析工作台），不要混用 | `DiagnosisEvidence.exposedConfidence` / `web/src/lib/domain.ts` |
| 流式账号闸门（AccountStreamGuard） | 流式回答进行中按时间片回查账号是否仍可用；封禁发生在开写之后也要立刻停止投递，不能等下一次请求 | `agent/service/AccountStreamGuard.java` |
| 公开影像前缀审计（public/） | `public/` 下判读原图与头像**没有任务表登记**，只做只读对账：列出无数据库引用的对象并告警，删除仍由人工决定（同一前缀混两类对象，删错不可恢复） | `upload/audit/PublicImageOrphanAuditor.java` |
| 上传任务（UploadTask） | 一次图片上传的状态机与补偿对账；清理 COS 上无任务行登记的孤儿对象 | `upload/state/`、`t_upload_task` |

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
