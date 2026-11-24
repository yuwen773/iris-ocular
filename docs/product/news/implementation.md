# Iris News Engine 首轮实施与 Java 交接

首轮交付 WP-00、WP-01A、WP-01B。Python Worker 的单次运行、配置和验收入口见 [Worker 说明](../../../news-worker/README.md)，跨语言消息、回执与状态规则以 [v1 协议](../../../contracts/news/README.md) 为准。

## 实施决策

- Worker 采集并上报原始资料。Java 控制面负责分配窗口、租约、游标、重试预算和可见性。本阶段以内存 Mock Java 验证交换协议。
- 首批三源使用官方 API。PubMed 通过 ESearch History 和 EFetch 投影 PMID 记录，创建日与修订日的组合查询需要小样本实测。Europe PMC 以源内更新日和游标分页采集，空结果与最终页返回形态需要实测。其 `firstPublicationDate` 可能由源站补全缺失的月或日，`publishedOn` 保留源站给出的值。ClinicalTrials.gov 以更新发布日期查询并保留 NCT ID 和试验结构。 [NCBI E-utilities](https://www.ncbi.nlm.nih.gov/sites/books/NBK25499/) · [Europe PMC 字段参考](https://staging.europepmc.org/doc/EBI_Europe_PMC_Web_Service_Reference.pdf) · [ClinicalTrials.gov OpenAPI](https://clinicaltrials.gov/api/oas/v2)
- 原始资料、健康资讯和临床证据各有准入。摘要保留依赖可识别的许可字段和后续启用核验。当前无全文下载，跨信源 PMID/DOI 合并留给事件聚合。原站接口展示最新可追踪状态，重叠回看加显式补采覆盖迟到更新。
- 部署环境管理机器凭证及外联限制。业务控制面管理信源查询、采集频率和窗口策略。将来使用模型时再接成熟的配置中心执行路径。参考 AIHOT 的采集、回执及编辑发布分层，Iris 的生产落地仍沿用现有 Java 和 MySQL。 [配置边界](../wp-00/configuration-boundary.md) · [AIHOT 架构](https://github.com/KKKKhazix/AIHOT/blob/main/docs/architecture.md)

## 后续集成门槛

| 工作包 | 前置条件与验收 |
| --- | --- |
| WP-02 · Java 调度与资料池 | 重查远端和活跃迁移后分配 Flyway 版本。实现按机器身份和指定内网路径授权，验证 Cookie、小程序 Bearer 与 Worker 凭证的边界。用真实 MySQL L2 覆盖领取并发、重复与过期回执、修订唯一性、部分批次可见性、崩溃恢复及游标事务。Java 时钟偏差和单信源并行窗口需要专项验证。 |
| WP-03 · 精选与评测 | 在已完成原始资料上开展 NewsBench，确定证据规则、模型调用回执、未知付费结果的恢复策略与预算。 |
| WP-04 · 审核与发布 | 绑定原始证据快照和内容修订。逐入口验证原有创建、批量发布、撤回流程。发布门禁集中在服务层，缓存失效安排在事务提交后，并验证 Redis 失效失败时的撤回可见性。 |
| WP-05、WP-06 · 工作台和刊物 | 基于 WP-02 至 WP-04 的已验收数据，逐步接入任务追踪、信源及预算管理、跨信源聚合和报告。 |
| WP-07 · 灰度 | 真实信源逐一验收，核验数据使用许可。以部署环境的资源限制和定时互斥执行，测量 Worker 自身开销，以及同时运行时 Java 和眼底推理的 P95 延迟。 |

离线验收和资源读数见 [首轮验收记录](../../archive/2026-12/news-worker-acceptance.md)。三个信源分别核验、分别启用。
