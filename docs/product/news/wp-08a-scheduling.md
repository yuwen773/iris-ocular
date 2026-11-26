# WP-08A 自动资讯采集

Java 在现有 Spring 调度器上周期规划窗口。Python Worker 以 `run-once` 领取、采集并上报。MySQL 保存窗口、配置归属、调度状态和 Worker 领取观测。调度无需 Redis 或模型服务。

## 窗口与版本

在资讯工作台的「信源与游标」登记查询、配置版本和初始游标。初始游标为已有覆盖的排他末日，或者首次回灌要覆盖的 UTC 起始日期。选择已知边界并记录覆盖依据。已登记的版本与查询绑定。

每次采用 MySQL UTC 日期计算安全上界。窗口以 `[startOn, endBefore)` 表示，推进段从已提交游标起步，长度受 `window-days` 限制，截止于今天减 `lag-days`，当天始终排除。窗口向前回看 `lookback-days`，最早不超过版本首次采用的游标。无新日期时停止规划。游标落后超过 `max-backfill-days` 时暂停，管理员核对后可在上限内扩大回灌范围。通过已授权的手动窗口先处理更旧的初始区段。

| 信源 | 官方查询日期 | 分页与迟到数据 |
| --- | --- | --- |
| PubMed | ESearch 的 `crdt` 与 `lr` 分支合并，覆盖初次建档与修订 | ESearch 历史区与 EFetch 分批，受记录上限约束。延迟与修订通过回看再次读取。[PubMed Help](https://pubmed.ncbi.nlm.nih.gov/help/) · [NCBI E-utilities](https://www.ncbi.nlm.nih.gov/sites/books/NBK25499/) |
| Europe PMC | 搜索字段 `UPDATE_DATE`，覆盖索引更新 | `cursorMark` 与 `nextCursorMark` 只在该次采集中使用。摘要仍执行适配器许可检查。[REST API](https://europepmc.org/RestfulWebService) · [字段参考](https://dev.europepmc.org/doc/EBI_Europe_PMC_Web_Service_Reference.pdf) |
| ClinicalTrials.gov | `AREA[LastUpdatePostDate]RANGE`，覆盖试验记录更新的公开发布日期 | `pageToken` 与 `nextPageToken` 只在该次采集中使用。保留 NCT ID 和试验结构。[OpenAPI](https://clinicaltrials.gov/api/oas/v2) |

适配器将排他截止日转换成源站查询的最后一个自然日。回看是有限的。超出回看范围的延迟或历史更正可以按原查询条件手动补采，资料合并遵循现有同源身份及不可变修订规则。

## 调度、幂等与恢复

- `eye-diagnosis.news-ingestion.enabled` 与 `eye-diagnosis.news-schedule.enabled` 都打开时才扫描配置。每个信源还受策略 `enabled` 和工作台信源开关控制。
- 各实例共用现有信源行锁。每轮对最多三个已配置的信源分别运行事务，每个信源最多创建一个窗口。当前信源有积压即暂停开窗，`FAILED` 保持阻塞，按任务 ID 从工作台重试。其他信源继续运行。
- 自动任务使用 `auto-<source>-<version>-<startOn>-<endBefore>` 主键，任务类型为 `AUTO`，手工任务为 `MANUAL`。自动窗口与 `AUTO_ENQUEUE` 审计、调度状态在同一事务提交。重复触发与重启均读取持久化结果。
- 已采用版本只允许手工重放已提交日期。退出自动调度后，既有归属仍保留。升版时先停用**所有 Java 实例**上的旧版策略，保持 Worker 运行，完成旧版积压并处置失败。以最终游标登记后继版本，再启用新策略。已登记的游标须与前序最终进度相同。新版本采用后，较低版本的实例停止规划。
- 正常流程为入队、领取、批次上报、完整提交。Worker 临时故障使用原有退避状态，确定性失败与耗尽尝试次数进入 `FAILED`。运维按失败码排查，再有审计地重开原任务。进程退出后由原有租约和 generation 恢复。游标只在原有完整提交事务中推进。
- 工作台查询提供上次调度、下次允许调度时间、自动窗口、相关阻塞任务、积压和最近领取请求。下一次实际执行还受轮询周期与数据库可用性影响。Worker 最近领取请求用于观察活跃情况，执行退出原因由 journal 回执提供。

V44 为既有任务追加默认 `MANUAL` 的类型和查询索引，并新增 `t_news_collection_schedule` 与 `t_news_worker_poll`。V40–V43 原样保留。Flyway 只执行正向迁移。

## Java 外部配置示例

各环境可独立调节频率和延迟留量。示例开关保持关闭，版本号须对应管理员登记的记录。轮询周期为 5 秒至 1 小时，默认 1 分钟。

```yaml
eye-diagnosis:
  news-ingestion:
    enabled: false
  news-schedule:
    enabled: false
    poll-interval: PT1M
    sources:
      - source: pubmed
        enabled: false
        source-config-version: 1
        interval-minutes: 20
        window-days: 2
        lag-days: 2
        lookback-days: 3
        max-backfill-days: 30
      - source: europe-pmc
        enabled: false
        source-config-version: 1
        interval-minutes: 15
        window-days: 1
        lag-days: 1
        lookback-days: 2
        max-backfill-days: 30
      - source: clinicaltrials-gov
        enabled: false
        source-config-version: 1
        interval-minutes: 60
        window-days: 3
        lag-days: 2
        lookback-days: 2
        max-backfill-days: 60
  news-curation:
    enabled: false
```

## Linux Worker 部署示例

选择单机 systemd timer。空闲时只有 systemd 自身计时，无 Worker 常驻内存。同一个 unit 的启动由 systemd 串行，资源与超时在 cgroup 内执行，journal 汇总日志。cron 加 flock 也能在单机串行，但需要组合锁、超时和日志轮转。容器化短任务适合已有定时平台，当前部署不依赖该平台。多主机的窗口互斥由 Java/MySQL 租约约束，跨主机官方速率预算需另行验收。初次灰度只启动一台 Worker。

1. 创建无登录权限的服务账号和组 `iris-news`。在 `/opt/iris-news-worker` 用 `uv sync --locked --no-dev --python /usr/bin/python3.12` 安装依赖，要求该路径已有系统 Python 3.12。虚拟环境与解释器都须可由服务账号读和执行，并位于用户主目录之外。该目录由发布账号写入，Worker 只有读权限。
2. 从 [systemd 示例](../../../news-worker/deploy/systemd/) 安装 `iris-news-worker.service` 和 `iris-news-worker.timer` 到 `/etc/systemd/system/`。由 root 从密钥存储生成 `/etc/iris-news-worker/worker.env`，权限 `0600`。控制面地址使用已批准的 HTTPS origin。为 Worker 配置确切信源权限。PubMed 需要有效联系邮箱。
3. 灰度获批后执行 `systemctl daemon-reload`、`systemctl start iris-news-worker.service`，从 journal 确认领任务、结束回执和状态。随后可运行 `systemctl enable --now iris-news-worker.timer`。
4. `systemctl list-timers iris-news-worker.timer` 查看下次运行。用 `journalctl -u iris-news-worker.service` 看回执。退出码 0 表示完成或空闲，1 表示任务失败，2 表示参数或配置错误。进程执行预算 55 秒，systemd 启动超时 65 秒，停止收尾上限 8 秒。三执行槽分别领取可用信源，上限为 0.5 CPU、256 MiB。
5. 暂停时先 `systemctl disable --now iris-news-worker.timer`，再关闭 Java 自动调度或单信源。若需立即停下执行中的进程，运行 `systemctl stop iris-news-worker.service`。停用所有采集再关闭控制面开关。重启服务后从既有任务和游标恢复，保留数据库中的任务、审计及历史。

生产启用还需完成目标网络的逐源实采、双档 Java 与眼底推理 P95、目标主机资源测量、多主机互斥及源站速率验收和人工授权。
