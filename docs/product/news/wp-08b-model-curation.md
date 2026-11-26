# WP-08B.0 / B.1 · 资讯精选契约与模型调用

WP-08A 由 PR #169 合入 `631bfc4b`，正式关闭。V44 文件树与验收提交 `15ebc920` 一致，主干 CI 通过。 [合并与治理记录](https://github.com/yuwen773/iris-ocular/pull/169)

## 资讯产品与证据

Iris 提供专业眼科研究精选。内容面向大众阅读，保留研究设计、证据限制和必要的专业术语。

| 类型 | 信息范围 | 运营目标 |
| --- | --- | --- |
| `RESEARCH_INSIGHT` | 研究问题、设计、主要发现、意义、局限及可核验证据。Java 要求合法可用的实际结果，以及核验过的设计、同行评审和研究完整性 | 每日 3–5 篇，每篇约 400–800 字 |
| `RESEARCH_RADAR` | 题录或开放摘要支持研究介绍和关注理由。只靠标题时，内容限于研究主题与后续关注价值 | 每日 5–15 条，每条约 80–150 字 |
| `TRIAL_WATCH` | 试验阶段、登记状态、研究设计和实质性状态变化。首次记录建立观察基线。现有登记证据无实际结果数据 | 按事件更新 |

数量和长度用于运营规划。证据不足时减少篇数或缩短内容。Weekly Digest、语义聚合和个性化后续处理。

`relevanceScore` 与 `researchValueScore` 独立记录，无法评估的研究价值用 `null` 表示 UNKNOWN。Java 单独执行证据门禁，由已核验的事实决定编辑路由。模型可给出建议。高分无法放行证据不足、撤稿或存在完整性问题的资料。

来源、研究设计、同行评审、可用范围、使用许可及完整性分别表达。现有采集协议能识别许可、部分试验设计和登记状态。同行评审、完整性及实际试验结果缺失时保持 UNKNOWN。本轮已有资料走 Radar 或 Trial Watch。新快照的开放摘要标记为 `LICENSED_ABSTRACT`，历史快照保持原值与哈希，工作台按未验证同行评审展示两者。试验 COMPLETED、PHASE3 和 PHASE4 只表示登记事实。Java 拦截常见无结果疗效论断，既有发布入口继续检查冻结证据及人工审核。

## 提示词与结构化协议

`src/main/resources/news/curation/wp08b-v1.json` 同时保存提示词正文与各自的 JSON Schema。

| 提示词 | 阶段 | 契约 |
| --- | --- | --- |
| P01 | SCREENING | 眼科相关性、可为 UNKNOWN 的研究价值、类型建议及理由 |
| P02 | GENERATION | Research Insight |
| P03 | GENERATION | Research Radar |
| P04 | GENERATION | Trial Watch |
| P05 | JOINT | 评分、类型建议和可空的结构化稿件 |

Java 验证字段、类型、证据路由、精确引文以及 HTML、链接、指令和常见临床越界表达。Trial Watch 只能复述平台登记的状态、阶段和设计，按 Java 计算的变更字段编码，不能增加自由文本疗效结论。可引用证据 ID 为题录 `title`、已授权的 `abstract` 和登记 `trial`，都绑定该次不可变资料修订。逐字匹配只证明引文存在。结论是否由引文语义支持、是否遗漏矛盾证据，由人工审核和后续标注评测确认。B1 有效结构化结果留在调用回执内，待后续明确的候选接入流程。

## 适配与安全配置

继续使用仓库锁定的 Spring AI 1.1.2 和 Spring AI Alibaba 1.1.2.2。资讯模块通过 `OpenAiChatModel` 与 `OpenAiApi` 使用 OpenAI Compatible Chat Completions。其余 Spring AI Alibaba 诊断与 Agent 路径沿用原有模型配置。配置中心发布快照当前拒绝 Provider 命名空间，资讯首期从部署配置解析独立模型实例，从环境变量读取 `secretRef`，暂不扩展配置中心。

| 模型 / 平台 | 固定 HTTPS 主机及路径 | 结构化和计费差异 |
| --- | --- | --- |
| Qwen / 百炼 | `dashscope.aliyuncs.com` 或 `dashscope-intl.aliyuncs.com` 的 `/compatible-mode/v1/chat/completions` | 按具体模型能力选 JSON Object、JSON Schema 或提示词 JSON。按帐号价格冻结费率 [百炼结构化输出](https://help.aliyun.com/en/model-studio/qwen-structured-output) |
| DeepSeek / 百炼或官方 | 百炼路径，或 `api.deepseek.com` 的 `/chat/completions`、`/v1/chat/completions` | 官方可选 JSON Object。官方缓存价差根据 `prompt_cache_hit_tokens`、`prompt_cache_miss_tokens` 结算 [官方 Chat API](https://api-docs.deepseek.com/api/create-chat-completion/) · [官方计费和模型](https://api-docs.deepseek.com/quick_start/pricing) |
| Spark / 讯飞 | `spark-api-open.xf-yun.com/v1/chat/completions` | 使用提示词 JSON，凭据是平台的 APIPassword，模型 ID 与套餐用量按平台文档核对 [星火 HTTP 文档](https://www.xfyun.cn/doc/spark/HTTP%E8%B0%83%E7%94%A8%E6%96%87%E6%A1%A3.html) |

开发使用运行时可独立指定的模型 ID。线下配置示例采用百炼，真实费率和凭据应来自受控部署配置。本轮默认关闭。

```yaml
eye-diagnosis:
  news-curation:
    enabled: false
    model-profiles:
      research-qwen:
        provider: QWEN
        serving-platform: BAILIAN
        base-url: https://dashscope.aliyuncs.com
        completions-path: /compatible-mode/v1/chat/completions
        model-id: qwen-flash
        secret-ref: IRIS_NEWS_QWEN_API_KEY
        connect-timeout: 3s
        read-timeout: 20s
        max-input-tokens: 8192
        max-output-tokens: 1024
        temperature: 0.2
        response-format: JSON_OBJECT
        provider-capabilities:
          json-object: true
          json-schema: false
          usage-format: STANDARD
        pricing-version:
          # 测试费率示例。真实启用前填同一币种的已核定费率，每百万 token 对应的币种百万分之一单位。
          version: fixture-v1
          input-per-million: 1000000
          cached-input-per-million: 1000000
          output-per-million: 2000000
```

运行时只接受固定官方 HTTPS origin 和路径。资讯使用独立 JDK HTTP/1.1 客户端，保持 POST 的非幂等重试语义，并拒绝启用 JDK 全方法重试。Spring Retry 只尝试一次，禁用跳转、工具执行、模型回退和结构修复重发。输入只包含可见的公众研究资料及同实验冻结输出。联网搜索、工具、MCP、RAG、Agent Memory 和患者数据都在该调用边界之外。支持的模型可显式配置 `disable-thinking`，通过 Spring AI `extraBody` 使用相应平台参数。Spark 原生响应的 `sid` 用于对账，成功且未到输出预算的无结束标志响应由适配器补齐供 Spring AI 读取。 [Spring AI 1.1.2 API](https://docs.spring.io/spring-ai/docs/1.1.2/api/org/springframework/ai/openai/OpenAiChatModel.html)

## 回执、命名空间与预算

V45 添加调用身份、成功冻结输出、缓存 Token 和全局日预算锁。逻辑身份包含资料修订、production 或 experiment 命名空间、策略版本、处理阶段、供应平台与模型 ID、Prompt SHA-256、配置 SHA-256，以及可选的上游冻结响应摘要。一次实际尝试用逻辑摘要与 `attemptNo` 作为幂等键。配置哈希含费率版本和凭据引用，凭据值只进入 HTTP 鉴权。应冻结模型版本并避免在实验中更新模型别名。

跨阶段只接受同资料修订、命名空间和策略内已成功的 SCREENING 回执。实验和新生产命名空间的 B1 输出都留在回执账本，与旧候选和发布轨迹隔离。V44 及更早回执的身份扩展字段为 NULL，仍走原来的候选和对账路径。

调用前冻结并提交预扣，随后提交 DISPATCHED，再执行一次 HTTP。全局日预算与各 scope 同时检查，以 MySQL 日预算行仲裁。真实适配器预扣配置的输入和输出上界，按未命中缓存费率取成本上界。计费信息完整时直接结算。缓存命中与普通输入分开计费。有效结构化输出为 SUCCEEDED，证据或结构无效且用量已知记 FAILED_CHARGED。明确的鉴权拒绝和官方 402 记 FAILED_DEFINITIVE。超时、断线、重定向、含糊的限流、服务端错误、缺失账单或超出已授权用量包络进入 OUTCOME_UNKNOWN，保留原日预算。已收费但未取回合规输出的尝试不自动重发。审计对账复用现有接口与状态机。

同一 UTC 日接纳各实例已提交的最严格全局限额。确认免费失败的恢复入口要求显式指定同身份、同 scope 的最后一张失败回执。凭据轮换后仍须由管理员复位已触发的熔断。普通调用重复执行只读已知终态，下一次授权尝试再写新的 `attemptNo`。

## NewsBench v2 实验与后续

- **A 单模型**调用 P05 完成评分、路由建议和结构化生成。
- **B 双模型**由轻量模型运行 P01，再由高质量模型在冻结 P01 输出后运行 P02、P03 或 P04。Java 路由与 A 相同。
- **C 自适应路由**留待 A/B 数据支持。生产策略取决于实测效果与成本。
- 计划人工标注 160 条，按同研究身份分组切分开发集 100、留出集 60，防止同一研究跨集。单独记录难例与医学安全样本。原冻结夹具继续用于回归。
- 评测记录 Precision/Recall、研究价值判断、路由准确率、关键论断语义支持率、医学事实错误、中文质量、JSON 契约、费用、延迟与稳定性。人工标注与模型预测采用不同字段及审计来源。首次付费评测需另行批准预算与供应商网络条件。
