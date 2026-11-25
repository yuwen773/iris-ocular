import { expect, test } from "@playwright/test"

/**
 * 资讯引擎 · 浏览器直连 Spring 的非空数据业务联调。
 *
 * 与 `news-workbench.spec.ts` 的区别：那一条用 `page.route` 造响应，验证的是渲染；
 * 这一条不拦任何请求，页面、nginx、Spring、MySQL 全是真的，走的是一条真实的编辑链路：
 *
 *   候选证据 → 建稿并提交审核 → 发布 → 公开站点读取 → 刊物预览 → 下架 → 公开站点消失
 *
 * 它需要一套已启动的栈和一条预置的精选候选（材料修订 + 成功回执 + 冻结证据快照），
 * 预置脚本与操作手册见 `docs/product/news/wp-07-canary.md`「浏览器业务联调」。
 * 因此默认跳过：没有 `IRIS_LIVE_E2E=1` 时不冒充通过，也不拖慢默认套件。
 */
const enabled = process.env.IRIS_LIVE_E2E === "1"

test.describe("资讯引擎 · 真实 Spring 业务链路", () => {
  test.skip(!enabled, "需要 IRIS_LIVE_E2E=1 与一套已预置候选数据的运行栈")

  test("建稿、审核、发布、公开读取、刊物展示与撤回", async ({ page }) => {
    test.setTimeout(120_000)
    const account = process.env.IRIS_LIVE_E2E_ACCOUNT ?? ""
    const password = process.env.IRIS_LIVE_E2E_PASSWORD ?? ""
    const title = process.env.IRIS_LIVE_E2E_TITLE ?? ""
    const day = process.env.IRIS_LIVE_E2E_DATE ?? new Date().toISOString().slice(0, 10)
    expect(account, "IRIS_LIVE_E2E_ACCOUNT 未设置").not.toBe("")
    expect(password, "IRIS_LIVE_E2E_PASSWORD 未设置").not.toBe("")
    expect(title, "IRIS_LIVE_E2E_TITLE 未设置").not.toBe("")
    await page.setViewportSize({ width: 1440, height: 960 })

    // —— 登录：真实 Cookie 会话 ——
    await page.goto("/login")
    await page.locator("#account").fill(account)
    await page.locator("#password").fill(password)
    await page.getByRole("button", { name: "登录" }).click()
    await page.waitForURL((url) => !url.pathname.startsWith("/login"), { timeout: 20_000 })

    // —— 建稿 + 提交审核：工作台候选证据抽屉 ——
    await page.goto("/admin/news-workbench")
    await page.getByRole("button", { name: "查看证据" }).first().click()
    await page.getByRole("button", { name: "建稿并提交审核" }).click()
    await expect(page.getByText(/已从候选 #\d+ 建稿并提交审核/)).toBeVisible({ timeout: 20_000 })

    // —— 发布：管理端资讯台账行菜单 ——
    await page.goto("/admin/news")
    const rowMenu = page.getByRole("button", { name: `更多资讯操作：${title}` })
    await expect(rowMenu).toBeVisible({ timeout: 20_000 })
    await rowMenu.click()
    await page.getByRole("menuitem", { name: "发布资讯" }).click()
    await expect(page.getByRole("button", { name: `更多资讯操作：${title}` })).toBeVisible()

    // —— 公开读取：站点资讯页（匿名可见，不带管理员 Cookie 之外的任何注入）——
    await page.goto("/news")
    await expect(page.getByText(title, { exact: false }).first()).toBeVisible({ timeout: 20_000 })

    // —— 刊物展示：只列证据可追溯的已发布资讯 ——
    await page.goto("/admin/news-workbench?tab=issue")
    await page.getByLabel("开始发布日期").fill(day)
    await page.getByLabel("结束发布日期").fill(day)
    await page.getByRole("button", { name: "生成预览" }).click()
    const issueSection = page.getByRole("region", { name: "资讯刊物预览" })
    await expect(issueSection.getByText(title, { exact: false }).first()).toBeVisible({
      timeout: 20_000,
    })
    await expect(issueSection.getByRole("status").first()).toContainText("篇已发布资讯")

    // —— 撤回：下架并记录原因 ——
    await page.goto("/admin/news")
    await page.getByRole("button", { name: `更多资讯操作：${title}` }).click()
    await page.getByRole("menuitem", { name: "下架资讯" }).click()
    await page.getByLabel("下架原因（会写进审核留痕）").fill("联调收尾：验证撤回链路后下架。")
    await page.getByRole("button", { name: "确认下架" }).click()
    await expect(page.getByText("已下架", { exact: false }).first()).toBeVisible({ timeout: 20_000 })

    // —— 撤回后在公开站点消失 ——
    await page.goto("/news")
    await expect(page.getByText(title, { exact: false })).toHaveCount(0)
  })
})
