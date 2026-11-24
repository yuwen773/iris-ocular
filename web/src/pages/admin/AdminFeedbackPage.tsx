import "@/styles/admin-feedback.css"

import { useMemo, useState, type FormEvent, type ReactElement, type ReactNode } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { ChevronDown, ChevronUp, FileText, MessageSquareText, RotateCcw } from "lucide-react"

import { useMediaQuery } from "@/lib/useMediaQuery"

import { AdminEmpty } from "@/components/admin/AdminEmpty"
import { AdminPageHead } from "@/components/admin/AdminPageHead"
import { DataTable, type Column } from "@/components/admin/DataTable"
import { Modal } from "@/components/admin/Modal"
import { RowMenu } from "@/components/admin/RowMenu"
import { RiskTag } from "@/components/RiskTag"
import { FaultNote } from "@/components/fault"
import { IrisFormAlert } from "@/components/system/IrisFormAlert"
import { useListWorkspace } from "@/hooks/useListWorkspace"
import {
  adminUserFeedbackAttachmentUrl,
  changeUserFeedbackStatus,
  listAdminUserFeedback,
  replyUserFeedback,
  userFeedbackPendingCount,
  type AdminFeedback,
  type AdminFeedbackQuery,
} from "@/lib/adminApi"
import { describeError, type ErrorDetail } from "@/lib/api"
import { formatStatNumber } from "@/lib/adminDomain"
import { formatTimestamp } from "@/lib/domain"
import { fault, faultFromError, validationFault, type Fault } from "@/lib/faults"
import { queryKeys } from "@/lib/queryKeys"
import {
  FEEDBACK_CATEGORIES,
  FEEDBACK_STATUSES,
  FEEDBACK_TEXT_LIMIT,
  feedbackCategoryLabel,
  feedbackIsClosed,
  feedbackNextStatuses,
  feedbackStatusLabel,
  feedbackStatusRisk,
  feedbackTargetLabel,
} from "@/lib/userFeedbackDomain"

const CATEGORY_FILTERS = [
  { value: "", label: "全部" },
  ...FEEDBACK_CATEGORIES.map((category) => ({ value: category.value, label: category.label })),
]

const STATUS_FILTERS = [
  { value: "", label: "全部" },
  ...FEEDBACK_STATUSES.map((status) => ({ value: status.value, label: status.label })),
]

const CONTENT_LIMIT = 64

type Query = {
  category: string
  status: string
  keyword: string
  startDate: string
  endDate: string
}

function truncate(value: string, limit: number): string {
  return value.length > limit ? `${value.slice(0, limit)}…` : value
}

function Fact({ label, value, mono }: { label: string; value: ReactNode; mono?: boolean }): ReactElement {
  return (
    <div className="admin-feedback__fact">
      <dt className="admin-feedback__fact-label">{label}</dt>
      <dd className={"admin-feedback__fact-value" + (mono ? " num" : "")}>{value}</dd>
    </div>
  )
}

/**
 * 意见反馈（Management，管理端工作台）。
 *
 * <p>台账 + 详情 + 状态流转 + 回复，闭环落在这一页。反馈是**产品意见通道**，
 * 不是医疗记录（见 `GLOSSARY.md`）：它不进入判读统计、复核队列与数据分析口径，
 * 页面也不提供任何改写医学结论的动作。
 *
 * <p>状态迁移只在当前状态允许的目标里出按钮（后端仍是裁决者，40900 = 别人先改过）。
 * 标记「已解决」前必须有一句回复：结论不能只是一个状态码。
 */
export function AdminFeedbackPage(): ReactElement {
  const queryClient = useQueryClient()

  /* ── 筛选 ─────────────────────────────────────────────── */
  const [keywordDraft, setKeywordDraft] = useState("")
  const [startDraft, setStartDraft] = useState("")
  const [endDraft, setEndDraft] = useState("")
  const [query, setQuery] = useState<Query>({
    category: "",
    status: "",
    keyword: "",
    startDate: "",
    endDate: "",
  })

  const {
    page,
    sort,
    applyFilter,
    resetFilters,
    changePage,
    changeSort,
  } = useListWorkspace()

  const { current, pageSize } = page
  const params = useMemo<AdminFeedbackQuery>(
    () => ({
      current,
      pageSize,
      ...(query.category ? { category: query.category } : {}),
      ...(query.status ? { status: query.status } : {}),
      ...(query.keyword ? { keyword: query.keyword } : {}),
      ...(query.startDate ? { startDate: query.startDate } : {}),
      ...(query.endDate ? { endDate: query.endDate } : {}),
      ...(sort ? { sortField: sort.field, sortOrder: sort.order } : {}),
    }),
    [current, pageSize, query, sort],
  )

  const listQuery = useQuery({
    queryKey: queryKeys.admin.feedback(params),
    queryFn: () => listAdminUserFeedback(params),
  })
  const listFault = listQuery.error ? describeError(listQuery.error) : null

  /** 待处理数：侧栏 / 页头的读数。读不到时显示「—」，不补成 0。 */
  const pendingQuery = useQuery({
    queryKey: queryKeys.admin.feedbackPendingCount,
    queryFn: userFeedbackPendingCount,
  })

  const [actionError, setActionError] = useState<ErrorDetail | null>(null)
  const [actionNote, setActionNote] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)

  const refreshFeedback = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.admin.feedbackAll }),
      queryClient.invalidateQueries({ queryKey: queryKeys.admin.feedbackPendingCount }),
    ])

  function onSearch(event: FormEvent) {
    event.preventDefault()
    setActionError(null)
    setQuery((prev) => ({
      ...prev,
      keyword: keywordDraft.trim(),
      startDate: startDraft,
      endDate: endDraft,
    }))
    applyFilter()
  }

  function onReset() {
    setKeywordDraft("")
    setStartDraft("")
    setEndDraft("")
    setActionError(null)
    setQuery({ category: "", status: "", keyword: "", startDate: "", endDate: "" })
    resetFilters()
  }

  /** 枚举筛选点一下即生效，与文本 / 日期字段的「查询」分工一致。 */
  function applyCategory(value: string) {
    setActionError(null)
    setQuery((prev) => ({ ...prev, category: value }))
    applyFilter()
  }

  function applyStatusFilter(value: string) {
    setActionError(null)
    setQuery((prev) => ({ ...prev, status: value }))
    applyFilter()
  }

  /* ── 详情 / 回复 / 状态流转 ───────────────────────────── */
  const [detail, setDetail] = useState<AdminFeedback | null>(null)
  const [replyDraft, setReplyDraft] = useState("")
  const [modalFault, setModalFault] = useState<Fault | null>(null)
  const [busy, setBusy] = useState(false)
  const [attachmentUrl, setAttachmentUrl] = useState<string | null>(null)
  const [attachmentBusy, setAttachmentBusy] = useState(false)

  function openDetail(row: AdminFeedback) {
    setActionError(null)
    setModalFault(null)
    setAttachmentUrl(null)
    setDetail(row)
    setReplyDraft(row.reply ?? "")
  }

  function closeDetail() {
    if (busy) return
    setDetail(null)
    setModalFault(null)
  }

  async function openAttachment() {
    if (!detail || attachmentBusy) return
    setAttachmentBusy(true)
    setModalFault(null)
    try {
      setAttachmentUrl(await adminUserFeedbackAttachmentUrl(detail.id))
    } catch (cause) {
      setModalFault(faultFromError(cause, { intent: "load" }))
    } finally {
      setAttachmentBusy(false)
    }
  }

  async function sendReply() {
    if (!detail || busy) return
    const reply = replyDraft.trim()
    if (!reply) {
      setModalFault(validationFault("回复不能是空的——用户要读到一句人话。"))
      return
    }
    setBusy(true)
    setModalFault(null)
    try {
      const next = await replyUserFeedback(detail.id, reply)
      setDetail(next)
      setReplyDraft(next.reply ?? "")
      setActionNote("回复已发送")
      await refreshFeedback()
    } catch (cause) {
      setModalFault(faultFromError(cause, { intent: "generic" }))
    } finally {
      setBusy(false)
    }
  }

  async function applyStatus(target: string) {
    if (!detail || busy) return
    const reply = replyDraft.trim()
    if (target === "RESOLVED" && !reply && !detail.reply) {
      setModalFault(validationFault("标记已解决前，先写一句回复给用户。"))
      return
    }
    setBusy(true)
    setModalFault(null)
    try {
      // 只有「标记已解决」把回复一起写入；其余迁移不顺手覆盖已发出去的回复。
      const next = await changeUserFeedbackStatus(
        detail.id,
        target,
        target === "RESOLVED" ? reply || undefined : undefined,
      )
      setDetail(next)
      setReplyDraft(next.reply ?? "")
      setActionNote(`已标记为「${feedbackStatusLabel(target)}」`)
      await refreshFeedback()
    } catch (cause) {
      setModalFault(faultFromError(cause, { intent: "generic" }))
    } finally {
      setBusy(false)
    }
  }

  /** 行内快速迁移：只做不需要回复的动作（「标记已解决」留在详情里）。 */
  async function quickChange(row: AdminFeedback, target: string) {
    if (busyId) return
    setBusyId(row.id)
    setActionError(null)
    setActionNote(null)
    try {
      await changeUserFeedbackStatus(row.id, target)
      setActionNote(`已把反馈 ${row.id} 标记为「${feedbackStatusLabel(target)}」`)
      await refreshFeedback()
    } catch (cause) {
      setActionError(describeError(cause))
    } finally {
      setBusyId(null)
    }
  }

  /* ── 表格 ─────────────────────────────────────────────── */
  const columns: Column<AdminFeedback>[] = [
    {
      key: "category",
      title: "分类",
      width: "132px",
      render: (row) => (
        <span className="admin-feedback__category">
          <span className="admin-feedback__category-name">{feedbackCategoryLabel(row.category)}</span>
          <span className="admin-feedback__category-sub" title={row.targetId ?? undefined}>
            {feedbackTargetLabel(row.targetType, row.targetId)}
          </span>
        </span>
      ),
    },
    {
      key: "content",
      title: "内容",
      width: "260px",
      render: (row) => (
        <span className="admin-feedback__content" title={row.content ?? undefined}>
          {truncate(row.content ?? "", CONTENT_LIMIT)}
        </span>
      ),
    },
    {
      key: "userName",
      title: "提交人",
      width: "112px",
      render: (row) => (
        <span className="admin-feedback__user" title={row.userName ?? undefined}>
          {row.userName || "未知用户"}
        </span>
      ),
    },
    {
      key: "status",
      title: "状态",
      width: "104px",
      sortable: true,
      render: (row) => (
        <RiskTag risk={feedbackStatusRisk(row.status)}>{feedbackStatusLabel(row.status)}</RiskTag>
      ),
    },
    {
      key: "createTime",
      title: "提交时间",
      width: "156px",
      mono: true,
      sortable: true,
      render: (row) => formatTimestamp(row.createTime),
    },
    {
      key: "action",
      title: "操作",
      width: "132px",
      render: (row) => (
        <div className="admin-dt__row-actions">
          <button
            type="button"
            className="admin-btn admin-btn--primary admin-feedback__rowaction"
            onClick={() => openDetail(row)}
          >
            <FileText size={14} strokeWidth={2} aria-hidden="true" />
            详情
          </button>
          {/* 状态未知（旧数据 / 响应漂移）时不给菜单：空菜单比「点了没反应」更诚实。 */}
          {feedbackNextStatuses(row.status).some((status) => status !== "RESOLVED") ? (
            <RowMenu
              label={`更多反馈操作：${row.id}`}
              items={feedbackNextStatuses(row.status)
                .filter((status) => status !== "RESOLVED")
                .map((status) => ({
                  label: `标记为${feedbackStatusLabel(status)}`,
                  icon:
                    status === "PENDING" ? (
                      <RotateCcw size={14} strokeWidth={2} aria-hidden="true" />
                    ) : (
                      <MessageSquareText size={14} strokeWidth={2} aria-hidden="true" />
                    ),
                  disabled: busyId === row.id,
                  onSelect: () => void quickChange(row, status),
                }))}
            />
          ) : null}
        </div>
      ),
    },
  ]

  const rows = listQuery.data?.records
  const total = listQuery.data?.total ?? 0
  const activeFilters =
    (query.category ? 1 : 0) +
    (query.status ? 1 : 0) +
    (query.keyword ? 1 : 0) +
    (query.startDate ? 1 : 0) +
    (query.endDate ? 1 : 0)

  // 新代码只用 390 / 768 / 900（web/DESIGN.md），这里与筛选区 CSS 同档。
  const compact = useMediaQuery("(max-width: 768px)")
  const [filtersOpen, setFiltersOpen] = useState(false)
  const showFields = !compact || filtersOpen

  return (
    <div className="admin-feedback">
      <AdminPageHead
        code="07"
        title="意见反馈"
        eyebrow="ADMIN · FEEDBACK"
        description="用户提交的产品意见与处理闭环。反馈不是医疗记录：它不进入判读统计、复核队列与数据分析口径。"
        meta={
          <>
            <span className="admin-filter__note num">
              {pendingQuery.data !== undefined
                ? `待处理 ${formatStatNumber(pendingQuery.data)} 条`
                : "待处理数读取中"}
            </span>
            <span className="admin-filter__note num">
              {listQuery.data ? `共 ${formatStatNumber(total)} 条` : "总数读取中"}
            </span>
          </>
        }
      />

      <section className="admin-filter" aria-label="反馈筛选">
        <form className="admin-feedback__form" onSubmit={onSearch}>
          <div className="admin-filter__head">
            <h2 className="admin-filter__title">筛选</h2>
            {activeFilters > 0 ? (
              <span className="admin-filter__count num">已应用 {activeFilters} 项</span>
            ) : null}
            <div className="admin-filter__actions">
              {compact ? (
                <button
                  type="button"
                  className="admin-btn admin-btn--ghost admin-btn--sm"
                  aria-expanded={filtersOpen}
                  onClick={() => setFiltersOpen((open) => !open)}
                >
                  {filtersOpen ? (
                    <ChevronUp size={14} strokeWidth={2} aria-hidden="true" />
                  ) : (
                    <ChevronDown size={14} strokeWidth={2} aria-hidden="true" />
                  )}
                  {filtersOpen ? "收起筛选" : "展开筛选"}
                </button>
              ) : null}
              <button className="admin-btn admin-btn--primary" type="submit">
                查询
              </button>
              <button className="admin-btn admin-btn--ghost" type="button" onClick={onReset}>
                重置
              </button>
            </div>
          </div>

          <div className="admin-ledger__group">
            <span className="admin-ledger__grouplabel" id="admin-feedback-status-label">
              状态 · STATUS
            </span>
            <div
              className="admin-ledger__chips"
              role="group"
              aria-labelledby="admin-feedback-status-label"
            >
              {STATUS_FILTERS.map((option) => (
                <button
                  key={option.value || "all"}
                  type="button"
                  className="admin-ledger__chip"
                  aria-pressed={query.status === option.value}
                  onClick={() => applyStatusFilter(option.value)}
                >
                  {option.label}
                </button>
              ))}
            </div>
          </div>

          <div className="admin-ledger__group">
            <span className="admin-ledger__grouplabel" id="admin-feedback-category-label">
              分类 · CATEGORY
            </span>
            <div
              className="admin-ledger__chips"
              role="group"
              aria-labelledby="admin-feedback-category-label"
            >
              {CATEGORY_FILTERS.map((option) => (
                <button
                  key={option.value || "all"}
                  type="button"
                  className="admin-ledger__chip"
                  aria-pressed={query.category === option.value}
                  onClick={() => applyCategory(option.value)}
                >
                  {option.label}
                </button>
              ))}
            </div>
          </div>

          {showFields ? (
            <div className="admin-filter__fields">
              <label className="admin-feedback__field">
                <span className="admin-feedback__label">关键词 · KEYWORD</span>
                <input
                  className="admin-feedback__input"
                  type="text"
                  value={keywordDraft}
                  onChange={(event) => setKeywordDraft(event.target.value)}
                  placeholder="按反馈正文模糊检索…"
                  aria-label="反馈正文关键词"
                />
              </label>
              <div className="admin-ledger__daterange">
                <label className="admin-feedback__field admin-feedback__field--narrow">
                  <span className="admin-feedback__label">提交自 · FROM</span>
                  <input
                    className="admin-feedback__input"
                    type="date"
                    value={startDraft}
                    onChange={(event) => setStartDraft(event.target.value)}
                    aria-label="提交时间起"
                  />
                </label>
                <label className="admin-feedback__field admin-feedback__field--narrow">
                  <span className="admin-feedback__label">提交至 · TO</span>
                  <input
                    className="admin-feedback__input"
                    type="date"
                    value={endDraft}
                    onChange={(event) => setEndDraft(event.target.value)}
                    aria-label="提交时间止"
                  />
                </label>
              </div>
            </div>
          ) : null}
        </form>
      </section>

      {actionError ? (
        <FaultNote
          className="admin-feedback__fault"
          fault={fault({
            severity: "attention",
            kind: "client",
            title: actionError.text,
            code: actionError.code,
          })}
        />
      ) : null}

      {actionNote ? <p className="admin-feedback__note num">{actionNote}</p> : null}

      <section className="admin-feedback__table" aria-label="反馈台账">
        <DataTable<AdminFeedback>
          columns={columns}
          rows={rows}
          rowKey={(row) => row.id}
          loading={listQuery.isPending}
          error={listFault?.text ?? null}
          emptyText="暂无反馈"
          emptyContent={
            <AdminEmpty
              icon={MessageSquareText}
              title="还没有反馈"
              description={
                activeFilters > 0
                  ? "当前筛选条件下没有反馈。放宽状态、分类或时间范围后再看。"
                  : "用户在「意见反馈」页提交后，反馈会出现在这里等待处理。"
              }
              action={
                activeFilters > 0 ? (
                  <button type="button" className="admin-btn admin-btn--ghost" onClick={onReset}>
                    清筛选
                  </button>
                ) : undefined
              }
            />
          }
          page={{ current: page.current, pageSize: page.pageSize, total }}
          onPageChange={(next) => {
            setActionError(null)
            changePage(next)
          }}
          sort={sort}
          onSortChange={(next) => {
            setActionError(null)
            changeSort(next)
          }}
        />
      </section>

      <p className="admin-feedback__footer num">
        列出的是全站用户反馈（管理员视角）· 反馈不参与判读统计与复核口径
      </p>

      <Modal
        open={detail != null}
        title="反馈详情"
        onClose={closeDetail}
        footer={
          <button type="button" className="admin-btn admin-btn--ghost" onClick={closeDetail} disabled={busy}>
            关闭
          </button>
        }
      >
        {detail ? (
          <div className="admin-feedback__detail">
            <div className="admin-feedback__detail-head">
              <RiskTag risk={feedbackStatusRisk(detail.status)}>
                {feedbackStatusLabel(detail.status)}
              </RiskTag>
              <span className="admin-feedback__detail-cat">{feedbackCategoryLabel(detail.category)}</span>
              <span className="admin-feedback__detail-id num">#{detail.id}</span>
            </div>

            <dl className="admin-feedback__facts">
              <Fact label="提交人" value={detail.userName || "未知用户"} />
              <Fact label="提交时间" value={formatTimestamp(detail.createTime)} mono />
              <Fact label="关联对象" value={feedbackTargetLabel(detail.targetType, detail.targetId)} />
              <Fact
                label="最近处理"
                value={detail.handledAt ? formatTimestamp(detail.handledAt) : "—"}
                mono
              />
            </dl>

            <section className="admin-feedback__section">
              <h3 className="admin-feedback__section-title">正文 · MESSAGE</h3>
              <p className="admin-feedback__body">{detail.content || "—"}</p>
            </section>

            {detail.hasAttachment ? (
              <section className="admin-feedback__section">
                <h3 className="admin-feedback__section-title">截图 · SCREENSHOT</h3>
                {attachmentUrl ? (
                  <a
                    className="admin-btn admin-btn--ghost"
                    href={attachmentUrl}
                    target="_blank"
                    rel="noreferrer"
                  >
                    在新窗口打开截图
                  </a>
                ) : (
                  <button
                    type="button"
                    className="admin-btn admin-btn--ghost"
                    onClick={() => void openAttachment()}
                    disabled={attachmentBusy}
                  >
                    {attachmentBusy ? "生成链接中…" : "查看截图"}
                  </button>
                )}
                <p className="admin-feedback__hint">
                  链接 5 分钟内有效；过期后重新点一次即可。
                </p>
              </section>
            ) : null}

            {detail.reply ? (
              <section className="admin-feedback__section admin-feedback__reply-block">
                <h3 className="admin-feedback__section-title">已回复用户 · REPLY</h3>
                <p className="admin-feedback__body">{detail.reply}</p>
                {detail.repliedAt ? (
                  <p className="admin-feedback__hint num">{formatTimestamp(detail.repliedAt)}</p>
                ) : null}
              </section>
            ) : null}

            <label className="admin-feedback__field">
              <span className="admin-feedback__label">回复用户 · REPLY</span>
              <textarea
                className="admin-feedback__input admin-feedback__input--area"
                rows={4}
                maxLength={FEEDBACK_TEXT_LIMIT}
                value={replyDraft}
                disabled={busy || feedbackIsClosed(detail.status)}
                onChange={(event) => setReplyDraft(event.target.value)}
                placeholder="说清处理结果或下一步，用户会在「我的反馈」里读到。"
              />
              <span className="admin-feedback__hint">
                {feedbackIsClosed(detail.status)
                  ? "已结案：先「重新打开」才能继续回复。"
                  : "标记「已解决」前必须有一句回复。"}
              </span>
            </label>

            {modalFault ? (
              <IrisFormAlert
                severity={modalFault.severity}
                title={modalFault.title}
                detail={modalFault.detail}
              />
            ) : null}

            <div className="admin-feedback__actions">
              <button
                type="button"
                className="admin-btn admin-btn--primary"
                onClick={() => void sendReply()}
                disabled={busy || feedbackIsClosed(detail.status)}
              >
                回复用户
              </button>
              {feedbackNextStatuses(detail.status).map((next) => (
                <button
                  key={next}
                  type="button"
                  className="admin-btn admin-btn--ghost"
                  onClick={() => void applyStatus(next)}
                  disabled={busy}
                >
                  标记为{feedbackStatusLabel(next)}
                </button>
              ))}
            </div>
          </div>
        ) : null}
      </Modal>
    </div>
  )
}

export default AdminFeedbackPage
