import type { ReactElement, ReactNode } from "react"

import type { AnalyticsModelVersion, AnalyticsReviewerStat } from "@/lib/adminApi"
import {
  formatConfidence,
  formatCount,
  formatMinutes,
  formatRate,
  reviewerLabel,
  type QualityIssue,
} from "@/lib/analyticsDomain"

/**
 * 可横向滚动的表格外壳。
 *
 * <p>窄屏下表格保持最小列宽（不压缩读数），溢出由外壳滚动承接。
 * 外壳可聚焦，键盘用户也能滚动；`aria-label` 让读屏知道这里能横向查看。
 */
function TableWrap(props: { label: string; children: ReactNode }): ReactElement {
  return (
    <div
      className="admin-analytics__tablewrap"
      role="region"
      aria-label={`${props.label}（可横向滚动）`}
      tabIndex={0}
    >
      {props.children}
    </div>
  )
}

/** 模型版本对照表。平均置信度只在有效样本内计算，必须同时读样本数。 */
export function ModelVersionsTable(props: {
  versions: AnalyticsModelVersion[]
  onInspect: () => void
}): ReactElement {
  if (props.versions.length === 0) {
    return <p className="admin-analytics__empty">该时间范围内没有模型运行记录。</p>
  }
  return (
    <TableWrap label="模型版本对照表">
      <table className="admin-analytics__table num" aria-label="模型版本对照表">
        <thead>
          <tr>
            <th scope="col">模型版本</th>
            <th scope="col" className="admin-analytics__th--right">判读</th>
            <th scope="col" className="admin-analytics__th--right">拒识</th>
            <th scope="col" className="admin-analytics__th--right">拒识率</th>
            <th scope="col" className="admin-analytics__th--right">置信度样本</th>
            <th scope="col" className="admin-analytics__th--right">平均置信度</th>
            <th scope="col" className="admin-analytics__th--right">量纲歧义</th>
          </tr>
        </thead>
        <tbody>
          {props.versions.map((version) => (
            <tr key={version.modelVersion}>
              <th scope="row" className="admin-analytics__td--label">
                {version.modelVersion === "UNRECORDED" ? "未记录版本" : version.modelVersion}
              </th>
              <td className="admin-analytics__td--right">{formatCount(version.count)}</td>
              <td className="admin-analytics__td--right">{formatCount(version.rejected)}</td>
              <td className="admin-analytics__td--right">{formatRate(version.rejectionRate)}</td>
              <td className="admin-analytics__td--right">
                {formatCount(version.confidenceSamples)}
              </td>
              <td className="admin-analytics__td--right">
                {formatConfidence(version.avgConfidence)}
              </td>
              <td className="admin-analytics__td--right">
                {formatCount(version.ambiguousConfidence)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <button type="button" className="admin-analytics__inspect" onClick={props.onInspect}>
        查看口径
      </button>
    </TableWrap>
  )
}

/** 复核人工作量。历史数据；名称缺失时用 id 兜底，不编名字。 */
export function ReviewersTable(props: {
  reviewers: AnalyticsReviewerStat[]
  onInspect: () => void
}): ReactElement {
  if (props.reviewers.length === 0) {
    return <p className="admin-analytics__empty">该时间范围内没有复核人工作量记录。</p>
  }
  return (
    <TableWrap label="复核人工作量表">
      <table className="admin-analytics__table num" aria-label="复核人工作量表">
        <thead>
          <tr>
            <th scope="col">复核人</th>
            <th scope="col" className="admin-analytics__th--right">完成复核</th>
            <th scope="col" className="admin-analytics__th--right">其中改写</th>
            <th scope="col" className="admin-analytics__th--right">平均时延</th>
          </tr>
        </thead>
        <tbody>
          {props.reviewers.map((reviewer, index) => (
            <tr key={`${reviewer.reviewerId ?? "none"}-${index}`}>
              <th scope="row" className="admin-analytics__td--label">
                {reviewerLabel(reviewer)}
              </th>
              <td className="admin-analytics__td--right">{formatCount(reviewer.reviewed)}</td>
              <td className="admin-analytics__td--right">{formatCount(reviewer.overridden)}</td>
              <td className="admin-analytics__td--right">{formatMinutes(reviewer.avgMinutes)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <button type="button" className="admin-analytics__inspect" onClick={props.onInspect}>
        查看口径
      </button>
    </TableWrap>
  )
}

/** 数据质量清单：逐项计数，不做跨类别求和。 */
export function QualityIssuesTable(props: {
  issues: QualityIssue[]
  onInspect: (metricId: string) => void
}): ReactElement {
  if (props.issues.length === 0) {
    return (
      <p className="admin-analytics__empty">
        本窗口内未发现契约外、量纲未知或时间缺失的记录；各项计数均为 0。
      </p>
    )
  }
  return (
    <TableWrap label="数据质量清单">
      <table className="admin-analytics__table admin-analytics__table--issues" aria-label="数据质量清单">
        <thead>
          <tr>
            <th scope="col">问题</th>
            <th scope="col" className="admin-analytics__th--right">计数</th>
            <th scope="col">说明</th>
            <th scope="col" className="admin-analytics__th--right">口径</th>
          </tr>
        </thead>
        <tbody>
          {props.issues.map((issue) => (
            <tr key={issue.key}>
              <th scope="row" className="admin-analytics__td--label">
                {issue.title}
              </th>
              <td className="admin-analytics__td--right num">{formatCount(issue.count)}</td>
              <td className="admin-analytics__td--detail">{issue.detail}</td>
              <td className="admin-analytics__td--right">
                <button
                  type="button"
                  className="admin-analytics__inspect"
                  onClick={() => props.onInspect(issue.metricId)}
                >
                  查看口径
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </TableWrap>
  )
}
