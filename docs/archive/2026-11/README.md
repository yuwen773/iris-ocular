# 归档 · 2026-11

本目录存放**一次性证据**：某个批次 / 某个 PR 的评审报告、审计材料、状态截图。
它们记录的是**当时的结论**，不是当前事实。

| 子目录 | 内容 | 来源 |
|---|---|---|
| `reviews/` | 评审报告（E-09 首屏选型、R3 Roundtable 收敛、R5 就绪、Roundtable 3.0 集成）+ 对比图 | PR #87 / #93 / #96 / E-09 |
| `system-states/`、`system-states-r2/` | 系统状态治理的快照与 before/after 证据 | 状态治理批次 |
| `codex-audit-2026-11-19/` | Codex 两轮独立审查（NO_GO）报告、探针源码、surefire/CI 原始记录、旧产物启动日志 | RC v1.1.0 门禁 |
| `handoff-2026-11-16/` | 多 Agent 交接文档、逐条对账规格、ready 信号、r13 远端验收记录 | 多 Agent 批次 |

## 视觉证据已裁剪

2026-11-22 起，一次性证据里的截图按「每个主题保留 ≤12 张代表图（优先 1440 宽，light/dark 各一）」裁剪。
被裁剪的文件逐目录记录在同级 `inventory.csv`：文件名、字节数、md5、裁剪理由、取回路径。

**原始字节没有丢**——仍在 git 历史里，按 `inventory.csv` 的 `retrieve_from` 列取回：

```
git show <commit>:<path> > <file>

# 例
git show 280f45c9:docs/reviews/assets/iris-hero-comparison/compare-all-desktop.png > compare-all-desktop.png
```

同一规则也用于 `docs/design/admin-*`、`docs/assets/iris2/r2|r25` 这些仍留在原位的对比矩阵目录。

## 规则

- 归档**只增不改**。要纠正结论，写新文档并在正文里指明取代关系，不要回改归档。
- 不要把归档当契约：当前真源是代码、迁移，以及 `docs/` 下的活文档。
- 新增归档请放到 `archive/<YYYY-MM>/<主题>/`，并在本文件顶部表格登记一行。
