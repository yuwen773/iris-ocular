import { afterEach, describe, expect, it, vi } from "vitest"

import {
  candidateStatusView,
  evidenceTierLabel,
  formatBpsPercent,
  formatMicrosUsd,
  getWorkbenchOverview,
  listWorkbenchTasks,
  receiptStateView,
  taskStatusView,
} from "./newsWorkbenchApi"

afterEach(() => {
  vi.restoreAllMocks()
})

describe("newsWorkbenchApi", () => {
  it("normalizes Long-serialized strings into numeric counters and preserves snowflake IDs", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes("/admin/news-workbench/overview")) {
        return new Response(
          JSON.stringify({
            code: 0,
            msg: "ok",
            data: {
              sources: { totalSources: 3, enabledSources: 2 },
              tasks: {
                pendingCount: "4",
                leasedCount: "1",
                retryScheduledCount: "2",
                completedCount: "19",
                failedCount: "0",
                expiredLeaseCount: "1",
              },
              materials: { visibleMaterialCount: "42", visibleRevisionCount: "50" },
              candidates: {
                acceptedCount: "8",
                needsReviewCount: "3",
                rejectedCount: "5",
                evidenceViolationCount: "2",
                needsReconciliationCount: "1",
                draftedNewsCount: "4",
              },
              receipts: {
                unknownOutcomeCount: "1",
                inFlightCount: "0",
                staleInFlightCount: "0",
                succeededCount: "10",
              },
              todayBudget: {
                budgetDate: "2026-10-22",
                scope: "GLOBAL",
                maxCalls: 50,
                usedCalls: 10,
                reservedCalls: 1,
                maxTokens: "200000",
                usedTokens: "12000",
                reservedTokens: "4000",
                maxCostMicros: "2000000",
                usedCostMicros: "150000",
                reservedCostMicros: "50000",
                circuitBroken: false,
                circuitReason: null,
                updatedAt: "2026-10-22T12:00:00Z",
              },
              curationEnabled: false,
              latestBenchRun: null,
            },
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        )
      }
      return new Response(
        JSON.stringify({
          code: 0,
          msg: "ok",
          data: {
            records: [
              {
                taskId: "9007199254740999",
                source: "pubmed",
                sourceConfigVersion: "9007199254741234",
                queryText: "myopia",
                windowStartOn: "2026-10-01",
                windowEndBefore: "2026-10-02",
                status: "LEASED",
                generation: "2",
                attemptCount: 1,
                maxAttempts: 3,
                leaseWorkerId: "worker-1",
                leaseExpiresAt: "2026-10-22T12:05:00Z",
                leaseExpired: true,
              },
            ],
            total: "1",
            current: "1",
            size: "10",
            pages: "1",
          },
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      )
    })
    vi.stubGlobal("fetch", fetchMock)

    const overview = await getWorkbenchOverview()
    expect(overview.curationEnabled).toBe(false)
    expect(overview.tasks.completedCount).toBe(19)
    expect(overview.todayBudget?.maxTokens).toBe(200000)
    expect(overview.todayBudget?.usedCostMicros).toBe(150000)

    const tasks = await listWorkbenchTasks({ current: 1, pageSize: 10 })
    expect(tasks.total).toBe(1)
    expect(tasks.records?.[0].taskId).toBe("9007199254740999")
    expect(tasks.records?.[0].sourceConfigVersion).toBe("9007199254741234")
    expect(tasks.records?.[0].generation).toBe(2)
    expect(tasks.records?.[0].leaseExpired).toBe(true)
  })

  it("formats micros, bps, and status tags predictably", () => {
    expect(formatMicrosUsd(12500)).toBe("$0.0125")
    expect(formatMicrosUsd(null)).toBe("—")
    expect(formatBpsPercent(10000)).toBe("100.0%")
    expect(formatBpsPercent(undefined)).toBe("—")

    expect(candidateStatusView("CURATED_ACCEPTED")).toEqual({ label: "已通过", risk: "normal" })
    expect(candidateStatusView("REJECTED_EVIDENCE_VIOLATION").risk).toBe("critical")
    expect(taskStatusView("LEASED").label).toBe("租约中")
    expect(receiptStateView("OUTCOME_UNKNOWN")).toEqual({ label: "结果未知", risk: "critical" })
    expect(evidenceTierLabel("PEER_REVIEWED_ABSTRACT")).toBe("开放许可摘要（同行评审未验证）")
    expect(evidenceTierLabel("LICENSED_ABSTRACT")).toBe("开放许可摘要（同行评审未验证）")
  })
})
