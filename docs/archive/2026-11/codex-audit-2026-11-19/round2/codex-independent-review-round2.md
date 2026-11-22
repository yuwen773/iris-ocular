# Iris RC 第二轮独立复验

**NO_GO**。审查代码为 `03b14efd8f9bcc8d6326a104396d8b68a30d5e8b`，文档为 `dfca38016fe960a4630945641d96b2435e396fc6`。两者差异仅在 docs。原始报告已完整读取，位置为 `D:/Work/code/iris-codex-audit-7dc3422b/docs/release/codex-independent-review.md`。本次只在独立 detached 检出运行验证，报告和探针位于本目录。没有修改候选源码。

## 七条原始阻断

| 编号 | 结论 | 独立验证与剩余问题 |
|---|---|---|
| P0-01 | CLOSED | 真实绑定方法和 ReactAgent 对空白、空 JSON、非结构化提供方响应均产生 failed / INVALID_OUTPUT，历史写入载荷失败，审计 FAILED，无资源。合法结构的成功对照及解析边界用例通过。 |
| P0-02 | STILL_OPEN | 归档状态查询失败、未跟踪文件枚举失败、归档期间再次修改已脏文件、bundle 对象包截断，均仍可进入删除。 |
| P1-01 | STILL_OPEN | 中断仍被归为 TIMEOUT，执行 Future 未取消。终态发送前历史操作仅入异步队列，写入结果没有反馈，无法保证已提交回放。 |
| P1-02 | STILL_OPEN | 第二批失败后的直接过滤有效，但清理不可用后成功重新处理会重新接纳上一代残留片段。 |
| P1-03 | STILL_OPEN | 公开 kind 仍位于可由用户原样写入的文本中，历史端据此转换正文并赋予 upload 来源。任意 HTTPS 主机的上传形状路径仍可通过前端。完整伪造链路因工具安全拦截未执行，结论依据源码，不能宣称浏览器第三方请求已实测。 |
| P1-04 | STILL_OPEN | 实际旧 JAR 在 V28 启动和基本账户读写成功，但推荐回退的产物恢复了正式复核写入与推理输出，恢复库数据差异核对指引也有缺口。 |
| P1-05 | STILL_OPEN | 同一模型节点先发铺垫文本再请求工具，额度耗尽后仍无终止说明，历史只保留铺垫。 |

## P0-01 的关闭范围

原始探针首先按首轮旧预期运行，在 malformed 场景因实际已经 failed 而退出。审查目录副本仅将 malformed 和 empty-json 的期望从 succeeded 改为 failed 后重跑，退出 0。空白、空 JSON、服务提示均为 INVALID_OUTPUT，无医学结果或资源提升。检索未配置与存储异常失败，合法无命中成功。日志保留两次结果。

独立全套后端包含 `EyeImageAnalyzerToolOutputValidationTest`、`AgentEyeImageInvalidOutputEndToEndTest` 及成功对照。原始探针的历史和审计证据为实际调用载荷捕获，未把它写成逐条实库行断言。真实 MySQL 的审计表与全应用门禁另外运行。

证据见 `original-tool-probe.log`、`adapted-tool-probe.log`、`IrisToolAuditProbe.java`、`backend-verify.log` 和 `checkout/target/surefire-reports/`。

## 仍开放问题及最小任务

### P0-02 归档成功判断仍允许不可恢复的数据被删除

源码为 `scripts/worktree-hygiene.sh` 第 74、131、166、188、223 行附近。`git status --porcelain | wc -l` 与初始 `ls-files` 的失败可丢失。状态比较无法察觉已修改文件内容再次变化。`git bundle verify` 未验证全部 pack 对象可以恢复。

独立临时仓库的 PATH 替身保护性拦截全部 remove，四种负例均退出 0，且各记录一次删除调用。截断 bundle 在新仓库实际导入失败。正常 bundle 可以恢复独有 HEAD，未跟踪文件恢复后字节一致。

Claude 定向任务为显式检查所有 Git 查询结果，检查必需归档项，在空仓库实际导入 bundle 并核对固定 HEAD，保全暂存区与工作区两个版本，在删除前比较内容清单及当前 HEAD。状态或对象恢复无法确认时保留工作树并返回失败。

复现入口为 `standards-evidence/probe.sh`，替身拦截删除，传入一次性测试目录。证据在 `standards-evidence/` 的 per-case stdout/remove/restore 与 `archive-restore-controls.txt`。

### P1-01 取消类别及终态提交仍有缺口

源码为 `agent/manager/MCPManager.java:149`、`agent/ToolStepSink.java:151`、`agent/service/ChatHistoryService.java:105` 和 `agent/queue/SessionMessageQueue.java:26`。

独立实际 MCPManager、McpToolCallback、ToolExecutionInterceptor 路径中，Future 抛 InterruptedException 得到 SSE failed/TIMEOUT、审计 FAILED/TIMEOUT，Future.cancel 调用为 0。真正 TimeoutException 同样 TIMEOUT，cancel 调用为 1，后者为成功对照。

实际 sink、历史服务与队列在暂停 worker 后，TIMEOUT 已发出，writer 调用为 0，放开后为 1。该证据说明提交有时间窗口。只证明异步窗口不足以单独判定数据丢失，忽略 Future 的写失败路径及中断误分类使该项无法关闭。候选的取消后迟到回执保护单测已通过。

Claude 定向任务为将线程中断保留为取消并取消传输 Future，观察历史写入成功或失败并为失败保留可恢复状态。在真实 MySQL 注入写失败，验证断开 SSE、等待队列、重读历史及迟到结果，确保终态一致。

另有真实浏览器确定性失败。首轮候选帧脚本在本轮 SHA 上发出 `toolStatus=failed,errorCategory=TIMEOUT`，双主题仍显示失败并缺少超时说明。`web/src/lib/agentStream.ts:197` 至 211 的实时 DTO 映射只复制 toolStatus，遗漏 errorCategory。历史映射第 351 行保留该字段，因而实时与回放仍不一致。Claude 须在实时 tool-result 映射传递 errorCategory，并通过实际 reducer 到组件的测试覆盖 TIMEOUT、CANCELLED、INVALID_OUTPUT。证据为 `browser-agent-wire/report.json`，154 条中 5 条失败，4 条是这两主题的超时断言，另一条是富文本流式中间时刻已出现表格，后者受宿主负载影响，未归为新产品阻断。

复现与日志为 `IrisSpecTerminalProbe.java`、`spec-terminal-probe.log`。历史窗口探针使用 writer mock，未升级为真实 MySQL 故障写入证据。

### P1-02 重试成功重新暴露失败代片段

源码为 `service/impl/KnowledgeBaseServiceImpl.java:287`、`:306`、`:447`，`service/impl/DocumentProcessingService.java:106`、`:136`，`agent/tools/DocumentSearchTool.java:153`。

实际 Java、MyBatis mapper、隔离 MySQL 8.0.26 与 Chroma 1.0.0 运行，模型嵌入、COS 和分片为合成夹具，在真实 Chroma REST 传输处注入错误。

| 场景 | 实际结果 |
|---|---|
| 11 片段，第 2 批失败，清理可用 | status=2，vectorCount=0，无旧文本，无资源 |
| 同上，补偿清理不可用 | status=2，vectorCount=10，正文与资源均过滤为空 |
| 更新前清理仍失败，较短版本成功处理 | status=1，publishedChunks=1，vectorCount=10，同时返回旧文本、新文本及资源 |
| 随后删除 | 逻辑行缺失使正文与资源为空，仍残留 9 个物理片段 |

Claude 定向任务为使用处理版本区分向量，并按已发布版本检索。清理失败时禁止旧版本重新被 READY 行接纳，保留可重试补偿。回归保留第二批失败、清理失败、较短重试、删除及成功对照。

复现为 `standards-evidence/IrisRound2StorageProbe.java`，日志为 `standards-evidence/storage-probe.log`。

### P1-03 公开文本标记无法建立服务端可信性

源码为 `agent/service/ChatHistoryService.java:79`、`controller/AgentController.java:75`、`:250`、`web/src/lib/agentStream.ts:313`、`:472`、`web/src/lib/agentResources.ts:124`、`:271` 和 `web/src/components/agent/messages/UserMessage.tsx:31`。

无附件的 content 原样保存，入口只验证 imageUrls。历史映射对内容中的公开 kind 做判断后改写正文、生成 images，资源派生为 upload，图片组件据此生成 img。URL 判断仅凭 HTTPS 及路径前缀，不绑定实际配置的 COS origin。源码支持用户描述的信任边界缺陷，按 P1 保留。影响为历史正文完整性、附件来源伪造和潜在回看追踪请求，未证明跨用户私有对象读取或服务端对象授权被真正授予。

本次完整伪造载荷、真实写入到第三方请求的执行任务遭工具安全审查拦截，提示可能涉及网络安全风险。没有规避该限制，以下四点均未获本轮端到端运行证据。正文改写、upload 提升和请求入口由源码确认，实际第三方请求未实测。不得把此报告作为完整运行链路通过或成功利用的证明。

Claude 最小任务为将附件类型和受校验对象引用存于服务端控制的独立字段，历史读取依据该元数据并按当前用户复核对象归属，正文始终作为正文处理。自动加载限制于精确可信对象来源或同源授权资源接口。保守兼容旧行须保留原文并支持经归属复核后的附件恢复。前端公开字符串规则不能代替服务端认证。

### P1-04 旧 JAR 可启动，推荐回滚产物仍不安全

源码与文档为 `docs/release/upgrade-rollback-manual.md:66`、`:94`，旧 master 的 `DiagnosisServiceImpl.java:263` 和 `DiagnosisAgent.java:176`、`:207`。

实际构建旧 `a0efaf46d6f8b53bea4a35d613fc806e2f6e9d47` 可执行 JAR，SHA256 为 `E3DC71B8B33018C6799C5B70BE2F3DC7D04FB03103868FEF7423FBB3C7A62529`。在一次性 MySQL/Redis/Chroma 上依次完成 V17 启动、注册与认证读取，RC 升至 V28并保留旧账户且新建账户，旧可执行 JAR 在 V28 上启动、读取两账户且注册成功，备份恢复至新 V17 库并启动旧 JAR。恢复库没有备份后账户。

这些证据纠正首轮关于旧 JAR 不能启动的推断。候选 Flyway 测试只加载截取的脚本，不能独自证明旧应用启动，本轮补上了真实产物基本冒烟。旧 master 还恢复了管理员正式复核写入与推理内容输出，手册推荐回滚没有要求安全产物或隔离访问。§4.3 将恢复库对备份时基线比较，无法获得停写源库的完整更新、删除和新建数据差异。

Claude 任务为指定经过安全验证的回滚产物及限制条件，恢复时比较停写源库与恢复库，逐表处置新增、更新和删除，包括用户、诊断、消息及 V18–V28 数据。修正测试名称和文档证据的实际范围。

复现为 `spec-old-artifact.ps1`、`spec-old-artifact.log`，应用各阶段日志保存在本目录。

### P1-05 铺垫文本掩盖未生成最终回答

源码为 `agent/DiagnosisAgent.java:264`、`:347`。实际 ReactAgent 的合成 streaming model 在 modelCallLimit=1 时，先发送普通铺垫文本，再发送工具调用，同轮工具成功后预算终止。实际只有铺垫、请求、running、succeeded，历史只保存铺垫，没有预算说明。模型调用仍为 1。

纯工具调用无文字的对照正确生成并保存终止说明，正常最终答案对照正确保存答案，空白对照生成预算说明。候选测试缺少铺垫后请求工具的场景。

Claude 任务为追踪最后模型节点是否以无待处理工具的回答结束，预算截断时追加并持久化明确说明，保留既有铺垫且不增加调用额度。复现与日志为 `IrisSpecBudgetProbe.java`、`spec-budget-probe.log`。

## 独立测试与证据范围

- `scripts/verify.sh` 三段在 detached `03b14efd` 执行，隔离 MySQL 8.0.26 和 Redis。pass1 为 751 条，0 failure/error，303 skip。pass2 为 9 条，0 failure/error/skip。pass3 为 303 条，0 failure/error/skip，最终 verify green。分段有重复用例，三个数字不合计为互不重复总数。日志为 `backend-verify.log`。VectorStoreConfigTest 与 RAGEvaluationTest 的 opt-in 外部门禁不能因三段绿色升级为已执行。
- React 全套 94 文件、1029 测试通过，日志为 `web-test.log`。类型检查完成，日志为 `web-typecheck.log`。Lint、build 和浏览器最终结果见同目录日志及后续补记。
- 项目归档故障注入为 10 例、28 断言通过，日志为 `worktree-tests.log`，独立相邻反例仍失败。
- 独立源代码和运行探针由 Standards、Spec 两个审查任务交叉执行，证据包括真实持久化、真实框架和替身故障注入，各节已注明范围。

## 远端 CI 与一致性

实时读取 #119 为 OPEN / Draft，head 为 `dfca38016fe960a4630945641d96b2435e396fc6`，base 为 ui。代码 SHA 对应 run `44459485719` attempt 1 成功。文档 HEAD 对应 run `44460971606` attempt 1 失败，`issue19-mysql` 失败，attempt 2 成功。原始 jobs JSON 在 `ci-code-attempt1.json`、`ci-docs-attempt1.json`、`ci-docs-attempt2.json`。失败 attempt 必须保留，不能只报告最后一轮绿色。

`verification-matrix.md` 顶部仍把当前 HEAD 写为 `6ff5a889`，虽然后面新增 §2.6，本项原 P2 证据标注问题仍存在。代码到文档 HEAD 仅 docs 变更已通过 Git diff 核对。

## G-5 与外部门禁

G-5 保持 OPEN。旧 72 张截图属于首轮 `7dc3422b` 的 Vite 验收，测试源代码明确只在 390、900、1440 截图，六宽断言不能当成六宽截图。旧文件缺少可独立确认的构建内容指纹，不能整体升级为新 SHA 证据。原始冻结夹具为空状态，不能证明管理端 PROVENANCE、活动资源侧栏和完整多 Agent 工作台。

本轮补跑新 SHA 的冻结扫描、System States、送达和资源浏览器路径，补充图片由独立视觉任务检查。完整视觉结果及覆盖缺口以本目录 visual 记录和浏览器 report.json 为准。遗留 R3 脚本的旧 status 字段需适配 toolStatus，首轮候选帧脚本仅在审查目录复用。遗留无 kind 附件历史失去自动图片展示，是本次修复声明的兼容取舍。

G-3 仍需要受控真实 DashScope 凭据及临床联调。合成模型、嵌入、COS 夹具和 loopback 服务不提供真实医疗效果、真实对象访问或生产就绪证据。本次没有调用真实付费提供方。

### 最终补记

- Web 全套单测、typecheck、lint、build 均完成，四条顺序命令最终退出 0。构建保留大 chunk 提示。
- Playwright `ui-freeze-regression`、`system-states-r2`、`agent-delivery` 共 22/22 通过，日志为 `browser-gates.log`，新版冻结截图在 `checkout/target/ui-freeze/`。源代码 SHA、开发模式及截图/生产产物内容指纹记录在 `artifact-manifest.json`，开发模式截图没有冒充生产包截图。
- 原始 R3 Agent 脚本 154 条中 14 条失败，主要使用旧 status 字段。首轮候选帧脚本本轮复跑为 154 条中 5 条失败，含实际超时映射缺口。原始资源脚本 45 条中 3 条失败，候选帧版只有无 kind 旧影像恢复断言失败，符合整改明确牺牲旧附件展示的取舍。各 report.json 和日志均保留，未修改集成候选中的脚本。
- 逐张检查首轮 72 图的记录及补充关键图见 `standards-evidence/g5-visual-review.md`。旧图通过 8 张联系表逐张检查顶部 900px，非所有全页长图逐像素检查。新版补图覆盖首页 390/820/1440/1920 双主题、活动 PROVENANCE、Agent 资源侧栏、System States 和多 Agent 工作台。工作台 18/18，含移动完成态和来源检查界面，来源为启用测试开关的独立开发服务器。旧加载状态证据及开发构建范围使 G-5 继续 OPEN。
- 文档 HEAD 的 CI 首次失败原因为无法从 Maven Central 解析 `surefire-junit-platform:3.5.4`，发生在启动测试提供方阶段，失败日志为 `ci-docs-attempt1-failure.log`。同一 head 的 attempt 2 21/21 成功，代码 SHA 的 attempt 1 21/21 成功。

## 相邻新增发现分级

| 级别 | 新反例 | 处理 |
|---|---|---|
| P0 | 截断 bundle 能过当前发布校验并进入删除，状态与文件枚举错误也可进入删除 | 纳入原 P0-02，避免重复计数 |
| P1 | RAG 清理失败后的较短成功重试重新引入旧片段 | 纳入原 P1-02 |
| P1 | 公开 kind 仍作为可信凭证，任意第三方上传形状路径获前端放行 | 纳入原 P1-03，源码确认，完整运行链未执行 |
| P1 | 模型铺垫文本压制预算终止说明，旧回滚产物恢复不安全行为 | 分别纳入原 P1-05、P1-04 |
| P2 | 验证矩阵顶部 HEAD 陈旧，仓库 R3 脚本沿用旧事件字段 | 证据与可重复性问题 |

报告最终裁决为 **NO_GO**。完整伪造消息端到端执行受工具安全拦截，真实 MySQL 终态写失败注入与真实 DashScope 临床链仍缺证。存在可重复的 P0/P1 失败已足以阻断发布。修复清单和探针保存在独立审查目录，可供 Claude 定向整改。

## 审查结束时的分支前移

只读复查发现集成工作树前移至 `dd44db7e64fb46b8fd98636fae5ee7c79bef41b4`。相对 dfca3801 仅 5 份 release 文档变化，本次 detached 检出仍为 03b14efd，工作树均干净。该变化由并行工作发生，本审查没有修改集成树。

新文档 §2.7 声称执行真实 DashScope 五场景，并记载验证后删除配置、启动日志、临时脚本、MySQL schema 与 Chroma 记录。本轮只能读取自述和汇总表，无法对已删除的原始链路证据独立交叉核验，故不解除 G-3。需要保留脱敏且可核对 SHA 的 SSE、历史、审计、提供方调用记录与脚本，真实视觉成功及 COS 上传仍需对应凭据，临床联调和生产等价性仍缺证。不能继续概括为“所有参与者均没有 DashScope 凭据”，准确范围为本次独立审查未调用真实提供方。

新文档 G3-5 的历史原文保留及 images=null 是后端读取结果，不能证明 React mapHistoryMessage、资源派生和 img 渲染保持原文，且外站样例路径不是用户要求的第三方 `/agent-chat/` 路径。这份旁证不能解除 P1-03。

新文档顶部 HEAD 从 6ff5a889 更新为 dfca3801，修正了原陈旧代码 SHA 的问题，但未等于当前 dd44db7e。前文 CI 判断严格对应 dfca3801 和 03b14efd，不代表最新文档提交的 CI 结论。

最终远端读取确认 #119 仍为 OPEN / Draft，最新 head 为 dd44db7e。其 run `44468099538` attempt 1 已 completed/success，保存于 `ci-final-heads.json` 和 `pr-final.json`。本轮所有自有浏览器服务与子任务服务已停止，独立检出和证据保留供复核。

当前候选的 `ClinicalReviewQualification` 恒拒绝正式复核，协作前后端默认关闭，源代码核对与本轮相关 MySQL/应用门禁保持通过。回退旧 master 的安全退化另见 P1-04。

## Standards 与 Spec

Standards 轴发现归档失败封闭和向量处理版本边界问题。`DocumentSearchToolReadyOnlyTest` 只测试辅助方法，未覆盖真实更新、检索、资源登记的接口边界。内外 catch 重复清理属于维护性判断，实际观察到重复 delete。

Spec 轴确认视觉输出校验符合原要求，其余阻断仍存在具体反例或未完成的可信性修复。若任何 P0/P1 开放，发布结论必须为 NO_GO。本轮满足该条件。
