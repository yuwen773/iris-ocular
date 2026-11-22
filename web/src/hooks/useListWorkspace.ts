import { useCallback, useState } from "react"

import type { SortState } from "@/components/admin/DataTable"

/** 台账默认每页条数。四个管理页原先各写一份同名常量，含义相同。 */
export const DEFAULT_LIST_PAGE_SIZE = 10

export type ListPageState = { current: number; pageSize: number }

/**
 * 管理端台账的工作区状态：分页、排序、勾选，以及它们之间那几条不变式。
 *
 * <p>这些规则原先在诊断 / 用户 / 知识库 / 资讯四个页面各抄一遍，且只写在
 * 注释里：
 * <ul>
 *   <li>应用新的筛选 → 回第 1 页，并清空勾选（勾选只对当前这一页的结论成立）；</li>
 *   <li>重置筛选 → 回第 1 页、清空勾选、连排序也清掉；</li>
 *   <li>换页 → 清空勾选；**pageSize 变化一律回第 1 页**（DataTable 已传
 *       current:1，这里再钉死一次，不依赖调用方）；</li>
 *   <li>改排序 → 回第 1 页并清空勾选：行顺序变了，旧的勾选语义就不再是同一批行。</li>
 * </ul>
 *
 * <p>本 hook 不管错误提示（`actionError` 属于页面自己的故障展示），也不管请求：
 * 页面拿到 `page` / `sort` 自己组查询键。
 */
export function useListWorkspace(initialPageSize: number = DEFAULT_LIST_PAGE_SIZE) {
  const [page, setPage] = useState<ListPageState>({ current: 1, pageSize: initialPageSize })
  const [sort, setSort] = useState<SortState>(null)
  const [selectedIds, setSelectedIds] = useState<string[]>([])

  const clearSelection = useCallback(() => setSelectedIds([]), [])

  /** 应用新的筛选条件：回第一页并清空勾选。 */
  const applyFilter = useCallback(() => {
    setPage((prev) => ({ ...prev, current: 1 }))
    setSelectedIds([])
  }, [])

  /** 重置全部筛选：回第一页、清空勾选，排序也回到默认。 */
  const resetFilters = useCallback(() => {
    setPage((prev) => ({ ...prev, current: 1 }))
    setSelectedIds([])
    setSort(null)
  }, [])

  /** 换页 / 换每页条数：pageSize 变了必须从第 1 页重新开始。 */
  const changePage = useCallback((next: ListPageState) => {
    setPage((prev) =>
      next.pageSize !== prev.pageSize
        ? { current: 1, pageSize: next.pageSize }
        : { current: next.current, pageSize: next.pageSize },
    )
    setSelectedIds([])
  }, [])

  /** 改排序：回第一页并清空勾选。 */
  const changeSort = useCallback((next: SortState) => {
    setPage((prev) => ({ ...prev, current: 1 }))
    setSelectedIds([])
    setSort(next)
  }, [])

  return {
    page,
    sort,
    selectedIds,
    setSelectedIds,
    clearSelection,
    applyFilter,
    resetFilters,
    changePage,
    changeSort,
  }
}
