// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import {
  approvePublishRequest,
  createDraft,
  fetchReleases,
  requestPublish,
  rollbackRelease,
} from "./controlCenterApi"
import { resetCsrfToken } from "./csrf"

/**
 * 发布审批客户端的契约测试（WP-01C）。
 *
 * <p>只验证三件容易悄悄坏掉的事：
 * <ol>
 *   <li>写请求带 CSRF 头，且 token 只取一次（会话内缓存）；</li>
 *   <li>后端把 Long 序列化成字符串，客户端必须转成 number；</li>
 *   <li>发布/回滚体里带齐服务端需要的字段（revision / expectedActiveReleaseId / requestId）。</li>
 * </ol>
 */
type Call = { url: string; init: RequestInit }

function stubFetch(...envelopes: unknown[]) {
  const calls: Call[] = []
  let index = 0
  vi.stubGlobal(
    "fetch",
    vi.fn<typeof fetch>(async (url: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: String(url), init: init ?? {} })
      const body = envelopes[Math.min(index, envelopes.length - 1)]
      index += 1
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      })
    }),
  )
  return calls
}

function body(call: Call): Record<string, unknown> {
  return JSON.parse(String(call.init.body)) as Record<string, unknown>
}

function header(call: Call, name: string): string | undefined {
  const headers = call.init.headers as Record<string, string> | undefined
  return headers?.[name]
}

beforeEach(() => {
  resetCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("requestPublish", () => {
  it("带 CSRF 头、原样提交期望活跃版本，并把 id 归一化成 number", async () => {
    const calls = stubFetch(
      { code: 0, data: { token: "csrf-1" } },
      {
        code: 0,
        data: {
          id: "12",
          status: "PENDING",
          draftRevision: "3",
          payloadDigest: "a".repeat(64),
          validationDigest: "b".repeat(64),
          expectedActiveReleaseId: null,
          note: null,
          requestedBy: "9200001",
          requestedTime: "2026-11-23T00:00:00.000+00:00",
          decidedBy: null,
          decidedTime: null,
          decisionReason: null,
          releaseId: null,
        },
      },
    )

    const approval = await requestPublish(1, {
      revision: 3,
      expectedActiveReleaseId: null,
      note: "首页改版",
      requestId: "pub-1",
    })

    expect(calls).toHaveLength(2)
    expect(calls[0].url).toBe("/api/admin/control-center/csrf")
    expect(calls[1].url).toBe("/api/admin/control-center/drafts/1/publish-requests")
    expect(calls[1].init.method).toBe("POST")
    expect(header(calls[1], "X-CSRF-Token")).toBe("csrf-1")
    expect(body(calls[1])).toMatchObject({
      revision: 3,
      expectedActiveReleaseId: null,
      note: "首页改版",
      requestId: "pub-1",
    })

    expect(approval.id).toBe(12)
    expect(approval.draftRevision).toBe(3)
    expect(approval.requestedBy).toBe(9200001)
    expect(approval.expectedActiveReleaseId).toBeNull()
  })
})

describe("approvePublishRequest", () => {
  it("返回的 release 字段全部归一化", async () => {
    const calls = stubFetch(
      { code: 0, data: { token: "csrf-2" } },
      {
        code: 0,
        data: {
          id: "7",
          releaseVersion: "2",
          schemaVersion: 1,
          digest: "c".repeat(64),
          rollbackFromReleaseId: "4",
          createdBy: "9200001",
          createdTime: "2026-11-23T00:00:00.000+00:00",
          values: { "brand.siteName": "Iris" },
        },
      },
    )

    const release = await approvePublishRequest(12, "appr-1")

    expect(calls[1].url).toBe("/api/admin/control-center/publish-requests/12/approve")
    expect(body(calls[1])).toEqual({ requestId: "appr-1" })
    expect(release.id).toBe(7)
    expect(release.releaseVersion).toBe(2)
    expect(release.rollbackFromReleaseId).toBe(4)
    expect(release.createdBy).toBe(9200001)
    expect(release.values["brand.siteName"]).toBe("Iris")
  })
})

describe("rollbackRelease", () => {
  it("回滚体里带期望活跃版本：服务端靠它拒绝过期回滚", async () => {
    const calls = stubFetch(
      { code: 0, data: { token: "csrf-3" } },
      {
        code: 0,
        data: {
          id: "9",
          releaseVersion: "3",
          schemaVersion: 1,
          digest: "d".repeat(64),
          rollbackFromReleaseId: "7",
          createdBy: "9200001",
          createdTime: null,
          values: {},
        },
      },
    )

    const release = await rollbackRelease(7, {
      expectedActiveReleaseId: 9,
      requestId: "rb-1",
      reason: "回滚",
    })

    expect(calls[1].url).toBe("/api/admin/control-center/releases/7/rollback")
    expect(body(calls[1])).toMatchObject({
      expectedActiveReleaseId: 9,
      requestId: "rb-1",
      reason: "回滚",
    })
    // 回滚产生的是新版本，不是目标版本。
    expect(release.id).toBe(9)
    expect(release.releaseVersion).toBe(3)
    expect(release.rollbackFromReleaseId).toBe(7)
  })
})

describe("fetchReleases", () => {
  it("分页响应里的每条记录都归一化，且不触发 CSRF 请求", async () => {
    const calls = stubFetch({
      code: 0,
      data: {
        total: 1,
        page: 1,
        pageSize: 20,
        records: [
          {
            id: "3",
            releaseVersion: "1",
            schemaVersion: 1,
            digest: "e".repeat(64),
            rollbackFromReleaseId: null,
            createdBy: "9200001",
            createdTime: null,
            values: {},
          },
        ],
      },
    })

    const page = await fetchReleases(1, 20)

    expect(calls).toHaveLength(1)
    expect(calls[0].url).toBe("/api/admin/control-center/releases?page=1&pageSize=20")
    expect(page.total).toBe(1)
    expect(page.records[0].id).toBe(3)
    expect(page.records[0].releaseVersion).toBe(1)
    expect(page.records[0].rollbackFromReleaseId).toBeNull()
  })
})

describe("draft 幂等键", () => {
  const draftEnvelope = {
    code: 0,
    data: {
      id: "1",
      revision: "2",
      status: "DRAFT",
      values: {},
      validationRevision: null,
      validationResult: null,
      updatedBy: "9200001",
      updateTime: null,
    },
  }

  it("调用方没给 requestId 时自动生成一个，且两次调用互不相同", async () => {
    const calls = stubFetch({ code: 0, data: { token: "csrf-4" } }, draftEnvelope, draftEnvelope)

    await createDraft({ "brand.siteName": "Iris" })
    await createDraft({ "brand.siteName": "Iris" })

    expect(calls).toHaveLength(3)
    const first = body(calls[1]).requestId
    const second = body(calls[2]).requestId
    expect(typeof first).toBe("string")
    expect(first as string).toBeTruthy()
    expect(first).not.toBe(second)
  })

  it("显式传入的 requestId 原样使用——重试幂等只能靠它", async () => {
    const calls = stubFetch({ code: 0, data: { token: "csrf-5" } }, draftEnvelope)

    await createDraft({ "brand.siteName": "Iris" }, { requestId: "draft-1" })

    expect(body(calls[1]).requestId).toBe("draft-1")
  })
})
