// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest"
import { cleanup, render, screen } from "@testing-library/react"

import { FilterActions } from "./FilterActions"

afterEach(() => {
  cleanup()
})

type Props = Parameters<typeof FilterActions>[0]

function renderActions(overrides: Partial<Props> = {}) {
  const props: Props = {
    compact: false,
    filtersOpen: false,
    activeFilters: 0,
    pendingCount: 0,
    onToggle: vi.fn(),
    onReset: vi.fn(),
    ...overrides,
  }
  render(<FilterActions {...props} />)
  return props
}

describe("FilterActions 未应用提示", () => {
  it("字段可见时按「查询」生效的说法才成立", () => {
    renderActions({ pendingCount: 2 })

    const hint = screen.getByRole("status")
    expect(hint.textContent).toContain("有 2 项未应用")
    expect(hint.textContent).toContain("按「查询」生效")
    expect(screen.getByRole("button", { name: "查询" })).toBeTruthy()
  })

  it("折叠态不渲染「查询」，提示语改成展开后再生效", () => {
    renderActions({ compact: true, filtersOpen: false, pendingCount: 2 })

    const hint = screen.getByRole("status")
    expect(hint.textContent).toContain("有 2 项未应用")
    expect(hint.textContent).toContain("展开筛选后生效")
    // 折叠态没有可提交的出口，提示语不能指向不存在的按钮
    expect(hint.textContent).not.toContain("按「查询」生效")
    expect(screen.queryByRole("button", { name: "查询" })).toBeNull()
    expect(screen.getByRole("button", { name: /展开筛选/ })).toBeTruthy()
  })

  it("折叠态展开后提示语切回「查询」口径", () => {
    renderActions({ compact: true, filtersOpen: true, pendingCount: 1 })

    const hint = screen.getByRole("status")
    expect(hint.textContent).toContain("按「查询」生效")
    expect(screen.getByRole("button", { name: "查询" })).toBeTruthy()
  })

  it("没有草稿时不显示提示", () => {
    renderActions({ pendingCount: 0 })

    expect(screen.queryByRole("status")).toBeNull()
  })
})
