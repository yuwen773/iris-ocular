# PR #153 · rebase 后视觉回归证据

> 日期：2026-11-23
> 分支：`t3/admin-analytics-dashboard` @ `af988ef5`（rebase 到 `master@c3808e4c`）
> 范围：Dashboard（B3）与数据分析工作台（B2）的亮/暗主题、桌面/390px、口径抽屉与移动端表格。

## 1. 方法与边界

本轮是**前端视觉回归**，不替代真实后端联调：

- 启动 Vite dev server（`127.0.0.1:5176`）。
- 用 Playwright Chromium 拦截 `/api/**`，返回固定夹具：
  - `/user/get/login`：管理员会话；
  - `/stats/admin/overview`：86 条窗口判读、12 条 AI 拒识、4 条待处理事项；
  - `/stats/admin/analytics`：复核、拒识、模型与质量、数据质量四域；
  - `/diagnosis/list/page/vo`：5 条最近判读。
- 通过 `localStorage.theme` 固定亮/暗主题；视口 1440×900 与 390×844。
- 取证过程中 `pageerror` 与 `console.error` 均为 0。

这样能稳定检查版式、密度、主题对比、抽屉层级与横向表格；**不能**证明 SQL 口径或真实接口连通性。
后者由 B2 的真实数据联调、MySQL 集成用例与 PR CI 覆盖。

## 2. 结论

| 检查项 | 结果 |
| --- | --- |
| Dashboard 四区结构 | 通过：KPI / 趋势与病种 / 运营待办 / 复核历史 + 最近判读 |
| 复核积压语义 | 通过：页面无「积压」「去复核」，有只读「复核历史记录」 |
| 运营待办链接 | 知识库 `/admin/knowledge?status=2`、资讯 `/admin/news?status=3`、拒识 `/admin/diagnosis?review=aiRejected` |
| 历史入口 | `/admin/data-analysis?days=30&section=review` |
| 病种长名称 | 「年龄相关性黄斑变性」在桌面与 390px 均完整显示 |
| Analytics 四分区 | 通过：分析概览 / 判读与复核 / 模型与图像质量 / 数据质量 |
| 口径抽屉 | 通过：从「复核完成率」打开 A-08，静态定义与本次窗口计数分层展示 |
| 移动端表格 | 通过：`clientWidth 348 / scrollWidth 560`，`role=region`、`tabIndex=0`，可横向滚动并显示「← 左右滑动查看全部列 →」提示 |
| 双主题 | 通过：亮/暗均使用同一布局与语义色，无对比度异常 |

## 3. 证据文件

### Dashboard

- `dashboard-desktop-light.png`
- `dashboard-desktop-dark.png`
- `dashboard-mobile-light.png`
- `dashboard-mobile-dark.png`
- `dashboard-links.json`：待办与历史入口 href、无「积压 / 去复核」断言

### Analytics

- `analytics-overview-desktop-light.png`
- `analytics-overview-desktop-dark.png`
- `analytics-drawer-desktop-light.png`：复核完成率口径抽屉
- `analytics-model-mobile-light-initial.png` / `-scrolled.png`
- `analytics-model-mobile-dark-initial.png` / `-scrolled.png`
- `analytics-mobile-light-table.json` / `analytics-mobile-dark-table.json`：表格溢出与可访问性读数
- `errors.json`：空数组，表示没有页面错误

## 4. 复现说明

1. `cd web && npm ci && npm run dev -- --host 127.0.0.1 --port 5176`
2. 用 Playwright 在 `127.0.0.1:5176` 打开：
   - `/admin`
   - `/admin/data-analysis?days=30&section=overview`
   - `/admin/data-analysis?days=30&section=review`（口径抽屉）
   - `/admin/data-analysis?days=30&section=model`（移动端表格）
3. 拦截 `/api/**` 返回上述固定夹具；亮/暗主题通过 `localStorage.theme = "light" | "dark"` 切换。
4. 桌面视口 1440×900，移动视口 390×844；截图使用 `fullPage: true`。

> 截图目录只保留关键帧，不包含临时探针库、登录 cookie 或测试账号。夹具中的数据是展示用样例，
> 不是生产统计，也不代表任何真实患者记录。
