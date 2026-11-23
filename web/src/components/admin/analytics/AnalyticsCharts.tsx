import type { ReactElement, ReactNode } from "react"
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  LabelList,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts"

import {
  ChartView,
  VIZ,
  VIZ_AXIS_TICK,
  VizTooltip,
  vizSeriesColor,
} from "@/components/admin/charts/ChartFrame"
import type { AnalyticsStatusStat } from "@/lib/adminApi"
import { useMediaQuery } from "@/lib/useMediaQuery"
import {
  formatCount,
  formatRate,
  qualityScoreLabel,
  type ReasonRow,
} from "@/lib/analyticsDomain"

/** 口径入口：所有图表共用一个「查看口径」按钮，样式由页面 CSS 提供。 */
function InspectButton(props: { onInspect?: () => void }): ReactElement | null {
  if (!props.onInspect) return null
  return (
    <button type="button" className="admin-analytics__inspect" onClick={props.onInspect}>
      查看口径
    </button>
  )
}

/** 图卡头右侧：口径说明 + 口径入口。 */
function headExtra(text: string, onInspect?: () => void): ReactNode {
  return (
    <>
      {text}
      <InspectButton onInspect={onInspect} />
    </>
  )
}

/**
 * 原因构成（横条）。
 *
 * <p>占比用后端给定分母算出的 `share`；分母不可用时显示「—」，
 * 不在前端按行合计重算——两个原因图的集合与分母都不同。
 */
export function ReasonBreakdown(props: {
  title: string
  ariaLabel: string
  rows: ReasonRow[]
  denominatorLabel: string
  note?: ReactNode
  emptyText: string
  onInspect?: () => void
}): ReactElement {
  const { title, ariaLabel, rows, denominatorLabel, note, emptyText, onInspect } = props
  const height = Math.max(rows.length * 34 + 12, 64)

  return (
    <ChartView
      title={title}
      ariaLabel={ariaLabel}
      labelHeader="原因"
      extraHeader="占比"
      headExtra={headExtra(denominatorLabel, onInspect)}
      emptyText={emptyText}
      note={note}
      rows={rows.map((row) => ({
        label: row.label,
        value: formatCount(row.count),
        extra: formatRate(row.share),
      }))}
      figure={
        <div className="admin-viz__chart">
          <ResponsiveContainer width="100%" height={height}>
            <BarChart
              data={rows}
              layout="vertical"
              margin={{ top: 4, right: 64, bottom: 4, left: 0 }}
              barCategoryGap={12}
            >
              <CartesianGrid horizontal={false} stroke={VIZ.rule} />
              <XAxis
                type="number"
                tick={VIZ_AXIS_TICK}
                axisLine={{ stroke: VIZ.rule }}
                tickLine={false}
                allowDecimals={false}
              />
              <YAxis
                type="category"
                dataKey="label"
                width={140}
                tick={{ fill: "var(--fg-2)", fontSize: 12.5 }}
                axisLine={false}
                tickLine={false}
              />
              <Tooltip
                content={<VizTooltip unit="条" />}
                cursor={{ fill: "var(--surface-2)", fillOpacity: 0.6 }}
              />
              <Bar dataKey="count" name="计数" radius={[0, 4, 4, 0]} isAnimationActive={false}>
                {rows.map((row, index) => (
                  <Cell key={row.reason} fill={vizSeriesColor(index)} />
                ))}
                <LabelList
                  dataKey="count"
                  position="right"
                  offset={8}
                  className="admin-viz__bar-value"
                  formatter={(label: unknown) => formatCount(Number(label))}
                />
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      }
    />
  )
}

/** 置信度十档直方图；空桶保留，横轴连续。 */
export function ConfidenceHistogram(props: {
  rows: { bucket: string; count: number }[]
  emptyText: string
  onInspect?: () => void
  note?: ReactNode
}): ReactElement {
  const { rows, emptyText, onInspect, note } = props
  // 390px 档：10 个区间标签会互相压字；隔一个显示，读数表仍列全量。
  const compact = useMediaQuery("(max-width: 640px)")
  const hasSamples = rows.some((row) => row.count > 0)
  const tableRows = hasSamples
    ? rows.map((row) => ({ label: row.bucket, value: formatCount(row.count) }))
    : []

  return (
    <ChartView
      title="置信度分布"
      ariaLabel="置信度十档直方图"
      labelHeader="置信度区间"
      headExtra={headExtra("0–100 量纲 · 10 桶", onInspect)}
      emptyText={emptyText}
      note={note}
      rows={tableRows}
      figure={
        <div className="admin-viz__chart">
          <ResponsiveContainer width="100%" height={220}>
            <BarChart data={rows} margin={{ top: 8, right: 8, bottom: 4, left: 0 }}>
              <CartesianGrid vertical={false} stroke={VIZ.rule} />
              <XAxis
                dataKey="bucket"
                tick={{ ...VIZ_AXIS_TICK, fontSize: 10 }}
                axisLine={{ stroke: VIZ.rule }}
                tickLine={false}
                interval={compact ? 1 : 0}
              />
              <YAxis
                tick={VIZ_AXIS_TICK}
                axisLine={false}
                tickLine={false}
                allowDecimals={false}
                width={40}
              />
              <Tooltip
                content={<VizTooltip unit="条" />}
                cursor={{ fill: "var(--surface-2)", fillOpacity: 0.4 }}
              />
              <Bar
                dataKey="count"
                name="记录数"
                fill={VIZ.accent}
                radius={[3, 3, 0, 0]}
                isAnimationActive={false}
              />
            </BarChart>
          </ResponsiveContainer>
        </div>
      }
    />
  )
}

/** 质量五档（精确契约值）；越界与未记录不进桶，由页面单独陈述。 */
export function QualityBuckets(props: {
  rows: { score: number; count: number }[]
  emptyText: string
  note?: ReactNode
  onInspect?: () => void
}): ReactElement {
  const { rows, emptyText, note, onInspect } = props
  // 五档标签较长，窄屏同样隔一个显示，避免互相压字。
  const compact = useMediaQuery("(max-width: 640px)")
  const total = rows.reduce((sum, row) => sum + row.count, 0)
  const data = rows.map((row) => ({ ...row, label: qualityScoreLabel(row.score) }))

  return (
    <ChartView
      title="图像质量分档"
      ariaLabel="图像质量五档分布"
      labelHeader="质量分"
      headExtra={headExtra("精确档位 0 / 25 / 50 / 75 / 100", onInspect)}
      emptyText={emptyText}
      note={note}
      rows={total > 0 ? data.map((row) => ({ label: row.label, value: formatCount(row.count) })) : []}
      figure={
        <div className="admin-viz__chart">
          <ResponsiveContainer width="100%" height={220}>
            <BarChart data={data} margin={{ top: 8, right: 8, bottom: 4, left: 0 }}>
              <CartesianGrid vertical={false} stroke={VIZ.rule} />
              <XAxis
                dataKey="label"
                tick={{ ...VIZ_AXIS_TICK, fontSize: 10 }}
                axisLine={{ stroke: VIZ.rule }}
                tickLine={false}
                interval={compact ? 1 : 0}
              />
              <YAxis
                tick={VIZ_AXIS_TICK}
                axisLine={false}
                tickLine={false}
                allowDecimals={false}
                width={40}
              />
              <Tooltip
                content={<VizTooltip unit="条" />}
                cursor={{ fill: "var(--surface-2)", fillOpacity: 0.4 }}
              />
              <Bar dataKey="count" name="记录数" radius={[3, 3, 0, 0]} isAnimationActive={false}>
                {data.map((row, index) => (
                  <Cell key={row.score} fill={vizSeriesColor(index)} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      }
    />
  )
}

/** 质量原因（多标签）：只给次数，不给占比——分母是标签次数，不是记录数。 */
export function QualityReasonList(props: {
  rows: { label: string; count: number }[]
  emptyText: string
  note?: ReactNode
  onInspect?: () => void
}): ReactElement {
  const { rows, emptyText, note, onInspect } = props
  const height = Math.max(rows.length * 34 + 12, 64)

  return (
    <ChartView
      title="质量原因构成"
      ariaLabel="图像质量原因构成"
      labelHeader="原因"
      headExtra={headExtra("多标签 · 按出现次数计", onInspect)}
      emptyText={emptyText}
      note={note}
      rows={rows.map((row) => ({ label: row.label, value: formatCount(row.count) }))}
      figure={
        <div className="admin-viz__chart">
          <ResponsiveContainer width="100%" height={height}>
            <BarChart
              data={rows}
              layout="vertical"
              margin={{ top: 4, right: 48, bottom: 4, left: 0 }}
              barCategoryGap={12}
            >
              <CartesianGrid horizontal={false} stroke={VIZ.rule} />
              <XAxis
                type="number"
                tick={VIZ_AXIS_TICK}
                axisLine={{ stroke: VIZ.rule }}
                tickLine={false}
                allowDecimals={false}
              />
              <YAxis
                type="category"
                dataKey="label"
                width={120}
                tick={{ fill: "var(--fg-2)", fontSize: 12.5 }}
                axisLine={false}
                tickLine={false}
              />
              <Tooltip
                content={<VizTooltip unit="次" />}
                cursor={{ fill: "var(--surface-2)", fillOpacity: 0.6 }}
              />
              <Bar dataKey="count" name="出现次数" radius={[0, 4, 4, 0]} isAnimationActive={false}>
                {rows.map((row, index) => (
                  <Cell key={row.label} fill={vizSeriesColor(index)} />
                ))}
                <LabelList
                  dataKey="count"
                  position="right"
                  offset={8}
                  className="admin-viz__bar-value"
                  formatter={(label: unknown) => formatCount(Number(label))}
                />
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      }
    />
  )
}

/**
 * 状态分布读数卡（OOD / 校准）。
 *
 * <p>这两种状态只有一个或两个取值，画 100% 占比条会把「未启用」读成「全部安全」。
 * 这里只列原始状态与计数，能力说明由页面写在卡下方。
 */
export function StatusDistributionCard(props: {
  title: string
  ariaLabel: string
  rows: AnalyticsStatusStat[]
  labelOf: (status: string) => string
  note?: ReactNode
  emptyText: string
  onInspect?: () => void
}): ReactElement {
  const { title, ariaLabel, rows, labelOf, note, emptyText, onInspect } = props
  return (
    <section className="panel admin-analytics__status" aria-label={ariaLabel}>
      <div className="panel__head">
        <h2 className="panel__title">{title}</h2>
        <span className="panel__actions">
          <InspectButton onInspect={onInspect} />
        </span>
      </div>
      <div className="panel__body">
        {rows.length === 0 ? (
          <p className="admin-analytics__empty">{emptyText}</p>
        ) : (
          <ul className="admin-analytics__status-list">
            {rows.map((row) => (
              <li key={row.status}>
                <span className="admin-analytics__status-name">{labelOf(row.status)}</span>
                <span className="admin-analytics__status-count num">{formatCount(row.count)}</span>
              </li>
            ))}
          </ul>
        )}
        {note ? <p className="admin-analytics__note num">{note}</p> : null}
      </div>
    </section>
  )
}
