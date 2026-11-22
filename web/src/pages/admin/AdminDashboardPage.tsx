import { useMemo, useState, type ReactElement } from "react"
import { Link } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"
import { ChartColumn, Eye, ScanEye } from "lucide-react"

import { AdminEmpty } from "@/components/admin/AdminEmpty"
import { AdminPageHead } from "@/components/admin/AdminPageHead"
import { InsightEmpty } from "@/components/admin/InsightEmpty"
import { MetricCell } from "@/components/admin/MetricCell"
import { ConfidenceCell } from "@/components/admin/ConfidenceCell"
import { TrendAreaChart } from "@/components/admin/charts/TrendAreaChart"
import { RankedBars } from "@/components/admin/charts/RankedBars"
import { DataTable, type Column } from "@/components/admin/DataTable"
import { RiskTag } from "@/components/RiskTag"
import { FaultNote } from "@/components/fault"
import { getAdminStats, listDiagnoses, type AdminDiagnosis } from "@/lib/adminApi"
import {
  formatStatNumber,
  formatStatPercent,
  noConclusionView,
  rankDistribution,
  reviewView,
} from "@/lib/adminDomain"
import { alignPreviousWindow, changeLabel } from "@/lib/chartData"
import { describeError } from "@/lib/api"
import { queryKeys } from "@/lib/queryKeys"
import { canonicalClassName, formatTimestamp } from "@/lib/domain"
import { fault } from "@/lib/faults"

/** 统计时间窗：整页统一，KPI、趋势、分布都覆盖同一段时间。 */
const RANGES = [
  { value: 7, label: "7 天" },
  { value: 30, label: "30 天" },
  { value: 90, label: "90 天" },
]

/**
 * 环比方向：只表达「比上期多 / 少」，不判定好坏——
 * 判读量上涨不是成绩，拒识上涨更不是。方向交给箭头，含义留给读者。
 */
function trendOf(current: number, previous: number): "up" | "down" | "flat" {
  if (!Number.isFinite(current) || !Number.isFinite(previous) || current === previous) return "flat"
  return current > previous ? "up" : "down"
}

/**
 * 仪表盘（B3 · 运营指挥台）。
 *
 * <p>职责是「发现现在能做什么」，不是「解释数据为什么这样」：
 * <ol>
 *   <li>核心运营概况：窗口内判读、AI 拒识、新增用户、可处理事项数量；</li>
 *   <li>近期趋势：判读趋势（含上一窗口对照）与病种构成；</li>
 *   <li>真正可处理的运营待办：知识库失败、资讯待审核；AI 拒识只读；</li>
 *   <li>最近判读记录 + 复核历史入口。</li>
 * </ol>
 *
 * <p>三条 B3 纪律：
 * <ul>
 *   <li>正式临床复核写入已安全关闭（D-01b），Dashboard 不再出现「复核积压 / 去复核」，
 *       只保留历史只读入口，详细复核统计交给数据分析；</li>
 *   <li>没有真实监测就不画系统健康灯；OOD 未启用也不写成健康；</li>
 *   <li>字段缺失显示「—」，不把未知补成 0；置信度口径统一交给数据分析，
 *       本页不再展示混合量纲的平均值。</li>
 * </ul>
 */
export function AdminDashboardPage(): ReactElement {
  const [days, setDays] = useState(30)

  const statsQuery = useQuery({
    queryKey: queryKeys.admin.stats(days),
    queryFn: () => getAdminStats(days),
    // 切窗必须立刻失效：不同窗口是不同的查询键；gcTime 0 让离开的窗口马上被回收。
    gcTime: 0,
    staleTime: 0,
  })
  const recentQuery = useQuery({
    queryKey: queryKeys.admin.recentDiagnoses,
    queryFn: () => listDiagnoses({ current: 1, pageSize: 5 }),
  })

  // 第二道守卫：后端回显的窗口天数与选中值不一致时，当作还没读到。
  const stats = statsQuery.data?.days === days ? statsQuery.data : undefined
  const statsFault = statsQuery.error ? describeError(statsQuery.error) : null
  const statsLoading = !stats && !statsFault

  const changeDays = (next: number) => {
    if (next !== days) setDays(next)
  }

  const recent = recentQuery.data
  const recentFault = recentQuery.error ? describeError(recentQuery.error) : null
  const recentLoading = recentQuery.isPending

  const trendRows = useMemo(
    () =>
      alignPreviousWindow(
        stats?.trendDays ?? [],
        stats?.diagnosisTrend ?? [],
        stats?.previousDiagnosisTrend ?? [],
      ),
    [stats],
  )

  const distribution = useMemo(() => {
    // 后端按原始 result 分组；前端按产品的类名口径归一（同一类的不同写法合并成
    // 一行），图例、读数表与最近记录读到的都是规范中文名。
    const merged = new Map<string, number>()
    for (const row of stats?.diagnosisDistribution ?? []) {
      const name = canonicalClassName(row.name) ?? row.name
      merged.set(name, (merged.get(name) ?? 0) + row.value)
    }
    return rankDistribution([...merged].map(([name, value]) => ({ name, value })))
  }, [stats])

  // 待审核资讯：只有后端真的返回了状态分布，才把「没有状态 3」当作真实 0；
  // 字段整体缺失时保留 undefined，界面显示「—」而不是「已清空」。
  const newsPending = useMemo(() => {
    if (!stats?.newsByStatus) return undefined
    const row = stats.newsByStatus.find((entry) => Number(entry.name) === 3)
    return row ? row.value : 0
  }, [stats])

  const inRange = stats?.diagnosisInRange
  const aiRejected = stats?.diagnosisAiRejected
  const knowledgeFailed = stats?.knowledgeFailed
  const userNew = stats?.userNew
  const rejectShare =
    inRange !== undefined && aiRejected !== undefined && inRange > 0 ? aiRejected / inRange : null

  // 可处理事项 = 知识库失败 + 资讯待审核。两项都已知才给合计，否则保持未知。
  const todoCount =
    knowledgeFailed !== undefined && newsPending !== undefined
      ? knowledgeFailed + newsPending
      : undefined

  /** 读数：统计还没回来或字段缺失时给破折号，不给 0——「还没读到」和「读到是零」是两件事。 */
  const read = (value: number | undefined): string =>
    stats && value !== undefined ? formatStatNumber(value) : "—"

  const recentRows = recent?.records ?? []

  const recentColumns: Column<AdminDiagnosis>[] = [
    {
      key: "url",
      title: "影像",
      width: "60px",
      render: (row) =>
        row.url ? (
          <img
            className="admin-diagnosis__thumb"
            src={row.url}
            alt={`判读影像 ${row.id}`}
            width={38}
            height={38}
            loading="lazy"
          />
        ) : (
          <span className="admin-diagnosis__thumb admin-diagnosis__thumb--empty" aria-label="无影像">
            <Eye size={14} strokeWidth={1.6} aria-hidden="true" />
          </span>
        ),
    },
    { key: "username", title: "患者", width: "120px" },
    {
      key: "result",
      title: "判读结果",
      render: (row) => {
        const raw = row.result?.trim() ?? ""
        if (raw) return canonicalClassName(raw) ?? raw
        // 没有结论有两种，拒识与「没判读结果」不是一回事：
        // 口径由 noConclusionView 统一裁决（与诊断台账同一份）。
        const view = noConclusionView(row)
        return (
          <span
            className="admin-chip"
            data-tone={view.rejected ? "critical" : "mute"}
            title={view.reason}
          >
            {view.label}
          </span>
        )
      },
    },
    {
      key: "confidence",
      title: "置信度",
      width: "128px",
      mono: true,
      render: (row) => <ConfidenceCell value={row.confidence} />,
    },
    {
      key: "review",
      title: "复审状态",
      width: "120px",
      render: (row) => {
        const view = reviewView(row)
        return <RiskTag risk={view.risk}>{view.label}</RiskTag>
      },
    },
    {
      key: "createTime",
      title: "时间",
      width: "164px",
      mono: true,
      render: (row) => formatTimestamp(row.createTime),
    },
  ]

  // 窗口内有没有判读，决定这一页是「工作台」还是「解释面板」。
  const noDiagnosis = stats ? stats.diagnosisInRange === 0 : false

  /* 窗口内判读的去向尺：已复核 / 待复核 / AI 拒识 / 其余。
     四段都用真实计数，宽度按计数分配；没有样本时整条不画。 */
  const meterParts = (() => {
    if (!stats || inRange <= 0) return []
    const parts = [
      { kind: "reviewed", label: "已复核", value: Math.max(0, stats.diagnosisReviewed) },
      { kind: "awaiting", label: "待复核", value: Math.max(0, stats.diagnosisAwaitingReview) },
      { kind: "rejected", label: "AI 拒识", value: Math.max(0, aiRejected) },
    ]
    const covered = parts.reduce((sum, part) => sum + part.value, 0)
    return [...parts, { kind: "rest", label: "其余", value: Math.max(0, inRange - covered) }]
  })()
  const meterLabel = meterParts
    .filter((part) => part.value > 0)
    .map((part) => `${part.label} ${formatStatNumber(part.value)}`)
    .join(" · ")

  /* ── 面板块（两种布局复用同一批内容）────────────────────────── */

  /**
   * 复核历史卡：只解释能力边界并引导到数据分析，不展示待处理数量。
   * 正式临床复核写入已按 D-01b 安全关闭，历史记录不构成当前待办。
   */
  const historyCard = (
    <section className="panel admin-dashboard__history" aria-label="复核历史记录">
      <div className="panel__head">
        <h2 className="admin-sec-title">复核历史记录</h2>
        <span className="admin-chip" data-tone="mute">
          历史数据
        </span>
      </div>
      <div className="panel__body admin-dashboard__history-body">
        <p>
          当前正式临床复核写入已安全关闭。历史记录仅供查询和分析，不构成当前待办任务。
        </p>
        <p className="admin-dashboard__history-meta">
          状态分布、复核时延与复核人工作量在数据分析中查看。
        </p>
        <Link
          className="action-row__link"
          to={`/admin/data-analysis?days=${days}&section=review`}
        >
          查看历史复核统计、状态分布与复核时延
        </Link>
      </div>
    </section>
  )

  const trendBlock = (
    <TrendAreaChart
      data={trendRows}
      ariaLabel={`判读趋势 · 近 ${days} 天（含上一等长窗口对照）`}
      height={288}
    />
  )

  const rankedBlock = (
    <RankedBars rows={distribution} ariaLabel="病种分布" unit="条" nameWidth={128} />
  )

  const actionsBlock = (
    <section className="panel admin-dashboard__todo" aria-label="运营待办">
      <div className="panel__head">
        <h2 className="admin-sec-title">运营待办</h2>
        <span className="panel__note">{stats ? `近 ${stats.days} 天` : "—"}</span>
      </div>
      <div className="panel__body">
        <div className="action-list">
          <div className="action-row">
            <span className="action-row__label">知识库处理失败</span>
            <span
              className={`action-row__count num ${knowledgeFailed && knowledgeFailed > 0 ? "action-row__count--hot" : ""}`}
            >
              {read(knowledgeFailed)}
            </span>
            <Link className="action-row__link" to="/admin/knowledge?status=2">
              去处理
            </Link>
          </div>
          <div className="action-row">
            <span className="action-row__label">资讯待审核</span>
            <span
              className={`action-row__count num ${newsPending && newsPending > 0 ? "action-row__count--hot" : ""}`}
            >
              {read(newsPending)}
            </span>
            <Link className="action-row__link" to="/admin/news?status=3">
              去审核
            </Link>
          </div>
          <div className="action-row">
            <span className="action-row__label">AI 拒识记录</span>
            <span
              className={`action-row__count num ${aiRejected && aiRejected > 0 ? "action-row__count--hot" : ""}`}
            >
              {read(aiRejected)}
            </span>
            {/* 拒识记录没有处理写入，只能查看；不写「去处理」。 */}
            <Link className="action-row__link" to="/admin/diagnosis?review=aiRejected">
              查看记录
            </Link>
          </div>
        </div>
      </div>
    </section>
  )

  const recentBlock = (
    <>
      <div className="panel__head">
        <h2 className="admin-sec-title">最近判读记录</h2>
        <span className="panel__note">最新 5 条 · 全量在诊断管理</span>
        <div className="panel__actions">
          <Link className="action-row__link" to="/admin/diagnosis">
            打开诊断管理
          </Link>
        </div>
      </div>
      <div className="panel__body">
        {recentFault ? (
          <FaultNote
            fault={fault({
              severity: "attention",
              kind: "client",
              title: `最近判读记录加载失败：${recentFault.text}`,
              code: recentFault.code,
            })}
          />
        ) : null}
        <DataTable<AdminDiagnosis>
          columns={recentColumns}
          rows={recentRows}
          rowKey={(row) => row.id}
          loading={recentLoading}
          emptyText="暂无判读记录"
          emptyContent={
            <AdminEmpty
              icon={ScanEye}
              title="还没有判读记录"
              description="患者完成首次影像判读后，这里会显示最近 5 条。"
            />
          }
          page={{ current: 1, pageSize: 5, total: recent?.total ?? 0 }}
          onPageChange={() => {}}
          hidePager
        />
      </div>
    </>
  )

  // 空窗口时的解释面板：说清「为什么空」与「上一窗口有多少」，并给出真实出口。
  const emptyInsight = (
    <InsightEmpty
      title="判读趋势与分布"
      note={`近 ${days} 天`}
      icon={ChartColumn}
      emptyTitle="窗口内还没有可统计的判读"
      description={
        stats?.previousDiagnosisInRange
          ? `上一等长窗口有 ${formatStatNumber(stats.previousDiagnosisInRange)} 条判读。` +
            "趋势与病种分布要等窗口内出现判读才会成图——把时间窗拉长，或到诊断管理查看全量。"
          : "上一等长窗口同样没有记录。趋势与病种分布要等窗口内出现判读才会成图——" +
            "把时间窗拉长，或到诊断管理查看全量。"
      }
      action={
        <Link className="action-row__link" to="/admin/diagnosis">
          打开诊断管理
        </Link>
      }
    />
  )

  return (
    <div className="admin-dashboard">
      <AdminPageHead
        code="01"
        title="运营总览"
        eyebrow="ADMIN · DASHBOARD"
        size="lead"
        description="判读规模、近期趋势与可处理的运营事项。历史复核与深入分析在数据分析。"
        meta={
          stats ? (
            <span className="admin-sample num">
              {stats.rangeStart} ~ {stats.rangeEnd} · 近 {stats.days} 天
            </span>
          ) : null
        }
        actions={
          <div className="admin-range" role="group" aria-label="统计时间窗">
            {RANGES.map((range) => (
              <button
                key={range.value}
                type="button"
                className="admin-range__btn"
                aria-pressed={days === range.value}
                onClick={() => changeDays(range.value)}
              >
                {range.label}
              </button>
            ))}
          </div>
        }
      />

      {statsFault ? (
        <FaultNote
          className="admin-dashboard__fault"
          fault={fault({
            severity: "attention",
            kind: "client",
            title: statsFault.text,
            code: statsFault.code,
          })}
        />
      ) : null}

      {statsLoading ? (
        <p className="admin-dashboard__loading num">统计载入中…</p>
      ) : (
        <>
          <section className="admin-metric-strip" aria-label="核心运营概况">
            <MetricCell
              label="窗口内判读"
              value={read(inRange)}
              primary
              foot={
                <span
                  className="admin-metric-card__delta"
                  data-trend={
                    stats && inRange !== undefined && stats.previousDiagnosisInRange !== undefined
                      ? trendOf(inRange, stats.previousDiagnosisInRange)
                      : "flat"
                  }
                >
                  {stats && inRange !== undefined && stats.previousDiagnosisInRange !== undefined
                    ? changeLabel(inRange, stats.previousDiagnosisInRange)
                    : "—"}
                </span>
              }
            />
            <MetricCell
              label="AI 拒识"
              value={read(aiRejected)}
              foot={
                <span className="admin-metric-card__delta">
                  {stats ? `占窗口内判读 ${formatStatPercent(rejectShare)}` : "统计读数尚未返回"}
                </span>
              }
            />
            <MetricCell
              label="新增用户"
              value={read(userNew)}
              foot={
                <span
                  className="admin-metric-card__delta"
                  data-trend={
                    stats && userNew !== undefined && stats.previousUserNew !== undefined
                      ? trendOf(userNew, stats.previousUserNew)
                      : "flat"
                  }
                >
                  {stats && userNew !== undefined && stats.previousUserNew !== undefined
                    ? changeLabel(userNew, stats.previousUserNew)
                    : "—"}
                </span>
              }
            />
            <MetricCell
              label="待处理事项"
              value={read(todoCount)}
              foot={
                <span className="admin-metric-card__delta admin-dashboard__todo-foot">
                  {!stats ? (
                    "统计读数尚未返回"
                  ) : todoCount === undefined ? (
                    "待办口径未接入"
                  ) : todoCount === 0 ? (
                    "当前没有待处理事项"
                  ) : (
                    <>
                      <span>知识库失败 {read(knowledgeFailed)}</span>
                      <span>资讯待审核 {read(newsPending)}</span>
                    </>
                  )}
                </span>
              }
            />
          </section>

          {noDiagnosis ? (
            /* 空窗口：历史入口与空窗解释在上，真实存在的记录与待办在下。
               两行各自成对，格子高度一致——不留下半张卡高的空洞。 */
            <div className="admin-bento">
              <div className="span-8">{emptyInsight}</div>
              <div className="span-4">{historyCard}</div>
              <section className="panel admin-dashboard__recent span-8" aria-label="最近判读记录">
                {recentBlock}
              </section>
              <div className="span-4">{actionsBlock}</div>
            </div>
          ) : (
            <div className="admin-bento">
              <div className="span-8">{trendBlock}</div>
              <div className="span-4">{rankedBlock}</div>
              <div className="span-7">{actionsBlock}</div>
              <div className="span-5">{historyCard}</div>
              <section
                className="panel admin-dashboard__recent span-12"
                aria-label="最近判读记录"
              >
                {recentBlock}
              </section>
            </div>
          )}
        </>
      )}

      {stats ? (
        <p className="admin-dashboard__note">
          统计窗口 {stats.rangeStart} ~ {stats.rangeEnd} · 判读与拒识按 createTime 筛选。
          置信度不在此页展示，有效样本与口径见{" "}
          <Link className="action-row__link" to={`/admin/data-analysis?days=${days}&section=model`}>
            数据分析 · 模型与图像质量
          </Link>
          。
        </p>
      ) : null}
    </div>
  )
}

export default AdminDashboardPage
