/**
 * 咨询回答里的证据引用。
 *
 * <p>正文里的引用标记是 `[[<documentId>#<chunkIndex>]]`：键由检索工具真实产出，
 * 模型只负责把它标在支持该句的位置。**能不能核验由端上判定**——键出现在本回合
 * 真实检索到的资源里才算数，否则这一处标成「未核验」。不把模型写出来的任何编号
 * 直接当成依据（与 web/src/lib/markdown.ts 同一口径）。
 *
 * <p>纯函数，改动必须补 `tests/citations.test.ts`。
 */

/** 证据键的字面形状；含空白或标点的标记不当作引用，按原文保留。 */
const CITE_MARKER_RE = /\[\[([A-Za-z0-9_.:-]+)#(\d+)\]\]/g

/** 标记自身的长度上限：挡住正文里意外出现的长 `[[...]]`。 */
const CITE_MARKER_MAX = 120

/** 后端 `ResourceRef` 的最小形状（只取端上用得到的字段）。 */
export interface ResourceRefLike {
  kind?: string | null
  documentId?: string | null
  chunkIndex?: number | null
  title?: string | null
}

export interface CitationRef {
  /** 角标编号，从 1 开始，顺序即正文里出现的顺序。 */
  n: number
  key: string
  title: string
}

export interface CitationText {
  /** 去掉标记、换上角标后的正文。 */
  text: string
  refs: CitationRef[]
  /** 本轮核验不出来的引用处数。 */
  unverified: number
}

/**
 * 工具终态的资源 → 可核验的文档证据（证据键 → 文档标题）。
 *
 * <p>只有**明确成功**的工具调用才算证据：未知或缺失的状态一律不带来源，
 * 宁可少一条也不伪造（与 web 端 `evidenceResources` 同规则）。
 */
export function evidenceResources(
  status: string | undefined,
  raw: unknown,
): ResourceRefLike[] {
  if (status === undefined) return Array.isArray(raw) ? (raw as ResourceRefLike[]) : []
  const normalized = status.toLowerCase()
  if (normalized !== 'success' && normalized !== 'succeeded') return []
  return Array.isArray(raw) ? (raw as ResourceRefLike[]) : []
}

/**
 * 文档类资源 → 证据键（`documentId#chunkIndex`）→ 标题。
 *
 * <p>证据粒度是**片段**而不是文档：没有片段序号的条目不构成可核验证据，
 * 直接丢掉——它只能说明「查过这份文档」，不能说明「这一句有依据」。
 */
export function documentCitationMap(resources: ResourceRefLike[] | null | undefined): Map<string, string> {
  const out = new Map<string, string>()
  if (!Array.isArray(resources)) return out
  for (const resource of resources) {
    if (!resource || resource.kind !== 'document') continue
    const documentId = typeof resource.documentId === 'string' ? resource.documentId : null
    const chunkIndex = typeof resource.chunkIndex === 'number' ? resource.chunkIndex : null
    if (!documentId || chunkIndex === null) continue
    const title = typeof resource.title === 'string' && resource.title.trim() ? resource.title.trim() : `文档 ${documentId}`
    out.set(`${documentId}#${chunkIndex}`, title)
  }
  return out
}

/**
 * 历史里的工具终态行 → 资源数组。
 *
 * <p>落库的正文是 `{"toolCallId","name","status","output","resources"}` 负载；
 * 解析不出来就当没有资源，不猜。
 */
export function parseToolResultResources(content?: string | null): ResourceRefLike[] {
  if (!content) return []
  try {
    const payload = JSON.parse(content) as { status?: string; resources?: unknown }
    if (!payload || typeof payload !== 'object') return []
    return evidenceResources(payload.status, payload.resources)
  } catch {
    // 旧数据可能是纯文本工具输出：没有结构化资源，就当没有来源。
    return []
  }
}

/**
 * 正文 → 带角标的正文 + 可核验引用表。
 *
 * <p>核验不出来的标记留在正文里、标成「未核验」：医学正文里不能把没有依据的
 * 引用悄悄抹掉——读者要看得见这一句没有依据。
 */
export function renderCitationText(content: string, known: Map<string, string>): CitationText {
  const refs: CitationRef[] = []
  const numbers = new Map<string, number>()
  let unverified = 0

  const text = (content ?? '').replace(CITE_MARKER_RE, (match, rawKey: string, rawIndex: string) => {
    if (rawKey.length > CITE_MARKER_MAX) return match
    const key = `${rawKey}#${rawIndex}`
    const title = known.get(key)
    if (!title) {
      unverified += 1
      return '[未核验]'
    }
    let n = numbers.get(key)
    if (n === undefined) {
      n = refs.length + 1
      numbers.set(key, n)
      refs.push({ n, key, title })
    }
    return `[${n}]`
  })

  return { text, refs, unverified }
}
