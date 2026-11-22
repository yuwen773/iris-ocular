import type { ReactElement } from "react"

import { AnalyticsStatusBadge } from "./AnalyticsStatusBadge"
import type { AnalyticsSectionId, AvailabilityItem } from "@/lib/analyticsDomain"

/**
 * 数据可用性摘要。
 *
 * <p>数据分析页的第一屏不是 KPI 复刻，而是「哪些结果可以分析」：每个领域一张
 * 摘要卡，带可信状态、一个主读数与样本口径。点卡片跳到对应分区——它同时承担
 * 导航职责，所以做成按钮而不是装饰卡。
 */
export function AnalyticsAvailability(props: {
  items: AvailabilityItem[]
  onOpen: (section: AnalyticsSectionId) => void
}): ReactElement {
  return (
    <section className="admin-analytics__availability" aria-label="数据可用性">
      <div className="admin-analytics__availability-head">
        <h2 className="admin-analytics__availability-title">数据可用性</h2>
        <p className="admin-analytics__availability-note">
          先确认哪些结果可以分析；状态来自后端，不由页面推断。
        </p>
      </div>
      <ul className="admin-analytics__availability-grid">
        {props.items.map((item) => (
          <li key={`${item.section}-${item.title}`}>
            <button
              type="button"
              className="admin-analytics__availability-card"
              onClick={() => props.onOpen(item.section)}
            >
              <span className="admin-analytics__availability-card-head">
                <span className="admin-analytics__availability-card-title">{item.title}</span>
                <AnalyticsStatusBadge label={item.statusLabel} tone={item.statusTone} />
              </span>
              <span className="admin-analytics__availability-value num">{item.value}</span>
              <span className="admin-analytics__availability-caption">{item.caption}</span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}
