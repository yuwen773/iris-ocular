import { expect, test } from "@playwright/test"

test("资讯工作台读取 Spring/MySQL 的自动窗口、游标和 Worker 领取记录", async ({ page }) => {
  test.skip(process.env.IRIS_SCHEDULE_LIVE_E2E !== "1", "需要已完成自动采集的隔离 Spring 测试栈")
  const account = process.env.IRIS_LIVE_E2E_ACCOUNT ?? ""
  const password = process.env.IRIS_LIVE_E2E_PASSWORD ?? ""
  const source = process.env.IRIS_SCHEDULE_LIVE_E2E_SOURCE ?? "europe-pmc"
  expect(account, "设置测试管理员账号").not.toBe("")
  expect(password, "设置测试管理员密码").not.toBe("")
  await page.setViewportSize({ width: 1440, height: 960 })
  await page.goto("/login")
  await page.locator("#account").fill(account)
  await page.locator("#password").fill(password)
  await page.getByRole("button", { name: "登录" }).click()
  await page.waitForURL((url) => !url.pathname.startsWith("/login"))

  const response = page.waitForResponse((res) => new URL(res.url()).pathname === "/api/admin/news-workbench/scheduling")
  await page.goto("/admin/news-workbench?tab=sources")
  const scheduling = await response
  expect(scheduling.ok()).toBe(true)
  expect(scheduling.headers()["cache-control"]).toContain("no-store")
  const panel = page.getByRole("region", { name: "自动采集调度" })
  const sourceCard = panel.getByRole("article").filter({ has: panel.getByRole("heading", { name: source }) })
  await expect(panel.getByText("调度已开启")).toBeVisible()
  await expect(sourceCard.getByText(/v1 · \d{4}-\d{2}-\d{2}/)).toBeVisible()
  await expect(sourceCard.getByText(/[1-9]\d* 条/)).toBeVisible()
  await expect(panel.getByText(new RegExp(`auto-${source}-`)).last()).toBeVisible()

  await page.getByRole("button", { name: "切换到深色主题" }).click()
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(sourceCard.getByRole("heading", { name: source })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(391)
})
