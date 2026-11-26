import type { ReactNode } from "react"
import { useQuery } from "@tanstack/react-query"
import { FaultNote } from "@/components/fault"
import { RiskTag } from "@/components/RiskTag"
import { describeError } from "@/lib/api"
import { formatTimestamp } from "@/lib/domain"
import { fault } from "@/lib/faults"
import { getCollectionSchedule } from "@/lib/newsWorkbenchApi"
import { queryKeys } from "@/lib/queryKeys"

const DECISIONS: Record<string, string> = {
  DISABLED: "已停用",
  AWAITING_EVALUATION: "待首次调度",
  QUEUED: "最近一次已入队",
  CAUGHT_UP: "已追至安全边界",
  WAITING_FOR_TASK: "有窗口待完成",
  FAILED_WINDOW: "失败窗口阻塞",
  INITIAL_CURSOR_REQUIRED: "待登记初始游标",
  BACKFILL_LIMIT: "超出回灌日期上限",
  CONFIG_VERSION_STALE: "部署的配置版本已过期",
  CONFIG_CURSOR_MISMATCH: "新旧版本游标未对齐",
  IDENTITY_CONFLICT: "任务 ID 冲突",
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="admin-workbench__factrow">
      <dt className="admin-workbench__factkey">{label}</dt>
      <dd className="admin-workbench__factval">{children}</dd>
    </div>
  )
}

export function AdminNewsCollectionSchedule() {
  const query = useQuery({
    queryKey: queryKeys.admin.newsWorkbench.schedule,
    queryFn: getCollectionSchedule,
    refetchInterval: 60_000,
  })
  if (query.isPending) {
    return (
      <p className="admin-workbench__note" role="status">正在加载自动采集调度…</p>
    )
  }
  const error = query.error ? describeError(query.error) : null
  const errorNote = error
    ? <FaultNote fault={fault({ severity: "attention", kind: "client", title: error.text, code: error.code })} />
    : null
  const schedule = query.data
  if (!schedule) return errorNote
  const running = schedule.ingestionEnabled && schedule.scheduleEnabled
  return (
    <section className="admin-workbench__sourcecard" aria-label="自动采集调度">
      <div className="admin-workbench__sourcehead">
        <h2 className="admin-workbench__sourcename">自动采集调度</h2>
        <RiskTag risk={running ? "normal" : "attention"}>
          {running ? "调度已开启" : "调度未运行"}
        </RiskTag>
      </div>
      <p className="admin-workbench__note">
        采集控制面{schedule.ingestionEnabled ? "开启" : "关闭"} · 自动规划{schedule.scheduleEnabled ? "开启" : "关闭"}
      </p>
      {errorNote}
      {schedule.sources.length === 0 && <p className="admin-workbench__note">暂无信源</p>}
      <div className="admin-workbench__sources">
        {schedule.sources.map((source) => (
          <article key={source.source} className="admin-workbench__sourcecard">
            <h3 className="admin-workbench__sourcename">{source.source}</h3>
            <dl className="admin-workbench__facts">
              <Fact label="自动调度">
                {DECISIONS[source.status] ?? source.status}
                {source.configuredVersion && ` · 配置 v${source.configuredVersion}`}
              </Fact>
              <Fact label="开关">
                信源{source.sourceEnabled ? "启用" : "停用"} · 策略{source.policyEnabled ? "启用" : "停用"}
              </Fact>
              <Fact label="已提交游标">
                {source.activeVersion ? `v${source.activeVersion} · ${source.committedCursor ?? "—"}` : "—"}
              </Fact>
              <Fact label="上次调度">{formatTimestamp(source.lastRunAt ?? undefined)}</Fact>
              <Fact label="下次计划">{formatTimestamp(source.nextRunAt ?? undefined)}</Fact>
              <Fact label="积压 / 失败 / 退避重试">
                {source.backlog} / {source.failed} / {source.retryScheduled}
              </Fact>
              {source.relatedTaskId && <Fact label="最近入队或阻塞任务">{source.relatedTaskId}</Fact>}
              {source.lastFailureCode && <Fact label="最近失败码">{source.lastFailureCode}</Fact>}
              <Fact label="最近完成的自动窗口">
                {source.lastCompletedEndBefore
                  ? `${source.lastCompletedStartOn} → ${source.lastCompletedEndBefore} · ${source.lastCompletedRecordCount} 条`
                  : "—"}
              </Fact>
            </dl>
          </article>
        ))}
      </div>
      <h3 className="admin-workbench__sectiontitle">Worker 最近领取请求</h3>
      {schedule.workers.length === 0 ? (
        <p className="admin-workbench__note">尚无已认证的领取记录</p>
      ) : (
        <ul className="admin-workbench__cursorlist">
          {schedule.workers.map((worker) => (
            <li key={worker.workerId} className="admin-workbench__cursoritem">
              <span>{worker.workerId} · 请求 {formatTimestamp(worker.lastPollAt)}</span>
              <span>
                最近领取 {formatTimestamp(worker.lastClaimAt ?? undefined)}
                {worker.lastTaskId && ` · ${worker.lastTaskId}`}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
