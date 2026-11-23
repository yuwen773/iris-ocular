import type { ReactElement } from "react"
import { ChevronDown, ChevronUp } from "lucide-react"

/**
 * 台账筛选面板右侧的动作组（用户 / 资讯 / 知识库三页共用）。
 *
 * <p>两套筛选模型并存是刻意的：枚举 chip 点一下即生效，文本与日期字段
 * 要按「查询」才提交（打字不触发请求）。并存的代价是用户打完字看到列表
 * 没变，会把旧结果当成新结果——所以有草稿没提交时，这里必须明说。
 *
 * <p>移动端筛选折叠时，字段不可见，「查询」没有可提交的对象，
 * 因此折叠态只留「展开筛选」；只有已有生效条件时才保留「重置」出口。
 */
export function FilterActions({
  compact,
  filtersOpen,
  activeFilters,
  pendingCount,
  onToggle,
  onReset,
}: {
  compact: boolean
  filtersOpen: boolean
  activeFilters: number
  pendingCount: number
  onToggle: () => void
  onReset: () => void
}): ReactElement {
  const fieldsVisible = !compact || filtersOpen

  return (
    <div className="admin-filter__actions">
      {pendingCount > 0 ? (
        <span className="admin-filter__pending num" role="status">
          有 {pendingCount} 项未应用 · 按「查询」生效
        </span>
      ) : null}
      {compact ? (
        <button
          type="button"
          className="admin-btn admin-btn--ghost admin-btn--sm"
          aria-expanded={filtersOpen}
          onClick={onToggle}
        >
          {filtersOpen ? (
            <ChevronUp size={14} strokeWidth={2} aria-hidden="true" />
          ) : (
            <ChevronDown size={14} strokeWidth={2} aria-hidden="true" />
          )}
          {filtersOpen ? "收起筛选" : "展开筛选"}
        </button>
      ) : null}
      {fieldsVisible ? (
        <button className="admin-btn admin-btn--primary" type="submit">
          查询
        </button>
      ) : null}
      {fieldsVisible || activeFilters > 0 ? (
        <button className="admin-btn admin-btn--ghost" type="button" onClick={onReset}>
          重置
        </button>
      ) : null}
    </div>
  )
}
