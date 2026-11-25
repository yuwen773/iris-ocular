# WP-07 · 资讯采集灰度验收

验收日期为 2026-11-25，最终核验基线为 `dca52493`（在 `e0dabebf` 之上叠加 WP-07C 整改、Europe PMC 单页修复、转发头可信边界与浏览器业务联调）。本次使用 Windows 工作站、隔离的 Spring Boot `ci` 实例及临时 MySQL 8、Redis 7、Chroma 容器。模型精选保持关闭，实测只涉及眼科资讯采集。以下结论按验证手段分组，**不互相顶替**：官方 API 探针、离线夹具重放、正式 Worker 实采、真实端到端联调各有独立口径。

## 信源结果

| 信源 | 正式 Worker 实采（本会话） | 当前网络可达性 | 备注 |
| --- | --- | --- | --- |
| PubMed | `wp07-pubmed-setup` COMPLETED，7 条真实落库，游标 2025-10-06→2025-10-07 | `BLOCKED_ENV`：`eutils.ncbi.nlm.nih.gov` TLS 0/6 | 另经独立 HTTP 探针核对 ESearch/EFetch 200 与字段；当前 SNI 过滤，Worker 直连失败 |
| Europe PMC | `wp07-europe-final2` COMPLETED（1 条）、`wp07-europe-perf` COMPLETED（98 条），共 99 条真实落库 | **可达**（TLS 证书 CN=ebi.ac.uk 校验通过，链路慢、偶发超时） | 修复单页窗口缺陷后实采通过；摘要许可 CC-BY 已核验 |
| ClinicalTrials.gov | `wp07-ct-pagination`/`wp07-ct-perf` 等 COMPLETED，396 条真实落库 | `BLOCKED_ENV`：`clinicaltrials.gov` TLS 0/6 | 独立探针核对分页令牌与字段；当前 SNI 过滤 |

三源在本会话内均有真实 Worker 实采落库，共 502 条资料修订，公开资讯 0 条（自动发布默认关闭，符合预期）。当前网络仅 Europe PMC 稳定可达，PubMed 与 ClinicalTrials.gov 处于 SNI 过滤状态，故对这两源只标注「曾实采成功、当前环境阻断」，不标为通过。

## 网络与 DNS 诊断

- 系统 DNS 把三个域名解析到 `198.18.0.0/15`（Clash Fake-IP 段）；TUN 以 `dns-hijack: any:53` 劫持全部 53 端口，容器内即使指定 `--dns 1.1.1.1` 仍得到假地址。
- Worker 的 `SourceHttp` 先解析、再校验地址必须为公网、再钉到已校验 IP 直连（`trust_env=False`，不走代理）。假 IP 会正确触发 `SOURCE_BLOCKED`，**未放宽**该校验。
- 隔离 Linux 容器内：`www.ebi.ac.uk` 真实 IP `193.62.193.80` 的 TLS 证书为 EBI 官方；`clinicaltrials.gov`/`eutils.ncbi.nlm.nih.gov` TCP 可建连但 TLS 握手 `SSLEOFError`（SNI 过滤）。trusted DoH（容器直连 `1.1.1.1`）对三域的 A 记录与上述一致。
- 结论：Europe PMC 走通；另两源需在具有正常公网出站（不经过 Fake-IP/SNI 过滤）的灰度环境重跑正式 Worker。

## 数据使用边界

- **PubMed** 保留题录字段，当前投影省略摘要。按 E-utilities 要求配置可联系邮箱及工具标识，并在对外展示前核对 NCBI 声明和具体权利。 [NCBI 接入与版权规则](https://www.ncbi.nlm.nih.gov/books/NBK25497/?report=printable)
- **Europe PMC** 仅在识别到允许的 CC BY 或 CC0 许可时保留摘要。本次实采记录 `MED:41009782` 摘要 1500 字、`abstractLicense=CC-BY`，已核验许可字段生效；其余记录按无许可省略摘要。 [Europe PMC 版权要求](https://dev.europepmc.org/Copyright)
- **ClinicalTrials.gov** 本次投影仅使用 NCT ID、标题、发布日期、更新发布日期及试验结构；过长 conditions 已截断至 100 条。对外分发前还需验证显示信源归属、数据处理日期、内容修改说明及更新处理。 [ClinicalTrials.gov 使用条款](https://clinicaltrials.gov/terms-conditions)

## 隔离控制面与资源

- 未携带机器凭证的领任务请求收到 HTTP 401；机器凭证只接受 `http://127.0.0.1` 或 HTTPS（`ControlClient` 校验），明文远程 origin 一律拒绝。
- 真实端到端：Worker 领取→拉取 Europe PMC→分批上报→完成回执→MySQL 落库，游标推进到排他上界；重复运行返回 `IDLE`，不产生重复修订。
- Worker 全量离线测试 35 项通过（含新增的欧洲 PMC 单页窗口正/负例）。
- 资源峰值（Europe PMC 采集，`--cpus 0.5 --memory 256m`）：峰值工作集 54.3 MiB，墙钟 37.1 秒，CPU 2.16 秒，平均 5.83% 单核。

## 性能对照（本机基线）

同一目标主机、同一预热方式（每次 24 次预热）、固定 480 样本，采集关闭/运行中两组对照，Worker 与公读请求时间窗重叠：

| 组别 | 样本 | 错误 | Java 公读 P95 |
| --- | --- | --- | --- |
| 采集关闭 | 480 | 0 | 8.26 ms |
| 采集运行中 | 480 | 0 | 9.30 ms |

增幅 **+12.59%**，**超过此前 10% 的设计目标**——本机基线下压测未通过该门槛，不能记为达标；本轮未做进一步归因（采样时 Worker 采集与公读请求时间窗重叠，Java 只读路径本轮未改动）。眼底推理 P95 本轮未纳入（无推理负载），**目标环境 P95 与门槛判定为 `NOT_RUN`**，上表仅本机基线意义。

## 浏览器业务联调（直连 Spring）

`web/e2e/news-engine-live.spec.ts` 是一条不拦任何请求的浏览器用例：页面 → nginx → Spring → MySQL 全是真的，覆盖 `建稿 → 审核 → 发布 → 公开读取 → 刊物展示 → 撤回`。默认跳过（`IRIS_LIVE_E2E=1` 才运行），因为它需要一套已启动的栈和一条预置候选。

**预置数据**（仓库外临时脚本，不入库）：材料行 + 材料修订（`visibleAt` 非空）+ `SUCCEEDED` 模型调用回执 + `CURATED_ACCEPTED` 候选。证据快照的 SHA-256 由 MySQL `SHA2()` 就地计算后写入，保证与 `lockAndVerifyCandidate` 的重算一致；只有付费模型调用被一条回执替代，其余门禁（候选状态、违规为空、快照身份、材料修订元组、回执绑定）全部真实走一遍。

**本次实跑**（隔离栈 `18087`/`15187`，HEAD `dca52493`）：

- 用例通过（13.2 s）；
- 审计链完整：`CREATE_FROM_CANDIDATE`（NULL → PENDING_REVIEW）→ `PUBLISH`（PENDING_REVIEW → PUBLISHED）→ `OFFLINE`（PUBLISHED → OFFLINE），`actorRole=admin`；
- 落库正文以 `<p>` 开头并含 `<h2>证据要点</h2>`（本轮修复前该路径落的是 Markdown 原文，公开页会显示字面的 `## 证据要点`）；
- 公开页在撤回后不再出现该标题；
- 刊物预览按发布日期窗口列出该篇（要求「已发布 + 证据快照可追溯」两个条件同时成立）。

复跑方式：起栈后执行预置脚本，再用 `E2E_BASE_URL=http://127.0.0.1:15187 IRIS_LIVE_E2E=1 IRIS_LIVE_E2E_ACCOUNT=… IRIS_LIVE_E2E_PASSWORD=… IRIS_LIVE_E2E_TITLE=… IRIS_LIVE_E2E_DATE=YYYY-MM-DD npx playwright test e2e/news-engine-live.spec.ts`。

## 验证证据与 HEAD 对应

| 证据 | HEAD | 结果 |
| --- | --- | --- |
| `scripts/verify.sh`（L3） | `dca52493` | pass 1/3 1234 例（跳过 306，均为协作套件，按设计在 pass 3 跑）0 失败 0 错误；pass 2/3 64 例全过；pass 3/3 305 例全过；收尾断言无残留容器/卷/网络 |
| Worker 离线套件 | `0e442e1e`（此后 worker 目录未改） | 35 例通过 |
| 浏览器业务联调 | `dca52493` | 1 例通过（见上节） |
| 真实信源实采 | `0e442e1e` | 三源 502 条资料修订落库，公开资讯 0 条 |

L3 的 306 条跳过不是「静默不跑」：它们是 4 个协作套件在 pass 1/2 的按设计跳过，pass 3 用独立库全量执行。

## 反向代理可信边界

Worker 的 HTTPS 门禁读 `request.isSecure()`，该值只由「来源可信」的转发头置真。因此部署基线在校验 `require-https=true` 时强制：

- 拒绝 `server.forward-headers-strategy=framework`——`ForwardedHeaderFilter` 不校验来源，外部直连带一个 `X-Forwarded-Proto: https` 就能伪造；
- `native` 必须显式配置 `server.tomcat.remoteip.internal-proxies`，未限定可信代理即拒绝启动；
- 策略**未显式配置**且运行在云平台（Kubernetes / Cloud Foundry 等）时拒绝启动：Spring Boot 会自动补成 native 并套用默认 `internal-proxies`（10/8、172.16/12、192.168/16、127/8），那是一条运维没决定的信任边界。本地/裸机未配置仍放行——没有转发处理时伪造头不会生效（fail-closed）。

`internal-proxies` 内的地址仍可改写 `getRemoteAddr()`，所以可选的白名单 `allowed-remote-addresses` 在 native 下必须填反代自身地址，并要求反代**覆盖**而非追加客户端传入的 `X-Forwarded-For`。

WP-07G 把这条从「部署约定」推进到**代码强制**（真实 Tomcat 10.1.46 实测，见
`NewsForwardedPeerSemanticsTest`）：

- **实测语义**：native 下 `getRemoteAddr()` 取 `X-Forwarded-For` 的**最右一跳**，
  也就是代理转述的客户端地址；客户端自带的该头会直接成为生效地址。
  `org.apache.tomcat.remoteAddr` 与 `org.apache.catalina.AccessLog.RemoteAddr` 在本版本
  **同样是改写后的地址**，原始 socket 对端在应用侧不可得。
  未启用转发处理时，`getRemoteAddr()` 恒为真实对端；非可信对端的转发头被 Tomcat 完全忽略，
  `isSecure()` 不会被置真。
- **白名单语义收窄**：`allowed-remote-addresses` 现在只表示「直连对端」。
  请求一旦带任何转发头（`Forwarded` / `X-Forwarded-*` / `X-Real-IP`）或 Tomcat 的转发标记，
  一律 **403 fail closed**——转发头出现即说明这条请求的对端不是 Worker 本身，
  而原始对端又不可判定，拿可伪造的地址当物理边界没有意义。
- **可信代理范围核对**：新增 `eye-diagnosis.news-ingestion.trusted-proxy-addresses`，
  启动基线逐条核对它与 `server.tomcat.remoteip.internal-proxies` 是否一致——
  声明的每个代理必须落在正则内，正则也不允许命中声明范围之外的地址
  （用哨兵地址与网段边界外探针判定，例如信任整个 10/8 会被拒绝）。
- **未配置即失败**：native 且未声明可信代理、或声明与正则不一致，启动即拒绝；
  `framework` 策略依然被拒绝。

仍然保留的限制：反代必须**覆盖**客户端自带的 `X-Forwarded-For`（否则持可信代理的对端仍能伪造生效地址）。
这一点无法由应用侧判定，只能靠反代配置保证。

## 交付口径（已实现 vs 后续功能）

这一节是为了防止把「外围机制已就绪」读成「功能已上线」。

| 能力 | 当前状态 | 说明 |
| --- | --- | --- |
| Worker 执行形态 | **run-once 单次执行** | 进程取一批窗口、跑到时间预算或取空即退出。仓库内**没有** cron / systemd timer / K8s CronJob，也没有常驻调度器。窗口不会自己出现，需要管理员手动入队。 |
| 自动窗口调度 | **未实现** | 归入 [WP-08](wp-08-backlog.md)。调度归属、互斥、失败窗口处置都还没定。 |
| 模型客户端 | **`DisabledNewsCurationModelClient`** | 任何调用直接失败，不发 HTTP、不产生付费调用。`news-curation.enabled` 默认 `false`。 |
| 真实模型 Provider | **未接入** | 预算预扣/结算、幂等回执、`OUTCOME_UNKNOWN` 人工对账、鉴权熔断这些**外围机制**已就绪，但真实适配器没有，归入 [WP-08](wp-08-backlog.md)。 |
| NewsBench 扩充 | **未做** | 门禁已要求三类样本齐备，数据集仍是仓库内冻结夹具，扩充需人工评审，归入 [WP-08](wp-08-backlog.md)。 |
| 失败窗口恢复 | **已实现**（WP-07G） | 管理员可从工作台重开失败窗口；系统仍不自动跳过失败窗口，也不静默推进游标。 |
| Worker 凭据强度 | **32–256 位可见 ASCII** | 与代码校验一致（`NewsIngestionProperties.isValidRawKey`），约合 200 bit 熵。此前文档口径与实现不一致，本轮已对齐。 |

采集与自动发布、模型精选均默认关闭；「合并进主干」不等于「在生产启用」。

## 剩余风险

- 失败窗口不会自动跳过（设计如此）：一个确定性失败的窗口会让该 `(source, sourceConfigVersion)`
  后续窗口无法领取、游标无法推进。WP-07G 已提供**失败窗口重试入口**（`POST /api/admin/news-workbench/tasks/{taskId}/retry`）
  与**阻塞原因展示**（工作台任务列表直接写明卡在哪个更早任务上），恢复不再需要升 `sourceConfigVersion` 开新泳道；
  但「永久放弃某个窗口」这个动作**仍然没有**——它会一直阻塞同泳道的后续窗口，直到该窗口被重试成功。
- 采集运行中公读 P95 增幅 12.59%，超过设计目标；本机基线不构成目标环境结论。
- NewsBench 门禁已要求对抗/许可/注入三类样本齐备；数据集本身仍是仓库内的冻结夹具，扩充需人工评审。

## 降级验证

- Redis 故障：停止 Redis 后公开资讯读仍在 549 ms 内成功返回（此前版本会阻塞到 40 s），恢复后正常。
- 任务领取拒绝无凭证请求（401）；控制面不可用时 Worker 返回 `CONTROL_UNAVAILABLE`。
- 采集关闭、模型精选关闭（`curationEnabled=false`）、付费模型调用 0 次。

## 目标环境的剩余验收

在目标环境先确定一个执行调度入口。若为单台 Linux 主机，复用同一个稳定锁路径，以非阻塞方式串行执行 `run-once`。按首轮资源口径限制 Worker 为 0.5 CPU、256 MiB，用单执行槽和显式秒数预算验证超时退出、重叠时不领取任务以及下一轮可启动。在多主机部署中，互斥证据须覆盖不同主机的重叠触发。

每次只给测试 Worker 授权一个信源，投递显式小窗口。记录请求量、终止条件、有效修订、失败回执、游标及资源峰值。通过灰度验收后再逐源决定是否启用。PubMed 与 ClinicalTrials.gov 须在无 SNI 过滤的公网环境重跑正式 Worker。

P95 使用同一目标主机、相同负载、相同预热方式和固定样本做两组对照，一组关闭采集，一组采集运行中。分别记录 Java 请求与眼底推理耗时的 P95、样本数、错误率和 Worker 资源数据。目标环境和压测结果目前为 `NOT_RUN`。付费模型联调须另行授权与明确调用上限。
