import { describe, expect, it } from "vitest"
import { readStreamError } from "./api"

describe("readStreamError", () => {
  it("后端给的 {code,msg} 原样读出", () => {
    const e = readStreamError(JSON.stringify({ code: 50301, msg: "模型服务鉴权失败" }))
    expect(e.code).toBe(50301)
    expect(e.message).toBe("模型服务鉴权失败")
  })

  it("旧服务只给一句人话时按服务端异常处理，不是网络中断", () => {
    const e = readStreamError("系统繁忙，请稍后重试")
    expect(e.code).toBe(50000)
    expect(e.message).toBe("系统繁忙，请稍后重试")
  })

  it("空载荷给一句兜底", () => {
    const e = readStreamError("")
    expect(e.code).toBe(50000)
    expect(e.message).toBe("对话服务出错")
  })

  it("像 JSON 但不是 JSON 的内容不炸", () => {
    const e = readStreamError("{不是 JSON")
    expect(e.code).toBe(50000)
    expect(e.message).toBe("{不是 JSON")
  })

  it("没有码的 JSON 也退回服务端异常", () => {
    const e = readStreamError(JSON.stringify({ msg: "模型服务鉴权失败" }))
    expect(e.code).toBe(50000)
    expect(e.message).toContain("模型服务鉴权失败")
  })
})
