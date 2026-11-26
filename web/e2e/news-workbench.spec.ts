import { expect, test, type Page } from "@playwright/test"
import fixture from "./fixtures/news-workbench.json" with { type: "json" }

const basePath = "/admin/news-workbench"
const issuePath = "/api/admin/news-workbench/issue-preview"
const emptyIssue = { ...fixture.issue, articleCount: 0, entries: [] }

async function stubWorkbench(page: Page) {
  const unexpected: string[] = []
  const issueRequests: string[] = []
  const retryRequests: { path: string; body: string }[] = []
  let issue = fixture.issue
  const responses: Record<string, unknown> = {
    "/api/user/get/login": fixture.session,
    "/api/admin/feedback/pending-count": 0,
    "/api/notifications/unread-count": 0,
    "/api/admin/news-workbench/overview": fixture.overview,
    "/api/admin/news-workbench/candidates": {
      records: [fixture.candidate],
      current: "1",
      size: "10",
      total: "1",
      pages: "1",
    },
    "/api/admin/news-workbench/candidates/7001": fixture.candidate,
    "/api/admin/news-workbench/tasks": {
      records: [fixture.failedTask],
      current: "1",
      size: "10",
      total: "1",
      pages: "1",
    },
    "/api/admin/news-workbench/tasks/task-failed-1/events": [],
  }
  await page.route("**/api/**", async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const isRetryPost =
      request.method() === "POST" &&
      url.pathname === "/api/admin/news-workbench/tasks/task-failed-1/retry"
    if (isRetryPost) {
      retryRequests.push({
        path: url.pathname,
        body: request.postData() ?? "",
      })
      await route.fulfill({
        json: {
          code: 0,
          msg: "",
          data: { ...fixture.failedTask, status: "PENDING", retryable: false },
        },
      })
      return
    }
    if (
      request.method() !== "GET" ||
      (url.pathname !== issuePath && !(url.pathname in responses))
    ) {
      unexpected.push(`${request.method()} ${url.pathname}`)
      await route.abort()
      return
    }
    if (url.pathname === issuePath) issueRequests.push(url.search)
    await route.fulfill({
      json: {
        code: 0,
        msg: "",
        data: url.pathname === issuePath ? issue : responses[url.pathname],
      },
    })
  })
  return {
    issueRequests,
    retryRequests,
    unexpected,
    withdrawAll: () => {
      issue = emptyIssue
    },
  }
}

async function generateIssue(page: Page) {
  await page.getByLabel("开始发布日期").fill(fixture.issue.startOn)
  await page.getByLabel("结束发布日期").fill(fixture.issue.endOn)
  await page.getByRole("button", { name: "生成预览" }).click()
  await expect(page.getByText(/3 篇已发布资讯 · 2 个条目/)).toBeVisible()
}

test("资讯工作台从候选证据进入刊物预览，并重新读取撤回后的结果", async ({
  page,
}) => {
  const backend = await stubWorkbench(page)
  await page.goto(basePath)
  await expect(
    page.getByRole("heading", { name: "资讯引擎工作台" })
  ).toBeVisible()
  await expect(page.getByText("眼科随访研究候选")).toBeVisible()
  await page.getByRole("button", { name: "查看证据" }).click()
  const drawer = page.getByRole("dialog", { name: "精选候选证据审查" })
  await expect(
    drawer.getByText("Ophthalmology follow-up study", { exact: true })
  ).toBeVisible()
  await drawer.getByRole("button", { name: "关闭" }).click()

  await page.getByRole("tab", { name: "刊物预览" }).click()
  await expect(page).toHaveURL(new RegExp(`${basePath}\\?tab=issue$`))
  expect(backend.issueRequests).toEqual([])
  await generateIssue(page)
  const preview = page.getByRole("region", { name: "资讯刊物预览" })
  const group = preview
    .locator("ol > li")
    .filter({ has: page.getByRole("heading", { name: "眼科随访研究进展" }) })
  await expect(group.getByRole("link")).toHaveCount(2)
  await expect(
    group.getByRole("link", { name: "眼科随访研究来源补充" })
  ).toHaveAttribute("href", "/admin/news/9502/edit")
  await expect(preview.getByText("来源标识冲突，已单列")).toBeVisible()
  expect(backend.issueRequests).toEqual([
    "?startOn=2026-10-01&endOn=2026-10-07",
  ])

  backend.withdrawAll()
  await page.getByRole("button", { name: "生成预览" }).click()
  await expect(
    preview.getByText("该日期范围内暂无可追溯的已发布资讯。")
  ).toBeVisible()
  await expect(
    preview.getByRole("link", { name: "眼科随访研究来源补充" })
  ).toHaveCount(0)
  expect(backend.issueRequests).toHaveLength(2)
  expect(backend.unexpected).toEqual([])
})

for (const theme of ["light", "dark"] as const) {
  test(`刊物预览 ${theme} 主题桌面渲染`, async ({ page }, testInfo) => {
    await stubWorkbench(page)
    await page.setViewportSize({ width: 1440, height: 1000 })
    await page.goto(`${basePath}?tab=issue`)
    const label = theme === "light" ? "切换到浅色主题" : "切换到深色主题"
    await page.getByRole("button", { name: label }).click()
    await expect(page.getByRole("button", { name: label })).toHaveAttribute(
      "aria-pressed",
      "true"
    )
    await generateIssue(page)
    const preview = page.getByRole("region", { name: "资讯刊物预览" })
    await expect(preview.getByText("来源标识冲突，已单列")).toBeVisible()
    await expect(preview.getByLabel("开始发布日期")).toHaveCSS(
      "color-scheme",
      theme
    )
    const workspace = await page.getByRole("main").boundingBox()
    const report = await preview.boundingBox()
    expect(report!.x).toBeGreaterThan(workspace!.x)
    const screenshot = testInfo.outputPath(`news-issue-${theme}.png`)
    await page.screenshot({
      path: screenshot,
      fullPage: true,
      animations: "disabled",
    })
    await testInfo.attach(`刊物预览 ${theme}`, {
      path: screenshot,
      contentType: "image/png",
    })
  })
}

test("失败采集窗口显示阻塞原因，并能通过重试入口重新打开", async ({
  page,
}, testInfo) => {
  const backend = await stubWorkbench(page)
  await page.goto(`${basePath}?tab=tasks`)
  await expect(page.getByText("task-failed-1")).toBeVisible()
  await expect(page.getByText(/本窗口已失败（SOURCE_TIMEOUT）/)).toBeVisible()
  await expect(page.getByText(/系统不会自动跳过/)).toBeVisible()

  await page.getByRole("button", { name: "重试" }).click()
  const dialog = page.getByRole("dialog")
  await expect(dialog.getByText(/不会推进信源游标/)).toBeVisible()
  await dialog.getByRole("button", { name: "确认重试" }).click()

  await expect(page.getByText(/已重新打开失败窗口 task-failed-1/)).toBeVisible()
  expect(backend.retryRequests).toHaveLength(1)
  expect(backend.retryRequests[0].path).toBe(
    "/api/admin/news-workbench/tasks/task-failed-1/retry"
  )
  expect(JSON.parse(backend.retryRequests[0].body)).toMatchObject({
    maxAttempts: 3,
  })
  expect(backend.unexpected).toEqual([])

  await page.screenshot({
    path: testInfo.outputPath("news-task-retry.png"),
    fullPage: true,
    animations: "disabled",
  })
})

test("窄屏刊物预览完整显示长 DOI", async ({ page }, testInfo) => {
  await stubWorkbench(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(`${basePath}?tab=issue`)
  await generateIssue(page)
  const preview = page.getByRole("region", { name: "资讯刊物预览" })
  const row = preview.getByText(/europe-pmc · MED:10111/)
  const bounds = await row.boundingBox()
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(390)
  expect(
    await row.evaluate((element) => element.scrollWidth - element.clientWidth)
  ).toBe(0)
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: "instant" }))
  await page.screenshot({
    path: testInfo.outputPath("news-issue-narrow.png"),
    fullPage: true,
    animations: "disabled",
  })
})
