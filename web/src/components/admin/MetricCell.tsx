import type { ReactElement, ReactNode } from "react"

/**
 * 读数轨的一格：标签 + 等宽大数 + 一行口径。
 *
 * <p>读数轨（`.admin-metric-strip`）是一条轨，不是四张卡：左缘一枚管理紫短柱
 * 标出主指标，格子之间用发丝竖线分栏——密度高于卡片阵列，扫读路径也只有一条。
 *
 * <p>仪表盘与数据分析页共用这一个实现：两页的读数轨必须长得一模一样，
 * 各自抄一份迟早会走样（字号、间距、脚注高度一漂移就会读成两套系统）。
 */
export function MetricCell(props: {
  label: string
  value: string
  foot?: ReactNode
  /** 主指标：左缘加一枚管理紫短柱 */
  primary?: boolean
  /** 打开指标口径抽屉；不传则不渲染入口（仪表盘不用这一层）。 */
  onInspect?: () => void
}): ReactElement {
  return (
    <div className={`admin-metric-card${props.primary ? " admin-metric-card--primary" : ""}`}>
      <span className="admin-metric-card__label">
        <span>{props.label}</span>
        {props.onInspect ? (
          <button
            type="button"
            className="admin-metric-card__inspect"
            onClick={props.onInspect}
            aria-label={`查看「${props.label}」的口径`}
          >
            查看口径
          </button>
        ) : null}
      </span>
      <span className="admin-metric-card__value">{props.value}</span>
      <span className="admin-metric-card__foot">{props.foot}</span>
    </div>
  )
}

export default MetricCell
