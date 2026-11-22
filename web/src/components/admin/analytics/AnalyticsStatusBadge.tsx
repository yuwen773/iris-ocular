import type { ReactElement } from "react"

/**
 * 数据状态徽标。
 *
 * <p>颜色不是唯一信息载体：徽标始终带文字。三种语气只表达「能怎么用」：
 * - live：当前窗口可直接分析；
 * - historical：历史数据，不能当当前运营读数；
 * - unknown：无法确认状态（字段缺失 / 未识别），不假定正常。
 */
export function AnalyticsStatusBadge(props: {
  label: string
  tone: "live" | "historical" | "unknown"
}): ReactElement {
  return (
    <span className="admin-analytics__badge" data-tone={props.tone}>
      {props.label}
    </span>
  )
}
