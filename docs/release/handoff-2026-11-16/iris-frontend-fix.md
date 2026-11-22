# Iris 前端修复交接（2026-11-16）

按 `docs/2026-11-16-web-react-code-review.md`（基线 `9d3c5317`，报告已入库）逐条处理。
所有改动在**独立工作树**里完成，**未 push、未开 PR、未合并、未部署**。

工作树：`D:\Work\code\eye-diagnosis\.worktrees\frontend-review-fix`

## 1. 分支与基线

| Branch | HEAD | Parent（基线） | 相对 Parent 提交数 | 变更文件 | 远端 |
|---|---|---|---|---|---|
| `fix/frontend-review-20261116` | `79c1c501` | `ui` @ `cf9e9351` | 6 | 48（+1295 / −394） | 未推送 |

Ancestry 检查（`git merge-base --is-ancestor cf9e9351 HEAD`）：exit 0。

两点须知：

- **P0 与首屏三项不在本分支**：`applyStreamEvent` 就地改写（P0-1）、光幕图 preload、
  字体去重与 latin 子集已在 `ui` 上修完（`b067efe0` / `1a4df433` / `cf9e9351`），
  本分支以它们为基线，报告里对应的「已完成」标注仍然成立。
- 工作树不继承主检出的 `CLAUDE.local.md`；本任务不需要 MySQL/Redis。

## 2. 改了什么

六个提交，按评审条目编号对账。

### 2.1 `eed44852` perf(agent)：流式渲染的重渲染范围

| 条目 | 处理 |
|---|---|
| A-1 | `MessageRow` 加 `memo`；`onPreviewImage` 直接传 `setPreviewUrl`；`AgentPage` 里 5 个内联箭头提成 `useCallback` |
| A-2 | `SessionList`：`groupSessions` 与 `selectedIds` 进 `useMemo`，组件 `memo` 化 |
| A-3 | 两个贴底 effect 合并成一个 `followBottom`，rAF 合帧，卸载时 `cancelAnimationFrame` |
| A-4 | `ReasoningMessage`：`useDeferredValue(msg.content)` 后再进 `useMemo` |

### 2.2 `2d382b9d` perf(admin)：按键与轮询路径上的重复计算

| 条目 | 处理 |
|---|---|
| M-3 | 摘要加 `tab !== "preview"` 早退；字数改用 `useDeferredValue(form.content)` |
| M-P2 | `DataTable` 选中集合改 `Set`、`selectableKeys` 单循环；`newsPlainText` 加带上界的模块级缓存；数据分析页三个派生值补 `useMemo`；三个页面的 `initialReview` 移进 lazy initializer；两处渲染期正则提到模块级 |

### 2.3 `85187ff7` perf(web)：首屏与公共页的渲染热点

| 条目 | 处理 |
|---|---|
| P-1 | `ScrollExpand.applyProgress` 拆成「先全读、再全写」，每帧只强制一次布局 |
| P-2 | `TargetCursor` 的 scroll 命中测试 rAF 合帧（每帧最多一次） |
| P-3 | `DiagnosisPage` 计时器改 ref 直写 DOM，去掉每 100ms 整页重渲染；日志里那一格 `aria-hidden`，live region 文本保持稳定 |
| P-4 | `ElectricBorder`：`settings` 用 `useMemo`，无依赖 effect 改为依赖它 |
| P-P2 | `NewsPage` 行日期/摘要按 `rows` 预计算；`CardSwap` 布局 effect 补依赖；`TargetCursor` 括号与圆点尺寸走 CSS 变量，帧内只写 transform 与圆角；`Prism` 指针事件 rAF 合帧；`NewsArticleView` 封面补固有宽高（1600×900，CSS 仍决定实际尺寸）；四处 reduced-motion 改跟随系统开关；`TargetCursor` 在 `LandingPage` 内按需分包 |

### 2.4 `9da06cda` refactor(web)：设计系统补桥、表格列身份与错误边界

| 条目 | 处理 |
|---|---|
| D-1 | `index.css` 加 `@custom-variant dark (&:where(.dark, .dark *))` |
| D-2 | `@theme inline` 桥接 12 个语义令牌（`--color-fg` / `--color-surface` / `--color-brand-primary` 等）。**整块放在所有 `@import` 之后**——CSS 规定 `@import` 只能出现在其他规则之前，放在中间会被构建期忽略或告警 |
| D-3 | 删掉 `html.dark` 里 `--paper-*` 的翻转与整个 `--paper-*-dark` 家族；`html.dark .is-sheet` 只留 `--surface`。纸面墨色回到 `.is-sheet` 自己的规则，不再靠特异性巧合 |
| D-4 | `DataTable` 给每个 `<td>/<th>` 写 `data-col`（勾选列写 `pick`）；34 处 `:nth-child` 改成 `[data-col="…"]` |
| D-5 | 列宽从行内 `style.width` 改为 `--col-w`，`.admin-dt__head { width: var(--col-w, auto) }`；390 档操作列的 2 条 `!important` 随之删掉 |
| 4.3 | 删 `class-variance-authority` / `cn` / `tw-animate-css` / `@fontsource-variable/geist` 与空壳 `components.json`、零引用的 `src/lib/utils.ts`；根级补 `--bg`；新增 `--paper-danger` 并在 `.is-sheet` 引用；reduced-motion 兜底补 `animation-iteration-count: 1` |
| 4.5 | 游离圆角收进令牌梯（`--radius-panel/-panel-sm/-field/-chip/-chip-sm/-chip-xs/-hair/-hair-sm`），12 处替换，数值不变 |
| 4.5 | 新增**硬编码色棘轮测试**：每个样式文件允许的字面色不超过登记值，新文件默认 0 |
| 4.7 | 在 `tokens.contrast.test.ts` 里加**上下文解析**：自带最小级联解析器（规则块 / 分组选择器 / `@media` / `var()` 链 / 继承链），断言暗主题纸面、纸面嵌暗房、删除红、`--bg` 根级兜底 |
| 3.2 M-P2 | 删 `admin.css` 里被后文完全覆盖的死规则 |
| B-P2 | 路由级 `RouteErrorBoundary`（渲染期抛错不再白屏，换页自动复位，带回归测试）；`/records` 与 `/profile` 的聚合请求与列表并行（新增 `loadServerStats`）；`fetch-event-source` 改按需加载（产物里独立成 2.4 kB chunk）；构建期工具移入 `devDependencies`；品牌蓝取色收进 `lib/brandPalette.ts` |

### 2.5 `368583c3` perf(agent)：输入草稿与故障订阅

| 条目 | 处理 |
|---|---|
| A-P2 | `ChatInput` 打字路径 300ms 节流落盘（prefill / 发送仍同步写，卸载时补写，立刻写之前先撤定时器）；卸载清理改读附件 ref |
| A-P2 | `FaultProvider` 拆成 `push/dismiss` 与 `toasts` 两个 context，订阅投放口的组件不再跟着每条浮条重渲染 |

### 2.6 `79c1c501` docs：评审报告补处理状态

`docs/2026-11-16-web-react-code-review.md` 头部加「处理状态」表：P0 与首屏三项的已修提交、
本批修复所在分支、需要单独一批的剩余项，并指向本交接文档。

## 3. 验证

`fix/frontend-review-20261116` @ `79c1c501`，在 `web/` 下逐项跑：

| 验证 | 结果 |
|---|---|
| `npm run typecheck`（`tsc -b`） | 通过，0 错误 |
| `npm run lint`（ESLint 10） | 通过，0 error / 0 warning |
| `npm test`（Vitest） | 53 个文件 / **463** 用例全绿（基线 436，新增 27） |
| `npm run build` | 通过，1.4 s |
| 入口 JS | 479.08 → **474.84 kB**（156.54 → **154.60 kB** gzip） |
| 入口 CSS | 203.70 → **203.17 kB**（55.38 → 56.28 kB gzip；raw 略降，gzip 因内容变化 +0.9 kB） |
| `dist` 体积 | 8.5 MB（首屏三项修复后即为此值，本分支未回退） |
| 新增 chunk | `TargetCursor-*.js/.css`、`esm-*.js`（SSE 客户端 2.4 kB）——两者都退出入口 |

新增测试：`RouteErrorBoundary.test.tsx`（2）、`hardcoded-colors.test.ts`（18）、
`tokens.contrast.test.ts` 的上下文解析（7）。

**没有做的验证**：没有起浏览器看页面。本分支全部改动按「零视觉 delta」设计
（数值不变、选择器等价、只改读写顺序与订阅范围），但管理端窄屏卡片化、
纸面在暗主题下的观感、品牌首屏滚动这几处仍建议合入前人工过一眼。

## 4. 未做 / 后续

**按冻结规则不该动的**（报告里已标 `[视觉·冻结阻塞]`，登记 Issue #84，不在本分支改）：

- 阴影硬编码 8 处改用 `--admin-shadow*`；品牌取色归并；封面改 `aspect-ratio`；
  编辑页 markdown 预览瘦身（−300 kB gzip，但会改交互）。

**需要单独评审的（本分支未做）**：

| 条目 | 为什么留到下一批 |
|---|---|
| **M-1** 筛选草稿 state 下沉（诊断 / 用户 / 资讯 / 知识库 4 页） | 要抽共用 `<AdminFilterForm>`，改组件边界；与 §4.4 的表格重构同文件，一起做比分开做安全 |
| **M-2** `columns` / `onSortChange` 稳定化（5 页） | 依赖 M-1 把按键路径移出表格组件；单独做只能消掉「每次渲染重建 ColumnDef」，收益有限 |
| **B-1** `@base-ui/react` 首屏拆包（−50.2 kB gzip） | 报告明确要求单独 PR 评审：`AccountMenu` 与 `LandingPage` 两处必须同时改，改的是首屏装配方式 |
| §4.4 ≤900 卡片化块收敛成 `.admin-dt--cards` | 纯结构收敛，没有行为收益；与表格重构同批 |
| §4.5 组件令牌集中（`--auth-fg-*` / `--aa-field-*`） | P2 组织项：定义在浅/暗两个语境块里，搬到 `tokens.css` 要复制语境，级联风险大于收益 |
| §4.5 `:hover` 分批门控（177 条） | 触屏粘滞是体验问题，改动面大，建议独立做 |

**已知遗留**：

- `!important` 还剩 10 条（两个 polish 文件各 5 条，撤 sticky 操作列用）。
  它们要压的是 `admin-redesign.css` 里 `.admin-*__table .admin-dt__cell:last-child`
  （特异性 0,3,0）的 sticky 规则，不能单独删；等 M-1/M-2 那批把 `:last-child`
  也换成 `[data-col="action"]` 后一起清。
- `NewsArticleView` 封面用 1600×900 作为占位比例；真实封面比例不同的话，
  加载完成时会有一次小幅高度修正（不是 0 → 520 的大跳）。

## 5. 继续工作时的操作提醒

- 工作树里的 `web/` 是唯一改动面；不要在主检出上重复做一遍。
- 控制台是 GBK，中文在工具输出里会花；**文件本身是 UTF-8**，别照终端显示改文件。
- 跑 `npm run build` 会写 `web/dist`（gitignored）；`npm test` 会起 53 个 worker，
  在机械硬盘上约 15 s。
- 若要合并：这条分支只动 `web/`，与 `ui` 上的后端/文档提交没有文件交集；
  合并前建议先 `git rebase ui` 再跑一遍 `typecheck` / `test` / `build`。
