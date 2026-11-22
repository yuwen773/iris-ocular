import assert from "node:assert/strict"
import { execFileSync } from "node:child_process"
import { mkdir, readFile, writeFile } from "node:fs/promises"
import { createRequire } from "node:module"
import { join } from "node:path"

const root = "D:/Work/code/iris-codex-audit-round2"
const candidate = join(root, "checkout")
const out = join(root, "standards-evidence", "candidate-visual")
const sha = execFileSync("git", ["rev-parse", "HEAD"], { cwd: candidate, encoding: "utf8" }).trim()
assert.equal(sha, "03b14efd8f9bcc8d6326a104396d8b68a30d5e8b")
const require = createRequire(join(candidate, "web", "package.json"))
const { chromium } = require("@playwright/test")
const base = "http://127.0.0.1:45299"
const browser = await chromium.launch({ args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader"] })
const shots = await readFile(join(out, "manifest.json"), "utf8").then(raw => JSON.parse(raw).shots).catch(() => [])
const user = { id: "7", userAccount: "audit-fixture", userName: "验收合成账号", userRole: "admin", userAvatar: "", userProfile: "" }
const row = { id: "410001", username: "合成病例", result: "cataract", severity: "mild", confidence: 0.82, createTime: "2026-11-19T00:00:00+08:00" }
const detail = {
  ...row,
  prediction: "cataract",
  humanReviewStatus: "PENDING_AI",
  modelVersion: "fixture-model-v1",
  preprocessVersion: "fixture-pre-v1",
  inferencePolicyVersion: "fixture-harness-v1",
  source: {
    recordSource: "COLLABORATION_DRAFT",
    authorizationState: "APPROVED",
    authorizationDetailsRestricted: true,
    draftReviewState: "APPROVED",
    imageArchivalState: "MISSING",
    reviewerRole: "doctor",
  },
}
function fixture(path) {
  if (path === "/api/user/get/login") return user
  if (path === "/api/diagnosis/list/page/vo") return { records: [row], total: 1, size: 10, current: 1, pages: 1 }
  if (path === "/api/diagnosis/get/410001") return detail
  return null
}
await mkdir(out, { recursive: true })
try {
  for (const theme of process.env.VISUAL_THEME ? [process.env.VISUAL_THEME] : ["light", "dark"]) {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 })
    const errors = []
    page.on("pageerror", error => errors.push(error.message))
    await page.addInitScript(theme => localStorage.setItem("theme", theme), theme)
    await page.route("**/*", async route => {
      const url = new URL(route.request().url())
      if (url.origin !== base) return route.abort()
      if (!url.pathname.startsWith("/api/")) return route.continue()
      return route.fulfill({ json: { code: 0, data: fixture(url.pathname), msg: "" } })
    })
    const state = async () => page.evaluate(() => ({
      width: innerWidth, height: innerHeight, dpr: devicePixelRatio,
      dark: document.documentElement.classList.contains("dark"),
      overflow: document.documentElement.scrollWidth - innerWidth,
      viteDevClient: !!document.querySelector("script[src='/@vite/client']"),
    }))
    for (const width of process.env.VISUAL_HOME_WIDTH ? [Number(process.env.VISUAL_HOME_WIDTH)] : [390, 820, 1440, 1920]) {
      await page.setViewportSize({ width, height: 900 })
      await page.goto(base + "/", { waitUntil: "load" })
      await page.locator(".hero-fusion__prism").waitFor({ state: "visible", timeout: 60000 })
      await page.evaluate(() => document.fonts.ready)
      const visual = await state()
      assert.equal(visual.dark, theme === "dark")
      assert.equal(visual.width, width)
      assert.ok(visual.overflow <= 1)
      const hero = await page.evaluate(() => ({
        breakpoint: document.querySelector(".hero-fusion__stage")?.getAttribute("data-breakpoint"),
        canvas: !!document.querySelector(".hero-fusion__canvas"),
        staticWash: !!document.querySelector(".hero-fusion__static-wash"),
        hints: [...document.querySelectorAll(".landing-hero__cta a")].map(a => a.textContent.trim()),
      }))
      const file = "home-" + theme + "-" + width + ".png"
      await page.screenshot({ path: join(out, file), animations: "disabled" })
      const shellFile = "home-hero-" + theme + "-" + width + ".png"
      await page.locator(".landing-hero__shell").screenshot({ path: join(out, shellFile), animations: "disabled" })
      shots.push({ file, shellFile, visual, hero, route: "/" })
    }
    for (const width of [390, 768, 1440, 1920]) {
      await page.setViewportSize({ width, height: 900 })
      await page.goto(base + "/admin/diagnosis", { waitUntil: "load" })
      await page.getByRole("button", { name: "详情", exact: true }).click()
      const dialog = page.getByRole("dialog", { name: "判读详情" })
      const provenance = dialog.getByRole("heading", { name: /PROVENANCE/ })
      await provenance.scrollIntoViewIfNeeded()
      await dialog.getByText("患者已授权保存 · 详情按最小权限不展示", { exact: true }).waitFor()
      await page.evaluate(() => document.fonts.ready)
      const visual = await state()
      assert.equal(visual.dark, theme === "dark")
      assert.ok(visual.overflow <= 1)
      const file = "admin-provenance-" + theme + "-" + width + ".png"
      await page.screenshot({ path: join(out, file), animations: "disabled" })
      shots.push({ file, visual, route: "/admin/diagnosis", fixture: "approved authorization without receipt details" })
    }
    assert.deepEqual(errors, [])
    await page.close()
  }
} finally {
  await browser.close()
  await writeFile(join(out, "manifest.json"), JSON.stringify({ sha, base, mode: "Vite dev with fixture API", chromium: browser.version(), shots }, null, 2))
}
