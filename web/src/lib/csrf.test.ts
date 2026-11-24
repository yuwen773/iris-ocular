// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import { ApiError } from "./api-error"
import { AUTH_EXPIRED_EVENT } from "./http"
import { CSRF_HEADER, csrfRequest, getCsrfToken, resetCsrfToken } from "./csrf"

/** 按 URL 返回不同响应的 fetch 桩，记录每次调用的 URL 与请求头。 */
function stubFetch(handlers: {
  csrf?: () => Response | Promise<Response>
  other?: () => Response | Promise<Response>
}) {
  const calls: { url: string; headers: Record<string, string> }[] = []
  const fetchMock = vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
    const path = String(url)
    calls.push({ url: path, headers: { ...(init?.headers as Record<string, string> | undefined) } })
    if (path.includes("/admin/control-center/csrf")) {
      return handlers.csrf
        ? handlers.csrf()
        : new Response(JSON.stringify({ code: 0, data: { token: "token-1", headerName: CSRF_HEADER } }), {
            status: 200,
            headers: { "Content-Type": "application/json" },
          })
    }
    return handlers.other
      ? handlers.other()
      : new Response(JSON.stringify({ code: 0, data: { ok: true } }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
  })
  vi.stubGlobal("fetch", fetchMock)
  return { fetchMock, calls }
}

beforeEach(() => {
  resetCsrfToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
  resetCsrfToken()
})

describe("getCsrfToken", () => {
  it("会话内只取一次（缓存命中后不再发请求）", async () => {
    const { fetchMock } = stubFetch({})
    expect(await getCsrfToken()).toBe("token-1")
    expect(await getCsrfToken()).toBe("token-1")
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it("并发首屏只发一次请求（单飞）", async () => {
    const { fetchMock } = stubFetch({})
    const [a, b, c] = await Promise.all([getCsrfToken(), getCsrfToken(), getCsrfToken()])
    expect([a, b, c]).toEqual(["token-1", "token-1", "token-1"])
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it("取 token 失败不缓存，下一次调用会真正重试", async () => {
    let attempt = 0
    stubFetch({
      csrf: () => {
        attempt += 1
        return attempt === 1
          ? new Response(JSON.stringify({ code: 40100, msg: "未登录" }), {
              status: 200,
              headers: { "Content-Type": "application/json" },
            })
          : new Response(JSON.stringify({ code: 0, data: { token: "token-2" } }), {
              status: 200,
              headers: { "Content-Type": "application/json" },
            })
      },
    })

    await expect(getCsrfToken()).rejects.toBeInstanceOf(ApiError)
    await expect(getCsrfToken()).resolves.toBe("token-2")
  })

  it("会话失效广播后缓存作废", async () => {
    const { fetchMock } = stubFetch({})
    await getCsrfToken()
    window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT))
    await getCsrfToken()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it("token 缺失视为失败，不返回空串", async () => {
    stubFetch({
      csrf: () =>
        new Response(JSON.stringify({ code: 0, data: {} }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
    })
    await expect(getCsrfToken()).rejects.toBeInstanceOf(ApiError)
  })
})

describe("csrfRequest", () => {
  it("写请求带上 X-CSRF-Token，且只取一次 token", async () => {
    const { fetchMock, calls } = stubFetch({})

    const result = await csrfRequest<{ ok: boolean }>("/admin/control-center/drafts", {
      method: "POST",
      body: JSON.stringify({ schemaVersion: 1, values: {} }),
    })

    expect(result).toEqual({ ok: true })
    const writeCall = calls.find((call) => call.url.includes("/drafts"))
    expect(writeCall?.headers[CSRF_HEADER]).toBe("token-1")
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it("保留调用方已有的其它请求头", async () => {
    const { calls } = stubFetch({})
    await csrfRequest("/admin/control-center/drafts", {
      method: "POST",
      headers: { "X-Custom": "keep" },
      body: "{}",
    })
    const writeCall = calls.find((call) => call.url.includes("/drafts"))
    expect(writeCall?.headers["X-Custom"]).toBe("keep")
    expect(writeCall?.headers[CSRF_HEADER]).toBe("token-1")
  })

  it("服务端判定 CSRF 不匹配时清空缓存，下一次重新取 token", async () => {
    let csrfCalls = 0
    stubFetch({
      csrf: () => {
        csrfCalls += 1
        return new Response(
          JSON.stringify({ code: 0, data: { token: `token-${csrfCalls}` } }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        )
      },
      other: () =>
        new Response(JSON.stringify({ code: 40300, msg: "CSRF_TOKEN_INVALID" }), {
          status: 403,
          headers: { "Content-Type": "application/json" },
        }),
    })

    await expect(
      csrfRequest("/admin/control-center/drafts", { method: "POST", body: "{}" }),
    ).rejects.toMatchObject({ code: 40300 })

    // 缓存已清空 → 下一次会重新取（拿到 token-2）
    await expect(getCsrfToken()).resolves.toBe("token-2")
    expect(csrfCalls).toBe(2)
  })

  it("普通业务错误不清空 token（下次写请求仍复用）", async () => {
    let csrfCalls = 0
    stubFetch({
      csrf: () => {
        csrfCalls += 1
        return new Response(JSON.stringify({ code: 0, data: { token: "token-1" } }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      },
      other: () =>
        new Response(JSON.stringify({ code: 40900, msg: "配置版本冲突，请刷新后重试" }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
    })

    await expect(
      csrfRequest("/admin/control-center/drafts/1", { method: "PUT", body: "{}" }),
    ).rejects.toMatchObject({ code: 40900 })

    await expect(getCsrfToken()).resolves.toBe("token-1")
    expect(csrfCalls).toBe(1)
  })
})
