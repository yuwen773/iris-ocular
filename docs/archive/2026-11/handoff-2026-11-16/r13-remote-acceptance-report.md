# Iris R1.3 · PR #87 远端验收报告

- 执行时间：2026-11-16（推送 10:07 +0800，CI 完成 10:10 +0800）
- 执行角色：R1.3 远端验收负责人
- 终态：**MERGE_READY（代码侧通过；合并决策留给维护者）**
- PR #87 保持 **Draft**
- 生产医疗协作：**BLOCKED**（未开启、未部署）

---

## 一、推送前复核

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| `git fetch origin` | — | 无新远端提交 |
| 本地最终 SHA | `git rev-parse HEAD` | `6ba65478b8c88d2ef1a019044bbd2232e1842961` |
| 远端 head（推送前） | `git rev-parse origin/feat/…` | `01098ccb8d89afa464904489b6d2b1c268d56e83` |
| 远端是否本地候选的祖先 | `git merge-base --is-ancestor origin/… HEAD` | **是** |
| 未整合的远端提交 | `git rev-list --left-right --count origin/…...HEAD` | `0 6`（落后 0） |
| 工作树 | `git status --porcelain` | 干净 |
| `origin/ui` | `git rev-parse` | `b5f37e5c`，未变 |
| 冻结 tag | `git rev-parse iris-ui-v2.1-freeze-20261115^{commit}` | `80f53f7f`，未移动 |

结论：满足快进推送条件。

## 二、推送

```
git push origin feat/iris21-multiagent-integration
   01098ccb..6ba65478  feat/iris21-multiagent-integration -> feat/iris21-multiagent-integration
```

- **快进**，无 `--force`、无 rebase、无改写历史、无自动合并。
- 推送后 `origin/feat/…` = `6ba65478`，与本地 `0 0`。
- 本次推送的 6 个提交：`d8bc8f84`、`d66e6e22`（Codex）、`ffb6ff4b`、`c94354b8`、`7abfe50c`（Claude 夜间）、`6ba65478`（收敛报告，docs-only）。

## 三、PR #87 更新

| 项 | 变更 |
| --- | --- |
| 标题 | 由 R1.2 改为 R1.3（受理游标竞态修复、Issue #81 冒烟真实化与 UI 冻结保护） |
| 正文 | 全量重写为 R1.3：创建游标修复、#81 fixme 0/0、夜间三提交与独立审查、本地证据与复用边界、9 条 MINOR、冻结 UI 对比、Hosted CI 逐项读数、生产 BLOCKED |
| 状态 | **仍为 Draft**（未转 Ready for Review） |
| 合并方式提示 | 保留"必须 merge commit，不要 squash" |
| 旧证据口径 | R1.2 的 `01098ccb` 20/20 明确标注不作为本 SHA 证据 |

## 四、Hosted CI（SHA `6ba65478`）

运行 [`43790216246`](https://github.com/yuwen773/eyes-diagnosis/actions/runs/43790216246)，created 02:07:19Z，completed 02:10:31Z，`conclusion: success`。

| 作业 | 结果 | 关键读数 |
| --- | --- | --- |
| `backend-unit` | ✅ | 619 跑 / 0 失败 / 0 错误 / 310 opt-in skip；`skip gate passed` |
| `backend-compile` | ✅ | |
| `collaboration-mysql` | ✅ | `CollaborationRunTest` 33/33，0 skip；skip gate passed |
| `graph-execution-mysql` | ✅ | 53/53（`GraphExecutionTest` 37 + spike + POC），0 skip |
| `flyway-mysql (alphabetical)` | ✅ | 空库迁移正序 |
| `flyway-mysql (reversealphabetical)` | ✅ | 空库迁移逆序 |
| `issue17-mysql` | ✅ | |
| `issue18-mysql` | ✅ | |
| `issue19-mysql` | ✅ | |
| `issue20-mysql` | ✅ | |
| `issue21-mysql` | ✅ | |
| `issue22-mysql` | ✅ | |
| `issue23-mysql` | ✅ | |
| `issue24-evaluation` | ✅ | |
| `product-contract` | ✅ | |
| `web` | ✅ | Vitest **637/637（66 文件）**、build、`check:e2e-fixme` 0/0、`check:gate-tests` 6/6 |
| `frontend`（已弃用 Vue 线） | ✅ | lint / test / build |
| `python` | ✅ | |
| `docs-contract` | ✅ | |
| `watchdog` | ✅ | |

**合计 20/20 成功。**

诚实口径：

- R1.2 记录的 `backend-unit` 约 50% 失败率（创建游标竞态）在本 SHA 的**一次**运行中未复现；确定性回归用例已通过，但单次绿色不等于概率性结论。
- 本次未重跑本机后端 Maven 套件；复用依据是源码树与夜间验收树逐字节相同（tree `20f8c424…`）。
- 真实模型提供方、真实患者数据、临床评估：**NOT_RUN**。

## 五、冻结 UI 复核（推送前）

| 文件 | 冻结 tag blob | 本 SHA blob |
| --- | --- | --- |
| `web/src/pages/AgentPage.tsx` | `df36b2de…` | 相同 |
| `web/src/styles/tokens.css` | `d5de71e4…` | 相同 |
| `Landing / Login / Register / News / NewsDetail / RecordSheet` | — | 逐字节相同 |
| `web/src/pages/admin/**` + `components/admin/**`（39 文件） | — | diff 为空 |

PR #86 的门禁（`check:e2e-fixme`、`check:gate-tests`）在 CI 中执行并通过；Issue #81 fixme 预算 `0/0`。

## 六、仍登记的 MINOR（9）

1. `ToolRequestMessage` 工具状态芯片非 flag 门控——待产品决定。
2. fixme 门禁不识别 `test.skip(true)` / `describe.skip`。
3. 记录空态 / 有记录是同一数据依赖测试的两个分支，未自动化。
4. `admission` 消费后未清空（fail-safe）。
5. 受理事务持锁范围略增。
6. `docs/collaboration-runs.md` 未写明 POST 水位契约。
7. `ui-freeze-regression.spec.ts` 是粗门禁，未接 CI。
8. `SameOriginFilter` 不比较 scheme（既有）。
9. 历史 `WP-C` 仍为 UNEXPLAINED。

均不构成合并阻断项。

## 七、MERGE_READY 判定

**代码侧 MERGE_READY = 是。** 依据：

- 最终 SHA 的 Hosted CI 20/20 全绿，证据归属正确（未挪用旧 SHA）。
- 冻结 UI 逐字节未变；PR #86 与 Issue #81 内容未回退。
- 推送为快进，历史可追溯，无重写。
- 生产协作开关保持关闭。

**合并前置条件（由维护者执行）**：

1. 使用 **merge commit**，不要 squash（保留 PR #68 来源链）。
2. PR #87 由 Draft 转 Ready 需维护者确认。
3. 合并不等于启用：`eye-diagnosis.collaboration.*` 与 `VITE_COLLABORATION_ENABLED` 继续保持关闭。
4. MINOR #1（`ToolRequestMessage` 是否 flag 门控）建议在启用协作前做产品决定。

## 八、边界确认

- 未 force push、未 rebase、未自动合并。
- 未合并到 `ui` / `master`，未部署。
- 未开启生产医疗协作，未使用真实患者数据。
- 未修改冻结 tag，未触碰 PR #68。
- PR #87 保持 Draft。
