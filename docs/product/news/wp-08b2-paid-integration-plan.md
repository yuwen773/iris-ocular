# WP-08B.2 · 付费联调方案（未执行）

本文件是一份**待授权**的付费联调方案，不是执行记录。截至 2026-11-27，本仓库对真实供应商请求 **0 次**，
`eye-diagnosis.news-curation.enabled` 保持 `false`。未经仓库所有者单独授权，不发出任何真实调用。

> 2026-11-27 修订：模型 ID、币种费率、结构化输出方式、usage 字段与对账入口按官方页面核对后填实；
> 原先的 `TO_CONFIRM` 占位符已消除。本轮**仍然没有任何真实请求**，本文所有数字都是「准备就绪的上限」，
> 不是实测值。实测结论只在实验报告里写。

## 1. 目标与边界

目标只有三条，任何超出部分都要重新评审：

1. 用真实 Provider 验证 P05 单模型策略 A 能否稳定产出通过 Java 契约的结构化结果；
2. 用真实计费数据验证回执里的 `logicalCallSha256`、token 与费用字段能和帐单对上；
3. 验证预算闸门——日上限、整次实验总上限、熔断——在真实返回形态下按预期触发。

不做：双模型对照、A/B 标注、NewsBench 扩量、生产发布、自动发布、任何患者数据出境。

## 2. 模型与平台

| 项 | 取值 | 说明 |
| --- | --- | --- |
| Provider | `QWEN` | 平台 `BAILIAN`，官方固定 origin `https://dashscope.aliyuncs.com` |
| 路径 | `/compatible-mode/v1/chat/completions` | 运行时只接受固定官方 origin 与路径 |
| 模型 | `qwen-flash`（等价快照 `qwen-flash-2025-07-28`） | 百炼官方模型列表可见；快照当日以控制台为准，只有控制台列出才写进配置 |
| 上下文 | 1M，最大输入 997 952，最大输出 32 768 token | 我们的 profile 只开 8192 / 1024，远低于平台上限 |
| 结构输出 | `response-format: JSON_OBJECT` + 提示词 JSON | qwen-flash 官方支持 `json_object`（需 `enable_thinking=false`）；**`json_schema` 对 qwen-flash 未见官方承诺**，因此本实验只用 JSON_OBJECT，不先用 schema 赌稳定性 |
| 温度 | `0.2` | 与 B1 现网配置一致 |
| 超时 | connect 3s / read 20s | 不放宽；契约与预算闸门都不因超时松口 |
| usage 字段 | `prompt_tokens`、`completion_tokens`、`total_tokens`、`prompt_tokens_details.cached_tokens` | 客户端按这四项写回执；缺 `cached_tokens` 时按 0 计，不猜 |

## 3. 费率与币种（已核对）

平台计费为人民币（CNY）。仓库内 `pricingVersion` 的单位是「每百万 token 的币种**百万分之一**」
（`NewsModelProfile.PricingVersion` 注释原文），因此官方「元 / 百万 token」要 ×1 000 000 后填入：

`cost_micro = ⌈(输入 token × input + 缓存命中 token × cachedInput + 输出 token × output) / 1e6⌉`

`qwen-flash` 官方阶梯价（CNY / 百万 token），2026-11-27 核对：

| 输入长度阶梯 | 输入 | 隐式缓存命中 | 显式缓存读取 | 缓存创建 | 输出 |
| --- | --- | --- | --- | --- | --- |
| ≤128K（本实验适用） | 0.15 | 0.03 | 0.015 | 0.188 | 1.5 |
| 128K–256K | 0.6 | 0.12 | 0.06 | 0.75 | 6 |
| 256K–1M | 1.2 | 0.24 | 0.12 | 1.5 | 12 |

按本实验的 8192 输入 / 1024 输出，落到 ≤128K 阶梯。写进配置的三个整数：

| 字段 | 值 | 对应官方价 |
| --- | --- | --- |
| `input-per-million` | `150000` | ¥0.15 / 百万 token |
| `cached-input-per-million` | `30000` | ¥0.03 / 百万 token（隐式缓存命中） |
| `output-per-million` | `1500000` | ¥1.50 / 百万 token |
| `version` | `bailian-20261127` | 与核对日期绑定，改价必须改版本号 |

**尚未核实、因此不做假设的三件事**：

- 平台是否存在「最小计费粒度」或按分钟/按次取整——若帐单出现行级差异，按 §7 的 1% 阈值判不通过，
  不在本文里预先编造取整规则；
- 显式缓存（cache creation / cache read）是否默认启用。本实验不主动创建缓存，只按
  `prompt_tokens_details.cached_tokens` 记录隐式命中；
- 是否存在更新的 `qwen-flash` 快照。若控制台显示 2026 快照，先在夹具上回归 JSON_OBJECT 再改配置。

## 4. 调用与预算上限

这一次实验的约束是**硬上限**，任何一项触达都必须停下来报告，不允许「自动追加费用、切换供应商或放宽预算」。

| 项 | 上限 | 落地字段 / 代码位置 |
| --- | --- | --- |
| 整次实验最大调用数 | 20 次（任何显式重试也计入） | `lifetime-max-calls: 20` → `t_news_curation_lifetime_budget.maxCalls` |
| 整次实验最大费用 | ¥5.00 | `lifetime-max-cost-micros: 5000000` |
| 整次实验最大 token | 200 000 | `lifetime-max-tokens: 200000` |
| 日调用数 | 20 | `daily-max-calls: 20` |
| 日 token | 200 000 | `daily-max-tokens: 200000` |
| 日费用 | ¥5.00 | `daily-max-cost-micros: 5000000` |
| 单次输入 / 输出 | 8192 / 1024 token | `max-input-tokens` / `max-output-tokens`（契约拒绝超限） |
| 单次预留 | token 与费用按 profile 费率算出的**上限值**预占 | `estimated-tokens-per-call` / `estimated-cost-micros-per-call` 仅用于无 profile 的旧路径 |

按阶梯价估算（非实测）：单次约 ¥0.0028，20 次约 ¥0.06，距 ¥5 上限有两个数量级余量。这个余量的意义是
**允许失败重试**，不是允许扩量。

### 4.1 四道闸门的真实语义

| 概念 | 代码事实 | 行为 |
| --- | --- | --- |
| **预扣** | `reserveAttempt` 在发出 HTTP 之前，以事务把 `reservedCalls/Tokens/CostMicros` 写进 `t_news_curation_budget`，并插入 `RESERVED` 回执 | 先占额再调用：进程崩溃也不会「花了钱但没记账」。结算时把预留改成实际（`usedCalls += 1`），差额退回 |
| **耗尽** | 日预算 `BUDGET_EXHAUSTED`；整次实验 `LIFETIME_BUDGET_EXHAUSTED` | 预留阶段直接拒绝，**不发请求**，回执不产生新行；调用方拿到稳定错误码 |
| **熔断** | 鉴权类失败（`FAILED_DEFINITIVE` 且 `authFailure`）把原因写进 `t_news_curation_circuit` | 同一 scope 之后的所有预留被 `CURATION_CIRCUIT_BROKEN` 拒绝，直到管理员显式重置；用来挡住「密钥错了还在循环重试」 |
| **对账** | `OUTCOME_UNKNOWN`（超时、连接中断、响应不可读但可能已计费）保留预留，**不自动重发** | 必须由管理员用 `reconcileUnknownCall` 给出结论（已计费有/无输出、未计费）后才继续；显式重试同样占用 20 次总额 |

锁定顺序固定为 **circuit → 日守卫 → scope 预算 → 整次实验上限 → 回执 → 候选**，任何新增代码都不得插队；
反过来就会在并发下死锁。

整次实验上限是**单例行**（`id = 1`），计数直接来自 `t_news_model_call_receipt` 全表：换 UTC 日期、
换 budget scope、重启进程都绕不过去。配置只能**收紧**：已有的非零上限不会被更大的配置值抬高，
`0` 表示「未设置」，也不会把已记录的上限抹掉。

## 5. 凭据配置

```yaml
eye-diagnosis:
  news-curation:
    enabled: false            # 授权后由部署配置在联调窗口内临时打开，仓库默认值不动
    lifetime-max-calls: 20
    lifetime-max-tokens: 200000
    lifetime-max-cost-micros: 5000000
    daily-max-calls: 20
    daily-max-tokens: 200000
    daily-max-cost-micros: 5000000
    model-profiles:
      b2-bailian-qwen-flash:
        provider: QWEN
        serving-platform: BAILIAN
        base-url: https://dashscope.aliyuncs.com
        completions-path: /compatible-mode/v1/chat/completions
        model-id: qwen-flash
        secret-ref: IRIS_NEWS_QWEN_API_KEY   # 只引用名字，值来自运行环境
        max-input-tokens: 8192
        max-output-tokens: 1024
        temperature: 0.2
        response-format: JSON_OBJECT
        provider-capabilities:
          json-object: true
          json-schema: false
          usage-format: STANDARD
          disable-thinking: true
        pricing-version:
          version: bailian-20261127
          input-per-million: 150000
          cached-input-per-million: 30000
          output-per-million: 1500000
```

要求：

- 密钥值只进入 HTTP 鉴权头，不写入配置、日志、回执或 `callIdentityJson`；身份里只出现 `secretRef` 名字。
- 用独立子账号的 API Key，额度独立，便于按帐单对账；不与其他系统共用。
- 打开 `enabled` 只在联调窗口内，结束立刻改回 `false` 并重启；随后核对生产配置仍为 `false`。

## 6. 执行顺序（授权后）

0. 前置检查：`t_news_curation_lifetime_budget` 尚无行或上限 ≥ 本文数字；数据库是隔离实例，
   无生产数据、无患者数据；`SPRING_PROFILES_ACTIVE` 不是生产 profile。
1. 用第 4 节数字写部署配置（不入库），注入密钥，打开 `news-curation.enabled`；
2. **先只跑一份**证据充分的 Research Insight：核验 → P05 一次调用 → 回执 `SUCCEEDED` →
   `logicalCallSha256` 稳定 → `validatedOutput` 通过 → `materializeCandidate` 得到
   `CURATED_NEEDS_REVIEW` 且有结构化内容 → 建稿得到中文标题与完整章节；
3. 逐份扩到 10 次，每次核对「回执用量 / 累计闸门 / 候选内容」三件事；
4. 关闭开关，导出帐单截图与回执导出表，写回实验报告（`wp-08b2-experiment-report.md`）。

## 7. 对账与验收

- **对账口径**：`t_news_model_call_receipt` 中 `promptTokens + completionTokens` 与
  `actualCostMicros` 的当日汇总，对比百炼控制台的账单明细（按分钟出账，通常 2–10 分钟内可见）
  同日同模型金额；差异超过 1% 即判不通过并停止扩量。
- **账单入口**：`bailian.console.aliyun.com/cn-beijing/costing-balance/bill`（明细）与
  `…/costing-balance/expense`（账单总览）；出账前的金额只能记为「待对账」，不得写成「一致」。
- **幂等验证**：同一逻辑调用重复触发返回同一 `callId`，账单不应出现第二笔扣费。
- **失败语义**：鉴权失败（`FAILED_DEFINITIVE`）不得重试；未知结果（`OUTCOME_UNKNOWN`）必须走
  `reconcileUnknownCall` 人工对账，不得自动重发。
- **验收结论只可能是三种**：通过、有条件通过（列出未核对项）、不通过。未取得帐单核对的费用一致性一律记为不通过。

## 8. 未授权状态

在得到仓库所有者的单独付费授权之前，本文件的任何步骤都不执行；仓库默认配置保持
`eye-diagnosis.news-curation.enabled=false`，CI 与本地验证全部使用脚本化夹具。
本文件的费率与模型 ID 是「准备好了」的证据，不是「跑过了」的证据。

## 9. 首次联调的实际状态：阻塞（0 次真实请求）

2026-11-27，PR #172 经授权合并进 master（`74eee927`）之后按 §6 的步骤准备执行首次真实调用，
在「执行顺序」第 1 步之前停下：**仓库里没有任何运行时入口会触发 P05 调用**。

核对证据：

| 检查 | 结果 |
| --- | --- |
| `git grep "runModel(" -- src/main` | 只有 `NewsCurationService` 自身的三个重载；调用方全在 `src/test` |
| `git grep "curateRevision" -- src/main` | 唯一调用方 `NewsBenchRunner.runEndToEndWithMySql`，这是**测试用** harness（`@Component`，没有任何 `@Scheduled`/`@RestController` 调用它） |
| 管理端 25 个端点 | 信源开关/配置、任务入队/重试、预算、重置熔断、回执回收/对账/转候选、核验登记/撤回——**没有一个会发起模型调用** |
| 网络可达性 | `https://dashscope.aliyuncs.com/compatible-mode/v1/models` 无鉴权返回 `401`（0.55s），链路可用 |
| 密钥 | 当前 shell 只有 `DASHSCOPE_API_KEY`；方案里的 `secretRef: IRIS_NEWS_QWEN_API_KEY` 未设置，且该密钥未做过任何真实鉴权验证 |

按 §4 的规则「缺少必要调用入口则报告阻塞，不自行绕过」：没有新增临时端点、没有写临时脚本直连供应商、
没有放宽或跳过任何预算闸门。本轮真实请求 **0 次**，`t_news_curation_budget` /
`t_news_curation_lifetime_budget` / `t_news_model_call_receipt` 均无新增行，
`eye-diagnosis.news-curation.enabled` 仍为 `false`。

解除阻塞需要的最小改动（属于新工作包，需单独评审，不在 B2 范围内）：一个窄入口
（管理员端点或授权的 CLI）以 `Selection("production", Prompt.P05, profileKey, null)` 调用
`NewsCurationService.runModel(...)`，并且必须复用既有四道闸门与整次实验上限，
不新增任何绕过路径。入口就绪后，§6 的单份 Research Insight 应先跑；本轮不预生成实验报告，
以免把「没跑」写成「跑过」。
