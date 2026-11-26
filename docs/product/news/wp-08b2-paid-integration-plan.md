# WP-08B.2 · 付费联调方案（未执行）

本文件是一份**待授权**的付费联调方案，不是执行记录。截至 2026-11-26，本仓库对真实供应商请求 **0 次**，
`eye-diagnosis.news-curation.enabled` 保持 `false`。未经仓库所有者单独授权，不发出任何真实调用。

## 1. 目标与边界

目标只有三条，任何超出部分都要重新评审：

1. 用真实 Provider 验证 P05 单模型策略 A 能否稳定产出通过 Java 契约的结构化结果；
2. 用真实计费数据验证回执里的 `logicalCallSha256`、token 与费用字段能和帐单对上；
3. 验证预算熔断（日调用数、日 token、日费用）在真实返回形态下按预期触发。

不做：双模型对照、A/B 标注、NewsBench 扩量、生产发布、自动发布、任何患者数据出境。

## 2. 模型与平台

| 项 | 取值 | 说明 |
| --- | --- | --- |
| Provider | `QWEN` | 平台 `BAILIAN`，官方固定 origin `https://dashscope.aliyuncs.com` |
| 路径 | `/compatible-mode/v1/chat/completions` | 运行时只接受固定官方 origin 与路径 |
| 首选模型 | `qwen-flash`（候选：`qwen-plus`） | 结构化输出能力按平台文档选择 JSON Object / JSON Schema |
| 结构输出 | `response-format: JSON_OBJECT` + 提示词 JSON | 与 B1 现网配置一致；若改用 JSON Schema 需先在夹具上回归 |
| 温度 | `0.2` | 与 B1 文档一致 |
| 超时 | connect 3s / read 20s | 与 B1 客户端默认一致，不放宽 |

模型 ID 与套餐名称以接通当日官方控制台为准；**不得**在实验中改模型别名而不同时更新费率版本。

## 3. 费率与币种（待填）

平台计费为人民币（CNY）。仓库内的 `pricingVersion` 是「每百万 token 的币种百万分之一单位」，
必须与帐单币种一致，不允许混币种。

| 模型 | 输入 / 百万 token | 缓存命中输入 / 百万 token | 输出 / 百万 token | 来源 | 状态 |
| --- | --- | --- | --- | --- | --- |
| `qwen-flash` | `TO_CONFIRM` | `TO_CONFIRM` | `TO_CONFIRM` | <https://help.aliyun.com/zh/model-studio/models> | 待核对 |
| `qwen-plus` | `TO_CONFIRM` | `TO_CONFIRM` | `TO_CONFIRM` | 同上 | 待核对 |

本轮抓取官方页面时未能取得逐百万 token 的明确报价（页面只列模型与能力，价格在计费页/控制台），
因此不填推测数字。**填写方式**：从官方控制台的「模型计费」页取当日价格，按同一币种换算为
`input-per-million` / `cached-input-per-million` / `output-per-million` 三个整数，版本号命名为
`bailian-<YYYYMMDD>`，并附截图或导出文件到受控目录（不入库）。

## 4. 调用与预算上限

按「一次联调跑通一条链路」设计，上限取整且可事后核对：

| 项 | 上限 | 依据 |
| --- | --- | --- |
| 单轮联调最大调用数 | 20 次 | 十份真实资料各一次 P05，加 10 次失败重试余量 |
| 单次调用输入上限 | 8192 token | `max-input-tokens`，超出时契约直接拒绝 |
| 单次调用输出上限 | 1024 token | `max-output-tokens` |
| 单次调用预估 token | 4000 | 预算预占（`estimated-tokens-per-call`） |
| 日调用数上限 | 20 | `daily-max-calls` |
| 日 token 上限 | 80 000 | `daily-max-tokens` |
| 日费用上限 | 按第 3 节费率换算，取整到 0.01 元 | `daily-max-cost-micros`，费率确认后填写 |
| 熔断 | 任一上限触达即 `circuitBroken` | 触达后同一 scope 不再发出调用，需管理员显式 `resetCircuitBreaker` |

预算 scope 固定为单一 `GLOBAL`，不按信源或资料拆分，保证「总额」这一件事只有一个口径。

## 5. 凭据配置

```yaml
eye-diagnosis:
  news-curation:
    enabled: false            # 授权后由部署配置临时打开，仓库默认值不动
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
        pricing-version:
          version: bailian-TO_CONFIRM
          input-per-million: TO_CONFIRM
          cached-input-per-million: TO_CONFIRM
          output-per-million: TO_CONFIRM
```

要求：

- 密钥值只进入 HTTP 鉴权头，不写入配置、日志、回执或 `callIdentityJson`；身份里只出现 `secretRef` 名字。
- 用独立子账号的 API Key，额度独立，便于按帐单对账；不与其他系统共用。
- 打开 `enabled` 只在联调窗口内，结束立刻改回 `false` 并重启。

## 6. 执行顺序（授权后）

1. 填第 3 节费率、第 4 节日费用上限，`pricing-version` 改名并提交评审；
2. 部署环境注入密钥，打开 `news-curation.enabled`；
3. 用十份真实资料中的 1 份跑一轮，核对：回执 `state=SUCCEEDED`、`logicalCallSha256` 稳定、
   `actualCostMicros` 与帐单一致、冻结响应能通过 `validatedOutput`；
4. 逐份扩到 10 次，观察预算行与熔断；
5. 关闭开关，导出帐单截图与回执导出表，写回验收记录。

## 7. 对账与验收

- **对账口径**：`t_news_model_call_receipt` 的 `usedTokens`/`actualCostMicros` 汇总，对比平台帐单同日同模型金额；
  差异超过 1% 即判不通过并停止扩量。
- **幂等验证**：同一逻辑调用重复触发必须返回同一 `callId`，帐单不应出现第二笔扣费。
- **失败语义**：鉴权失败（`FAILED_DEFINITIVE`）不得重试；未知结果（`OUTCOME_UNKNOWN`）必须走
  `reconcileUnknownCall` 人工对账，不得自动重发。
- **验收结论只可能是三种**：通过、有条件通过（列出未核对项）、不通过。未取得帐单核对一律记为不通过。

## 8. 未授权状态

在得到仓库所有者的单独付费授权之前，本文件的任何步骤都不执行；仓库默认配置保持
`eye-diagnosis.news-curation.enabled=false`，CI 与本地验证全部使用脚本化夹具。
