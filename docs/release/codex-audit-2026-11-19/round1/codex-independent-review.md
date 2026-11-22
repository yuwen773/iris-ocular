# Iris RC Independent Audit Report

**NO_GO** · 2026-11-19（UTC+8） · Draft PR #119 → `ui`

视觉工具会把无法解析的上游响应记录为成功。归档工具在备份失败后仍允许强制删除工作树。另有工具状态回放、知识库入库和回滚手册的阻断问题。所有结论仅适用于下表锁定的候选。这份报告及复现文件保存在独立审查目录。

## 1. 交接门禁与候选

| 项目 | 复核结果 |
|---|---|
| Claude 终态 | `RC_READY_WITH_EXTERNAL_GATES`。核对四份 release 文件及 Claude 会话 `4cc40376-c288-4b9c-a4b7-ab8c6f9c12bb` 最后一条最终报告，记录为 end_turn。另一个更新更晚的会话是已结束的 PR #117 合并。 |
| 候选 | `integration/iris-release-candidate@7dc3422b70828d400ad1d3a09e8c29aa12f48309` |
| Tree SHA | `ddce8b81f9681bdfa3cecb396dfcfaa7d9fcf9a9` |
| 上一代码 SHA | `6ff5a889daaac2e489db0f4ddb710c360e60fee1`。候选在此基础上仅更改 3 份 release 文档，已核对父提交与差异。 |
| 集成树 | `.worktrees/integration-rc` 工作树干净，upstream 为 +0/-0，未发现仍在修改该候选的任务。多次独立远端读取返回同一 SHA。 |
| `origin/ui` | `1ff9c71c2fcdffc6647fb51c2d0d40e99b7a9a21`，候选 ahead 47 / behind 0，246 文件差异。 |
| `origin/master` | `a0efaf46d6f8b53bea4a35d613fc806e2f6e9d47`，候选 ahead 431 / behind 0。 |
| 旧 release | `origin/release/v1.0.0@5ac742f0db30ded1c87462211a535012d952e4bb`，候选 ahead 553 / behind 27，有独立迁移史。 |
| UI 冻结 tag | 远端 annotated tag 的解引用为 `80f53f7f55955c2d30a1a9c98479dcb87682051b`。 |
| Hosted CI | #119 为 OPEN / Draft。两个 SHA 分别在 run 44442060009 和 run 44443511141 的 attempt 1 完成，均 21/21 job 成功。后者覆盖当前候选。 |

[候选 PR](https://github.com/yuwen773/eyes-diagnosis/pull/119) · [当前候选 CI](https://github.com/yuwen773/eyes-diagnosis/actions/runs/44443511141) · [父提交 CI](https://github.com/yuwen773/eyes-diagnosis/actions/runs/44442060009)

## 2. PR 与基线

- 对 GitHub 全部开放 PR 的实时 head 作 Git 祖先核验。13 个 INCLUDE 均为候选祖先，包括 #101、#105–#116。#14 和 #68 已是 `origin/ui` 的祖先。#102、#103、#104 三个 DEFER 的 head 均不在候选树，未发现遗漏的已批准开放 PR。
- 第一父链与台账里的合并提交对应。`git cherry origin/ui HEAD` 无 patch-equivalent 旧提交信号，新非合并提交的非文档 patch-id 未发现重复。合并可达证明提交来源，内容冲突裁定另以实际代码和下述反例审查。
- #111/#117 合流保留一条工具事件链。#112 预算在 Agent 每次构建时新建。R2 资讯状态层调整触及冻结相关页面，浏览器按错误、空态、缓存恢复和布局检查。相对 ui 对静态首页、品牌组件、管理页和主 tokens 的路径核对没有差异，公开资讯页两处调整见 #101。双主题布局抽查见测试矩阵。
- `origin/ui..HEAD` 的 Flyway migration 和 callback 目录零差异。切换自 V17 或旧 release 是另一种升级场景，见 P1-04。

## 3. P0 / P1 缺陷和给 Claude 的修复任务

### P0-01 · 视觉工具把无效上游输出登记为成功

- 路径 `src/main/java/com/csust/eyediagnosis/agent/tools/EyeImageAnalyzerTool.java:177–196`。只拒绝空白，`{}` 被转成全空结果，解析异常则返回普通 DTO，还填入未经分析得出的 `attention`。`ToolExecutionInterceptor.java:79–95` 对普通返回登记成功。
- 使用已登记、归属当前用户的合成影像和模拟 COS 字节，运行真实 `UserBoundEyeImageAnalyzerTool`、`MethodToolCallback`、`ReactAgent`、`DiagnosisAgent.stream`。以空白、空 JSON、无法解析的服务提示分别作为视觉提供方返回。预期三者都失败。空白失败，其余两者实际 SSE DTO 均为 `running → succeeded`，历史写入调用携带 `succeeded`，审计记录为 `SUCCEEDED/NONE`，后续模型收到普通分析对象。模拟模型和存储夹具用于注入，真实 COS 与实时付费提供方属于外部门禁。
- 影响普通诊断聊天的影像分析。界面、审计和回放会把未得到有效分析的调用标为完成，后续模型也会拿到虚构的关注等级。
- **修复与回归**：在工具边界验证模型输出结构、必要字段和枚举，将不可用或空壳输出归为 INVALID_OUTPUT。回归使用真实绑定方法与 ReactAgent，断言 SSE、实库历史与审计均只记录失败，无证据或关注等级输出，另保留合法结构成功对照。
- 复现 [IrisToolAuditProbe.java](../../IrisToolAuditProbe.java) · [tool-probe.log](../../tool-probe.log)

### P0-02 · 备份失败后清理脚本仍继续强制删除

- 路径 `scripts/worktree-hygiene.sh:79–99,137–153`。备份操作失败后函数仍执行成功的末尾 `echo`。调用方依赖函数返回码判断是否执行 `git worktree remove --force`。
- 最小复现使用只提取归档函数的夹具，令 tar 返回 73。预期归档返回非零，实际输出归档成功且返回 0。复现只运行替身命令，没有删除或移动工作树。磁盘满、权限或不可读文件都会触发这条路径。另有同日同名归档覆盖旧文件及 detached unique HEAD 只保存 SHA 文本的问题。
- 影响主动使用 #116 的维护者。未提交内容可能永久丢失，脚本被项目收尾手册引用。
- **修复与回归**：归档逐步检查并验证可读，唯一且原子发布完成的备份。持久保留独有对象的 ref 或 bundle。用临时夹具注入 mkdir、git diff、tar 失败和重名，断言整个命令非零且强制移除函数调用为零，并演练还原独有提交与未跟踪文件。
- 复现 [standards-archive-failure.sh](../../standards-archive-failure.sh) · [standards-archive-failure.log](../../standards-archive-failure.log)

### P1-01 · 超时、取消和真实回放状态不一致

- 路径 `src/main/java/com/csust/eyediagnosis/agent/manager/MCPManager.java:143–163`、`ToolExecutionInterceptor.java:79–99`、`ToolStepSink.java:68–83,97–113,124–146`、`DiagnosisAgent.java:295–308,322–335`，以及 `web/src/lib/agentStream.ts:184–199,326–342`、`web/src/components/agent/ThoughtLine.tsx:129–155`。
- MCP manager 的 timeout 经框架同步工具分支转换为 `ToolCallResponse.error`，类型丢失，观察器写成 TOOL_EXCEPTION。能保留 TIMEOUT 类别的方法工具也统一出站为 `failed`。客户端和历史丢失类别。审计取消后历史仅从可被取消的 SSE 订阅写入，断连或停止可留下只有审计终态的回合。前端把终态空正文也当成「工具返回了空结果」。
- 使用真实 MCP callback、ReactAgent 和合成 transport 超时，实际为 `FAILED/TOOL_EXCEPTION`。sink 夹具在退订后结束 pending，审计为 `CANCELLED`，观察流无取消终态。浏览器用候选真实帧 `toolStatus: failed, errorCategory: TIMEOUT`，双主题均显示「失败」，4 条超时断言失败。原始 R3 脚本用旧 `status` 字段，相关截图不能证明新契约。
- **修复与回归**：用有类型的执行结果区分拒绝、失败、超时与取消。终态持久化挂在状态转移，订阅取消后仍可刷新恢复。调整前端终态和正文逻辑。用真实框架、MySQL、SSE 退出、重连获取历史及界面断言验证超时和取消，补一条迟到结果不得覆盖已取消的测试。
- 复现 [IrisRuntimeAuditProbe.java](../../IrisRuntimeAuditProbe.java) · [runtime-probe.log](../../runtime-probe.log) · [候选帧浏览器结果](../../r3-agent-acceptance-candidate-wire/report.json)

### P1-02 · 分批向量写入失败后，半份文档仍成为 RAG 来源

- 路径 `src/main/java/com/csust/eyediagnosis/agent/rag/EmbeddingBatchWriter.java:24–30`、`service/impl/DocumentProcessingService.java:120–132,144–151`、`agent/tools/DocumentSearchTool.java:91–123,212–230`、`service/impl/KnowledgeBaseServiceImpl.java:287–292,348–353`。
- 文档状态与向量独立提交。第二批失败只把 MySQL 状态标为失败，第一批仍可检索，清理又依赖可能缺失的 chunkCount。
- 用实际 ETL 和记来源方法，注入 11 个合成片段与第二批写入失败。预期失败文档没有可引用片段。实际状态为 2，chunkCount 为空，向量后端模拟保留 10 个片段，检索把片段交给模型并登记 `doc:124`。影响同一用户的来源完整性和失败后的删除／重试。
- **修复与回归**：分代暂存到完整成功再发布，或对失败批次进行可重试补偿并以可信文档就绪状态收紧读取。清理按文档索引元数据识别残留。回归在隔离 MySQL/Chroma 中测试第二批失败、重试和删除，要求文本与 ResourceRef 都无失败代的片段。
- 复现 [IrisRagBatchAuditProbe.java](../../IrisRagBatchAuditProbe.java) · [rag-batch-probe.log](../../rag-batch-probe.log)

### P1-03 · 用户文本可被历史解析器误认为受信影像附件

- 路径 `src/main/java/com/csust/eyediagnosis/agent/service/ChatHistoryService.java:67–80`、`controller/AgentController.java:250–254`、`web/src/lib/agentStream.ts:300–307,444–454`、`web/src/components/agent/messages/UserMessage.tsx:26–43`、`web/src/lib/agentResources.ts:138–145,248–260`。
- 服务端在无显式附件时保存普通文本；历史解析器只看文本结构即可生成图片数组，渲染层把它当成上传影像并加载。普通内容被误读为附件会丢失原文本和来源真实性，涉及历史和资源栏。
- **复核及回归**：源代码确认。回归用无 URL、无附件元数据的普通结构化用户文本，要求刷新后逐字保留且不创建附件，再用已验证上传做正对照。另外使用非加载的旧数据夹具验证可信来源的兼容。独立持久化附件类型，由读取端确认归属，自动加载限定于可信站点路径。当前只作防御性源码审查。

### P1-04 · V17 升级后的回滚指引与迁移路径冲突

- 路径 `docs/release/upgrade-rollback-manual.md:10–11,36–55`、`docs/architecture/migration-matrix.md:27–45`、`docs/release/release-notes-rc.md:97`。
- 手册把相对 ui 的零新增迁移写成所有目标库无需 schema 回滚。已有 master V17 目标运行 RC 时按 Flyway 经 V18–V28，手册却要求恢复旧 jar 后直接起服务。旧 release 还包含候选不认的 V10–V14 迁移史。
- 实际独立 Flyway 用例从 V17 升到 V28 并保留既有账户。预期手册给出匹配每种历史的部署和恢复方式。当前文本不支持 V17 副本的可验证回退，影响故障恢复时间和数据切换。
- **修复与回归**：通过目标库实际 history 判断路径。分别编制 V28 同 schema 回滚和 V17 升级前备份、恢复到新库、数据差异确认、旧 jar 启动和切流。明确旧 Java/release 历史的隔离处理。在副本上同时演练升级和回退。

### P1-05 · 模型预算耗尽时以正常结束掩盖未生成回答

- 路径 `src/main/java/com/csust/eyediagnosis/agent/DiagnosisAgent.java:137–155,284–308`、`src/main/java/com/csust/eyediagnosis/controller/AgentController.java:91–109`。配置用递归限制控制模型节点，但框架用正常完成信号标记预算耗尽，Controller 因而追加 `done`。没有专门的受控截断帧或持久化结束原因。
- 配置 `modelCallLimit=1`，脚本模型首轮只请求一个成功的 MCP 工具。预期能够在限额内生成明确收口，或通知用户达到运行预算。实际 `DiagnosisAgent.stream` 正常完成，只返回请求、执行中、工具成功三帧，没有助手回答和中止说明。默认模型额度 15、工具额度 10 的反例同样重现，实际 15 次模型调用、10 次真实工具执行、5 次拒绝，仍以无回答的正常完成收口。影响长任务和持续工具循环，用户可能只见执行过程成功后静默停住。
- **修复与回归**：在模型额度内留出明确的最终回答机会或发出可回放的预算终态，不得增加上游调用数。回归覆盖配额 1、持续请求工具、并发独立额度和最终帧、历史一致。
- 复现 [IrisRuntimeAuditProbe.java](../../IrisRuntimeAuditProbe.java) · [runtime-probe-rerun.log](../../runtime-probe-rerun.log)

## 4. 高风险模块的其他判定

| 模块 | 独立判断 |
|---|---|
| A 方法工具 / MCP | `javap` 核对 Spring AI Alibaba 1.1.2.2、Spring AI 1.1.2。Method callback 包装异常，AgentToolNode 默认处理会变为 `of`。候选 `alwaysThrow` 配合外层失败边界正确修复该路径。实际检索库不可用和存储抛异常均失败，无资源。正常无命中仍成功。MCP 结构化 `isError` 实际失败，超时语义见 P1-01。ToolResponseMessage 只携带 id/name/result，模型收到错误文本。 |
| B 运行预算 | 失败边界 → 观察器 → 工具预算，预算测试配置上限 1，脚本模型请求 2 次，底层 MCP 只执行 1 次，第二次失败并允许模型第三次调用收口。模型上限 1 也观察到只调用一次。框架递归上限是最终保护。预算耗尽时的静默正常结束见 P1-05。普通方法工具缺少统一单次时限与可观察的主动取消保证，仍要求验收。并发隔离由 wiring、step concurrency 测试和 per-agent 实例作用域核对，普通聊天未做多用户并发实压。 |
| C SSE / React | 本地 1009 前端测试包括旧回合缺回执、乱序、StrictMode、历史和 Markdown 净化。合成浏览器覆盖长 Markdown、未收到回执、断流、历史恢复。普通聊天 POST SSE 遇断连由 `web/src/lib/api.ts:550–561` 终止，现有路径通过重新读历史恢复。网络级实时完整补流未获得证据。 |
| D 来源完整性 | owner 过滤源自调用时的服务端身份。排序空结果回退、最终 TopK、同文档多片段、缺 docId 有定向单测。ThreadLocal 绑定同步工具执行线程，跨线程登记缺乏显式调用身份传递。失败资源的结构化入口过滤 success，P1-01/P1-02/P1-03 仍限制证据闭环。模型文本引用不能提升为已核验文档来源。 |
| E 医疗和权限 | `ClinicalReviewQualification.java:48` 恒拒绝正式复核写入，`DiagnosisServiceImpl.java:404–418` 先检查资质。历史 REVIEWED、审计和角色未核验投影用例通过。任务工作流的草稿审阅与 t_diagnosis 正式复核是两个持久化契约，患者保存写 AI_ONLY。真实 HTTP 普通用户访问医师列表与跨用户读历史被拒，MySQL 套件验证患者投影、显式授权、撤权、取消和冻结材料保护。 |
| F 工程 | 三个独立 Flyway 用例执行到 V28，包含空库、兼容 V17、V23→V24 冻结历史。时钟测试在同一轮对实际数据库时钟先测 baseline，再减出 ±6 秒增量，差值容差仍 <1000 ms；继续执行真实租约续租及并发争抢断言。本机 Graph 37/37，含 +/- 两例。未发现候选新增 Flyway 脚本或生产开关变化。CI 清单无独立依赖/密钥扫描阶段，产品构建通过无法提供该类报告。 |

## 5. 独立执行记录

以下使用候选 `7dc3422b70828d400ad1d3a09e8c29aa12f48309`。所有测试数据均为合成。独立 detached 检出，JDK Corretto 21.0.7、Node 22.21.0、npm 10.9.4、Windows Chromium 修订 1254。MySQL/Redis/Chroma 使用一次性隔离容器，Java、Vite 使用 loopback 端口。历史/审计探针核对写入调用，MySQL 独立套件核对真实存储。

| 面向 | 输入、期望与实际 | 可复现证据 |
|---|---|---|
| 后端 pass 1 | `scripts/verify.sh` 的 ci profile，MySQL 8.0.26、Redis 7。期望非环境门禁通过。719 报告条目，416 执行通过、303 跳过，0 失败/错误。真实 Spring context、全应用验收 10 条、正式复核关闭和历史审计 7 条通过。 | [backend-verify.log](../../backend-verify.log) |
| 后端 pass 2 | 空库、V17 测试旧库、V23 冻结行及真实 JDBC/审计操作。预期迁至 V28、保留历史并能正确写入。7/7，0 skip。 | [backend-verify.log](../../backend-verify.log) |
| 后端 pass 3 | 隔离库的协作、Graph、任务、受控来源、医生复核、授权、随访与进程恢复。12 份本次 Surefire XML 共 303/303，0 失败/错误/skip。外层日志中未取得 verify green 汇总行，以逐类文件为依据。 | [backend-pass3-surefire.csv](../../backend-pass3-surefire.csv) |
| 前端 | `npm ci` 后运行全套测试、typecheck、lint、build。93 文件、1009 通过，四命令退出 0。用例含 Markdown/净化、状态、资源、StrictMode。 | [web-test.log](../../web-test.log) · [退出记录](../../web-exit.txt) |
| R2 浏览器 | 首轮混跑有缺环境/测试钩子错误。修正本地 Vite 探针环境 `VITE_E2E_REFRESH_HOOK=1` 后，认证、资讯的 14 条合成故障均通过。另首轮 6 条送达对比度和 1 条协作默认关闭通过。 | [首轮日志](../../web-playwright.log) · [R2 独立复跑](../../web-playwright-r2-ci.log) |
| Agent 浏览器 | 原始 R3 夹具 154 条断言中 14 条失败，生成 30 图。改用候选状态字段和状态值、副作用仅在外部夹具，154 条中 4 条失败，均为两个主题的超时状态和说明。其余包括长 Markdown、乱序、断流、旧数据回放。 | [原始报告](../../browser-r3-agent/report.json) · [候选帧报告](../../r3-agent-acceptance-candidate-wire/report.json) |
| 资源浏览器 | 原始脚本 45 条中 2 条失败。以候选帧重跑 45/45，生成 8 图，覆盖成功来源、失败丢弃、历史、抽屉与链接处理。 | [原始报告](../../browser-r3-resources/report.json) · [候选帧报告](../../r3-resources-acceptance-candidate-wire/report.json) |
| 双主题响应式冻结验证 | 12 路由 × 6 个视口 × 2 个主题，检查浏览器溢出、主题类和未捕获异常，2/2。生成 72 个截图，抽看了首页、管理端知识库和 Agent 移动/桌面布局。以候选布局验收为据，未和冻结 tag 做全量像素差分。 | [日志](../../web-playwright-freeze.log) · [首页样图](../../freeze-screenshots/home-light-390.png) · [知识库样图](../../freeze-screenshots/admin-knowledge-dark-1440.png) |
| 协作浏览器 | 真实 loopback Java HTTP/SSE 与独立 MySQL，推理、存储、模型和引导登录为夹具。启动项目指定 harness 后 5/5，覆盖跨角色读、批准、拒绝、撤权、取消、历史刷新和回执保护。另对当前生产构建 preview 验证协作默认关 1/1。 | [协作日志](../../web-playwright-collaboration.log) · [默认关](../../web-playwright-default-off.log) |
| HTTP 和 SSE | 启动完整 Java 应用连接独立 MySQL、Redis、Chroma。两名合成账号通过真实登录。所有者读本会话 0，另一用户读 40101 且无 data，普通用户读医生列表 40101。提供方指向不可用 loopback，POST 流返回 HTTP 200 / SSE error，历史仍有原用户消息。 | [http-smoke.ps1](../../http-smoke.ps1) · [http-smoke.log](../../http-smoke.log) |
| 独立故障注入 | 工具异常、格式错误、空 JSON、预算拒绝、MCP 错误和超时、取消流、半批 RAG、归档写失败，实际和预期列于第 3 节。 | [工具](../../tool-probe.log) · [预算/MCP](../../runtime-probe.log) · [RAG](../../rag-batch-probe.log) · [归档](../../standards-archive-failure.log) |
| 依赖复核 | 1.1.2.2 agent-framework JAR SHA-256 `EF53F0D668D4FD24E722B26C4B278516AED984F9AE5269814B84C0721AA7120A`。类包括 AgentToolNode、InterceptorChain、MethodToolCallback、默认异常处理和 ToolCallResponse。 | [dependency-bytecode.log](../../dependency-bytecode.log) |

## 6. 与交付报告的差异及 P2

- Claude 报告修复了方法异常机制，这一点成立。空 JSON、非结构化提供方错误、MCP 超时和取消回放仍有实测缺陷。
- 303 个跳过项中只有 300 个被 pass 3 补上。独立日志确认 `VectorStoreConfigTest` 的 1 条和 `RAGEvaluationTest` 的 2 条 opt-in 测试仍未执行，pass 3 重跑了 pass 1 已过的 3 条 `DiagnosisTaskApiTest`。Pass 1+3 有 716 个不同执行个案，另有 pass 2 的 7 条独立门禁。
- **P2** · release 验证矩阵第 11 行继续把父代码提交写作当前 HEAD，而收尾后的 #119 指向 `7dc3422b`。台账 B4 仍显示进行中。Claude 最终会话及 GitHub CI 可互相核实两 SHA，建议修正文档和覆盖表。R3 的两个浏览器脚本也需要适配候选帧，使日后证据可重复。
- **P2** · `src/main/java/com/csust/eyediagnosis/config/JsonConfig.java:67` 的全局 Date 序列化器可接到 `java.sql.Date`，`TimeFormats.java:38` 调用该子类不支持的 `toInstant`。未找到实际接口返回这种子类，作为潜在兼容问题登记。
- G-3 的真实 DashScope/临床联调仍未执行。本次受控 loopback 的 Java 测试补充了权限与 SSE 负例证据。真实生产库升级、旧版本恢复及受信真实外部资源验证仍未执行。G-5 缺少截图的事实已在独立审查环境补证，覆盖口径见测试矩阵。

## 7. 发布裁决

保持 #119 Draft。Claude 应优先修复两条 P0，再处理五条 P1，提供新的候选 SHA。按新 SHA 重跑本报告复现、MySQL 实库状态一致性、完整前后端门禁和双主题关键路径，并重新确认正式临床复核和协作生产默认关闭。外部 DashScope、实际目标库与生产发布另有 owner 门禁。

### 复现入口

[run-probes.ps1](../../run-probes.ps1) 从锁定检出的 Surefire XML 读取依赖类路径，在审查目录编译并运行独立 Java 反例。工具探针的退出码 0 表示成功重现记录中的实际现象，包括仍需修复的现象。与发布通过的期望值对照请见各缺陷条目。[汇总](../../probe-rerun-summary.log)


