# Iris News Worker

以单次短任务运行的 Python 3.12 Worker。使用官方 API 采集 PubMed、Europe PMC 和 ClinicalTrials.gov，按 [Worker 协议](../contracts/news/README.md) 上报原始资料。

## 本地验收

在 `news-worker/` 执行以下命令。

```text
uv sync --locked --python 3.12
uv run --locked ruff check .
uv run --locked ruff format --check .
uv run --locked pytest -q
uv run --locked iris-news-worker --help
```

`uv run --locked pytest -q tests/test_cli.py` 通过正式 CLI 入口执行三源离线用例。Mock Java 接收并验证 HTTP 领任务、批次、完成和失败协议。源 API 使用固定夹具。依赖同步后可加 `--no-sync`，离线重复运行用例。

## 配置与执行

部署环境设置 `IRIS_NEWS_CONTROL_URL` 和 `IRIS_NEWS_WORKER_KEY`。控制面地址是 HTTPS origin，HTTP 仅用于裸回环地址的本地 Mock。PubMed 可通过 `IRIS_NEWS_NCBI_EMAIL` 提供 NCBI 联系邮箱，真实采集前按 NCBI 规则确认工具标识和邮箱。运行 `uv run --locked iris-news-worker run-once`，或使用从本目录构建的 Docker 镜像。

Java 控制面的 `require-https=true` 依赖 Servlet 容器确认连接安全。若由反向代理终止 TLS，容器应只信任受控代理，代理应覆盖客户端转发头。Worker 鉴权过滤器不会直接把请求携带的 `X-Forwarded-Proto` 当作 HTTPS 证明。

默认最多领取 4 个租约，每个执行槽领取一次。全局 HTTP 并发最多 4，单信源主机只有一个请求在途。执行预算默认 600 秒，`--seconds` 和 `--concurrency` 可缩短时间或降低并行度。进程收到中断信号后最多留出 5 秒收尾，并始终遵守当前任务的有效截止时间。部署计划应避免定时执行重叠，同一部署的跨进程速率与进程硬时限由调度器控制。

输出一条 JSON 运行回执。全部任务完成或空闲时退出 0，任一任务失败或无法确认完成时退出 1，配置或参数错误时退出 2。错误按分类码输出，诊断不含机器凭证、请求 URL、分页令牌或原始响应。失败窗口由控制面保留游标并按租约规则处理。

## 资源测量与真实信源验证

分别在新进程执行 `uv run --locked python -m tests.measure startup`、`normal`、`dense`、`retry`。结果包含 CPU 时间、墙钟时间、峰值工作集、请求数和提交条数。峰值包含运行在同一进程内的模拟器和夹具，适用于本机离线基线。资源与信源实测状态见 [本轮验收记录](../docs/archive/2026-12/news-worker-acceptance.md)。
