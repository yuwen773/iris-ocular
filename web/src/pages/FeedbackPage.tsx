import "@/styles/feedback.css"

import { useRef, useState, type ChangeEvent } from "react"
import { ArrowUpRight, Paperclip, X } from "lucide-react"
import { Link } from "react-router-dom"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import {
  describeError,
  getMyUserFeedbackAttachmentUrl,
  getSessions,
  listMyUserFeedback,
  submitUserFeedback,
  uploadUserFeedbackAttachment,
} from "@/lib/api"
import { diagnosisTaskApi } from "@/lib/diagnosisTaskApi"
import { formatTimestamp } from "@/lib/domain"
import { faultFromError, infoFault, validationFault, type Fault } from "@/lib/faults"
import { queryKeys } from "@/lib/queryKeys"
import { useSession } from "@/lib/session"
import { UPLOAD_LIMIT_HINT, exceedsUploadLimit, uploadLimitError } from "@/lib/uploadLimits"
import {
  FEEDBACK_ATTACHMENT_TYPES,
  FEEDBACK_CATEGORIES,
  FEEDBACK_TEXT_LIMIT,
  feedbackCategoryLabel,
  feedbackStatusHint,
  feedbackStatusLabel,
  feedbackStatusRisk,
  feedbackTargetLabel,
  imageTaskStatusLabel,
  validateFeedbackContent,
} from "@/lib/userFeedbackDomain"
import { useFault } from "@/components/fault"
import { EmptyFrame } from "@/components/EmptyFrame"
import { FaultPanel } from "@/components/FaultPanel"
import { RiskTag } from "@/components/RiskTag"
import { SectionHead } from "@/components/SectionHead"
import { DisclaimerBar } from "@/components/DisclaimerBar"
import { ProductHeader } from "@/components/shell/ProductHeader"
import { IrisFormAlert } from "@/components/system/IrisFormAlert"
import { useFormSubmit } from "@/components/system/useFormSubmit"

const MINE_PAGE_SIZE = 5
/** 关联对象下拉的取样条数：够挑到最近的一条，不在表单里做全量分页。 */
const TARGET_SAMPLE = 50

type TargetKind = "" | "DIAGNOSIS_TASK" | "AGENT_SESSION"

/**
 * 意见反馈（Clinical，用户侧）。
 *
 * <p>一条闭环最短的通道：写下来 → 管理员看到并回复 → 回到这里看到结论。
 * 反馈是产品意见通道，**不是医疗记录**（见 `GLOSSARY.md`）：
 * 它不参与判读结论、复核队列与统计口径，页面文案也不做医学承诺。
 *
 * <p>三态口径与全站一致：错误 > 空 > 加载；失败不假装「还没有反馈」。
 */
export function FeedbackPage() {
  const { status, scope } = useSession()
  const { push } = useFault()
  const queryClient = useQueryClient()
  /** 只有确认登录了才发个人请求。 */
  const authed = status === "authed"

  /* ── 提交表单 ─────────────────────────────────────────── */
  const [category, setCategory] = useState("")
  const [content, setContent] = useState("")
  const [targetKind, setTargetKind] = useState<TargetKind>("")
  const [targetId, setTargetId] = useState("")
  const [attachmentKey, setAttachmentKey] = useState<string | null>(null)
  const [attachmentName, setAttachmentName] = useState("")
  const [uploading, setUploading] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [formFault, setFormFault] = useState<Fault | null>(null)
  /** 我的反馈的页码：提交成功后回到第 1 页（新条目在最前）。 */
  const [minePage, setMinePage] = useState(1)
  const fileRef = useRef<HTMLInputElement | null>(null)

  /**
   * 关联对象下拉：只在选了类型后才取数。
   * 判读任务取 `/diagnosis-tasks`（`taskId` 就是提交时服务端要比对的那个 id；
   * `/diagnosis/list/page/my` 给的是 `t_diagnosis.id`，两者不是同一个键），
   * 会话复用 `/agent/sessions`——两个接口都是现成的，不为此新增聚合端点。
   */
  const tasksQuery = useQuery({
    queryKey: queryKeys.feedback.targetTasks(scope),
    queryFn: ({ signal }) => diagnosisTaskApi.list(signal),
    enabled: authed && targetKind === "DIAGNOSIS_TASK",
  })
  const sessionsQuery = useQuery({
    queryKey: queryKeys.feedback.targetSessions(scope),
    queryFn: () => getSessions(undefined, TARGET_SAMPLE),
    enabled: authed && targetKind === "AGENT_SESSION",
  })

  function pickTargetKind(next: TargetKind) {
    setTargetKind(next)
    setTargetId("")
  }

  async function onPickFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ""
    if (!file) return
    if (!FEEDBACK_ATTACHMENT_TYPES.includes(file.type as (typeof FEEDBACK_ATTACHMENT_TYPES)[number])) {
      setFormFault(validationFault("截图只支持 JPG / PNG"))
      return
    }
    if (exceedsUploadLimit(file.size)) {
      setFormFault(validationFault(uploadLimitError("截图")))
      return
    }
    setUploading(true)
    setFormFault(null)
    try {
      const key = await uploadUserFeedbackAttachment(file)
      setAttachmentKey(key)
      setAttachmentName(file.name)
    } catch (cause) {
      setFormFault(faultFromError(cause, { intent: "upload" }))
    } finally {
      setUploading(false)
    }
  }

  /**
   * 移除已上传的截图：只清掉表单里的引用，不删 COS 对象——
   * 后端没有「未提交截图的删除」端点，这里也不假装删了。
   */
  function clearAttachment() {
    setAttachmentKey(null)
    setAttachmentName("")
  }

  const onSubmit = useFormSubmit(async () => {
    setFormFault(null)
    if (!category) {
      setFormFault(validationFault("先选一个分类——管理员据此分派处理。"))
      return
    }
    const checked = validateFeedbackContent(content)
    if (!checked.ok) {
      setFormFault(validationFault(checked.message))
      return
    }
    if (targetKind && !targetId) {
      setFormFault(validationFault("选了关联类型，还要挑一个具体对象。"))
      return
    }

    setSubmitting(true)
    try {
      await submitUserFeedback({
        category,
        content: checked.content,
        ...(targetKind && targetId ? { targetType: targetKind, targetId } : {}),
        ...(attachmentKey ? { attachmentObjectKey: attachmentKey } : {}),
      })
      setCategory("")
      setContent("")
      setTargetKind("")
      setTargetId("")
      setAttachmentKey(null)
      setAttachmentName("")
      setMinePage(1)
      void queryClient.invalidateQueries({ queryKey: ["feedback", "mine"] })
      push(infoFault("反馈已提交，处理进度会出现在下面。"))
    } catch (cause) {
      setFormFault(faultFromError(cause, { intent: "generic" }))
    } finally {
      setSubmitting(false)
    }
  })

  /* ── 我的反馈 ─────────────────────────────────────────── */
  const mineKey = queryKeys.feedback.mine(scope, { current: minePage, pageSize: MINE_PAGE_SIZE })
  const mineQuery = useQuery({
    queryKey: mineKey,
    queryFn: ({ signal }) =>
      listMyUserFeedback({ current: minePage, pageSize: MINE_PAGE_SIZE }, signal),
    enabled: authed,
  })
  const mine = mineQuery.data
  const items = mine?.records ?? []
  const mineLoading = mineQuery.isPending
  // 后台重取失败时保留已有列表；只有拿不到数据才整块出故障面板。
  const mineFault =
    !mineLoading && mine === undefined && mineQuery.error
      ? describeError(mineQuery.error)
      : null
  const pages = Math.max(1, mine?.pages ?? 1)

  /** 截图的限时链接按需现签（5 分钟有效），不在列表里预取。 */
  const [attachmentUrls, setAttachmentUrls] = useState<Record<string, string>>({})
  const [attachmentLoadingId, setAttachmentLoadingId] = useState<string | null>(null)

  async function loadAttachment(id: string) {
    if (attachmentUrls[id] || attachmentLoadingId) return
    setAttachmentLoadingId(id)
    try {
      const url = await getMyUserFeedbackAttachmentUrl(id)
      setAttachmentUrls((prev) => ({ ...prev, [id]: url }))
    } catch (cause) {
      push(faultFromError(cause, { intent: "load" }))
    } finally {
      setAttachmentLoadingId(null)
    }
  }

  if (status === "anon") {
    return (
      <div className="is-field is-brand-page">
        <ProductHeader scene="clinical" />
        <main id="main-content" tabIndex={-1} className="wrap feedback">
          <SectionHead code="06" kicker="意见反馈" en="FEEDBACK · YOUR VOICE" />
          <div className="feedback__empty">
            <EmptyFrame label="未登录" />
            <p className="feedback__empty-title">登录后才能提交反馈</p>
            <p className="feedback__empty-body">反馈与处理进度都跟着账号走，登录后才能看到回复。</p>
            <Link className="btn" to="/login?redirect=%2Ffeedback">
              去登录 <ArrowUpRight className="btn__key" size={13} strokeWidth={2} aria-hidden="true" />
            </Link>
          </div>
        </main>
        <DisclaimerBar />
      </div>
    )
  }

  const taskOptions = tasksQuery.data ?? []
  const sessionOptions = sessionsQuery.data ?? []

  return (
    <div className="is-field is-brand-page">
      <ProductHeader scene="clinical" />

      <main id="main-content" tabIndex={-1} className="wrap feedback">
        <SectionHead code="06" kicker="意见反馈" en="FEEDBACK · YOUR VOICE" />

        <div className="feedback__intro">
          <p className="overline">Feedback · 意见反馈</p>
          <h1 className="feedback__title">意见反馈</h1>
          <p className="lead">
            对判读结论有疑问、对界面有意见、想加一个功能，都可以写在这里。管理员会看到并回复，
            处理进度与回复会出现在下方「我的反馈」。
          </p>
        </div>

        <section className="feedback__panel" aria-labelledby="feedback-form-title">
          <div className="feedback__panel-head">
            <h2 className="feedback__panel-title" id="feedback-form-title">
              提交反馈 · NEW
            </h2>
            <span className="feedback__panel-note num">1–{FEEDBACK_TEXT_LIMIT} 字 · 截图可选</span>
          </div>

          <form className="feedback__form" onSubmit={onSubmit} noValidate>
            <fieldset className="feedback__fieldset">
              <legend className="feedback__label">分类 · CATEGORY</legend>
              <div className="feedback__categories">
                {FEEDBACK_CATEGORIES.map((option) => (
                  <label className="feedback__category" key={option.value}>
                    <input
                      type="radio"
                      name="feedback-category"
                      value={option.value}
                      checked={category === option.value}
                      onChange={() => setCategory(option.value)}
                    />
                    <span className="feedback__category-body">
                      <span className="feedback__category-label">{option.label}</span>
                      <span className="feedback__category-hint">{option.hint}</span>
                    </span>
                  </label>
                ))}
              </div>
            </fieldset>

            <label className="feedback__field">
              <span className="feedback__label">正文 · MESSAGE</span>
              <textarea
                className="feedback__input feedback__input--area"
                value={content}
                rows={6}
                maxLength={FEEDBACK_TEXT_LIMIT}
                onChange={(event) => setContent(event.target.value)}
                placeholder="说清楚发生了什么、你期望怎样。附上关联的判读记录或会话会更好定位。"
                aria-describedby="feedback-content-count"
              />
              <span className="feedback__counter num" id="feedback-content-count">
                {content.length} / {FEEDBACK_TEXT_LIMIT}
              </span>
            </label>

            <div className="feedback__field">
              <span className="feedback__label">关联对象 · CONTEXT（选填）</span>
              <div className="feedback__target">
                <label className="feedback__target-kind">
                  <span className="feedback__sublabel">类型</span>
                  <select
                    className="feedback__input"
                    value={targetKind}
                    onChange={(event) => pickTargetKind(event.target.value as TargetKind)}
                  >
                    <option value="">不关联</option>
                    <option value="DIAGNOSIS_TASK">判读任务</option>
                    <option value="AGENT_SESSION">Agent 会话</option>
                  </select>
                </label>

                {targetKind === "DIAGNOSIS_TASK" ? (
                  <label className="feedback__target-kind">
                    <span className="feedback__sublabel">判读任务</span>
                    <select
                      className="feedback__input"
                      value={targetId}
                      onChange={(event) => setTargetId(event.target.value)}
                      disabled={tasksQuery.isPending}
                    >
                      <option value="">
                        {tasksQuery.isPending
                          ? "读取中…"
                          : tasksQuery.isError
                            ? "任务读取失败，稍后重试"
                            : taskOptions.length === 0
                              ? "还没有判读任务"
                              : "选择一条判读任务"}
                      </option>
                      {taskOptions.map((task) => (
                        <option key={task.taskId} value={task.taskId}>
                          {imageTaskStatusLabel(task.status) + " · 任务 " + task.taskId.slice(-6)}
                        </option>
                      ))}
                    </select>
                  </label>
                ) : null}

                {targetKind === "AGENT_SESSION" ? (
                  <label className="feedback__target-kind">
                    <span className="feedback__sublabel">Agent 会话</span>
                    <select
                      className="feedback__input"
                      value={targetId}
                      onChange={(event) => setTargetId(event.target.value)}
                      disabled={sessionsQuery.isPending}
                    >
                      <option value="">
                        {sessionsQuery.isPending
                          ? "读取中…"
                          : sessionOptions.length === 0
                            ? "还没有会话"
                            : "选择一个会话"}
                      </option>
                      {sessionOptions.map((session) => (
                        <option key={session.sessionId} value={session.sessionId}>
                          {(session.title || "未命名会话") + " · " + formatTimestamp(session.updatedTime)}
                        </option>
                      ))}
                    </select>
                  </label>
                ) : null}
              </div>
              <span className="feedback__hint">
                只能关联你自己的判读任务或会话；不关联也可以提交。
              </span>
            </div>

            <div className="feedback__field">
              <span className="feedback__label">截图 · SCREENSHOT（选填）</span>
              <div className="feedback__upload">
                <button
                  type="button"
                  className="btn btn--sm"
                  disabled={uploading || submitting}
                  onClick={() => fileRef.current?.click()}
                >
                  <Paperclip size={14} strokeWidth={2} aria-hidden="true" />
                  {uploading ? "上传中…" : attachmentKey ? "更换截图" : "选择截图"}
                </button>
                <input
                  ref={fileRef}
                  type="file"
                  accept="image/jpeg,image/png"
                  className="sr-only"
                  onChange={(event) => void onPickFile(event)}
                />
                <span className="feedback__hint">
                  {attachmentKey
                    ? `已上传 · ${attachmentName || "截图"}`
                    : `JPG / PNG，${UPLOAD_LIMIT_HINT}`}
                </span>
                {attachmentKey ? (
                  <button type="button" className="link feedback__upload-remove" onClick={clearAttachment}>
                    <X size={13} strokeWidth={2} aria-hidden="true" />
                    移除
                  </button>
                ) : null}
              </div>
            </div>

            {formFault ? (
              <IrisFormAlert
                severity={formFault.severity}
                title={formFault.title}
                detail={formFault.detail}
              />
            ) : null}

            <div className="feedback__form-foot">
              <button className="btn" type="submit" disabled={submitting || uploading}>
                {submitting ? "提交中…" : "提交反馈"}
                <span className="btn__key">↵</span>
              </button>
              <span className="feedback__hint">提交后可在下方查看处理进度与回复。</span>
            </div>
          </form>
        </section>

        <section className="feedback__panel feedback__panel--list" aria-labelledby="feedback-mine-title">
          <div className="feedback__panel-head">
            <h2 className="feedback__panel-title" id="feedback-mine-title">
              我的反馈 · HISTORY
            </h2>
            <span className="feedback__panel-note num">
              {mine ? `共 ${mine.total ?? 0} 条` : "读取中"}
            </span>
          </div>

          {mineFault ? (
            <FaultPanel
              code={mineFault.code}
              title="我的反馈没有读出来"
              body={mineFault.text}
              endpoint="POST /feedback/mine"
              onRetry={() => void mineQuery.refetch()}
            />
          ) : null}

          {!mineFault && mineLoading ? (
            <p className="feedback__state">读取中…</p>
          ) : null}

          {!mineFault && !mineLoading && items.length === 0 ? (
            <div className="feedback__empty">
              <EmptyFrame label="暂无反馈" />
              <p className="feedback__empty-title">还没有提交过反馈</p>
              <p className="feedback__empty-body">提交第一条后，状态与管理员回复会显示在这里。</p>
            </div>
          ) : null}

          {!mineFault && !mineLoading && items.length > 0 ? (
            <>
              <ul className="feedback__list">
                {items.map((item) => (
                  <li className="feedback__item" key={item.id}>
                    <div className="feedback__item-head">
                      <RiskTag risk={feedbackStatusRisk(item.status)}>
                        {feedbackStatusLabel(item.status)}
                      </RiskTag>
                      <span className="feedback__item-cat">{feedbackCategoryLabel(item.category)}</span>
                      <span className="feedback__item-time num">
                        {formatTimestamp(item.createTime)}
                      </span>
                    </div>

                    <p className="feedback__item-content">{item.content}</p>

                    <div className="feedback__item-meta">
                      <span>{feedbackTargetLabel(item.targetType, item.targetId)}</span>
                      {item.hasAttachment ? (
                        attachmentUrls[item.id] ? (
                          <a
                            className="link"
                            href={attachmentUrls[item.id]}
                            target="_blank"
                            rel="noreferrer"
                          >
                            在新窗口打开截图
                            <ArrowUpRight size={12} strokeWidth={2} aria-hidden="true" />
                          </a>
                        ) : (
                          <button
                            type="button"
                            className="link"
                            disabled={attachmentLoadingId === item.id}
                            onClick={() => void loadAttachment(item.id)}
                          >
                            {attachmentLoadingId === item.id ? "生成链接中…" : "查看截图"}
                          </button>
                        )
                      ) : null}
                    </div>

                    {item.reply ? (
                      <div className="feedback__reply">
                        <span className="feedback__reply-label">管理员回复</span>
                        <p className="feedback__reply-text">{item.reply}</p>
                        {item.repliedAt ? (
                          <span className="feedback__reply-time num">
                            {formatTimestamp(item.repliedAt)}
                          </span>
                        ) : null}
                      </div>
                    ) : (
                      <p className="feedback__item-note">{feedbackStatusHint(item.status)}</p>
                    )}
                  </li>
                ))}
              </ul>

              <nav className="feedback__pager" aria-label="我的反馈分页">
                <button
                  type="button"
                  className="feedback__pager-step"
                  disabled={minePage <= 1}
                  onClick={() => setMinePage(minePage - 1)}
                >
                  ← 上一页
                </button>
                <span className="feedback__pager-state num">
                  {minePage} / {pages}
                </span>
                <button
                  type="button"
                  className="feedback__pager-step"
                  disabled={minePage >= pages}
                  onClick={() => setMinePage(minePage + 1)}
                >
                  下一页 →
                </button>
              </nav>
            </>
          ) : null}
        </section>
      </main>

      <DisclaimerBar />
    </div>
  )
}
