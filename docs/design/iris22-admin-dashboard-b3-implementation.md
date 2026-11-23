# B3 · 运营总览改版实施记录

> 对应审计：[`iris22-admin-dashboard-b3-audit.md`](iris22-admin-dashboard-b3-audit.md)
> 实施范围：只改 `web/` 的运营总览页与其读数归一；不动 D-01b、OOD、数据库迁移与
> Analytics 口径。改动随本分支提交。
> 日期：2026-11-22

## 1. 已确认的四项决策

| 项 | 决策 | 落地方式 |
| --- | --- | --- |
| 人工复核积压卡 | A · 历史只读 | 删除「积压 / 去复核」，改为「复核历史记录」卡，只解释能力边界并链接 Analytics 复核分区 |
| 复核状态分布 | 完整移交 Analytics | 仪表盘不再请求/渲染 `reviewDistribution` 图；详细复核统计只在数据分析 |
| 系统健康状态 | 暂不立项接口 | 不画健康灯；无 Actuator、无 health 控制器时不设计该区块 |
| 平均置信度 | 统一 Analytics 口径 | 从仪表盘核心 KPI 与脚注移除；脚注只给「数据分析 · 模型与图像质量」入口 |

## 2. 页面结构（四区）

| 区块 | 内容 | 数据源 | 可操作性 |
| --- | --- | --- | --- |
| 01 核心运营概况 | 窗口内判读（含环比）、AI 拒识（占判读比）、新增用户（含环比）、待处理事项（知识库失败 + 资讯待审核） | `GET /api/stats/admin/overview` | 只读；详细事项在 03 处理 |
| 02 近期运营趋势 | 判读趋势（含上一等长窗口）、病种分布 | `diagnosisTrend` / `previousDiagnosisTrend` / `diagnosisDistribution` | 只读 |
| 03 真正可处理的运营待办 | 知识库处理失败 → `/admin/knowledge?status=2`；资讯待审核 → `/admin/news?status=3`；AI 拒识 → `/admin/diagnosis?review=aiRejected`，按钮文案是「查看记录」 | `knowledgeFailed` / `newsByStatus` / `diagnosisAiRejected` | 前两项真实处理闭环；拒识只读 |
| 04 最近活动与历史入口 | 最近 5 条判读（全量入口）；复核历史卡 → `/admin/data-analysis?days=N&section=review` | `GET /api/diagnosis/list/page/vo`；无复核数字 | 查看；历史入口保留可发现性 |

空窗口形态保留原来的解释面板，但把复核历史卡放在解释面板旁，最近记录与待办成对，
不出现半张卡高的空洞。

## 3. 数据口径修正（P1）

`web/src/lib/adminApi.ts` 的 `AdminStats` 计数从「一律转 number」改为：

- `optNum()`：缺失 / `null` / 空串 / 非法值 → `undefined`；真实 `"0"` → `0`。
- 状态分布、趋势等数组字段缺失时保持 `undefined`，不再统一变成 `[]`。
- 仪表盘 `read()`：统计未返回或字段缺失 → `—`；真实零 → `0`。
- 「待处理事项」只有知识库失败与资讯待审核两项都已知才给合计；缺一项显示
  「待办口径未接入」，不把未知补成 0。
- 平均置信度不再进入仪表盘；旧 `confidencePercent` / `normalizeConfidence` 的混合量纲
  展示从本页移除。

## 4. 不展示的内容（与能力边界一致）

- 不展示「人工复核积压」「去复核」或任何待处理计数。
- 不展示复核状态分布图；详细复核统计只在 Analytics。
- 不展示平均置信度、置信度趋势或「诊断准确率」类读数。
- 不展示系统健康灯；没有真实探针就不设计。
- 不展示 OOD 健康状态；OOD 仍恒为 `NOT_EVALUATED`，由 Analytics 明确标注「未评估」。

## 5. 代码与样式

| 文件 | 变更 |
| --- | --- |
| `web/src/pages/admin/AdminDashboardPage.tsx` | 四区重构；删除积压卡/复核分布/置信度脚注；新增复核历史卡；待办文案区分「处理 / 查看」 |
| `web/src/lib/adminApi.ts` | `AdminStats` 计数改为可选；新增 `optNum`；数组字段缺失不再补 `[]` |
| `web/src/styles/admin.css` | 复核历史卡正文与元信息、待处理事项拆项换行 |
| `web/src/pages/admin/AdminDashboardPage.test.tsx` | 新增 4 例 B3 语义测试（历史只读、拒识只读、缺字段不补零、移除置信度） |
| `web/src/lib/adminApi.test.ts` | 新增 1 例计数归一（0 / 缺失 / 字符串 / 数组缺失） |

## 6. 验证

### 6.1 静态与单测

- `npm run typecheck`：通过。
- `npx vitest run`（Dashboard、DataAnalysis、analyticsDomain、adminApi、
  hardcoded-colors）：5 文件 / 65 例通过。
- `npm run lint`：通过。
- `npm run build`：通过。

### 6.2 真实数据联调

- 最新后端（Flyway 到 V30，隔离 schema `diagnosis_b3probe`，MySQL 8.0.26）+ 造数：
  9 条判读（含拒识、待复核、契约外状态）、2 条知识库失败、2 条待审核资讯。
- 仪表盘读到：窗口内判读 9 / AI 拒识 3（33.3%）/ 新增用户 2 / 待处理事项 4
  （知识库失败 2 · 资讯待审核 2）。
- 历史复核卡链接实测跳到 `/admin/data-analysis?days=30&section=review`。
- 所有 `pageerror` / console error 为空。

### 6.3 双主题与移动端截图

Playwright（本机 Chromium）截取桌面 1440×900 与手机 390×844 × 明暗共 4 张：

- [桌面浅色](C:/Users/yuwen/AppData/Local/Temp/iris-b3-shots/desktop-light-dashboard.png)
- [桌面深色](C:/Users/yuwen/AppData/Local/Temp/iris-b3-shots/desktop-dark-dashboard.png)
- [手机浅色](C:/Users/yuwen/AppData/Local/Temp/iris-b3-shots/mobile-light-dashboard.png)
- [手机深色](C:/Users/yuwen/AppData/Local/Temp/iris-b3-shots/mobile-dark-dashboard.png)

截图目录为临时产物，不入库。

视觉验收中修正：

1. 病种名「年龄相关性黄斑变性」在窄列被截断 → 名称列宽从 104 调到 128，
   桌面与 390px 均完整显示。
2. 手机端「知识库失败 2 · 资讯待审核 2」在「审核」中间断行 → 拆成两个可换行项，
   只在分隔处换行。

## 7. 已知边界

- 最近判读表仍显示记录自身的复核状态（如「待人工复核」）。这是历史记录属性，
  页面不提供任何写入入口，也不把它汇总成当前待办；复核写入仍由 D-01b 关闭。
- 部署实例的 MySQL 版本核验不在本次范围，继续作为发布门禁执行。
