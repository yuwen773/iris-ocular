import { useMemo, useState, type ReactElement } from "react"
import { Link, useSearchParams } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { RefreshCw } from "lucide-react"

import "@/styles/admin-analytics.css"

import { AdminPageHead } from "@/components/admin/AdminPageHead"
import { MetricCell } from "@/components/admin/MetricCell"
import { AnalyticsAvailability } from "@/components/admin/analytics/AnalyticsAvailability"
import { AnalyticsStatusBadge } from "@/components/admin/analytics/AnalyticsStatusBadge"
import { MetricDefinitionDrawer } from "@/components/admin/analytics/MetricDefinitionDrawer"
import {
  ConfidenceHistogram,
  QualityBuckets,
  QualityReasonList,
  ReasonBreakdown,
  StatusDistributionCard,
} from "@/components/admin/analytics/AnalyticsCharts"
import {
  ModelVersionsTable,
  QualityIssuesTable,
  ReviewersTable,
} from "@/components/admin/analytics/AnalyticsTables"
import { FaultNote } from "@/components/fault"
import { getAdminAnalytics, type AdminAnalytics } from "@/lib/adminApi"
import {
  ANALYTICS_RANGES,
  ANALYTICS_SECTIONS,
  METRIC_DEFINITIONS,
  buildAvailability,
  buildQualityIssues,
  calibrationStatusLabel,
  confidenceHistogramRows,
  formatConfidence,
  formatCount,
  formatMinutes,
  formatRate,
  metricLiveRows,
  oodCapability,
  oodStatusLabel,
  parseAnalyticsDays,
  parseAnalyticsSection,
  qualityReasonLabel,
  qualityScoreRows,
  reasonRows,
  reviewDataStatus,
  type AnalyticsSectionId,
} from "@/lib/analyticsDomain"
import { describeError } from "@/lib/api"
import { queryKeys } from "@/lib/queryKeys"
import { fault } from "@/lib/faults"

/**
 * 数据分析工作台（B2）。
 *
 * <p>它回答的是「为什么会这样」，不是「现在怎么样」：
 * 判读与拒识归因、历史复核效率、模型与图像质量、数据质量自检。
 * 仪表盘的 KPI、趋势与台账本页一概不复制——两页共用分布图会重新耦合。
 *
 * <p>三条不可让渡的展示纪律：
 * <ul>
 *   <li>后端说 `HISTORICAL_ONLY` 就标历史，页面不自行升格为当前运营；</li>
 *   <li>比率/均值/时延为 null 时显示「—」，不补 0；</li>
 *   <li>契约外取值、量纲未知与时间缺失单独计数，不静默丢弃、不强行归类。</li>
 * </ul>
 */
export function AdminDataAnalysisPage(): ReactElement {
  const [params, setParams] = useSearchParams()
  const days = parseAnalyticsDays(params.get("days"))
  const section = parseAnalyticsSection(params.get("section"))

  const setView = (next: { days?: number; section?: AnalyticsSectionId }) => {
    const nextParams = new URLSearchParams(params)
    nextParams.set("days", String(next.days ?? days))
    nextParams.set("section", next.section ?? section)
    setParams(nextParams)
  }

  const analyticsQuery = useQuery({
    queryKey: queryKeys.admin.analytics(days),
    queryFn: () => getAdminAnalytics(days),
    // 切窗必须立刻失效：旧窗口的读数不能挂在新标签下。
    gcTime: 0,
    staleTime: 0,
  })

  // 第二道守卫：后端回显的窗口与选中值不一致时，当作还没读到。
  const data = analyticsQuery.data?.days === days ? analyticsQuery.data : undefined
  const error = analyticsQuery.error ? describeError(analyticsQuery.error) : null
  const loading = !data && !error

  const [metricId, setMetricId] = useState<string | null>(null)
  const definition = metricId ? METRIC_DEFINITIONS[metricId] ?? null : null
  const liveRows = useMemo(
    () => (data && metricId ? metricLiveRows(metricId, data) : []),
    [data, metricId],
  )

  const reviewStatus = data ? reviewDataStatus(data.review.dataStatus) : null
  const availability = useMemo(() => (data ? buildAvailability(data) : []), [data])
  const issues = useMemo(() => (data ? buildQualityIssues(data) : []), [data])

  // 各分区各有自己的样本前提：复核按复核完成时间统计，窗口内没有新判读时
  // 仍可能有历史复核活动。用 `rejection.total` 一个数把整页判空，会把复核分区
  // 连同数据质量一起藏掉；因此逐分区判空，只有四域全空才给整页空态。
  const hasWindowDiagnoses = data ? data.rejection.total > 0 : false
  const hasReviewActivity = data
    ? data.review.awaitingTotal + data.review.queuedInWindow + data.review.reviewedInWindow > 0
    : false
  const hasModelData = data ? data.model.versions.length > 0 : false
  const hasQualityIssues = issues.length > 0
  const hasAnyAnalysis =
    hasWindowDiagnoses || hasReviewActivity || hasModelData || hasQualityIssues
  const sectionAvailable =
    section === "overview"
      ? hasAnyAnalysis
      : section === "review"
        ? hasReviewActivity
        : section === "model"
          ? hasWindowDiagnoses
          : hasWindowDiagnoses || hasQualityIssues

  return (
    <div className="admin-analytics">
      <AdminPageHead
        code="06"
        title="数据分析"
        eyebrow="ADMIN · DATA ANALYSIS"
        description="审视判读与 AI 质量数据的统计口径、分布和异常。每个读数都带有效样本与可信状态，口径可逐项展开。"
        meta={
          data ? (
            <span className="admin-sample num">
              {data.rangeStart} ~ {data.rangeEnd} · 近 {data.days} 天
            </span>
          ) : null
        }
        actions={
          <div className="admin-range" role="group" aria-label="统计窗口">
            {ANALYTICS_RANGES.map((range) => (
              <button
                key={range}
                type="button"
                className="admin-range__btn"
                aria-pressed={days === range}
                onClick={() => setView({ days: range })}
              >
                近 {range} 天
              </button>
            ))}
          </div>
        }
      />

      <nav className="admin-analytics__tabs" aria-label="分析分区">
        {ANALYTICS_SECTIONS.map((item) => (
          <button
            key={item.id}
            type="button"
            className="admin-analytics__tab"
            aria-pressed={section === item.id}
            onClick={() => setView({ section: item.id })}
          >
            {item.label}
          </button>
        ))}
      </nav>

      {error ? (
        <div className="admin-analytics__fault">
          <FaultNote
            fault={fault({
              severity: "attention",
              kind: "client",
              title: error.text,
              code: error.code,
            })}
          />
          <button
            type="button"
            className="admin-analytics__retry"
            onClick={() => void analyticsQuery.refetch()}
          >
            <RefreshCw size={14} strokeWidth={2} aria-hidden="true" />
            重试
          </button>
        </div>
      ) : null}

      {loading ? (
        <div className="admin-analytics__loading" aria-live="polite" aria-busy="true">
          <span className="admin-analytics__skeleton admin-analytics__skeleton--strip" />
          <span className="admin-analytics__skeleton admin-analytics__skeleton--panel" />
          <span className="admin-analytics__skeleton admin-analytics__skeleton--panel" />
          <span className="admin-analytics__sr-only">统计载入中</span>
        </div>
      ) : null}

      {data && !hasAnyAnalysis ? (
        <section className="panel admin-analytics__empty-panel" aria-label="空窗口说明">
          <div className="panel__head">
            <h2 className="panel__title">该时间范围内暂无可分析记录</h2>
            <span className="panel__note num">
              {data.rangeStart} ~ {data.rangeEnd}
            </span>
          </div>
          <div className="panel__body">
            <p className="admin-analytics__empty-copy">
              窗口内没有判读记录，也没有复核或数据质量异常，因此拒识归因、模型版本、
              置信度与图像质量都没有可统计的样本。把统计窗口拉长，或到诊断管理查看全量记录。
            </p>
            <div className="admin-analytics__empty-actions">
              <Link className="action-row__link" to="/admin/diagnosis">
                打开诊断管理
              </Link>
              <button
                type="button"
                className="admin-analytics__retry"
                onClick={() => setView({ days: 365 })}
              >
                改看近 365 天
              </button>
            </div>
          </div>
        </section>
      ) : null}

      {data && hasAnyAnalysis ? (
        <>
          {section === "overview" ? (
            <OverviewSection
              data={data}
              availability={availability}
              issues={issues}
              onOpenSection={(next) => setView({ section: next })}
            />
          ) : null}

          {section === "review" && hasReviewActivity ? (
            <ReviewSection
              data={data}
              reviewStatus={reviewStatus}
              onInspect={setMetricId}
            />
          ) : null}

          {section === "model" && hasWindowDiagnoses ? (
            <ModelSection data={data} onInspect={setMetricId} />
          ) : null}

          {section === "quality" && (hasWindowDiagnoses || hasQualityIssues) ? (
            <QualitySection issues={issues} onInspect={setMetricId} />
          ) : null}

          {!sectionAvailable ? (
            <section
              className="panel admin-analytics__empty-panel"
              aria-label="分区空态"
              data-empty-section={section}
            >
              <div className="panel__head">
                <h2 className="panel__title">该分区在当前窗口没有可统计样本</h2>
                <span className="panel__note num">
                  {data.rangeStart} ~ {data.rangeEnd}
                </span>
              </div>
              <div className="panel__body">
                <p className="admin-analytics__empty-copy">
                  {section === "review"
                    ? "窗口内没有入队、完成或待复核的记录；历史复核数据仍以复核完成时间为准，换个窗口或到诊断管理核对。"
                    : "窗口内没有判读记录，模型版本与图像质量暂无样本。"}
                </p>
              </div>
            </section>
          ) : null}

          <p className="admin-analytics__scope num" aria-label="统计口径">
            <b>统计窗口</b> {data.rangeStart} ~ {data.rangeEnd}（{data.days} 天）· 判读 / 拒识 /
            模型 / 质量按判读创建时间 · 复核按复核完成时间 · 复核数据状态：
            {reviewStatus?.label ?? "无法确认"}
          </p>
        </>
      ) : null}

      <MetricDefinitionDrawer
        open={metricId !== null}
        definition={definition}
        liveRows={liveRows}
        onClose={() => setMetricId(null)}
      />
    </div>
  )
}

/* ── 分析概览 ─────────────────────────────────────────────── */

function OverviewSection(props: {
  data: AdminAnalytics
  availability: ReturnType<typeof buildAvailability>
  issues: ReturnType<typeof buildQualityIssues>
  onOpenSection: (section: AnalyticsSectionId) => void
}): ReactElement {
  const { data, availability, issues, onOpenSection } = props
  const reviewStatus = reviewDataStatus(data.review.dataStatus)

  return (
    <>
      <AnalyticsAvailability items={availability} onOpen={onOpenSection} />

      <div className="admin-bento">
        <section className="panel span-6" aria-label="统计范围与时间轴">
          <div className="panel__head">
            <h2 className="panel__title">统计范围与时间轴</h2>
            <span className="panel__note">双时间轴 · 不混算</span>
          </div>
          <div className="panel__body admin-analytics__scope-list">
            <dl>
              <div>
                <dt>判读 · 拒识 · 模型 · 质量</dt>
                <dd>按判读创建时间（createTime）筛窗，右开区间含当天。</dd>
              </div>
              <div>
                <dt>复核效率与复核人</dt>
                <dd>按复核完成时间（humanReviewedAt）筛窗；入队量与完成率按当前复核状态。</dd>
              </div>
              <div>
                <dt>复核数据状态</dt>
                <dd>
                  <AnalyticsStatusBadge label={reviewStatus.label} tone={reviewStatus.tone} />
                  <span className="admin-analytics__inline-note">
                    {reviewStatus.tone === "historical"
                      ? "正式复核写入已安全关闭（D-01b），复核读数只用于历史分析。"
                      : "正式复核写入已安全关闭（D-01b）；这批复核读数的统计状态无法确认。"}
                  </span>
                </dd>
              </div>
            </dl>
          </div>
        </section>

        <section className="panel span-6" aria-label="数据质量提示">
          <div className="panel__head">
            <h2 className="panel__title">数据质量提示</h2>
            <span className="panel__note num">{issues.length} 类问题</span>
          </div>
          <div className="panel__body">
            {issues.length === 0 ? (
              <p className="admin-analytics__empty">
                本窗口未发现契约外取值、量纲未知或时间缺失的记录。
              </p>
            ) : (
              <ul className="admin-analytics__issue-brief">
                {issues.slice(0, 4).map((issue) => (
                  <li key={issue.key}>
                    <span>{issue.title}</span>
                    <span className="num">{formatCount(issue.count)}</span>
                  </li>
                ))}
              </ul>
            )}
            <button
              type="button"
              className="admin-analytics__inspect"
              onClick={() => onOpenSection("quality")}
            >
              查看数据质量
            </button>
          </div>
        </section>
      </div>
    </>
  )
}

/* ── 判读与复核 ───────────────────────────────────────────── */

function ReviewSection(props: {
  data: AdminAnalytics
  reviewStatus: ReturnType<typeof reviewDataStatus> | null
  onInspect: (id: string) => void
}): ReactElement {
  const { data, reviewStatus, onInspect } = props
  const { review, rejection } = data

  const rejectionRows = reasonRows(rejection.reasons)
  const queueRows = reasonRows(review.queueReasons)

  return (
    <>
      {reviewStatus ? (
        <div className="admin-analytics__notice" role="note">
          <AnalyticsStatusBadge label={reviewStatus.label} tone={reviewStatus.tone} />
          <p>
            {reviewStatus.tone === "historical"
              ? "历史复核数据 · 当前复核写入已安全关闭。以下结果用于历史记录分析，不代表当前运营复核能力。"
              : "无法确认这批复核数据的统计状态 · 当前复核写入已安全关闭。以下结果按响应原样展示，不能当作当前运营复核能力。"}
          </p>
        </div>
      ) : null}

      <section className="admin-metric-strip" aria-label="复核效率">
        <MetricCell
          label="复核完成率"
          value={formatRate(review.completionRate)}
          primary
          onInspect={() => onInspect("A-08")}
          foot={
            <span className="admin-metric-card__delta num">
              入队 {formatCount(review.queuedInWindow)} · 完成{" "}
              {formatCount(review.completedInWindow)}
            </span>
          }
        />
        <MetricCell
          label="复核改写率"
          value={formatRate(review.overrideRate)}
          onInspect={() => onInspect("A-09")}
          foot={
            <span className="admin-metric-card__delta num">
              已复核 {formatCount(review.reviewedInWindow)} · 改写{" "}
              {formatCount(review.overriddenInWindow)}
            </span>
          }
        />
        <MetricCell
          label="复核时延 · P50"
          value={formatMinutes(review.p50Minutes)}
          onInspect={() => onInspect("A-10")}
          foot={
            <span className="admin-metric-card__delta num">
              P90 {formatMinutes(review.p90Minutes)} · 样本{" "}
              {formatCount(review.latencySamples)}
            </span>
          }
        />
        <MetricCell
          label="待复核积压 · 全站"
          value={formatCount(review.awaitingTotal)}
          onInspect={() => onInspect("A-06")}
          foot={
            <span className="admin-metric-card__delta num">
              窗口内入队 {formatCount(review.queuedInWindow)} · 快照不受窗口限制
            </span>
          }
        />
      </section>

      <div className="admin-bento">
        <div className="span-6">
          <ReasonBreakdown
            title="AI 未出结论原因"
            ariaLabel="AI 未出结论原因构成"
            rows={rejectionRows}
            denominatorLabel={`分母 ${formatCount(rejection.rejected)} 条`}
            emptyText="该时间范围内没有 AI 拒识记录。"
            onInspect={() => onInspect("A-05")}
            note={`分母：窗口内 AI 拒识 ${formatCount(rejection.rejected)} 条（判读总量 ${formatCount(
              rejection.total,
            )} 条）。低置信度会进入复核队列但不算拒识，见右侧「触发复核原因」。`}
          />
        </div>
        <div className="span-6">
          <ReasonBreakdown
            title="触发复核原因"
            ariaLabel="触发复核原因构成"
            rows={queueRows}
            denominatorLabel={`分母 ${formatCount(review.queuedInWindow)} 条`}
            emptyText="该时间范围内没有进入复核队列的记录。"
            onInspect={() => onInspect("A-12")}
            note={`分母：窗口内进入复核队列 ${formatCount(
              review.queuedInWindow,
            )} 条（按当前复核状态，无法还原历史状态）。原因为空的记录作为「未记录原因」保留。`}
          />
        </div>

        <section className="panel span-6" aria-label="复核时延明细">
          <div className="panel__head">
            <h2 className="panel__title">复核时延明细</h2>
            <span className="panel__actions">
              <button type="button" className="admin-analytics__inspect" onClick={() => onInspect("A-10")}>
                查看口径
              </button>
            </span>
          </div>
          <div className="panel__body">
            {review.latencySamples === 0 ? (
              <p className="admin-analytics__empty">
                暂无有效复核时间样本。
                {data.dataQuality.reviewedWithoutTime > 0
                  ? `另有 ${formatCount(
                      data.dataQuality.reviewedWithoutTime,
                    )} 条已复核记录缺少复核时间，不进入时延统计。`
                  : ""}
              </p>
            ) : (
              <dl className="admin-analytics__readouts num">
                <div>
                  <dt>平均</dt>
                  <dd>{formatMinutes(review.avgMinutes)}</dd>
                </div>
                <div>
                  <dt>P50</dt>
                  <dd>{formatMinutes(review.p50Minutes)}</dd>
                </div>
                <div>
                  <dt>P90</dt>
                  <dd>{formatMinutes(review.p90Minutes)}</dd>
                </div>
                <div>
                  <dt>最大值</dt>
                  <dd>{formatMinutes(review.maxMinutes)}</dd>
                </div>
                <div>
                  <dt>有效样本</dt>
                  <dd>{formatCount(review.latencySamples)}</dd>
                </div>
              </dl>
            )}
            {data.dataQuality.reviewedWithoutTime > 0 && review.latencySamples > 0 ? (
              <p className="admin-analytics__note num">
                另有 {formatCount(data.dataQuality.reviewedWithoutTime)} 条已复核记录缺少复核时间，
                未进入时延统计。
              </p>
            ) : null}
          </div>
        </section>

        <section className="panel span-6" aria-label="复核人工作量">
          <div className="panel__head">
            <h2 className="panel__title">复核人工作量</h2>
            <span className="panel__note">历史数据 · 只称「复核人」</span>
          </div>
          <div className="panel__body">
            <ReviewersTable reviewers={review.reviewers} onInspect={() => onInspect("A-11")} />
          </div>
        </section>
      </div>
    </>
  )
}

/* ── 模型与图像质量 ───────────────────────────────────────── */

function ModelSection(props: {
  data: AdminAnalytics
  onInspect: (id: string) => void
}): ReactElement {
  const { data, onInspect } = props
  const { model } = data
  const histogram = confidenceHistogramRows(model.confidenceHistogram)
  const quality = qualityScoreRows(model.qualityScores)
  const ood = oodCapability(model.oodStatuses)
  const qualityReasonRows = model.qualityReasons.map((row) => ({
    label: qualityReasonLabel(row.reason),
    count: row.count,
  }))

  return (
    <>
      <section className="admin-metric-strip" aria-label="模型与质量读数">
        <MetricCell
          label="有效置信度样本"
          value={formatCount(model.confidence.samples)}
          primary
          onInspect={() => onInspect("B-03")}
          foot={
            <span className="admin-metric-card__delta num">
              0–100 量纲 · 已排除量纲未知与越界
            </span>
          }
        />
        <MetricCell
          label="平均置信度"
          value={formatConfidence(model.confidence.avg)}
          onInspect={() => onInspect("B-03")}
          foot={
            <span className="admin-metric-card__delta">
              模型自评 · 不是诊断准确率
            </span>
          }
        />
        <MetricCell
          label="质量分越界"
          value={formatCount(model.qualityOutOfContract)}
          onInspect={() => onInspect("B-05")}
          foot={
            <span className="admin-metric-card__delta num">
              契约值 {"{100,75,50,25,0}"} · 不就近归类
            </span>
          }
        />
        <MetricCell
          label="质量分缺失"
          value={formatCount(model.qualityUnknown)}
          onInspect={() => onInspect("B-05")}
          foot={<span className="admin-metric-card__delta">空值不当作 0 分</span>}
        />
      </section>

      <div className="admin-bento">
        <section className="panel span-12" aria-label="模型版本对照">
          <div className="panel__head">
            <h2 className="panel__title">模型版本对照</h2>
            <span className="panel__note">平均置信度只在有效样本内计算</span>
          </div>
          <div className="panel__body">
            <ModelVersionsTable versions={model.versions} onInspect={() => onInspect("B-01")} />
          </div>
        </section>

        <div className="span-6">
          <ConfidenceHistogram
            rows={histogram}
            emptyText={
              data.rejection.total > 0
                ? "有判读记录，但没有量纲可靠（1 < 置信度 ≤ 100）的样本。"
                : "该时间范围内没有判读记录。"
            }
            onInspect={() => onInspect("B-04")}
            note="量纲未知、越界与空值不进入任何桶；它们在数据质量页单独计数。"
          />
        </div>
        <div className="span-6">
          <QualityBuckets
            rows={quality}
            emptyText="该时间范围内没有质量分记录。"
            onInspect={() => onInspect("B-05")}
            note={`契约外质量分 ${formatCount(
              model.qualityOutOfContract,
            )} 条、质量分缺失 ${formatCount(model.qualityUnknown)} 条，均不进入这五档。`}
          />
        </div>

        <div className="span-6">
          <QualityReasonList
            rows={qualityReasonRows}
            emptyText="该时间范围内没有记录图像质量问题。"
            onInspect={() => onInspect("B-06")}
            note={`多标签：一行可命中多个原因，合计可超过记录数。契约外标签 ${formatCount(
              model.unclassifiedQualityReasonCount,
            )} 次，单独计数。`}
          />
        </div>

        <section className="panel span-6" aria-label="置信度可用性说明">
          <div className="panel__head">
            <h2 className="panel__title">置信度可用性</h2>
            <span className="panel__actions">
              <button type="button" className="admin-analytics__inspect" onClick={() => onInspect("B-03")}>
                查看口径
              </button>
            </span>
          </div>
          <div className="panel__body">
            <dl className="admin-analytics__readouts num">
              <div>
                <dt>有效样本（1 &lt; c ≤ 100）</dt>
                <dd>{formatCount(model.confidence.samples)}</dd>
              </div>
              <div>
                <dt>量纲歧义（0 &lt; c ≤ 1）</dt>
                <dd>{formatCount(model.confidence.ambiguous)}</dd>
              </div>
              <div>
                <dt>越界（c ≤ 0 或 c &gt; 100）</dt>
                <dd>{formatCount(model.confidence.outOfRange)}</dd>
              </div>
              <div>
                <dt>缺失</dt>
                <dd>{formatCount(model.confidence.missing)}</dd>
              </div>
            </dl>
            <p className="admin-analytics__note">
              (0,1] 的样本不自动 ×100，也不进入均值与直方图——0.74 既可能是 74%，也可能是 0.74%。
            </p>
          </div>
        </section>

        <div className="span-6">
          <StatusDistributionCard
            title="OOD 状态"
            ariaLabel="OOD 状态分布"
            rows={model.oodStatuses}
            labelOf={oodStatusLabel}
            emptyText="该时间范围内没有 OOD 状态记录。"
            onInspect={() => onInspect("B-07")}
            note={
              ood.disabled
                ? "OOD 检测当前未启用 · 记录值为 NOT_EVALUATED。这不是「全部安全」，页面不展示 100% 分布图。"
                : undefined
            }
          />
        </div>

        <div className="span-6">
          <StatusDistributionCard
            title="校准状态"
            ariaLabel="校准状态分布"
            rows={model.calibrationStatuses}
            labelOf={calibrationStatusLabel}
            emptyText="该时间范围内没有校准状态记录。"
            onInspect={() => onInspect("B-08")}
          />
        </div>
      </div>
    </>
  )
}

/* ── 数据质量 ─────────────────────────────────────────────── */

function QualitySection(props: {
  issues: ReturnType<typeof buildQualityIssues>
  onInspect: (id: string) => void
}): ReactElement {
  return (
    <>
      <section className="panel admin-analytics__quality-panel" aria-label="数据质量清单">
        <div className="panel__head">
          <h2 className="panel__title">数据质量</h2>
          <span className="panel__note">
            计数来自 DataQualityVO；不同类别不加总
          </span>
        </div>
        <div className="panel__body">
          <p className="admin-analytics__lede">
            这里列出不满足当前统计口径的记录：契约外枚举、不进入复核统计的状态、量纲无法判定、
            质量分越界或时间字段缺失。它们不参与对应比率的分母，也不会被静默丢弃——
            每条异常都能在指标口径里看到处理规则。
          </p>
          <QualityIssuesTable issues={props.issues} onInspect={props.onInspect} />
        </div>
      </section>
    </>
  )
}

export default AdminDataAnalysisPage
