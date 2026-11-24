import { describe, expect, it } from 'vitest'
import {
  documentCitationMap,
  evidenceResources,
  parseToolResultResources,
  renderCitationText,
} from '@/lib/citations'

describe('工具终态 → 证据资源', () => {
  it('只有成功的调用才算证据', () => {
    const raw = [{ kind: 'document', documentId: '42', chunkIndex: 3, title: '干眼症诊疗共识' }]
    expect(evidenceResources('SUCCEEDED', raw)).toHaveLength(1)
    expect(evidenceResources('success', raw)).toHaveLength(1)
    expect(evidenceResources('FAILED', raw)).toHaveLength(0)
    expect(evidenceResources('TIMEOUT', raw)).toHaveLength(0)
  })

  it('状态缺失时按旧数据处理，但不凭空造资源', () => {
    expect(evidenceResources(undefined, [{ kind: 'document' }])).toHaveLength(1)
    expect(evidenceResources(undefined, undefined)).toHaveLength(0)
    expect(evidenceResources(undefined, 'not-an-array')).toHaveLength(0)
  })
})

describe('资源 → 证据键', () => {
  it('证据粒度是片段：没有片段序号的不算可核验证据', () => {
    const map = documentCitationMap([
      { kind: 'document', documentId: '42', chunkIndex: 3, title: '干眼症诊疗共识' },
      { kind: 'document', documentId: '42', title: '只有文档级' },
      { kind: 'image', documentId: '42', chunkIndex: 1, title: '图' },
    ])
    expect(map.size).toBe(1)
    expect(map.get('42#3')).toBe('干眼症诊疗共识')
  })

  it('标题为空时给一个占位，不给空白', () => {
    const map = documentCitationMap([{ kind: 'document', documentId: '7', chunkIndex: 0, title: '  ' }])
    expect(map.get('7#0')).toBe('文档 7')
  })
})

describe('历史工具终态行 → 资源', () => {
  it('解析落库的负载', () => {
    const refs = parseToolResultResources(
      JSON.stringify({
        toolCallId: 't1',
        name: 'doc_search',
        status: 'SUCCEEDED',
        output: '命中 1 段',
        resources: [{ kind: 'document', documentId: '9', chunkIndex: 0, title: '白内障诊疗指南' }],
      }),
    )
    expect(documentCitationMap(refs).get('9#0')).toBe('白内障诊疗指南')
  })

  it('旧数据是纯文本输出：解析不出就当没有来源', () => {
    expect(parseToolResultResources('这是一段工具输出文本')).toEqual([])
    expect(parseToolResultResources(undefined)).toEqual([])
  })

  it('失败的调用不带资源', () => {
    const refs = parseToolResultResources(
      JSON.stringify({ status: 'FAILED', resources: [{ kind: 'document', documentId: '9', chunkIndex: 0 }] }),
    )
    expect(refs).toEqual([])
  })
})

describe('正文引用标记', () => {
  it('核验得出来的换成角标，并给出引用表', () => {
    const known = new Map([['42#3', '干眼症诊疗共识']])
    const rendered = renderCitationText('依据[[42#3]]，建议复查。', known)
    expect(rendered.text).toBe('依据[1]，建议复查。')
    expect(rendered.refs).toHaveLength(1)
    expect(rendered.refs[0]).toMatchObject({ n: 1, key: '42#3', title: '干眼症诊疗共识' })
    expect(rendered.unverified).toBe(0)
  })

  it('同一处引用重复出现，角标沿用同一个号', () => {
    const known = new Map([['42#3', '干眼症诊疗共识']])
    const rendered = renderCitationText('[[42#3]] 与 [[42#3]]', known)
    expect(rendered.text).toBe('[1] 与 [1]')
    expect(rendered.refs).toHaveLength(1)
  })

  it('核验不出来的留在正文里，标成未核验', () => {
    const rendered = renderCitationText('依据[[99#1]]。', new Map())
    expect(rendered.text).toBe('依据[未核验]。')
    expect(rendered.refs).toHaveLength(0)
    expect(rendered.unverified).toBe(1)
  })

  it('不像引用的方括号按原文保留', () => {
    const rendered = renderCitationText('数组 a[[0]] 与 [[非证据]]', new Map())
    expect(rendered.text).toBe('数组 a[[0]] 与 [[非证据]]')
    expect(rendered.unverified).toBe(0)
  })
})
