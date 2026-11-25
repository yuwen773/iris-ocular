# WP-07 · 资讯采集灰度验收

验收日期为 2026-11-25，最终核验基线为 `0e442e1e`（在 `e0dabebf` 之上叠加 WP-07C 整改与 Europe PMC 单页修复）。本次使用 Windows 工作站、隔离的 Spring Boot `ci` 实例及临时 MySQL 8、Redis 7、Chroma 容器。模型精选保持关闭，实测只涉及眼科资讯采集。以下结论按验证手段分组，**不互相顶替**：官方 API 探针、离线夹具重放、正式 Worker 实采、真实端到端联调各有独立口径。

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

眼底推理 P95 本轮未纳入（无推理负载），**目标环境 P95 与门槛判定为 `NOT_RUN`**，上表仅本机基线意义。

## 降级验证

- Redis 故障：停止 Redis 后公开资讯读仍在 549 ms 内成功返回（此前版本会阻塞到 40 s），恢复后正常。
- 任务领取拒绝无凭证请求（401）；控制面不可用时 Worker 返回 `CONTROL_UNAVAILABLE`。
- 采集关闭、模型精选关闭（`curationEnabled=false`）、付费模型调用 0 次。

## 目标环境的剩余验收

在目标环境先确定一个执行调度入口。若为单台 Linux 主机，复用同一个稳定锁路径，以非阻塞方式串行执行 `run-once`。按首轮资源口径限制 Worker 为 0.5 CPU、256 MiB，用单执行槽和显式秒数预算验证超时退出、重叠时不领取任务以及下一轮可启动。在多主机部署中，互斥证据须覆盖不同主机的重叠触发。

每次只给测试 Worker 授权一个信源，投递显式小窗口。记录请求量、终止条件、有效修订、失败回执、游标及资源峰值。通过灰度验收后再逐源决定是否启用。PubMed 与 ClinicalTrials.gov 须在无 SNI 过滤的公网环境重跑正式 Worker。

P95 使用同一目标主机、相同负载、相同预热方式和固定样本做两组对照，一组关闭采集，一组采集运行中。分别记录 Java 请求与眼底推理耗时的 P95、样本数、错误率和 Worker 资源数据。目标环境和压测结果目前为 `NOT_RUN`。付费模型联调须另行授权与明确调用上限。
