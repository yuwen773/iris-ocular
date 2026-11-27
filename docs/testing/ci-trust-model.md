# CI 信任模型：谁在保护门禁本身

本文记录 G1 对「CI 自身的修改」所建立的防护、**实际核验过的平台能力**，以及明确做不到的部分。
结论先说：**在单维护者 + 现行分支保护下，没有任何自动机制能阻止所有者本人或管理员改门禁；
G1 能做到的是让这类改动永远可见、且必须走一次显式的管理员合并。**

## 1. 平台事实（2026-11-27 核验）

| 事实 | 来源 |
| --- | --- |
| 仓库 public，协作者只有 `yuwen773`（admin），无其他写权限账号 | `GET /repos/.../collaborators` |
| Actions 允许全部 action；工作流默认令牌权限**未显式设置**（API 返回 null） | `GET /repos/.../actions/permissions` |
| 分支保护：22 个必需上下文、`strict=true`、`required_approving_review_count=1`、`require_code_owner_reviews=true`、`dismiss_stale_reviews=true`、`enforce_admins=false`、无 ruleset | `GET /branches/master/protection`、`GET /rulesets` |
| **`pull_request` 跑的是 PR 合并结果**：`GITHUB_SHA` 是 PR 的最后一个合并提交，检出的是 `refs/pull/N/merge` | GitHub 文档 *Events that trigger workflows → pull_request* |
| fork 的 PR：密钥不注入，`GITHUB_TOKEN` **只读** | 同上 |
| **必需检查按名字匹配**：job 不存在则检查一直 Pending，挡住合并；但同名 job 被改成 `echo ok` 仍会报 success | GitHub 分支保护行为 |
| **CODEOWNERS 读基线分支的版本**：「pull requests use the version of `CODEOWNERS` from the base branch」；`require_code_owner_reviews` 只在开启了必需评审时生效 | GitHub 文档 *About code owners* |

## 2. 由此推出的四条结论

1. **工作流不能给自己当信任根**：`verify.yml` 在 PR 里可被改，改后的版本照常执行。任何「本 job 检查自己」的写法都只是礼貌提醒。
2. **CODEOWNERS 可以当信任根的一部分**：因为读的是基线分支，PR 改 `.github/CODEOWNERS` **不能**免除自己的评审要求。把 `.github/**` 与门禁脚本列进去后，改门禁的 PR 必须拿到所有者批准；而单维护者无法自审，所以只能**显式** `--admin` 合并——这一步会进 GitHub 审计日志。
3. **`ci-change-approved` 标签是第二道显式信号**：打标签需要 triage/write 权限，fork 作者做不到。它让 `docs-contract` 直接变红，把「顺手改了一下 CI」挡在合入之前。
4. **两者都不是防篡改**：所有者本人（以及任何 admin）仍可绕过。这里防的是「静默」和「误改」，不是「内部人恶意」。

## 3. G1 实际做了什么

| 控制 | 位置 | 作用 | 不能做什么 |
| --- | --- | --- | --- |
| 门禁路径纳入 CODEOWNERS | `.github/CODEOWNERS` | 改 CI/门禁脚本/`pom.xml` 的 PR 需要所有者评审；基线分支版本不可被 PR 自免 | 所有者自审仍需 `--admin` |
| `ci-change-approved` 标签闸门 | `docs-contract` → `scripts/check_ci_integrity.py` | 敏感路径改动未打标签时 `docs-contract` 红，并列出每个文件与原因 | PR 可删掉这一步；标签由所有者本人打 |
| 显式最小权限 | `verify.yml` / `news-worker.yml` 顶层 `permissions: contents: read` | 不再依赖仓库默认令牌权限；fork PR 的只读语义对同仓分支同样成立 | 不影响已有写权限的人 |
| PR 运行自动取消 | `verify.yml` 顶层 `concurrency` | 同一 PR 的新 push 取消旧 run；master push 不取消，保证每个合并提交都有完整证据 | — |
| 失败信号更可靠 | `docs-contract` 的完整性步骤、`issue24` 的来源校验 | 见 `scripts/check_surefire_artifacts.py`：跨 job 复用报告必须绑定 commit/run/attempt/SHA-256 | 不能证明测试本身正确 |

## 4. 残余风险（不作「已解决」声明）

| 风险 | 现状 | 归口 |
| --- | --- | --- |
| 作者＝评审者 | 单维护者，无法产生独立批准；`--admin` 是唯一路径 | G5 审批模型 |
| 同名 job 空转 | 必需检查按名字匹配，改 job 内容不会被自动发现；只有人看 diff 才能发现 | `ci-change-approved` + CODEOWNERS；G5 `merge-gate` 用「结果 + 用例数」双向核对 |
| 标签自打 | 所有者给自己打标签，语义是「我已看过」而非「他人批准」 | 审计时按 PR 正文的授权句统计 |
| `actions/*` 用 tag 而非 SHA | 供应链风险（上游 tag 可移动） | G3 评估钉 SHA |
| 工作流默认令牌 | 仓库级设置未显式配置；本仓库已用 job 级 `permissions` 覆盖 | 可选：仓库设置改为 read-only |

## 5. 不做什么

- 不注册第二个账号伪造「独立批准」。
- 不在 G1 改分支保护、ruleset、`enforce_admins`、审批数量。
- 不建独立治理平台；上述能力全部由 GitHub 原生机制 + 两个小脚本组成。
- 不声称「加了 CODEOWNERS 就解决了单维护者下的门禁篡改」——§4 的残余风险在 G5 之前一直成立。
