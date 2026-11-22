import type { ReactElement } from "react"

import { Drawer } from "@/components/admin/Drawer"
import type { MetricDefinition } from "@/lib/analyticsDomain"

export type MetricLiveRow = { label: string; value: string }

/**
 * 指标口径抽屉。
 *
 * <p>两层信息刻意分开：上半是 B0 注册表里的**静态定义**（公式、分母、时间字段、
 * 排除规则），下半是**本次响应里的真实计数**。定义可以随前端发布更新，计数只能
 * 来自服务端——两者混在一起写，读者就分不清哪个是「规定」哪个是「事实」。
 */
export function MetricDefinitionDrawer(props: {
  open: boolean
  definition: MetricDefinition | null
  liveRows: MetricLiveRow[]
  onClose: () => void
}): ReactElement | null {
  const { open, definition, liveRows, onClose } = props
  if (!definition) return null

  return (
    <Drawer open={open} title={definition.title} onClose={onClose}>
      <article className="admin-analytics__definition" aria-label={`${definition.title} 口径`}>
        <p className="admin-analytics__definition-id num">B0 · {definition.id}</p>
        <p className="admin-analytics__definition-question">{definition.question}</p>

        <dl className="admin-analytics__definition-list">
          <div>
            <dt>公式</dt>
            <dd className="num">{definition.formula}</dd>
          </div>
          {definition.denominator ? (
            <div>
              <dt>分母</dt>
              <dd>{definition.denominator}</dd>
            </div>
          ) : null}
          <div>
            <dt>时间字段</dt>
            <dd>{definition.timeField}</dd>
          </div>
          <div>
            <dt>单位</dt>
            <dd>{definition.unit}</dd>
          </div>
        </dl>

        <section className="admin-analytics__definition-block" aria-label="排除与边界规则">
          <h3 className="admin-analytics__definition-heading">排除与边界规则</h3>
          <ul className="admin-analytics__definition-rules">
            {definition.exclusions.map((rule) => (
              <li key={rule}>{rule}</li>
            ))}
          </ul>
        </section>

        <section className="admin-analytics__definition-block" aria-label="本次窗口计数">
          <h3 className="admin-analytics__definition-heading">本次窗口计数</h3>
          {liveRows.length > 0 ? (
            <dl className="admin-analytics__definition-live">
              {liveRows.map((row) => (
                <div key={row.label}>
                  <dt>{row.label}</dt>
                  <dd className="num">{row.value}</dd>
                </div>
              ))}
            </dl>
          ) : (
            <p className="admin-analytics__definition-muted">当前响应没有可展示的计数。</p>
          )}
        </section>

        <p className="admin-analytics__definition-source">
          口径来自指标注册表；计数来自当前接口响应。历史复核数据不代表当前运营能力。
        </p>
      </article>
    </Drawer>
  )
}
