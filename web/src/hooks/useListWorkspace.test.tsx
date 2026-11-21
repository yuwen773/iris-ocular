// @vitest-environment jsdom
import { act, cleanup, renderHook } from "@testing-library/react"
import { afterEach, describe, expect, it } from "vitest"

import { DEFAULT_LIST_PAGE_SIZE, useListWorkspace } from "@/hooks/useListWorkspace"

afterEach(cleanup)

describe("useListWorkspace", () => {
  it("初始为第 1 页、默认每页 10 条、无排序、无勾选", () => {
    const { result } = renderHook(() => useListWorkspace())

    expect(result.current.page).toEqual({ current: 1, pageSize: DEFAULT_LIST_PAGE_SIZE })
    expect(result.current.sort).toBeNull()
    expect(result.current.selectedIds).toEqual([])
  })

  it("应用筛选回到第 1 页并清空勾选，每页条数保持", () => {
    const { result } = renderHook(() => useListWorkspace())

    act(() => result.current.changePage({ current: 3, pageSize: 50 }))
    act(() => result.current.setSelectedIds(["a"]))
    act(() => result.current.applyFilter())

    expect(result.current.page).toEqual({ current: 1, pageSize: 50 })
    expect(result.current.selectedIds).toEqual([])
  })

  it("重置筛选连带清掉排序", () => {
    const { result } = renderHook(() => useListWorkspace())

    act(() => result.current.changeSort({ field: "createdAt", order: "descend" }))
    act(() => result.current.changePage({ current: 2, pageSize: DEFAULT_LIST_PAGE_SIZE }))
    act(() => result.current.setSelectedIds(["a", "b"]))
    act(() => result.current.resetFilters())

    expect(result.current.page.current).toBe(1)
    expect(result.current.sort).toBeNull()
    expect(result.current.selectedIds).toEqual([])
  })

  it("换页只动 current，清空勾选", () => {
    const { result } = renderHook(() => useListWorkspace())

    act(() => result.current.setSelectedIds(["a"]))
    act(() => result.current.changePage({ current: 4, pageSize: DEFAULT_LIST_PAGE_SIZE }))

    expect(result.current.page).toEqual({ current: 4, pageSize: DEFAULT_LIST_PAGE_SIZE })
    expect(result.current.selectedIds).toEqual([])
  })

  it("每页条数变化一律回第 1 页——即使调用方传了别的 current", () => {
    const { result } = renderHook(() => useListWorkspace())

    act(() => result.current.changePage({ current: 4, pageSize: 20 }))
    act(() => result.current.setSelectedIds(["a"]))
    act(() => result.current.changePage({ current: 4, pageSize: 50 }))

    expect(result.current.page).toEqual({ current: 1, pageSize: 50 })
    expect(result.current.selectedIds).toEqual([])
  })

  it("改排序回第 1 页并清空勾选（行顺序变了，旧勾选不再指同一批行）", () => {
    const { result } = renderHook(() => useListWorkspace())

    act(() => result.current.changePage({ current: 5, pageSize: DEFAULT_LIST_PAGE_SIZE }))
    act(() => result.current.setSelectedIds(["a"]))
    act(() => result.current.changeSort({ field: "updatedAt", order: "ascend" }))

    expect(result.current.page.current).toBe(1)
    expect(result.current.sort).toEqual({ field: "updatedAt", order: "ascend" })
    expect(result.current.selectedIds).toEqual([])
  })

  it("clearSelection 只清勾选，不动分页与排序", () => {
    const { result } = renderHook(() => useListWorkspace())

    act(() => result.current.changeSort({ field: "id", order: "ascend" }))
    act(() => result.current.changePage({ current: 2, pageSize: DEFAULT_LIST_PAGE_SIZE }))
    act(() => result.current.setSelectedIds(["a"]))
    act(() => result.current.clearSelection())

    expect(result.current.selectedIds).toEqual([])
    expect(result.current.page.current).toBe(2)
    expect(result.current.sort).toEqual({ field: "id", order: "ascend" })
  })
})
