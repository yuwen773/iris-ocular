# Iris 全仓库代码审查（合并外部审计）

> 日期：2026-11-23
> 性质：**合并结论，取代** `repo-wide-code-review-2026-11-23.md`（首版只含内部 7 分区审查）；
> 首版保留为第一遍内部审查的原始记录。
> 来源 A：本工作树内部审查（7 个只读评审子代理 + 主会话逐条复核），基线 `b5a493e4`。
> 来源 B：外部 GitHub 只读审计（仓库 `yuwen773/iris-ocular`），基线 `master@5dc2eac5`。
> 复核：外部审计的每条**代码结论**均在本工作树回到源码核对；GitHub 治理项用 `gh api` 现场核实。
> 标注：`[已复核]` 主会话读过对应代码；`[外部证据]` 仅外部审计提供、本地无法验证；
> `[需核实]` 依赖运行环境或跨文件推断。

---

## 1. 基线对齐（先看这个）

两份审查的基线**不是同一条提交**，但差异可控：

| 项 | 内部审查（本工作树） | 外部审计 |
|---|---|---|
| 提交 | `b5a493e4` | `5dc2eac5` |
| 分支关系 | 与 `origin/master` 分叉：本地多 2 个 docs 提交，origin 多 1 个 #153 | `origin/master` HEAD |
| 代码差异 | 47 个文件，主要是 `AdminDashboardPage` / `AdminDataAnalysisPage` / `admin-analytics.css`（PR #153） | — |
| 后端 / 算法 / 迁移 | 两边一致 | 两边一致 |
| 文件数 | 2926（`git ls-tree` 一致） | 2926 |
| 迁移 head | V31 | V31 |

**影响判断：**

- 外部审计的后端、算法、安全、迁移结论可直接迁移到本工作树——这些文件在 #153 中未改动。
- 管理端 UI 结论需要按 `5dc2eac5` 重核：本报告已把 `WEB-08`、`WEB-12`、`WEB-13` 在
  `5dc2eac5` 上重新验证（见 §4 注）。
- 外部审计提到的 `WP-01A 配置中心` 不在 `master`，本报告不把它当作已进入基线。

---

## 2. 合并结果总览

| 严重度 | 合并后数量 | 说明 |
|---|---|---|
| **P0** | 0（1 条条件性） | 未发现已触发路径上的 P0；`S-02` 在 Python MCP 服务对外部署时升级为 P0 |
| **P1** | 22 | 安全边界 8、医学推理 2、数据恢复 4、Agent/检索 4、门禁与前端 4 |
| **P2** | 39 | 含外部新增 3 条、由 P1 降级 1 条 |
| **P3** | 23 | 与首版一致，外部无新增 |

两份来源的重叠与新增：

- **双方都报**：MCP 图片输入暴露面、质量门公式、BM25 非独立召回、Python 依赖、复诊 NPE、
  Docker 匿名卷（外部 PR #154）、MCP 超时语义。
- **外部独有且本地已复核**：管理员单条删除绕过、OOD 与异步任务准入不匹配、
  文档更新先删旧向量、Agent 图片 302 到原始 COS、批量上传空成功、上传孤儿对象、
  生产配置默认值、Session 生命周期。
- **内部独有**：新闻批量事务、医生复核原图 403、MCP 信号量泄漏/凭据日志/双初始化、
  `verify-fast` fail-open、会话导出隐私旁路、医生复核失焦清空、患者域契约漂移。
- **外部独有且本地不可验证**：GitHub 分支保护（已用 `gh api` 补验，见 `S-06`）。

---

## 3. P1 详述（22 条）

### 3.1 安全边界（8 条）

#### S-01 管理员账号保护策略不统一：单条删除与单条改角色都能绕过 `[已复核]`
- 来源：外部 P1-A + 内部 `BE-06`
- 位置：`controller/UserController.java:258-265`（单删）、`:178-189`（单改角色）；
  `service/impl/UserServiceImpl.java:379-399`（批量删有保护）、`:420-430`（批量改角色有保护）
- 证据：`deleteUser` 直接 `userService.removeById(id)`；批量 `deleteUsers` 在
  `:391` 明确拦截 `ADMIN_ROLE` 并注释"管理员账号不可删"。单条 `/user/update` 用
  `BeanUtils.copyProperties(userUpdateDTO, user)` 后 `updateById`，`userRole` 无白名单。
- 问题：管理员可单条删除管理员（含自己、最后一个管理员），也可把任意账号改成任意角色串。
  批量入口的安全策略被单条入口绕过，前端隐藏按钮不构成保护。
- 修法：把"管理员不可删、角色只能取 `user/ban/admin`、最后一个管理员保护"收进服务层
  单一策略，所有入口复用；用并发测试验证两个管理员同时互删的场景。

#### S-02 Python MCP 服务无鉴权绑定 `0.0.0.0`，开放 SSRF 与任意本地文件 `[已复核]`（部署即 P0）
- 来源：外部 P1-F + 内部 `ALG-01`
- 位置：`algorithm/mcp_server/config.py:9`、`algorithm/mcp_server/server.py:48`、
  `algorithm/mcp_server/image_handler.py:52-70`
- 证据：`host: str = "0.0.0.0"`；`mcp.run(transport="streamable-http", host=config.host, ...)`；
  `_resolve_url` 对任意 `http(s)://` 直接 `client.get(url)`，`_resolve_filepath` 对任意本地路径直接返回。
- 问题：无鉴权；任何可达调用方可抓内网/元数据地址（`169.254.169.254`）或把宿主机任意文件
  当输入；外网大响应先下载全量再判 50MB，可内存打爆。Java 侧 Agent 图片工具已改为只认
  服务端签发的 `imageId`，Python MCP 仍停留在"模型给什么就读什么"。
- 修法：默认绑 `127.0.0.1` 或受控私网；加服务鉴权；URL 主机白名单/禁私网段；路径限制到
  允许目录；优先接受后端签发的短期对象引用而不是任意 URL。

#### S-03 Agent 图片"已授权查看"后 302 到原始 COS 地址 `[已复核]`
- 来源：外部 P1-D
- 位置：`agent/controller/AgentImageUploadController.java:32-42`；
  `agent/service/AgentImageUploadService.java:125-133`；
  `manager/CosManager.java:91-92`
- 证据：`view()` 校验身份与归属后返回 `302`，目标是
  `resolveViewTarget()` 的 `cosClientConfig.getHost() + "/" + key`；同仓
  `CosManager.generatePresignedUrl()` 已实现，知识库预览（`KnowledgeBaseServiceImpl:250`）
  也在用，唯独这条链路没用。
- 问题：桶私有则浏览器收到 COS 403；桶公开则拿到原始地址即可绕过 Iris 登录与归属校验。
  **桶的实际 ACL 未验证**，但代码没有在这条链路提供私有访问签名，是确定的。
- 修法：数据库只存私有 objectKey；查看时重新鉴权后生成短期签名，或由服务端代理流式返回；
  明确区分头像等公开素材与患者影像。

#### S-04 生产基线配置不是"误部署也安全" `[已复核]`
- 来源：外部 P1-I
- 位置：`src/main/resources/application.yml:1-65`、`:27-32`
- 证据：`spring.profiles.active: local,dev`；`mybatis-plus...log-impl: StdOutImpl`；
  `knife4j.enable: true`；`SimpleLoggerAdvisor: debug`；30 天 Cookie 仅 `SameSite=Lax`，
  未强制 `Secure`；gRPC 客户端明文连接 `0.0.0.0:50051`。
- 问题：这些在开发环境不一定错，但作为共享生产基线默认继承时，SQL 参数、调试信息、
  接口文档与患者影像/会话数据可能一起暴露。代码里没有"生产必须覆盖"的守卫。
- 修法：生产 profile 显式关闭 SQL stdout / knife4j / Advisor debug；`Secure` 由 HTTPS
  profile 强制；gRPC 跨不可信网络时加 TLS/mTLS 或网络隔离；启动时对危险默认值 fail-fast。

#### S-05 会话生命周期未收紧：登录不换 SessionId，登出不 invalidate `[已复核]`
- 来源：外部 P1-J
- 位置：`service/impl/UserServiceImpl.java:121-215`
- 证据：`userLogin` 成功后直接 `request.getSession().setAttribute(USER_LOGIN_STATE, user)`；
  `userLogout` 只 `removeAttribute`，未 `invalidate()`；`getLoginUser` 用
  `request.getSession()` 而非 `getSession(false)`。
- 问题：登录前后可能沿用同一会话标识；退出后会话容器仍在。不能据此断言可利用的
  Session Fixation，但在 Cookie 会话系统中这是应主动关闭的风险。
- 修法：登录成功 `request.changeSessionId()`；登出 `invalidate()`；取登录态用
  `getSession(false)`；补"登录前后 SessionId 变化"测试。保留现有 BCrypt、MD5 升级、封禁重校验。

#### S-06 `master` 无分支保护 `[已复核 · GitHub API]`
- 来源：外部 P1-K
- 证据：`gh api repos/yuwen773/iris-ocular/branches/master/protection` 返回
  `404 Branch not protected`；`gh api repos/yuwen773/iris-ocular/rulesets` 返回 `[]`。
- 问题：最新 `master@5dc2eac5` 的 verify 工作流 22/22 成功，但 GitHub 没把这些检查变成
  不可跳过的合并门槛；直接推送、强推、绕过审查都可行。
- 修法：启用分支保护：要求 PR、必要状态检查、至少一次独立审查、禁止强推；
  对迁移 / 安全策略 / 医疗证据契约加 CODEOWNERS 或等价专项复核。

#### S-07 上传孤儿对象窗口存在，补偿任务默认关闭 `[已复核]`
- 来源：外部 P1-G
- 位置：`agent/service/AgentImageUploadService.java:71-105`；
  `upload/state/UploadCompensationService.java:24-40`；`application.yml:110-116`
- 证据：`imageArchive.storeBlob(objectKey, tempFile)` 成功后写 `t_upload_task`；
  若 DB 插入失败，`catch` 只做内存状态机 `FAILED` 转换，未删除已写对象。注释承认
  "补偿对账只是兜底"，而 `upload.compensation.enabled: false`。
- 问题：进程在"COS 写成功、任务行落库前"崩溃会留下无任务行的孤儿对象；
  判读影像的 `public/` 前缀按代码注释没有对应自动对账器。
- 修法：对象写入与任务登记建立持久补偿（先确认对象仍被有效记录引用再删）；
  默认开启补偿或至少在启动日志中显式警告；迁移到 MinIO/S3 时不要让删除依据只依赖旧状态表。

#### S-08 医生复核"回放原图"在普通患者任务上恒 403 `[已复核]`
- 来源：内部 `BE-02`（外部未覆盖）
- 位置：`controller/DoctorReviewController.java:59-70`；
  `collaboration/CollaborationImages.java:26-31`
- 证据：`readTask()` 要求 `t_agent_collab_run` 恰好一行；全仓库 `INSERT` 只有
  `CollaborationRunService.java:99`（协作准入，默认关闭）。普通 `/api/diagnosis-tasks`
  任务从不写这行。
- 问题：文档把该端点当作"医生可回放原图"的依据，实际普通任务必 403；即便有行，
  还需 `clinicianDetailEnabled`（默认 false）。`docs/doctor-review.md` 接口表里也没有它。
- 修法：直接按 `t_diagnosis_task.objectKey/objectVersion` 读（医生授权已在上游校验），
  不要绕协作准入；补进 `doctor-review.md` 与 `DoctorReviewApiTest`。

### 3.2 医学推理（2 条）

#### M-01 OOD `NOT_EVALUATED` 与异步患者任务准入不匹配，任务恒 `RESTRICTED` `[已复核]`
- 来源：外部 P1-B
- 位置：`algorithm/grpc_server.py:118-120,152`；
  `diagnosistask/DiagnosisTaskRunner.java:96-101`
- 证据：Python 返回 `ood = OodDecision(is_ood=False, score=0.0, threshold=0.0)` 与
  `ood_status=NOT_EVALUATED`；Java 要求
  `response.getOodStatus() == IN_DISTRIBUTION` 才允许 `WAITING_REVIEW`，否则 `RESTRICTED`。
  `DiagnosisTaskRecoveryTest` 显式测试了 `NOT_EVALUATED → RESTRICTED`。
- 问题：当前 Python 输出若直接服务异步患者任务，即使分类成功也永远进不了待复核。
  这是**有意 fail-closed 的产品准入规则**，不是枚举写错；不能为了跑通把
  `NOT_EVALUATED` 当成 `IN_DISTRIBUTION`。
- 修法：保留门禁，明确区分"研发演示推理"与"满足证据准入的患者任务"；建立版本绑定的
  OOD 验证产物、模型指纹与审核门槛；前端如实说明任务为何被限制。
- 关联：传统 `/diagnosis/diagnosis` 允许未拒识的 gRPC 结果生成分类结论，与异步任务
  准入不一致——按产品场景统一契约（外部审计单列为 P1，本报告并入本条）。

#### M-02 质量门公式错误，且 reliability/confidence 语义未分离 `[已复核]`
- 来源：内部 `ALG-02` + 外部 P1-H
- 位置：`algorithm/eye_diagnosis/quality.py:32-37`；`algorithm/grpc_server.py:120-157`
- 证据：`_laplacian_variance` 返回
  `np.var(gray * ifft2(fft2(gray) * fft2(k)).real)`——"灰度 × 拉普拉斯响应"的方差，
  不是拉普拉斯响应本身的方差；阈值 `12.0` 因此失效。gRPC 把 softmax Top-1 直接写进
  `probability`、`reliability_score`，`calibrated_probability` 只在 `CALIBRATED` 时非零。
- 问题：`enforce` 模式下会大量放过真正模糊的输入；UI 可能把未校准的 softmax 读成
  "医学诊断可靠性"。
- 修法：改为 `np.var(laplacian(gray))` 并按真实数据重标阈值；把"分类概率 / 校准状态 /
  图像质量 / OOD / 是否允许患者可见结论"拆成独立字段，各自带版本与验证证据。

### 3.3 数据与恢复（4 条）

#### D-01 知识库文档更新先删旧向量，再建新版本 `[已复核]`
- 来源：外部 P1-C
- 位置：`service/impl/KnowledgeBaseServiceImpl.java:284-313`
- 证据：`updateDocument()` 第一件事是 `deleteVectors(doc)`，之后才上传新文件、更新 DB、
  触发异步向量化。
- 问题：新文件上传/解析/Embedding/写 Chroma 任一步失败，旧向量已没了；一次失败的编辑
  会让原先可检索的知识立即不可用。`indexVersion` 能防旧向量被错误引用，
  **但索引安全不等于索引可恢复**。
- 修法：`旧 READY → 创建候选版本 → 候选向量完整写入 → 验证 → 原子切换发布指针 →
  延迟清理旧版本`。

#### D-02 批量上传回执不完整 + 临时文件泄漏 `[已复核]`
- 来源：外部 P1-E + 内部 `BE-03`
- 位置：`service/impl/KnowledgeBaseServiceImpl.java:95-165`、`:284-316`
- 证据：`future.join()` 的 `catch (Exception)` 只 `log.error`，不把文件加入 `failedFiles`；
  非 `IOException` 异常会造成"成功 ID 为空、失败列表也为空、API 正常返回空列表"。
  `File.createTempFile("upload_", originalFilename)` 用未清洗原始名作后缀；重复命中
  （`:112-114` 直接 return）时临时文件无人删除；`try` 无 `finally`。
- 问题：上传回执不可信；每次重复/失败都在 `java.io.tmpdir` 留孤儿文件（仓库有过
  "临时文件写满盘"的历史）；`uploadExecutor` 无界队列也没有背压。
- 修法：每个文件必须有成功或失败终态；`join()` 异常写入 `failedFiles`；
  `try/finally` 兜底删临时文件；后缀用扩展名白名单；线程池/队列设边界。

#### D-03 新闻批量写绕过事务，状态与审计可分裂 `[已复核]`
- 来源：内部 `BE-01`（外部未覆盖）
- 位置：`service/NewsLifecycleService.java:117-135`、`:144-172`
- 证据：`softDeleteBatch`/`changeStatusBatch` 内部 `this.softDelete(...)`、
  `this.applyEdit(...)` 自调用；两个批量方法自身没有 `@Transactional`。
- 问题：类注释承诺"状态迁移、审核落盘、审计、软删都在一个事务内"，批量路径实际各自提交；
  审计插入失败时状态已改、审计丢失，调用方却看到"失败"。
- 修法：批量方法加 `@Transactional`，或把"单条迁移 + 审计"下沉到独立 bean /
  `TransactionTemplate` 逐条提交；补批量路径测试。

#### D-04 文档 ETL 依赖本地 `@Async`，未见持久恢复链路 `[已复核 · 静态]`
- 来源：外部审计（P1 表）
- 位置：`service/impl/DocumentProcessingService.java:61-90`
- 证据：`@Async("documentProcessingExecutor")` + 本地临时文件；`beginIndex(docId, version)`
  只做版本栅栏；未发现按 `PENDING`/超时扫描的恢复任务。
- 问题：进程在 ETL 中途退出，任务可能停在中间状态且无人接手；重启后没有自动恢复。
- 修法：为长时 ETL 增加持久任务状态、租约与恢复扫描；异常退出后能续跑或进入明确失败终态。
  `[需核实]`：真实 MySQL 下 PENDING 行的实际残留比例。

### 3.4 Agent / 检索 / 日志（4 条）

#### A-01 MCP 信号量在任务提交被拒时永久泄漏 `[已复核]`
- 来源：内部 `AG-01`
- 位置：`agent/manager/MCPManager.java:146-166`
- 证据：`mcpExecutor.submit(...)` 在 `try` 之外；`AbortPolicy` 饱和时同步抛
  `RejectedExecutionException`，`finally { gate.release(); }` 不执行。
- 问题：累计 `per-server-max`（默认 8）次拒绝后该 MCP 服务永久 `McpOverloadException`。
- 修法：把 `submit` 纳入 `try`，或 `tryAcquire` 后立即进入 `try/finally`。

#### A-02 MCP 凭据写入运行日志 `[已复核]`
- 来源：内部 `AG-02`
- 位置：`MCPManager.java:238`、`:243`
- 证据：`log.info("成功解析 SSE URL: {}", config.getUrl())`；
  `log.warn("解析 deployConfig 失败: {}", configJson, e)`。
- 问题：`deployConfig` 可含 `authorization`/token，URL query 也可能带 token；失败时整份 JSON
  进日志。类注释自己写明"query 里可能带 token"。
- 修法：只记 host 与异常类型；失败不打印原文。

#### A-03 患者原文进入运行日志 `[已复核]`
- 来源：内部 `AG-03`
- 位置：`agent/service/TitleGenerationService.java:38`
- 证据：`log.info("会话标题已更新, sessionId={}, title={}", sessionId, title)`，title 由首条
  消息前 20 字拼成。
- 问题：与 `AgentController`"症状、病史等原文不得进入运行日志"直接冲突。
- 修法：只记 sessionId 与长度。

#### A-04 BM25 不是独立词法召回，混合检索默认开启 `[已复核]`
- 来源：内部 `AG-07`（原 P2）+ 外部审计（P1）
- 位置：`application.yml:141-142`；`agent/tools/DocumentSearchTool.java:404-425`
- 证据：`refreshDocumentCache` 用 `similaritySearch("*", topK 1000, threshold 0)` 冒充
  "全部文档"，再在本地算 BM25；`hybrid-search.enabled: true`。
- 问题：`"*"` 只是语义近邻的 1000 条子集，超过 1000 片段或分布偏离时静默丢失词法召回；
  BM25 命中还可能绕过 `similarity-threshold` 进入证据；`documentCaches` 按 userId 只增不减。
- 修法：按 owner 元数据直取或独立全文索引做词法召回，再用现有 RRF + Rerank 融合；
  缓存加容量/驱逐；明确记录阈值旁路。
- 严重度裁决：内部原列 P2，合并后升为 **P1**——因为混合检索默认开启，直接影响证据召回完整性。

### 3.5 门禁、前端与患者域（4 条）

#### G-01 `verify-fast.sh` 的 L0 对 web 类型检查是 fail-open `[已复核]`
- 来源：内部 `DE-01`
- 位置：`scripts/verify-fast.sh:150-156`、`:161`、`:320`
- 证据：无 `web/node_modules/.bin/tsc` 时只打印"跳过"，不 `record_stage`，`rc_any` 仍为 0，
  主流程打印 `RESULT: PASS` 并 `exit 0`。
- 问题：web-only 改动在默认 L0 路径下没做任何类型检查却报 PASS。
- 修法：web 变更且 tsc 不可执行时记失败或 exit 2，除非显式 `--allow-missing-web-deps`。

#### G-02 会话导出文件名用模型生成的会话标题，隐私契约实现是死代码 `[已复核]`
- 来源：内部 `WEB-01`
- 位置：`web/src/lib/sessionExport.ts`；`web/src/pages/AgentPage.tsx:541-551`
- 证据：`sessionExport` 注释明写不得把会话标题/消息内容拼进离开浏览器的文件名，
  `exportDescriptor` 只产出 `会话导出-<id>.<ext>`；但全仓仅测试引用它，生产用
  `safeFileName(session.title)`。
- 问题：会话标题由模型从患者症状生成，导出会把症状文本写进磁盘与分享链；测试覆盖的是死代码。
- 修法：`AgentPage` 改调 `sessionExport`，删本地 `safeFileName`/`downloadText`。

#### G-03 医生复核页窗口失焦即清空已填意见 `[已复核]`
- 来源：内部 `WEB-02`
- 位置：`web/src/pages/DoctorReviewsPage.tsx:110-125`
- 证据：`window.addEventListener("blur", clear)`，`clear` 里 `setDetail(null); setNote(""); setList([])`。
- 问题：切窗口查资料、开 devtools 都会销毁正在填写的复核意见，无提示、无草稿。
  `docs/doctor-review.md:34` 只要求"切换身份会清理材料"。
- 修法：删 `blur` 监听，改监听会话身份变化；确需防偷窥用遮罩 + 点击恢复。

#### P-01 未预期异常被记成 `INVALID_OUTPUT` `[已复核]`
- 来源：内部 `PD-01`
- 位置：`patientdraft/PatientDraftCoordinator.java:93`
- 证据：`catch (Exception e) { log.error(...); restrict(taskId, "INVALID_OUTPUT", ...); }`
- 问题：`docs/patient-draft.md:40` 明令 `INVALID_OUTPUT` 只表示输出不合格；DB 死锁、NPE、
  落库失败都会被写成"模型输出问题"，运维按停止原因排查会走错方向。
- 修法：兜底 reason 改独立 `INTERNAL_ERROR`，或复用 `transportFailure` 的精细分类。

---

## 4. P2 合并表（39 条）

> 首版报告 §3.2 的 37 条全部保留；本表只列**合并后的编号与变化**。新增/变化行标 ★。

| 编号 | 问题 | 来源 / 变化 |
|---|---|---|
| BE-04 | `@AuthCheck` 只对 ADMIN 生效，doctor/evidence_reviewer 角色形同虚设 | 内部 |
| BE-05 | 复诊申请对不存在记录 NPE → 500 | 内部 + 外部 P2 |
| BE-07 | `pageSize` 无上限 | 内部 |
| BE-08 | 业务异常按 ERROR 打堆栈 | 内部 |
| BE-09 | 缓存 JSON 走 hutool `JSONUtil`，与 `JsonUtils` 约定漂移 | 内部 |
| ★ BE-10 | `SameOriginFilter` 只比 host+port，不比 scheme，HTTPS 下 Origin 契约不严格 | 外部 P2 |
| PD-02 | `patient-authorization.md:36` 写 35 项，门禁脚本声明 37 | 内部 |
| PD-03 | 草稿播种资料绕过受控治理（`isTest=0`、上传者=审核者） | 内部 |
| PD-04 | 草稿轮询用写锁事务忙等 | 内部 |
| ★ AG-04 | STREAMABLE_HTTP 客户端被 initialize 两次（SDK 非幂等性 `[需核实]`，故由 P1 降为 P2） | 内部 |
| AG-05 | MCP"会话覆盖"与文档优先级不一致，禁用态不可达 | 内部 |
| AG-06 | MCP 失败静默降级为空工具列表 | 内部 |
| AG-08 | 历史轮次取消重生成时新旧答案同屏 | 内部 |
| AG-09 | 单条坏历史行让整页历史 500 | 内部 |
| AG-10 | 聊天 SSE 缺 `X-Accel-Buffering`/心跳/超时 | 内部 |
| ★ AG-11 | BM25 缓存按 userId 只增不减、刷新 `synchronized` 串行 | 外部 P2 |
| ★ AG-12 | `Future.cancel(true)` 不保证底层 MCP 工具停止，`finally` 已释放许可 | 外部 P2 |
| DE-02 | V27 是死迁移，`ascii_bin`/`DATETIME(6)`/索引从未生效 | 内部 |
| DE-03 | `verify.sh` 不跑 skip 门禁，与 CI 口径漂移 | 内部 |
| DE-04 | `verify-fast.sh --db` 漏匿名卷、镜像默认漂移 | 内部（外部 PR #154 同项） |
| DE-05 | `spring.flyway.schemas` 固定 `diagnosis` | 内部 |
| DE-06 | tool-audit HMAC 有公开弱默认密钥 | 内部 |
| WEB-03 | `response.json()` 无守卫，非 JSON 错误体绕过 `ApiError` | 内部 |
| WEB-04 | `signOut` 的 logout 失败未兜住 | 内部 |
| WEB-05 | 协作深链把网络/5xx 压成"未授权或已失效" | 内部 |
| WEB-06 | 会话列表失败渲染成"暂无历史会话" | 内部 |
| WEB-07 | 硬编码色 ratchet 只扫 CSS，TSX 是盲区 | 内部 |
| WEB-08 | 管理仪表盘把全站累计挂在"近 N 天"面板下 | 内部（`5dc2eac5` 复验仍成立） |
| WEB-09 | Agent 图片预览是手写第二套弹层 | 内部 |
| WEB-10 | `/records` 与 `/profile` 对同一病名显示不同 | 内部 |
| WEB-11 | 四个管理列表页约 450 行筛选/删除脚手架复制 | 内部 |
| WEB-12 | `.feature-card` 规则散在 4 个 CSS（#153 后 `admin-polish-analysis` 换成 `admin-analytics`） | 内部（`5dc2eac5` 复验：15+26+26+1） |
| WEB-13 | 9/9.5/10/10.5px 标签七处，低于 11px 下限且未登记 | 内部（`5dc2eac5` 复验仍成立） |
| ALG-03 | `allow_sha256: []`，默认启动必失败 | 内部 |
| ALG-04 | `requirements.txt` 下界与代码 API 不兼容 | 内部 + 外部 P2 |
| ALG-05 | 两套类别顺序共存，`frozen_eval` 只认服务侧类序 | 内部 |
| ALG-06 | 启动 `pretrained=True` 再覆盖；MCP 权重路径硬编码 | 内部 |
| ALG-07 | MCP 推理绕过 quality/OOD/calibration，reliability 语义与 gRPC 不同 | 内部 |
| ALG-08 | CI 仍把已弃用 `frontend/` 当硬门禁，`lint:check` 重复两次 | 内部 + 外部 |

---

## 5. P3

与首版报告 §3.3 的 23 条一致，外部审计没有新增 P3。最值得顺手处理的：

- `ToolAuditService:76-77` 终态行 `startedAt=finishedAt`，无法算时长。
- `DocumentParser:46-51` 保留 HTTP 抓取入口，是 SSRF 原语。
- `DiagnosisTaskService:43-45` 10MB 文件却允许 4000 万像素解码。
- `V16/V17/V22/V30` 既有大表 ALTER 未带 `ALGORITHM/LOCK`。
- `DoctorReviewsPage:55` 在无 role 的 div 上挂 `aria-label`。
- `RecordsPage:91-103` 复诊后未失效 `records.stats`。
- 弃用 `frontend/` 仍有 `v-html` 渲染模型/资讯内容。

---

## 6. 两份审查的冲突与裁决

| 事项 | 内部分级 | 外部分级 | 合并裁决 |
|---|---|---|---|
| BM25 非独立召回 | P2（`AG-07`） | P1 | **P1**：混合检索默认开启，直接影响证据召回完整性 |
| 管理员改角色 | P2（`BE-06`） | P1（与单删合并） | **P1**：单删与单改角色是同一策略缺口 |
| MCP 双 initialize | P1（`AG-04`） | 未提 | **P2**：SDK 行为 `[需核实]`，未证实必抛错 |
| Python MCP 暴露面 | P1 | P1 | **P1**；对外部署时 **P0** |
| 质量门公式 | P1（`ALG-02`） | P1（更宽的可靠性闭环） | **P1**，并把 reliability/confidence 语义分离并入 |
| COS 302 | 未覆盖 | P1 | **P1**；桶 ACL 未验证，代码层"无签名"确定 |
| 分支保护 | 未覆盖 | P1 | **P1**，已用 `gh api` 现场复核 |
| OOD 门禁 | 未覆盖 | P1 | **P1**；这是产品准入阻断，不是应放宽的 Bug |
| `verify-fast` fail-open | P1 | 未覆盖 | **P1**，本地默认路径 |
| 新闻批量事务 | P1 | 未覆盖 | **P1** |

**没有发现的矛盾：** 两份审查对"哪些是强项"的判断一致——服务端归属校验、BCrypt/封禁重校验、
RAG 的 `indexVersion` 代际闸、协作的幂等与 SSE 权限重验、Flyway 迁移纪律、前端 XSS 分层，
都值得保留。外部审计明确建议**不要推翻** Java + Spring AI Alibaba + React + Python gRPC
主路线，本报告同意。

---

## 7. 统一整改工作包

| 工作包 | 内容 | 对应条目 | 验收重点 |
|---|---|---|---|
| **WP-S1 安全边界**（最高） | 管理员策略、私有影像访问、MCP 输入边界、Session、生产配置、分支保护 | S-01～S-08、G-02 | 越权/匿名/跨用户/已删对象/会话失效/外部 URL 负例必须失败 |
| **WP-R1 数据与恢复** | 上传回执、分代索引与原子发布、对象登记与对账、ETL 恢复、新闻事务 | D-01～D-04、S-07 | 模拟 MySQL/COS/Chroma 故障、进程强杀、重复提交、迟到任务 |
| **WP-M1 医学推理契约**（生产开放前必须） | OOD 产物、质量算法、校准与 reliability 语义、两套判读准入统一 | M-01、M-02 | 不合格/分布外/未校准/服务异常不能伪装成临床结论 |
| **WP-Q1 RAG 与工程治理** | 独立词法召回、缓存与线程池、迁移收敛、CI 与本地门禁一致 | A-04、AG-11、DE-02～DE-06、G-01 | Recall@K、引用有效率、延迟、内存、可复现 CI |
| **WP-F1 前端与隐私** | 导出文件名、复核页失焦、错误态、口径标注、弹层与列表结构 | G-02、G-03、WEB-03～WEB-13 | 隐私文本不落盘、错误 ≠ 空、四场景与令牌一致 |

**顺序建议：** WP-S1 → WP-R1 → WP-M1 → WP-Q1/WP-F1。
WP-S1 的多数条目改动小、可独立验证，适合先做；WP-M1 是"生产开放患者判读"的前置阻断，
不应被 UI 或新 Agent 角色插队。

---

## 8. 统一验收矩阵

| 场景 | 必须证明的结果 | 对应条目 |
|---|---|---|
| 单条删除最后一个管理员 | 被拒绝，管理入口保留 | S-01 |
| 单条把普通用户改成 `admin`/`doctor` | 受角色白名单约束 | S-01 |
| 用户 A 读取用户 B 的眼底图（知道 objectKey） | 不能绕过授权 | S-03 |
| 私有 COS 图片查看 | 登录后可读，授权过期后不可读 | S-03 |
| MCP 请求内网 IP / 重定向到内网 | 连接被拒绝 | S-02 |
| 文档替换到一半 Chroma 不可用 | 旧 READY 版本仍能检索 | D-01 |
| 上传已写 COS、数据库插入失败 | 有失败回执，对象最终可核对 | D-02、S-07 |
| ETL 处理途中进程退出 | 可恢复或进入明确失败终态 | D-04 |
| Python 返回 `NOT_EVALUATED` | 前端如实提示不可满足患者任务准入 | M-01 |
| 高 softmax 但未校准 | 不展示为已验证的医学可靠性 | M-02 |
| 用户封禁期间已有 SSE 连接 | 后续事件无法继续越权读取 | S-05 |
| 1 万以上知识分块参与混合检索 | 无不可控内存增长、无长期串行阻塞 | A-04、AG-11 |
| 新闻批量删改中途失败 | 状态与审计一致，回执如实 | D-03 |
| 医生复核普通患者任务 | 原图可读 | S-08 |
| 导出会话 | 文件名不含会话标题/症状文本 | G-02 |
| 医生填写复核意见时窗口失焦 | 输入保留 | G-03 |
| web-only 改动、无 `node_modules` | `verify-fast` 不报 PASS | G-01 |
| PR 未通过必要 CI | 无法合并 `master` | S-06 |

---

## 9. 审计限制

- 两份审查都是**静态审查**：没有启动 Java/Python 服务，没有连接真实 MySQL/Redis/Chroma/COS，
  没有加载模型权重，没有跑安全渗透，也没有审查本地未提交的 WP-01A 配置中心。
- 外部审计提到的 "master Actions 22/22 成功" 是工作流证据，不等于生产环境、真实模型、
  权限滥用与故障恢复都通过。
- 外部审计的代码行号基于 `5dc2eac5`；本报告已在本工作树复核，管理端 UI 条目另按
  `5dc2eac5` 重验。
- COS 桶的实际 ACL 未验证；`S-03` 的"无签名"是代码事实，"公开/私有"是部署事实。
- `AG-04` 依赖 MCP SDK 对重复 `initialize()` 的具体行为，标 `[需核实]`。
- `D-04` 的 ETL 残留比例需要真实 MySQL 才能量化。
- 统计口径（如 `avgConfidence` 是否 ×100）只做了跨页一致性核对，未逐条回后端 SQL 验算。

---

## 10. 结论

合并后共 **22 条 P1、39 条 P2、23 条 P3**，其中 P0 为 0（`S-02` 对外部署即 P0）。

最该先做的五件事：

1. **S-01** 管理员账号保护收口（单删与单改角色）。
2. **S-02** Python MCP 服务默认绑定与输入边界。
3. **S-03** 患者影像私有访问（签名或代理，不再 302 原始 COS）。
4. **M-01** OOD 准入契约——这是"能不能开放患者判读"的阻断条件。
5. **D-01** 知识库更新的分代索引与原子切换。

Iris 的工程底子足以继续走现有技术路线；现在缺的不是新功能，而是把**安全边界、恢复闭环、
医学证据契约**这三件事做成有验收门槛的收敛。
