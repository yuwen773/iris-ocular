import { useState, type FormEvent } from "react"
import { useQuery } from "@tanstack/react-query"
import { Link } from "react-router-dom"

import { describeError } from "@/lib/api"
import { getNewsIssuePreview } from "@/lib/newsWorkbenchApi"
import { queryKeys } from "@/lib/queryKeys"

export function AdminNewsIssuePreview() {
  const [startOn, setStartOn] = useState("")
  const [endOn, setEndOn] = useState("")
  const [submitted, setSubmitted] = useState<{
    startOn: string
    endOn: string
  } | null>(null)
  const query = useQuery({
    queryKey: queryKeys.admin.newsWorkbench.issuePreview(
      submitted?.startOn ?? "",
      submitted?.endOn ?? ""
    ),
    queryFn: () => getNewsIssuePreview(submitted!.startOn, submitted!.endOn),
    enabled: submitted !== null,
  })

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitted?.startOn === startOn && submitted.endOn === endOn) {
      void query.refetch()
    } else {
      setSubmitted({ startOn, endOn })
    }
  }

  const report = query.data
  return (
    <section
      className="admin-workbench__section admin-workbench__issue"
      aria-label="资讯刊物预览"
    >
      <form className="admin-workbench__toolbar" onSubmit={submit}>
        <div className="admin-workbench__filters">
          <label className="admin-workbench__field">
            开始发布日期
            <input
              className="admin-workbench__input"
              type="date"
              required
              value={startOn}
              max={endOn || undefined}
              onChange={(event) => setStartOn(event.target.value)}
            />
          </label>
          <label className="admin-workbench__field">
            结束发布日期
            <input
              className="admin-workbench__input"
              type="date"
              required
              value={endOn}
              min={startOn || undefined}
              onChange={(event) => setEndOn(event.target.value)}
            />
          </label>
        </div>
        <button
          className="admin-btn admin-btn--primary"
          type="submit"
          disabled={query.isFetching}
        >
          生成预览
        </button>
      </form>
      {query.isFetching ? <p role="status">正在读取已发布资讯…</p> : null}
      {!query.isFetching && query.error ? (
        <p role="alert">{describeError(query.error).text}</p>
      ) : null}
      {!query.isFetching && !query.error && report ? (
        <>
          <p className="admin-workbench__note" role="status">
            {report.startOn} 至 {report.endOn} · {report.articleCount}{" "}
            篇已发布资讯 · {report.entries.length} 个条目
          </p>
          {report.entries.length === 0 ? (
            <p>该日期范围内暂无可追溯的已发布资讯。</p>
          ) : (
            <ol className="admin-workbench__revlist">
              {report.entries.map((entry) => {
                const lead = entry.articles[0]
                return (
                  <li className="admin-workbench__revcard" key={lead.newsId}>
                    <h3>{lead.title}</h3>
                    {lead.summary ? <p>{lead.summary}</p> : null}
                    {entry.identifierConflict ? (
                      <p>来源标识冲突，已单列</p>
                    ) : null}
                    <ul className="admin-workbench__cursorlist">
                      {entry.articles.map((article) => (
                        <li
                          className="admin-workbench__cursoritem"
                          key={article.newsId}
                        >
                          <Link to={`/admin/news/${article.newsId}/edit`}>
                            {article.title}
                          </Link>
                          <div className="admin-workbench__mono">
                            {article.source} · {article.sourceRecordId} ·
                            发布修订 {article.revisionNo} · 资料修订{" "}
                            {article.materialRevisionId}
                            {article.pmid ? ` · PMID ${article.pmid}` : ""}
                            {article.doi ? ` · DOI ${article.doi}` : ""}
                          </div>
                        </li>
                      ))}
                    </ul>
                  </li>
                )
              })}
            </ol>
          )}
        </>
      ) : null}
    </section>
  )
}
