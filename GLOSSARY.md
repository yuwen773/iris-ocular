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
| 资讯资料修订（News Material Revision） | 同一 `(source, sourceRecordId)` 在某个 `contentDigest` 下冻结的不可变原始记录快照，仅整窗原子完成后对编辑可见 | `V39` / `newsingestion/NewsIngestionService.java` |
| 资讯采集窗口（Collection Window） | 为单个信源和采集条件版本指定的日期范围，全部批次确认后才推进游标 | `contracts/news/` / `newsingestion/` |
| 采集游标（Collection Cursor） | 按 `(source, sourceConfigVersion)` 独立维护的已提交日期上界，只随连续已完成的窗口单调前进，回填不回退 | `t_news_source_cursor` / `NewsIngestionService.java` |
| 资讯精选候选（News Curation Candidate） | 仅针对可见 `t_news_material_revision` 产出并绑定不可变证据快照的精选候选结果；需通过证据/许可/临床/防注入门禁，不自动写入 `t_news` | `V40` / `newscuration/` |
| 资讯内容修订（News Revision） | 编辑建稿、审批、发布、封面更新与软删时产出的不可变内容快照与证据摘要绑定记录，按 `(newsId, revisionNo)` 顺序持久化 | `V41` / `NewsLifecycleService` |
| 资讯刊物预览（News Issue Preview） | 管理员按资讯发布日期生成的只读报告，依据已发布修订的 PMID 或 DOI 聚合，保留各条发布修订的溯源 | `newsworkbench/NewsIssueService.java` |
| 资讯列表缓存纪元（News List Cache Epoch） | 持久化单调递增的缓存失效纪元；写事务提交前推进并于 `afterCommit()` 同步 Redis 世代，若 Redis 失效失败则读侧凭数据库纪元降级穿透并自愈 | `V41` / `NewsListCacheService` |
| 模型调用回执（Model Call Receipt） | 付费模型调用前写入的预扣预算与外部请求台账，用于区分预检中止与 `OUTCOME_UNKNOWN` 并支持人工对账，禁止未知付费状态下自动重发 | `V40` / `newscuration/NewsCurationService.java` |
| NewsBench（资讯精选离线评测） | 基于冻结夹具集验证眼科相关性筛选、逐字引文地面校验、摘要许可遵守与提示词注入拦截的离线评测基线 | `contracts/news/newsbench/` / `newscuration/NewsBenchRunner.java` |
| Agent 会话 | 一次智能对话的线程；消息写入由 `ChatMessageWriter` 承担事务 | `agent/service/` |
| MCP 服务 | 用户可启用的外部工具服务，会话覆盖 → 全局启用 → 禁用的解析优先级 | `agent/manager/MCPManager.java` |
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
